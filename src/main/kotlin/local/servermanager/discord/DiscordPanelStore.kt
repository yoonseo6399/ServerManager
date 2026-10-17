package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*

data class DiscordPanelRecord(val panelId:String?=null, val errorIds:List<String> = emptyList(), val stopId:String?=null, val startId:String?=null)

/** Stores only Discord message IDs. No log text or parsed server output is persisted. */
class DiscordPanelStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val path=directory.resolve("panel-messages.json")
    private val records:MutableMap<String,DiscordPanelRecord>
    init {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(path))
        directory.createDirectories()
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        records=if(path.exists()) mapper.readValue(path.readText()) else mutableMapOf()
    }
    @Synchronized fun get(key:String)=records[key] ?: DiscordPanelRecord()
    @Synchronized fun change(key:String, f:(DiscordPanelRecord)->DiscordPanelRecord):DiscordPanelRecord {
        val next=f(get(key))
        records[key]=next
        val tmp=Files.createTempFile(directory,".panels-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try { tmp.writeBytes(mapper.writeValueAsBytes(records));Files.move(tmp,path,ATOMIC_MOVE,REPLACE_EXISTING) }
        finally { tmp.deleteIfExists() }
        return next
    }
    companion object { fun key(route:DiscordAlertRoute)="${route.guildId}:${route.channelId}:${route.instanceId}" }
}
