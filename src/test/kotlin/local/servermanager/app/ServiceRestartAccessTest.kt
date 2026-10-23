package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.*
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.nio.file.Path
import kotlin.io.path.*

class ServiceRestartAccessTest {
    @TempDir lateinit var temp:Path
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import(SecurityConfig::class,ServiceRestartApi::class,RunnerApi::class)
    class Web
    private fun session(account:Account,fresh:Boolean=true)=MockHttpSession().also {it.setAttribute("sm.user",account.id);it.setAttribute("sm.epoch",account.epoch);it.setAttribute("sm.started",System.currentTimeMillis());if(fresh)it.setAttribute("sm.verified",System.currentTimeMillis())}
    @Test fun `restart HTTP filters enforce owner registrant fresh passkey and CSRF`() {
        val manager=mock(ServerManager::class.java);`when`(manager.root).thenReturn(temp.resolve("profile").createDirectories())
        val desktop=mock(DesktopLifecycle::class.java);val expected=ServiceRestartStatus(true,"boot","REQUESTED",true)
        `when`(desktop.serviceRestartStatus()).thenReturn(expected);`when`(desktop.requestServiceRestart()).thenReturn(expected)
        val registry=mock(RunnerRegistry::class.java);val gateway=mock(RunnerGateway::class.java)
        AnnotationConfigWebApplicationContext().use {context->
            context.servletContext=MockServletContext();context.register(Web::class.java)
            context.addBeanFactoryPostProcessor {factory->factory.registerSingleton("manager",manager);factory.registerSingleton("desktop",desktop);factory.registerSingleton("runners",registry);factory.registerSingleton("gateway",gateway);factory.registerSingleton("updates",mock(AppUpdates::class.java))}
            context.refresh();val store=context.getBean(AuthStore::class.java);val owner=store.accounts().single();store.invite("registrant",emptyMap());val member=store.accounts().first {!it.owner};store.invite("other",emptyMap());val other=store.accounts().first {it.id!=owner.id&&it.id!=member.id}
            val row=RunnerView("runner","fixture","MAC",member.id,"now","fixture",true,emptySet(),true,null)
            for(account in listOf(owner,member,other))`when`(registry.views(account.id)).thenReturn(listOf(row))
            `when`(registry.registrant("runner")).thenReturn(member.id)
            val mvc=MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            mvc.perform(get("/api/admin/service-restart")).andExpect(status().isUnauthorized)
            mvc.perform(get("/api/admin/service-restart").with(user(member.id)).session(session(member))).andExpect(status().isForbidden)
            mvc.perform(post("/api/admin/service-restart").with(user(owner.id)).session(session(owner))).andExpect(status().isForbidden)
            mvc.perform(post("/api/admin/service-restart").with(user(owner.id)).with(csrf()).session(session(owner,false))).andExpect(status().isPreconditionRequired)
            mvc.perform(post("/api/admin/service-restart").with(user(owner.id)).with(csrf()).session(session(owner))).andExpect(status().isAccepted)
            mvc.perform(post("/api/runners/runner/restart").with(user(other.id)).with(csrf()).session(session(other))).andExpect(status().isForbidden)
            mvc.perform(post("/api/runners/runner/restart").with(user(member.id)).with(csrf()).session(session(member,false))).andExpect(status().isPreconditionRequired)
            mvc.perform(post("/api/runners/runner/restart").with(user(member.id)).with(csrf()).session(session(member))).andExpect(status().isOk)
            mvc.perform(post("/api/runners/runner/restart").with(user(owner.id)).with(csrf()).session(session(owner))).andExpect(status().isOk)
            verify(desktop,times(1)).requestServiceRestart();verify(gateway,times(2)).requestRestart("runner")
        }
    }
}
