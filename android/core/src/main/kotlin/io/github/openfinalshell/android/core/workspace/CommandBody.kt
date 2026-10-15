package io.github.openfinalshell.android.core.workspace

data class CommandTarget(
    val id: String,
    val host: String,
    val user: String,
    val port: Int,
    val bracketedPaste: Boolean
)

enum class CommandSkipReason(val message: String) {
    MultilineInsertNeedsBracketedPaste("该目标未启用 bracketed paste，无法仅插入多行")
}

data class TargetSend(
    val targetId: String,
    /** Placeholder-expanded body, without the trailing submit byte. */
    val expanded: String?,
    /** Characters to write. Null means this target is not sent. */
    val text: String?,
    val submit: Boolean,
    val skipReason: CommandSkipReason?
) {
    val bytes: ByteArray? get() = text?.toByteArray(Charsets.UTF_8)
}

data class PreparedCommand(
    /** Shared by send and by saving the shortcut. Placeholders are still in this text. */
    val normalized: String,
    val targets: List<TargetSend>
)

/**
 * Command editor text, before any session is written.
 *
 * Line endings become LF and trailing empty lines are removed; blank lines in the middle stay.
 * `{{host}}`, `{{user}}` and `{{port}}` allow whitespace inside the braces. With the placeholder
 * switch off, the normalized text is sent unchanged. Auto-enter only appends a trailing CR.
 * A multi-line body with auto-enter off is inserted only when that target has bracketed paste.
 */
object CommandBody {
    private val lineEndings = Regex("\r\n?")
    private val hostToken = Regex("\\{\\{\\s*host\\s*\\}\\}")
    private val userToken = Regex("\\{\\{\\s*user\\s*\\}\\}")
    private val portToken = Regex("\\{\\{\\s*port\\s*\\}\\}")

    fun normalize(raw: String): String = raw.replace(lineEndings, "\n").trimEnd('\n')

    fun expand(normalized: String, target: CommandTarget, placeholdersEnabled: Boolean): String {
        if (!placeholdersEnabled) return normalized
        return normalized
            .replace(hostToken) { target.host }
            .replace(userToken) { target.user }
            .replace(portToken) { target.port.toString() }
    }

    fun prepare(
        raw: String,
        placeholdersEnabled: Boolean,
        autoEnter: Boolean,
        targets: List<CommandTarget>
    ): PreparedCommand {
        val normalized = normalize(raw)
        val multiline = '\n' in normalized
        val sends = targets.map { target ->
            val expanded = expand(normalized, target, placeholdersEnabled)
            if (normalized.isEmpty()) {
                TargetSend(target.id, expanded, null, false, null)
            } else if (!autoEnter && multiline && !target.bracketedPaste) {
                TargetSend(target.id, expanded, null, false, CommandSkipReason.MultilineInsertNeedsBracketedPaste)
            } else {
                val text = if (autoEnter) expanded + "\r" else expanded
                TargetSend(target.id, expanded, text, autoEnter, null)
            }
        }
        return PreparedCommand(normalized, sends)
    }
}
