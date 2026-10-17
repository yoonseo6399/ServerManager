package local.servermanager

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.channel.concrete.Category
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.ChannelAction
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import kotlin.test.*

class ProjectAgentDiscordTest {
    @Test fun `private channel uses same category and clears inherited role and member permissions`() {
        val guild=mock(Guild::class.java);val category=mock(Category::class.java)
        val everyone=mock(Role::class.java);val self=mock(SelfMember::class.java)
        `when`(everyone.idLong).thenReturn(11);`when`(self.idLong).thenReturn(22)
        `when`(guild.publicRole).thenReturn(everyone);`when`(guild.selfMember).thenReturn(self)
        @Suppress("UNCHECKED_CAST") val action=mock(ChannelAction::class.java,RETURNS_SELF) as ChannelAction<TextChannel>
        `when`(guild.createTextChannel("qwen-demo",category)).thenReturn(action)
        assertSame(action,ProjectAgentChannels.create(guild,category,33,"qwen-demo"))
        verify(guild).createTextChannel("qwen-demo",category)
        val order=inOrder(action);order.verify(action).clearPermissionOverrides()
        order.verify(action).addRolePermissionOverride(11,0,Permission.VIEW_CHANNEL.rawValue)
        val allowed=Permission.getRaw(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY,Permission.MESSAGE_SEND_IN_THREADS,Permission.CREATE_PUBLIC_THREADS,Permission.MESSAGE_ATTACH_FILES,Permission.MESSAGE_EMBED_LINKS)
        order.verify(action).addMemberPermissionOverride(33,allowed,0)
        order.verify(action).addMemberPermissionOverride(22,allowed,0)
        verify(action,never()).syncPermissionOverrides()
        // Building a request does not send any message or create any real Discord channel in tests.
        verify(action,never()).complete()
    }
}
