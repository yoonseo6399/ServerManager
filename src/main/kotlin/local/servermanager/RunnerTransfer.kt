package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlin.io.path.*

data class RunnerFileManifest(val files:Map<String,CloudFile>,val directories:Set<String>)
data class RunnerTransferBegin(val manifest:RunnerFileManifest,val importRequest:String?=null,val resumeProtocol:Int=0)
data class RunnerTransferPlan(val transferId:String,val changedFiles:Set<String>,val offsets:Map<String,Long> = emptyMap(),val completedFiles:Int=0,val completedBytes:Long=0,val totalFiles:Int=0,val totalBytes:Long=0)
private data class StoredTransfer(val protocol:Int=1,val runnerId:String,val instanceId:String,val context:String,val manifest:RunnerFileManifest,val before:RunnerFileManifest,val importRequest:String?,val createdAt:Long=System.currentTimeMillis())
private data class TransferReceipt(val runnerId:String,val instanceId:String,val context:String,val importRequest:String?)
private data class PendingTransfer(val id:String,val runnerId:String,val instanceId:String,val manifest:RunnerFileManifest,val changed:Set<String>,val folder:Path,
    val before:RunnerFileManifest,val importRequest:String?=null,
    val progress:MutableMap<String,Long> = mutableMapOf(),var bytes:Long=0,var files:Int=0,
    val totalBytes:Long=changed.sumOf {manifest.files.getValue(it).size})

