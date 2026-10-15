package io.github.openfinalshell.android.core.termops

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class CommandHistoryIndexTest {
    private var nextId = 0
    private fun index(existing: List<CommandHistoryEntry> = emptyList()) =
        CommandHistoryIndex(existing) { "id-${nextId++}" }

    @Test
    fun `the same command merges the count and refreshes the submission time`() {
        val history = index()
        val first = history.record("ls -al", 1_000)
        val second = history.record("ls -al", 3_000)
        assertEquals(1, history.entries().size)
        assertEquals(first.id, second.id)
        assertEquals(2, second.useCount)
        assertEquals(3_000, second.lastSubmittedAt)
        assertEquals(1_000, second.createdAt)

        history.record("第二条", 2_000)
        history.record("第三条", 4_000)
        history.record("第一条", 5_000)
        assertEquals(listOf("第一条", "第三条", "ls -al", "第二条"), history.entries().map { it.text })
        history.record("ls -al", 6_000)
        assertEquals("ls -al", history.entries().first().text)
        assertEquals(3, history.entries().first().useCount)
        assertEquals("echo \"你好 🌏\"", history.record("echo \"你好 🌏\"", 7_000).text)
    }

    @Test
    fun `only the 1000 most recently submitted commands are kept`() {
        val history = index()
        repeat(CommandHistoryIndex.MAX_ENTRIES + 1) { history.record("cmd-$it", it.toLong()) }
        val entries = history.entries()
        assertEquals(CommandHistoryIndex.MAX_ENTRIES, entries.size)
        assertEquals("cmd-1000", entries.first().text)
        assertTrue(entries.none { it.text == "cmd-0" })
        assertEquals(listOf("id-0"), history.evicted().map { it.id })

        repeat(50) { history.record("cmd-1", 10_000L + it) }
        assertEquals(CommandHistoryIndex.MAX_ENTRIES, history.entries().size)
        assertEquals("cmd-1", history.entries().first().text)
        assertEquals(51, history.entries().first().useCount)
        assertTrue(history.entries().any { it.text == "cmd-999" })

        history.record("cmd-new", 20_000)
        assertEquals("cmd-new", history.entries().first().text)
        assertTrue(history.entries().none { it.text == "cmd-2" })
        assertEquals(CommandHistoryIndex.MAX_ENTRIES, history.entries().size)
    }

    @Test
    fun `commands over 32768 characters are rejected and shorter ones are kept whole`() {
        val history = index()
        assertFailsWith<IllegalArgumentException> { history.record("", 1) }
        assertFailsWith<IllegalArgumentException> { history.record("   ", 1) }
        assertFailsWith<IllegalArgumentException> { history.record("a".repeat(CommandHistoryIndex.MAX_CHARS + 1), 1) }
        assertTrue(history.entries().isEmpty())
        assertEquals(CommandHistoryIndex.MAX_CHARS, history.record("b".repeat(CommandHistoryIndex.MAX_CHARS), 2).text.length)
        assertEquals(2001, history.record("c".repeat(2001), 3).text.length)
    }

    @Test
    fun `keyword filtering is case insensitive and clear removes every row`() {
        val history = index()
        history.record("systemctl status nginx", 1_000)
        history.record("journalctl -xe", 2_000)
        assertEquals(listOf("journalctl -xe", "systemctl status nginx"), history.filter("CTL").map { it.text })
        assertEquals(listOf("systemctl status nginx"), history.filter("Nginx").map { it.text })
        assertEquals(2, history.filter("").size)
        history.clear()
        assertTrue(history.entries().isEmpty())
        history.record("journalctl -xe", 3_000)
        assertEquals(listOf("journalctl -xe"), history.entries().map { it.text })
    }
}
