package local.servermanager

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID
import kotlin.io.path.*

object ModFiles {
    /** Only renamed JARs are staged. Other mod files retain their inode and content. */
    fun rename(directory:Path,changes:Map<String,String>,history:Path) {
        require(changes.isNotEmpty() && changes.keys.intersect(changes.values.toSet()).isEmpty())
        val before=FileInventory.collectCached(directory).let {(f,d)->RunnerFileManifest(f,d)}
        require(changes.values.distinct().size==changes.size)
        changes.forEach {(from,to)->
            require(from in before.files && to !in before.files && to !in before.directories)
            require(!from.contains('/') && !to.contains('/') && from.lowercase().endsWith(".jar")!=to.lowercase().endsWith(".jar"))
        }
        val stage=directory.parent.resolve(".mod-stage-${UUID.randomUUID()}").createDirectory()
        try {
            changes.forEach {(from,to)->Files.copy(FileInventory.safePath(directory,from),FileInventory.safePath(stage,to))}
            val after=RunnerFileManifest(before.files.filterKeys {it !in changes}+changes.map {(from,to)->to to before.files.getValue(from)},before.directories)
            DeltaTransfer.apply(directory,stage,before,after,history)
        } finally {stage.toFile().deleteRecursively()}
    }
    /** Fresh world: copy runtime/config/mods, skip saved worlds, reports, logs and common backup archives. */
    fun sandbox(source:Path,target:Path,onProgress:(Int,Long)->Unit={_,_->}) {
        require(source.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(source) && !target.exists())
        val props=Properties();source.resolve("server.properties").takeIf {it.exists()}?.inputStream()?.use {props.load(it)}
        val world=props.getProperty("level-name","world").replace('\\','/').trim('/').substringBefore('/')
        val skip=setOf(world,"world","world_nether","world_the_end","logs","crash-reports","backups","backup","cache",".git")
        var count=0;var bytes=0L
        target.createDirectories()
        Files.walkFileTree(source,object:SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes):FileVisitResult {
                require(!Files.isSymbolicLink(dir)) {"검사 복사본에 심볼릭 링크를 사용할 수 없습니다"}
                if(dir!=source) {
                    val rel=source.relativize(dir)
                    if(rel.getName(0).toString() in skip || dir.resolve("level.dat").exists() || dir.resolve("region").isDirectory())return FileVisitResult.SKIP_SUBTREE
                    target.resolve(rel).createDirectories()
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult {
                require(attrs.isRegularFile && !Files.isSymbolicLink(file)) {"검사 복사본에 특수 파일을 사용할 수 없습니다"}
                val rel=source.relativize(file);val name=file.name.lowercase()
                if(rel.nameCount==1 && (name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".log") || name=="session.lock"))return FileVisitResult.CONTINUE
                require(++count<=100000 && attrs.size()<=1024L*1024*1024) {"검사 파일 수·크기가 제한을 초과했습니다"}
                require(Files.getFileStore(target).usableSpace>attrs.size()+256L*1024*1024) {"검사 복사본을 만들 디스크 공간이 부족합니다"}
                Files.copy(file,target.resolve(rel),COPY_ATTRIBUTES);bytes+=attrs.size();onProgress(count,bytes)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