@Service
class RunnerTransfer(private val manager:ServerManager,private val registry:RunnerRegistry) {
    private val mapper=jacksonObjectMapper()
    private val pending=java.util.concurrent.ConcurrentHashMap<String,PendingTransfer>()
    private fun record(folder:Path)=folder.resolveSibling("${folder.name}.pending.json")
    private fun store(transfer:PendingTransfer) {
        val encoded=mapper.writeValueAsString(StoredTransfer(runnerId=transfer.runnerId,instanceId=transfer.instanceId,context=manager.profileDir(transfer.instanceId).toAbsolutePath().toString(),manifest=transfer.manifest,before=transfer.before,importRequest=transfer.importRequest))
        require(encoded.toByteArray().size<=64*1024*1024);atomicText(record(transfer.folder),encoded)
    }
    private fun compatibleTicket(old:String?,current:String?)=old==current || old!=null && current!=null
    private fun restore(id:String,runner:String,manifest:RunnerFileManifest,before:RunnerFileManifest,request:String?):PendingTransfer? {
        pending.values.firstOrNull {it.instanceId==id}?.let {previous->
            check(previous.runnerId==runner && compatibleTicket(previous.importRequest,request) && previous.manifest==manifest && previous.before==before) {"중단된 전송의 실행기·요청·원본 파일 목록이 다릅니다. 원본 변경 여부를 확인하세요"}
            val saved=mapper.readValue<StoredTransfer>(record(previous.folder).toFile())
            check(saved.context==manager.profileDir(id).toAbsolutePath().toString()) {"전송 작업 폴더가 변경됐습니다"}
            return if(previous.importRequest==request)previous else previous.copy(importRequest=request).also {store(it);pending[it.id]=it}
        }
        val root=manager.root.resolve(id).resolve("runner-transfer")
        if(!root.exists())return null
        require(!Files.isSymbolicLink(root))
        val records=root.listDirectoryEntries("*.pending.json")
        check(records.size<=1) {"복수의 중단된 전송 기록이 있습니다. 기록을 보존하고 확인하세요"}
        if(records.isNotEmpty()) {
            val file=records.single();require(!Files.isSymbolicLink(file) && file.fileSize()<=64L*1024*1024)
            val saved=mapper.readValue<StoredTransfer>(file.toFile())
            check(saved.protocol==1 && saved.instanceId==id && saved.runnerId==runner && saved.context==manager.profileDir(id).toAbsolutePath().toString() && compatibleTicket(saved.importRequest,request) && saved.manifest==manifest && saved.before==before) {"중단된 전송과 실행기·파일 목록이 다릅니다. 받은 파일은 보존했습니다"}
            val transferId=file.name.removeSuffix(".pending.json");require(UUID.fromString(transferId).toString()==transferId)
            val folder=root.resolve(transferId);require(folder.isDirectory() && !Files.isSymbolicLink(folder))
            return PendingTransfer(transferId,runner,id,manifest,manifest.files.filter {(p,f)->before.files[p]!=f}.keys,folder,before,request).also {if(saved.importRequest!=request)store(it);pending[it.id]=it}
        }
        // Older managers did not persist a descriptor. Adopt exactly one cache only for
        // the current authenticated, still-pending original-folder import ticket.
        val legacy=root.listDirectoryEntries().filter {it.isDirectory(java.nio.file.LinkOption.NOFOLLOW_LINKS)}
        if(legacy.isEmpty())return null
        check(request!=null && legacy.size==1) {"이전 버전의 미완료 파일이 있습니다. 기존 가져오기 요청으로 재개하세요"}
        val folder=legacy.single();require(UUID.fromString(folder.name).toString()==folder.name && !Files.isSymbolicLink(folder))
        return PendingTransfer(folder.name,runner,id,manifest,manifest.files.filter {(p,f)->before.files[p]!=f}.keys,folder,before,request).also {store(it);pending[it.id]=it}
    }
    private fun receipt(id:String,transfer:String):Path {
        require(UUID.fromString(transfer).toString()==transfer)
        return manager.profileDir(id).resolve("transfer-receipts").resolve("$transfer.json")
    }
    fun transferState(runner:String,id:String,transfer:String):String=locked(id) {
        val complete=receipt(id,transfer)
        if(complete.exists()) {
            require(!Files.isSymbolicLink(complete) && complete.fileSize()<65536)
            val data=mapper.readValue<TransferReceipt>(complete.toFile())
            require(data.runnerId==runner && data.instanceId==id && data.context==manager.profileDir(id).toAbsolutePath().toString())
            return@locked "COMPLETE"
        }
        val file=manager.root.resolve(id).resolve("runner-transfer/$transfer.pending.json")
        if(!file.exists())return@locked "EXPIRED"
        require(!Files.isSymbolicLink(file) && file.fileSize()<=64L*1024*1024)
        val data=mapper.readValue<StoredTransfer>(file.toFile())
        require(data.runnerId==runner && data.instanceId==id && data.context==manager.profileDir(id).toAbsolutePath().toString())
        "PENDING"
    }
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private fun <T> locked(id:String,action:()->T):T=synchronized(locks.computeIfAbsent(id){Any()}) {action()}
    private fun history(id:String)=manager.profileDir(id).resolve("runner-backups")
    private fun report(transfer:PendingTransfer,phase:String,message:String="") {
        val value=FolderImportProgress(phase,transfer.files,transfer.bytes,transfer.changed.size,transfer.totalBytes,message)
        if(transfer.importRequest!=null)manager.folderImportProgress(transfer.instanceId,transfer.runnerId,transfer.importRequest,value)
        else manager.fileTransferProgress(transfer.instanceId,transfer.runnerId,value)
    }
    private val inventories=VerifiedInventory()
    private val inventoryEpoch=java.util.concurrent.ConcurrentHashMap<String,Long>()
    @jakarta.annotation.PreDestroy fun close(){inventories.close()}
    private fun invalidateInventory(id:String){inventoryEpoch.merge(id,1L,Long::plus);inventories.invalidate(id)}
    fun preparedInventory(id:String):RunnerFileManifest? {
        fun context():String {
            manager.checkTransferReady(id)
            val r=manager.runtime(id)
            synchronized(r) {
                check(r.state !in setOf("STARTING","RUNNING","STOPPING") && r.process?.isAlive!=true) {"서버를 종료한 뒤 파일 목록을 검증하세요"}
                return "${manager.serverDir(id).toRealPath()}|${r.activityVersion}|${inventoryEpoch[id]}"
            }
        }
        return inventories.request(id,manager.serverDir(id),::context)
    }
    fun inventory(id:String):RunnerFileManifest=locked(id) {
        manager.checkTransferReady(id)
        val (files,dirs)=FileInventory.collect(manager.serverDir(id))
        RunnerFileManifest(files,dirs)
    }
    fun begin(runnerId:String,instanceId:String,manifest:RunnerFileManifest,importRequest:String?=null,resumeProtocol:Int=0,prepared:RunnerFileManifest?=null):RunnerTransferPlan=locked(instanceId) {
        require(manager.instance(instanceId).runnerId==runnerId && registry.active(runnerId)?.allowedInstanceIds?.contains(instanceId)==true)
        if(importRequest!=null)manager.checkFolderImport(instanceId,runnerId,importRequest)
        else check(!manager.root.resolve(instanceId).resolve("folder-import.pending").exists()) { "기존 폴더 가져오기 작업이 진행 중입니다" }
        manager.checkTransferReady(instanceId)
        DeltaTransfer.validate(manifest,manager.serverDir(instanceId))
        DeltaTransfer.recover(manager.serverDir(instanceId),history(instanceId))
        if(importRequest!=null)manager.folderImportProgress(instanceId,runnerId,importRequest,FolderImportProgress("SCANNING",message="관리 서버의 기존 파일과 비교하고 있습니다"))
        else manager.fileTransferProgress(instanceId,runnerId,FolderImportProgress("SCANNING",message="관리 서버의 기존 파일과 비교하고 있습니다"))
        val old=prepared ?: inventory(instanceId)
        val changed=manifest.files.filter { (path,file)->old.files[path]!=file }.keys
        val cacheRoot=manager.root.resolve(instanceId).resolve("runner-transfer")
        if(old==manifest && cacheRoot.exists()) {
            for(file in cacheRoot.listDirectoryEntries("*.pending.json")) {
                require(!Files.isSymbolicLink(file) && file.fileSize()<=64L*1024*1024)
                val saved=mapper.readValue<StoredTransfer>(file.toFile());val transferId=file.name.removeSuffix(".pending.json")
                if(saved.runnerId==runnerId && compatibleTicket(saved.importRequest,importRequest) && saved.manifest==manifest && saved.context==manager.profileDir(instanceId).toAbsolutePath().toString()) {
                    // Delta application may have finished before a process interruption
                    // prevented its receipt. The full central manifest was just verified.
                    atomicText(receipt(instanceId,transferId),mapper.writeValueAsString(TransferReceipt(runnerId,instanceId,saved.context,importRequest)))
                    if(manager.root.resolve(instanceId).resolve("remote-lease").exists())manager.remoteCommitted(instanceId,runnerId,importRequest)
                    pending.remove(transferId);cacheRoot.resolve(transferId).toFile().deleteRecursively();file.deleteIfExists()
                    return@locked RunnerTransferPlan(transferId,emptySet())
                }
            }
        }
        require(resumeProtocol in 0..1)
        val previous=restore(instanceId,runnerId,manifest,old,importRequest)
        val transfer=previous ?: run {
            val id=UUID.randomUUID().toString()
            val folder=manager.root.resolve(instanceId).resolve("runner-transfer").resolve(id).also {it.createDirectories()}
            PendingTransfer(id,runnerId,instanceId,manifest,changed,folder,old,importRequest).also {store(it);pending[id]=it}
        }
        report(transfer,"SCANNING",if(previous!=null)"이미 받은 파일을 검증하고 중단된 위치를 확인하고 있습니다" else "전송할 파일을 준비하고 있습니다")
        transfer.progress.clear();transfer.bytes=0;transfer.files=0
        Files.walk(transfer.folder).use {paths->paths.filter {it!=transfer.folder}.forEach {p->
            require(!Files.isSymbolicLink(p)) {"전송 캐시에 심볼릭 링크가 있습니다"}
            if(p.isRegularFile())require(transfer.folder.relativize(p).toString().replace('\\','/') in changed) {"전송 캐시에 알 수 없는 파일이 있습니다"}
            else require(p.isDirectory())
        }}
        changed.forEach {path->
            val expected=manifest.files.getValue(path);val target=FileInventory.safePath(transfer.folder,path)
            target.parent.createDirectories()
            if(!target.exists() && expected.size==0L)target.writeBytes(byteArrayOf())
            var offset=if(target.exists())target.fileSize() else 0L
            if(offset>expected.size || offset==expected.size && FileInventory.digest(target)!=expected.sha256 || resumeProtocol==0 && offset<expected.size && offset>0) {
                target.deleteIfExists();offset=0
            }
            transfer.progress[path]=offset;transfer.bytes+=offset
            if(offset==expected.size)transfer.files++
        }
        val remaining=changed.filter {transfer.progress.getValue(it)<manifest.files.getValue(it).size}.toSet()
        manager.beginTransferProgress(instanceId,transfer.bytes)
        report(transfer,"UPLOADING",if(previous!=null)"이어받기 · 받은 파일과 청크를 재사용합니다" else "변경 파일을 전송하고 있습니다")
        RunnerTransferPlan(transfer.id,remaining,if(resumeProtocol==1)remaining.associateWith {transfer.progress.getValue(it)} else emptyMap(),transfer.files,transfer.bytes,changed.size,transfer.totalBytes)
    }
    fun upload(runnerId:String,instanceId:String,transferId:String,path:String,offset:Long,bytes:ByteArray)=locked(instanceId) {
        val transfer=pending[transferId] ?: error("파일 인계가 만료되었습니다")
        require(transfer.runnerId==runnerId && transfer.instanceId==instanceId)
        transfer.importRequest?.let {manager.checkFolderImport(instanceId,runnerId,it)}
        require(path in transfer.changed && bytes.size in 1..4*1024*1024)
        val file=transfer.manifest.files.getValue(path)
        val progress=transfer.progress[path] ?: 0L
        check(offset==progress && offset+bytes.size<=file.size) { "파일 청크 순서가 맞지 않습니다" }
        val target=FileInventory.safePath(transfer.folder,path)
        target.parent.createDirectories()
        FileChannel.open(target,CREATE,WRITE).use { channel -> channel.position(offset);val remaining=ByteBuffer.wrap(bytes);while(remaining.hasRemaining())channel.write(remaining) }
        transfer.progress[path]=offset+bytes.size
        transfer.bytes+=bytes.size
        if(offset+bytes.size==file.size)transfer.files++
        report(transfer,"UPLOADING")
    }
    fun batch(runner:String,id:String,transferId:String,bytes:ByteArray)=locked(id) {
        val parts=TransferBatch.decode(bytes)
        val transfer=pending[transferId] ?: error("먼저 전송을 재개하세요")
        require(transfer.runnerId==runner && transfer.instanceId==id)
        transfer.importRequest?.let {manager.checkFolderImport(id,runner,it)}
        parts.forEach {part->
            require(part.path in transfer.changed)
            FileInventory.safePath(transfer.folder,part.path)
            val file=transfer.manifest.files.getValue(part.path)
            require(part.offset==(transfer.progress[part.path] ?: 0) && part.bytes.size>0 && part.offset+part.bytes.size==file.size && file.size<=TransferBatch.SMALL_FILE)
        }
        parts.forEach {upload(runner,id,transferId,it.path,it.offset,it.bytes)}
    }
    fun batchFiles(id:String,paths:List<String>):ByteArray=locked(id) {
        require(paths.size in 1..TransferBatch.MAX_FILES && paths.distinct().size==paths.size)
        var total=8L
        val files=paths.map {path->val file=manager.path(id,path);require(file.isRegularFile() && file.fileSize()<=TransferBatch.SMALL_FILE);total+=file.fileSize()+path.toByteArray().size+16;require(total<=TransferBatch.MAX_BYTES);path to file}
        TransferBatch.encode(files.map {(path,file)->TransferPart(path,0,file.inputStream().use {it.readNBytes(TransferBatch.SMALL_FILE+1)})})
    }
    fun commit(runnerId:String,instanceId:String,transferId:String)=locked(instanceId) {
        if(transferState(runnerId,instanceId,transferId)=="COMPLETE") {
            val done=mapper.readValue<TransferReceipt>(receipt(instanceId,transferId).toFile())
            if(manager.root.resolve(instanceId).resolve("remote-lease").exists())manager.remoteCommitted(instanceId,runnerId,done.importRequest)
            return@locked
        }
        val transfer=pending[transferId] ?: error("관리 서버 재시작 후에는 먼저 파일 목록으로 전송을 재개하세요")
        require(transfer.runnerId==runnerId && transfer.instanceId==instanceId)
        fun checkRequest() {transfer.importRequest?.let {manager.checkFolderImport(instanceId,runnerId,it)}}
        checkRequest()
        invalidateInventory(instanceId)
        report(transfer,"VERIFYING","변경 파일을 검증하고 반영하고 있습니다")
        DeltaTransfer.apply(manager.serverDir(instanceId),transfer.folder,transfer.before,transfer.manifest,history(instanceId),::checkRequest)
        val done=receipt(instanceId,transferId)
        atomicText(done,mapper.writeValueAsString(TransferReceipt(runnerId,instanceId,manager.profileDir(instanceId).toAbsolutePath().toString(),transfer.importRequest)))
        pending.remove(transferId)
        transfer.folder.toFile().deleteRecursively();record(transfer.folder).deleteIfExists()
        done.parent.listDirectoryEntries("*.json").sortedByDescending {it.getLastModifiedTime()}.drop(20).forEach {it.deleteIfExists()}
        invalidateInventory(instanceId)
        manager.remoteCommitted(instanceId,runnerId,transfer.importRequest)
    }
    fun chunk(instanceId:String,path:String,offset:Long):ByteArray=locked(instanceId) {
        val source=manager.path(instanceId,path)
        require(source.isRegularFile() && offset>=0 && offset<=source.fileSize())
        val bytes=ByteArray(minOf(4L*1024*1024,source.fileSize()-offset).toInt())
        FileChannel.open(source).use {channel->channel.position(offset);val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())check(channel.read(buffer)>=0) {"파일이 읽는 중 바뀌었습니다"}}
        bytes
    }
}

