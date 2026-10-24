package io.github.openfinalshell.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLayoutTest {
    @Test
    fun `wide window docks files below and monitor beside the terminal`() {
        val chrome = resolveSessionChrome(
            widthDp = 1400,
            heightDp = 900,
            connectionWidthDp = 280,
            monitorWidthDp = 280,
            fileHeightDp = 240,
            filesOpen = true,
            monitorOpen = true,
            sideRequested = true,
            wide = true
        )

        assertTrue(chrome.showSidePane)
        assertEquals(280, chrome.sideWidthDp)
        assertEquals(SessionPanelMode.BOTTOM, chrome.fileMode)
        assertEquals(240, chrome.fileHeightDp)
        assertEquals(SessionPanelMode.END, chrome.monitorMode)
        assertEquals(280, chrome.monitorWidthDp)
    }

    @Test
    fun `the 600 dp break keeps the side pane and overlays the monitor`() {
        val chrome = resolveSessionChrome(
            widthDp = 600,
            heightDp = 800,
            connectionWidthDp = 280,
            monitorWidthDp = 280,
            fileHeightDp = 240,
            filesOpen = false,
            monitorOpen = true,
            sideRequested = true,
            wide = true
        )

        assertTrue(chrome.showSidePane)
        assertEquals(SessionPanelMode.OVERLAY, chrome.monitorMode)
        assertEquals(WorkbenchFormFactor.TABLET, workbenchFormFactor(600))
        assertEquals(WorkbenchFormFactor.PHONE, workbenchFormFactor(599))
    }

    @Test
    fun `a window that cannot hold the side pane drops it after the monitor`() {
        val chrome = resolveSessionChrome(
            widthDp = 560,
            heightDp = 800,
            connectionWidthDp = 280,
            monitorWidthDp = 280,
            fileHeightDp = 240,
            filesOpen = false,
            monitorOpen = true,
            sideRequested = true,
            wide = true
        )

        assertFalse(chrome.showSidePane)
        assertEquals(SessionPanelMode.OVERLAY, chrome.monitorMode)
    }

    @Test
    fun `theme toggle leaves system mode for an explicit opposite`() {
        assertEquals("light", WorkbenchTheme.next("dark", resolvedDark = true))
        assertEquals("dark", WorkbenchTheme.next("light", resolvedDark = false))
        assertEquals("light", WorkbenchTheme.next("system", resolvedDark = true))
        assertEquals("dark", WorkbenchTheme.next("system", resolvedDark = false))
    }

    @Test
    fun `stored widths stay at or above the minimums when the window can hold them`() {
        val chrome = resolveSessionChrome(
            widthDp = 1100,
            heightDp = 800,
            connectionWidthDp = 80,
            monitorWidthDp = 10,
            fileHeightDp = 40,
            filesOpen = true,
            monitorOpen = true,
            sideRequested = true,
            wide = true
        )

        assertTrue(chrome.sideWidthDp >= SessionLayoutLimits.SIDE_MIN_DP)
        assertTrue(chrome.monitorWidthDp >= SessionLayoutLimits.MONITOR_MIN_DP)
        assertTrue(chrome.fileHeightDp >= SessionLayoutLimits.FILE_MIN_HEIGHT_DP)
    }

    @Test
    fun `drag preferences shrink to keep the terminal minimum`() {
        val chrome = resolveSessionChrome(
            widthDp = 980,
            heightDp = 800,
            connectionWidthDp = 400,
            monitorWidthDp = 400,
            fileHeightDp = 200,
            filesOpen = false,
            monitorOpen = true,
            sideRequested = true,
            wide = true
        )
        val content = 980 - SessionLayoutLimits.RAIL_DP
        val terminal = content - chrome.sideWidthDp - chrome.monitorWidthDp - SessionLayoutLimits.SPLITTER_DP * 2

        assertTrue(chrome.showSidePane)
        assertEquals(SessionPanelMode.END, chrome.monitorMode)
        assertTrue(chrome.sideWidthDp >= SessionLayoutLimits.SIDE_MIN_DP)
        assertTrue(chrome.monitorWidthDp >= SessionLayoutLimits.MONITOR_MIN_DP)
        assertTrue(terminal >= SessionLayoutLimits.TERMINAL_MIN_WIDTH_DP)
    }

    @Test
    fun `ime and toolbar collapse a short file split into a fullscreen page`() {
        val chrome = resolveSessionChrome(
            widthDp = 800,
            heightDp = 400,
            connectionWidthDp = 280,
            monitorWidthDp = 280,
            fileHeightDp = 240,
            filesOpen = true,
            monitorOpen = false,
            sideRequested = false,
            wide = false,
            imeDp = 200,
            toolbarDp = 96
        )

        assertEquals(SessionPanelMode.FULLSCREEN, chrome.fileMode)
    }

    @Test
    fun `compact layout shows one bottom panel`() {
        val files = resolveSessionChrome(
            widthDp = 400,
            heightDp = 800,
            connectionWidthDp = 280,
            monitorWidthDp = 280,
            fileHeightDp = 200,
            filesOpen = true,
            monitorOpen = true,
            sideRequested = false,
            wide = false
        )
        val monitor = resolveSessionChrome(
            widthDp = 400,
            heightDp = 800,
            connectionWidthDp = 280,
            monitorWidthDp = 280,
            fileHeightDp = 200,
            filesOpen = false,
            monitorOpen = true,
            sideRequested = false,
            wide = false
        )

        assertEquals(SessionPanelMode.BOTTOM, files.fileMode)
        assertEquals(SessionPanelMode.HIDDEN, files.monitorMode)
        assertEquals(SessionPanelMode.BOTTOM, monitor.monitorMode)
    }
}
