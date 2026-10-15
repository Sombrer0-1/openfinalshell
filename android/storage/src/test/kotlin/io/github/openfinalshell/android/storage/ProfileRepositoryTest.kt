package io.github.openfinalshell.android.storage

import io.github.openfinalshell.android.core.model.ConnectionAuth
import io.github.openfinalshell.android.core.model.ConnectionProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileRepositoryTest {
    @Test
    fun `duplicate import keeps generated id in serialized profile`() = runBlocking {
        val dao = InMemoryProfileDao()
        val repository = ProfileRepository(dao)
        val source = ConnectionProfile(
            id = "source",
            name = "server",
            host = "example.test",
            username = "root",
            auth = ConnectionAuth(method = "password")
        )

        repository.upsertImported(
            kotlinx.serialization.json.JsonObject(
                kotlinx.serialization.json.buildJsonObject {
                    put("id", source.id)
                    put("name", source.name)
                    put("host", source.host)
                    put("port", source.port)
                    put("username", source.username)
                    put("auth", kotlinx.serialization.json.buildJsonObject { put("method", "password") })
                }
            ),
            idOverride = "duplicate"
        )

        assertEquals("duplicate", dao.find("duplicate")?.id)
        assertEquals("duplicate", repository.list().single().id)
    }

    @Test
    fun `last used time is stored in the column and the serialized profile`() = runBlocking {
        val dao = InMemoryProfileDao()
        val repository = ProfileRepository(dao)
        val profile = ConnectionProfile(
            id = "server",
            name = "server",
            host = "example.test",
            username = "root",
            auth = ConnectionAuth(method = "password"),
            lastUsedAt = 10L
        )
        repository.upsert(profile)
        assertEquals(10L, dao.find("server")?.lastUsedAt)
        repository.touchLastUsed("server", 42L)
        assertEquals(42L, dao.find("server")?.lastUsedAt)
        assertEquals(42L, repository.find("server")?.lastUsedAt)
    }

    @Test
    fun `saving preserves unknown imported fields`() = runBlocking {
        val dao = InMemoryProfileDao()
        val repository = ProfileRepository(dao)
        repository.upsertImported(
            kotlinx.serialization.json.buildJsonObject {
                put("id", "server")
                put("name", "server")
                put("host", "old.example")
                put("port", 22)
                put("username", "root")
                put("auth", kotlinx.serialization.json.buildJsonObject { put("method", "password") })
                put("desktopOnly", "keep-me")
                put("terminal", kotlinx.serialization.json.buildJsonObject {
                    put("charset", "gbk")
                    put("termType", "xterm")
                })
            }
        )
        val edited = repository.find("server")!!.copy(host = "new.example")
        repository.savePreserving("server", edited)
        val raw = dao.find("server")?.profileJson.orEmpty()
        org.junit.Assert.assertTrue(raw.contains("keep-me"))
        org.junit.Assert.assertTrue(raw.contains("gbk"))
        assertEquals("new.example", repository.find("server")?.host)
    }

    private class InMemoryProfileDao : ProfileDao {
        private val rows = linkedMapOf<String, ProfileEntity>()
        override suspend fun list(): List<ProfileEntity> = rows.values.toList()
        override suspend fun find(id: String): ProfileEntity? = rows[id]
        override suspend fun upsert(profile: ProfileEntity) { rows[profile.id] = profile }
        override suspend fun delete(id: String) { rows.remove(id) }
    }
}
