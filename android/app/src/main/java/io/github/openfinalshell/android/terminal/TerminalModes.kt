package io.github.openfinalshell.android.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import io.github.openfinalshell.android.core.termops.TerminalPalette

/**
 * Termux keeps DECSET 2004 in a private flag and does not expose isBracketedPasteMode.
 * Read that flag when the field is still there. Otherwise remember the 2004 sequences the
 * remote actually sent. Never assume the mode is on.
 */
class DecSet2004Tracker {
    private val tail = StringBuilder()
    var enabled: Boolean? = null
        private set

    fun observe(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        tail.append(bytes.toString(Charsets.ISO_8859_1))
        if (tail.length > 512) tail.delete(0, tail.length - 256)
        parse()
    }

    private fun parse() {
        val text = tail.toString()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("\u001b[?", index)
            if (start < 0) {
                val partial = text.lastIndexOf('\u001b')
                tail.delete(0, if (partial >= 0 && text.length - partial <= 16) partial else text.length)
                return
            }
            var cursor = start + 3
            val params = ArrayList<Int>(4)
            var current = 0
            var digits = false
            var finished = false
            while (cursor < text.length) {
                val char = text[cursor]
                when {
                    char in '0'..'9' -> {
                        current = current * 10 + (char - '0')
                        digits = true
                        cursor += 1
                    }
                    char == ';' -> {
                        if (digits) params += current
                        current = 0
                        digits = false
                        cursor += 1
                    }
                    char == 'h' || char == 'l' -> {
                        if (digits) params += current
                        if (2004 in params) enabled = char == 'h'
                        index = cursor + 1
                        finished = true
                        break
                    }
                    else -> {
                        index = start + 1
                        finished = true
                        break
                    }
                }
            }
            if (!finished) {
                tail.delete(0, start)
                return
            }
        }
        tail.clear()
    }
}

object TerminalModes {
    /** Internal bit Termux uses for DECSET 2004. See TerminalEmulator.mapDecSetBitToInternalBit. */
    private const val BRACKETED_PASTE_BIT = 1 shl 10

    /**
     * Ctrl over a printable ASCII key maps onto the C0 control range the way xterm does: letters
     * become their control counterparts and space, @, [, \, ], ^, _ and ? follow the classic
     * table. Other characters have no control meaning, so callers decide what to do with them.
     */
    fun controlChar(plain: Char): Char? = when (plain) {
        in 'a'..'z' -> (plain.code - 0x60).toChar()
        in 'A'..'Z' -> (plain.code - 0x40).toChar()
        ' ', '@' -> 0.toChar()
        '[' -> 0x1b.toChar()
        '\\' -> 0x1c.toChar()
        ']' -> 0x1d.toChar()
        '^' -> 0x1e.toChar()
        '_' -> 0x1f.toChar()
        '?' -> 0x7f.toChar()
        else -> null
    }

    fun readBracketedPaste(emulator: TerminalEmulator): Boolean? = runCatching {
        val field = TerminalEmulator::class.java.getDeclaredField("mCurrentDecSetFlags")
        field.isAccessible = true
        (field.getInt(emulator) and BRACKETED_PASTE_BIT) != 0
    }.getOrNull()

    fun bracketedPaste(emulator: TerminalEmulator?, tracker: DecSet2004Tracker?): Boolean {
        val live = emulator?.let(::readBracketedPaste)
        if (live != null) return live
        return tracker?.enabled == true
    }
}

/** The renderer paints emulator.mColors.mCurrentColors. Reset copies the scheme defaults back. */
fun applyTerminalPalette(emulator: TerminalEmulator, palette: TerminalPalette) {
    writePalette(emulator.mColors.mCurrentColors, palette)
    writePalette(TerminalColors.COLOR_SCHEME.mDefaultColors, palette)
}

private fun writePalette(target: IntArray, palette: TerminalPalette) {
    for (index in palette.colors.indices) {
        if (index < target.size) target[index] = palette.colors[index]
    }
    if (TextStyle.COLOR_INDEX_FOREGROUND < target.size) target[TextStyle.COLOR_INDEX_FOREGROUND] = palette.foreground
    if (TextStyle.COLOR_INDEX_BACKGROUND < target.size) target[TextStyle.COLOR_INDEX_BACKGROUND] = palette.background
    if (TextStyle.COLOR_INDEX_CURSOR < target.size) target[TextStyle.COLOR_INDEX_CURSOR] = palette.cursor
}

enum class ScreenKey { ENTER, ESC, TAB, UP, DOWN, LEFT, RIGHT, SLASH }

fun screenKeyBytes(emulator: TerminalEmulator, key: ScreenKey, ctrl: Boolean): ByteArray = when (key) {
    ScreenKey.ENTER -> "\r".toByteArray()
    ScreenKey.ESC -> byteArrayOf(0x1b)
    ScreenKey.TAB -> byteArrayOf(0x09)
    ScreenKey.SLASH -> if (ctrl) byteArrayOf(0x1f) else "/".toByteArray()
    ScreenKey.UP -> arrow(emulator, KeyEvent.KEYCODE_DPAD_UP, ctrl)
    ScreenKey.DOWN -> arrow(emulator, KeyEvent.KEYCODE_DPAD_DOWN, ctrl)
    ScreenKey.LEFT -> arrow(emulator, KeyEvent.KEYCODE_DPAD_LEFT, ctrl)
    ScreenKey.RIGHT -> arrow(emulator, KeyEvent.KEYCODE_DPAD_RIGHT, ctrl)
}

private fun arrow(emulator: TerminalEmulator, keyCode: Int, ctrl: Boolean): ByteArray {
    val modifiers = if (ctrl) KeyHandler.KEYMOD_CTRL else 0
    val code = KeyHandler.getCode(
        keyCode,
        modifiers,
        emulator.isCursorKeysApplicationMode,
        emulator.isKeypadApplicationMode
    )
    return code?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
}
