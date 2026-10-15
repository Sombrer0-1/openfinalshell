package io.github.openfinalshell.android.core.ssh

import io.github.openfinalshell.android.core.filesync.RemoteName
import java.io.IOException
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.sftp.client.SftpErrorDataHandler
import org.apache.sshd.sftp.client.SftpVersionSelector
import org.apache.sshd.sftp.client.impl.DefaultSftpClient
import org.apache.sshd.sftp.client.impl.DefaultSftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants

/**
 * Reads SFTP name bytes before MINA turns them into a String. Invalid UTF-8 is recorded while a
 * directory listing is in progress, and rejected for every other name the client would otherwise
 * operate on.
 */
internal class StrictNameSftpClient(
    session: ClientSession,
    selector: SftpVersionSelector?,
    errorDataHandler: SftpErrorDataHandler?
) : DefaultSftpClient(session, selector, errorDataHandler) {
    private val filenameInvalid = ArrayDeque<Boolean>()
    private var recording = false

    fun beginListing() {
        check(!recording) { "SFTP listing is already being recorded" }
        filenameInvalid.clear()
        recording = true
    }

    fun finishListing(expectedEntries: Int): List<Boolean> {
        recording = false
        val flags = filenameInvalid.toList()
        filenameInvalid.clear()
        if (expectedEntries >= 0 && flags.size != expectedEntries) {
            throw IOException("remote name decode count does not match directory entries")
        }
        return flags
    }

    override fun getReferencedName(cmd: Int, buf: Buffer, nameIndex: Int): String {
        val decoded = RemoteName.decodeStrictUtf8(buf.getBytes())
        val filename = version != SftpConstants.SFTP_V3 || nameIndex % 2 == 0
        if (filename && recording) filenameInvalid.addLast(decoded == null)
        if (decoded == null && !(filename && recording)) {
            throw IOException("remote name is not strict UTF-8")
        }
        return decoded ?: ""
    }
}

internal object StrictSftpClientFactory : DefaultSftpClientFactory() {
    override fun createDefaultSftpClient(
        session: ClientSession,
        selector: SftpVersionSelector,
        errorDataHandler: SftpErrorDataHandler
    ): DefaultSftpClient = StrictNameSftpClient(session, selector, errorDataHandler)
}
