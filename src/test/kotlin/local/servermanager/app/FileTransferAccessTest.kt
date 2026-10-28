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

class FileTransferAccessTest {
    @TempDir lateinit var root:Path
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig::class,FolderDownloadApi::class,PackUpdateApi::class,Errors::class)
    class Web
    @Test fun `viewer can prepare and view downloads with CSRF but cannot update packs or access other instances`() {
        val manager=mock(ServerManager::class.java);`when`(manager.root).thenReturn(root.resolve("profile").createDirectories());manager.root.resolve("security").createDirectories()
        val downloads=mock(FolderDownloads::class.java);val updates=mock(PackUpdates::class.java)
        val status=FolderDownloadStatus("token","READY","ready")
        `when`(downloads.create("server","world","snapshot")).thenReturn(status);`when`(downloads.status("server","token")).thenReturn(status)
        AnnotationConfigWebApplicationContext().use {context->
            context.servletContext=MockServletContext();context.register(Web::class.java);context.addBeanFactoryPostProcessor {factory->factory.registerSingleton("manager",manager);factory.registerSingleton("downloads",downloads);factory.registerSingleton("updates",updates)};context.refresh()
            val store=context.getBean(AuthStore::class.java);val viewer=store.invitation(store.invite("Viewer",mapOf("server" to "VIEWER")))!!.userId
            val mvc=MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            fun session()=MockHttpSession().also {it.setAttribute("sm.epoch",0);it.setAttribute("sm.started",System.currentTimeMillis())}
            val body="""{"path":"world","source":"snapshot"}"""
            mvc.perform(post("/api/instances/server/folder-downloads").with(user(viewer)).session(session()).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk).andExpect(jsonPath("$.phase").value("READY"))
            mvc.perform(get("/api/instances/server/folder-downloads/token").with(user(viewer)).session(session())).andExpect(status().isOk)
            mvc.perform(post("/api/instances/server/folder-downloads").with(user(viewer)).session(session()).contentType("application/json").content(body)).andExpect(status().isForbidden)
            mvc.perform(get("/api/instances/other/folder-downloads/token").with(user(viewer)).session(session())).andExpect(status().isForbidden)
            mvc.perform(post("/api/instances/server/pack-update").with(user(viewer)).session(session()).with(csrf()).contentType("application/json").content("""{"token":"x","mode":"replace"}""")).andExpect(status().isForbidden)
            mvc.perform(delete("/api/instances/server/files?path=mods/test.jar").with(user(viewer)).session(session()).with(csrf())).andExpect(status().isForbidden)
        }
    }
}
