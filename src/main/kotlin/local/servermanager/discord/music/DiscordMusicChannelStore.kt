package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

data class MusicChannelRecord(val guildId:String,val channelId:String,val panelId:String?=null)

/** Stores Discord IDs only. A saved panel message is never deleted as part of registration. */
class DiscordMusicChannelStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val path=directory.resolve("music-channels.json")
    private val records:MutableMap<String,MusicChannelRecord>

    init {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(path)) { "음악 채널 설정에 심볼릭 링크를 사용할 수 없습니다." }
        directory.createDirectories()
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        records=if(Files.exists(path,NOFOLLOW_LINKS)) mapper.readValue(path.readText()) else mutableMapOf()
        records.forEach { (key,record) -> require(key==record.guildId); validate(record) }
    }

    @Synchronized fun get(guildId:String):MusicChannelRecord?=records[guildId]
    @Synchronized fun all():List<MusicChannelRecord> = records.values.toList()

    @Synchronized fun put(record:MusicChannelRecord):MusicChannelRecord {
        validate(record)
        records[record.guildId]=record
        persist()
        return record
    }

    @Synchronized fun remove(guildId:String):MusicChannelRecord? {
        val removed=records.remove(guildId) ?: return null
        persist()
        return removed
    }

    private fun validate(record:MusicChannelRecord) {
        fun snowflake(id:String)=id.matches(Regex("[1-9][0-9]{16,19}")) && id.toULongOrNull()!=null
        require(snowflake(record.guildId) && snowflake(record.channelId) && (record.panelId==null || snowflake(record.panelId))) { "Discord 서버·채널·메시지 ID를 확인하세요." }
    }

    private fun persist() {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(path)) { "음악 채널 설정에 심볼릭 링크를 사용할 수 없습니다." }
        val temp=Files.createTempFile(directory,".music-channels-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            temp.writeBytes(mapper.writeValueAsBytes(records))
            Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))
        } finally { Files.deleteIfExists(temp) }
    }
}
