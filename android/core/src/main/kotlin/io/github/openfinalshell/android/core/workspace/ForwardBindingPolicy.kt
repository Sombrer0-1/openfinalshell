package io.github.openfinalshell.android.core.workspace

import io.github.openfinalshell.android.core.model.SessionState

data class ForwardSessionRef(
    val sessionId: String,
    val profileId: String,
    val state: SessionState,
    val readyAt: Long,
    val selected: Boolean,
    val localShell: Boolean
)

data class RunningForward(val sessionId: String)

sealed class ForwardBind {
    /** The rule already has a live binding. A new tab must not start a second listener. */
    data class Keep(val sessionId: String) : ForwardBind()
    data class Start(val sessionId: String) : ForwardBind()
    data object RejectLocalShell : ForwardBind()
    data object NoReadySession : ForwardBind()
}

/**
 * One forward rule binds at most one SSH session.
 *
 * A running binding wins over a newer selected tab. A fresh start uses the connection's selected
 * ready session, otherwise the earliest ready session. Local shells are not SSH transports.
 */
object ForwardBindingPolicy {
    fun choose(
        profileId: String,
        sessions: List<ForwardSessionRef>,
        running: RunningForward?
    ): ForwardBind {
        val mine = sessions.filter { it.profileId == profileId }
        if (running != null) {
            val bound = mine.find { it.sessionId == running.sessionId }
            if (bound != null && bound.state != SessionState.CLOSED) {
                return if (bound.localShell) ForwardBind.RejectLocalShell else ForwardBind.Keep(bound.sessionId)
            }
        }
        val ready = mine.filter { it.state == SessionState.READY }
        val ssh = ready.filterNot { it.localShell }
        if (ssh.isEmpty()) {
            return if (ready.any { it.localShell }) ForwardBind.RejectLocalShell else ForwardBind.NoReadySession
        }
        val selected = ssh.filter { it.selected }.minWithOrNull(compareBy({ it.readyAt }, { it.sessionId }))
        val chosen = selected ?: ssh.minWith(compareBy({ it.readyAt }, { it.sessionId }))
        return ForwardBind.Start(chosen.sessionId)
    }
}
