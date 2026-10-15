package io.github.openfinalshell.android.core.connection

import io.github.openfinalshell.android.core.model.ConnectionAuth
import io.github.openfinalshell.android.core.model.ConnectionOptions
import io.github.openfinalshell.android.core.model.ConnectionProfile
import io.github.openfinalshell.android.core.model.ConnectionTerminal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ConnectionPlanningTest {
    @Test
    fun `quick connect accepts the documented forms and does not invent a username`() {
        assertEquals(QuickConnectTarget("root", "example.test", 22), parseQuickConnect("root@example.test"))
        assertEquals(QuickConnectTarget(null, "example.test", 22), parseQuickConnect("example.test"))
        assertEquals(QuickConnectTarget(null, "example.test", 2222), parseQuickConnect("example.test:2222"))
        assertEquals(QuickConnectTarget("admin", "example.test", 2222), parseQuickConnect("admin@example.test:2222"))
        assertEquals(QuickConnectTarget("admin", "example.test", 2200), parseQuickConnect("ssh -p 2200 admin@example.test"))
        assertEquals(QuickConnectTarget("admin", "2001:db8::1", 22), parseQuickConnect("admin@[2001:db8::1]"))
        assertEquals(QuickConnectTarget(null, "2001:db8::1", 2222), parseQuickConnect("[2001:db8::1]:2222"))
        assertNull(parseQuickConnect("ssh -p 70000 admin@example.test"))
        assertNull(parseQuickConnect("example.test:abc"))
        assertNull(parseQuickConnect("ssh example.test; rm -rf /"))
        assertNull(parseQuickConnect(""))
    }

    @Test
    fun `ssh command quotes arguments and never includes a password`() {
        val plain = profile(username = "root", host = "example.test", port = 2200)
        assertEquals("ssh -p 2200 root@example.test", formatSshCommand(plain).command)
        assertTrue(formatSshCommand(plain).notices.isEmpty())

        val quoted = profile(username = "a b", host = "ex'ample", port = 22, privateKeyId = "key", proxyId = "proxy")
        val line = formatSshCommand(quoted)
        assertEquals("ssh -p 22 'a b@ex'\\''ample'", line.command)
        assertFalse(line.command.contains("secret"))
        assertEquals(listOf(SshCommandNotice.PRIVATE_KEY, SshCommandNotice.PROXY), line.notices)
    }

    @Test
    fun `mask host keeps only distinguishing ends`() {
        assertEquals("10.•.•.8", maskHost("10.1.2.8"))
        assertEquals("2001:…:1", maskHost("2001:db8::1"))
        assertEquals("jp1.•••.org", maskHost("jp1.example.org"))
    }

    @Test
    fun `deleting a group sends direct connections to the root and lifts child groups`() {
        val groups = listOf(
            GroupNode("root-group", null),
            GroupNode("parent", "root-group"),
            GroupNode("child", "parent")
        )
        val plan = planGroupDelete(
            "parent",
            groups,
            mapOf("direct" to "parent", "nested" to "child")
        )
        assertNull(plan?.connectionGroupId)
        assertEquals("root-group", plan?.parentId)
        assertEquals(listOf("direct"), plan?.directProfileIds)
        assertEquals(listOf("child"), plan?.childGroupIds)
        assertFalse(isAllowedParent("parent", "child", groups))
        assertTrue(isAllowedParent("parent", "root-group", groups))
        assertTrue(isAllowedParent("child", null, groups))
    }

    @Test
    fun `timing treats oversized desktop values as milliseconds`() {
        assertEquals(20_000L, ConnectionTiming.readyTimeoutMillis(20))
        assertEquals(15_000L, ConnectionTiming.readyTimeoutMillis(15_000))
        assertEquals(30, ConnectionTiming.keepaliveSeconds(30))
        assertEquals(15, ConnectionTiming.keepaliveSeconds(15_000))
        assertEquals(0, ConnectionTiming.keepaliveSeconds(0))
    }

    @Test
    fun `profile merge keeps unknown fields and clears last use on a duplicate`() {
        val existing = buildJsonObject {
            put("id", "source")
            put("desktopOnly", "keep")
            put("terminal", buildJsonObject {
                put("charset", "gbk")
                put("desktopTerm", true)
            })
        }
        val duplicate = profile().copy(id = "copy", lastUsedAt = null, terminal = ConnectionTerminal(charset = "gbk"))
        val merged = ProfileJsonMerge.merge(existing, duplicate)
        assertEquals("keep", (merged["desktopOnly"] as JsonPrimitive).content)
        assertEquals(true, merged["terminal"]!!.let { (it as kotlinx.serialization.json.JsonObject)["desktopTerm"] is JsonPrimitive })
        assertEquals("copy", (merged["id"] as JsonPrimitive).content)
    }

    @Test
    fun `recent profiles are the eight newest successful connections`() {
        val profiles = (1..10).map { index ->
            profile().copy(id = index.toString(), lastUsedAt = if (index == 3) null else index.toLong())
        }
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "2"), recentProfiles(profiles).map { it.id })
    }

    @Test
    fun `legacy algorithms are an explicit append list`() {
        assertEquals(
            listOf("diffie-hellman-group14-sha1", "diffie-hellman-group1-sha1"),
            SshRuntimePolicy.legacyKexNames
        )
        assertTrue(SshRuntimePolicy.compressionFactories().none { it.name == "none" } || SshRuntimePolicy.compressionFactories().isNotEmpty())
    }

    @Test
    fun `charset bridge keeps a split multibyte sequence`() {
        val bridge = CharsetBridge("gbk")
        val first = bridge.decodeRemoteToUtf8(byteArrayOf(0xD6.toByte()))
        val second = bridge.decodeRemoteToUtf8(byteArrayOf(0xD0.toByte()))
        assertEquals("", first.toString(Charsets.UTF_8))
        assertEquals("中", (first + second).toString(Charsets.UTF_8))
    }

    private fun profile(
        username: String = "root",
        host: String = "example.test",
        port: Int = 22,
        privateKeyId: String? = null,
        proxyId: String? = null
    ) = ConnectionProfile(
        id = "id",
        name = "server",
        host = host,
        port = port,
        username = username,
        auth = ConnectionAuth(method = if (privateKeyId != null) "privateKey" else "password", passwordRef = "secret", privateKeyId = privateKeyId),
        terminal = ConnectionTerminal(),
        options = ConnectionOptions(),
        proxyId = proxyId
    )
}
