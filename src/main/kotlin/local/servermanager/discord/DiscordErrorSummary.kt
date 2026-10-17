package local.servermanager

/** Small, runtime-only Discord notification headers; stack frames remain the browser worker's job. */
data class DiscordErrorSummary(val errorName:String,val description:String,val causes:List<String>) {
    fun fields():List<Pair<String,String>> = listOf("오류" to errorName,"설명" to description)+
        if(causes.isEmpty())emptyList() else listOf("Caused by" to causes.joinToString("\n"))
}

class DiscordErrorBuffer(private val report:Boolean=false) {
    private var name:String?=null
    private var message:String?=null
    private var reportDescription:String?=null
    private var header:String?=null
    private val causes=mutableListOf<String>()
    private var lastAt=0L
    private var active=false
    @Synchronized fun clear(){name=null;message=null;reportDescription=null;header=null;causes.clear();lastAt=0;active=false}
    @Synchronized fun recent(now:Long=System.currentTimeMillis()):DiscordErrorSummary? = snapshot()?.takeIf {now-lastAt in 0..30000}
    @Synchronized fun offer(raw:String,error:Boolean=false,now:Long=System.currentTimeMillis()):Boolean {
        val bounded=raw.take(4096)
        if(bounded.trimStart().startsWith("at ")){if(active)lastAt=now;return false}
        val line=PREFIX.replace(ANSI.replace(bounded,""),"").trim()
        if(line.startsWith("at ")||line.startsWith("... ")||line.startsWith("Suppressed:")) {
            if(active)lastAt=now
            return false
        }
        if(line.startsWith("Description:")) {
            reportDescription=clean(line.removePrefix("Description:"),700);lastAt=now;active=true;return false
        }
        val caused=line.startsWith("Caused by:")
        val text=if(caused)line.removePrefix("Caused by:").trim() else THREAD.replace(line,"")
        val match=if(text.substringBefore(':').length<=240)THROWABLE.matchEntire(text) else null
        if(match!=null&&(caused||match.groupValues[1].contains('.')||listOf("Exception","Error","Throwable").any {match.groupValues[1].endsWith(it)})) {
            val type=match.groupValues[1]
            val detail=clean(match.groupValues[2],700)
            if(caused) {
                val cause=clean(type+(if(detail.isBlank())"" else ": $detail"),260)
                if(cause !in causes){if(causes.size>=3)causes.removeAt(2);causes.add(cause)}
            } else {
                if(name!=null){if(report)return false;clear()}
                name=clean(type,200);message=detail
            }
            lastAt=now;active=true;return true
        }
        if(caused)return false
        if((error||FATAL.containsMatchIn(raw.take(512)))&&line.isNotBlank()) {
            clear();header=clean(line,700);lastAt=now;active=true;return true
        }
        return false
    }
    @Synchronized fun snapshot():DiscordErrorSummary? {
        if(!active)return null
        val description=listOfNotNull(reportDescription?.takeIf {it.isNotBlank()},message?.takeIf {it.isNotBlank()})
            .distinct().joinToString(" · ").ifBlank {header ?: "예외에 별도 설명이 없습니다."}
        return DiscordErrorSummary(name ?: "서버 오류",clean(description,900),causes.toList())
    }
    companion object {
        private val FATAL=Regex("(?:/|\\s|\\[)FATAL\\]")
        private val ANSI=Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val PREFIX=Regex("^\\s*(?:\\[[^\\]\\r\\n]{0,240}\\]\\s*){1,5}(?::\\s*)?")
        private val THREAD=Regex("^Exception in thread \\\"[^\\\"]{0,120}\\\"\\s+")
        private val THROWABLE=Regex("((?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Z_$][A-Za-z0-9_$]*)(?::\\s*(.*))?")
        private val SECRET=Regex("(?i)((?:password|passwd|token|secret|api[_-]?key|authorization)\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;]+")
        private val USERINFO=Regex("(https?://)[^/@\\s]+:[^/@\\s]+@",RegexOption.IGNORE_CASE)
        private fun clean(value:String,limit:Int):String=SECRET.replace(USERINFO.replace(value,"$1[redacted]@")){it.groupValues[1]+"[redacted]"}
            .replace('`','\u02bc').replace(Regex("[\\p{Cc}\\p{Cf}\\s]+")," ").trim().let {if(it.length>limit)it.take(limit-1)+"…" else it}
        fun fromReport(bytes:ByteArray):DiscordErrorSummary? {
            val buffer=DiscordErrorBuffer(report=true)
            // Bound notification extraction even when an attached raw report is many MiB.
            bytes.copyOfRange(0,minOf(bytes.size,CrashReports.MAX_BYTES)).toString(Charsets.UTF_8).lineSequence().take(100000).takeWhile {!it.contains("-- System Details --")}.forEach {buffer.offer(it)}
            return buffer.snapshot()
        }
    }
}
