package io.github.openfinalshell.android.terminal

/**
 * Remembers the PTY size the user can see, separately from the size a shell was opened with.
 *
 * A measurement that arrives while `open` is still in flight must not be dropped, and a later
 * open request must not push the session back to the placeholder size once a real measurement
 * has been applied.
 */
internal class TerminalSizeTracker {
    enum class Phase { IDLE, OPENING, OPEN }

    data class Size(val cols: Int, val rows: Int)

    private var phase = Phase.IDLE
    private var desired = Size(80, 24)
    private var applied: Size? = null

    @Synchronized
    fun beginOpen(cols: Int, rows: Int): Size? {
        if (phase != Phase.IDLE) return null
        phase = Phase.OPENING
        desired = Size(cols.coerceAtLeast(1), rows.coerceAtLeast(1))
        applied = null
        return desired
    }

    /** Records a measured size. Returns it only when the shell is already open and differs. */
    @Synchronized
    fun noteResize(cols: Int, rows: Int): Size? {
        desired = Size(cols.coerceAtLeast(1), rows.coerceAtLeast(1))
        if (phase != Phase.OPEN || applied == desired) return null
        return desired
    }

    /** Moves to open and returns a size that arrived after the PTY was created, if any. */
    @Synchronized
    fun finishOpen(openedCols: Int, openedRows: Int): Size? {
        if (phase != Phase.OPENING) return null
        phase = Phase.OPEN
        val opened = Size(openedCols, openedRows)
        applied = opened
        return if (desired != opened) desired else null
    }

    @Synchronized
    fun markApplied(cols: Int, rows: Int) {
        applied = Size(cols, rows)
    }

    @Synchronized
    fun unapplied(): Size? {
        if (phase != Phase.OPEN) return null
        return desired.takeIf { it != applied }
    }

    @Synchronized
    fun failOpen() {
        if (phase == Phase.OPENING) {
            phase = Phase.IDLE
            applied = null
        }
    }

    @Synchronized
    fun reset() {
        phase = Phase.IDLE
        applied = null
    }
}
