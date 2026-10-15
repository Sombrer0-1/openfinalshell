package io.github.openfinalshell.android.core.termops

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ScrollbackLimitsTest {
    @Test
    fun `the settable range is 1000 through 50000 and the emulator never sees the silent fallback`() {
        assertEquals(1_000, ScrollbackLimits.MIN)
        assertEquals(50_000, ScrollbackLimits.MAX)
        assertTrue(ScrollbackLimits.isSelectable(1_000))
        assertTrue(ScrollbackLimits.isSelectable(50_000))
        assertFalse(ScrollbackLimits.isSelectable(999))
        assertFalse(ScrollbackLimits.isSelectable(50_001))
        assertFalse(ScrollbackLimits.isSelectable(100_000))

        assertEquals(1_000, ScrollbackLimits.clamp(100))
        assertEquals(1_000, ScrollbackLimits.clamp(999))
        assertEquals(50_000, ScrollbackLimits.clamp(50_001))
        assertEquals(50_000, ScrollbackLimits.clamp(100_000))
        assertEquals(50_000, ScrollbackLimits.forEmulator(75_000))
        assertTrue(ScrollbackLimits.forEmulator(100_000) != ScrollbackLimits.TERMUX_SILENT_FALLBACK)
        assertFalse(ScrollbackLimits.forEmulator(50_001) in 50_001..100_000)
    }
}
