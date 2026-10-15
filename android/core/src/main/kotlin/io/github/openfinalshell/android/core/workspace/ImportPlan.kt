package io.github.openfinalshell.android.core.workspace

enum class ImportCategory {
    Connections,
    Groups,
    Proxies,
    PrivateKeys,
    Forwards,
    Commands,
    HostTrust
}

enum class ImportKind {
    Group,
    Proxy,
    PrivateKey,
    Connection,
    Forward,
    CommandGroup,
    Command,
    HostTrust
}

enum class ConflictAction { Skip, Overwrite, Duplicate }

enum class RowAction { Insert, Overwrite, Skip, Duplicate, Block }

fun RowAction.persists(): Boolean =
    this == RowAction.Insert || this == RowAction.Overwrite || this == RowAction.Duplicate

data class RecordRef(val kind: ImportKind, val id: String)

enum class DropReason {
    DependencyCancelled,
    DanglingReference,
    PrivateKeyFingerprintMismatch,
    NoLongerRequired
}

data class DroppedRecord(
    val ref: RecordRef,
    val reason: DropReason,
    /** Fingerprint mismatch blocks the key and keeps the on-device material. */
    val preserveLocalMaterial: Boolean = false
)

data class ImportGroup(val id: String, val name: String, val parentId: String? = null)
data class ImportProxy(
    val id: String,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    val passwordRef: String? = null
)
data class ImportPrivateKey(
    val id: String,
    val name: String,
    val fingerprint: String,
    val passphraseRef: String? = null
)
data class ImportConnection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val groupId: String? = null,
    val proxyId: String? = null,
    val privateKeyId: String? = null,
    val passwordRef: String? = null,
    val passphraseRef: String? = null,
    val proxyPasswordRef: String? = null
)
data class ImportForward(
    val id: String,
    val profileId: String,
    val type: String = "local",
    val label: String = "",
    val bindAddr: String = "127.0.0.1",
    val bindPort: Int = 0,
    val dstHost: String? = null,
    val dstPort: Int? = null,
    val autoStart: Boolean = false
)
data class ImportCommandGroup(val id: String, val name: String)
data class ImportCommand(val id: String, val name: String, val groupId: String? = null)
data class ImportHostTrust(val key: String, val fingerprint: String)

data class ImportCatalog(
    val groups: List<ImportGroup> = emptyList(),
    val proxies: List<ImportProxy> = emptyList(),
    val privateKeys: List<ImportPrivateKey> = emptyList(),
    val connections: List<ImportConnection> = emptyList(),
    val forwards: List<ImportForward> = emptyList(),
    val commandGroups: List<ImportCommandGroup> = emptyList(),
    val commands: List<ImportCommand> = emptyList(),
    val hostTrust: List<ImportHostTrust> = emptyList(),
    val availableSecrets: Set<String> = emptySet()
)

data class LocalIndex(
    val groups: Set<String> = emptySet(),
    val proxies: Set<String> = emptySet(),
    val privateKeyFingerprints: Map<String, String> = emptyMap(),
    val connections: Set<String> = emptySet(),
    val forwards: Set<String> = emptySet(),
    val commandGroups: Set<String> = emptySet(),
    val commands: Set<String> = emptySet(),
    val hostTrustFingerprints: Map<String, String> = emptyMap()
)

data class PlannedGroup(
    val sourceId: String,
    val targetId: String,
    val action: RowAction,
    val name: String,
    val parentId: String?
)
data class PlannedProxy(
    val sourceId: String,
    val targetId: String,
    val action: RowAction,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val username: String?,
    val passwordRef: String?,
    val pendingSecret: Boolean
)
data class PlannedPrivateKey(
    val sourceId: String,
    val targetId: String,
    val action: RowAction,
    val name: String,
    val fingerprint: String,
    val passphraseRef: String?,
    val pendingSecret: Boolean,
    val bindExistingMaterial: Boolean,
    val materialPending: Boolean
)
data class PlannedConnection(
    val sourceId: String,
    val targetId: String,
    val action: RowAction,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val groupId: String?,
    val proxyId: String?,
    val privateKeyId: String?,
    val passwordRef: String?,
    val passphraseRef: String?,
    val pendingSecrets: List<String>,
    val proxyPasswordRef: String? = null
)
data class PlannedForward(
    val sourceId: String,
    val targetId: String,
    val action: RowAction,
    val profileId: String,
    val type: String,
    val label: String,
    val bindAddr: String,
    val bindPort: Int,
    val dstHost: String?,
    val dstPort: Int?,
    val autoStart: Boolean
)
data class PlannedCommandGroup(
    val sourceId: String,
    val targetId: String,
    val action: RowAction,
    val name: String
)
data class PlannedCommand(
    val sourceId: String,
    val targetId: String,
    val action: RowAction,
    val name: String,
    val groupId: String?
)
data class ImportedSecret(val sourceRef: String, val localRef: String)
data class HostTrustDecision(
    val key: String,
    val incomingFingerprint: String,
    val localFingerprint: String?,
    val action: RowAction,
    val keepLocal: Boolean
)

