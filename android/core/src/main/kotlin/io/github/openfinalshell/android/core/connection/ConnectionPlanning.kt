package io.github.openfinalshell.android.core.connection

import io.github.openfinalshell.android.core.model.ConnectionProfile

/** Eight accent colors shared with the desktop preset list. Connection tag colors stay separate. */
object AccentPalette {
    const val DEFAULT = "#1677FF"
    val presets = listOf(
        "#1677FF",
        "#13C2C2",
        "#52C41A",
        "#FAAD14",
        "#FA8C16",
        "#F5222D",
        "#EB2F96",
        "#722ED1"
    )

    fun canonical(input: String): String? =
        presets.firstOrNull { it.equals(input.trim(), ignoreCase = true) }
}

/**
 * Android stores keepalive and ready-timeout in seconds. Desktop exports use milliseconds in the
 * same JSON fields. Values above the second-based clamps are treated as milliseconds.
 */
object ConnectionTiming {
    fun readyTimeoutMillis(stored: Int): Long {
        val millis = if (stored > 300) stored.toLong() else stored.toLong() * 1_000L
        return millis.coerceIn(5_000L, 120_000L)
    }

    /** Zero disables the session heartbeat. */
    fun keepaliveSeconds(stored: Int): Int {
        if (stored <= 0) return 0
        val seconds = if (stored > 3_600) stored / 1_000 else stored
        return seconds.coerceIn(1, 3_600)
    }
}

data class GroupNode(val id: String, val parentId: String?)

fun descendantIds(groupId: String, groups: List<GroupNode>): Set<String> {
    val children = groups.groupBy { it.parentId }
    val found = mutableSetOf<String>()
    val pending = ArrayDeque(children[groupId].orEmpty().map { it.id })
    while (pending.isNotEmpty()) {
        val next = pending.removeLast()
        if (found.add(next)) children[next].orEmpty().mapTo(pending) { it.id }
    }
    return found
}

/** Parent choices for a group editor. A new group (null id) may use any existing group. */
fun allowedParentIds(editingId: String?, groups: List<GroupNode>): List<String?> {
    val banned = if (editingId == null) emptySet() else descendantIds(editingId, groups) + editingId
    return listOf(null) + groups.map { it.id }.filter { it !in banned }
}

fun isAllowedParent(editingId: String?, parentId: String?, groups: List<GroupNode>): Boolean =
    parentId in allowedParentIds(editingId, groups).toSet() || (parentId == null)

/**
 * Deleting a group moves its direct connections to the ungrouped root and lifts child groups
 * onto the deleted group's parent. A root-level group's children also land on the root.
 * Connections that already live inside a child group stay there.
 */
data class GroupDeletePlan(
    val deletedId: String,
    /** Always null: direct connections leave every group. */
    val connectionGroupId: String? = null,
    /** Parent that child groups are reattached to. Null means the ungrouped root. */
    val parentId: String?,
    val directProfileIds: List<String>,
    val childGroupIds: List<String>
)

fun planGroupDelete(
    deletedId: String,
    groups: List<GroupNode>,
    profileGroupIds: Map<String, String?>
): GroupDeletePlan? {
    val deleted = groups.find { it.id == deletedId } ?: return null
    return GroupDeletePlan(
        deletedId = deletedId,
        connectionGroupId = null,
        parentId = deleted.parentId,
        directProfileIds = profileGroupIds.filterValues { it == deletedId }.keys.toList(),
        childGroupIds = groups.filter { it.parentId == deletedId }.map { it.id }
    )
}

data class QuickConnectTarget(val username: String?, val host: String, val port: Int)

/**
 * Accepts user@host, a bare host, host:port, user@host:port, and `ssh -p PORT USER@HOST`.
 * IPv6 with a port must be written as [address]:port. The input is never executed as a shell.
 * A missing username stays null so the form can collect it before connecting.
 */
fun parseQuickConnect(input: String): QuickConnectTarget? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it == '\n' || it == '\r' || it == '\u0000' || it == ';' || it == '|' || it == '&' || it == '$' || it == '`' || it == '<' || it == '>' }) {
        return null
    }
    if (trimmed.any { it == '"' || it == '\'' || it == '\\' }) return null
    val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    var index = 0
    if (tokens[0].equals("ssh", ignoreCase = true)) index += 1
    var optionPort: Int? = null
    if (index < tokens.size && tokens[index] == "-p") {
        val rawPort = tokens.getOrNull(index + 1) ?: return null
        optionPort = rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        index += 2
    }
    if (index >= tokens.size) return null
    if (tokens[index].startsWith("-")) return null
    if (index != tokens.lastIndex) return null
    return parseDestination(tokens[index], optionPort ?: 22)
}

