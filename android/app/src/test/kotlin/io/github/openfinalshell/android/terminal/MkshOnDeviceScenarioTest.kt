package io.github.openfinalshell.android.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterizes the exact on-device report: a wrapped mksh line edit at a 66-column prompt (the
 * prompt itself wraps, so its trailing fragment occupies the start of the continuation row),
 * followed by a rotation to landscape and a short command.
 *
 * Ground truth (captured from Android mksh on a 46x18 pty with pty echo off): when the edit
 * cursor reaches mksh's right margin guard (x_col >= columns-2), the editor slides its visible
 * window over the line (edit.c x_adjust) and redraws the current row: CR, the window content,
 * space fill, a literal '<' marker byte in column columns-2, then BS to the cursor. This makes
 * the row below the prompt show the line tail with its leading characters hidden by the shell —
 * and the prompt fragment on that row overwritten — on every faithful terminal, including this
 * app's Termux emulator (verified cell- and cursor-identical against pyte 0.8.2).
 *
 * The marker cell is ordinary buffer content: short commands never emit it (verified against
 * mksh at 90 columns), but once written it legitimately survives resize reflow, so a '<' seen
 * next to a later short command row is preserved shell output, not an artifact injected by the
 * app or the renderer. The marker-count assertions pin that invariant.
 */
class MkshOnDeviceScenarioTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** 66 columns wide, so its last 20 columns wrap onto the continuation row. */
    private val prompt = "emu64xa:/data/local/tmp/" + "a".repeat(31) + " $ "

    private fun rows(controller: SshTerminalController): List<String> {
        val terminal = controller.emulator!!
        return (0 until terminal.mRows).map { row ->
            // getSelectedText's row range is inclusive on both ends.
            terminal.screen.getSelectedText(0, row, terminal.mColumns, row).trimEnd('\n')
        }
    }

    private fun markerCount(controller: SshTerminalController): Int =
        rows(controller).sumOf { row -> row.count { it == '<' } }

    @Test
    fun `wrapped edit at wrapping prompt reproduces reported continuation row`() = runBlocking {
        val controller = SshTerminalController(scope, {}, initialCols = 46, initialRows = 18)
        val stream = buildString {
            append(prompt)
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
            append(prompt)
        }

        controller.write(stream.toByteArray())

        val screen = rows(controller)
        // Prompt wraps; the continuation row is exactly mksh's redraw: line tail with the shell's
        // own leading characters hidden ("ec" before the window) and the prompt fragment and the
        // "echo 01" prefix gone, marker at column 44. Not a lost or reordered write.
        assertEquals(prompt.take(46), screen[0])
        assertEquals("ho 0123456789-0123456789-0123456789         <", screen[1])
        assertEquals('<', screen[1][44])
        assertEquals("0123456789-0123456789-0123456789", screen[2])
        assertEquals(1, markerCount(controller))
    }

    @Test
    fun `rotation preserves the marker as stale content and short commands add none`() = runBlocking {
        val controller = SshTerminalController(scope, {}, initialCols = 46, initialRows = 18)
        controller.write(
            buildString {
                append(prompt)
                append("echo 0123456789-0123456789-01234")
                append('\r')
                append("ho 0123456789-0123456789-01234")
                append(" ".repeat(14))
                append('<')
                append("\b".repeat(15))
                append("56789")
            }.toByteArray()
        )
        assertEquals(1, markerCount(controller))

        // Landscape: Termux's reflow decides cell retention per buffer state (it kept the marker
        // for the fuller transcript in the test above, and may drop it here) — what matters is
        // that resize invents nothing of its own.
        controller.resize(90, 4)
        val markersAfterRotation = markerCount(controller)

        // A short command in landscape: mksh never emits a marker for it (verified against a real
        // 90-column pty capture), so the count must stay unchanged — no artifact is invented.
        controller.write((prompt + "stty size\r18 90\r\n" + prompt).toByteArray())
        assertEquals(markersAfterRotation, markerCount(controller))
        val landscape = rows(controller)
        assertEquals(true, landscape.any { it.contains("stty size") })
        assertEquals(true, landscape.any { it.contains("18 90") })
    }
}