data class ImportPlanResult(
    val groups: List<PlannedGroup>,
    val proxies: List<PlannedProxy>,
    val privateKeys: List<PlannedPrivateKey>,
    val connections: List<PlannedConnection>,
    val forwards: List<PlannedForward>,
    val commandGroups: List<PlannedCommandGroup>,
    val commands: List<PlannedCommand>,
    /** Source id to the id a later executor writes or the existing row a skip points at. */
    val idMap: Map<RecordRef, String>,
    val actions: Map<RecordRef, RowAction>,
    val skipped: List<RecordRef>,
    val dropped: List<DroppedRecord>,
    /** Secret values the executor may import. Refs not listed here stay untouched. */
    val secrets: List<ImportedSecret>,
    val hostTrust: List<HostTrustDecision>,
    /** Local private-key ids whose Keystore material this plan does not delete. */
    val preserveLocalKeyMaterial: Set<String>
)

/**
 * Category selection, dependency closure, id assignment, and reference rewrite.
 *
 * Every kept record gets its target id before any parentId, groupId, proxyId, privateKeyId,
 * profileId, or command groupId is rewritten. Skip points at the existing id and does not emit a
 * write, so its secrets stay as they are. Duplicate points at a new id. Host trust never receives
 * a random id: the same fingerprint is skipped, a different one is a conflict and the local row
 * stays. A private-key overwrite binds existing material only when the fingerprints match;
 * a mismatch drops that key and every record that needs it, and does not delete local material.
 */
