package io.github.openfinalshell.android.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalShellCatalogTest {
    @Test
    fun parsesConnectionsAndGroupsWithoutSecrets() {
        val cipher = "CIPHER-TOKEN-XYZ"
        val parsed = FinalShellCatalog.parse(listOf(connection(password = cipher), group(), mapOf("啥都没有" to 1), "不是对象"))
        val row = parsed.connections.single()
        assertEquals("Hytron", row.name)
        assertEquals("203.0.113.7", row.host)
        assertEquals(50035, row.port)
        assertEquals("root", row.username)
        assertEquals("utf-8", row.charset)
        assertEquals("香港节点", row.note)
        assertEquals("group-1", row.groupId)
        assertEquals(EndpointKey("203.0.113.7", 50035, "root"), row.endpoint)
        assertFalse(row.monitorEnabled)
        assertEquals("生产", parsed.groups.single().name)
        assertNull(parsed.groups.single().parentId)
        assertEquals(2, parsed.invalidCount)
        assertEquals(listOf("conn-1"), parsed.ignored.passwordIds)
        assertEquals(listOf("conn-1"), parsed.ignored.privateKeyIds)
        assertEquals(listOf("conn-1"), parsed.ignored.proxyIds)
        assertFalse(parsed.toString().contains(cipher))
    }

    @Test
    fun missingUsernameBecomesRootAndUnknownCharsetFallsBack() {
        val parsed = FinalShellCatalog.parse(
            listOf(
                connection(username = null, encoding = "EUC-JP", password = null, key = null, proxy = null),
                connection(id = "gbk", encoding = "GB2312", username = "alice")
            )
        )
        assertEquals("root", parsed.connections.first().username)
        assertEquals("utf-8", parsed.connections.first().charset)
        assertEquals("EUC-JP", parsed.connections.first().unknownCharset)
        assertEquals(listOf(UnknownCharset("conn-1", "EUC-JP")), parsed.unknownCharsets.filter { it.sourceId == "conn-1" })
        assertEquals("gbk", parsed.connections.single { it.sourceId == "gbk" }.charset)
        assertNull(parsed.connections.single { it.sourceId == "gbk" }.unknownCharset)
        assertEquals(EndpointKey("203.0.113.7", 50035, "root"), parsed.connections.first().endpoint)
    }

    @Test
    fun unsupportedRecordsAreCountedAndNotImported() {
        val parsed = FinalShellCatalog.parse(
            listOf(
                connection(type = 200, forwards = listOf("local"), remote = mapOf("r" to 1)),
                connection(id = "alias", type = null, typeKey = "connection_type", usernameKey = "userName", username = "bob"),
                connection(id = "bad-port", port = 22.5)
            )
        )
        assertEquals(1, parsed.unsupportedCount)
        assertTrue(parsed.unsupported.single().detail.contains("不是 SSH"))
        assertEquals("bob", parsed.connections.single().username)
        assertEquals(1, parsed.invalidCount)
        assertEquals(listOf(ForwardNote("conn-1", 2)), parsed.ignored.forwards)
        assertTrue(parsed.connections.none { it.sourceId == "conn-1" })
    }

    @Test
    fun groupsRelinkAndEndpointConflictsUseHostPortUsername() {
        val child = group(id = "child", name = "子组", parent = "parent")
        val parent = group(id = "parent", name = "父组", parent = "")
        val first = connection(id = "a", parent = "child")
        val second = connection(id = "b", parent = "child")
        val orphan = connection(id = "c", parent = "missing-group")
        val parsed = FinalShellCatalog.parse(listOf(child, parent, first, second, orphan))
        val plan = FinalShellCatalog.plan(parsed, emptySet(), EndpointConflict.Skip) { "n:$it" }
        val parentRow = plan.groups.single { it.sourceId == "parent" }
        val childRow = plan.groups.single { it.sourceId == "child" }
        assertNull(parentRow.parentId)
        assertEquals(parentRow.targetId, childRow.parentId)
        assertEquals(childRow.targetId, plan.connections.single { it.sourceId == "a" }.groupId)
        assertEquals(3, plan.connections.count { it.endpoint == EndpointKey("203.0.113.7", 50035, "root") })
        assertNull(plan.connections.single { it.sourceId == "c" }.groupId)
        assertTrue(plan.orphanGroupRefs.contains("c"))
        assertTrue(plan.connections.all { !it.monitorEnabled })
        assertThrows(IllegalArgumentException::class.java) {
            plan.connections.first().copy(monitorEnabled = true)
        }

        val skip = FinalShellCatalog.plan(parsed, setOf(EndpointKey("203.0.113.7", 50035, "root")), EndpointConflict.Skip) { "n:$it" }
        assertTrue(skip.connections.isEmpty())
        assertEquals(3, skip.skipped.size)

        val saved = FinalShellCatalog.plan(parsed, setOf(EndpointKey("203.0.113.7", 50035, "root")), EndpointConflict.SaveAs) { "n:$it" }
        assertEquals(setOf("n:a", "n:b", "n:c"), saved.connections.map { it.targetId }.toSet())
        assertTrue(saved.skipped.isEmpty())
    }

    private fun connection(
        id: String = "conn-1",
        username: String? = "root",
        usernameKey: String = "user_name",
        encoding: String? = "UTF-8",
        password: String? = "CIPHER-TOKEN-XYZ",
        key: String? = "key-1",
        proxy: String? = "proxy-1",
        type: Int? = FinalShellCatalog.SSH_TYPE,
        typeKey: String = "conection_type",
        port: Any? = 50035,
        parent: String? = "group-1",
        forwards: List<String> = emptyList(),
        remote: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = buildMap {
        put("id", id)
        put("name", "Hytron")
        put("host", "203.0.113.7")
        put("port", port)
        if (username != null) put(usernameKey, username)
        if (encoding != null) put("terminal_encoding", encoding)
        put("description", "香港节点")
        if (parent != null) put("parent_id", parent)
        if (type != null) put(typeKey, type)
        if (password != null) put("password", password)
        if (key != null) put("secret_key_id", key)
        if (proxy != null) put("proxy_id", proxy)
        put("port_forwarding_list", forwards)
        put("remote_port_forwarding", remote)
    }

    private fun group(id: String = "group-1", name: String = "生产", parent: String? = ""): Map<String, Any?> = buildMap {
        put("id", id)
        put("name", name)
        if (parent != null) put("parent_id", parent)
    }
}