private fun parseDestination(token: String, defaultPort: Int): QuickConnectTarget? {
    if (token.isEmpty()) return null
    val at = token.indexOf('@')
    val user: String?
    val rest: String
    if (at >= 0) {
        user = token.substring(0, at)
        if (user.isEmpty() || user.contains('@')) return null
        rest = token.substring(at + 1)
    } else {
        user = null
        rest = token
    }
    if (rest.isEmpty()) return null
    if (rest.startsWith("[")) {
        val end = rest.indexOf(']')
        if (end <= 1) return null
        val host = rest.substring(1, end)
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        val after = rest.substring(end + 1)
        val port = when {
            after.isEmpty() -> defaultPort
            after.startsWith(":") -> after.drop(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            else -> return null
        }
        return QuickConnectTarget(user, host, port)
    }
    val colon = rest.lastIndexOf(':')
    if (colon > 0 && !rest.substring(0, colon).contains(':')) {
        val host = rest.substring(0, colon)
        val portText = rest.substring(colon + 1)
        if (host.isEmpty() || portText.isEmpty() || portText.any { !it.isDigit() }) return null
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return QuickConnectTarget(user, host, port)
    }
    if (rest.any { it.isWhitespace() }) return null
    return QuickConnectTarget(user, rest, defaultPort)
}

enum class SshCommandNotice { PRIVATE_KEY, PROXY }

data class SshCommandLine(val command: String, val notices: List<SshCommandNotice>)

/** `ssh -p PORT USER@HOST`, quoted as one destination argument. Passwords are never included. */
fun formatSshCommand(profile: ConnectionProfile): SshCommandLine {
    require(profile.port in 1..65535) { "invalid SSH port" }
    val destination = "${profile.username}@${profile.host}"
    val command = "ssh -p ${profile.port} ${quoteSshArg(destination)}"
    val notices = buildList {
        if (profile.auth.privateKeyId != null) add(SshCommandNotice.PRIVATE_KEY)
        if (profile.proxyId != null || (profile.proxy != null && profile.proxy.type != "none")) add(SshCommandNotice.PROXY)
    }
    return SshCommandLine(command, notices)
}

fun quoteSshArg(argument: String): String {
    require(!argument.contains('\u0000')) { "ssh argument contains NUL" }
    val safe = argument.isNotEmpty() && argument.all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "._~@%+-:/[]"
    } && !argument.startsWith("-")
    if (safe) return argument
    return "'${argument.replace("'", "'\\''")}'"
}

/** Display-only host masking. Search, connect, and copied commands keep the raw host. */
fun maskHost(raw: String): String {
    val host = raw.trim()
    if (host.isEmpty()) return raw
    val bare = host.removePrefix("[").removeSuffix("]")
    val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(bare)
    if (ipv4 != null) return "${ipv4.groupValues[1]}.•.•.${ipv4.groupValues[4]}"
    if (bare.contains(':')) {
        val groups = bare.split(':').filter { it.isNotEmpty() }
        if (groups.size >= 2) return "${groups.first()}:…:${groups.last()}"
        return maskWord(bare)
    }
    val labels = bare.split('.')
    return when {
        labels.size >= 3 -> "${labels.first()}.•••.${labels.last()}"
        labels.size == 2 -> "${maskWord(labels[0])}.${labels[1]}"
        else -> maskWord(labels[0])
    }
}

private fun maskWord(word: String): String {
    if (word.length <= 2) return if (word.isEmpty()) word else "${word[0]}•"
    val keep = minOf(4, (word.length + 1) / 2)
    return word.take(keep) + "•••"
}

fun recentProfiles(profiles: List<ConnectionProfile>, limit: Int = 8): List<ConnectionProfile> =
    profiles.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(limit)

fun profilesUsingProxy(profiles: List<ConnectionProfile>, proxyId: String): List<ConnectionProfile> =
    profiles.filter { it.proxyId == proxyId }

fun profilesUsingPrivateKey(profiles: List<ConnectionProfile>, privateKeyId: String): List<ConnectionProfile> =
    profiles.filter { it.auth.privateKeyId == privateKeyId }
