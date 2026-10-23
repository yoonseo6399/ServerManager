package local.servermanager

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Editing must never silently replace undecodable bytes and write them back. */
object EditableText {
    fun decode(bytes:ByteArray):String {
        require(!bytes.contains(0.toByte())) {"바이너리 파일은 원본을 다운로드하세요"}
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch(e:CharacterCodingException) {
            throw IllegalArgumentException("UTF-8 텍스트로 읽을 수 없어 편집을 중단했습니다. 원본을 다운로드하고 인코딩을 확인하세요.",e)
        }
    }
}
