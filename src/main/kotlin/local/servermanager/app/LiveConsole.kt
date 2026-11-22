package local.servermanager

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

/** Preserve cumulative history; only genuine Minecraft launches start a new live file. */
object LiveConsole {
    @Synchronized fun open(directory:java.nio.file.Path,identity:String,fresh:Boolean=false):java.nio.file.Path {
        directory.createDirectories()
        val live=directory.resolve("live.log");val marker=directory.resolve(".live-console-session")
        require(!Files.isSymbolicLink(live) && !Files.isSymbolicLink(marker))
        if(marker.exists() && marker.readText()==identity && live.isRegularFile())return live
        val temporary=Files.createTempFile(directory,".live-",".tmp")
        try {
            if(!fresh) {
                val history=directory.resolve("console.log")
                if(history.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(history)) {
                    java.io.RandomAccessFile(history.toFile(),"r").use { file->
                        var start=0L
                        while(true){val line=file.readLine() ?: break;if(line.matches(Regex("--- \\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z ---")))start=file.filePointer}
                        if(start>0){file.seek(start);Files.newOutputStream(temporary).use {out->val buffer=ByteArray(65536);while(true){val n=file.read(buffer);if(n<0)break;out.write(buffer,0,n)}}}
                    }
                }
            }
            Files.move(temporary,live,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            val stamp=Files.createTempFile(directory,".live-session-",".tmp")
            try {stamp.writeText(identity);Files.move(stamp,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally{stamp.deleteIfExists()}
        } finally {temporary.deleteIfExists()}
        return live
    }
}
