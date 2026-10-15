package io.github.openfinalshell.android.core.workspace

import io.github.openfinalshell.android.core.model.SessionState

data class CollectionDirective(
    val start: Boolean,
    val stop: Boolean,
    val resetDelta: Boolean
)

/**
 * When a session's monitor collector runs.
 *
 * The panel's collapsed flag is an input so callers can pass it through, and it is not a branch:
 * hiding the panel must not turn collection off. A reconnect that reaches [SessionState.READY]
 * starts a new collector and clears that session's delta baseline.
 */
object MonitorCollectionPolicy {
    fun decide(
        state: SessionState,
        monitorEnabled: Boolean,
        panelCollapsed: Boolean,
        reconnected: Boolean
    ): CollectionDirective {
        val collect = state == SessionState.READY && monitorEnabled
        val directive = CollectionDirective(
            start = collect,
            stop = when (state) {
                SessionState.CLOSED, SessionState.RECONNECTING -> true
                SessionState.READY -> !monitorEnabled
                SessionState.CONNECTING, SessionState.AUTHENTICATING -> false
            },
            resetDelta = collect && reconnected
        )
        return when (panelCollapsed) {
            true, false -> directive
        }
    }
}