@RestController
@RequestMapping("/api/runners/transfer")
class RunnerTransferApi(private val transfer:RunnerTransfer,private val gateway:RunnerGateway,private val manager:ServerManager) {
    private val tasks=TransferTasks()
    @jakarta.annotation.PreDestroy fun close(){tasks.close()}
    private fun pending(protocol:Int):ResponseEntity<Void> = ResponseEntity.status(if(protocol>=2)202 else 503).header("Retry-After","5").header("X-SM-Transfer-State","VERIFYING").header("Cache-Control","no-store").build()
    private fun taskKey(id:String,runner:String,operation:String,request:String)=TransferTasks.Key(id,runner,manager.profileDir(id).toAbsolutePath().toString(),operation,request)

    private fun check(runnerId:String,token:String?,instanceId:String) {
        if(!gateway.authorized(runnerId,token) || manager.instance(instanceId).runnerId!=runnerId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN,"실행기 연결 또는 서버 배정을 확인하세요")
    }
    @GetMapping("/{instanceId}/capabilities") fun capabilities(@PathVariable instanceId:String,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,Int> {
        check(runnerId,token,instanceId);return mapOf("resumeProtocol" to 1,"batchProtocol" to 1,"parallel" to TransferBatch.PARALLEL,"exitReasonProtocol" to 1,"asyncTransferProtocol" to 2)
    }
    @PutMapping("/{instanceId}/batch") fun batch(@PathVariable instanceId:String,@RequestParam transferId:String,req:jakarta.servlet.http.HttpServletRequest,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,Boolean> {
        check(runnerId,token,instanceId);val bytes=req.inputStream.readNBytes(TransferBatch.MAX_BYTES+1);require(bytes.size<=TransferBatch.MAX_BYTES);transfer.batch(runnerId,instanceId,transferId,bytes);return mapOf("ok" to true)
    }
    @PostMapping("/{instanceId}/files") fun files(@PathVariable instanceId:String,req:jakarta.servlet.http.HttpServletRequest,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):ResponseEntity<ByteArray> {
        check(runnerId,token,instanceId);val bytes=req.inputStream.readNBytes(1024*1024+1);require(bytes.size<=1024*1024);val paths=jacksonObjectMapper().readValue<List<String>>(bytes);return ResponseEntity.ok().header("Cache-Control","no-store").body(transfer.batchFiles(instanceId,paths))
    }
    @PostMapping("/{instanceId}/exit") fun exited(@PathVariable instanceId:String,@RequestBody report:RunnerExitReport,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,Boolean> {
        check(runnerId,token,instanceId);manager.remoteExited(instanceId,runnerId,report);return mapOf("ok" to true)
    }
    @GetMapping("/{instanceId}/transfer-state") fun state(@PathVariable instanceId:String,@RequestParam transferId:String,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?,@RequestParam(defaultValue="1") protocol:Int=1):Any {
        check(runnerId,token,instanceId)
        if(tasks.busy(instanceId))return pending(protocol)
        return mapOf("state" to transfer.transferState(runnerId,instanceId,transferId))
    }
    @GetMapping("/{instanceId}/import-state") fun importState(@PathVariable instanceId:String,@RequestParam request:String,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,String> {
        check(runnerId,token,instanceId);return mapOf("state" to manager.folderImportState(instanceId,runnerId,request))
    }
    @GetMapping("/{instanceId}/manifest") fun manifest(@PathVariable instanceId:String,@RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?,@RequestParam(defaultValue="1") protocol:Int=1):ResponseEntity<*> {
        check(runnerId,token,instanceId)
        if(tasks.busy(instanceId))return pending(protocol)
        val manifest=transfer.preparedInventory(instanceId) ?: return pending(protocol)
        return ResponseEntity.ok().header("Cache-Control","no-store").body(manifest)
    }
    @GetMapping("/{instanceId}/file") fun file(@PathVariable instanceId:String,@RequestParam path:String,@RequestParam offset:Long,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):ResponseEntity<ByteArray> {
        check(runnerId,token,instanceId)
        val bytes=transfer.chunk(instanceId,path,offset)
        return ResponseEntity.ok().header("Cache-Control","no-store").body(bytes)
    }
    @PostMapping("/{instanceId}/begin") fun begin(@PathVariable instanceId:String,@RequestBody request:RunnerTransferBegin,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?,@RequestParam(defaultValue="1") protocol:Int=1):Any {
        check(runnerId,token,instanceId)
        val encoded=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsBytes(request)
        val hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded))
        val key=taskKey(instanceId,runnerId,"begin",hash)
        val plan=tasks.poll(key,consume=true) {
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(30)
            var prepared=transfer.preparedInventory(instanceId)
            while(prepared==null) {
                check(System.nanoTime()<deadline) {"중앙 파일 검증 대기 시간이 초과됐습니다"}
                Thread.sleep(1000);prepared=transfer.preparedInventory(instanceId)
            }
            transfer.begin(runnerId,instanceId,request.manifest,request.importRequest,request.resumeProtocol,prepared)
        } ?: return pending(protocol)
        // Older runner ObjectMappers reject unknown fields. Their wire shape must
        // stay exact even though the manager internally restores full counters.
        return if(request.resumeProtocol==0)mapOf("transferId" to plan.transferId,"changedFiles" to plan.changedFiles) else plan
    }
    @PutMapping("/{instanceId}/chunk") fun chunk(@PathVariable instanceId:String,@RequestParam transferId:String,@RequestParam path:String,@RequestParam offset:Long,@RequestBody bytes:ByteArray,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,Boolean> {
        check(runnerId,token,instanceId);transfer.upload(runnerId,instanceId,transferId,path,offset,bytes);return mapOf("ok" to true)
    }
    @PostMapping("/{instanceId}/commit") fun commit(@PathVariable instanceId:String,@RequestParam transferId:String,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?,@RequestParam(defaultValue="1") protocol:Int=1):Any {
        check(runnerId,token,instanceId)
        return tasks.poll(taskKey(instanceId,runnerId,"commit",transferId)) {
            transfer.commit(runnerId,instanceId,transferId);mapOf("committed" to true)
        } ?: pending(protocol)
    }
}
