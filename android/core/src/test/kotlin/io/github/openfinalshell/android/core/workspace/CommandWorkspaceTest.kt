package io.github.openfinalshell.android.core.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandWorkspaceTest {
    private val alpha = CommandTarget("a", "10.0.0.1", "root", 22, bracketedPaste = false)
    private val beta = CommandTarget("b", "10.0.0.2", "admin", 2200, bracketedPaste = true)

    @Test
    fun normalizeCollapsesLineEndingsAndKeepsMiddleBlankLines() {
        assertEquals("echo a\necho b", CommandBody.normalize("echo a\r\necho b\r\n"))
        assertEquals("echo a\n\necho b", CommandBody.normalize("echo a\n\necho b\n\n"))
        assertEquals("echo a\necho b", CommandBody.normalize("echo a\recho b\r"))
        assertEquals("echo a", CommandBody.normalize("echo a\n"))
    }

    @Test
    fun placeholdersFollowEachConnectionUnlessTheSwitchIsOff() {
        val raw = "ssh {{ host }} {{user}} {{ port }}\n"
        val prepared = CommandBody.prepare(raw, placeholdersEnabled = true, autoEnter = true, targets = listOf(alpha, beta))
        assertEquals("ssh {{ host }} {{user}} {{ port }}", prepared.normalized)
        assertEquals("ssh 10.0.0.1 root 22", prepared.targets[0].expanded)
        assertEquals("ssh 10.0.0.2 admin 2200", prepared.targets[1].expanded)
        val kept = CommandBody.prepare(raw, placeholdersEnabled = false, autoEnter = false, targets = listOf(alpha))
        assertEquals(prepared.normalized, kept.normalized)
        assertEquals(prepared.normalized, kept.targets.single().text)
        assertEquals(prepared.normalized, CommandBody.normalize(raw))
    }

    @Test
    fun autoEnterOnlyAppendsCrAndMultilineInsertNeedsBracketedPaste() {
        val single = CommandBody.prepare("echo hi\r\n", false, false, listOf(alpha))
        assertEquals("echo hi", single.targets.single().text)
        assertFalse(single.targets.single().submit)
        assertFalse(single.targets.single().text!!.contains('\r'))

        val blocked = CommandBody.prepare("echo a\necho b", false, false, listOf(alpha, beta))
        assertEquals(CommandSkipReason.MultilineInsertNeedsBracketedPaste, blocked.targets[0].skipReason)
        assertNull(blocked.targets[0].text)
        assertEquals("echo a\necho b", blocked.targets[1].text)
        assertFalse(blocked.targets[1].submit)

        val submitted = CommandBody.prepare("echo a\r\necho b\r\n", false, true, listOf(alpha))
        assertEquals("echo a\necho b\r", submitted.targets.single().text)
        assertFalse(submitted.targets.single().text!!.contains("\r\n"))
        assertTrue(submitted.targets.single().submit)
        assertArrayEquals("echo a\necho b\r".toByteArray(), submitted.targets.single().bytes)
    }

    @Test
    fun partialFailureKeepsTheBodyAndRetrySkipsSuccesses() {
        val ready = mutableSetOf("a")
        val written = mutableListOf<String>()
        val first = send(ready, written)
        assertFalse(first.clearBody)
        assertEquals("echo {{host}}", first.normalized)
        assertEquals(listOf("a"), first.succeeded)
        assertEquals(listOf("b"), first.retryIds)
        assertEquals(listOf("echo 10.0.0.1"), first.history)
        assertEquals(listOf("a"), written)

        ready += "b"
        val second = CommandBatch.execute(
            "echo {{host}}\n",
            true,
            true,
            first.retryTargets(listOf(alpha, beta)),
            recheck = { it in ready },
            write = { id, _ -> written += id; true }
        )
        assertEquals(listOf("a", "b"), written)
        assertEquals(listOf("echo 10.0.0.2"), second.history)
        assertTrue(second.clearBody)
    }

    @Test
    fun insertDoesNotRecordAndUnsupportedTargetsAreNotWritten() {
        val written = mutableListOf<String>()
        val inserted = CommandBatch.execute(
            "echo hi",
            false,
            false,
            listOf(alpha),
            recheck = { true },
            write = { id, _ -> written += id; true }
        )
        assertTrue(inserted.clearBody)
        assertTrue(inserted.history.isEmpty())
        assertEquals(listOf("a"), written)
    }

    @Test
    fun multilineWithoutPasteIsSkippedAndNotRetried() {
        var checks = 0
        var writes = 0
        val outcome = CommandBatch.execute(
            "echo a\necho b",
            false,
            false,
            listOf(alpha, beta),
            recheck = { checks += 1; true },
            write = { _, _ -> writes += 1; true }
        )
        assertEquals(1, checks)
        assertEquals(1, writes)
        assertEquals(listOf("b"), outcome.succeeded)
        assertEquals("a", outcome.skipped.single().targetId)
        assertTrue(outcome.history.isEmpty())
        assertTrue(outcome.retryIds.isEmpty())
        assertFalse(outcome.clearBody)
    }

    @Test
    fun historyIsOneFullSubmitAndDropsOverlongText() {
        val longOk = "a".repeat(CommandHistoryPolicy.MAX_CHARS)
        val tooLong = "b".repeat(CommandHistoryPolicy.MAX_CHARS + 1)
        val writes = mutableListOf<Int>()
        val outcome = CommandBatch.execute(
            tooLong,
            false,
            true,
            listOf(alpha),
            recheck = { true },
            write = { _, bytes -> writes += bytes.size; true }
        )
        assertEquals(1, writes.size)
        assertTrue(outcome.history.isEmpty())
        assertTrue(outcome.clearBody)
        val multiline = CommandBatch.execute(
            "echo a\necho b",
            false,
            true,
            listOf(alpha),
            recheck = { true },
            write = { _, _ -> true }
        )
        assertEquals(listOf("echo a\necho b"), multiline.history)
        assertEquals(longOk, CommandHistoryPolicy.accept(longOk))
        assertNull(CommandHistoryPolicy.accept(tooLong))

        var rows = CommandHistoryPolicy.record(emptyList(), "echo a\necho b", 1)
        assertEquals(1, rows.size)
        assertEquals("echo a\necho b", rows.single().command)
        rows = CommandHistoryPolicy.record(rows, "echo a\necho b", 2)
        assertEquals(1, rows.size)
        assertEquals(2, rows.single().count)
        assertEquals(2L, rows.single().lastSubmittedAt)
        val marker = rows
        assertEquals(marker, CommandHistoryPolicy.record(rows, "", 3))
        assertEquals(marker, CommandHistoryPolicy.record(rows, tooLong, 3))
    }

    @Test
    fun historyKeepsTheNewestThousand() {
        var rows = emptyList<HistoryRow>()
        for (i in 0 until CommandHistoryPolicy.MAX_ROWS) {
            rows = CommandHistoryPolicy.record(rows, "c$i", i.toLong())
        }
        rows = CommandHistoryPolicy.record(rows, "overflow", 5_000)
        assertEquals(CommandHistoryPolicy.MAX_ROWS, rows.size)
        assertEquals("overflow", rows.first().command)
        assertNull(rows.find { it.command == "c0" })
        assertEquals("c1", rows.last().command)
    }

    @Test
    fun recheckFailureIsNotWritten() {
        val written = mutableListOf<String>()
        val outcome = CommandBatch.execute(
            "echo hi",
            false,
            true,
            listOf(alpha, beta),
            recheck = { it == "a" },
            write = { id, _ -> written += id; id == "a" }
        )
        assertEquals(listOf("a"), written)
        assertEquals(listOf("b"), outcome.retryIds)
        assertEquals(BatchFailureReason.RecheckFailed, outcome.failed.single().reason)
        assertEquals(listOf("echo hi"), outcome.history)
        assertFalse(outcome.clearBody)
    }

    private fun send(ready: Set<String>, written: MutableList<String>): BatchOutcome =
        CommandBatch.execute(
            "echo {{host}}\n",
            true,
            true,
            listOf(alpha, beta),
            recheck = { it in ready },
            write = { id, _ -> written += id; true }
        )
}
