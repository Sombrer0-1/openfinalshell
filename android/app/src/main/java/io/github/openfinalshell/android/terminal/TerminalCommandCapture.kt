package io.github.openfinalshell.android.terminal

import com.termux.terminal.TerminalEmulator
import io.github.openfinalshell.android.core.termops.PromptSnapshot
import io.github.openfinalshell.android.core.termops.SubmittedCommandCapture
import io.github.openfinalshell.android.core.termops.TerminalScreenKind

/**
 * One capture per session. Ordinary input is read back from the server echo.
 * A programmatic submit (assistant or command editor) sets [suppress] so the echo
 * of that same submit is not recorded again.
 */
class TerminalCommandCapture {
    private val capture = SubmittedCommandCapture()
    private val lock = Any()
    private var pending: PromptSnapshot? = null
    private var awaitEcho = false
    private var suppress = false
    private var submitRow = 0

    fun onInput(
        emulator: TerminalEmulator?,
        bytes: ByteArray,
        programmaticInsert: Boolean,
        programmaticSubmit: Boolean
    ) {
        if (emulator == null || bytes.isEmpty()) return
        synchronized(lock) {
            val buffer = TerminalBufferReader.lineBuffer(emulator)
            if (buffer.kind == TerminalScreenKind.ALTERNATE) {
                pending = null
                awaitEcho = false
                capture.noteSubmit()
                return
            }
            if (programmaticInsert || programmaticSubmit) capture.noteProgrammaticWrite(buffer)
            else capture.noteKeystroke(buffer)
            val submits = programmaticSubmit || bytes.any { it == '\r'.code.toByte() || it == '\n'.code.toByte() }
            if (!submits) return
            pending = capture.snapshot()
            submitRow = buffer.baseY + buffer.cursorY
            capture.noteSubmit()
            awaitEcho = true
            suppress = programmaticSubmit
        }
    }

    fun onRemoteOutput(emulator: TerminalEmulator, chunk: ByteArray): String? = synchronized(lock) {
        if (chunk.none { it == '\n'.code.toByte() }) null else finish(emulator, chunkHasNewline = true)
    }

    /** Local shells append their own bytes, so the screen change is the echo signal. */
    fun onScreenChanged(emulator: TerminalEmulator): String? = synchronized(lock) {
        finish(emulator, chunkHasNewline = false)
    }

    private fun finish(emulator: TerminalEmulator, chunkHasNewline: Boolean): String? {
        if (!awaitEcho || pending == null) return null
        val after = TerminalBufferReader.lineBuffer(emulator, pending?.row)
        val cursorRow = after.baseY + after.cursorY
        val advanced = chunkHasNewline ||
            cursorRow > submitRow
        if (!advanced) return null
        val snapshot = pending
        awaitEcho = false
        val skip = suppress
        suppress = false
        pending = null
        if (skip || snapshot == null || after.kind == TerminalScreenKind.ALTERNATE) return null
        return capture.captureEcho(after, snapshot)
    }
}
