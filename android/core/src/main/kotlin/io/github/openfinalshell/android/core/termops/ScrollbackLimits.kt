package io.github.openfinalshell.android.core.termops

/**
 * 产品目标上限是 100000，但 Termux v0.118.0 的 TERMINAL_TRANSCRIPT_ROWS_MAX = 50000。
 * 小于 100 或大于 50000 时，TerminalEmulator.getTerminalTranscriptRows 会静默改成
 * DEFAULT_TERMINAL_TRANSCRIPT_ROWS（2000）。因此对外可设范围只能是 1000..50000，
 * 禁止把 50001..100000 交给模拟器。回滚容量只对新建会话生效。
 */
object ScrollbackLimits {
    const val MIN = 1000
    const val MAX = 50_000
    const val PRODUCT_GOAL_MAX = 100_000
    const val TERMUX_SILENT_FALLBACK = 2000

    fun isSelectable(lines: Int): Boolean = lines in MIN..MAX

    fun clamp(lines: Int): Int = lines.coerceIn(MIN, MAX)

    /** Rows that may be passed into TerminalEmulator. Never a value Termux would replace with 2000. */
    fun forEmulator(lines: Int): Int {
        val value = clamp(lines)
        check(value in MIN..MAX)
        check(value !in (MAX + 1)..PRODUCT_GOAL_MAX)
        return value
    }
}
