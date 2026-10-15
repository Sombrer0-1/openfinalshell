package io.github.openfinalshell.android.core.workspace

data class EndpointKey(val host: String, val port: Int, val username: String)

enum class EndpointConflict { Skip, SaveAs }

data class CatalogIssue(val index: Int, val sourceId: String?, val detail: String)
data class UnknownCharset(val sourceId: String, val raw: String)
data class ForwardNote(val sourceId: String, val count: Int)
data class IgnoredMaterial(
    val passwordIds: List<String>,
    val privateKeyIds: List<String>,
    val proxyIds: List<String>,
    val forwards: List<ForwardNote>
)

data class CatalogGroup(val sourceId: String, val name: String, val parentId: String?)

data class CatalogConnection(
    val sourceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val charset: String,
    val unknownCharset: String?,
    val note: String?,
    val groupId: String?,
    val endpoint: EndpointKey,
    val monitorEnabled: Boolean = false
) {
    init {
        require(!monitorEnabled) { "Android imports FinalShell with monitorEnabled false" }
    }
}

data class ParsedFinalShell(
    val connections: List<CatalogConnection>,
    val groups: List<CatalogGroup>,
    val invalid: List<CatalogIssue>,
    val unsupported: List<CatalogIssue>,
    val unknownCharsets: List<UnknownCharset>,
    val ignored: IgnoredMaterial
) {
    val invalidCount: Int get() = invalid.size
    val unsupportedCount: Int get() = unsupported.size
    val unknownCharsetCount: Int get() = unknownCharsets.size
}

data class PlannedFinalShellGroup(
    val sourceId: String,
    val targetId: String,
    val name: String,
    val parentId: String?
)

data class PlannedFinalShellConnection(
    val sourceId: String,
    val targetId: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val charset: String,
    val unknownCharset: String?,
    val note: String?,
    val groupId: String?,
    val endpoint: EndpointKey,
    val monitorEnabled: Boolean = false
) {
    init {
        require(!monitorEnabled) { "Android imports FinalShell with monitorEnabled false" }
    }
}

data class FinalShellPlan(
    val groups: List<PlannedFinalShellGroup>,
    val connections: List<PlannedFinalShellConnection>,
    val skipped: List<CatalogConnection>,
    val orphanGroupRefs: List<String>
)

/**
 * FinalShell `conn` records. Passwords, private keys, proxies and port forwards are counted and
 * then dropped. An unknown terminal encoding is stored as the original string and the connection
 * uses UTF-8. A missing username becomes `root`. Duplicate identity is host + port + username.
 */
object FinalShellCatalog {
    const val SSH_TYPE = 100

    fun parse(records: List<Any?>): ParsedFinalShell {
        val connections = mutableListOf<CatalogConnection>()
        val groups = mutableListOf<CatalogGroup>()
        val invalid = mutableListOf<CatalogIssue>()
        val unsupported = mutableListOf<CatalogIssue>()
        val unknownCharsets = mutableListOf<UnknownCharset>()
        val passwords = mutableListOf<String>()
        val privateKeys = mutableListOf<String>()
        val proxies = mutableListOf<String>()
        val forwards = mutableListOf<ForwardNote>()
        records.forEachIndexed { index, raw ->
            val map = stringKeyMap(raw)
            if (map == null) {
                invalid += CatalogIssue(index, null, "不是对象")
                return@forEachIndexed
            }
            val id = text(map["id"])
            val name = text(map["name"])
            if (id == null || name == null) {
                invalid += CatalogIssue(index, id, "缺少 id 或名称")
                return@forEachIndexed
            }
            val charset = mapCharset(map["terminal_encoding"])
            if (charset.unknown != null) unknownCharsets += UnknownCharset(id, charset.unknown)
            val host = text(map["host"])
            val port = integer(map["port"])
            if (host == null || port == null) {
                if (host != null && map["port"] != null) {
                    invalid += CatalogIssue(index, id, "端口无效")
                } else {
                    groups += CatalogGroup(id, name, text(map["parent_id"]))
                }
                return@forEachIndexed
            }
            if (text(map["password"]) != null) passwords += id
            if (text(map["secret_key_id"]) != null) privateKeys += id
            if (text(map["proxy_id"]) != null) proxies += id
            val forwardCount = forwardCount(map)
            if (forwardCount > 0) forwards += ForwardNote(id, forwardCount)
            val type = integer(map["conection_type"] ?: map["connection_type"])
            if (type != null && type != SSH_TYPE) {
                unsupported += CatalogIssue(index, id, "$name 不是 SSH 连接")
                return@forEachIndexed
            }
            val username = text(map["user_name"]) ?: text(map["userName"]) ?: "root"
            connections += CatalogConnection(
                sourceId = id,
                name = name,
                host = host,
                port = port,
                username = username,
                charset = charset.value,
                unknownCharset = charset.unknown,
                note = text(map["description"]),
                groupId = text(map["parent_id"]),
                endpoint = EndpointKey(host, port, username),
                monitorEnabled = false
            )
        }
        return ParsedFinalShell(
            connections,
            groups,
            invalid,
            unsupported,
            unknownCharsets,
            IgnoredMaterial(passwords, privateKeys, proxies, forwards)
        )
    }

