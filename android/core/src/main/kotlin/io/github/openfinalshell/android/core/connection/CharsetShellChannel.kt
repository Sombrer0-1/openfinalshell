package io.github.openfinalshell.android.core.connection

import io.github.openfinalshell.android.core.ssh.ShellChannel
import io.github.openfinalshell.android.core.ssh.ShellEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The terminal emulator always speaks UTF-8. This wrapper keeps a remote charset's decode and
 * encode state across channel reads and writes.
 */
class CharsetShellChannel(
    private val inner: ShellChannel,
    charsetName: String
) : ShellChannel {
    private val bridge = CharsetBridge(charsetName)

    override val ownsEmulator: Boolean get() = inner.ownsEmulator
    override val events: Flow<ShellEvent> get() = inner.events
    override val output: Flow<ByteArray> = inner.output.map { bridge.decodeRemoteToUtf8(it) }

    override suspend fun write(data: ByteArray) {
        val encoded = bridge.encodeUtf8ToRemote(data)
        if (encoded.isNotEmpty()) inner.write(encoded)
    }

    suspend fun writeRemoteText(text: String) {
        val encoded = bridge.encodeTextToRemote(text)
        if (encoded.isNotEmpty()) inner.write(encoded)
    }

    override suspend fun resize(cols: Int, rows: Int) = inner.resize(cols, rows)

    override suspend fun close() = inner.close()
}
