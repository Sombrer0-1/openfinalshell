package io.github.openfinalshell.android.core.filesync

import io.github.openfinalshell.android.core.ssh.SftpEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionBitsTest {
    @Test fun octalAndGridRoundTripTheLowNineBits() {
        assertEquals(mode("644"), PermissionBits.parseOctal("644"))
        assertEquals(mode("644"), PermissionBits.parseOctal("0644"))
        assertEquals("644", PermissionBits.formatOctal(mode("100644")))
        assertArrayEquals(
            booleanArrayOf(true, true, true, true, false, true, true, false, true),
            PermissionBits.toGrid(mode("755")),
        )
        assertEquals(mode("755"), PermissionBits.fromGrid(PermissionBits.toGrid(mode("755"))))
    }

    @Test fun mergeKeepsSetuidSetgidAndSticky() {
        assertEquals(mode("4644"), PermissionBits.merge(mode("4755"), mode("644")))
        assertEquals(mode("1755"), PermissionBits.merge(mode("1755"), mode("755")))
        assertEquals(mode("6640"), PermissionBits.merge(mode("6750"), mode("640")))
        assertEquals(mode("755"), PermissionBits.merge(mode("100644"), mode("4755")))
    }

    private fun mode(text: String) = text.toInt(8)
}

class RemoteNameFlagTest {
    @Test fun onlyTheFlagBlocksAnEntry() {
        val marked = SftpEntry("plain", "/data/plain", SftpEntry.Type.FILE, nameInvalid = true)
        val replaced = SftpEntry("a\uFFFDb", "/data/a\uFFFDb", SftpEntry.Type.FILE, nameInvalid = false)
        assertFalse(RemoteName.canOperate(marked))
        assertTrue(RemoteName.canOperate(replaced))
        assertTrue(replaced.name.contains('\uFFFD'))
        assertThrows(IllegalStateException::class.java) { RemoteName.requireOperable(marked) }
    }

    @Test fun strictUtf8DropsMalformedBytes() {
        assertEquals("目录", RemoteName.decodeStrictUtf8("目录".toByteArray(Charsets.UTF_8)))
        assertNull(RemoteName.decodeStrictUtf8(byteArrayOf(0xFF.toByte())))
    }
}
