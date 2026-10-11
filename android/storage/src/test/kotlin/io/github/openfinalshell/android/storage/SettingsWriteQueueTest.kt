package io.github.openfinalshell.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWriteQueueTest {
    @Test
    fun `edits made before load replay onto the stored document`() {
        val queue = SettingsWriteQueue()
        queue.edit { it.copy(maskHosts = false) }

        assertNull(queue.snapshotForSave())
        val shown = queue.confirm(AndroidSettings(theme = "dark"))

        assertEquals("dark", shown.theme)
        assertFalse(shown.maskHosts)
        val batch = queue.snapshotForSave()
        assertEquals("dark", batch!!.settings.theme)
        assertFalse(batch.settings.maskHosts)
    }

    @Test
    fun `an in flight snapshot keeps edits that arrive after it`() {
        val queue = SettingsWriteQueue()
        queue.confirm(AndroidSettings(theme = "light"))
        queue.edit { it.copy(theme = "dark") }
        val batch = queue.snapshotForSave()!!

        queue.edit { it.copy(terminalFontSize = 20) }
        queue.commit(batch)

        val current = queue.current()
        assertEquals("dark", current.theme)
        assertEquals(20, current.terminalFontSize)
        val followUp = queue.snapshotForSave()
        assertEquals(20, followUp!!.settings.terminalFontSize)
        assertEquals("dark", followUp.settings.theme)
    }

    @Test
    fun `confirming a migrated document is saved even without a later edit`() {
        val queue = SettingsWriteQueue()
        val stored = AndroidSettings(language = "en-US")
        val shown = queue.confirm(stored.copy(language = "zh-CN"), dirty = true)

        assertEquals("zh-CN", shown.language)
        assertTrue(queue.needsSave())
        assertEquals("zh-CN", queue.snapshotForSave()!!.settings.language)
    }

    @Test
    fun `a failed save can be retried with the same pending edits`() {
        val queue = SettingsWriteQueue()
        queue.confirm(AndroidSettings())
        queue.edit { it.copy(sftpConcurrency = 4) }
        val first = queue.snapshotForSave()!!
        val second = queue.snapshotForSave()!!

        assertEquals(first, second)
        queue.commit(second)
        assertFalse(queue.needsSave())
    }
}
