package io.github.openfinalshell.android.core.filesync

enum class ListedKind { FILE, DIRECTORY, SYMLINK, OTHER }

data class ListedEntry(val path: String, val kind: ListedKind, val nameInvalid: Boolean = false)

/**
 * `rm -rf` is only offered for a selection made entirely of absolute directories.
 * Preview and the command that would be executed are the same string.
 */
object FastDeletePlan {
    const val MIN_SEGMENTS = 2
    private const val MAX_LENGTH = 4096

    sealed interface Result {
        data class Ready(val command: String) : Result
        data object Unavailable : Result
    }

    fun plan(entries: List<ListedEntry>): Result {
        if (entries.isEmpty() || entries.any { it.kind != ListedKind.DIRECTORY || it.nameInvalid }) {
            return Result.Unavailable
        }
        return Result.Ready(commandFor(entries.map { assertDeletable(it.path) }))
    }

    /** Same quoting and the same path checks as [plan]. */
    fun executeCommand(entries: List<ListedEntry>): String = when (val result = plan(entries)) {
        is Result.Ready -> result.command
        Result.Unavailable -> throw IllegalStateException("fast delete is unavailable")
    }

    fun assertDeletable(path: String): String {
        if (path.trim().isEmpty()) throw IllegalArgumentException("empty")
        if (path.any { it == '\u0000' || it == '\n' || it == '\r' }) throw IllegalArgumentException("control")
        if (path.length > MAX_LENGTH) throw IllegalArgumentException("too-long")
        if (!path.startsWith("/")) throw IllegalArgumentException("not-absolute")
        val segments = path.split('/')
        if (segments.any { it == "." || it == ".." }) throw IllegalArgumentException("dot-segment")
        if (segments.count { it.isNotEmpty() } < MIN_SEGMENTS) throw IllegalArgumentException("too-shallow")
        return path
    }

    private fun commandFor(paths: List<String>): String {
        val quoted = paths.joinToString(" ") { shQuote(it) }
        return "rm -rf -- $quoted"
    }
}
