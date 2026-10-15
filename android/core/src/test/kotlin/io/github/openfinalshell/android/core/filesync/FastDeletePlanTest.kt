package io.github.openfinalshell.android.core.filesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FastDeletePlanTest {
    @Test fun commandQuotesSpacesQuotesAndLiteralWildcards() {
        val entries = listOf(
            ListedEntry("/data/my dir", ListedKind.DIRECTORY),
            ListedEntry("/data/it's", ListedKind.DIRECTORY),
            ListedEntry("/data/*.log", ListedKind.DIRECTORY),
        )
        val command = "rm -rf -- '/data/my dir' '/data/it'\\''s' '/data/*.log'"
        val planned = FastDeletePlan.plan(entries)
        assertTrue(planned is FastDeletePlan.Result.Ready)
        assertEquals(command, (planned as FastDeletePlan.Result.Ready).command)
        assertEquals(command, FastDeletePlan.executeCommand(entries))
    }

    @Test fun rejectsShallowDotAndControlPathsForPreviewAndExecute() {
        for (path in listOf("/", "/etc", "//", "/etc/")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                FastDeletePlan.plan(listOf(ListedEntry(path, ListedKind.DIRECTORY)))
            }
            assertEquals("too-shallow", error.message)
            assertEquals(
                error.message,
                assertThrows(IllegalArgumentException::class.java) {
                    FastDeletePlan.executeCommand(listOf(ListedEntry(path, ListedKind.DIRECTORY)))
                }.message,
            )
        }
        assertEquals(
            "dot-segment",
            assertThrows(IllegalArgumentException::class.java) {
                FastDeletePlan.plan(listOf(ListedEntry("/data/../etc", ListedKind.DIRECTORY)))
            }.message,
        )
        assertEquals(
            "dot-segment",
            assertThrows(IllegalArgumentException::class.java) {
                FastDeletePlan.plan(listOf(ListedEntry("/data/./x", ListedKind.DIRECTORY)))
            }.message,
        )
        assertEquals(
            "control",
            assertThrows(IllegalArgumentException::class.java) {
                FastDeletePlan.plan(listOf(ListedEntry("/data/a\nb", ListedKind.DIRECTORY)))
            }.message,
        )
        assertEquals(
            "control",
            assertThrows(IllegalArgumentException::class.java) {
                FastDeletePlan.plan(listOf(ListedEntry("/data/a\u0000b", ListedKind.DIRECTORY)))
            }.message,
        )
        assertEquals(
            "not-absolute",
            assertThrows(IllegalArgumentException::class.java) {
                FastDeletePlan.plan(listOf(ListedEntry("data/foo", ListedKind.DIRECTORY)))
            }.message,
        )
    }

    @Test fun acceptsADottedNameAndKeepsAReplacementCharacterWhenTheFlagIsClear() {
        val dotted = FastDeletePlan.plan(listOf(ListedEntry("/data/foo.bar", ListedKind.DIRECTORY)))
        assertEquals("rm -rf -- '/data/foo.bar'", (dotted as FastDeletePlan.Result.Ready).command)
        val literal = FastDeletePlan.plan(listOf(ListedEntry("/data/a\uFFFDb", ListedKind.DIRECTORY)))
        assertEquals("rm -rf -- '/data/a\uFFFDb'", (literal as FastDeletePlan.Result.Ready).command)
    }

    @Test fun mixedSelectionOrAnInvalidNameMakesThePlanUnavailable() {
        val mixed = listOf(
            ListedEntry("/data/dir", ListedKind.DIRECTORY),
            ListedEntry("/data/file", ListedKind.FILE),
        )
        assertEquals(FastDeletePlan.Result.Unavailable, FastDeletePlan.plan(mixed))
        assertEquals(
            FastDeletePlan.Result.Unavailable,
            FastDeletePlan.plan(listOf(ListedEntry("/data/file", ListedKind.FILE))),
        )
        assertEquals(
            FastDeletePlan.Result.Unavailable,
            FastDeletePlan.plan(listOf(ListedEntry("/data/ok", ListedKind.DIRECTORY, nameInvalid = true))),
        )
        assertEquals(FastDeletePlan.Result.Unavailable, FastDeletePlan.plan(emptyList()))
        assertThrows(IllegalStateException::class.java) { FastDeletePlan.executeCommand(mixed) }
    }
}
