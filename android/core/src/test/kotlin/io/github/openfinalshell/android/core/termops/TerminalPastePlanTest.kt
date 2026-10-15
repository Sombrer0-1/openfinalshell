package io.github.openfinalshell.android.core.termops

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TerminalPastePlanTest {
    @Test
    fun `only text containing CR or LF needs confirmation and cancel sends nothing`() {
        val single = TerminalPastePlan.of("echo hi", bracketedPaste = false, confirmMultiline = true, confirmed = false)
        assertFalse(single.needsConfirmation)
        assertContentEquals("echo hi".toByteArray(), single.bytes)

        val pending = TerminalPastePlan.of("a\rb", bracketedPaste = false, confirmMultiline = true, confirmed = false)
        assertTrue(pending.needsConfirmation)
        assertEquals(0, pending.bytes.size)
        assertEquals(0, TerminalPastePlan.cancelled().bytes.size)

        val disabled = TerminalPastePlan.of("a\nb", bracketedPaste = false, confirmMultiline = false, confirmed = false)
        assertFalse(disabled.needsConfirmation)
        assertContentEquals("a\rb".toByteArray(), disabled.bytes)
    }

    @Test
    fun `a confirmed paste uses CR and bracketed markers only when that mode is on`() {
        val plain = TerminalPastePlan.of("a\r\nb\n", bracketedPaste = false, confirmMultiline = true, confirmed = true)
        assertContentEquals("a\rb\r".toByteArray(), plain.bytes)

        val bracketed = TerminalPastePlan.of("a\r\nb\n", bracketedPaste = true, confirmMultiline = true, confirmed = true)
        assertContentEquals("\u001b[200~a\rb\u001b[201~".toByteArray(), bracketed.bytes)

        val oneLine = TerminalPastePlan.of("echo hi", bracketedPaste = true, confirmMultiline = true, confirmed = false)
        assertFalse(oneLine.needsConfirmation)
        assertContentEquals("\u001b[200~echo hi\u001b[201~".toByteArray(), oneLine.bytes)
        assertEquals(0, TerminalPastePlan.encode("\n\n", bracketedPaste = true).size)
    }
}
