package io.github.openfinalshell.android.core.sftp

import io.github.openfinalshell.android.core.ssh.SftpEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SftpListingTest {
    @Test
    fun `directories stay ahead of files and names share one order`() {
        val entries = listOf(
            SftpEntry("b.txt", "/b.txt", SftpEntry.Type.FILE),
            SftpEntry("zeta", "/zeta", SftpEntry.Type.DIRECTORY),
            SftpEntry("A.txt", "/A.txt", SftpEntry.Type.FILE),
            SftpEntry("mid", "/mid", SftpEntry.Type.DIRECTORY)
        )

        assertEquals(listOf("mid", "zeta", "A.txt", "b.txt"), orderedSftpEntries(entries).map { it.name })
        assertEquals(listOf("mid", "zeta", "A.txt", "b.txt"), orderedSftpEntries(entries.asReversed()).map { it.name })
    }
}
