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
     * Same as [shouldHold], plus an in-flight connect handshake that has not yet produced a
     * counted session.
     */
    fun shouldHoldIncludingConnects(
        openSessions: Int,
        activeTransfers: Int,
        activeForwards: Int,
        connectsInFlight: Int
    ): Boolean =
        connectsInFlight > 0 || shouldHold(openSessions, activeTransfers, activeForwards)

    /**
     * Only an UPDATE intent may write session/transfer/forward counts into prefs.
     * A START/begin intent must leave existing counts alone so a second connect during an open
     * session does not wipe the notification to zeros.
     */
    fun shouldPersistCounts(isUpdateAction: Boolean): Boolean = isUpdateAction

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

    /**
     * Snapshot of what the last FGS update published. Used to skip identical UPDATEs so a busy
     * transfer (many chunk progress events) does not flood AMS / prefs / notifications.
     */
    data class PublishedCounts(
        val sessions: Int,
        val transfers: Int,
        val forwards: Int,
        val connecting: Boolean
    )

    /** True when counts differ from the last publish, or nothing has been published yet. */
    fun shouldRepublish(last: PublishedCounts?, next: PublishedCounts): Boolean =
        last == null || last != next

    /**
     * A non-STOP intent on a live service instance must clear a prior stop flag so START/UPDATE
     * are not swallowed after a raced STOP (restores base semantics where only ACTION_STOP stopped).
     */
    fun shouldResetStopRequested(actionIsStop: Boolean, intentPresent: Boolean): Boolean =
        intentPresent && !actionIsStop

}