object ImportPlan {
    fun build(
        catalog: ImportCatalog,
        local: LocalIndex,
        categories: Set<ImportCategory>,
        conflict: ConflictAction,
        cancelled: Set<RecordRef> = emptySet(),
        newId: (ImportKind, String) -> String,
        newSecretRef: (String) -> String
    ): ImportPlanResult {
        val index = Index(catalog)
        val explicit = index.explicit(categories)
        val full = index.closure(explicit, emptySet())
        val selected = index.closure(explicit, cancelled)
        val mismatched = selected.filter { index.fingerprintMismatch(it, local, conflict) }.toSet()
        val tainted = selected.filter { index.reaches(it, mismatched) }.toSet()
        val kept = index.closure(explicit, cancelled + tainted)
        val dropped = drops(explicit, full, selected, kept, cancelled, mismatched, tainted)
        val actions = linkedMapOf<RecordRef, RowAction>()
        val idMap = linkedMapOf<RecordRef, String>()
        for (ref in kept.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))) {
            actions[ref] = rowAction(ref, conflict, local)
            idMap[ref] = if (conflict == ConflictAction.Duplicate) newId(ref.kind, ref.id) else ref.id
        }
        val secrets = linkedMapOf<String, String>()
        fun rewrite(source: String?, field: String, pending: MutableList<String>): String? {
            if (source.isNullOrBlank()) return null
            if (source !in catalog.availableSecrets) {
                pending += field
                return null
            }
            return secrets.getOrPut(source) { newSecretRef(source) }
        }
        fun mapped(kind: ImportKind, source: String?): String? {
            if (source == null) return null
            return checkNotNull(idMap[RecordRef(kind, source)]) { "missing id for $kind $source" }
        }
        val groups = index.groups.values.filter { persist(it.id, ImportKind.Group, actions) }.map { row ->
            val ref = RecordRef(ImportKind.Group, row.id)
            PlannedGroup(row.id, idMap.getValue(ref), actions.getValue(ref), row.name, mapped(ImportKind.Group, row.parentId))
        }
        val proxies = index.proxies.values.filter { persist(it.id, ImportKind.Proxy, actions) }.map { row ->
            val pending = mutableListOf<String>()
            val password = rewrite(row.passwordRef, "passwordRef", pending)
            PlannedProxy(
                row.id, idMap.getValue(RecordRef(ImportKind.Proxy, row.id)), actions.getValue(RecordRef(ImportKind.Proxy, row.id)),
                row.name, row.type, row.host, row.port, row.username, password, pending.isNotEmpty()
            )
        }
        val keys = index.privateKeys.values.filter { persist(it.id, ImportKind.PrivateKey, actions) }.map { row ->
            val pending = mutableListOf<String>()
            val passphrase = rewrite(row.passphraseRef, "passphraseRef", pending)
            val localFingerprint = local.privateKeyFingerprints[row.id]
            val bind = actions.getValue(RecordRef(ImportKind.PrivateKey, row.id)) == RowAction.Overwrite &&
                localFingerprint != null && localFingerprint == row.fingerprint
            PlannedPrivateKey(
                row.id, idMap.getValue(RecordRef(ImportKind.PrivateKey, row.id)), actions.getValue(RecordRef(ImportKind.PrivateKey, row.id)),
                row.name, row.fingerprint, passphrase, pending.isNotEmpty(), bind, !bind
            )
        }
        val connections = index.connections.values.filter { persist(it.id, ImportKind.Connection, actions) }.map { row ->
            val pending = mutableListOf<String>()
            PlannedConnection(
                sourceId = row.id,
                targetId = idMap.getValue(RecordRef(ImportKind.Connection, row.id)),
                action = actions.getValue(RecordRef(ImportKind.Connection, row.id)),
                name = row.name,
                host = row.host,
                port = row.port,
                username = row.username,
                groupId = mapped(ImportKind.Group, row.groupId),
                proxyId = mapped(ImportKind.Proxy, row.proxyId),
                privateKeyId = mapped(ImportKind.PrivateKey, row.privateKeyId),
                passwordRef = rewrite(row.passwordRef, "passwordRef", pending),
                passphraseRef = rewrite(row.passphraseRef, "passphraseRef", pending),
                proxyPasswordRef = rewrite(row.proxyPasswordRef, "proxy.passwordRef", pending),
                pendingSecrets = pending
            )
        }
        val forwards = index.forwards.values.filter { persist(it.id, ImportKind.Forward, actions) }.map { row ->
            PlannedForward(
                row.id, idMap.getValue(RecordRef(ImportKind.Forward, row.id)), actions.getValue(RecordRef(ImportKind.Forward, row.id)),
                checkNotNull(mapped(ImportKind.Connection, row.profileId)),
                row.type, row.label, row.bindAddr, row.bindPort, row.dstHost, row.dstPort, row.autoStart
            )
        }
        val commandGroups = index.commandGroups.values.filter { persist(it.id, ImportKind.CommandGroup, actions) }.map { row ->
            PlannedCommandGroup(row.id, idMap.getValue(RecordRef(ImportKind.CommandGroup, row.id)), actions.getValue(RecordRef(ImportKind.CommandGroup, row.id)), row.name)
        }
        val commands = index.commands.values.filter { persist(it.id, ImportKind.Command, actions) }.map { row ->
            PlannedCommand(
                row.id, idMap.getValue(RecordRef(ImportKind.Command, row.id)), actions.getValue(RecordRef(ImportKind.Command, row.id)),
                row.name, mapped(ImportKind.CommandGroup, row.groupId)
            )
        }
        return ImportPlanResult(
            orderGroups(groups),
            proxies.sortedBy { it.sourceId },
            keys.sortedBy { it.sourceId },
            connections.sortedBy { it.sourceId },
            forwards.sortedBy { it.sourceId },
            commandGroups.sortedBy { it.sourceId },
            commands.sortedBy { it.sourceId },
            idMap,
            actions,
            actions.filterValues { it == RowAction.Skip }.keys.sortedWith(compareBy({ it.kind.ordinal }, { it.id })),
            dropped.sortedWith(compareBy({ it.ref.kind.ordinal }, { it.ref.id })),
            secrets.map { ImportedSecret(it.key, it.value) }.sortedBy { it.sourceRef },
            planHostTrust(catalog, local, categories, cancelled),
            local.privateKeyFingerprints.keys
        )
    }

    private fun orderGroups(rows: List<PlannedGroup>): List<PlannedGroup> {
        val byTarget = rows.associateBy { it.targetId }
        val emitted = mutableSetOf<String>()
        val ordered = mutableListOf<PlannedGroup>()
        fun visit(row: PlannedGroup, stack: Set<String>) {
            if (row.targetId in emitted) return
            if (row.targetId in stack) {
                ordered += row
                emitted += row.targetId
                return
            }
            row.parentId?.let { byTarget[it] }?.let { visit(it, stack + row.targetId) }
            if (row.targetId !in emitted) {
                ordered += row
                emitted += row.targetId
            }
        }
        rows.sortedBy { it.sourceId }.forEach { visit(it, emptySet()) }
        return ordered
    }

    private fun persist(id: String, kind: ImportKind, actions: Map<RecordRef, RowAction>): Boolean =
        actions[RecordRef(kind, id)]?.persists() == true

    private fun rowAction(ref: RecordRef, conflict: ConflictAction, local: LocalIndex): RowAction = when (conflict) {
        ConflictAction.Duplicate -> RowAction.Duplicate
        ConflictAction.Skip -> if (localHas(ref, local)) RowAction.Skip else RowAction.Insert
        ConflictAction.Overwrite -> if (localHas(ref, local)) RowAction.Overwrite else RowAction.Insert
    }

    private fun localHas(ref: RecordRef, local: LocalIndex): Boolean = when (ref.kind) {
        ImportKind.Group -> ref.id in local.groups
        ImportKind.Proxy -> ref.id in local.proxies
        ImportKind.PrivateKey -> ref.id in local.privateKeyFingerprints
        ImportKind.Connection -> ref.id in local.connections
        ImportKind.Forward -> ref.id in local.forwards
        ImportKind.CommandGroup -> ref.id in local.commandGroups
        ImportKind.Command -> ref.id in local.commands
        ImportKind.HostTrust -> ref.id in local.hostTrustFingerprints
    }

    private fun planHostTrust(
        catalog: ImportCatalog,
        local: LocalIndex,
        categories: Set<ImportCategory>,
        cancelled: Set<RecordRef>
    ): List<HostTrustDecision> {
        if (ImportCategory.HostTrust !in categories) return emptyList()
        return catalog.hostTrust.mapNotNull { row ->
            if (RecordRef(ImportKind.HostTrust, row.key) in cancelled) return@mapNotNull null
            val localFingerprint = local.hostTrustFingerprints[row.key]
            when {
                localFingerprint == null -> HostTrustDecision(row.key, row.fingerprint, null, RowAction.Insert, false)
                localFingerprint == row.fingerprint ->
                    HostTrustDecision(row.key, row.fingerprint, localFingerprint, RowAction.Skip, true)
                else -> HostTrustDecision(row.key, row.fingerprint, localFingerprint, RowAction.Block, true)
            }
        }.sortedBy { it.key }
    }

    private fun drops(
        explicit: Set<RecordRef>,
        full: Set<RecordRef>,
        selected: Set<RecordRef>,
        kept: Set<RecordRef>,
        cancelled: Set<RecordRef>,
        mismatched: Set<RecordRef>,
        tainted: Set<RecordRef>
    ): List<DroppedRecord> {
        val dropped = mutableListOf<DroppedRecord>()
        for (ref in full) {
            if (ref in kept || ref in cancelled) continue
            val reason = when {
                ref in mismatched || ref in tainted -> DropReason.PrivateKeyFingerprintMismatch
                ref !in selected -> DropReason.DependencyCancelled
                else -> DropReason.NoLongerRequired
            }
            dropped += DroppedRecord(ref, reason, preserveLocalMaterial = ref in mismatched)
        }
        for (ref in explicit) {
            if (ref in full || ref in cancelled || ref in kept) continue
            dropped += DroppedRecord(ref, DropReason.DanglingReference)
        }
        return dropped
    }

    private class Index(catalog: ImportCatalog) {
        val groups = catalog.groups.associateBy { it.id }
        val proxies = catalog.proxies.associateBy { it.id }
        val privateKeys = catalog.privateKeys.associateBy { it.id }
        val connections = catalog.connections.associateBy { it.id }
        val forwards = catalog.forwards.associateBy { it.id }
        val commandGroups = catalog.commandGroups.associateBy { it.id }
        val commands = catalog.commands.associateBy { it.id }

        fun explicit(categories: Set<ImportCategory>): Set<RecordRef> = buildSet {
            if (ImportCategory.Groups in categories) groups.keys.forEach { add(RecordRef(ImportKind.Group, it)) }
            if (ImportCategory.Proxies in categories) proxies.keys.forEach { add(RecordRef(ImportKind.Proxy, it)) }
            if (ImportCategory.PrivateKeys in categories) privateKeys.keys.forEach { add(RecordRef(ImportKind.PrivateKey, it)) }
            if (ImportCategory.Connections in categories) connections.keys.forEach { add(RecordRef(ImportKind.Connection, it)) }
            if (ImportCategory.Forwards in categories) forwards.keys.forEach { add(RecordRef(ImportKind.Forward, it)) }
            if (ImportCategory.Commands in categories) commands.keys.forEach { add(RecordRef(ImportKind.Command, it)) }
        }

        fun contains(ref: RecordRef): Boolean = when (ref.kind) {
            ImportKind.Group -> ref.id in groups
            ImportKind.Proxy -> ref.id in proxies
            ImportKind.PrivateKey -> ref.id in privateKeys
            ImportKind.Connection -> ref.id in connections
            ImportKind.Forward -> ref.id in forwards
            ImportKind.CommandGroup -> ref.id in commandGroups
            ImportKind.Command -> ref.id in commands
            ImportKind.HostTrust -> false
        }

        fun deps(ref: RecordRef): List<RecordRef> = when (ref.kind) {
            ImportKind.Group -> groups[ref.id]?.parentId?.let { listOf(RecordRef(ImportKind.Group, it)) }.orEmpty()
            ImportKind.Connection -> connections[ref.id]?.let { row ->
                listOfNotNull(
                    row.groupId?.let { RecordRef(ImportKind.Group, it) },
                    row.proxyId?.let { RecordRef(ImportKind.Proxy, it) },
                    row.privateKeyId?.let { RecordRef(ImportKind.PrivateKey, it) }
                )
            }.orEmpty()
            ImportKind.Forward -> forwards[ref.id]?.let { listOf(RecordRef(ImportKind.Connection, it.profileId)) }.orEmpty()
            ImportKind.Command -> commands[ref.id]?.groupId?.let { listOf(RecordRef(ImportKind.CommandGroup, it)) }.orEmpty()
            else -> emptyList()
        }

        fun closure(roots: Set<RecordRef>, deny: Set<RecordRef>): Set<RecordRef> {
            val kept = linkedSetOf<RecordRef>()
            fun treeOk(ref: RecordRef, stack: Set<RecordRef>): Boolean {
                if (ref in deny || !contains(ref)) return false
                if (ref in stack) return true
                val next = stack + ref
                return deps(ref).all { treeOk(it, next) }
            }
            fun visit(ref: RecordRef) {
                if (ref in deny || !contains(ref) || ref in kept || !treeOk(ref, emptySet())) return
                kept += ref
                deps(ref).forEach { visit(it) }
            }
            roots.forEach { visit(it) }
            return kept
        }

        fun fingerprintMismatch(ref: RecordRef, local: LocalIndex, conflict: ConflictAction): Boolean {
            if (conflict != ConflictAction.Overwrite || ref.kind != ImportKind.PrivateKey) return false
            val incoming = privateKeys[ref.id]?.fingerprint ?: return false
            val stored = local.privateKeyFingerprints[ref.id] ?: return false
            return stored != incoming
        }

        fun reaches(ref: RecordRef, bad: Set<RecordRef>): Boolean {
            val memo = mutableMapOf<RecordRef, Boolean>()
            val stack = mutableSetOf<RecordRef>()
            fun walk(current: RecordRef): Boolean {
                memo[current]?.let { return it }
                if (current in bad) {
                    memo[current] = true
                    return true
                }
                if (!stack.add(current)) return false
                val answer = deps(current).any { walk(it) }
                stack.remove(current)
                memo[current] = answer
                return answer
            }
            return walk(ref)
        }
    }
}
