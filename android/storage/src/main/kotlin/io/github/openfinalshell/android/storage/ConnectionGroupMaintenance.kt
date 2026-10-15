package io.github.openfinalshell.android.storage

import androidx.room.withTransaction
import io.github.openfinalshell.android.core.connection.GroupNode
import io.github.openfinalshell.android.core.connection.planGroupDelete
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Deletes one connection group in a single storage transaction. Direct connections move to the
 * ungrouped root. Child groups lift to the deleted group's parent. Nested connections stay
 * inside their child group.
 */
class ConnectionGroupMaintenance(private val database: AppDatabase) {
    suspend fun deleteAndLift(groupId: String) = database.withTransaction {
        val groupDao = database.groups()
        val profileDao = database.profiles()
        val groups = groupDao.list()
        val profiles = profileDao.list()
        val plan = planGroupDelete(
            groupId,
            groups.map { GroupNode(it.id, it.parentId) },
            profiles.associate { it.id to readGroupId(it) }
        ) ?: return@withTransaction
        profiles.filter { it.id in plan.directProfileIds.toSet() }.forEach { entity ->
            profileDao.upsert(rewriteGroupId(entity, plan.connectionGroupId))
        }
        groups.filter { it.id in plan.childGroupIds.toSet() }.forEach { child ->
            groupDao.upsert(child.copy(parentId = plan.parentId))
        }
        groupDao.delete(groupId)
    }
}

internal fun readGroupId(entity: ProfileEntity): String? {
    val json = entity.profileJson?.let { raw ->
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }
    val stored = json?.get("groupId")
    return when (stored) {
        null -> entity.groupId
        is JsonNull -> null
        is JsonPrimitive -> stored.contentOrNull
        else -> entity.groupId
    }
}

internal fun rewriteGroupId(entity: ProfileEntity, parentId: String?): ProfileEntity {
    val json = entity.profileJson?.let { raw ->
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }
    val patched = if (json == null) {
        entity.profileJson
    } else {
        val map = json.toMutableMap()
        map["groupId"] = if (parentId == null) JsonNull else JsonPrimitive(parentId)
        JsonObject(map).toString()
    }
    return entity.copy(groupId = parentId, profileJson = patched)
}
