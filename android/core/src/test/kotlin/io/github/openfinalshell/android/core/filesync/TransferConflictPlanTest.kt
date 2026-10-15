package io.github.openfinalshell.android.core.filesync

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferConflictPlanTest {
    private fun file(name: String) = TransferNode(name, NodeKind.FILE)
    private fun dir(name: String) = TransferNode(name, NodeKind.DIRECTORY)

    @Test fun dedupeInsertsACountBeforeTheLastExtension() {
        val taken = mutableSetOf("a.txt", "a (2).txt")
        assertEquals("a (3).txt", TransferConflictPlan.dedupeName("a.txt") { taken.contains(it) })
        assertEquals("b.txt", TransferConflictPlan.dedupeName("b.txt") { taken.contains(it) })
        assertEquals("README (2)", TransferConflictPlan.dedupeName("README") { setOf("README").contains(it) })
        assertEquals("a.tar (2).gz", TransferConflictPlan.dedupeName("a.tar.gz") { setOf("a.tar.gz").contains(it) })
        assertEquals(".bashrc (2)", TransferConflictPlan.dedupeName(".bashrc") { setOf(".bashrc").contains(it) })
    }

    @Test fun applyToRestStaysInsideOneBatchAndRenameAvoidsTakenNames() {
        val existing = listOf(file("a.txt"), file("c.txt"), file("a (2).txt"))
        val incoming = listOf(file("a.txt"), file("b.txt"), file("c.txt"), file("d.txt"))
        val once = TransferConflictPlan.plan(
            incoming,
            existing,
            listOf(BatchChoice(ConflictPolicy.RENAME, applyToRest = false)),
        )
        assertEquals(TransferConflictPlan.Resolution.Rename("a (3).txt"), once[0])
        assertEquals(TransferConflictPlan.Resolution.Use("b.txt"), once[1])
        assertEquals(TransferConflictPlan.Resolution.Ask("c.txt", NodeKind.FILE, NodeKind.FILE), once[2])
        val rest = TransferConflictPlan.plan(
            incoming,
            existing,
            listOf(BatchChoice(ConflictPolicy.SKIP, applyToRest = true)),
        )
        assertEquals(TransferConflictPlan.Resolution.Skip, rest[0])
        assertEquals(TransferConflictPlan.Resolution.Use("b.txt"), rest[1])
        assertEquals(TransferConflictPlan.Resolution.Skip, rest[2])
        val nextBatch = TransferConflictPlan.plan(incoming, existing)
        assertEquals(TransferConflictPlan.Resolution.Ask("a.txt", NodeKind.FILE, NodeKind.FILE), nextBatch[0])
        assertEquals(rest, TransferConflictPlan.plan(incoming, existing, listOf(BatchChoice(ConflictPolicy.SKIP, true))))
    }

    @Test fun uploadAndDownloadSharePolicyAndATypeClashCannotOverwrite() {
        val incoming = listOf(file("logs"), dir("src"))
        val existing = listOf(dir("logs"), dir("src"))
        val choices = listOf(BatchChoice(ConflictPolicy.OVERWRITE, applyToRest = true))
        val upload = TransferConflictPlan.plan(incoming, existing, choices)
        val download = TransferConflictPlan.plan(incoming, existing, choices)
        assertEquals(upload, download)
        assertEquals(TransferConflictPlan.Resolution.TypeConflict("logs", NodeKind.FILE, NodeKind.DIRECTORY), upload[0])
        assertEquals(TransferConflictPlan.Resolution.Overwrite("src"), upload[1])
        val renamed = TransferConflictPlan.plan(
            listOf(file("logs")),
            listOf(dir("logs")),
            listOf(BatchChoice(ConflictPolicy.RENAME, false)),
        )
        assertEquals(TransferConflictPlan.Resolution.Rename("logs (2)"), renamed.single())
    }
}
