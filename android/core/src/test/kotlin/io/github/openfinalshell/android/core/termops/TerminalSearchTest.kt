package io.github.openfinalshell.android.core.termops

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class TerminalSearchTest {
    private val lines = listOf(
        "scrollback needle here",
        "middle",
        "screen needle"
    )

    @Test
    fun `next and previous walk matches including scrollback and wrap`() {
        val first = TerminalSearch.findNext(lines, "needle", caseSensitive = false, regex = false, fromLine = 0, fromColumn = -1)
        val found = assertIs<TerminalSearchResult.Found>(first)
        assertEquals(0, found.active.line)
        assertEquals(11, found.active.startColumn)
        assertEquals(17, found.active.endColumn)
        assertFalse(found.wrapped)
        assertEquals(2, found.matches.size)

        val second = TerminalSearch.findNext(lines, "needle", caseSensitive = false, regex = false, fromLine = 0, fromColumn = 11)
        val next = assertIs<TerminalSearchResult.Found>(second)
        assertEquals(2, next.active.line)
        assertFalse(next.wrapped)

        val back = assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findPrevious(lines, "needle", caseSensitive = false, regex = false, fromLine = 2, fromColumn = 0)
        )
        assertEquals(0, back.active.line)
        assertFalse(back.wrapped)

        val wrapped = assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findNext(lines, "needle", caseSensitive = false, regex = false, fromLine = 2, fromColumn = 100)
        )
        assertEquals(0, wrapped.active.line)
        assertTrue(wrapped.wrapped)
    }

    @Test
    fun `case sensitivity and literal versus regex stay distinct`() {
        val lines = listOf("Error: a.b not axb")
        assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findNext(lines, "error", caseSensitive = false, regex = false, fromLine = 0, fromColumn = -1)
        )
        assertIs<TerminalSearchResult.None>(
            TerminalSearch.findNext(lines, "error", caseSensitive = true, regex = false, fromLine = 0, fromColumn = -1)
        )
        val literal = assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findNext(lines, "a.b", caseSensitive = true, regex = false, fromLine = 0, fromColumn = -1)
        )
        assertEquals(7, literal.active.startColumn)
        assertEquals(1, literal.matches.size)
        val wrappedLiteral = assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findNext(lines, "a.b", caseSensitive = true, regex = false, fromLine = 0, fromColumn = 20)
        )
        assertEquals(7, wrappedLiteral.active.startColumn)
        assertTrue(wrappedLiteral.wrapped)
        val regex = assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findNext(lines, "a.b", caseSensitive = true, regex = true, fromLine = 0, fromColumn = -1)
        )
        assertEquals(7, regex.matches[0].startColumn)
        assertEquals(15, regex.matches[1].startColumn)
    }

    @Test
    fun `an illegal regular expression is a result`() {
        val result = TerminalSearch.findNext(listOf("abc"), "(", caseSensitive = false, regex = true, fromLine = 0, fromColumn = -1)
        val invalid = assertIs<TerminalSearchResult.InvalidRegex>(result)
        assertTrue(invalid.message.isNotBlank())
        assertIs<TerminalSearchResult.None>(
            TerminalSearch.findPrevious(listOf("abc"), "", caseSensitive = false, regex = true, fromLine = 0, fromColumn = -1)
        )
    }

    @Test
    fun `several matches on one line move by column`() {
        val line = listOf("aaa aaa")
        val first = assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findNext(line, "aaa", caseSensitive = true, regex = false, fromLine = 0, fromColumn = -1)
        )
        assertEquals(0, first.active.startColumn)
        assertEquals(3, first.active.endColumn)
        val second = assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findNext(line, "aaa", caseSensitive = true, regex = false, fromLine = 0, fromColumn = 0)
        )
        assertEquals(4, second.active.startColumn)
        val previous = assertIs<TerminalSearchResult.Found>(
            TerminalSearch.findPrevious(line, "aaa", caseSensitive = true, regex = false, fromLine = 0, fromColumn = 4)
        )
        assertEquals(0, previous.active.startColumn)
    }
}
