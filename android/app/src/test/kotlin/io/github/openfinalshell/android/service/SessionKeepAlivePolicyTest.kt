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
    fun `connects in flight hold the process even with zero counted work`() {
        assertTrue(SessionKeepAlivePolicy.shouldHoldIncludingConnects(0, 0, 0, 1))
        assertTrue(SessionKeepAlivePolicy.shouldHoldIncludingConnects(0, 1, 0, 0))
        assertFalse(SessionKeepAlivePolicy.shouldHoldIncludingConnects(0, 0, 0, 0))
    }

    @Test
    fun `closing sessions still holds while transfers or forwards remain`() {
        // Mirrors the disconnect path: zero open sessions must not stop FGS if other work remains.
        assertTrue(SessionKeepAlivePolicy.shouldHoldIncludingConnects(0, 1, 0, 0))
        assertTrue(SessionKeepAlivePolicy.shouldHoldIncludingConnects(0, 0, 1, 0))
        assertTrue(SessionKeepAlivePolicy.shouldHoldIncludingConnects(0, 0, 0, 1))
        assertFalse(SessionKeepAlivePolicy.shouldHoldIncludingConnects(0, 0, 0, 0))
    }

    @Test
    fun `only update actions persist session counts — begin must not wipe them`() {
        assertTrue(SessionKeepAlivePolicy.shouldPersistCounts(isUpdateAction = true))
        assertFalse(SessionKeepAlivePolicy.shouldPersistCounts(isUpdateAction = false))
    }

    @Test
    fun `a recreated process does not pretend the sockets survived`() {
        assertFalse(SessionKeepAlivePolicy.keepRunningAfterStart(true, connecting = true, 2, 1, 1))
        assertTrue(SessionKeepAlivePolicy.keepRunningAfterStart(false, connecting = false, 1, 0, 0))
        assertTrue(SessionKeepAlivePolicy.keepRunningAfterStart(false, connecting = true, 0, 0, 0))
        assertFalse(SessionKeepAlivePolicy.keepRunningAfterStart(false, connecting = false, 0, 0, 0))
    }
}
