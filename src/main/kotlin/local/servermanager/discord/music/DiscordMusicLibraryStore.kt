package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

data class MusicSavedTrack(val title:String,val url:String)
data class MusicSavedPlaylist(val name:String,val tracks:List<MusicSavedTrack>,val ownerId:String)
data class MusicPlayStat(val track:MusicSavedTrack,val completions:Int,val lastCompletedAt:Long,val skips:Int=0)

private data class MusicLibraryGuild(
    val autoplay:Boolean=true,
    val playlists:List<MusicSavedPlaylist> = emptyList(),
    val stats:List<MusicPlayStat> = emptyList(),
)

/** Guild-scoped metadata only. No Discord or YouTube network access is performed here. */
class DiscordMusicLibraryStore(private val directory:Path?=null) {
    private val mapper=jacksonObjectMapper()
    private val path=directory?.resolve("music-library.json")
    private val guilds:MutableMap<String,MusicLibraryGuild>

    init {
        if(directory!=null) {
            requireSafePath()
            directory.createDirectories()
            Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        }
        guilds=if(path!=null && Files.exists(path,NOFOLLOW_LINKS)) mapper.readValue(path.readBytes()) else mutableMapOf()
        guilds.forEach { (id,data) ->
            require(snowflake(id)) { "Discord 서버 ID를 확인하세요." }
            require(data.playlists.size<=50 && data.stats.size<=1000) { "음악 라이브러리 제한을 초과했습니다." }
            require(data.playlists.map { it.name }.toSet().size==data.playlists.size) { "중복된 재생목록입니다." }
            data.playlists.forEach(::validatePlaylist)
            data.stats.forEach { validateTrack(it.track);require(it.completions in 1..1_000_000 && it.lastCompletedAt>=0 && it.skips in 0..1_000_000) }
        }
    }

    @Synchronized fun recordCompletion(guildId:String,track:MusicSavedTrack) {
        require(snowflake(guildId)) { "Discord 서버 ID를 확인하세요." }
        validateTrack(track)
        val normalized=track.copy(url="https://www.youtube.com/watch?v=${videoId(track.url)}")
        val now=System.currentTimeMillis()
        val old=guilds[guildId] ?: MusicLibraryGuild()
        val stats=old.stats.toMutableList()
        val index=stats.indexOfFirst { it.track.url==normalized.url }
        val previous=if(index>=0) stats.removeAt(index) else null
        val count=previous?.completions?.coerceAtMost(999_999)?.plus(1) ?: 1
        stats.add(0,MusicPlayStat(normalized,count,now,previous?.skips ?: 0))
        // Retain the most recently completed distinct videos when the bound is reached.
        val bounded=stats.sortedByDescending { it.lastCompletedAt }.take(1000)
        persistChange(guildId,old.copy(stats=bounded))
    }

    /** Skipping must not make a song eligible: only manually completed songs are recommended. */
    @Synchronized fun recordSkip(guildId:String,track:MusicSavedTrack) {
        require(snowflake(guildId)) { "Discord 서버 ID를 확인하세요." }
        validateTrack(track)
        val url="https://www.youtube.com/watch?v=${videoId(track.url)}"
        val old=guilds[guildId] ?: return
        val previous=old.stats.firstOrNull {it.track.url==url} ?: return
        val updated=previous.copy(skips=previous.skips.coerceAtMost(999_999)+1)
        persistChange(guildId,old.copy(stats=old.stats.map {if(it.track.url==url) updated else it}))
    }

    @Synchronized fun stats(guildId:String):List<MusicPlayStat> {
        require(snowflake(guildId))
        return guilds[guildId]?.stats.orEmpty().sortedWith(compareByDescending<MusicPlayStat> { it.completions }.thenByDescending { it.lastCompletedAt })
    }

    @Synchronized fun autoplayEnabled(guildId:String):Boolean {
        require(snowflake(guildId))
        return guilds[guildId]?.autoplay ?: true
    }

    @Synchronized fun setAutoplay(guildId:String,enabled:Boolean) {
        require(snowflake(guildId))
        persistChange(guildId,(guilds[guildId] ?: MusicLibraryGuild()).copy(autoplay=enabled))
    }

