package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class CsrfRefreshTest {
    @TempDir lateinit var temp:Path
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import(SecurityConfig::class,AuthApi::class)
    class Web
    @Test fun `expired session token is rejected and a freshly fetched token is accepted`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),"AuthStore needs POSIX permissions")
        val manager=mock(ServerManager::class.java);`when`(manager.root).thenReturn(temp.resolve("profile").createDirectories())
        AnnotationConfigWebApplicationContext().use {context->
            context.servletContext=MockServletContext();context.register(Web::class.java)
            context.addBeanFactoryPostProcessor {factory->factory.registerSingleton("manager",manager)}
            context.refresh()
            val mvc=MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            val old=MockHttpSession()
            val oldToken=Regex("\"csrf\":\"([^\"]+)\"").find(mvc.perform(get("/api/auth/session").session(old)).andExpect(status().isOk).andReturn().response.contentAsString)!!.groupValues[1]
            old.invalidate() // server side session expiry
            val body="{\"reauthenticate\":false}"
            mvc.perform(post("/api/auth/login/options").header("X-CSRF-TOKEN",oldToken).session(MockHttpSession()).contentType("application/json").content(body)).andExpect(status().isForbidden)
            val fresh=MockHttpSession()
            val newToken=Regex("\"csrf\":\"([^\"]+)\"").find(mvc.perform(get("/api/auth/session").session(fresh)).andReturn().response.contentAsString)!!.groupValues[1]
            assertNotEquals(oldToken,newToken)
            val retry=mvc.perform(post("/api/auth/login/options").header("X-CSRF-TOKEN",newToken).session(fresh).contentType("application/json").content(body)).andReturn().response.status
            assertNotEquals(403,retry)
        }
    }
}
