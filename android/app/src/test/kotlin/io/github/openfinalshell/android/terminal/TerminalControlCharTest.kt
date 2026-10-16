package io.github.openfinalshell.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalControlCharTest {
    @Test
    fun `letters map onto the c0 range regardless of case`() {
        assertEquals(0x01, TerminalModes.controlChar('a')!!.code)
        assertEquals(0x03, TerminalModes.controlChar('C')!!.code)
        assertEquals(0x18, TerminalModes.controlChar('x')!!.code)
        assertEquals(0x18, TerminalModes.controlChar('X')!!.code)
        assertEquals(0x1a, TerminalModes.controlChar('z')!!.code)
    }

    @Test
    fun `the classic punctuation table holds`() {
        assertEquals(0x00, TerminalModes.controlChar(' ')!!.code)
        assertEquals(0x00, TerminalModes.controlChar('@')!!.code)
        assertEquals(0x1b, TerminalModes.controlChar('[')!!.code)
        assertEquals(0x1c, TerminalModes.controlChar('\\')!!.code)
        assertEquals(0x1d, TerminalModes.controlChar(']')!!.code)
        assertEquals(0x1e, TerminalModes.controlChar('^')!!.code)
        assertEquals(0x1f, TerminalModes.controlChar('_')!!.code)
        assertEquals(0x7f, TerminalModes.controlChar('?')!!.code)
    }

    @Test
    fun `characters without control meaning stay unmapped`() {
        assertNull(TerminalModes.controlChar('1'))
        assertNull(TerminalModes.controlChar('8'))
        assertNull(TerminalModes.controlChar('{'))
        assertNull(TerminalModes.controlChar('中'))
    }
}
