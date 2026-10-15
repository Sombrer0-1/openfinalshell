package io.github.openfinalshell.android.storage

import io.github.openfinalshell.android.core.model.ConnectionProfile
import io.github.openfinalshell.android.core.model.ForwardRule
import io.github.openfinalshell.android.core.model.LOCAL_SHELL_PROTOCOL
import io.github.openfinalshell.android.core.protocol.ProtocolJson
import io.github.openfinalshell.android.core.workspace.ConflictAction
import io.github.openfinalshell.android.core.workspace.DropReason
import io.github.openfinalshell.android.core.workspace.HostTrustDecision
import io.github.openfinalshell.android.core.workspace.ImportCatalog
import io.github.openfinalshell.android.core.workspace.ImportCategory
import io.github.openfinalshell.android.core.workspace.ImportCommand
import io.github.openfinalshell.android.core.workspace.ImportCommandGroup
import io.github.openfinalshell.android.core.workspace.ImportConnection
import io.github.openfinalshell.android.core.workspace.ImportForward
import io.github.openfinalshell.android.core.workspace.ImportGroup
import io.github.openfinalshell.android.core.workspace.ImportHostTrust
import io.github.openfinalshell.android.core.workspace.ImportKind
import io.github.openfinalshell.android.core.workspace.ImportPlan
import io.github.openfinalshell.android.core.workspace.ImportPlanResult
import io.github.openfinalshell.android.core.workspace.ImportPrivateKey
import io.github.openfinalshell.android.core.workspace.ImportProxy
import io.github.openfinalshell.android.core.workspace.LocalIndex
import io.github.openfinalshell.android.core.workspace.PlannedConnection
import io.github.openfinalshell.android.core.workspace.RecordRef
import io.github.openfinalshell.android.core.workspace.RowAction
import io.github.openfinalshell.android.core.workspace.persists
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

data class StorageExportSnapshot(
    val profiles: List<ConnectionProfile>,
    val forwards: List<ForwardRule> = emptyList(),
    val groups: List<ConnectionGroupEntity> = emptyList(),
    val proxies: List<SavedProxyEntity> = emptyList(),
    val privateKeys: List<PrivateKeyEntity> = emptyList(),
    val knownHosts: List<KnownHostEntity> = emptyList(),
    val secretValues: Map<String, String> = emptyMap(),
    val settings: JsonObject? = null,
    /** Local on-device sessions withheld from this export; surfaced to the user, never silent. */
    val localSessionsExcluded: Int = 0
)

/** An export payload plus what was deliberately left out of it. */
data class PortableExportResult(val text: String, val localSessionsExcluded: Int = 0)

@Serializable
data class ExportEnvelope(
    val app: String,
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: Long,
    val includesSecrets: Boolean,
    val note: String = "",
    val data: JsonObject? = null,
    val secrets: SealedBlock? = null,
    val enc: SealedBlock? = null
)

data class PortableImportResult(
    val profiles: Int,
    val groups: Int,
    val proxies: Int,
    val privateKeys: Int,
    val forwards: Int,
    val knownHosts: Int,
    val secrets: Int,
    val skipped: Int,
    val invalid: Int,
    val notes: List<String> = emptyList(),
    val snippets: Int = 0
)

/**
 * Decrypted portable records. Secret values stay here so a preview can show the catalog
 * without copying passwords into composition. Source paths and desktop material refs are not
 * treated as key material.
 */
data class PortableSource(
    val catalog: ImportCatalog,
    val profiles: Map<String, JsonObject>,
    val groupOrder: Map<String, Double>,
    val proxyTimes: Map<String, Pair<Long, Long>>,
    val keyTimes: Map<String, Pair<Long, Long>>,
    val commandGroupOrder: Map<String, Int>,
    val commands: Map<String, Pair<String, Long>>,
    val hosts: Map<String, Pair<String, Long>>,
    val secretValues: Map<String, String>,
    val invalid: Int,
    val rdpSkipped: Int,
    val materialRefsIgnored: Int,
    val secretsLocked: Boolean
)

/**
 * Portable v1/v2 envelope support. The storage mapping is deliberately conservative: unknown
 * records are counted as invalid and managed private-key material is never emitted.
 */
object PortableExport {
    fun parse(text: String): ExportEnvelope = ProtocolJson.instance.decodeFromString(text)

