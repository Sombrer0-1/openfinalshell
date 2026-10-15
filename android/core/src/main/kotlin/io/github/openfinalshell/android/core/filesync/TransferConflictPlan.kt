package io.github.openfinalshell.android.core.filesync

enum class NodeKind { FILE, DIRECTORY }

enum class ConflictPolicy { ASK, OVERWRITE, SKIP, RENAME }

data class TransferNode(val name: String, val kind: NodeKind)

/** One answer for one conflict. [applyToRest] sticks only for later conflicts in this call. */
data class BatchChoice(val policy: ConflictPolicy, val applyToRest: Boolean)

/**
 * Upload and download share this plan. A file is never overwritten onto a directory, or the reverse.
 * Rename uses an unoccupied `name (2).ext`.
 */
object TransferConflictPlan {
    sealed interface Resolution {
        data class Use(val name: String) : Resolution
        data class Overwrite(val name: String) : Resolution
        data object Skip : Resolution
        data class Rename(val name: String) : Resolution
        data class Ask(val name: String, val source: NodeKind, val existing: NodeKind) : Resolution
        data class TypeConflict(val name: String, val source: NodeKind, val existing: NodeKind) : Resolution
    }

    fun plan(
        incoming: List<TransferNode>,
        existing: List<TransferNode>,
        choices: List<BatchChoice> = emptyList(),
    ): List<Resolution> {
        val occupied = LinkedHashMap<String, NodeKind>()
        for (node in existing) occupied[leaf(node.name)] = node.kind
        val out = ArrayList<Resolution>(incoming.size)
        var choiceIndex = 0
        var sticky: ConflictPolicy? = null
        for (item in incoming) {
            val name = leaf(item.name)
            val existingKind = occupied[name]
            if (existingKind == null) {
                occupied[name] = item.kind
                out.add(Resolution.Use(name))
                continue
            }
            val policy = sticky ?: choices.getOrNull(choiceIndex)?.let { choice ->
                choiceIndex++
                if (choice.applyToRest && choice.policy != ConflictPolicy.ASK) sticky = choice.policy
                choice.policy
            } ?: ConflictPolicy.ASK
            out.add(resolve(name, item.kind, existingKind, policy, occupied))
        }
        return out
    }

    /** `name (2).ext`. A leading dot is not an extension (`.bashrc` → `.bashrc (2)`). */
    fun dedupeName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (index in 2 until 1000) {
            val candidate = "$stem ($index)$ext"
            if (!exists(candidate)) return candidate
        }
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    private fun resolve(
        name: String,
        source: NodeKind,
        existing: NodeKind,
        policy: ConflictPolicy,
        occupied: MutableMap<String, NodeKind>,
    ): Resolution {
        val crossed = source != existing
        return when (policy) {
            ConflictPolicy.ASK -> if (crossed) {
                Resolution.TypeConflict(name, source, existing)
            } else {
                Resolution.Ask(name, source, existing)
            }
            ConflictPolicy.OVERWRITE -> if (crossed) {
                Resolution.TypeConflict(name, source, existing)
            } else {
                Resolution.Overwrite(name)
            }
            ConflictPolicy.SKIP -> Resolution.Skip
            ConflictPolicy.RENAME -> {
                val renamed = dedupeName(name) { occupied.containsKey(it) }
                occupied[renamed] = source
                Resolution.Rename(renamed)
            }
        }
    }

    private fun leaf(name: String): String {
        val trimmed = name.trimEnd('/')
        val slash = trimmed.lastIndexOf('/')
        return if (slash < 0) trimmed else trimmed.substring(slash + 1)
    }
}
