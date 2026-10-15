package io.github.openfinalshell.android.core.connection

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction

/**
 * Converts between the remote shell charset and the UTF-8 bytes the terminal emulator speaks.
 * Incomplete sequences stay in the decoder or encoder until the next chunk arrives.
 */
class CharsetBridge(charsetName: String) {
    private val remote = charsetForName(charsetName)
    private val remoteDecoder = newDecoder(remote)
    private val remoteEncoder = newEncoder(remote)
    private val utf8Decoder = newDecoder(Charsets.UTF_8)
    private val utf8Encoder = newEncoder(Charsets.UTF_8)
    private var pendingRemote = ByteArray(0)
    private var pendingUtf8 = ByteArray(0)

    fun decodeRemoteToUtf8(chunk: ByteArray): ByteArray {
        if (chunk.isEmpty() && pendingRemote.isEmpty()) return ByteArray(0)
        val text = decode(remoteDecoder, pendingRemote + chunk) { pendingRemote = it }
        return encodeComplete(utf8Encoder, text)
    }

    fun encodeUtf8ToRemote(chunk: ByteArray): ByteArray {
        if (chunk.isEmpty() && pendingUtf8.isEmpty()) return ByteArray(0)
        val text = decode(utf8Decoder, pendingUtf8 + chunk) { pendingUtf8 = it }
        return encodeComplete(remoteEncoder, text)
    }

    fun encodeTextToRemote(text: String): ByteArray = encodeComplete(remoteEncoder, text)

    private fun decode(decoder: CharsetDecoder, inputBytes: ByteArray, keepPending: (ByteArray) -> Unit): String {
        val input = ByteBuffer.wrap(inputBytes)
        val output = CharBuffer.allocate((inputBytes.size + 1) * 2)
        val text = StringBuilder()
        while (true) {
            val result = decoder.decode(input, output, false)
            output.flip()
            text.append(output)
            output.clear()
            when {
                result.isUnderflow -> {
                    val remaining = ByteArray(input.remaining())
                    input.get(remaining)
                    keepPending(remaining)
                    return text.toString()
                }
                result.isOverflow -> continue
                else -> result.throwException()
            }
        }
    }

    private fun encodeComplete(encoder: CharsetEncoder, text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val input = CharBuffer.wrap(text)
        var output = ByteBuffer.allocate(text.length * 4 + 8)
        while (true) {
            val result = encoder.encode(input, output, false)
            if (result.isOverflow) {
                val grown = ByteBuffer.allocate(output.capacity() * 2)
                output.flip()
                grown.put(output)
                output = grown
                continue
            }
            if (!result.isUnderflow) result.throwException()
            output.flip()
            return ByteArray(output.remaining()).also { output.get(it) }
        }
    }

    private fun newDecoder(charset: Charset): CharsetDecoder =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private fun newEncoder(charset: Charset): CharsetEncoder =
        charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
}

fun charsetForName(name: String): Charset {
    val normalized = name.trim().lowercase().replace('_', '-')
    val mapped = when (normalized) {
        "utf8", "utf-8" -> "UTF-8"
        "gbk", "cp936" -> "GBK"
        "gb18030" -> "GB18030"
        "big5", "cp950" -> "Big5"
        "latin1", "latin-1", "iso-8859-1", "iso8859-1" -> "ISO-8859-1"
        else -> name.trim()
    }
    return Charset.forName(mapped)
}

fun charsetNeedsTranslation(name: String): Boolean {
    val normalized = name.trim().lowercase().replace('_', '-')
    return normalized !in setOf("utf8", "utf-8", "")
}
