package io.github.openfinalshell.android.storage

import io.github.openfinalshell.android.core.model.ConnectionAuth
import io.github.openfinalshell.android.core.model.ConnectionProfile
import io.github.openfinalshell.android.core.model.ConnectionProxy
import io.github.openfinalshell.android.core.protocol.ProtocolJson
import io.github.openfinalshell.android.core.workspace.ConflictAction
import io.github.openfinalshell.android.core.workspace.HostTrustDecision
import io.github.openfinalshell.android.core.workspace.ImportCatalog
import io.github.openfinalshell.android.core.workspace.ImportCategory
import io.github.openfinalshell.android.core.workspace.ImportConnection
import io.github.openfinalshell.android.core.workspace.ImportPlan
import io.github.openfinalshell.android.core.workspace.LocalIndex
import io.github.openfinalshell.android.core.workspace.PlannedConnection
import io.github.openfinalshell.android.core.workspace.RowAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableImportRulesTest {
    private val passphrase = "correct horse".toCharArray()

    @Test
    fun fourExportCombinationsKeepSecretsSealedAndReadableConfigSeparate() {
        val profile = sampleProfile()
        val secrets = mapOf("pw" to "s3cret-value")
        val plain = PortableExport.parse(PortableExport.buildV1(listOf(profile), includeSecrets = false))
        assertEquals(1, plain.formatVersion)
        assertNull(plain.secrets)
        assertNull(plain.enc)
        assertTrue(plain.data.toString().contains("203.0.113.10"))
        assertFalse(PortableExport.parse(ProtocolJson.instance.encodeToString(ExportEnvelope.serializer(), plain)).toString().contains("s3cret-value"))

        val sealedConfig = PortableExport.buildV1(listOf(profile), includeSecrets = true, secretValues = secrets, passphrase = passphrase)
        val sealed = PortableExport.parse(sealedConfig)
        assertEquals(1, sealed.formatVersion)
        assertTrue(sealedConfig.contains("203.0.113.10"))
        assertFalse(sealedConfig.contains("s3cret-value"))
        assertTrue(ImportExportCrypto.open(sealed.secrets!!, passphrase).toString(Charsets.UTF_8).contains("s3cret-value"))

        val encryptedEmpty = PortableExport.buildV2(listOf(profile), passphrase = passphrase, secretValues = emptyMap())
        val encrypted = PortableExport.parse(encryptedEmpty)
        assertEquals(2, encrypted.formatVersion)
        assertNull(encrypted.data)
        assertFalse(encryptedEmpty.contains("203.0.113.10"))
        assertFalse(encryptedEmpty.contains("s3cret-value"))
        val openedEmpty = PortableExport.decrypt(encrypted, passphrase).jsonObject
        assertTrue(openedEmpty["data"].toString().contains("203.0.113.10"))
        assertFalse(openedEmpty["secrets"].toString().contains("s3cret-value"))

        val encryptedSecrets = PortableExport.buildV2(listOf(profile), passphrase = passphrase, secretValues = secrets)
        assertFalse(encryptedSecrets.contains("s3cret-value"))
        assertFalse(encryptedSecrets.contains("203.0.113.10"))
        val opened = PortableExport.decrypt(PortableExport.parse(encryptedSecrets), passphrase).jsonObject
        assertTrue(opened["data"].toString().contains("203.0.113.10"))
        assertTrue(opened["secrets"].toString().contains("s3cret-value"))
    }

    @Test
    fun exportOmitsKeystoreMaterialAndLocalPaneSizes() {
        val key = PrivateKeyEntity(
            id = "key-1",
            name = "laptop",
            originalPath = "C:/Users/a/id_rsa",
            sha256 = "abc",
            passphraseRef = null,
            materialRef = "keystore-alias-secret",
            createdAt = 1L,
            updatedAt = 2L
        )
        val settings = buildJsonObject {
            put("language", "en-US")
            put("sessionConnectionWidthDp", 280)
            put("sessionMonitorWidthDp", 300)
            put("sessionFileHeightDp", 200)
        }
        val text = PortableExport.buildV1(emptyList(), settings = settings, privateKeys = listOf(key))
        assertFalse(text.contains("keystore-alias-secret"))
        assertTrue(text.contains("abc"))
        val stored = PortableExport.parse(text).data!!.jsonObject["settings"]!!.jsonObject
        assertEquals("en-US", stored["language"]!!.jsonPrimitive.content)
        assertFalse(stored.containsKey("sessionConnectionWidthDp"))
        assertFalse(stored.containsKey("sessionMonitorWidthDp"))
        assertFalse(stored.containsKey("sessionFileHeightDp"))
    }

    @Test
    fun readSourceDropsDesktopMaterialAndKeepsHostKey() {
        val data = buildJsonObject {
            put("privateKeys", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("id", "key-1")
                put("name", "laptop")
                put("path", "C:/Users/a/id_rsa")
                put("sourceFingerprint", "abc")
                put("materialRef", "dpapi-blob")
            })))
            put("knownHosts", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("key", "203.0.113.5:22:ssh-ed25519")
                put("keyType", "ssh-ed25519")
                put("fingerprintSha256", "SHA256:same")
            })))
            put("profiles", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        val envelope = ExportEnvelope("openfinalshell", 1, "test", 0L, false, data = data)
        val source = PortableExport.readSource(envelope, null)
        assertEquals(1, source.materialRefsIgnored)
        assertEquals("abc", source.catalog.privateKeys.single().fingerprint)
        assertEquals("203.0.113.5:22:ssh-ed25519", source.catalog.hostTrust.single().key)
        assertFalse(source.toString().contains("dpapi-blob"))
        val blocked = HostTrustDecision("203.0.113.5:22:ssh-ed25519", "SHA256:other", "SHA256:local", RowAction.Block, true)
        val same = HostTrustDecision("203.0.113.5:22:ssh-ed25519", "SHA256:same", "SHA256:same", RowAction.Skip, true)
        val fresh = HostTrustDecision("203.0.113.5:22:ssh-ed25519", "SHA256:same", null, RowAction.Insert, false)
        assertNull(PortableExport.hostTrustStorageKey(blocked))
        assertNull(PortableExport.hostTrustStorageKey(same))
        assertEquals("203.0.113.5:22:ssh-ed25519", PortableExport.hostTrustStorageKey(fresh))
        assertEquals("keystore-keep", PortableExport.retainedMaterialRef("keystore-keep", true))
        assertNull(PortableExport.retainedMaterialRef("keystore-keep", false))
    }

    @Test
    fun profileRewriteUsesFinalIdsAndClearsMissingProxySecret() {
        val raw = buildJsonObject {
            put("id", "conn")
            put("name", "box")
            put("host", "203.0.113.10")
            put("port", 22)
            put("username", "root")
            put("groupId", "group-old")
            put("proxyId", "proxy-old")
            put("jumpHostId", "other")
            put("auth", buildJsonObject {
                put("method", "password")
                put("passwordRef", "pw-old")
                put("privateKeyId", "key-old")
            })
            put("proxy", buildJsonObject { put("type", "socks5"); put("passwordRef", "proxy-secret") })
        }
        val planned = PlannedConnection(
            sourceId = "conn",
            targetId = "conn-copy",
            action = RowAction.Duplicate,
            name = "box",
            host = "203.0.113.10",
            port = 22,
            username = "root",
            groupId = "group-new",
            proxyId = "proxy-new",
            privateKeyId = "key-new",
            passwordRef = "pw-new",
            passphraseRef = null,
            pendingSecrets = emptyList()
        )
        val rewritten = PortableExport.rewriteImportedProfile(
            raw,
            planned,
            mapOf("other" to "other-copy"),
            setOf("conn", "other")
        )
        assertEquals("conn-copy", rewritten["id"]!!.jsonPrimitive.content)
        assertEquals("group-new", rewritten["groupId"]!!.jsonPrimitive.content)
        assertEquals("proxy-new", rewritten["proxyId"]!!.jsonPrimitive.content)
        assertEquals("other-copy", rewritten["jumpHostId"]!!.jsonPrimitive.content)
        val auth = rewritten["auth"]!!.jsonObject
        assertEquals("pw-new", auth["passwordRef"]!!.jsonPrimitive.content)
        assertEquals("key-new", auth["privateKeyId"]!!.jsonPrimitive.content)
        assertFalse(rewritten["proxy"]!!.jsonObject.containsKey("passwordRef"))
        val mismatch = ImportPlan.build(
            ImportCatalog(privateKeys = listOf(io.github.openfinalshell.android.core.workspace.ImportPrivateKey("key-1", "laptop", "incoming"))),
            LocalIndex(privateKeyFingerprints = mapOf("key-1" to "local")),
            setOf(ImportCategory.PrivateKeys),
            ConflictAction.Overwrite,
            newId = { _, id -> "copy-$id" },
            newSecretRef = { "secret-$it" }
        )
        assertTrue(mismatch.privateKeys.isEmpty())
        assertTrue(mismatch.preserveLocalKeyMaterial.contains("key-1"))
    }

    @Test
    fun inlineProxyPasswordSurvivesImportAndOnlyAllocatesForWrittenConnections() {
        val profile = sampleProfile().copy(
            proxy = ConnectionProxy("socks5", "proxy.example", 1080, "proxy-user", "proxy-pw")
        )
        val exported = PortableExport.buildV1(
            listOf(profile), includeSecrets = true,
            secretValues = mapOf("pw" to "login-password", "proxy-pw" to "proxy-password"),
            passphrase = passphrase
        )
        val source = PortableExport.readSource(PortableExport.parse(exported), passphrase)
        fun plan(conflict: ConflictAction, available: Set<String> = source.catalog.availableSecrets) = ImportPlan.build(
            source.catalog.copy(availableSecrets = available),
            LocalIndex(connections = setOf(profile.id)),
            setOf(ImportCategory.Connections), conflict,
            newId = { _, id -> "copy-$id" }, newSecretRef = { "local-$it" }
        )
        val copied = plan(ConflictAction.Duplicate)
        assertEquals(setOf("pw", "proxy-pw"), copied.secrets.map { it.sourceRef }.toSet())
        val row = copied.connections.single()
        val rewritten = PortableExport.rewriteImportedProfile(source.profiles.getValue(profile.id), row, emptyMap(), setOf(profile.id))
        assertEquals("local-proxy-pw", rewritten["proxy"]!!.jsonObject["passwordRef"]!!.jsonPrimitive.content)
        assertEquals("proxy-password", source.secretValues[copied.secrets.single { it.localRef == row.proxyPasswordRef }.sourceRef])
        assertTrue(plan(ConflictAction.Skip).secrets.isEmpty())
        val missing = plan(ConflictAction.Overwrite, setOf("pw")).connections.single()
        assertNull(missing.proxyPasswordRef)
        assertTrue("proxy.passwordRef" in missing.pendingSecrets)
        val cleared = PortableExport.rewriteImportedProfile(source.profiles.getValue(profile.id), missing, emptyMap(), setOf(profile.id))
        assertFalse(cleared["proxy"]!!.jsonObject.containsKey("passwordRef"))
    }

    private fun sampleProfile() = ConnectionProfile(
        id = "p1",
        name = "box",
        host = "203.0.113.10",
        username = "root",
        auth = ConnectionAuth("password", passwordRef = "pw")
    )
}
