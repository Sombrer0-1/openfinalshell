package io.github.openfinalshell.android.core.filesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellQuoteTest {
    @Test fun quotesLiterallyAndEscapesOnlySingleQuotes() {
        assertEquals("''", shQuote(""))
        assertEquals("'/data/foo'", shQuote("/data/foo"))
        assertEquals("""'it'\''s'""", shQuote("it's"))
        assertEquals("""''\'''""", shQuote("'"))
        assertEquals("""'a\b'""", shQuote("a\\b"))
        assertEquals("""'a\'\''b'""", shQuote("a\\'b"))
        assertEquals("'$(id)'", shQuote("$(id)"))
        assertEquals("'`id`'", shQuote("`id`"))
        assertEquals("'*'", shQuote("*"))
        assertEquals("'a*b?c[d]'", shQuote("a*b?c[d]"))
        assertEquals("'a b'", shQuote("a b"))
        assertEquals("'a;rm -rf /'", shQuote("a;rm -rf /"))
    }

    @Test fun nulIsRejectedAndNewlineIsNot() {
        val error = assertThrows(IllegalArgumentException::class.java) { shQuote("a\u0000b") }
        assertEquals("NUL byte in shell argument", error.message)
        assertEquals("'/data/a\nb'", shQuote("/data/a\nb"))
    }
}
