package io.github.openfinalshell.android.ui

/** Where a session panel sits after the window, toolbar, insets and IME have been accounted for. */
enum class SessionPanelMode { HIDDEN, BOTTOM, END, OVERLAY, FULLSCREEN }

data class SessionChrome(
    val showSidePane: Boolean,
    val sideWidthDp: Int,
    val fileMode: SessionPanelMode,
    val fileHeightDp: Int,
    val monitorMode: SessionPanelMode,
    val monitorWidthDp: Int
)

object SessionLayoutLimits {
    const val NAV_BREAK_DP = 600
    const val SIDE_MIN_DP = 200
    const val TERMINAL_MIN_WIDTH_DP = 320
    const val MONITOR_MIN_DP = 240
    const val TERMINAL_MIN_HEIGHT_DP = 120
    const val FILE_MIN_HEIGHT_DP = 160
    const val TOOLBAR_DP = 48
    const val SPLITTER_DP = 8
    const val RAIL_DP = 80
}

/**
 * Decides the session chrome for the current window.
 *
 * Three columns need the side pane, the terminal and the monitor, each at its minimum, plus
 * splitters. When that does not fit, the monitor becomes an overlay first and the side pane
 * collapses after that. A vertical split needs the terminal and the file pane at their minimums
 * after the toolbar, system insets and IME; otherwise that pane becomes a fullscreen page.
 */
fun resolveSessionChrome(
    widthDp: Int,
    heightDp: Int,
    connectionWidthDp: Int,
    monitorWidthDp: Int,
    fileHeightDp: Int,
    filesOpen: Boolean,
    monitorOpen: Boolean,
    sideRequested: Boolean,
    wide: Boolean,
    imeDp: Int = 0,
    systemInsetDp: Int = 0,
    toolbarDp: Int = SessionLayoutLimits.TOOLBAR_DP + SessionLayoutLimits.TOOLBAR_DP,
    railDp: Int = SessionLayoutLimits.RAIL_DP
): SessionChrome {
    val split = SessionLayoutLimits.SPLITTER_DP
    val termW = SessionLayoutLimits.TERMINAL_MIN_WIDTH_DP
    val sideMin = SessionLayoutLimits.SIDE_MIN_DP
    val monMin = SessionLayoutLimits.MONITOR_MIN_DP
    val contentWidth = (widthDp - if (wide) railDp else 0).coerceAtLeast(0)
    val sidePref = connectionWidthDp.coerceAtLeast(sideMin)
    val monPref = monitorWidthDp.coerceAtLeast(monMin)

    fun needed(side: Boolean, monitor: Boolean, sideW: Int, monW: Int): Int {
        var total = termW
        if (side) total += sideW + split
        if (monitor) total += monW + split
        return total
    }

    var showSide = wide && sideRequested
    var dockMonitor = wide && monitorOpen
    if (dockMonitor && needed(showSide, true, sideMin, monMin) > contentWidth) dockMonitor = false
    if (showSide && needed(true, dockMonitor, sideMin, if (dockMonitor) monMin else 0) > contentWidth) {
        showSide = false
    }

    var sideW = if (showSide) sidePref else 0
    var monW = if (dockMonitor) monPref else 0
    fun room(): Int = contentWidth - sideW - monW - (if (showSide) split else 0) - (if (dockMonitor) split else 0)
    while (room() < termW && ((showSide && sideW > sideMin) || (dockMonitor && monW > monMin))) {
        val sideSlack = if (showSide) sideW - sideMin else 0
        val monSlack = if (dockMonitor) monW - monMin else 0
        val need = termW - room()
        if (sideSlack >= monSlack && sideSlack > 0) sideW -= minOf(sideSlack, need)
        else if (monSlack > 0) monW -= minOf(monSlack, need)
        else break
    }

    val availableHeight = heightDp - toolbarDp - systemInsetDp.coerceAtLeast(0) - imeDp.coerceAtLeast(0)
    val splitHeight = SessionLayoutLimits.TERMINAL_MIN_HEIGHT_DP + split + SessionLayoutLimits.FILE_MIN_HEIGHT_DP
    val filePref = fileHeightDp.coerceAtLeast(SessionLayoutLimits.FILE_MIN_HEIGHT_DP)
    val fileMode: SessionPanelMode
    val fileH: Int
    if (!filesOpen) {
        fileMode = SessionPanelMode.HIDDEN
        fileH = filePref
    } else if (availableHeight >= splitHeight) {
        fileMode = SessionPanelMode.BOTTOM
        val maxFile = (availableHeight - SessionLayoutLimits.TERMINAL_MIN_HEIGHT_DP - split)
            .coerceAtLeast(SessionLayoutLimits.FILE_MIN_HEIGHT_DP)
        fileH = filePref.coerceIn(SessionLayoutLimits.FILE_MIN_HEIGHT_DP, maxFile)
    } else {
        fileMode = SessionPanelMode.FULLSCREEN
        fileH = filePref
    }

    val monitorMode = when {
        !monitorOpen -> SessionPanelMode.HIDDEN
        !wide && filesOpen -> SessionPanelMode.HIDDEN
        fileMode == SessionPanelMode.FULLSCREEN -> SessionPanelMode.HIDDEN
        dockMonitor -> if (availableHeight < SessionLayoutLimits.TERMINAL_MIN_HEIGHT_DP) {
            SessionPanelMode.FULLSCREEN
        } else {
            SessionPanelMode.END
        }
        availableHeight < splitHeight -> SessionPanelMode.FULLSCREEN
        wide -> SessionPanelMode.OVERLAY
        else -> SessionPanelMode.BOTTOM
    }
    val monitorWidth = when (monitorMode) {
        SessionPanelMode.END -> monW
        SessionPanelMode.OVERLAY -> monPref.coerceAtMost(contentWidth.coerceAtLeast(1)).coerceAtLeast(minOf(monMin, contentWidth.coerceAtLeast(1)))
        else -> monPref
    }
    return SessionChrome(
        showSidePane = showSide,
        sideWidthDp = if (showSide) sideW else sidePref,
        fileMode = fileMode,
        fileHeightDp = fileH,
        monitorMode = monitorMode,
        monitorWidthDp = monitorWidth
    )
}
