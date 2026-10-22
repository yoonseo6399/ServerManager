package local.servermanager

import java.nio.file.Files
import java.nio.file.Path

/** Strata refuses an existing ready file. Each invocation owns a fresh directory, even after a crash. */
internal class StrataLaunchFiles(parent:Path):AutoCloseable {
    val directory:Path=Files.createTempDirectory(parent,"run-")
    val ready:Path=directory.resolve("ready.json")
    val config:Path=directory.resolve("config.json")
    fun prepareConfig(source:Path,window:Int,compressed:Boolean):Path {
        require(window in setOf(8192,16384))
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val body=mapper.readTree(Files.readString(source)) as com.fasterxml.jackson.databind.node.ObjectNode
        val input=body.path("args").map {it.asText()};val args=mutableListOf<String>();var index=0
        while(index<input.size){val option=input[index++];if(option in setOf("--max-context","--kv")){require(index<input.size);index++}else args.add(option)}
        args.addAll(listOf("--max-context",window.toString(),"--kv",if(compressed)"q4_0"else "fp16"))
        body.set<com.fasterxml.jackson.databind.JsonNode>("args",mapper.valueToTree(args));body.set<com.fasterxml.jackson.databind.JsonNode>("args_written",mapper.valueToTree(args))
        Files.writeString(config,mapper.writeValueAsString(body));return config
    }
    override fun close() {
        // Never scan/delete another invocation's files, legacy ready.json, or arbitrary directory contents.
        Files.deleteIfExists(ready)
        Files.deleteIfExists(config)
        Files.deleteIfExists(directory)
    }
}
