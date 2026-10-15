package io.github.openfinalshell.android.core.filesync

import io.github.openfinalshell.android.core.ssh.SftpEntry
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Operability comes only from [SftpEntry.nameInvalid]. A replacement character in the name is not an error flag.
 */
object RemoteName {
    fun canOperate(nameInvalid: Boolean): Boolean = !nameInvalid

    fun canOperate(entry: SftpEntry): Boolean = canOperate(entry.nameInvalid)

    fun requireOperable(entry: SftpEntry) {
        if (!canOperate(entry)) throw IllegalStateException("remote name is not operable")
    }

    /** Strict UTF-8. Malformed bytes return null so callers never keep a replacement character. */
    fun decodeStrictUtf8(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
