package local.servermanager

/** Runtime telemetry only. Never carries a token, URL, file path or exception body. */
data class RunnerUpdateStatus(val phase:String="IDLE",val latest:String?=null,val downloadedBytes:Long=0,val totalBytes:Long=0) {
    fun validated():RunnerUpdateStatus {
        require(phase in setOf("IDLE","REQUESTED","CHECKING","DOWNLOADING","VERIFYING","READY","WAITING","PAUSING","RESTARTING","CURRENT","FAILED","UNSUPPORTED"))
        latest?.let {SignedUpdates.compare(it,"0.0.0")}
        require(downloadedBytes in 0..150L*1024*1024 && totalBytes in 0..150L*1024*1024 && downloadedBytes<=totalBytes)
        return this
    }
}
data class RunnerUpdateRequest(val pauseTransfer:Boolean=false,val version:String?=null,val allowPrerelease:Boolean=false,val allowRollback:Boolean=false)
object RunnerUpdateSupport {
    fun supported(version:String)=runCatching {SignedUpdates.compare(version,"0.4.4")>=0}.getOrDefault(false)
}

/** What a control-layer update may do while hosted games are alive. The decision is pure so it can be tested exhaustively. */
object HostCompatibility {
    data class Verdict(val allowed:Boolean,val reason:String?=null)
    /**
     * [targetMinHost]: oldest host protocol the new control layer supports (signed manifest `controller.minHost`, default 1).
     * [targetMaxHost]: newest host protocol it understands (`controller.maxHost`, default 1 = only the current protocol).
     */
    fun controlUpdate(hostProtocol:Int,hostMinCompatible:Int,controllerProtocol:Int,targetMinHost:Int,targetMaxHost:Int):Verdict = when {
        hostProtocol<targetMinHost || hostProtocol>targetMaxHost -> Verdict(false,"HOST_PROTOCOL_INCOMPATIBLE")
        controllerProtocol<hostMinCompatible -> Verdict(false,"CONTROLLER_PROTOCOL_TOO_OLD")
        else -> Verdict(true)
    }
}
