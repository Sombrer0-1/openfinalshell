package io.github.openfinalshell.android.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the app's rendering of a real mksh interactive line-editing stream to the reference
 * behavior of independent terminal implementations (pyte 0.8.2, xterm wrap semantics).
 *
 * Context for future readers: when a typed line approaches the right margin, mksh's line editor
 * (edit.c x_adjust/x_redraw/x_clrtoeol) does NOT let the terminal wrap the edit line. It emits
 * CR, then a sliding window of the line, space-fill, a literal '<' marker byte at column
 * columns-2 (">"/"*" when text is hidden at the right/both ends), and a chain of BS to park the
 * cursor. The prompt fragment and the line prefix before the window start are intentionally
 * hidden by the shell; every faithful terminal — including this app's Termux emulator — shows
 * the same "compressed" row. These bytes were captured from Android's /system/bin/sh (mksh) on
 * a 46x18 pty with pty echo disabled, i.e. exactly what an SSH session delivers.
 *
 * The assertions guard the emulator append path (CR, BS including the pending-wrap state at the
 * last column, printable at the last column) so a Termux upgrade that regresses any of them
 * breaks here instead of silently corrupting live line-editing echo.
 */
class MkshLineEditingRenderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun row(controller: SshTerminalController, row: Int): String {
        val terminal = controller.emulator!!
        // getSelectedText's row range is inclusive on both ends.
        return terminal.screen.getSelectedText(0, row, terminal.mColumns, row).trimEnd('\n')
    }

    @Test
    fun `mksh windowed redraw of an overlong line renders byte-faithfully`() {
        val controller = SshTerminalController(scope, {}, initialCols = 46, initialRows = 18)
        // Captured stream: prompt, 32 typed chars (echo stops mksh at the xx_cols-2 boundary),
        // CR, 30-char window sliding past "ec", 14-space fill, '<' marker, 15 BS, "56789".
        val stream = buildString {
            append("emu64xa:/ $ ")
            append("echo 0123456789-0123456789-01234")
            append('\r')
            append("ho 0123456789-0123456789-01234")
            append(" ".repeat(14))
            append('<')
            append("\b".repeat(15))
            append("56789")
        }

        controller.write(stream.toByteArray())

        // The window row shows the tail of the line, space-padded, with mksh's '<' marker in
        // column 44 (= columns - 2, where mksh's x_clrtoeol puts it) — not a notdef glyph and
        // not a lost write. Identical to the pyte reference render.
        assertEquals(
            "ho 0123456789-0123456789-0123456789         <",
            row(controller, 0)
        )
        assertEquals('<', row(controller, 0)[44])
        // The cursor parks where mksh expects it (line offset 35 -> column 35, row 0); any drift
        // here would desynchronize every subsequent redraw.
        assertEquals(35, controller.emulator!!.cursorCol)
        assertEquals(0, controller.emulator!!.cursorRow)
    }

    @Test
    fun `full mksh session stream keeps edit row output and next prompt apart`() {
        val controller = SshTerminalController(scope, {}, initialCols = 46, initialRows = 18)
        val stream = buildString {
            append("emu64xa:/ $ ")
            append("echo 0123456789-0123456789-01234")
            append('\r')
            append("ho 0123456789-0123456789-01234")
            append(" ".repeat(14))
            append('<')
            append("\b".repeat(15))
            append("56789")
            append("\r\r\r\n")
            append("0123456789-0123456789-0123456789")
            append("\r\r\n")
            append("emu64xa:/ $ ")
        }

        controller.write(stream.toByteArray())

        assertEquals("ho 0123456789-0123456789-0123456789         <", row(controller, 0))
        assertEquals("0123456789-0123456789-0123456789", row(controller, 1))
        assertEquals("emu64xa:/ $", row(controller, 2))
        assertEquals(12, controller.emulator!!.cursorCol)
        assertEquals(2, controller.emulator!!.cursorRow)
    }

    @Test
    fun `printable at the last column stays pending and wraps only on the next one`() {
        val controller = SshTerminalController(scope, {}, initialCols = 46, initialRows = 18)
        // 46th char occupies the last column; the 47th must wrap to the next row (xterm/DECAWM
        // lazy wrap, the semantics mksh's column tracking relies on). Mirrors the plain-output
        // wrap path that must stay identical to program output rendering.
        controller.write("a".repeat(48).toByteArray())

        assertEquals("a".repeat(46), row(controller, 0))
        assertEquals("aa", row(controller, 1))
        assertEquals(2, controller.emulator!!.cursorCol)
        assertEquals(1, controller.emulator!!.cursorRow)
    }
}
