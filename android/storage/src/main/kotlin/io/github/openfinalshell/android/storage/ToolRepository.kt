package io.github.openfinalshell.android.storage

import androidx.room.*
import io.github.openfinalshell.android.core.ai.AiClient
import io.github.openfinalshell.android.core.ai.AiProfile
import io.github.openfinalshell.android.core.ai.AiFailure
import io.github.openfinalshell.android.core.termops.CommandHistoryEntry
import io.github.openfinalshell.android.core.termops.CommandHistoryIndex
import java.util.UUID

@Entity(tableName = "ai_profiles")
data class AiProfileEntity(@PrimaryKey val id: String, val name: String, val baseUrl: String,
    val model: String, val enabled: Boolean, val tokenRef: String?, val updatedAt: Long)
@Entity(tableName = "command_groups")
data class CommandGroupEntity(@PrimaryKey val id: String, val name: String, val sortOrder: Int = 0)
@Entity(tableName = "command_snippets")
data class CommandSnippetEntity(@PrimaryKey val id: String, val name: String, val content: String,
    val groupId: String?, val updatedAt: Long)
@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey val id: String,
    val secretRef: String,
    val createdAt: Long,
    val useCount: Int = 1,
    val lastSubmittedAt: Long = createdAt
)

data class CommandHistoryRecord(val id: String, val text: String, val useCount: Int, val lastSubmittedAt: Long)

@Dao
interface ToolDao {
    @Query("SELECT * FROM ai_profiles ORDER BY name") suspend fun profiles(): List<AiProfileEntity>
    @Query("SELECT * FROM ai_profiles WHERE id = :id") suspend fun profile(id: String): AiProfileEntity?
    @Upsert suspend fun save(profile: AiProfileEntity)
    @Query("DELETE FROM ai_profiles WHERE id = :id") suspend fun deleteProfile(id: String)
    @Query("SELECT * FROM command_snippets ORDER BY name") suspend fun snippets(): List<CommandSnippetEntity>
    @Upsert suspend fun save(snippet: CommandSnippetEntity)
    @Query("DELETE FROM command_snippets WHERE id = :id") suspend fun deleteSnippet(id: String)
    @Query("SELECT * FROM command_groups ORDER BY sortOrder,name") suspend fun groups(): List<CommandGroupEntity>
    @Upsert suspend fun save(group: CommandGroupEntity)
    @Query("DELETE FROM command_groups WHERE id = :id") suspend fun deleteGroup(id: String)
    @Query("UPDATE command_snippets SET groupId = NULL WHERE groupId = :id") suspend fun ungroup(id: String)
    @Query("SELECT * FROM command_history ORDER BY lastSubmittedAt DESC") suspend fun history(): List<CommandHistoryEntity>
    @Upsert suspend fun save(history: CommandHistoryEntity)
    @Query("DELETE FROM command_history WHERE id = :id") suspend fun deleteHistory(id: String)
}

