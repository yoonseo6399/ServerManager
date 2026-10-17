package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress
import kotlin.io.path.*
import kotlin.test.*

class WebDavSyncTest {
    @TempDir lateinit var temp:Path
    @Test fun `WebDAV password stays encrypted and file manifest changes only for changed files`() {
        val manager=mock(ServerManager::class.java)
        `when`(manager.root).thenReturn(temp)
            `when`(manager.runtime("instance")).thenReturn(RuntimeState())
            `when`(manager.instance("instance")).thenReturn(Instance("instance","test",loaderVersion="21.1.249",port=25565,memoryMb=512,javaPath="java"))
        `when`(manager.onSignal(any<(ServerSignal)->Unit>() ?: {})).thenReturn(AutoCloseable {})
        val settings=WebDavSettings(manager)
        val password="private-app-password-for-test"
        settings.save(WebDavSave(WebDavConfig(true,"https://cloud.example.test/remote.php/dav/files/me/ServerManager","me"),0,password))
        assertTrue(settings.view().passwordConfigured)
        assertEquals(password,WebDavSettings(manager).credentials()?.second)
        assertFalse(temp.resolve("security/webdav/settings.json").readText().contains(password))
        val sync=WebDavSync(manager,settings)
        try {
            val server=temp.resolve("server").also { it.createDirectories() }
            server.resolve("world").createDirectories()
            server.resolve("world/level.dat").writeText("first")
            server.resolve("mods").createDirectories()
            server.resolve("mods/a.jar").writeText("same")
            val (before,dirs)=sync.collect(server)
            assertEquals(setOf("mods","world"),dirs)
            server.resolve("world/level.dat").writeText("second")
            val (after,_)=sync.collect(server)
            assertEquals(setOf("world/level.dat"),after.filter { (path,file)->before[path]!=file }.keys)
            server.resolve("mods/a.jar").deleteIfExists()
            val (deleted,_)=sync.collect(server)
            assertEquals(setOf("mods/a.jar"),before.keys-deleted.keys)
            Files.createSymbolicLink(server.resolve("outside"),temp)
            assertFailsWith<IllegalArgumentException> { sync.collect(server) }
        } finally { sync.close() }
    }

    @Test fun `sync uploads only changed blobs and rejects remote generation conflicts`() {
        val objects=mutableMapOf<String,ByteArray>()
        val etags=mutableMapOf<String,String>()
        val writes=mutableMapOf<String,Int>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            val path=exchange.requestURI.path
            val old=objects[path]
            val code=when(exchange.requestMethod) {
                "MKCOL" -> 201
                "HEAD","GET" -> if(old==null)404 else 200
                "PUT" -> {
                    val match=exchange.requestHeaders.getFirst("If-Match")
                    val absent=exchange.requestHeaders.getFirst("If-None-Match")
                    if((match!=null && match!=etags[path]) || (absent=="*" && old!=null))412 else {
                        objects[path]=exchange.requestBody.readAllBytes()
                        writes[path]=(writes[path] ?: 0)+1
                        etags[path]="\"${writes[path]}\""
                        if(old==null)201 else 204
                    }
                }
                else -> 405
            }
            etags[path]?.let { exchange.responseHeaders.add("ETag",it) }
            val body=if(exchange.requestMethod=="GET" && code==200) old!! else byteArrayOf()
            exchange.sendResponseHeaders(code,if(exchange.requestMethod=="HEAD" || code==204) -1 else body.size.toLong())
            if(body.isNotEmpty())exchange.responseBody.write(body)
            exchange.close()
        }
        server.start()
        try {
            val manager=mock(ServerManager::class.java)
            val settings=mock(WebDavSettings::class.java)
            `when`(manager.root).thenReturn(temp)
            `when`(manager.runtime("instance")).thenReturn(RuntimeState())
            `when`(manager.instance("instance")).thenReturn(Instance("instance","test",loaderVersion="21.1.249",port=25565,memoryMb=512,javaPath="java"))
            `when`(manager.serverDir("instance")).thenReturn(temp.resolve("server"))
            `when`(manager.view("instance")).thenReturn(mapOf("state" to "STOPPED"))
            `when`(manager.onSignal(any<(ServerSignal)->Unit>() ?: {})).thenReturn(AutoCloseable {})
            `when`(settings.credentials()).thenReturn(WebDavConfig(true,"http://127.0.0.1:${server.address.port}/dav","user") to "test-password")
            temp.resolve("instance").createDirectories()
            val base=temp.resolve("server").also { it.createDirectories() }
            base.resolve("world.dat").writeText("first")
            val sync=WebDavSync(manager,settings)
            try {
                val first=sync.syncNow("instance")
                val blobPrefix="/dav/instances/instance/objects/"
                assertEquals(1,writes.keys.count { it.startsWith(blobPrefix) })
                assertEquals(1,writes["/dav/instances/instance/manifest.json"])
                assertEquals(first.generation,sync.syncNow("instance").generation)
                assertEquals(1,writes["/dav/instances/instance/manifest.json"])
                base.resolve("world.dat").writeText("second")
                val second=sync.syncNow("instance")
                assertNotEquals(first.generation,second.generation)
                assertEquals(2,writes.keys.count { it.startsWith(blobPrefix) })
                assertEquals(2,writes["/dav/instances/instance/manifest.json"])
                val path="/dav/instances/instance/manifest.json"
                objects[path]=jacksonObjectMapper().writeValueAsBytes(second.copy(generation="someone-else"))
                etags[path]="\"external\""
                base.resolve("world.dat").writeText("third")
                assertFailsWith<IllegalStateException> { sync.syncNow("instance") }
                assertEquals("third",base.resolve("world.dat").readText())
                assertEquals(2,writes.keys.count { it.startsWith(blobPrefix) })
            } finally { sync.close() }
        } finally { server.stop(0) }
    }
}