    /**
     * Assigns executor ids, rewrites group parent links, and applies the endpoint conflict.
     * Records already stored under the same host, port and username are skipped or saved as new
     * rows. Two records in this file do not suppress each other.
     */
    fun plan(
        parsed: ParsedFinalShell,
        existing: Set<EndpointKey>,
        conflict: EndpointConflict,
        newId: (String) -> String
    ): FinalShellPlan {
        val groupIds = parsed.groups.associate { it.sourceId to newId(it.sourceId) }
        val orphans = mutableListOf<String>()
        val groups = parsed.groups.map { group ->
            val parent = group.parentId?.let { source ->
                groupIds[source] ?: run {
                    orphans += group.sourceId
                    null
                }
            }
            PlannedFinalShellGroup(group.sourceId, groupIds.getValue(group.sourceId), group.name, parent)
        }
        val skipped = mutableListOf<CatalogConnection>()
        val connections = mutableListOf<PlannedFinalShellConnection>()
        for (connection in parsed.connections) {
            if (connection.endpoint in existing && conflict == EndpointConflict.Skip) {
                skipped += connection
                continue
            }
            val groupId = connection.groupId?.let { source ->
                groupIds[source] ?: run {
                    orphans += connection.sourceId
                    null
                }
            }
            connections += PlannedFinalShellConnection(
                sourceId = connection.sourceId,
                targetId = newId(connection.sourceId),
                name = connection.name,
                host = connection.host,
                port = connection.port,
                username = connection.username,
                charset = connection.charset,
                unknownCharset = connection.unknownCharset,
                note = connection.note,
                groupId = groupId,
                endpoint = connection.endpoint,
                monitorEnabled = false
            )
        }
        return FinalShellPlan(groups, connections, skipped, orphans)
    }

    private data class CharsetMapping(val value: String, val unknown: String?)

    private fun mapCharset(raw: Any?): CharsetMapping {
        val value = text(raw) ?: return CharsetMapping("utf-8", null)
        val normalized = value.lowercase().replace(Regex("[\\s_]"), "")
        val table = mapOf(
            "utf-8" to "utf-8",
            "utf8" to "utf-8",
            "gbk" to "gbk",
            "gb2312" to "gbk",
            "gb18030" to "gb18030",
            "big5" to "big5",
            "iso-8859-1" to "latin1",
            "iso88591" to "latin1",
            "latin1" to "latin1"
        )
        val hit = table[normalized] ?: table[value.lowercase()]
        return if (hit != null) CharsetMapping(hit, null) else CharsetMapping("utf-8", value)
    }

    private fun stringKeyMap(raw: Any?): Map<String, Any?>? {
        val map = raw as? Map<*, *> ?: return null
        if (map.keys.any { it !is String }) return null
        @Suppress("UNCHECKED_CAST")
        return map as Map<String, Any?>
    }

    private fun text(value: Any?): String? = (value as? String)?.trim()?.ifEmpty { null }

    private fun integer(value: Any?): Int? {
        val number = value as? Number ?: return null
        val asDouble = number.toDouble()
        if (!asDouble.isFinite() || asDouble % 1.0 != 0.0) return null
        if (asDouble < Int.MIN_VALUE || asDouble > Int.MAX_VALUE) return null
        return asDouble.toInt()
    }

    private fun forwardCount(map: Map<String, Any?>): Int {
        val listed = (map["port_forwarding_list"] as? List<*>)?.size ?: 0
        val remote = (map["remote_port_forwarding"] as? Map<*, *>)?.size ?: 0
        return listed + remote
    }
}
