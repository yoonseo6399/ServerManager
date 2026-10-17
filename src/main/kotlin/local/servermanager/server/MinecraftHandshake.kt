package local.servermanager

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class GameHandshake(val host:String,val bytes:ByteArray)
object MinecraftHandshake {
    private fun varint(input:InputStream,raw:ByteArrayOutputStream?=null):Int {
        var value=0
        repeat(5) { n ->
            val b=input.read();if(b<0)throw EOFException();raw?.write(b)
            require(n!=4 || b and 0xf0==0) { "잘못된 Minecraft VarInt" }
            value=value or ((b and 0x7f) shl (7*n))
            if(b and 0x80==0)return value
        }
        error("Minecraft VarInt가 너무 깁니다")
    }
    fun read(input:InputStream):GameHandshake {
        val raw=ByteArrayOutputStream()
        val size=varint(input,raw);require(size in 1..4096) { "잘못된 Minecraft handshake 크기" }
        val payload=input.readNBytes(size);require(payload.size==size) { "Minecraft handshake가 끊겼습니다" }
        raw.write(payload)
        val data=ByteArrayInputStream(payload)
        require(varint(data)==0 && varint(data)>=0) { "Minecraft handshake가 아닙니다" }
        val length=varint(data);require(length in 1..1024 && length<=data.available())
        val address=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data.readNBytes(length))).toString()
        require(data.read()>=0 && data.read()>=0)
        require(varint(data) in 1..3 && data.available()==0)
        val hostname=GameDomains.host(address.substringBefore('\u0000'))
        return GameHandshake(hostname,raw.toByteArray())
    }
}
