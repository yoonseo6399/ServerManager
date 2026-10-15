package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class AssistantAccessTest {
    @TempDir lateinit var temp:Path
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import(SecurityConfig::class,AssistantHarness::class,AssistantApi::class,AssistantIdentity::class,AssistantGoogle::class)
    class Web
    private fun session(account:Account,fresh:Boolean=true)=MockHttpSession().also {it.setAttribute("sm.user",account.id);it.setAttribute("sm.epoch",account.epoch);it.setAttribute("sm.started",System.currentTimeMillis());if(fresh)it.setAttribute("sm.verified",System.currentTimeMillis())}
    @Test fun `actual security filters enforce owner csrf fresh passkey and live revocation`() {
        val manager=mock(ServerManager::class.java);`when`(manager.root).thenReturn(temp.resolve("profile").createDirectories())
        AnnotationConfigWebApplicationContext().use {context->
            context.servletContext=MockServletContext();context.register(Web::class.java)
            context.addBeanFactoryPostProcessor {factory->factory.registerSingleton("manager",manager);factory.registerSingleton("googleReadonlyService",GoogleReadonlyService(temp.resolve("google")))}
            context.refresh()
            val store=context.getBean(AuthStore::class.java);val owner=store.accounts().single();store.invite("member",emptyMap());val member=store.accounts().first {!it.owner}
            val mvc=MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            mvc.perform(get("/api/admin/assistant")).andExpect(status().isUnauthorized)
            mvc.perform(get("/api/admin/assistant").with(user(member.id)).session(session(member))).andExpect(status().isForbidden)
            mvc.perform(get("/api/admin/assistant").with(user(owner.id)).session(session(owner))).andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store"))
            mvc.perform(put("/api/admin/assistant/policy").with(user(owner.id)).session(session(owner)).contentType("application/json").content("{}" )).andExpect(status().isForbidden)
            mvc.perform(put("/api/admin/assistant/policy").with(user(owner.id)).with(csrf()).session(session(owner,false)).contentType("application/json").content("{}" )).andExpect(status().isPreconditionRequired)
            mvc.perform(put("/api/admin/assistant/policy").with(user(owner.id)).with(csrf()).session(session(owner)).contentType("application/json").content("{}" )).andExpect(status().isOk)
            val stale=session(owner);store.recoverOwner(owner.id)
            mvc.perform(get("/api/admin/assistant").with(user(owner.id)).session(stale)).andExpect(status().isUnauthorized)
        }
    }
    @Test fun `Discord identity requires single use owner code and current epoch`() {
        AuthStore(temp.resolve("auth")).use {store->
            val owner=store.accounts().single().id;val identity=AssistantIdentity(store)
            assertNull(identity.resolveDiscord("d","g"));val code=identity.code(owner);assertEquals(owner,identity.link(code,"d","g"))
            assertFails {identity.link(code,"other","g")};assertEquals(owner,identity.resolveDiscord("d","g"));assertNull(identity.resolveDiscord("d","other"))
            store.recoverOwner(owner);assertNull(identity.resolveDiscord("d","g"))
            identity.link(identity.code(owner),"d","g");identity.revoke(owner);assertNull(identity.resolveDiscord("d","g"))
            store.invite("member",emptyMap());assertFails {identity.code(store.accounts().first {!it.owner}.id)}
        }
    }
}
