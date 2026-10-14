package com.rar.hearth.ha

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One entity's live state from subscribe_entities. [attributes] is HA's raw attribute object. */
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastUpdatedMs: Long,
) {
    fun attr(key: String): String? = (attributes[key] as? JsonPrimitive)?.contentOrNull
    fun attrDouble(key: String): Double? = (attributes[key] as? JsonPrimitive)?.doubleOrNull
    fun attrStringList(key: String): List<String> =
        (attributes[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}

/** One registry entity for the web picker: id, display name (nullable), and domain. */
data class RegistryEntity(val id: String, val name: String?, val domain: String)

/**
 * Registry index for the web picker and display names. [registryNames] maps entity id -> display name
 * and [allEntities] is EVERY registry entity so the config page can list and name them.
 */
data class RegistryIndex(
    val registryNames: Map<String, String>,
    val allEntities: List<RegistryEntity> = emptyList(),
)

/** Display name: registry name/original_name, else live friendly_name, else the entity id. */
fun RegistryIndex.displayName(entityId: String, state: EntityState?): String =
    registryNames[entityId] ?: state?.attr("friendly_name") ?: entityId

@Serializable
private data class RegistryFrame(val result: List<RegistryRow>? = null)

@Serializable
private data class RegistryRow(
    @SerialName("entity_id") val entityId: String? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
)

private val registryJson = Json { ignoreUnknownKeys = true }

/**
 * Build the index from a raw config/entity_registry/list result frame ([HaClient.requestRaw]),
 * decoding only the three fields used. Null when the frame is malformed or carries no result.
 *
 * Typed rather than via a JsonElement tree: a 14k-entity registry is a 10 MB frame, and its tree
 * on an 80 MB Echo Show 5 heap was enough on its own to push the app into a GC-thrash crash.
 */
fun parseEntityRegistryFrame(text: String): RegistryIndex? {
    val rows = runCatching { registryJson.decodeFromString<RegistryFrame>(text) }.getOrNull()?.result
        ?: return null
    val names = LinkedHashMap<String, String>()
    val all = ArrayList<RegistryEntity>(rows.size)
    for (row in rows) {
        val id = row.entityId ?: continue
        val name = row.name?.takeIf { it.isNotBlank() } ?: row.originalName?.takeIf { it.isNotBlank() }
        if (name != null) names[id] = name
        all += RegistryEntity(id = id, name = name, domain = id.substringBefore('.'))
    }
    return RegistryIndex(names, all)
}

/** Build the index from an already-parsed config/entity_registry/list result array. */
fun parseEntityRegistry(result: JsonElement): RegistryIndex =
    parseEntityRegistryFrame(buildJsonObject { put("result", result) }.toString()) ?: RegistryIndex(emptyMap())
