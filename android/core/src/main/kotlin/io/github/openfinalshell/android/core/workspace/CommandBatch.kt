package io.github.openfinalshell.android.core.workspace

enum class BatchFailureReason { RecheckFailed, WriteFailed }

data class BatchFailure(val targetId: String, val reason: BatchFailureReason)

data class BatchSkip(val targetId: String, val reason: CommandSkipReason)

data class BatchOutcome(
    val normalized: String,
    val clearBody: Boolean,
    val succeeded: List<String>,
    val failed: List<BatchFailure>,
    val skipped: List<BatchSkip>,
    /** Failed targets only. Skips and successes stay out so a retry cannot run them again. */
    val retryIds: List<String>,
    /** One full expanded body per successful submit. Insert-only sends are absent. */
    val history: List<String>
) {
    fun retryTargets(targets: List<CommandTarget>): List<CommandTarget> {
        val ids = retryIds.toSet()
        return targets.filter { it.id in ids }
    }
}

data class HistoryRow(val command: String, val count: Int, val lastSubmittedAt: Long)

/**
 * Phase-3 history rules used by a programmatic submit: one record per submit, merge identical
 * text by incrementing the count, drop anything past 32768 characters, keep the newest 1000.
 */
object CommandHistoryPolicy {
    const val MAX_CHARS = 32768
    const val MAX_ROWS = 1000

    fun accept(command: String): String? = command.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }

    fun record(rows: List<HistoryRow>, command: String, submittedAt: Long): List<HistoryRow> {
        val text = accept(command) ?: return rows
        val existing = rows.indexOfFirst { it.command == text }
        val updated = if (existing >= 0) {
            rows.toMutableList().also { copy ->
                val row = copy[existing]
                copy[existing] = row.copy(count = row.count + 1, lastSubmittedAt = submittedAt)
            }
        } else {
            rows + HistoryRow(text, 1, submittedAt)
        }
        return updated.sortedByDescending { it.lastSubmittedAt }.take(MAX_ROWS)
    }
}

/**
 * Sends one normalized body to the target set captured at click time.
 *
 * Each target is checked again, with [recheck], immediately before it is written. The editor
 * clears only when every target was written. History is the expanded body of a successful write
 * that included the trailing submit, and only when it fits [CommandHistoryPolicy.MAX_CHARS].
 */
object CommandBatch {
    fun execute(
        raw: String,
        placeholdersEnabled: Boolean,
        autoEnter: Boolean,
        targets: List<CommandTarget>,
        recheck: (String) -> Boolean,
        write: (String, ByteArray) -> Boolean
    ): BatchOutcome {
        val prepared = CommandBody.prepare(raw, placeholdersEnabled, autoEnter, targets)
        if (prepared.normalized.isEmpty() || targets.isEmpty()) {
            return BatchOutcome(prepared.normalized, false, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<BatchFailure>()
        val skipped = mutableListOf<BatchSkip>()
        val history = mutableListOf<String>()
        for (send in prepared.targets) {
            val reason = send.skipReason
            val payload = send.bytes
            if (reason != null || payload == null) {
                if (reason != null) skipped += BatchSkip(send.targetId, reason)
                continue
            }
            if (!recheck(send.targetId)) {
                failed += BatchFailure(send.targetId, BatchFailureReason.RecheckFailed)
                continue
            }
            if (!write(send.targetId, payload)) {
                failed += BatchFailure(send.targetId, BatchFailureReason.WriteFailed)
                continue
            }
            succeeded += send.targetId
            if (send.submit) {
                CommandHistoryPolicy.accept(send.expanded ?: "")?.let { history += it }
            }
        }
        val clear = failed.isEmpty() && skipped.isEmpty() && succeeded.size == targets.size
        return BatchOutcome(
            prepared.normalized,
            clear,
            succeeded,
            failed,
            skipped,
            failed.map { it.targetId },
            history
        )
    }
}
