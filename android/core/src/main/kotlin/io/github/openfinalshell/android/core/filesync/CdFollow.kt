package io.github.openfinalshell.android.core.filesync

/**
 * Static `cd` follow. Anything the shell would have to expand (`cd -`, `$VAR`, backticks, `~user`)
 * stays put: a wrong jump is worse than no jump.
 */
object CdFollow {
    private val separator = Regex("""^(?:&&|\|\||[;|&])""")

    /** Target of a `cd`, or null when it cannot be known. A bare `cd` is `~`. */
    fun parseCdTarget(command: String): String? {
        val first = firstSegment(command).trim()
        if (first != "cd" && !Regex("""^cd\s""").containsMatchIn(first)) return null
        val tokens = splitTokens(first.substring(2))
        var index = 0
        while (index < tokens.size && (tokens[index] == "-P" || tokens[index] == "-L" || tokens[index] == "--")) {
            index++
        }
        val rest = tokens.subList(index, tokens.size)
        if (rest.isEmpty()) return "~"
        val target = rest[0]
        if (target == "-") return null
        if (target.any { it == '$' || it == '`' }) return null
        return target
    }

    /** Absolute POSIX path after applying [target], or null when there is no safe base. */
    fun applyCd(cwd: String, target: String, home: String?): String? {
        if (target.isEmpty()) return null
        if (target == "~") return home?.let(::normalizePosix)
        if (target.startsWith("~/")) {
            return home?.let { normalizePosix("$it/${target.substring(2)}") }
        }
        if (target.startsWith("~")) return null
        if (target.startsWith("/")) return normalizePosix(target)
        if (!cwd.startsWith("/")) return null
        return normalizePosix("$cwd/$target")
    }

    /** Collapse `.` / `..` (root stays root) and repeated or trailing slashes. */
    fun normalizePosix(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") {
                if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                continue
            }
            out.add(part)
        }
        return "/" + out.joinToString("/")
    }

    /**
     * What the path bar shows while a navigation is in flight.
     * Refreshing the directory that is already open is not a pending navigation.
     */
    fun navView(cwd: String, pendingDir: String?): NavView = NavView(
        displayDir = pendingDir ?: cwd,
        navPending = pendingDir != null && pendingDir != cwd,
    )

    private fun firstSegment(command: String): String {
        var quote: Char? = null
        var index = 0
        while (index < command.length) {
            val ch = command[index]
            if (quote != null) {
                if (ch == quote && !(quote == '"' && command.getOrNull(index - 1) == '\\')) quote = null
                index++
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                index++
                continue
            }
            if (ch == '\\') {
                index += 2
                continue
            }
            if (separator.containsMatchIn(command.substring(index))) return command.substring(0, index)
            index++
        }
        return command
    }

    private fun splitTokens(source: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        fun push() {
            if (inToken) tokens.add(current.toString())
            current.setLength(0)
            inToken = false
        }
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            if (quote != null) {
                if (ch == quote) quote = null else current.append(ch)
                index++
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                inToken = true
                index++
                continue
            }
            if (ch == '\\' && index + 1 < source.length) {
                current.append(source[index + 1])
                inToken = true
                index += 2
                continue
            }
            if (ch == ' ' || ch == '\t') {
                push()
                index++
                continue
            }
            current.append(ch)
            inToken = true
            index++
        }
        push()
        return tokens
    }
}

data class NavView(val displayDir: String, val navPending: Boolean)
