package io.github.openfinalshell.android.core.session

/**
 * Per-session workspace state. File listings, selection and panel visibility stay with the session
 * that requested them, so a late listing cannot overwrite another session or a newer navigation.
 */
data class SessionFileState(
    val confirmedPath: String = "/",
    val backStack: List<String> = emptyList(),
    val forwardStack: List<String> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val generation: Long = 0,
    val sortKey: FileSortKey = FileSortKey.NAME,
    val sortAscending: Boolean = true
)

enum class FileSortKey { NAME, SIZE, MODIFIED, OWNER }

data class SessionPanels(
    val filesOpen: Boolean = false,
    val monitorOpen: Boolean = false,
    val title: String? = null
)

data class NavigationRequest(val sessionId: String, val path: String, val generation: Long)

fun beginNavigation(state: SessionFileState, path: String): Pair<SessionFileState, Long> {
    val generation = state.generation + 1
    return state.copy(generation = generation) to generation
}

/** Applies a listing only when it is still the newest request for this session. */
fun applyListing(state: SessionFileState, request: NavigationRequest, path: String): SessionFileState? {
    if (request.generation != state.generation) return null
    if (path == state.confirmedPath) return state.copy(selectedPaths = emptySet())
    return state.copy(
        confirmedPath = path,
        backStack = state.backStack + state.confirmedPath,
        forwardStack = emptyList(),
        selectedPaths = emptySet()
    )
}

fun goBack(state: SessionFileState): Pair<SessionFileState, String>? {
    val previous = state.backStack.lastOrNull() ?: return null
    val generation = state.generation + 1
    return state.copy(
        backStack = state.backStack.dropLast(1),
        forwardStack = state.forwardStack + state.confirmedPath,
        generation = generation
    ) to previous
}

fun goForward(state: SessionFileState): Pair<SessionFileState, String>? {
    val next = state.forwardStack.lastOrNull() ?: return null
    val generation = state.generation + 1
    return state.copy(
        forwardStack = state.forwardStack.dropLast(1),
        backStack = state.backStack + state.confirmedPath,
        generation = generation
    ) to next
}
