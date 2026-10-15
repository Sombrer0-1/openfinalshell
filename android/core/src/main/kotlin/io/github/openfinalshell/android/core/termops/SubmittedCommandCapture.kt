package io.github.openfinalshell.android.core.termops

enum class TerminalScreenKind { NORMAL, ALTERNATE }

data class TerminalBufferLine(val text: String, val wrapped: Boolean = false)

/**
 * A terminal line buffer the capture logic can read without starting Termux.
 * [origin] is the absolute row of [rows] element 0, so a saturated scrollback row can be
 * represented without allocating the rows above it.
 */
data class TerminalLineBuffer(
    val rows: List<TerminalBufferLine>,
    val cursorX: Int,
    val cursorY: Int,
    val baseY: Int = 0,
    val kind: TerminalScreenKind = TerminalScreenKind.NORMAL,
    val columns: Int = 80,
    val origin: Int = 0
) {
    fun line(absoluteRow: Int): TerminalBufferLine? = rows.getOrNull(absoluteRow - origin)
}

data class PromptSnapshot(val column: Int, val trusted: Boolean, val prompt: String, val row: Int? = null)

/**
 * Reads the command from the server echo after Enter, not from the keystroke stream.
 * Backspace, history recall, and Tab completion all show up as the line the server painted;
 * the first keystroke on that logical line only records where the prompt ended.
 * Returns null when the command boundary is not trustworthy, the prompt is asking for a secret,
 * or the alternate screen (vim, less, …) is active.
 */
class SubmittedCommandCapture {
    private var row: Int? = null
    private var column = 0
    private var trusted = true
    private var prompt = ""

    fun noteKeystroke(buffer: TerminalLineBuffer) {
        val start = logicalStartRow(buffer)
        val line = readLogicalLine(buffer)
        if (this.row == start && line.startsWith(prompt)) return
        this.row = start
        column = logicalCursorColumn(buffer, start)
        prompt = if (column <= 0) "" else line.substring(0, minOf(column, line.length))
        trusted = true
    }

    /** Call before a programmatic insert. The measured column on this logical line is then ignored. */
    fun noteProgrammaticWrite(buffer: TerminalLineBuffer) {
        row = logicalStartRow(buffer)
        column = 0
        prompt = readLogicalLine(buffer)
        trusted = false
    }

    fun noteSubmit() {
        row = null
        column = 0
        prompt = ""
        trusted = true
    }

    fun promptColumn(buffer: TerminalLineBuffer): Int? {
        val remembered = row ?: return null
        if (!trusted || remembered != logicalStartRow(buffer)) return null
        return if (readLogicalLine(buffer).startsWith(prompt)) column else null
    }

    fun snapshot(): PromptSnapshot = PromptSnapshot(column, trusted && row != null, prompt, row)

    /** Reads the submitted row even when the same output chunk also painted results and a new prompt. */
    fun captureEcho(buffer: TerminalLineBuffer, snapshot: PromptSnapshot): String? {
        if (buffer.kind == TerminalScreenKind.ALTERNATE) return null
        val start = snapshot.row ?: return null
        val first = buffer.line(start) ?: return null
        if (first.wrapped || start >= buffer.baseY + buffer.cursorY) return null
        val line = readLogicalLineFrom(buffer, start)
        val promptColumn = if (snapshot.trusted && snapshot.prompt.isNotEmpty() && line.startsWith(snapshot.prompt)) {
            snapshot.column
        } else null
        return extractSubmittedCommand(line, promptColumn)
    }

    companion object {
        const val MAX_WRAP_ROWS = 64
    }
}

private val PROMPT_END = Regex("[$#%] ")
private val SECRET_PROMPT = Regex("pass(word|phrase)|密码|口令|secret|token", RegexOption.IGNORE_CASE)

fun readLogicalLine(buffer: TerminalLineBuffer): String = readLogicalLineFrom(buffer, logicalStartRow(buffer))

fun extractSubmittedCommand(line: String, promptColumn: Int?): String? {
    val cut = if (promptColumn != null && promptColumn >= 0 && promptColumn <= line.length) {
        promptColumn
    } else {
        val match = PROMPT_END.find(line) ?: return null
        match.range.last + 1
    }
    val command = line.substring(cut).trim()
    if (command.isEmpty() || command.length > CommandHistoryIndex.MAX_CHARS) return null
    if (SECRET_PROMPT.containsMatchIn(line.substring(0, cut))) return null
    return command
}

private fun logicalStartRow(buffer: TerminalLineBuffer): Int {
    var row = buffer.baseY + buffer.cursorY
    var guard = SubmittedCommandCapture.MAX_WRAP_ROWS
    while (row > buffer.origin && guard > 0 && buffer.line(row)?.wrapped == true) {
        guard -= 1
        row -= 1
    }
    return row
}

/** Column of the cursor inside the logical line: the wrapped rows above it count as [TerminalLineBuffer.columns] each. */
private fun logicalCursorColumn(buffer: TerminalLineBuffer, start: Int): Int {
    val rowsAbove = (buffer.baseY + buffer.cursorY - start).coerceAtLeast(0)
    return rowsAbove * buffer.columns + buffer.cursorX
}

private fun readLogicalLineFrom(buffer: TerminalLineBuffer, start: Int): String {
    val text = StringBuilder()
    for (y in start until start + SubmittedCommandCapture.MAX_WRAP_ROWS) {
        val line = buffer.line(y) ?: break
        if (y > start && !line.wrapped) break
        text.append(line.text.padEnd(buffer.columns, ' '))
    }
    return text.trimEnd().toString()
}
