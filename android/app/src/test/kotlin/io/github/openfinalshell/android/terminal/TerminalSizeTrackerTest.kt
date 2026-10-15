package io.github.openfinalshell.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalSizeTrackerTest {
    @Test
    fun `a resize during open is sent once the shell exists`() {
        val tracker = TerminalSizeTracker()
        val opened = tracker.beginOpen(80, 24)

        assertEquals(TerminalSizeTracker.Size(80, 24), opened)
        assertNull(tracker.noteResize(40, 12))

        assertEquals(TerminalSizeTracker.Size(40, 12), tracker.finishOpen(80, 24))
    }

    @Test
    fun `a second open does not replace a measured size`() {
        val tracker = TerminalSizeTracker()
        tracker.beginOpen(80, 24)
        tracker.noteResize(40, 12)
        tracker.finishOpen(80, 24)
        tracker.markApplied(40, 12)

        assertNull(tracker.beginOpen(80, 24))
        assertNull(tracker.unapplied())
    }

    @Test
    fun `duplicate measurements are not sent again`() {
        val tracker = TerminalSizeTracker()
        tracker.beginOpen(80, 24)
        tracker.finishOpen(80, 24)

        assertNull(tracker.noteResize(80, 24))
        assertEquals(TerminalSizeTracker.Size(100, 30), tracker.noteResize(100, 30))
        tracker.markApplied(100, 30)
        assertNull(tracker.noteResize(100, 30))
    }

    @Test
    fun `a failed open can be retried`() {
        val tracker = TerminalSizeTracker()
        tracker.beginOpen(80, 24)
        tracker.failOpen()

        assertEquals(TerminalSizeTracker.Size(90, 20), tracker.beginOpen(90, 20))
    }

    @Test
    fun `prepareRebind keeps the measured size and lets the next open start`() {
        val tracker = TerminalSizeTracker()
        tracker.beginOpen(80, 24)
        tracker.noteResize(100, 40)
        tracker.finishOpen(80, 24)
        tracker.markApplied(100, 40)

        assertNull(tracker.beginOpen(100, 40))
        tracker.prepareRebind()

        assertEquals(TerminalSizeTracker.Size(100, 40), tracker.desiredSize())
        assertEquals(TerminalSizeTracker.Size(100, 40), tracker.beginOpen(100, 40))
    }
}
