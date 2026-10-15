package io.github.openfinalshell.android.core.termops

import java.util.UUID

data class CommandHistoryEntry(
    val id: String,
    val text: String,
    val useCount: Int,
    val lastSubmittedAt: Long,
    val createdAt: Long
)

/**
 * Merge, cap, and filter for command history. Callers store the ciphertext.
 * Same text increments [CommandHistoryEntry.useCount] and replaces [CommandHistoryEntry.lastSubmittedAt].
 * At most [MAX_ENTRIES] rows are kept, by most recent submission. A command longer than [MAX_CHARS]
 * is rejected with [IllegalArgumentException], matching `recordCommand`'s `require`, and is not truncated.
 */
class CommandHistoryIndex(
    existing: List<CommandHistoryEntry> = emptyList(),
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val byText = LinkedHashMap<String, CommandHistoryEntry>()
    private val removed = ArrayList<CommandHistoryEntry>()

    init {
        for (entry in existing) absorb(entry)
        trim()
    }

    fun record(command: String, now: Long): CommandHistoryEntry {
        require(command.isNotBlank() && command.length <= MAX_CHARS)
        val saved = byText[command]?.let { current ->
            current.copy(useCount = current.useCount + 1, lastSubmittedAt = now)
        } ?: CommandHistoryEntry(newId(), command, 1, now, now)
        byText[command] = saved
        trim()
        return saved
    }

    fun entries(): List<CommandHistoryEntry> = byText.values.sortedWith(NEWEST_FIRST)

    fun filter(keyword: String): List<CommandHistoryEntry> {
        if (keyword.isEmpty()) return entries()
        return entries().filter { it.text.contains(keyword, ignoreCase = true) }
    }

    fun clear() {
        removed += byText.values
        byText.clear()
    }

    /** Rows dropped by loading, [record], or [clear]. Callers delete their ciphertext. */
    fun evicted(): List<CommandHistoryEntry> = removed.toList()

    private fun absorb(entry: CommandHistoryEntry) {
        val previous = byText[entry.text]
        if (previous == null) {
            byText[entry.text] = entry
            return
        }
        if (previous.id == entry.id) return
        val incomingNewer = entry.lastSubmittedAt > previous.lastSubmittedAt
        val keep = if (incomingNewer) entry else previous
        val drop = if (incomingNewer) previous else entry
        byText[entry.text] = keep.copy(
            useCount = previous.useCount + entry.useCount,
            lastSubmittedAt = maxOf(previous.lastSubmittedAt, entry.lastSubmittedAt),
            createdAt = minOf(previous.createdAt, entry.createdAt)
        )
        removed += drop
    }

    private fun trim() {
        val overflow = byText.size - MAX_ENTRIES
        if (overflow <= 0) return
        val victims = byText.values.sortedWith(OLDEST_FIRST).take(overflow)
        for (victim in victims) {
            byText.remove(victim.text)
            removed += victim
        }
    }

    companion object {
        const val MAX_ENTRIES = 1000
        const val MAX_CHARS = 32768

        private val NEWEST_FIRST = compareByDescending<CommandHistoryEntry> { it.lastSubmittedAt }
            .thenByDescending { it.createdAt }
            .thenBy { it.id }
        private val OLDEST_FIRST = compareBy<CommandHistoryEntry> { it.lastSubmittedAt }
            .thenBy { it.createdAt }
            .thenBy { it.id }
    }
}
