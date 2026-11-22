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
                    var start=0L;var position=0L;var length=0
                    val head=ByteArray(512);val buffer=ByteArray(65536)
                    Files.newInputStream(history,LinkOption.NOFOLLOW_LINKS).buffered(65536).use {input->
                        while(true){val count=input.read(buffer);if(count<0)break
                            for(n in 0 until count){val byte=buffer[n];position++
                                if(byte==10.toByte()){
                                    if(length in 4..512 && head[0]==45.toByte() && head[1]==45.toByte() && head[2]==45.toByte() && String(head,0,length,Charsets.UTF_8).trimEnd('\r').matches(Regex("--- \\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z ---")))start=position
                                    length=0
                                }else{if(length<512)head[length]=byte;length=minOf(513,length+1)}
                            }
                        }
                    }
                    if(start>0)Files.newInputStream(history,LinkOption.NOFOLLOW_LINKS).buffered(65536).use {input->input.skipNBytes(start);Files.newOutputStream(temporary).use {out->input.copyTo(out,65536)}}
                }
            }
            Files.move(temporary,live,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            val stamp=Files.createTempFile(directory,".live-session-",".tmp")
            try {stamp.writeText(identity);Files.move(stamp,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally{stamp.deleteIfExists()}
        } finally {temporary.deleteIfExists()}
        return live
    }
}
