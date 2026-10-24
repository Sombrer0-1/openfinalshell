package io.github.openfinalshell.android.service

/**
 * When the process should stay awake for SSH.
 *
 * A live session, transfer, or forward is enough. Force-stop and process death drop the sockets;
 * a sticky restart must not claim those sockets are still open.
 */
object SessionKeepAlivePolicy {
    fun shouldHold(openSessions: Int, activeTransfers: Int, activeForwards: Int): Boolean =
        openSessions > 0 || activeTransfers > 0 || activeForwards > 0

    /**
     * A null start intent means Android recreated the process. The TCP sessions are gone.
     * [connecting] keeps the process up during the handshake, before a session is counted.
     */
    fun keepRunningAfterStart(
        startIntentMissing: Boolean,
        connecting: Boolean,
        openSessions: Int,
        activeTransfers: Int,
        activeForwards: Int
    ): Boolean = !startIntentMissing && (connecting || shouldHold(openSessions, activeTransfers, activeForwards))
}
