package io.github.openfinalshell.android.core.sftp

import io.github.openfinalshell.android.core.filesync.AcknowledgedRemote
import io.github.openfinalshell.android.core.filesync.RemoteSaveDecision
import io.github.openfinalshell.android.core.filesync.RemoteSnapshot
import io.github.openfinalshell.android.core.filesync.SaveInput
import io.github.openfinalshell.android.core.filesync.SaveRelease
import io.github.openfinalshell.android.core.ssh.AtomicReplaceUnavailable
import io.github.openfinalshell.android.core.ssh.SftpChannel
import io.github.openfinalshell.android.core.ssh.SftpEntry
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

data class RemoteTextDocument(
    val path: String,
    val text: String,
    val fingerprint: String,
    val permissions: Int?,
    val encoding: String = "utf8",
    val hasBom: Boolean = false,
    val eol: String = "lf",
    val resolvedPath: String = path,
    val byteSize: Int = 0,
)
class RemoteTextConflict : IllegalStateException("remote document has changed")
class RemoteTextRetargeted : IllegalStateException("remote path changed; reopen the file")
class RemoteTextShrink(val remoteBytes: Long, val localBytes: Long) :
    IllegalStateException("remote text shrank from $remoteBytes to $localBytes")
class RemoteTextEncodingUnavailable : IllegalArgumentException("remote text encoding is unavailable")
class RemoteTextEncodingFailure : IllegalArgumentException("remote text cannot round-trip with this encoding")

/** The bytes contain NUL, so the file is binary rather than text in any supported encoding. */
class RemoteTextBinary : IllegalArgumentException("remote file is binary")

enum class RemoteTextEncoding(val id: String, val charsetName: String, val label: String) {
    UTF8("utf8", "UTF-8", "UTF-8"),
    GB18030("gb18030", "GB18030", "GB18030"),
    GBK("gbk", "GBK", "GBK"),
    BIG5("big5", "Big5", "Big5"),
    LATIN1("latin1", "ISO-8859-1", "Latin-1")
}

/** Explicit desktop-compatible encoding choices; no guessing or replacement characters. */
object RemoteTextEditor {
    const val MAX_BYTES = RemoteSaveDecision.MAX_BYTES
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun isEncodingAvailable(encoding: RemoteTextEncoding): Boolean = Charset.isSupported(encoding.charsetName)

    internal fun resolveEncoding(id: String, available: (String) -> Boolean = Charset::isSupported): RemoteTextEncoding {
        val normalized = when (val name = id.trim().lowercase()) {
            "utf-8" -> "utf8"
            "gb-18030" -> "gb18030"
            "gb2312", "cp936" -> "gbk"
            "cp950" -> "big5"
            "iso-8859-1" -> "latin1"
            else -> name
        }
        return RemoteTextEncoding.entries.firstOrNull { it.id == normalized && available(it.charsetName) }
            ?: throw RemoteTextEncodingUnavailable()
    }

