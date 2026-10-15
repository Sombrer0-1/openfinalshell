package io.github.openfinalshell.android.terminal

import io.github.openfinalshell.android.core.termops.ScrollbackLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandCaptureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun terminal(rows: Int = 6) = SshTerminalController(
        scope, {}, initialCols = 40, initialRows = rows, scrollbackLines = ScrollbackLimits.MAX
    )

    @Test fun coalescedEchoAndOutputCaptureTheCommandExactlyOnce() {
        val terminal = terminal()
        val capture = TerminalCommandCapture()
        terminal.write("root@h:~# ".toByteArray())
        capture.onInput(terminal.emulator, "echo hello".toByteArray(), false, false)
        terminal.write("echo hello".toByteArray())
        capture.onInput(terminal.emulator, byteArrayOf(13), false, false)
        val output = "\r\nhello\r\nroot@h:~# ".toByteArray()
        terminal.write(output)
        assertEquals("echo hello", capture.onRemoteOutput(terminal.emulator, output))
        assertNull(capture.onRemoteOutput(terminal.emulator, "more\r\n".toByteArray()))
    }

    @Test fun submittedRowSurvivesScrollingAtTheBottomOfTheScreen() {
        val terminal = terminal(rows = 4)
        val capture = TerminalCommandCapture()
        terminal.write("old\r\n".repeat(10).toByteArray())
        terminal.write("root@h:~# ".toByteArray())
        capture.onInput(terminal.emulator, "echo hi".toByteArray(), false, false)
        terminal.write("echo hi".toByteArray())
        capture.onInput(terminal.emulator, byteArrayOf(13), false, false)
        val output = "\r\nhi\r\nroot@h:~# ".toByteArray()
        terminal.write(output)
        assertEquals("echo hi", capture.onRemoteOutput(terminal.emulator, output))
    }

    @Test fun splitCarriageReturnAndLineFeedDoNotConsumeThePendingSubmission() {
        val terminal = terminal()
        val capture = TerminalCommandCapture()
        terminal.write("root@h:~# ".toByteArray())
        capture.onInput(terminal.emulator, "pwd".toByteArray(), false, false)
        terminal.write("pwd".toByteArray())
        capture.onInput(terminal.emulator, byteArrayOf(13), false, false)
        terminal.write(byteArrayOf(13))
        assertNull(capture.onRemoteOutput(terminal.emulator, byteArrayOf(13)))
        assertNull(capture.onScreenChanged(terminal.emulator))
        terminal.write(byteArrayOf(10))
        assertEquals("pwd", capture.onRemoteOutput(terminal.emulator, byteArrayOf(10)))
    }

    @Test fun commandReadsStaySmallWithASaturatedScrollback() {
        val terminal = terminal()
        terminal.write("old\r\n".repeat(ScrollbackLimits.MAX + 10).toByteArray())
        terminal.write("root@h:~# ".toByteArray())
        assertTrue(terminal.emulator.screen.activeTranscriptRows > 40_000)
        val buffer = TerminalBufferReader.lineBuffer(terminal.emulator)
        assertEquals(1, buffer.rows.size)
        assertTrue(buffer.rows.single().text.startsWith("root@h:~#"))
    }

    @Test fun wrappedCommandsAreCapturedWithCoalescedOutput() {
        val terminal = terminal()
        val capture = TerminalCommandCapture()
        terminal.write("root@h:~# ".toByteArray())
        val command = "echo " + "a".repeat(60)
        capture.onInput(terminal.emulator, command.toByteArray(), false, false)
        terminal.write(command.toByteArray())
        capture.onInput(terminal.emulator, byteArrayOf(13), false, false)
        val output = "\r\nresult\r\nroot@h:~# ".toByteArray()
        terminal.write(output)
        assertEquals(command, capture.onRemoteOutput(terminal.emulator, output))
    }

    @Test fun localScreenUpdatesAndProgrammaticSubmissionsUseTheSameBoundary() {
        val terminal = terminal()
        val capture = TerminalCommandCapture()
        terminal.write("root@h:~# ".toByteArray())
        capture.onInput(terminal.emulator, "pwd".toByteArray(), false, false)
        terminal.write("pwd".toByteArray())
        capture.onInput(terminal.emulator, byteArrayOf(13), false, false)
        terminal.write("\r\n/home/root\r\nroot@h:~# ".toByteArray())
        assertEquals("pwd", capture.onScreenChanged(terminal.emulator))
        capture.onInput(terminal.emulator, "echo once\r".toByteArray(), true, true)
        terminal.write("echo once\r\nonce\r\nroot@h:~# ".toByteArray())
        assertNull(capture.onScreenChanged(terminal.emulator))
    }
}