    @Synchronized fun save(guildId:String,name:String,tracks:List<MusicSavedTrack>,ownerId:String):MusicSavedPlaylist {
        require(snowflake(guildId) && snowflake(ownerId)) { "Discord ID를 확인하세요." }
        val playlist=MusicSavedPlaylist(name,tracks.map { track ->
            validateTrack(track)
            track.copy(url="https://www.youtube.com/watch?v=${videoId(track.url)}")
        },ownerId)
        validatePlaylist(playlist)
        val old=guilds[guildId] ?: MusicLibraryGuild()
        val lists=old.playlists.filterNot { it.name==name }+playlist
        require(lists.size<=50) { "재생목록은 서버마다 최대 50개까지 저장할 수 있습니다." }
        persistChange(guildId,old.copy(playlists=lists))
        return playlist
    }

    @Synchronized fun playlists(guildId:String):List<MusicSavedPlaylist> {
        require(snowflake(guildId))
        return guilds[guildId]?.playlists.orEmpty().map { it.copy(tracks=it.tracks.toList()) }
    }

    @Synchronized fun playlist(guildId:String,name:String):MusicSavedPlaylist? {
        require(snowflake(guildId))
        return guilds[guildId]?.playlists?.firstOrNull { it.name==name }?.let { it.copy(tracks=it.tracks.toList()) }
    }

    @Synchronized fun delete(guildId:String,name:String):Boolean {
        require(snowflake(guildId))
        val old=guilds[guildId] ?: return false
        val lists=old.playlists.filterNot { it.name==name }
        if(lists.size==old.playlists.size) return false
        persistChange(guildId,old.copy(playlists=lists))
        return true
    }

    private fun persistChange(guildId:String,next:MusicLibraryGuild) {
        val updated=guilds.toMutableMap().apply { put(guildId,next) }
        persist(updated)
        guilds[guildId]=next
    }

    private fun persist(updated:Map<String,MusicLibraryGuild>) {
        val file=path ?: return
        val dir=directory ?: return
        requireSafePath()
        val temp=Files.createTempFile(dir,".music-library-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            temp.writeBytes(mapper.writeValueAsBytes(updated))
            Files.move(temp,file,ATOMIC_MOVE,REPLACE_EXISTING)
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
        } finally { Files.deleteIfExists(temp) }
    }

    private fun requireSafePath() {
        require(directory!=null && !Files.isSymbolicLink(directory) && (path==null || !Files.isSymbolicLink(path))) {
            "음악 라이브러리에 심볼릭 링크를 사용할 수 없습니다."
        }
    }

    private fun validatePlaylist(list:MusicSavedPlaylist) {
        require(list.name.length in 1..40 && list.name.isNotBlank() && list.name.none(Char::isISOControl)) { "재생목록 이름은 1~40자여야 합니다." }
        require(snowflake(list.ownerId)) { "소유자 ID를 확인하세요." }
        require(list.tracks.size in 1..100) { "재생목록당 1~100곡을 저장할 수 있습니다." }
        list.tracks.forEach(::validateTrack)
    }

    private fun validateTrack(track:MusicSavedTrack) {
        require(track.title.length in 1..200 && track.title.isNotBlank() && track.title.none(Char::isISOControl)) { "곡 제목을 확인하세요." }
        require(videoId(track.url)!=null) { "HTTPS YouTube 영상 URL을 입력하세요." }
    }

    private fun videoId(raw:String):String? {
        val uri=runCatching { URI(raw) }.getOrNull() ?: return null
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 || uri.fragment!=null) return null
        val host=uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val id=when(host) {
            "youtu.be" -> uri.path.removePrefix("/").takeIf { !it.contains('/') }
            "youtube.com","m.youtube.com","music.youtube.com" -> when {
                uri.path=="/watch" -> uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                uri.path.startsWith("/shorts/") -> uri.path.removePrefix("/shorts/").takeIf { !it.contains('/') }
                uri.path.startsWith("/live/") -> uri.path.removePrefix("/live/").takeIf { !it.contains('/') }
                else -> null
            }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    private fun snowflake(id:String)=id.matches(Regex("[1-9][0-9]{16,19}")) && id.toULongOrNull()!=null
}
