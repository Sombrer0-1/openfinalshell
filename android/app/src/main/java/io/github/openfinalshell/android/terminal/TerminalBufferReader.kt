package io.github.openfinalshell.android.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth
import io.github.openfinalshell.android.core.termops.TerminalBufferLine
import io.github.openfinalshell.android.core.termops.TerminalLineBuffer
import io.github.openfinalshell.android.core.termops.TerminalScreenKind
import io.github.openfinalshell.android.core.termops.SubmittedCommandCapture

/**
 * Reads the Termux screen, including scrollback, into the shapes the terminal search and
 * command-capture logic already understand. Termux marks wrap on the row that overflowed;
 * [TerminalBufferLine.wrapped] means this row continues the previous one.
 */
object TerminalBufferReader {
    private val URL = Regex("""https?://[^\s<>"']+""")

    /** Reads only the logical command line, independent of the configured scrollback capacity. */
    fun lineBuffer(emulator: TerminalEmulator, absoluteRow: Int? = null): TerminalLineBuffer = synchronized(emulator) {
        val screen = emulator.screen
        val transcript = screen.activeTranscriptRows
        val screenRows = emulator.mRows
        val columns = emulator.mColumns.coerceAtLeast(1)
        val baseY = emulator.scrollCounter
        val focus = absoluteRow?.minus(baseY) ?: emulator.cursorRow
        val firstRow = -transcript
        var start = focus
        val rows = ArrayList<TerminalBufferLine>()
        if (focus in firstRow until screenRows) {
            var remaining = SubmittedCommandCapture.MAX_WRAP_ROWS - 1
            while (start > firstRow && remaining > 0 && screen.getLineWrap(start - 1)) {
                start--
                remaining--
            }
            for (row in start until minOf(screenRows, start + SubmittedCommandCapture.MAX_WRAP_ROWS)) {
                val continuation = row > firstRow && screen.getLineWrap(row - 1)
                if (row > start && !continuation) break
                rows += TerminalBufferLine(rowText(emulator, row, columns), wrapped = continuation)
            }
        }
        TerminalLineBuffer(
            rows = rows,
            cursorX = emulator.cursorCol,
            cursorY = emulator.cursorRow,
            baseY = baseY,
            kind = if (emulator.isAlternateBufferActive()) TerminalScreenKind.ALTERNATE else TerminalScreenKind.NORMAL,
            columns = columns,
            origin = baseY + start
        )
    }

    fun searchLines(emulator: TerminalEmulator): List<String> = synchronized(emulator) {
        val transcript = emulator.screen.activeTranscriptRows
        val columns = emulator.mColumns.coerceAtLeast(1)
        (-transcript until emulator.mRows).map { row -> rowText(emulator, row, columns) }
    }

    fun transcriptRows(emulator: TerminalEmulator): Int = synchronized(emulator) {
        emulator.screen.activeTranscriptRows
    }

    /** Half-open string indices from [io.github.openfinalshell.android.core.termops.TerminalSearch] become cell columns. */
    fun cellColumn(text: String, utf16Index: Int): Int {
        val end = utf16Index.coerceIn(0, text.length)
        var column = 0
        var index = 0
        while (index < end) {
            val codePoint = Character.codePointAt(text, index)
            val width = WcWidth.width(codePoint)
            if (width > 0) column += width
            index += Character.charCount(codePoint)
        }
        return column
    }

    fun urlAt(emulator: TerminalEmulator, column: Int, row: Int): String? = synchronized(emulator) {
        val screen = emulator.screen
        val minRow = -screen.activeTranscriptRows
        val maxRow = emulator.mRows - 1
        if (row < minRow || row > maxRow) return null
        var start = row
        var guard = 0
        while (start > minRow && guard < 8 && screen.getLineWrap(start - 1)) {
            start -= 1
            guard += 1
        }
        var end = row
        guard = 0
        while (end < maxRow && guard < 8 && screen.getLineWrap(end)) {
            end += 1
            guard += 1
        }
        val columns = emulator.mColumns.coerceAtLeast(1)
        val text = StringBuilder()
        val rowOffset = IntArray(end - start + 1)
        for (physical in start..end) {
            rowOffset[physical - start] = text.length
            text.append(rowText(emulator, physical, columns))
        }
        val local = rowText(emulator, row, columns)
        val offset = rowOffset[row - start] + column.coerceIn(0, local.length)
        URL.findAll(text).firstOrNull { offset in it.range }?.value?.trimEnd('.', ',', ';', ':', ')', ']', '>')
    }

    private fun rowText(emulator: TerminalEmulator, row: Int, columns: Int): String =
        emulator.getSelectedText(0, row, columns, row).trimEnd('\n', '\r')
}
