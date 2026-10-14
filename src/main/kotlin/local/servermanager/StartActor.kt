package local.servermanager

/** Trusted lifecycle attribution, supplied by the authenticated entry point, never by request JSON. */
data class StartActor(val source:String,val name:String,val discordId:String?=null,val avatarUrl:String?=null) {
    fun profileUrl():String?=discordId?.takeIf {source=="DISCORD" && it.matches(Regex("[0-9]{17,20}"))}?.let {"https://discord.com/users/$it"}
    fun safeAvatar():String?=avatarUrl?.takeIf {profileUrl()!=null && runCatching {
        val uri=java.net.URI(it)
        uri.scheme=="https" && uri.host in setOf("cdn.discordapp.com","media.discordapp.net") && uri.userInfo==null && uri.port in setOf(-1,443)
    }.getOrDefault(false)}
    fun label()=when(source){"DISCORD"->"Discord";"WEB"->"관리 웹";"AUTO"->"자동 재시작";else->"관리 시스템"}
}

object DiscordStartProfile {
    fun apply(builder:net.dv8tion.jda.api.EmbedBuilder,actor:StartActor?) {
        val value=actor ?: StartActor("SYSTEM","관리 시스템")
        val name=value.name.replace('@','＠').take(80).ifBlank {value.label()}
        builder.setAuthor(name,value.profileUrl(),value.safeAvatar())
            .addField("시작한 사람",name,true).addField("실행 경로",value.label(),true)
        value.safeAvatar()?.let {builder.setThumbnail(it)}
    }
}
