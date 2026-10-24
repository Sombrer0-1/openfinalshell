package io.github.openfinalshell.android.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeepAlivePolicyTest {
    @Test
    fun `any live session transfer or forward holds the process`() {
        assertTrue(SessionKeepAlivePolicy.shouldHold(1, 0, 0))
        assertTrue(SessionKeepAlivePolicy.shouldHold(0, 1, 0))
        assertTrue(SessionKeepAlivePolicy.shouldHold(0, 0, 1))
        assertFalse(SessionKeepAlivePolicy.shouldHold(0, 0, 0))
    }

    @Test
    fun `a recreated process does not pretend the sockets survived`() {
        assertFalse(SessionKeepAlivePolicy.keepRunningAfterStart(true, connecting = true, 2, 1, 1))
        assertTrue(SessionKeepAlivePolicy.keepRunningAfterStart(false, connecting = false, 1, 0, 0))
        assertTrue(SessionKeepAlivePolicy.keepRunningAfterStart(false, connecting = true, 0, 0, 0))
        assertFalse(SessionKeepAlivePolicy.keepRunningAfterStart(false, connecting = false, 0, 0, 0))
    }
}
