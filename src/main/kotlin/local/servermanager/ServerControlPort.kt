package local.servermanager

import java.nio.file.Path

/**
 * The only way the Discord side reaches Minecraft server control. Today [ServerManagerControl] calls the in-process
 * [ServerManager]; when the bot moves to its own process the same methods are served over an authenticated HTTP API
 * (docs/architecture/server-control-api.md), so bot code does not change.
 * Per-server permission (who may start/stop which server) is decided by the server manager, never by the caller.
 */
interface ServerControlPort {
    /** Private data directory for bot-side settings. Not part of the remote API (a separate bot keeps its own). */
    val dataRoot:Path
    fun list():List<Map<String,Any?>>
    fun instance(id:String):Instance
    fun view(id:String):Map<String,Any?>
    fun serverDir(id:String):Path
    fun startAs(id:String,actor:StartActor)
    fun stop(id:String,force:Boolean)
    fun restartAs(id:String,actor:StartActor)
    /** Server state signals (started, stopped, crashed...). The remote form is a server-sent event stream. */
    fun onSignal(listener:(ServerSignal)->Unit):AutoCloseable
}

class ServerManagerControl(private val manager:ServerManager) : ServerControlPort {
    override val dataRoot:Path get()=manager.root
    override fun list():List<Map<String,Any?>> = manager.list()
    override fun instance(id:String):Instance = manager.instance(id)
    override fun view(id:String):Map<String,Any?> = manager.view(id)
    override fun serverDir(id:String):Path = manager.serverDir(id)
    override fun startAs(id:String,actor:StartActor) {manager.startAs(id,actor)}
    override fun stop(id:String,force:Boolean) {manager.stop(id,force)}
    override fun restartAs(id:String,actor:StartActor) {manager.restartAs(id,actor)}
    override fun onSignal(listener:(ServerSignal)->Unit):AutoCloseable = manager.onSignal(listener)
}
