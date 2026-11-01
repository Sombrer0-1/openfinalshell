package io.github.openfinalshell.android.terminal

import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollRoutingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `scroll mode prefers mouse reporting over alternate buffer and transcript`() {
        assertEquals(TerminalScrollMode.MOUSE_REPORT, terminalScrollMode(mouseTracking = true, alternateBuffer = true))
        assertEquals(TerminalScrollMode.MOUSE_REPORT, terminalScrollMode(mouseTracking = true, alternateBuffer = false))
        assertEquals(TerminalScrollMode.CURSOR_KEYS, terminalScrollMode(mouseTracking = false, alternateBuffer = true))
        assertEquals(TerminalScrollMode.TRANSCRIPT, terminalScrollMode(mouseTracking = false, alternateBuffer = false))
    }

    @Test
    fun `drag deltas accumulate sub-row remainders into whole rows`() {
        val first = wheelRowsForDelta(deltaY = -8f, remainder = 0f, lineSpacing = 16)
        assertEquals(0, first.rows)
        val second = wheelRowsForDelta(deltaY = -8f, remainder = first.remainder, lineSpacing = 16)
        assertEquals(-1, second.rows)
        assertEquals(0f, second.remainder, 1e-4f)

        val fast = wheelRowsForDelta(deltaY = -70f, remainder = 0f, lineSpacing = 16)
        assertEquals(-4, fast.rows)
        assertEquals(-6f, fast.remainder, 1e-4f)

        val down = wheelRowsForDelta(deltaY = 33f, remainder = 0f, lineSpacing = 16)
        assertEquals(2, down.rows)
        assertEquals(1f, down.remainder, 1e-4f)
    }

    @Test
    fun `touch drag is natural - dragging up scrolls the host toward newer rows`() {
        // Finger moves up (negative y travel): content follows and the host scrolls down.
        val up = dragScrollRowsForDelta(deltaY = -16f, remainder = 0f, lineSpacing = 16)
        assertEquals(1, up.rows)
        // Finger moves down: reveal older rows, i.e. scroll the host up (wheel up / copy-mode).
        val down = dragScrollRowsForDelta(deltaY = 16f, remainder = 0f, lineSpacing = 16)
        assertEquals(-1, down.rows)

        // The sub-row remainder still accumulates in finger-travel space.
        val slow = dragScrollRowsForDelta(deltaY = -5f, remainder = 0f, lineSpacing = 16)
        assertEquals(0, slow.rows)
        val slowMore = dragScrollRowsForDelta(deltaY = -11f, remainder = slow.remainder, lineSpacing = 16)
        assertEquals(1, slowMore.rows)
        assertEquals(0f, slowMore.remainder, 1e-4f)
    }

    @Test
    fun `transcript drag follows the finger`() {
        // Dragging up moves the view toward the live screen (topRow toward 0).
        assertEquals(-4, transcriptTopRowAfterDrag(startTopRow = -5, deltaY = -16f, lineSpacing = 16, transcriptRows = 10))
        // Dragging down reveals older transcript rows.
        assertEquals(-6, transcriptTopRowAfterDrag(startTopRow = -5, deltaY = 16f, lineSpacing = 16, transcriptRows = 10))
        // Clamped to the transcript on both ends.
        assertEquals(-10, transcriptTopRowAfterDrag(startTopRow = -5, deltaY = 400f, lineSpacing = 16, transcriptRows = 10))
        assertEquals(0, transcriptTopRowAfterDrag(startTopRow = -5, deltaY = -400f, lineSpacing = 16, transcriptRows = 10))
    }

    @Test
    fun `tmux mouse-on escape stream routes scrolling to mouse reports`() {
        val controller = SshTerminalController(scope, {})
        assertFalse(controller.emulator.isMouseTrackingActive)
        assertEquals(TerminalScrollMode.TRANSCRIPT, modeOf(controller))

        controller.write("\u001b[?1000h\u001b[?1006h".toByteArray())

        assertTrue(controller.emulator.isMouseTrackingActive)
        assertEquals(TerminalScrollMode.MOUSE_REPORT, modeOf(controller))
    }

    @Test
    fun `alternate screen without mouse reporting routes scrolling to cursor keys`() {
        val controller = SshTerminalController(scope, {})
        controller.write("\u001b[?1049h".toByteArray())

        assertFalse(controller.emulator.isMouseTrackingActive)
        assertTrue(controller.emulator.isAlternateBufferActive)
        assertEquals(TerminalScrollMode.CURSOR_KEYS, modeOf(controller))
    }

    @Test
    fun `mouse tracking survives alternate screen like tmux copy mode`() {
        val controller = SshTerminalController(scope, {})
        controller.write("\u001b[?1000h\u001b[?1006h".toByteArray())
        controller.write("\u001b[?1049h".toByteArray())

        assertEquals(TerminalScrollMode.MOUSE_REPORT, modeOf(controller))
    }

    @Test
    fun `wheel reports reach the ssh input sink as sgr escape sequences`() {
        val sent = mutableListOf<ByteArray>()
        val controller = SshTerminalController(scope, { bytes -> sent.add(bytes) })
        controller.write("\u001b[?1000h\u001b[?1006h".toByteArray())

        controller.emulator.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 5, 3, true)
        controller.emulator.sendMouseEvent(TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, 5, 3, true)
        controller.emulator.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 5, 3, false)

        assertEquals(listOf("\u001b[<64;5;3M", "\u001b[<65;5;3M", "\u001b[<64;5;3m"), sent.map { String(it) })
    }

    @Test
    fun `cursor-key fallback honors application cursor keys mode`() {
        assertEquals("\u001b[A", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_UP, 0, false, false))
        assertEquals("\u001bOA", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_UP, 0, true, false))
        assertEquals("\u001b[B", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_DOWN, 0, false, false))
        assertEquals("\u001bOB", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_DOWN, 0, true, false))
    }

    private fun modeOf(controller: SshTerminalController): TerminalScrollMode = synchronized(controller.emulator) {
        terminalScrollMode(controller.emulator.isMouseTrackingActive, controller.emulator.isAlternateBufferActive)
    }
}
