package io.github.openfinalshell.android.core.connection

import io.github.openfinalshell.android.core.model.ConnectionProfile
import io.github.openfinalshell.android.core.protocol.ProtocolJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Overlay a saved profile onto its original JSON so desktop-only fields survive an Android edit.
 * Explicit nulls in the overlay clear a field; keys the Android model does not know stay put.
 */
object ProfileJsonMerge {
    private val explicit = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    fun merge(existing: JsonObject?, profile: ConnectionProfile): JsonObject {
        val overlay = explicit.encodeToJsonElement(ConnectionProfile.serializer(), profile).jsonObject
        if (existing == null) return overlay
        return deepMerge(existing, overlay) as JsonObject
    }

    private fun deepMerge(base: JsonElement, overlay: JsonElement): JsonElement {
        if (base !is JsonObject || overlay !is JsonObject) return overlay
        val merged = base.toMutableMap()
        overlay.forEach { (key, value) ->
            val previous = merged[key]
            merged[key] = if (previous == null) value else deepMerge(previous, value)
        }
        return JsonObject(merged)
    }

    fun parse(raw: String?): JsonObject? =
        raw?.let { runCatching { ProtocolJson.instance.parseToJsonElement(it) as? JsonObject }.getOrNull() }
}
