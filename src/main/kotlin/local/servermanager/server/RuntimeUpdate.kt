package local.servermanager

import java.nio.file.*
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.io.path.*

/** Installs official runtime files in isolation; existing worlds/mods/config are never given to an installer. */
class RuntimeUpdate(private val server:Path,private val history:Path,private val install:(Instance,Path,(String)->Unit)->Unit) {
    data class Target(val minecraft:String,val loaderVersion:String)
    data class Status(val id:String,val state:String,val target:Target,val logs:List<String>,val backup:String?=null,val error:String?=null)
    private var current:Status?=null
    @Synchronized fun busy()=current?.state in setOf("PREPARING","INSTALLING","APPLYING")
    @Synchronized fun status():Status=current ?: error("진행한 버전 변경 작업이 없습니다")
    @Synchronized private fun update(state:String,line:String?=null,backup:String?=null,error:String?=null){val old=current!!;current=old.copy(state=state,logs=(old.logs+listOfNotNull(line)).takeLast(60),backup=backup ?: old.backup,error=error)}
    @Synchronized fun start(before:Instance,target:Target):Status {
        validate(before,target)
        if(busy()){check(current!!.target==target){"다른 버전 변경이 진행 중입니다"};return current!!}
        val id=UUID.randomUUID().toString();current=Status(id,"PREPARING",target,listOf("별도 폴더에서 공식 런타임을 준비합니다"))
        val result=current!!
        workers.submit {
            var stage:Path?=null
            try {
                val next=before.copy(minecraft=target.minecraft,loaderVersion=target.loaderVersion)
                stage=Files.createTempDirectory(server.parent,".runtime-stage-");update("INSTALLING")
                install(next,stage){line->update("INSTALLING",line.take(1000))}
                Launchers.requiredJava(next,stage);Launchers.arguments(next,stage)
                val backup=history.resolve("runtime-$id");update("APPLYING","검증한 실행 파일을 반영합니다",backup.toString())
                promote(server,stage,backup)
                update("COMPLETE","버전 변경 완료 · 월드·모드·설정은 보존했습니다")
            }catch(e:Exception){update("FAILED","설치·반영에 실패했습니다. 백업 위치를 확인하세요",error=e.message?.take(500))}
            finally {stage?.toFile()?.deleteRecursively()}
        }
        return result
    }
    companion object {
        private val workers=Executors.newFixedThreadPool(2){r->Thread(r,"runtime-update").apply {isDaemon=true}}
        fun compareMinecraft(a:String,b:String):Int {val x=a.split('.').map(String::toInt);val y=b.split('.').map(String::toInt);return (0 until maxOf(x.size,y.size)).map {x.getOrElse(it){0}.compareTo(y.getOrElse(it){0})}.firstOrNull {it!=0} ?: 0}
        fun validate(before:Instance,target:Target){Launchers.validate(before.loader,target.minecraft,target.loaderVersion);require(compareMinecraft(target.minecraft,before.minecraft)>=0){"월드 손상을 막기 위해 Minecraft 다운그레이드는 지원하지 않습니다"};require(target.minecraft!=before.minecraft||target.loaderVersion!=before.loaderVersion){"이미 선택한 버전입니다"}}
        private fun runtime(relative:String):Boolean {val root=relative.substringBefore('/');return root in setOf("libraries","versions",".fabric") || relative in setOf("server.jar","fabric-server-launch.jar","fabric-server-launcher.properties","server-manager-runtime.json","run.sh","run.bat","user_jvm_args.txt") || !relative.contains('/') && relative.matches(Regex("(?:minecraft_server|fabric-server-mc)[A-Za-z0-9._-]*\\.jar"))}
        /** Transaction journal and old runtime files are retained for manual recovery. No world file is copied or removed. */
        fun promote(server:Path,stage:Path,backup:Path,beforeReplace:(String)->Unit={}) {
            require(!Files.isSymbolicLink(server) && !Files.isSymbolicLink(stage));backup.createDirectories()
            val all=Files.walk(stage).use {s->s.filter {it.isRegularFile(LinkOption.NOFOLLOW_LINKS)}.toList()}
            val entry=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(stage.resolve("server-manager-runtime.json").readText()).path("entryJar").asText("")
            require(entry.isEmpty() || entry.endsWith(".jar") && '/' !in entry && '\\' !in entry && ':' !in entry)
            val files=all.map {stage.relativize(it).toString().replace('\\','/')}.filter {runtime(it)||entry.isNotEmpty()&&it==entry}.sorted()
            require(files.isNotEmpty() && "server-manager-runtime.json" in files)
            val original=mutableMapOf<String,String?>();val desired=mutableMapOf<String,String>()
            for(relative in files){val source=FileInventory.safePath(stage,relative);var part:Path?=source;while(part!=stage){require(!Files.isSymbolicLink(part));part=part?.parent};val dest=FileInventory.safePath(server,relative);part=dest;while(part!=server){require(!Files.isSymbolicLink(part));part=part?.parent};if(relative=="user_jvm_args.txt" && dest.exists())continue
                original[relative]=if(dest.exists())FileInventory.digest(dest)else null;desired[relative]=FileInventory.digest(source)
                if(dest.exists()){val saved=FileInventory.safePath(backup.resolve("before"),relative);saved.parent.createDirectories();Files.copy(dest,saved);check(FileInventory.digest(saved)==original[relative])}
            }
            val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper();val journal=backup.resolve("transaction.json");journal.writeBytes(mapper.writeValueAsBytes(mapOf("state" to "PREPARED","before" to original,"after" to desired)))
            val changed=mutableListOf<String>()
            try {
                for((relative,sha) in desired){val dest=FileInventory.safePath(server,relative);check((if(dest.exists())FileInventory.digest(dest)else null)==original[relative]){"실행 파일이 변경됐습니다: $relative"};beforeReplace(relative);dest.parent.createDirectories();val temporary=Files.createTempFile(dest.parent,".runtime-",".tmp");try {Files.copy(FileInventory.safePath(stage,relative),temporary,StandardCopyOption.REPLACE_EXISTING);Files.move(temporary,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);changed+=relative;check(FileInventory.digest(dest)==sha)}finally{temporary.deleteIfExists()}}
                journal.writeBytes(mapper.writeValueAsBytes(mapOf("state" to "COMPLETE","before" to original,"after" to desired)))
            }catch(e:Exception){for(relative in changed.asReversed()){val dest=FileInventory.safePath(server,relative);if(original[relative]!=null)Files.copy(FileInventory.safePath(backup.resolve("before"),relative),dest,StandardCopyOption.REPLACE_EXISTING)else dest.deleteIfExists()};journal.writeBytes(mapper.writeValueAsBytes(mapOf("state" to "ROLLED_BACK","before" to original,"after" to desired)));throw e}
        }
    }
}
