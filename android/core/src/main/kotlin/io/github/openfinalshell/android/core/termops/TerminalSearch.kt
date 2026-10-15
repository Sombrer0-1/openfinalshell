package io.github.openfinalshell.android.core.termops

import java.util.regex.PatternSyntaxException

enum class TerminalSearchDirection { NEXT, PREVIOUS }

/** Column range is a half-open interval of Kotlin string indices (UTF-16 code units). */
data class TerminalMatch(val line: Int, val startColumn: Int, val endColumn: Int)

sealed class TerminalSearchResult {
    data class Found(
        val matches: List<TerminalMatch>,
        val activeIndex: Int,
        val wrapped: Boolean
    ) : TerminalSearchResult() {
        val active: TerminalMatch get() = matches[activeIndex]
    }

    data object None : TerminalSearchResult()
    data class InvalidRegex(val message: String) : TerminalSearchResult()
}

/**
 * Searches a full line array, scrollback included. An illegal regular expression is a result,
 * not an exception for the caller.
 */
object TerminalSearch {
    fun findNext(
        lines: List<String>,
        query: String,
        caseSensitive: Boolean,
        regex: Boolean,
        fromLine: Int,
        fromColumn: Int
    ): TerminalSearchResult = find(lines, query, caseSensitive, regex, fromLine, fromColumn, TerminalSearchDirection.NEXT)

    fun findPrevious(
        lines: List<String>,
        query: String,
        caseSensitive: Boolean,
        regex: Boolean,
        fromLine: Int,
        fromColumn: Int
    ): TerminalSearchResult = find(lines, query, caseSensitive, regex, fromLine, fromColumn, TerminalSearchDirection.PREVIOUS)

    private fun find(
        lines: List<String>,
        query: String,
        caseSensitive: Boolean,
        regex: Boolean,
        fromLine: Int,
        fromColumn: Int,
        direction: TerminalSearchDirection
    ): TerminalSearchResult {
        if (query.isEmpty()) return TerminalSearchResult.None
        val pattern = if (regex) {
            try {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (error: PatternSyntaxException) {
                return TerminalSearchResult.InvalidRegex(error.message ?: "invalid regex")
            }
        } else null
        val matches = ArrayList<TerminalMatch>()
        for ((lineIndex, line) in lines.withIndex()) {
            if (pattern != null) collectRegex(lineIndex, line, pattern, matches)
            else collectLiteral(lineIndex, line, query, caseSensitive, matches)
        }
        if (matches.isEmpty()) return TerminalSearchResult.None
        val (activeIndex, wrapped) = select(matches, fromLine, fromColumn, direction)
        return TerminalSearchResult.Found(matches, activeIndex, wrapped)
    }

    private fun collectLiteral(
        lineIndex: Int,
        line: String,
        query: String,
        caseSensitive: Boolean,
        matches: MutableList<TerminalMatch>
    ) {
        var from = 0
        while (from <= line.length) {
            val index = line.indexOf(query, from, ignoreCase = !caseSensitive)
            if (index < 0) return
            matches += TerminalMatch(lineIndex, index, index + query.length)
            from = index + query.length.coerceAtLeast(1)
        }
    }

    private fun collectRegex(lineIndex: Int, line: String, pattern: Regex, matches: MutableList<TerminalMatch>) {
        var from = 0
        while (from <= line.length) {
            val found = pattern.find(line, from) ?: return
            if (found.range.isEmpty()) {
                from += 1
                continue
            }
            matches += TerminalMatch(lineIndex, found.range.first, found.range.last + 1)
            from = found.range.last + 1
        }
    }

    private fun select(
        matches: List<TerminalMatch>,
        fromLine: Int,
        fromColumn: Int,
        direction: TerminalSearchDirection
    ): Pair<Int, Boolean> {
        if (direction == TerminalSearchDirection.NEXT) {
            val index = matches.indexOfFirst { it.line > fromLine || (it.line == fromLine && it.startColumn > fromColumn) }
            if (index >= 0) return index to false
            return 0 to true
        }
        val index = matches.indexOfLast { it.line < fromLine || (it.line == fromLine && it.startColumn < fromColumn) }
        if (index >= 0) return index to false
        return matches.lastIndex to true
    }
}
