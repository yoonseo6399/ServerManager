package local.servermanager

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Properties
import java.util.UUID
import kotlin.io.path.*

/** Only a local installer can grant paths. The manager never supplies a filesystem path. */
class RunnerFolders(private val home:Path) {
    private fun settings(id:String):Path {
        require(UUID.fromString(id).toString()==id) { "올바른 서버 ID를 입력하세요" }
        return home.resolve("instances/$id/server-folder.properties")
    }
    private fun config(id:String):Properties? = settings(id).takeIf { it.isRegularFile() }?.let { file ->
        require(!Files.isSymbolicLink(file))
        Properties().apply { file.inputStream().use { load(it) } }
    }
    fun custom(id:String)=config(id)!=null
    fun directory(id:String):Path {
        val stored=config(id)?.getProperty("path") ?: return home.resolve("instances/$id/server")
        val path=Path.of(stored).toAbsolutePath().normalize()
        require(path.isDirectory() && !Files.isSymbolicLink(path) && path.toRealPath()==path) { "등록한 서버 폴더가 이동됐거나 링크로 바뀌었습니다. 실행기에서 경로를 다시 등록하세요" }
        val profile=if(home.exists())home.toRealPath() else home.toAbsolutePath().normalize()
        require(!path.startsWith(profile) && !profile.startsWith(path) && path.parent!=null) { "실행기 등록 데이터와 서버 폴더는 분리하세요" }
        return path
    }
    fun initial(id:String)=config(id)?.getProperty("initial")=="true"
    fun published(id:String,manifest:RunnerFileManifest) {
        val properties=config(id) ?: return
        properties.setProperty("initial","false")
        properties.setProperty("fingerprint",fingerprint(manifest))
        val file=settings(id);val temp=Files.createTempFile(file.parent,".folder-",".tmp")
        try {temp.outputStream().use {properties.store(it,"Local server folder")};Files.move(temp,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)}
        finally {temp.deleteIfExists()}
    }
    fun transferHistory(id:String):Path = if(custom(id)) directory(id).resolveSibling(".servermanager-history-$id")
        else home.resolve("instances/$id/transfer-backups")
    fun recover(id:String)=DeltaTransfer.recover(directory(id),transferHistory(id))
    fun checkStopped(id:String,onProgress:(Int,Long)->Unit={_,_->}) {
        if(!custom(id)) {if(directory(id).exists())recover(id);return}
        val base=directory(id)
        checkWorldStopped(id)
        recover(id)
        require(base.resolve("server.properties").isRegularFile()) { "server.properties가 있는 실제 마크 서버 폴더를 선택하세요" }
        val (files,dirs)=FileInventory.collectCached(base,onProgress=onProgress) // Reject links/special files before any pull, replacement or start.
        if(!initial(id)) check(config(id)!!.getProperty("fingerprint")==fingerprint(RunnerFileManifest(files,dirs))) { "원래 폴더에 외부 변경이 있습니다. 자동 덮어쓰기를 중단했습니다. 실행기 설치 창에서 폴더를 다시 등록하면 로컬 파일을 원본으로 사용합니다" }
    }
    fun checkWorldStopped(id:String) {
        val base=directory(id)
        require(base.resolve("server.properties").isRegularFile()) { "server.properties가 있는 실제 마크 서버 폴더를 선택하세요" }
        val props=Properties().apply {base.resolve("server.properties").inputStream().use {load(it)}}
        val world=props.getProperty("level-name","world")
        val lock=FileInventory.safePath(base,"$world/session.lock")
        if(lock.exists()) FileChannel.open(lock,READ,WRITE).use {channel ->
            val held=try {channel.tryLock()} catch(_:java.nio.channels.OverlappingFileLockException){null}
            check(held!=null) { "이 폴더의 Minecraft 서버가 이미 실행 중입니다. 정상 종료한 뒤 다시 시도하세요" }
            held.use { }
        }
    }
    fun arguments(id:String,instance:Instance,java:String,windows:Boolean):List<String> {
        Launchers.validate(instance.loader,instance.minecraft,instance.loaderVersion)
        val properties=config(id) ?: return Launchers.arguments(instance,directory(id),java,windows)
        val jar=properties.getProperty("jar","")
        if(jar.isBlank())return Launchers.arguments(instance,directory(id),java,windows)
        require(instance.loader in setOf("Vanilla","Paper","Fabric")) { "Forge·NeoForge는 공식 args 파일을 사용하므로 JAR 이름을 지정하지 마세요" }
        require(jar.endsWith(".jar") && '/' !in jar && '\\' !in jar && ':' !in jar)
        val file=FileInventory.safePath(directory(id),jar)
        require(file.isRegularFile() && !Files.isSymbolicLink(file)) { "선택한 서버 JAR가 없습니다: $jar" }
        return listOf(java,"-Xms512M","-Xmx${instance.memoryMb}M","-jar",file.toString(),"nogui")
    }
    fun requiredJava(id:String,instance:Instance):Int {
        if(!custom(id) || directory(id).resolve("server-manager-runtime.json").exists())return Launchers.requiredJava(instance,directory(id))
        return config(id)!!.getProperty("requiredJava","21").toInt().also {require(it in setOf(17,21,25)) { "필요 Java 버전을 확인하세요" }}
    }
    fun prepareRuntime(id:String,instance:Instance) {
        if(!custom(id) || !initial(id))return
        arguments(id,instance,instance.javaPath,System.getProperty("os.name").startsWith("Windows"))
        val required=requiredJava(id,instance)
        val file=directory(id).resolve("server-manager-runtime.json")
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val node=if(file.exists())mapper.readTree(file.readText()) as com.fasterxml.jackson.databind.node.ObjectNode else mapper.createObjectNode()
        node.put("loader",instance.loader).put("minecraft",instance.minecraft).put("loaderVersion",instance.loaderVersion).put("requiredJava",required).put("source","local-existing-folder")
        config(id)!!.getProperty("jar","").takeIf {it.isNotBlank()}?.let {node.put("entryJar",it)}
        val temp=Files.createTempFile(file.parent,".runtime-",".tmp")
        try {temp.writeBytes(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));Files.move(temp,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)}
        finally {temp.deleteIfExists()}
    }
    fun stage(id:String):Path = if(custom(id)) Files.createTempDirectory(directory(id).parent,".servermanager-stage-")
        else home.resolve("instances/$id/stage-${UUID.randomUUID()}").also {it.createDirectories()}
    fun backup(id:String):Path = if(custom(id)) directory(id).resolveSibling(".servermanager-backup-$id-${UUID.randomUUID()}")
        else home.resolve("instances/$id/backup-${UUID.randomUUID()}")
    private fun fingerprint(manifest:RunnerFileManifest):String {
        val hash=java.security.MessageDigest.getInstance("SHA-256")
        manifest.files.toSortedMap().forEach { (path,file)->hash.update("F\u0000$path\u0000${file.size}\u0000${file.sha256}\n".toByteArray()) }
        manifest.directories.sorted().forEach {hash.update("D\u0000$it\n".toByteArray())}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
}