    suspend fun load(channel: SftpChannel, path: String, encoding: String = "utf8"): RemoteTextDocument {
        val format = resolveEncoding(encoding)
        val entry = channel.list(path.substringBeforeLast('/').ifEmpty { "/" }).first { it.path == path }
        require(entry.type == SftpEntry.Type.FILE && (entry.size ?: Long.MAX_VALUE) <= MAX_BYTES)
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= MAX_BYTES) {
            val part = channel.readChunk(path, bytes.size().toLong(), minOf(32 * 1024, MAX_BYTES + 1 - bytes.size()))
            if (part.isEmpty()) break
            bytes.write(part)
        }
        val raw = bytes.toByteArray()
        require(raw.size <= MAX_BYTES) { "remote text file too large" }
        if (raw.any { it == 0.toByte() }) throw RemoteTextBinary()
        val hasBom = format == RemoteTextEncoding.UTF8 && raw.take(3).toByteArray().contentEquals(UTF8_BOM)
        val body = if (hasBom) raw.copyOfRange(3, raw.size) else raw
        val charset = Charset.forName(format.charsetName)
        val text = decode(body, charset)
        if (!encode(text, charset).contentEquals(body)) throw RemoteTextEncodingFailure()
        // Keep original CR/LF bytes represented in the text. Mixed endings are never silently unified.
        return RemoteTextDocument(
            path, text, digest(raw), entry.permissions, format.id, hasBom, detectEol(text),
            resolvedPath = channel.realpath(path),
            byteSize = raw.size,
        )
    }

    /**
     * First save of a file that does not exist yet. The bytes go straight to [SftpChannel.createExclusive];
     * a local plaintext temp file is not created, and an existing target is left untouched.
     */
    suspend fun create(
        channel: SftpChannel,
        path: String,
        text: String,
        encoding: String = "utf8",
        eol: String = "lf",
        hasBom: Boolean = false,
    ): RemoteTextDocument {
        val prepared = prepare(encoding, eol, hasBom, text)
        enforce(RemoteSaveDecision.decide(SaveInput(
            encodedSize = prepared.bytes.size.toLong(),
            creating = true,
            remote = RemoteSnapshot.Absent,
            openedRealPath = path,
            atomicAvailable = true,
            allowNonAtomic = true,
        )))
        channel.createExclusive(path, prepared.bytes)
        return RemoteTextDocument(
            path = path,
            text = prepared.text,
            fingerprint = digest(prepared.bytes),
            permissions = null,
            encoding = prepared.format.id,
            hasBom = hasBom && prepared.format == RemoteTextEncoding.UTF8,
            eol = detectEol(prepared.text),
            resolvedPath = channel.realpath(path),
            byteSize = prepared.bytes.size,
        )
    }

    suspend fun save(
        channel: SftpChannel,
        original: RemoteTextDocument,
        text: String,
        allowNonAtomic: Boolean = false,
        release: SaveRelease? = null,
    ): RemoteTextDocument {
        val gates = release ?: SaveRelease(
            overwriteRemoteChanges = false,
            allowNonAtomic = allowNonAtomic,
            allowShrink = false,
        )
        val prepared = prepare(original.encoding, original.eol, original.hasBom, text)
        val loaded = try {
            load(channel, original.path, original.encoding)
        } catch (_: NoSuchElementException) {
            null
        }
        val remote = if (loaded == null) {
            RemoteSnapshot.Absent
        } else {
            RemoteSnapshot.Present(loaded.resolvedPath, loaded.fingerprint, loaded.byteSize.toLong())
        }
        // Atomic support is discovered by trying the replace. The flag must not skip the other gates.
        enforce(RemoteSaveDecision.decide(SaveInput(
            roundTripOk = true,
            containsNul = false,
            encodedSize = prepared.bytes.size.toLong(),
            openedRealPath = original.resolvedPath,
            remote = remote,
            baselineFingerprint = original.fingerprint,
            atomicAvailable = true,
            overwriteRemoteChanges = gates.overwriteRemoteChanges,
            allowNonAtomic = true,
            allowShrink = gates.allowShrink,
            acknowledged = gates.acknowledged,
        )))
        if (gates.allowNonAtomic) {
            channel.write(original.path, prepared.bytes)
        } else {
            val temp = original.path.substringBeforeLast('/').ifEmpty { "/" }.trimEnd('/') + "/.ofs-edit-${UUID.randomUUID()}.tmp"
            try {
                channel.write(temp, prepared.bytes)
                original.permissions?.let { channel.permissions(temp, it and 0xFFF) }
                val latest = try {
                    load(channel, original.path, original.encoding)
                } catch (_: NoSuchElementException) {
                    null
                }
                if (latest == null) {
                    val restoring = gates.overwriteRemoteChanges && gates.acknowledged is AcknowledgedRemote.Absent
                    if (!restoring) throw RemoteTextConflict()
                } else if (latest.resolvedPath != original.resolvedPath) {
                    throw RemoteTextRetargeted()
                } else if (replacedConflict(gates, original, latest.fingerprint)) {
                    throw RemoteTextConflict()
                }
                channel.atomicReplace(temp, original.path)
            } finally { runCatching { channel.delete(temp) } }
        }
        return original.copy(
            text = prepared.text,
            fingerprint = digest(prepared.bytes),
            eol = detectEol(prepared.text),
            byteSize = prepared.bytes.size,
        )
    }

    private fun prepare(encoding: String, eol: String, hasBom: Boolean, text: String): Prepared {
        require(text.length <= MAX_BYTES && !text.contains('\u0000')) { "unsupported remote text file" }
        val format = resolveEncoding(encoding)
        val charset = Charset.forName(format.charsetName)
        val outputText = if (eol == "mixed") text else {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            when (eol) { "crlf" -> normalized.replace("\n", "\r\n"); "cr" -> normalized.replace('\n', '\r'); else -> normalized }
        }
        val encoded = encode(outputText, charset)
        if (decode(encoded, charset) != outputText) throw RemoteTextEncodingFailure()
        val bytes = if (hasBom && format == RemoteTextEncoding.UTF8) UTF8_BOM + encoded else encoded
        require(bytes.size <= MAX_BYTES && bytes.none { it == 0.toByte() })
        return Prepared(outputText, bytes, format)
    }

    private fun replacedConflict(gates: SaveRelease, original: RemoteTextDocument, latestFingerprint: String): Boolean {
        if (!gates.overwriteRemoteChanges) return latestFingerprint != original.fingerprint
        val acknowledged = gates.acknowledged
        return acknowledged !is AcknowledgedRemote.Content || acknowledged.fingerprint != latestFingerprint
    }

    private fun enforce(decision: RemoteSaveDecision) {
        when (decision) {
            RemoteSaveDecision.Proceed -> Unit
            is RemoteSaveDecision.HardReject -> when (decision.reason) {
                RemoteSaveDecision.HardReject.Reason.ENCODING -> throw RemoteTextEncodingFailure()
                RemoteSaveDecision.HardReject.Reason.NUL,
                RemoteSaveDecision.HardReject.Reason.TOO_LARGE -> throw IllegalArgumentException("unsupported remote text file")
                RemoteSaveDecision.HardReject.Reason.REALPATH_CHANGED -> throw RemoteTextRetargeted()
            }
            is RemoteSaveDecision.Conflict -> throw RemoteTextConflict()
            is RemoteSaveDecision.ShrinkConfirm -> throw RemoteTextShrink(decision.remoteBytes, decision.localBytes)
            RemoteSaveDecision.NonAtomicConfirm -> throw AtomicReplaceUnavailable()
            RemoteSaveDecision.AlreadyExists -> throw IllegalStateException("remote file already exists")
            RemoteSaveDecision.Reread, RemoteSaveDecision.KeepDraft -> error("encoding switch is not a save")
        }
    }

    private data class Prepared(val text: String, val bytes: ByteArray, val format: RemoteTextEncoding)

    private fun decode(bytes: ByteArray, charset: Charset): String = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { throw RemoteTextEncodingFailure() }

    private fun encode(text: String, charset: Charset): ByteArray = try {
        val encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) { throw RemoteTextEncodingFailure() }

    private fun detectEol(text: String): String {
        val crlf = Regex("\r\n").findAll(text).count()
        val lf = text.count { it == '\n' } - crlf
        val cr = text.count { it == '\r' } - crlf
        return when {
            listOf(crlf, lf, cr).count { it > 0 } > 1 -> "mixed"
            crlf > 0 -> "crlf"
            cr > 0 -> "cr"
            else -> "lf"
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
