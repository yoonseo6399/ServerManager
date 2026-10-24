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
import kotlin.test.assertTrue

class ServerArchiveAccessTest {
    @TempDir lateinit var root:Path
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig::class,ServerArchiveApi::class,Errors::class)
    class Web
    @Test fun `HTTP upload needs owner or global admin CSRF and upload ownership including binary chunks`() {
        val manager=mock(ServerManager::class.java);`when`(manager.root).thenReturn(root.resolve("profile").createDirectories());manager.root.resolve("security").createDirectories()
        val importer=ServerArchiveImporter(manager)
        try {AnnotationConfigWebApplicationContext().use {context->
            context.servletContext=MockServletContext();context.register(Web::class.java);context.addBeanFactoryPostProcessor {factory->factory.registerSingleton("manager",manager);factory.registerSingleton("importer",importer)};context.refresh()
            val store=context.getBean(AuthStore::class.java);val owner=store.accounts().single()
            val member=store.invitation(store.invite("member",emptyMap()))!!.userId
            val admin=store.invitation(store.invite("admin",emptyMap(),AccountRole.ADMIN))!!.userId
            val mvc=MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            fun session()=MockHttpSession().also {it.setAttribute("sm.epoch",0);it.setAttribute("sm.started",System.currentTimeMillis())}
            val body="""{"filename":"server.zip","size":10}"""
            mvc.perform(post("/api/server-archives/uploads").with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized)
            mvc.perform(post("/api/server-archives/uploads").with(user(member)).session(session()).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden)
            mvc.perform(post("/api/server-archives/uploads").with(user(owner.id)).session(session()).contentType("application/json").content(body)).andExpect(status().isForbidden)
            val response=mvc.perform(post("/api/server-archives/uploads").with(user(owner.id)).session(session()).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andReturn()
            val token=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(response.response.contentAsString).path("token").asText()
            assertTrue(token.isNotEmpty())
            mvc.perform(get("/api/server-archives/uploads/$token").with(user(admin)).session(session())).andExpect(status().isNotFound)
            mvc.perform(put("/api/server-archives/uploads/$token?offset=0").with(user(owner.id)).session(session()).contentType("application/octet-stream").content(byteArrayOf(1,2,3))).andExpect(status().isForbidden)
            mvc.perform(put("/api/server-archives/uploads/$token?offset=0").with(user(member)).session(session()).with(csrf()).contentType("application/octet-stream").content(byteArrayOf(1,2,3))).andExpect(status().isForbidden)
            mvc.perform(put("/api/server-archives/uploads/$token?offset=0").with(user(owner.id)).session(session()).with(csrf()).contentType("application/octet-stream").content(byteArrayOf(1,2,3))).andExpect(status().isOk).andExpect(jsonPath("$.received").value(3))
            mvc.perform(put("/api/server-archives/uploads/$token?offset=0").with(user(owner.id)).session(session()).with(csrf()).contentType("application/octet-stream").content(byteArrayOf(1))).andExpect(status().isConflict)
            mvc.perform(delete("/api/server-archives/uploads/$token").with(user(admin)).session(session()).with(csrf())).andExpect(status().isNotFound)
            mvc.perform(delete("/api/server-archives/uploads/$token").with(user(owner.id)).session(session()).with(csrf())).andExpect(status().isOk)
            mvc.perform(post("/api/server-archives/uploads").with(user(admin)).session(session()).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk)
        }} finally {importer.shutdown()}
    }
}
