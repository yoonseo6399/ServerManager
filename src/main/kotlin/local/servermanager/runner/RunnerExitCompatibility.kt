package local.servermanager

/** New native names and run tokens are sent only to managers advertising support. */
internal object RunnerExitCompatibility {
    fun report(report:RunnerExitReport,exitProtocol:Int,reasonProtocol:Int)=report.copy(
        crashReport=report.crashReport?.takeUnless {CrashReports.nativeName(it) && exitProtocol<2},
        runToken=report.runToken.takeIf {exitProtocol>=2},
        reason=report.reason.takeUnless {it=="IDLE" && reasonProtocol<1})
}
