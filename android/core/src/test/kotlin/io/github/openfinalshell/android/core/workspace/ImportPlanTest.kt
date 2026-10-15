package io.github.openfinalshell.android.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlanTest {
    @Test
    fun duplicateAssignsEveryIdBeforeRewritingReferences() {
        val plan = plan(conflict = ConflictAction.Duplicate, categories = everything)
        val parent = plan.groups.single { it.sourceId == "g-parent" }
        val child = plan.groups.single { it.sourceId == "g-child" }
        assertTrue(plan.groups.indexOf(parent) < plan.groups.indexOf(child))
        assertEquals("Group:g-parent", parent.targetId)
        assertEquals(parent.targetId, child.parentId)
        assertEquals("Group:g-child", plan.connections.single().groupId)
        assertEquals("Proxy:p", plan.connections.single().proxyId)
        assertEquals("PrivateKey:k", plan.connections.single().privateKeyId)
        assertEquals("Connection:c", plan.forwards.single().profileId)
        assertEquals("CommandGroup:cg", plan.commands.single().groupId)
        assertEquals("local:sec-pw", plan.connections.single().passwordRef)
        assertEquals(
            setOf("sec-pass", "sec-passphrase", "sec-proxy", "sec-pw"),
            plan.secrets.map { it.sourceRef }.toSet()
        )
        assertFalse(plan.secrets.any { it.sourceRef == "decoy" })
        assertTrue(plan.commandGroups.none { it.sourceId == "cg-empty" })
        assertTrue(plan.idMap.values.all { it.contains(':') })
    }

    @Test
    fun skipPointsAtExistingRowsAndDoesNotTouchTheirSecrets() {
        val plan = plan(
            conflict = ConflictAction.Skip,
            categories = setOf(ImportCategory.Connections),
            local = LocalIndex(groups = setOf("g-parent"), proxies = setOf("p"), privateKeyFingerprints = mapOf("k" to "fp-file"))
        )
        assertTrue(plan.connections.single().sourceId == "c")
        assertEquals("g-child", plan.connections.single().groupId)
        assertEquals("p", plan.connections.single().proxyId)
        assertEquals("k", plan.connections.single().privateKeyId)
        assertEquals("g-child", plan.groups.single().sourceId)
        assertEquals("g-parent", plan.groups.single().parentId)
        assertTrue(plan.proxies.isEmpty())
        assertTrue(plan.privateKeys.isEmpty())
        assertEquals(setOf("sec-passphrase", "sec-pw"), plan.secrets.map { it.sourceRef }.toSet())
        assertTrue(plan.skipped.contains(RecordRef(ImportKind.Proxy, "p")))
        assertTrue(plan.skipped.contains(RecordRef(ImportKind.PrivateKey, "k")))
    }

    @Test
    fun skippedRecordIsNotRewrittenWhenAnotherRowIsInserted() {
        val catalog = ImportCatalog(
            connections = listOf(
                ImportConnection("old", "keep", "10.0.0.1", 22, "root", passwordRef = "sec-old"),
                ImportConnection("new", "add", "10.0.0.2", 22, "root", passwordRef = "sec-new")
            ),
            availableSecrets = setOf("sec-old", "sec-new", "unused")
        )
        val plan = ImportPlan.build(
            catalog,
            LocalIndex(connections = setOf("old")),
            setOf(ImportCategory.Connections),
            ConflictAction.Skip,
            newId = { kind, id -> "$kind:$id" },
            newSecretRef = { "local:$it" }
        )
        assertTrue(plan.connections.none { it.sourceId == "old" })
        assertEquals("local:sec-new", plan.connections.single().passwordRef)
        assertEquals(listOf("sec-new"), plan.secrets.map { it.sourceRef })
    }

    @Test
    fun overwriteClearsAMissingSecretAndMarksItPending() {
        val catalog = this.catalog.copy(availableSecrets = setOf("sec-pass", "sec-passphrase"))
        val plan = ImportPlan.build(
            catalog,
            LocalIndex(
                groups = setOf("g-parent", "g-child"),
                privateKeyFingerprints = mapOf("k" to "fp-file"),
                connections = setOf("c")
            ),
            setOf(ImportCategory.Connections, ImportCategory.PrivateKeys),
            ConflictAction.Overwrite,
            newId = { _, id -> id },
            newSecretRef = { "local:$it" }
        )
        val connection = plan.connections.single()
        assertEquals(RowAction.Overwrite, connection.action)
        assertNull(connection.passwordRef)
        assertTrue(connection.pendingSecrets.contains("passwordRef"))
        assertEquals("local:sec-passphrase", connection.passphraseRef)
        assertEquals("local:sec-pass", plan.privateKeys.single().passphraseRef)
        assertTrue(plan.privateKeys.single().bindExistingMaterial)
        assertFalse(plan.privateKeys.single().materialPending)
        assertFalse(plan.secrets.any { it.sourceRef == "sec-pw" || it.sourceRef == "sec-proxy" })
    }

    @Test
    fun cancellingAProxyDropsDependentsAndTheirSecrets() {
        val plan = plan(
            conflict = ConflictAction.Overwrite,
            categories = setOf(ImportCategory.Forwards, ImportCategory.PrivateKeys),
            cancelled = setOf(RecordRef(ImportKind.Proxy, "p")),
            local = LocalIndex(privateKeyFingerprints = mapOf("k" to "fp-file"))
        )
        assertTrue(plan.connections.isEmpty())
        assertTrue(plan.forwards.isEmpty())
        assertTrue(plan.proxies.isEmpty())
        assertEquals("k", plan.privateKeys.single().sourceId)
        assertEquals(listOf("sec-pass"), plan.secrets.map { it.sourceRef })
        assertTrue(plan.dropped.any { it.ref == RecordRef(ImportKind.Connection, "c") && it.reason == DropReason.DependencyCancelled })
        assertTrue(plan.dropped.any { it.ref == RecordRef(ImportKind.Forward, "f") })
    }

    @Test
    fun fingerprintMismatchBlocksTheKeyAndDependentsWithoutDeletingMaterial() {
        val plan = plan(
            conflict = ConflictAction.Overwrite,
            categories = everything,
            local = LocalIndex(privateKeyFingerprints = mapOf("k" to "fp-local"))
        )
        assertTrue(plan.privateKeys.isEmpty())
        assertTrue(plan.connections.isEmpty())
        assertTrue(plan.forwards.isEmpty())
        val blocked = plan.dropped.single { it.ref == RecordRef(ImportKind.PrivateKey, "k") }
        assertEquals(DropReason.PrivateKeyFingerprintMismatch, blocked.reason)
        assertTrue(blocked.preserveLocalMaterial)
        assertFalse(plan.dropped.single { it.ref == RecordRef(ImportKind.Connection, "c") }.preserveLocalMaterial)
        assertTrue("k" in plan.preserveLocalKeyMaterial)
        assertFalse(plan.secrets.any { it.sourceRef == "sec-pass" || it.sourceRef == "sec-pw" })
        assertEquals(listOf("sec-proxy"), plan.secrets.map { it.sourceRef })
    }

    @Test
    fun duplicateOfAMismatchedKeyLeavesTheLocalMaterialAlone() {
        val plan = plan(
            conflict = ConflictAction.Duplicate,
            categories = setOf(ImportCategory.Connections),
            local = LocalIndex(privateKeyFingerprints = mapOf("k" to "fp-local"))
        )
        val key = plan.privateKeys.single()
        assertEquals("PrivateKey:k", key.targetId)
        assertFalse(key.bindExistingMaterial)
        assertTrue(key.materialPending)
        assertEquals(key.targetId, plan.connections.single().privateKeyId)
        assertFalse(plan.privateKeys.any { it.targetId == "k" })
        assertTrue("k" in plan.preserveLocalKeyMaterial)
    }

    @Test
    fun sameNameDifferentIdIsNotAConflict() {
        val plan = ImportPlan.build(
            ImportCatalog(
                connections = listOf(
                    ImportConnection("file-1", "same", "10.0.0.1", 22, "root"),
                    ImportConnection("file-2", "same", "10.0.0.2", 22, "root")
                )
            ),
            LocalIndex(connections = setOf("local-9")),
            setOf(ImportCategory.Connections),
            ConflictAction.Skip,
            newId = { _, id -> id },
            newSecretRef = { it }
        )
        assertEquals(setOf("file-1", "file-2"), plan.connections.map { it.targetId }.toSet())
        assertTrue(plan.connections.all { it.action == RowAction.Insert })
    }

    @Test
    fun sharedSecretIsImportedOnceAndOnlyForWrittenRows() {
        val plan = ImportPlan.build(
            ImportCatalog(
                connections = listOf(
                    ImportConnection("a", "a", "h", 22, "root", passwordRef = "sec"),
                    ImportConnection("b", "b", "h", 22, "root", passwordRef = "sec")
                ),
                availableSecrets = setOf("sec")
            ),
            LocalIndex(),
            setOf(ImportCategory.Connections),
            ConflictAction.Duplicate,
            newId = { kind, id -> "$kind:$id" },
            newSecretRef = { "local:$it" }
        )
        assertEquals(listOf(ImportedSecret("sec", "local:sec")), plan.secrets)
        assertTrue(plan.connections.all { it.passwordRef == "local:sec" && it.targetId != it.sourceId })
    }

    @Test
    fun danglingAndCancelledCommandGroupsAreNotWritten() {
        val dangling = ImportPlan.build(
            ImportCatalog(commands = listOf(ImportCommand("m", "df", "missing"))),
            LocalIndex(),
            setOf(ImportCategory.Commands),
            ConflictAction.Overwrite,
            newId = { _, id -> id },
            newSecretRef = { it }
        )
        assertTrue(dangling.commands.isEmpty())
        assertEquals(DropReason.DanglingReference, dangling.dropped.single().reason)

        val cancelled = ImportPlan.build(
            ImportCatalog(
                commandGroups = listOf(ImportCommandGroup("cg", "tools")),
                commands = listOf(ImportCommand("m", "df", "cg"))
            ),
            LocalIndex(),
            setOf(ImportCategory.Commands),
            ConflictAction.Overwrite,
            cancelled = setOf(RecordRef(ImportKind.CommandGroup, "cg")),
            newId = { _, id -> id },
            newSecretRef = { it }
        )
        assertTrue(cancelled.commands.isEmpty())
        assertTrue(cancelled.commandGroups.isEmpty())
        assertEquals(DropReason.DependencyCancelled, cancelled.dropped.single { it.ref.id == "m" }.reason)
    }

    @Test
    fun hostTrustNeverCopiesARandomIdAndDoesNotFollowOtherCategories() {
        val hidden = plan(conflict = ConflictAction.Duplicate, categories = setOf(ImportCategory.Connections))
        assertTrue(hidden.hostTrust.isEmpty())

        val shown = plan(
            conflict = ConflictAction.Overwrite,
            categories = setOf(ImportCategory.HostTrust),
            local = LocalIndex(hostTrustFingerprints = mapOf("h-same" to "fp-same", "h-clash" to "fp-local"))
        )
        assertEquals(RowAction.Insert, shown.hostTrust.single { it.key == "h-new" }.action)
        val same = shown.hostTrust.single { it.key == "h-same" }
        assertEquals(RowAction.Skip, same.action)
        assertTrue(same.keepLocal)
        val clash = shown.hostTrust.single { it.key == "h-clash" }
        assertEquals(RowAction.Block, clash.action)
        assertTrue(clash.keepLocal)
        assertEquals("h-clash", clash.key)
        assertTrue(shown.hostTrust.none { it.action.persists() && it.key == "h-clash" })

        val duplicated = plan(
            conflict = ConflictAction.Duplicate,
            categories = setOf(ImportCategory.HostTrust),
            local = LocalIndex(hostTrustFingerprints = mapOf("h-clash" to "fp-local"))
        )
        assertEquals(RowAction.Block, duplicated.hostTrust.single { it.key == "h-clash" }.action)
        assertTrue(duplicated.hostTrust.none { it.key.contains(':') })
    }

    @Test
    fun newKeyStaysPendingUntilAFileIsChosen() {
        val plan = ImportPlan.build(
            ImportCatalog(privateKeys = listOf(ImportPrivateKey("new-key", "laptop", "fp"))),
            LocalIndex(),
            setOf(ImportCategory.PrivateKeys),
            ConflictAction.Overwrite,
            newId = { _, id -> id },
            newSecretRef = { it }
        )
        val key = plan.privateKeys.single()
        assertEquals(RowAction.Insert, key.action)
        assertFalse(key.bindExistingMaterial)
        assertTrue(key.materialPending)
    }

    private val everything = setOf(
        ImportCategory.Connections,
        ImportCategory.Groups,
        ImportCategory.Proxies,
        ImportCategory.PrivateKeys,
        ImportCategory.Forwards,
        ImportCategory.Commands,
        ImportCategory.HostTrust
    )

    private val catalog = ImportCatalog(
        groups = listOf(
            ImportGroup("g-child", "Child", "g-parent"),
            ImportGroup("g-parent", "Parent", null)
        ),
        proxies = listOf(ImportProxy("p", "proxy", "socks", "127.0.0.1", 1080, passwordRef = "sec-proxy")),
        privateKeys = listOf(ImportPrivateKey("k", "key", "fp-file", "sec-pass")),
        connections = listOf(
            ImportConnection("c", "box", "10.1.1.1", 22, "root", "g-child", "p", "k", "sec-pw", "sec-passphrase")
        ),
        forwards = listOf(ImportForward("f", "c", label = "db")),
        commandGroups = listOf(ImportCommandGroup("cg", "tools"), ImportCommandGroup("cg-empty", "unused")),
        commands = listOf(ImportCommand("m", "df", "cg")),
        hostTrust = listOf(
            ImportHostTrust("h-new", "fp-new"),
            ImportHostTrust("h-same", "fp-same"),
            ImportHostTrust("h-clash", "fp-file")
        ),
        availableSecrets = setOf("sec-proxy", "sec-pass", "sec-pw", "sec-passphrase", "decoy")
    )

    private fun plan(
        conflict: ConflictAction,
        categories: Set<ImportCategory>,
        local: LocalIndex = LocalIndex(),
        cancelled: Set<RecordRef> = emptySet()
    ) = ImportPlan.build(
        catalog,
        local,
        categories,
        conflict,
        cancelled,
        newId = { kind, id ->
            check(kind != ImportKind.HostTrust)
            "$kind:$id"
        },
        newSecretRef = { "local:$it" }
    )
}
