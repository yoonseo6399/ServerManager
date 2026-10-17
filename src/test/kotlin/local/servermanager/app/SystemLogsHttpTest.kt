package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.nio.file.Path
import kotlin.io.path.createDirectories

class SystemLogsHttpTest {
    @TempDir lateinit var temp:Path
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig::class)
    open class Config {
        companion object {lateinit var root:Path}
        @Bean open fun manager():ServerManager=mock(ServerManager::class.java).also {`when`(it.root).thenReturn(root)}
        @Bean open fun logs()=SystemLogs()
        @Bean open fun api(logs:SystemLogs)=SystemLogsApi(logs)
    }
    @Test fun `HTTP route requires active owner and returns no-store redacted content`() {
        Config.root=temp;temp.resolve("security").createDirectories()
        AnnotationConfigWebApplicationContext().use {context->
            context.servletContext=MockServletContext();context.register(Config::class.java);context.refresh()
            val store=context.getBean(AuthStore::class.java);val owner=store.accounts().single()
            val member=store.invitation(store.invite("member",emptyMap()))!!.userId
            val admin=store.invitation(store.invite("admin",emptyMap(),AccountRole.ADMIN))!!.userId
            val mvc=MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            fun request(id:String)=get("/api/admin/system-logs").with(user(id)).sessionAttr("sm.epoch",0).sessionAttr("sm.started",System.currentTimeMillis()).with {it.remoteAddr="127.0.0.1";it}
            context.getBean(SystemLogs::class.java).buffer.append(0,"INFO","network.Relay","token=never-return")
            mvc.perform(get("/api/admin/system-logs").with {it.remoteAddr="127.0.0.1";it}).andExpect(status().isUnauthorized)
            for(id in listOf(member,admin))mvc.perform(request(id)).andExpect(status().isForbidden)
            mvc.perform(request(owner.id)).andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("never-return"))))
        }
    }
}
