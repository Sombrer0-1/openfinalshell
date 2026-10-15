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

    @Test
    fun `names that fold to the same letters do not follow the server order`() {
        val forward = listOf(
            SftpEntry("readme", "/srv/readme", SftpEntry.Type.FILE),
            SftpEntry("Readme", "/srv/Readme", SftpEntry.Type.FILE)
        )
        val expected = listOf("/srv/Readme", "/srv/readme")
        assertEquals(expected, orderedSftpEntries(forward).map { it.path })
        assertEquals(expected, orderedSftpEntries(forward.asReversed()).map { it.path })
    }

    @Test
    fun `directories stay first and missing values stay last in both directions`() {
        val entries = listOf(
            SftpEntry("file-null", "/n", SftpEntry.Type.FILE, size = null, owner = null, modifiedAtMillis = null),
            SftpEntry("file-small", "/s", SftpEntry.Type.FILE, size = 1, owner = "root", modifiedAtMillis = 10),
            SftpEntry("file-big", "/b", SftpEntry.Type.FILE, size = 5, owner = "ada", modifiedAtMillis = 20),
            SftpEntry("dir", "/d", SftpEntry.Type.DIRECTORY, size = 9, owner = null, modifiedAtMillis = 30),
            SftpEntry("file-same", "/s2", SftpEntry.Type.FILE, size = 5, owner = "ada", modifiedAtMillis = 20),
        )
        assertEquals(
            listOf("dir", "file-small", "file-big", "file-same", "file-null"),
            orderedSftpEntries(entries, SftpSortKey.SIZE, SftpSortDirection.ASC).map { it.name },
        )
        assertEquals(
            listOf("dir", "file-big", "file-same", "file-small", "file-null"),
            orderedSftpEntries(entries, SftpSortKey.SIZE, SftpSortDirection.DESC).map { it.name },
        )
        assertEquals(
            listOf("dir", "file-small", "file-big", "file-same", "file-null"),
            orderedSftpEntries(entries, SftpSortKey.MODIFIED, SftpSortDirection.ASC).map { it.name },
        )
        assertEquals(
            listOf("dir", "file-big", "file-same", "file-small", "file-null"),
            orderedSftpEntries(entries, SftpSortKey.OWNER, SftpSortDirection.ASC).map { it.name },
        )
        assertEquals(
            listOf("dir", "file-small", "file-big", "file-same", "file-null"),
            orderedSftpEntries(entries.asReversed(), SftpSortKey.OWNER, SftpSortDirection.DESC).map { it.name },
        )
    }
}
