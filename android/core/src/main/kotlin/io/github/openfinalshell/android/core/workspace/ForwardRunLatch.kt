package io.github.openfinalshell.android.core.workspace

data class RuleSession(val ruleId: String, val sessionId: String)

/** Rules the user stopped. Keyed by the bound tab, which keeps its id across reconnect. */
data class LatchState(val suppressed: Set<RuleSession> = emptySet())

sealed class ReadyDecision {
    data object Restore : ReadyDecision()
    data object StayStopped : ReadyDecision()
    data object IgnoreOtherTab : ReadyDecision()
}

/**
 * Manual stop sticks for the life of the bound tab, including reconnect.
 * Manual start lifts it. Deleting the rule or closing that tab forgets it.
 * Another tab becoming ready does not take the binding and does not lift the stop.
 */
object ForwardRunLatch {
    fun manualStop(state: LatchState, ruleId: String, sessionId: String): LatchState =
        state.copy(suppressed = state.suppressed + RuleSession(ruleId, sessionId))

    fun manualStart(state: LatchState, ruleId: String, sessionId: String): LatchState =
        state.copy(suppressed = state.suppressed - RuleSession(ruleId, sessionId))

    fun ruleDeleted(state: LatchState, ruleId: String): LatchState =
        state.copy(suppressed = state.suppressed.filterNot { it.ruleId == ruleId }.toSet())

    fun tabClosed(state: LatchState, sessionId: String): LatchState =
        state.copy(suppressed = state.suppressed.filterNot { it.sessionId == sessionId }.toSet())

    fun onReady(state: LatchState, ruleId: String, boundSessionId: String, readySessionId: String): ReadyDecision {
        if (readySessionId != boundSessionId) return ReadyDecision.IgnoreOtherTab
        return if (RuleSession(ruleId, boundSessionId) in state.suppressed) {
            ReadyDecision.StayStopped
        } else {
            ReadyDecision.Restore
        }
    }
}
