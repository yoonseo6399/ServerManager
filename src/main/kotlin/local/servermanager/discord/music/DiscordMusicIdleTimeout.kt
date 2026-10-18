package local.servermanager

/** Continuous absence on the same connected voice channel, using monotonic time. Other bots are not listeners. */
internal class DiscordMusicIdleTimeout(private val clock:()->Long=System::nanoTime) {
    private var channel:String?=null
    private var emptySince:Long?=null
    fun expired(connectedChannel:String?,hasHuman:Boolean):Boolean {
        if(connectedChannel!=channel){channel=connectedChannel;emptySince=null}
        if(connectedChannel==null || hasHuman){emptySince=null;return false}
        val now=clock();val since=emptySince ?: now.also {emptySince=it}
        return now-since>=java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
    }
}
