package io.github.openfinalshell.android.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.core.terminal.TerminalCursorStyle
import io.github.openfinalshell.android.core.termops.TerminalPalette
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

internal data class TerminalViewport(val columns: Int, val rows: Int)

internal data class TerminalLayoutSignal(
    val columns: Int,
    val rows: Int,
    val fontPx: Int,
    val density: Float,
    val fontScale: Float
)

internal data class TerminalLayoutEffect(val resize: Boolean, val redraw: Boolean)

/**
 * Grid changes notify the PTY. Pixel, density, or font changes redraw even when the grid is unchanged,
 * because the glyph paint was built for the previous metrics.
 */
internal fun terminalLayoutEffect(previous: TerminalLayoutSignal?, next: TerminalLayoutSignal): TerminalLayoutEffect {
    if (previous == null) return TerminalLayoutEffect(resize = true, redraw = true)
    val gridChanged = previous.columns != next.columns || previous.rows != next.rows
    val pixelsChanged = previous.fontPx != next.fontPx || previous.density != next.density || previous.fontScale != next.fontScale
    return TerminalLayoutEffect(resize = gridChanged, redraw = gridChanged || pixelsChanged)
}

internal data class TerminalLayoutPublication(
    val effect: TerminalLayoutEffect,
    val remembered: TerminalLayoutSignal?
)

/**
 * A grid change that cannot be delivered is not remembered. onAttached measures before bind()
 * installs the listener; storing that signal made the later bind look unchanged, so the PTY
 * stayed at the placeholder size.
 */
internal fun publishTerminalLayout(
    previous: TerminalLayoutSignal?,
    next: TerminalLayoutSignal,
    listenerReady: Boolean
): TerminalLayoutPublication {
    val effect = terminalLayoutEffect(previous, next)
    if (effect.resize && !listenerReady) {
        return TerminalLayoutPublication(effect.copy(resize = false), previous)
    }
    return TerminalLayoutPublication(effect, next)
}

/** Converts measured pixels into a stable PTY viewport, preserving cell boundaries. */
internal fun measureTerminalViewport(
    width: Int,
    height: Int,
    fontWidth: Float,
    lineSpacing: Int,
    paddingLeft: Int = 0,
    paddingTop: Int = 0,
    paddingRight: Int = 0,
    paddingBottom: Int = 0,
    minColumns: Int = 4,
    minRows: Int = 4
): TerminalViewport? {
    val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
    val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
    if (contentWidth == 0 || contentHeight == 0 || !fontWidth.isFinite() || fontWidth <= 0f) return null
    val spacing = lineSpacing.coerceAtLeast(1)
    return TerminalViewport(
        columns = max(minColumns.coerceAtLeast(1), floor(contentWidth / fontWidth).toInt()),
        rows = max(minRows.coerceAtLeast(1), contentHeight / spacing)
    )
}

/**
 * A remote-shell adapter around Termux's TerminalEmulator/TerminalRenderer pair. It deliberately
 * has no local PTY: every input byte is routed to the SSH shell supplied by [onInput].
 */
class SshTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var controller: TerminalHostController? = null
    private var onInput: ((ByteArray) -> Unit)? = null
    private var onResize: ((Int, Int) -> Unit)? = null
    private var renderer = TerminalRenderer(DEFAULT_FONT_SIZE, Typeface.MONOSPACE)
    private var rendererTextSizePx = DEFAULT_FONT_SIZE
    private var topRow = 0
    private var dragStartY = 0f
    private var startTopRow = 0
    private var movedDuringGesture = false
    private var lastSignal: TerminalLayoutSignal? = null
    private var selectionStart: TerminalCell? = null
    /** Invoked only for the user's explicit selection, never a transcript snapshot. */
    var onTextSelected: ((String) -> Unit)? = null
    var onContextMenu: (() -> Unit)? = null
    var onPasteText: ((String) -> Unit)? = null
    var onOpenLink: ((String) -> Unit)? = null
    var onEscape: (() -> Unit)? = null
    var onFind: (() -> Unit)? = null
    var onHistory: (() -> Unit)? = null
    var onPasteShortcut: (() -> Unit)? = null
    var onCloseTab: (() -> Unit)? = null
    var closeSearchOnEscape: Boolean = false
    var copyOnSelect: Boolean = true
    private var selectionEnd: TerminalCell? = null
    private var lineHeightMultiplier = 1f
    private var cursorBlink = false
    private var blinkOn = true
    private var blinkPosted = false
    private var appliedPalette: TerminalPalette? = null
    private var searchRow = Int.MIN_VALUE
    private var searchX1 = -1
    private var searchX2 = -1
    private var rowBitmap: Bitmap? = null
    private var lastSelection = ""
    private var longPressArmed = false
    private var longPressX = 0f
    private var longPressY = 0f
    private var consumedTap = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val blinkTask = object : Runnable {
        override fun run() {
            val terminal = controller?.emulator ?: return
            if (!cursorBlink) return
            blinkOn = !blinkOn
            terminal.setCursorBlinkState(blinkOn)
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val link = controller?.emulator?.let { terminal ->
                val cell = cellAt(event)
                TerminalBufferReader.urlAt(terminal, cell.column, cell.row)
            }
            if (!link.isNullOrBlank()) {
                consumedTap = true
                onOpenLink?.invoke(link)
                return true
            }
            requestFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(this@SshTerminalView, InputMethodManager.SHOW_IMPLICIT)
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            longPressArmed = true
            longPressX = event.x
            longPressY = event.y
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
        contentDescription = null
    }

    fun bind(
        nextController: TerminalHostController,
        fontSizeSp: Int,
        cursorStyle: TerminalCursorStyle,
        input: (ByteArray) -> Unit,
        resize: (Int, Int) -> Unit
    ) {
        if (controller !== nextController) {
            lastSignal = null
            topRow = 0
            selectionStart = null
            selectionEnd = null
        }
        controller = nextController
        onInput = input
        onResize = resize
        nextController.setFontSize(fontSizeSp)
        nextController.setCursorStyle(cursorStyle)
        appliedPalette?.let { palette -> nextController.emulator?.let { applyTerminalPalette(it, palette) } }
        nextController.emulator?.let { terminal ->
            terminal.setCursorBlinkingEnabled(cursorBlink)
            if (!cursorBlink) terminal.setCursorBlinkState(true)
        }
        ensureRenderer(fontPixels())
        updateTerminalSize()
        syncBlink()
        invalidate()
    }

    fun setLineHeightMultiplier(multiplier: Float) {
        val next = multiplier.coerceIn(1f, 2f)
        if (next == lineHeightMultiplier) return
        lineHeightMultiplier = next
        updateTerminalSize()
        invalidate()
    }

    fun setCursorBlink(enabled: Boolean) {
        cursorBlink = enabled
        controller?.emulator?.let { terminal ->
            terminal.setCursorBlinkingEnabled(enabled)
            if (!enabled) terminal.setCursorBlinkState(true)
        }
        syncBlink()
        invalidate()
    }

    fun setPalette(palette: TerminalPalette) {
        if (appliedPalette == palette) return
        appliedPalette = palette
        setBackgroundColor(palette.background)
        controller?.emulator?.let { applyTerminalPalette(it, palette) }
        invalidate()
    }

    fun showMatch(externalRow: Int, startColumn: Int, endColumnExclusive: Int) {
        searchRow = externalRow
        searchX1 = startColumn.coerceAtLeast(0)
        searchX2 = (endColumnExclusive - 1).coerceAtLeast(searchX1)
        invalidate()
    }

    fun clearMatch() {
        if (searchRow == Int.MIN_VALUE) return
        searchRow = Int.MIN_VALUE
        invalidate()
    }

    fun scrollToExternalRow(externalRow: Int) {
        val transcript = activeTranscriptRows()
        topRow = externalRow.coerceIn(-transcript, 0)
        invalidate()
    }

    fun selectAll() {
        val terminal = controller?.emulator ?: return
        val top = synchronized(terminal) { -terminal.screen.activeTranscriptRows }
        val bottom = (terminal.mRows - 1).coerceAtLeast(0)
        val right = (terminal.mColumns - 1).coerceAtLeast(0)
        selectionStart = TerminalCell(0, top)
        selectionEnd = TerminalCell(right, bottom)
        invalidate()
        if (copyOnSelect) finishSelection()
    }

    fun copySelection() {
        val live = selectionText()
        val text = live.ifBlank { lastSelection }
        if (text.isBlank()) return
        writeClipboard(text)
    }

    private fun fontPixels(): Int {
        val scaledDensity = resources.displayMetrics.density * resources.configuration.fontScale
        return (controller?.requestedFontSizeSp()?.times(scaledDensity) ?: (DEFAULT_FONT_SIZE * scaledDensity))
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun ensureRenderer(fontPx: Int) {
        if (fontPx == rendererTextSizePx) return
        rendererTextSizePx = fontPx
        renderer = TerminalRenderer(fontPx, Typeface.MONOSPACE)
    }

    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val terminal = controller?.emulator ?: return
        val bounds = highlightBounds()
        synchronized(terminal) {
            topRow = topRow.coerceIn(-terminal.screen.activeTranscriptRows, 0)
            drawRows(canvas, terminal, bounds)
        }
    }

    private fun highlightBounds(): IntArray {
        val start = selectionStart
        val end = selectionEnd
        if (start != null && end != null) {
            val first = minOf(start, end)
            val last = maxOf(start, end)
            return intArrayOf(first.row, last.row, first.column, last.column)
        }
        if (searchRow != Int.MIN_VALUE) return intArrayOf(searchRow, searchRow, searchX1, searchX2)
        return intArrayOf(-1, -1, -1, -1)
    }

    /**
     * TerminalRenderer always steps by Paint.getFontSpacing(). A multiplier above 1 draws each
     * row into its native slot, then places that slot lower so the gap and the row count agree.
     */
    private fun drawRows(canvas: Canvas, terminal: TerminalEmulator, bounds: IntArray) {
        val native = renderer.getFontLineSpacing().coerceAtLeast(1)
        val scaled = scaledLineSpacing()
        if (scaled == native) {
            renderer.render(terminal, canvas, topRow, bounds[0], bounds[1], bounds[2], bounds[3])
            return
        }
        val rows = terminal.mRows.coerceAtLeast(1)
        val bitmapWidth = width.coerceAtLeast(1)
        val bitmapHeight = (rows * native).coerceAtLeast(1)
        val bitmap = ensureRowBitmap(bitmapWidth, bitmapHeight)
        val offscreen = Canvas(bitmap)
        offscreen.drawColor(appliedPalette?.background ?: Color.BLACK)
        renderer.render(terminal, offscreen, topRow, bounds[0], bounds[1], bounds[2], bounds[3])
        val source = Rect()
        val destination = Rect()
        for (row in 0 until rows) {
            source.set(0, row * native, bitmapWidth, (row + 1) * native)
            destination.set(0, row * scaled, bitmapWidth, row * scaled + native)
            canvas.drawBitmap(bitmap, source, destination, null)
        }
    }

    private fun ensureRowBitmap(bitmapWidth: Int, bitmapHeight: Int): Bitmap {
        val current = rowBitmap
        if (current != null && current.width == bitmapWidth && current.height == bitmapHeight && !current.isRecycled) return current
        current?.recycle()
        return Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).also { rowBitmap = it }
    }

    private fun scaledLineSpacing(): Int {
        val native = renderer.getFontLineSpacing().coerceAtLeast(1)
        return (native * lineHeightMultiplier).roundToInt().coerceAtLeast(1)
    }

    private fun syncBlink() {
        if (cursorBlink && isAttachedToWindow) {
            if (!blinkPosted) {
                blinkPosted = true
                postDelayed(blinkTask, BLINK_MS)
            }
        } else {
            removeCallbacks(blinkTask)
            blinkPosted = false
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateTerminalSize()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTerminalSize()
        syncBlink()
    }

    override fun onDetachedFromWindow() {
        // The controller owns the emulator buffer; only release view callbacks to avoid keeping
        // a disconnected Compose/ViewModel graph alive across navigation or configuration changes.
        onInput = null
        onResize = null
        onContextMenu = null
        onPasteText = null
        onOpenLink = null
        onEscape = null
        onFind = null
        onHistory = null
        onPasteShortcut = null
        onCloseTab = null
        lastSignal = null
        selectionStart = null
        selectionEnd = null
        removeCallbacks(blinkTask)
        blinkPosted = false
        rowBitmap?.recycle()
        rowBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this@SshTerminalView, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isEmpty()) return true
                if (isPasteChunk(value)) onPasteText?.invoke(value) ?: dispatch(value)
                else dispatch(value)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) dispatch("\u007f".repeat(beforeLength))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = handleTerminalKey(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleTerminalKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.y
                startTopRow = topRow
                movedDuringGesture = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressArmed && hypot(event.x - longPressX, event.y - longPressY) > touchSlop) {
                    longPressArmed = false
                    startSelection(event)
                }
                if (selectionStart != null) {
                    selectionEnd = cellAt(event)
                    invalidate()
                } else {
                    val lineSpacing = scaledLineSpacing()
                    val rowDelta = ((event.y - dragStartY) / lineSpacing).roundToInt()
                    if (abs(event.y - dragStartY) > lineSpacing / 2f) {
                        movedDuringGesture = true
                        topRow = (startTopRow + rowDelta).coerceIn(-activeTranscriptRows(), 0)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressArmed && event.actionMasked == MotionEvent.ACTION_UP) {
                    longPressArmed = false
                    onContextMenu?.invoke()
                } else if (selectionStart != null) finishSelection()
                else if (!movedDuringGesture && !consumedTap) performClick()
                consumedTap = false
                longPressArmed = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        return true
    }

    private fun handleTerminalKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (closeSearchOnEscape && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onEscape?.invoke()
            return true
        }
        if (consumeHardwareShortcut(event)) return true
        val terminal = controller?.emulator ?: return false
        synchronized(terminal) {
            val modifiers = buildKeyModifiers(event)
            KeyHandler.getCode(
                event.keyCode,
                modifiers,
                terminal.isCursorKeysApplicationMode,
                terminal.isKeypadApplicationMode
            )?.let {
                dispatch(it)
                return true
            }
        }
        if (event.unicodeChar != 0) {
            dispatch(String(Character.toChars(event.unicodeChar)))
            return true
        }
        return false
    }

    /** Fixed hardware-keyboard shortcuts. Ctrl+C stays with the terminal as an interrupt. */
    private fun consumeHardwareShortcut(event: KeyEvent): Boolean {
        if (!event.isCtrlPressed || event.isAltPressed) return false
        val fire = event.repeatCount == 0
        return when (event.keyCode) {
            KeyEvent.KEYCODE_F -> if (event.isShiftPressed) false else { if (fire) onFind?.invoke(); true }
            KeyEvent.KEYCODE_H -> if (event.isShiftPressed) { if (fire) onHistory?.invoke(); true } else false
            KeyEvent.KEYCODE_C -> if (event.isShiftPressed) { if (fire) copySelection(); true } else false
            KeyEvent.KEYCODE_V -> { if (fire) onPasteShortcut?.invoke(); true }
            KeyEvent.KEYCODE_W -> if (event.isShiftPressed) false else { if (fire) onCloseTab?.invoke(); true }
            else -> false
        }
    }

    private fun buildKeyModifiers(event: KeyEvent): Int {
        var modifiers = 0
        if (event.isCtrlPressed) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        return modifiers
    }

    private fun updateTerminalSize() {
        if (controller?.emulator == null || width == 0 || height == 0) return
        ensureRenderer(fontPixels())
        val fontWidth = renderer.getFontWidth().takeIf { it.isFinite() && it > 0f } ?: return
        val viewport = measureTerminalViewport(
            width = width,
            height = height,
            fontWidth = fontWidth,
            lineSpacing = scaledLineSpacing(),
            paddingLeft = paddingLeft,
            paddingTop = paddingTop,
            paddingRight = paddingRight,
            paddingBottom = paddingBottom,
            minColumns = MIN_COLUMNS,
            minRows = MIN_ROWS
        ) ?: return
        val signal = TerminalLayoutSignal(
            columns = viewport.columns,
            rows = viewport.rows,
            fontPx = rendererTextSizePx,
            density = resources.displayMetrics.density,
            fontScale = resources.configuration.fontScale
        )
        val publication = publishTerminalLayout(lastSignal, signal, onResize != null)
        lastSignal = publication.remembered
        if (publication.effect.redraw) invalidate()
        if (publication.effect.resize) onResize?.invoke(viewport.columns, viewport.rows)
    }

    private fun activeTranscriptRows(): Int {
        val terminal = controller?.emulator ?: return 0
        return activeTranscriptRows(terminal)
    }

    private fun activeTranscriptRows(terminal: TerminalEmulator): Int =
        synchronized(terminal) { terminal.screen.activeTranscriptRows }

    private fun startSelection(event: MotionEvent) {
        selectionStart = cellAt(event)
        selectionEnd = selectionStart
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun finishSelection() {
        val text = selectionText()
        selectionStart = null
        selectionEnd = null
        if (text.isBlank()) {
            invalidate()
            return
        }
        lastSelection = text
        onTextSelected?.invoke(text)
        if (copyOnSelect) writeClipboard(text)
        invalidate()
    }

    private fun selectionText(): String {
        val start = selectionStart ?: return ""
        val end = selectionEnd ?: return ""
        val first = minOf(start, end)
        val last = maxOf(start, end)
        val terminal = controller?.emulator ?: return ""
        return synchronized(terminal) {
            terminal.getSelectedText(
                first.column,
                first.row,
                (last.column + 1).coerceAtMost(terminal.mColumns),
                last.row.coerceAtMost(terminal.mRows - 1)
            )
        }
    }

    private fun writeClipboard(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.terminal_clipboard_label), text))
    }

    private fun cellAt(event: MotionEvent): TerminalCell {
        val fontWidth = renderer.getFontWidth().takeIf { it.isFinite() && it > 0f } ?: 1f
        val lineSpacing = scaledLineSpacing()
        val terminal = controller?.emulator ?: return TerminalCell(0, 0)
        return synchronized(terminal) {
            val columns = terminal.mColumns
            val rows = terminal.mRows
            val column = (event.x / fontWidth).toInt().coerceIn(0, columns - 1)
            val row = (topRow + event.y.div(lineSpacing).toInt())
                .coerceIn(-terminal.screen.activeTranscriptRows, rows - 1)
            TerminalCell(column, row)
        }
    }

    private fun dispatch(text: String?) {
        if (!text.isNullOrEmpty()) onInput?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    private fun isPasteChunk(text: String): Boolean {
        if (text == "\n" || text == "\r" || text == "\r\n") return false
        return text.contains('\n') || text.contains('\r') || text.length > 1
    }

    private companion object {
        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 32
        const val MIN_COLUMNS = 4
        const val MIN_ROWS = 4
        const val BLINK_MS = 500L
    }

    private data class TerminalCell(val column: Int, val row: Int) : Comparable<TerminalCell> {
        override fun compareTo(other: TerminalCell): Int =
            if (row != other.row) row.compareTo(other.row) else column.compareTo(other.column)
    }
}
