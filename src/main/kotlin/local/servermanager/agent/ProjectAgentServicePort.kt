package local.servermanager

import java.nio.file.Path

/** Backend-owned app services. Model arguments never change grants or permission settings. */
interface ProjectAgentServicePort {
    fun plan(home:Path,call:ProjectAgentTool):ProjectAgentPlan
    fun execute(call:ProjectAgentTool,active:()->Unit):ProjectAgentToolResult
    companion object {val names=setOf("schedule_task","list_chat_channels","read_chat_history","list_forum_posts")}
}