    fun decrypt(envelope: ExportEnvelope, passphrase: CharArray): JsonElement {
        require(envelope.app == "openfinalshell") { "unsupported export application" }
        return when (envelope.formatVersion) {
            1 -> envelope.data ?: error("v1 export has no data")
            2 -> {
                val block = envelope.enc ?: error("v2 export has no encrypted payload")
                ProtocolJson.instance.parseToJsonElement(
                    ImportExportCrypto.open(block, passphrase).toString(Charsets.UTF_8)
                )
            }
            else -> error("unsupported export format version: ${envelope.formatVersion}")
        }
    }

    fun buildV1(
        profiles: List<ConnectionProfile>,
        forwards: List<ForwardRule> = emptyList(),
        settings: JsonObject? = null,
        includeSecrets: Boolean = false,
        secretValues: Map<String, String> = emptyMap(),
        passphrase: CharArray? = null,
        appVersion: String = "android",
        groups: List<ConnectionGroupEntity> = emptyList(),
        proxies: List<SavedProxyEntity> = emptyList(),
        privateKeys: List<PrivateKeyEntity> = emptyList(),
        knownHosts: List<KnownHostEntity> = emptyList(),
        toolsData: JsonObject = JsonObject(emptyMap())
    ): String {
        val data = buildData(profiles, forwards, settings, groups, proxies, privateKeys, knownHosts, toolsData)
        val secrets = if (includeSecrets) {
            ImportExportCrypto.seal(
                ProtocolJson.instance.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { secretValues.forEach { (key, value) -> put(key, value) } }
                ).toByteArray(Charsets.UTF_8),
                requireSecretPassphrase(passphrase, secretValues)
            )
        } else null
        return ProtocolJson.instance.encodeToString(
            ExportEnvelope.serializer(),
            ExportEnvelope("openfinalshell", 1, appVersion, System.currentTimeMillis(), includeSecrets, data = data, secrets = secrets)
        )
    }

    /** Builds a portable export from all Android repositories, including reusable references. */
    suspend fun buildV1FromStorage(
        profiles: ProfileRepository,
        forwards: ForwardRepository,
        groups: ConnectionGroupRepository,
        proxies: SavedProxyRepository,
        privateKeys: PrivateKeyRepository,
        knownHosts: KnownHostRepository,
        credentials: AndroidCredentialStore,
        includeSecrets: Boolean = false,
        passphrase: CharArray? = null,
        settings: JsonObject? = null,
        appVersion: String = "android",
        tools: ToolDao? = null
    ): PortableExportResult {
        val snapshot = collectStorage(profiles, forwards, groups, proxies, privateKeys, knownHosts, credentials, settings)
        return PortableExportResult(
            buildV1(
                snapshot.profiles,
                snapshot.forwards,
                snapshot.settings,
                includeSecrets,
                snapshot.secretValues,
                passphrase,
                appVersion,
                snapshot.groups,
                snapshot.proxies,
                snapshot.privateKeys,
                snapshot.knownHosts,
                ToolPortable.export(tools)
            ),
            snapshot.localSessionsExcluded
        )
    }

    fun buildV2(
        profiles: List<ConnectionProfile>,
        forwards: List<ForwardRule> = emptyList(),
        settings: JsonObject? = null,
        passphrase: CharArray,
        secretValues: Map<String, String> = emptyMap(),
        appVersion: String = "android",
        groups: List<ConnectionGroupEntity> = emptyList(),
        proxies: List<SavedProxyEntity> = emptyList(),
        privateKeys: List<PrivateKeyEntity> = emptyList(),
        knownHosts: List<KnownHostEntity> = emptyList(),
        toolsData: JsonObject = JsonObject(emptyMap())
    ): String {
        val blob = buildJsonObject {
            put("data", buildData(profiles, forwards, settings, groups, proxies, privateKeys, knownHosts, toolsData))
            put("secrets", buildJsonObject { secretValues.forEach { (key, value) -> put(key, value) } })
        }
        val block = ImportExportCrypto.seal(
            ProtocolJson.instance.encodeToString(JsonObject.serializer(), blob).toByteArray(Charsets.UTF_8),
            passphrase
        )
        return ProtocolJson.instance.encodeToString(
            ExportEnvelope.serializer(),
            ExportEnvelope("openfinalshell", 2, appVersion, System.currentTimeMillis(), secretValues.isNotEmpty(), enc = block)
        )
    }

    /** Builds a fully encrypted export from all Android repositories. */
    suspend fun buildV2FromStorage(
        profiles: ProfileRepository,
        forwards: ForwardRepository,
        groups: ConnectionGroupRepository,
        proxies: SavedProxyRepository,
        privateKeys: PrivateKeyRepository,
        knownHosts: KnownHostRepository,
        credentials: AndroidCredentialStore,
        passphrase: CharArray,
        includeSecrets: Boolean = true,
        settings: JsonObject? = null,
        appVersion: String = "android",
        tools: ToolDao? = null
    ): PortableExportResult {
        val snapshot = collectStorage(profiles, forwards, groups, proxies, privateKeys, knownHosts, credentials, settings)
        return PortableExportResult(
            buildV2(
                snapshot.profiles,
                snapshot.forwards,
                snapshot.settings,
                passphrase,
                if (includeSecrets) snapshot.secretValues else emptyMap(),
                appVersion,
                snapshot.groups,
                snapshot.proxies,
                snapshot.privateKeys,
                snapshot.knownHosts,
                ToolPortable.export(tools)
            ),
            snapshot.localSessionsExcluded
        )
    }

    private suspend fun collectStorage(
        profiles: ProfileRepository,
        forwards: ForwardRepository,
        groups: ConnectionGroupRepository,
        proxies: SavedProxyRepository,
        privateKeys: PrivateKeyRepository,
        knownHosts: KnownHostRepository,
        credentials: AndroidCredentialStore,
        settings: JsonObject?
    ): StorageExportSnapshot {
        val profileRows = profiles.list()
        // Local on-device sessions stay on the device. A shell path, a privilege tier and a start
        // directory describe one phone; exporting them would either fail on import or, at the root
        // tier, describe somewhere the user never intended. The desktop importer also has no
        // concept of this protocol, so excluding here is what keeps it out of the desktop entirely.
        val localRows = profileRows.filter { it.protocol == LOCAL_SHELL_PROTOCOL }
        val portableRows = profileRows.filterNot { it.protocol == LOCAL_SHELL_PROTOCOL }
        val forwardRows = forwards.list().map { ForwardRule(it.id, it.profileId, it.type, it.label, it.bindAddr, it.bindPort, it.dstHost, it.dstPort, it.autoStart) }
        val groupRows = groups.list()
        val proxyRows = proxies.list()
        val keyRows = privateKeys.list()
        val hostRows = knownHosts.list()
        val refs = buildSet {
            portableRows.forEach { profile ->
                profile.auth.passwordRef?.let(::add)
                profile.auth.passphraseRef?.let(::add)
                profile.proxy?.passwordRef?.let(::add)
            }
            proxyRows.mapNotNullTo(this) { it.passwordRef }
            keyRows.mapNotNullTo(this) { it.passphraseRef }
        }
        val secrets = refs.mapNotNull { ref -> credentials.get(ref)?.let { ref to it } }.toMap()
        return StorageExportSnapshot(
            portableRows,
            forwardRows,
            groupRows,
            proxyRows,
            keyRows,
            hostRows,
            secrets,
            settings,
            localRows.size
        )
    }

    /**
     * Opens an envelope into a catalog. A v2 file needs a passphrase. A v1 file with a sealed
     * secret block can be opened without one; the configuration stays readable and [PortableSource.secretsLocked]
     * is set. Desktop `materialRef` ciphertext is counted and dropped.
     */
    fun readSource(envelope: ExportEnvelope, passphrase: CharArray?): PortableSource {
        require(envelope.app == "openfinalshell") { "unsupported export application" }
        val decryptedV2 = if (envelope.formatVersion == 2) {
            val key = passphrase ?: error("passphrase required")
            decrypt(envelope, key).jsonObject
        } else null
        val payload = when (envelope.formatVersion) {
            1 -> envelope.data ?: error("v1 export has no data")
            2 -> decryptedV2 ?: error("invalid v2 payload")
            else -> error("unsupported export format version: ${envelope.formatVersion}")
        }
        val secretsLocked = envelope.formatVersion == 1 && envelope.secrets != null && passphrase == null
        val secretValues = when {
            secretsLocked -> emptyMap()
            envelope.formatVersion == 1 && envelope.secrets != null ->
                decodeSecrets(ImportExportCrypto.open(envelope.secrets, passphrase ?: error("passphrase required")))
            envelope.formatVersion == 2 -> decodeSecrets(
                ProtocolJson.instance.encodeToString(
                    JsonObject.serializer(),
                    (decryptedV2?.get("secrets") as? JsonObject) ?: JsonObject(emptyMap())
                ).toByteArray(Charsets.UTF_8)
            )
            else -> emptyMap()
        }
        val objectPayload = payload as? JsonObject ?: error("invalid payload")
        val root = if (envelope.formatVersion == 2) objectPayload["data"] as? JsonObject ?: error("invalid v2 payload") else objectPayload
        return readRoot(root, secretValues, secretsLocked)
    }

    suspend fun localIndex(
        profiles: ProfileRepository,
        forwards: ForwardRepository,
        groups: ConnectionGroupRepository,
        proxies: SavedProxyRepository,
        privateKeys: PrivateKeyRepository,
        knownHosts: KnownHostRepository,
        tools: ToolDao?
    ): LocalIndex = LocalIndex(
        groups = groups.list().map { it.id }.toSet(),
        proxies = proxies.list().map { it.id }.toSet(),
        privateKeyFingerprints = privateKeys.list().associate { it.id to it.sha256 },
        connections = profiles.list().map { it.id }.toSet(),
        forwards = forwards.list().map { it.id }.toSet(),
        commandGroups = tools?.groups()?.map { it.id }?.toSet().orEmpty(),
        commands = tools?.snippets()?.map { it.id }?.toSet().orEmpty(),
        hostTrustFingerprints = knownHosts.list().associate { it.key to it.fingerprintSha256 }
    )

    /** Apply a decrypted envelope to Room. The caller supplies repositories to keep this layer UI independent. */
    suspend fun importInto(
        envelope: ExportEnvelope,
        passphrase: CharArray?,
        profiles: ProfileRepository,
        forwards: ForwardRepository,
        groups: ConnectionGroupRepository,
        proxies: SavedProxyRepository,
        privateKeys: PrivateKeyRepository,
        knownHosts: KnownHostRepository,
        credentials: AndroidCredentialStore,
        conflict: ImportConflict = ImportConflict.SKIP,
        tools: ToolDao? = null
    ): PortableImportResult {
        val source = readSource(envelope, passphrase)
        val local = localIndex(profiles, forwards, groups, proxies, privateKeys, knownHosts, tools)
        val action = when (conflict) {
            ImportConflict.SKIP -> ConflictAction.Skip
            ImportConflict.OVERWRITE -> ConflictAction.Overwrite
            ImportConflict.DUPLICATE -> ConflictAction.Duplicate
        }
        val plan = ImportPlan.build(
            source.catalog,
            local,
            ImportCategory.entries.toSet(),
            action,
            emptySet(),
            { _, _ -> UUID.randomUUID().toString() },
            { UUID.randomUUID().toString() }
        )
        return applyPlan(source, plan, profiles, forwards, groups, proxies, privateKeys, knownHosts, credentials, tools)
    }

    /**
     * Writes [plan] after its associations check out. Skip rows keep the local record and its
     * secrets. A private-key overwrite keeps Keystore material only when the plan says the
     * fingerprints match; this function never deletes that material. Host trust is inserted only
     * for a new key, using the original key, and an existing row is left as it is.
     */
    suspend fun applyPlan(
        source: PortableSource,
        plan: ImportPlanResult,
        profiles: ProfileRepository,
        forwards: ForwardRepository,
        groups: ConnectionGroupRepository,
        proxies: SavedProxyRepository,
        privateKeys: PrivateKeyRepository,
        knownHosts: KnownHostRepository,
        credentials: AndroidCredentialStore,
        tools: ToolDao? = null
    ): PortableImportResult {
        val fresh = localIndex(profiles, forwards, groups, proxies, privateKeys, knownHosts, tools)
        if (tools == null && (plan.commandGroups.isNotEmpty() || plan.commands.isNotEmpty())) error("command library is unavailable")
        checkAssociations(plan, fresh)
        for (row in plan.privateKeys) {
            if (row.action != RowAction.Overwrite) continue
            val existing = privateKeys.find(row.targetId) ?: error("private key ${row.targetId} is no longer on this device")
            if (existing.sha256 != row.fingerprint) error("private key fingerprint changed; local material was kept")
        }
        val notes = mutableListOf<String>()
        if (source.rdpSkipped > 0) notes += "Skipped ${source.rdpSkipped} RDP connections"
        if (source.materialRefsIgnored > 0) notes += "Private key material from another device was not imported"
        if (source.secretsLocked) notes += "Sealed secrets were not opened"
        plan.dropped.forEach { drop ->
            notes += when (drop.reason) {
                DropReason.PrivateKeyFingerprintMismatch -> "Private key fingerprint differs; local material was kept"
                DropReason.DependencyCancelled -> "Cleared because a required item was cleared"
                DropReason.DanglingReference -> "Missing required item"
                DropReason.NoLongerRequired -> "No longer required"
            }
        }
        for (secret in plan.secrets) {
            val value = source.secretValues[secret.sourceRef] ?: error("secret ${secret.sourceRef} is not in the file")
            credentials.put(value, secret.localRef)
        }
        for (row in plan.groups) {
            groups.upsert(ConnectionGroupEntity(row.targetId, row.name, row.parentId, source.groupOrder[row.sourceId] ?: 0.0))
        }
        for (row in plan.proxies) {
            val times = source.proxyTimes[row.sourceId] ?: (0L to 0L)
            val previous = proxies.find(row.targetId)
            if (row.pendingSecret) notes += "Proxy ${row.name} is missing its password"
            proxies.upsert(
                SavedProxyEntity(
                    row.targetId, row.name, row.type, row.host, row.port, row.username, row.passwordRef,
                    previous?.createdAt?.takeIf { it > 0L } ?: times.first,
                    System.currentTimeMillis()
                )
            )
        }
        for (row in plan.privateKeys) {
            val times = source.keyTimes[row.sourceId] ?: (0L to 0L)
            val previous = if (row.action == RowAction.Overwrite) privateKeys.find(row.targetId) else null
            val keepMaterial = row.action == RowAction.Overwrite && previous?.sha256 == row.fingerprint
            if (row.materialPending && !keepMaterial) notes += "Private key ${row.name} needs its key file"
            if (row.pendingSecret) notes += "Private key ${row.name} is missing its passphrase"
            privateKeys.upsert(
                PrivateKeyEntity(
                    id = row.targetId,
                    name = row.name,
                    originalPath = if (keepMaterial) previous?.originalPath else null,
                    sha256 = row.fingerprint,
                    passphraseRef = row.passphraseRef,
                    materialRef = retainedMaterialRef(previous?.materialRef, keepMaterial),
                    createdAt = previous?.createdAt?.takeIf { it > 0L } ?: times.first,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val catalogConnections = source.catalog.connections.map { it.id }.toSet()
        val connectionIds = plan.idMap.filterKeys { it.kind == ImportKind.Connection }.mapKeys { it.key.id }.mapValues { it.value }
        for (row in plan.connections) {
            val raw = source.profiles[row.sourceId] ?: error("connection ${row.sourceId} is missing")
            if (row.pendingSecrets.isNotEmpty()) notes += "Connection ${row.name} is missing ${row.pendingSecrets.joinToString()}"
            profiles.upsertImported(rewriteImportedProfile(raw, row, connectionIds, catalogConnections), row.targetId)
        }
        for (row in plan.forwards) {
            forwards.upsert(ForwardRule(row.targetId, row.profileId, row.type, row.label, row.bindAddr, row.bindPort, row.dstHost, row.dstPort, row.autoStart))
        }
        if (tools != null) {
            for (row in plan.commandGroups) {
                tools.save(CommandGroupEntity(row.targetId, row.name, source.commandGroupOrder[row.sourceId] ?: 0))
            }
            for (row in plan.commands) {
                val body = source.commands[row.sourceId] ?: error("command ${row.sourceId} is missing")
                tools.save(CommandSnippetEntity(row.targetId, row.name, body.first, row.groupId, body.second))
            }
        }
        var hostCount = 0
        for (decision in plan.hostTrust) {
            val storageKey = hostTrustStorageKey(decision) ?: continue
            if (knownHosts.find(storageKey) != null) {
                notes += "Host trust for $storageKey already exists and was kept"
                continue
            }
            val meta = source.hosts[decision.key] ?: continue
            knownHosts.trust(KnownHostEntity(storageKey, meta.first, decision.incomingFingerprint, meta.second))
            hostCount++
        }
        val skipped = plan.skipped.size + plan.hostTrust.count { it.action == RowAction.Skip || it.action == RowAction.Block }
        return PortableImportResult(
            profiles = plan.connections.size,
            groups = plan.groups.size,
            proxies = plan.proxies.size,
            privateKeys = plan.privateKeys.size,
            forwards = plan.forwards.size,
            knownHosts = hostCount,
            secrets = plan.secrets.size,
            skipped = skipped,
            invalid = source.invalid,
            notes = notes,
            snippets = plan.commands.size
        )
    }

    private fun readRoot(root: JsonObject, secretValues: Map<String, String>, secretsLocked: Boolean): PortableSource {
        fun array(name: String): List<JsonElement> {
            val items = (root[name] as? JsonArray)?.toList().orEmpty()
            require(items.size <= 10_000) { "import limit" }
            return items
        }
        var invalid = 0
        var rdpSkipped = 0
        var materialRefsIgnored = 0
        val groups = mutableListOf<ImportGroup>()
        val groupOrder = linkedMapOf<String, Double>()
        for (element in array("groups")) {
            val value = element as? JsonObject
            if (value == null) {
                invalid++
                continue
            }
            val id = text(value, "id")
            val name = text(value, "name")
            if (id == null || name == null) { invalid++; continue }
            groups += ImportGroup(id, name, text(value, "parentId"))
            groupOrder[id] = (value["order"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        }
        val proxies = mutableListOf<ImportProxy>()
        val proxyTimes = linkedMapOf<String, Pair<Long, Long>>()
        for (element in array("proxies")) {
            val value = element as? JsonObject
            if (value == null) {
                invalid++
                continue
            }
            val id = text(value, "id")
            val name = text(value, "name")
            val type = text(value, "type")
            val host = text(value, "host")
            val port = intOf(value, "port")
            if (id == null || name == null || type == null || host == null || port == null) { invalid++; continue }
            proxies += ImportProxy(id, name, type, host, port, text(value, "username"), text(value, "passwordRef"))
            proxyTimes[id] = longOf(value, "createdAt") to longOf(value, "updatedAt")
        }
        val keys = mutableListOf<ImportPrivateKey>()
        val keyTimes = linkedMapOf<String, Pair<Long, Long>>()
        for (element in array("privateKeys")) {
            val value = element as? JsonObject
            if (value == null) {
                invalid++
                continue
            }
            val id = text(value, "id")
            val name = text(value, "name")
            if (id == null || name == null) { invalid++; continue }
            if (value.containsKey("materialRef")) materialRefsIgnored++
            val fingerprint = text(value, "sourceFingerprint") ?: text(value, "sha256") ?: ""
            keys += ImportPrivateKey(id, name, fingerprint, text(value, "passphraseRef"))
            keyTimes[id] = longOf(value, "createdAt") to longOf(value, "updatedAt")
        }
        val profileJson = linkedMapOf<String, JsonObject>()
        val connections = mutableListOf<ImportConnection>()
        for (element in array("profiles")) {
            val value = element as? JsonObject
            if (value == null) {
                invalid++
                continue
            }
            val parsed = runCatching { ProtocolJson.instance.decodeFromJsonElement(ConnectionProfile.serializer(), value) }.getOrNull()
            if (parsed == null) { invalid++; continue }
            if (parsed.protocol == LOCAL_SHELL_PROTOCOL) { invalid++; continue }
            if (parsed.protocol.equals("rdp", ignoreCase = true)) { rdpSkipped++; continue }
            profileJson[parsed.id] = value
            connections += ImportConnection(
                parsed.id, parsed.name, parsed.host, parsed.port, parsed.username,
                parsed.groupId, parsed.proxyId, parsed.auth.privateKeyId, parsed.auth.passwordRef, parsed.auth.passphraseRef,
                proxyPasswordRef = parsed.proxy?.passwordRef
            )
        }
        val forwards = mutableListOf<ImportForward>()
        for (element in array("forwards")) {
            val parsed = (element as? JsonObject)?.let { runCatching { ProtocolJson.instance.decodeFromJsonElement(ForwardRule.serializer(), it) }.getOrNull() }
            if (parsed == null) { invalid++; continue }
            forwards += ImportForward(parsed.id, parsed.profileId, parsed.type, parsed.label, parsed.bindAddr, parsed.bindPort, parsed.dstHost, parsed.dstPort, parsed.autoStart)
        }
        val commandGroups = mutableListOf<ImportCommandGroup>()
        val commandGroupOrder = linkedMapOf<String, Int>()
        for (element in array("snippetGroups")) {
            val value = element as? JsonObject
            if (value == null) {
                invalid++
                continue
            }
            val id = text(value, "id")
            val name = text(value, "name")
            if (id == null || name == null || name.length > 200) { invalid++; continue }
            commandGroups += ImportCommandGroup(id, name)
            commandGroupOrder[id] = intOf(value, "order") ?: 0
        }
        val commands = mutableListOf<ImportCommand>()
        val commandBodies = linkedMapOf<String, Pair<String, Long>>()
        for (element in array("snippets")) {
            val value = element as? JsonObject
            if (value == null) {
                invalid++
                continue
            }
            val id = text(value, "id")
            val name = text(value, "name")
            val command = text(value, "command")
            if (id == null || name == null || command == null || name.length > 200 || command.length > 32_768 || '\u0000' in command) {
                invalid++; continue
            }
            commands += ImportCommand(id, name, text(value, "groupId"))
            commandBodies[id] = command to (longOf(value, "updatedAt").takeIf { it > 0L } ?: System.currentTimeMillis())
        }
        val hosts = mutableListOf<ImportHostTrust>()
        val hostMeta = linkedMapOf<String, Pair<String, Long>>()
        for (element in array("knownHosts")) {
            val value = element as? JsonObject
            if (value == null) {
                invalid++
                continue
            }
            val key = text(value, "key")
            val keyType = text(value, "keyType")
            val fingerprint = text(value, "fingerprintSha256")
            if (key == null || keyType == null || fingerprint == null) { invalid++; continue }
            hosts += ImportHostTrust(key, fingerprint)
            hostMeta[key] = keyType to longOf(value, "addedAt")
        }
        return PortableSource(
            ImportCatalog(groups, proxies, keys, connections, forwards, commandGroups, commands, hosts, secretValues.keys),
            profileJson, groupOrder, proxyTimes, keyTimes, commandGroupOrder, commandBodies, hostMeta,
            secretValues, invalid, rdpSkipped, materialRefsIgnored, secretsLocked
        )
    }

    private fun checkAssociations(plan: ImportPlanResult, local: LocalIndex) {
        if (!plan.groups.all { it.action.persists() } || !plan.connections.all { it.action.persists() }) error("a skipped row was queued for write")
        val groupIds = plan.groups.map { it.targetId }.toSet() + local.groups
        val proxyIds = plan.proxies.map { it.targetId }.toSet() + local.proxies
        val keyIds = plan.privateKeys.map { it.targetId }.toSet() + local.privateKeyFingerprints.keys
        val connectionIds = plan.connections.map { it.targetId }.toSet() + local.connections
        val commandGroupIds = plan.commandGroups.map { it.targetId }.toSet() + local.commandGroups
        fun check(id: String?, allowed: Set<String>, label: String) {
            if (id != null && id !in allowed) error("dangling $label $id")
        }
        fun expect(action: RowAction, id: String, exists: Boolean, label: String) {
            when (action) {
                RowAction.Insert, RowAction.Duplicate -> if (exists) error("$label $id already exists")
                RowAction.Overwrite -> if (!exists) error("$label $id is missing")
                else -> error("$label $id is not a write")
            }
        }
        plan.groups.forEach { row ->
            expect(row.action, row.targetId, row.targetId in local.groups, "group")
            check(row.parentId, groupIds, "group")
        }
        plan.proxies.forEach { expect(it.action, it.targetId, it.targetId in local.proxies, "proxy") }
        plan.privateKeys.forEach { expect(it.action, it.targetId, it.targetId in local.privateKeyFingerprints, "private key") }
        plan.connections.forEach { row ->
            expect(row.action, row.targetId, row.targetId in local.connections, "connection")
            check(row.groupId, groupIds, "group")
            check(row.proxyId, proxyIds, "proxy")
            check(row.privateKeyId, keyIds, "private key")
        }
        plan.forwards.forEach { row ->
            expect(row.action, row.targetId, row.targetId in local.forwards, "forward")
            check(row.profileId, connectionIds, "connection")
        }
        plan.commandGroups.forEach { expect(it.action, it.targetId, it.targetId in local.commandGroups, "command group") }
        plan.commands.forEach { row ->
            expect(row.action, row.targetId, row.targetId in local.commands, "command")
            check(row.groupId, commandGroupIds, "command group")
        }
        val secretLocals = plan.secrets.map { it.localRef }.toSet()
        fun secret(ref: String?) {
            if (ref != null && ref !in secretLocals) error("secret ref $ref was not allocated")
        }
        plan.proxies.forEach { secret(it.passwordRef) }
        plan.privateKeys.forEach { secret(it.passphraseRef) }
        plan.connections.forEach { secret(it.passwordRef); secret(it.passphraseRef); secret(it.proxyPasswordRef) }
    }

    private fun buildData(
        profiles: List<ConnectionProfile>,
        forwards: List<ForwardRule>,
        settings: JsonObject?,
        groups: List<ConnectionGroupEntity> = emptyList(),
        proxies: List<SavedProxyEntity> = emptyList(),
        privateKeys: List<PrivateKeyEntity> = emptyList(),
        knownHosts: List<KnownHostEntity> = emptyList(),
        toolsData: JsonObject = JsonObject(emptyMap())
    ): JsonObject = buildJsonObject {
        // Second filter, on the single choke point every export path passes through. collectStorage
        // already excludes local sessions and reports the count, but buildData is what guarantees
        // no caller can leak one by passing an explicit profile list.
        put("profiles", JsonArray(profiles.filterNot { it.protocol == LOCAL_SHELL_PROTOCOL }.map { ProtocolJson.instance.encodeToJsonElement(ConnectionProfile.serializer(), it) }))
        put("forwards", JsonArray(forwards.map { ProtocolJson.instance.encodeToJsonElement(ForwardRule.serializer(), it) }))
        put("groups", JsonArray(groups.map { group -> buildJsonObject {
            put("id", group.id)
            put("name", group.name)
            group.parentId?.let { put("parentId", it) }
            put("order", group.sortOrder)
        } }))
        put("proxies", JsonArray(proxies.map { proxy -> buildJsonObject {
            put("id", proxy.id)
            put("name", proxy.name)
            put("type", proxy.type)
            put("host", proxy.host)
            put("port", proxy.port)
            proxy.username?.let { put("username", it) }
            proxy.passwordRef?.let { put("passwordRef", it) }
            put("createdAt", proxy.createdAt)
            put("updatedAt", proxy.updatedAt)
        } }))
        // The managed materialRef is local to Android Keystore and is deliberately omitted.
        put("privateKeys", JsonArray(privateKeys.map { key -> buildJsonObject {
            put("id", key.id)
            put("name", key.name)
            key.originalPath?.let { put("path", it) }
            put("sourceFingerprint", key.sha256)
            key.passphraseRef?.let { put("passphraseRef", it) }
            put("createdAt", key.createdAt)
            put("updatedAt", key.updatedAt)
        } }))
        put("knownHosts", JsonArray(knownHosts.map { host -> buildJsonObject {
            put("key", host.key)
            put("keyType", host.keyType)
            put("fingerprintSha256", host.fingerprintSha256)
            put("addedAt", host.addedAt)
        } }))
        for (key in listOf("snippets", "snippetGroups")) toolsData[key]?.let { put(key,it) }
        settings?.let { put("settings", withoutLocalDisplay(it)) }
    }

    private fun decodeSecrets(bytes: ByteArray): Map<String, String> =
        ProtocolJson.instance.decodeFromString<Map<String, String>>(bytes.toString(Charsets.UTF_8))

    private fun text(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && '\u0000' !in it }

    private fun intOf(obj: JsonObject, key: String): Int? {
        val value = obj[key] as? JsonPrimitive ?: return null
        return value.intOrNull ?: value.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun longOf(obj: JsonObject, key: String): Long = (obj[key] as? JsonPrimitive)?.longOrNull ?: 0L

    private fun putOptional(target: MutableMap<String, JsonElement>, key: String, value: String?) {
        if (value == null) target.remove(key) else target[key] = JsonPrimitive(value)
    }

    private fun requireSecretPassphrase(passphrase: CharArray?, values: Map<String, String>): CharArray =
        passphrase ?: error("a passphrase is required when exporting secrets (${values.size} values)")

    internal fun rewriteImportedProfile(
        raw: JsonObject,
        planned: PlannedConnection,
        connectionIds: Map<String, String>,
        catalogConnectionIds: Set<String>
    ): JsonObject {
        val root = raw.toMutableMap()
        root["id"] = JsonPrimitive(planned.targetId)
        putOptional(root, "groupId", planned.groupId)
        putOptional(root, "proxyId", planned.proxyId)
        val auth = (raw["auth"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        putOptional(auth, "privateKeyId", planned.privateKeyId)
        putOptional(auth, "passwordRef", planned.passwordRef)
        putOptional(auth, "passphraseRef", planned.passphraseRef)
        root["auth"] = JsonObject(auth)
        val proxy = (raw["proxy"] as? JsonObject)?.toMutableMap()
        if (proxy != null) {
            putOptional(proxy, "passwordRef", planned.proxyPasswordRef)
            root["proxy"] = JsonObject(proxy)
        }
        val jump = (raw["jumpHostId"] as? JsonPrimitive)?.contentOrNull
        if (jump != null) {
            val mapped = connectionIds[jump]
            when {
                mapped != null -> root["jumpHostId"] = JsonPrimitive(mapped)
                jump in catalogConnectionIds -> root.remove("jumpHostId")
            }
        }
        return JsonObject(root)
    }

    /** Keeps Keystore material only when the fingerprints matched. Never a signal to delete it. */
    internal fun retainedMaterialRef(existing: String?, keepExistingMaterial: Boolean): String? =
        if (keepExistingMaterial) existing else null

    /** Inserts use the original host key. Skip, block and every other action write nothing. */
    internal fun hostTrustStorageKey(decision: HostTrustDecision): String? =
        if (decision.action == RowAction.Insert) decision.key else null

    internal fun withoutLocalDisplay(settings: JsonObject): JsonObject {
        val local = setOf("sessionConnectionWidthDp", "sessionMonitorWidthDp", "sessionFileHeightDp")
        return JsonObject(settings.filterKeys { it !in local })
    }
}

enum class ImportConflict { SKIP, OVERWRITE, DUPLICATE }