/** Service-only repository. AI records never participate in PortableExport's credential graph. */
class ToolRepository(private val db: AppDatabase, private val credentials: AndroidCredentialStore) {
    companion object {
        /** Same document the assistant checkbox already writes. Only the literal "true" enables recording. */
        const val HISTORY_ENABLED_DOCUMENT = "tools.history.enabled"
    }
    private val dao = db.tools()
    private fun public(row: AiProfileEntity) = AiProfile(row.id,row.name,row.baseUrl,row.model,row.enabled,row.tokenRef != null,row.updatedAt)
    suspend fun profiles(): List<AiProfile> = dao.profiles().map(::public)
    suspend fun saveProfile(old: AiProfile?, name: String, baseUrl: String, model: String, token: String?, clear: Boolean = false): AiProfile = db.withTransaction {
        require(name.isNotBlank() && name.length <= 100 && model.isNotBlank() && model.length <= 200)
        val url = AiClient.normalizeUrl(baseUrl)
        val current = old?.id?.let { dao.profile(it) }
        if (old != null && current?.updatedAt != old.updatedAt) throw AiFailure("conflict")
        if (old == null && dao.profiles().size >= 100) throw AiFailure("profile_limit")
        if (!token.isNullOrBlank() && (token.trim().length > 16384 || token.trim().any { it !in '!'..'~' })) throw AiFailure("invalid_token")
        val id = old?.id ?: UUID.randomUUID().toString()
        var reference = current?.tokenRef
        if (clear) { reference?.let { credentials.delete(it) }; reference = null }
        if (!token.isNullOrBlank()) reference = credentials.put(token.trim(), reference ?: UUID.randomUUID().toString())
        val now = maxOf(System.currentTimeMillis(), (current?.updatedAt ?: 0) + 1)
        val row = AiProfileEntity(id,name.trim(),url,model.trim(),true,reference,now)
        dao.save(row); public(row)
    }
    suspend fun resolve(id: String): Pair<AiProfile,String> = db.withTransaction {
        val row = dao.profile(id) ?: throw AiFailure("missing_profile")
        public(row) to (row.tokenRef?.let { credentials.get(it) } ?: "")
    }
    suspend fun deleteProfile(id: String) = db.withTransaction {
        dao.profile(id)?.tokenRef?.let { credentials.delete(it) }; dao.deleteProfile(id)
    }
    suspend fun snippets() = dao.snippets()
    suspend fun groups() = dao.groups()
    suspend fun saveSnippet(id: String?, name: String, command: String, groupId: String?) {
        require(name.isNotBlank() && name.length <= 200 && command.isNotBlank() && command.length <= 32768)
        require(!command.contains('\u0000'))
        dao.save(CommandSnippetEntity(id ?: UUID.randomUUID().toString(),name,command,groupId,System.currentTimeMillis()))
    }
    suspend fun deleteSnippet(id: String) = dao.deleteSnippet(id)
    suspend fun saveGroup(id: String?, name: String) {
        require(name.isNotBlank() && name.length <= 200)
        dao.save(CommandGroupEntity(id ?: UUID.randomUUID().toString(),name))
    }
    suspend fun deleteGroup(id: String) = db.withTransaction { dao.ungroup(id); dao.deleteGroup(id) }
    suspend fun history(): List<Pair<String,String>> = dao.history().mapNotNull { row ->
        credentials.get(row.secretRef)?.let { row.id to it }
    }
    suspend fun historyRecords(): List<CommandHistoryRecord> = dao.history().mapNotNull { row ->
        credentials.get(row.secretRef)?.let { CommandHistoryRecord(row.id, it, row.useCount, row.lastSubmittedAt) }
    }
    suspend fun recordCommand(command: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        val rows = dao.history()
        val loaded = rows.mapNotNull { row ->
            credentials.get(row.secretRef)?.let { text ->
                CommandHistoryEntry(row.id, text, row.useCount, row.lastSubmittedAt, row.createdAt)
            }
        }
        val readable = loaded.map { it.id }.toSet()
        val index = CommandHistoryIndex(loaded)
        val saved = index.record(command, now)
        val keep = index.entries().associateBy { it.id }
        keep[saved.id]?.let { entry ->
            val existing = rows.find { it.id == entry.id }
            if (existing != null) dao.save(existing.copy(useCount = entry.useCount, lastSubmittedAt = entry.lastSubmittedAt))
            else dao.save(CommandHistoryEntity(entry.id, credentials.put(entry.text), entry.createdAt, entry.useCount, entry.lastSubmittedAt))
        }
        for (row in rows) if (row.id in readable && row.id !in keep) {
            credentials.delete(row.secretRef)
            dao.deleteHistory(row.id)
        }
    }
    suspend fun clearHistory() = db.withTransaction {
        for (row in dao.history()) { credentials.delete(row.secretRef); dao.deleteHistory(row.id) }
    }
}
