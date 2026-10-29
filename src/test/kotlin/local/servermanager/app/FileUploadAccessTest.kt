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

class FileUploadAccessTest {
 @TempDir lateinit var temp:Path
 @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig::class,FileUploadApi::class,FileUploads::class,RemoteFiles::class,Errors::class)
 class Web
 @Test fun `all upload operations require current server write permission owner token and CSRF`() {
  val manager=mock(ServerManager::class.java);`when`(manager.root).thenReturn(temp.resolve("profile").createDirectories());manager.root.resolve("security").createDirectories()
  `when`(manager.instance("test")).thenReturn(Instance("test","test",loaderVersion="21.1.219",port=25565,memoryMb=512,javaPath="java"))
  AnnotationConfigWebApplicationContext().use {context->
   context.servletContext=MockServletContext();context.register(Web::class.java);context.addBeanFactoryPostProcessor {it.registerSingleton("manager",manager)};context.refresh()
   val store=context.getBean(AuthStore::class.java);val owner=store.accounts().single().id
   val viewer=store.invitation(store.invite("viewer",mapOf("test" to "VIEWER")))!!.userId
   val admin=store.invitation(store.invite("admin",mapOf("test" to "ADMIN")))!!.userId
   val mvc=MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
   fun session()=MockHttpSession().also {it.setAttribute("sm.epoch",0);it.setAttribute("sm.started",System.currentTimeMillis())}
   val path="/api/instances/test/file-uploads";val body="""{"filename":"mod.jar","size":3,"path":"mods","clientId":"00000000-0000-0000-0000-000000000001"}"""
   mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized)
   mvc.perform(post(path).with(user(owner)).session(session()).contentType("application/json").content(body)).andExpect(status().isForbidden)
   mvc.perform(post(path).with(user(viewer)).session(session()).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden)
   val result=mvc.perform(post(path).with(user(owner)).session(session()).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andReturn()
   val token=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(result.response.contentAsString).path("token").asText()
   mvc.perform(get("$path/$token").with(user(admin)).session(session())).andExpect(status().isNotFound)
   mvc.perform(get(path).with(user(viewer)).session(session())).andExpect(status().isForbidden)
   mvc.perform(put("$path/$token/content?offset=0").with(user(owner)).session(session()).contentType("application/octet-stream").content(byteArrayOf(1))).andExpect(status().isForbidden)
   mvc.perform(put("$path/$token/content?offset=0").with(user(owner)).session(session()).with(csrf()).contentType("application/octet-stream").content(byteArrayOf(1,2,3))).andExpect(status().isOk).andExpect(jsonPath("$.received").value(3))
   mvc.perform(post("$path/$token/cancel").with(user(admin)).session(session()).with(csrf())).andExpect(status().isNotFound)
   mvc.perform(post("$path/$token/cancel").with(user(owner)).session(session()).with(csrf())).andExpect(status().isOk).andExpect(jsonPath("$.phase").value("CANCELLED"))
  }
 }
}
