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
private data class RegistryFrame(val result: RegistryDisplay? = null)

@Serializable
private data class RegistryDisplay(val entities: List<RegistryRow> = emptyList())

/** One config/entity_registry/list_for_display row: `ei` entity id, `en` display name. */
@Serializable
private data class RegistryRow(val ei: String? = null, val en: String? = null)

private val registryJson = Json { ignoreUnknownKeys = true }

/**
 * Build the index from a raw config/entity_registry/list_for_display result frame
 * ([HaClient.requestRaw]). Null when the frame is malformed or carries no result.
 *
 * The display list rather than config/entity_registry/list, and typed rather than a JsonElement
 * tree: on this 14k-entity install the full list is a 10 MB frame (the display list 1.9 MB), and
 * parsing the full one as a tree on an 80 MB Echo Show 5 heap was enough on its own to crash the
 * app. `en` is HA's resolved name (registry name, else original_name) -- the same fallback this
 * used to compute -- and the display list omits disabled entities, which have no state to show.
 */
fun parseEntityRegistryFrame(text: String): RegistryIndex? {
    val rows = runCatching { registryJson.decodeFromString<RegistryFrame>(text) }.getOrNull()?.result?.entities
        ?: return null
    val names = LinkedHashMap<String, String>()
    val all = ArrayList<RegistryEntity>(rows.size)
    for (row in rows) {
        val id = row.ei ?: continue
        val name = row.en?.takeIf { it.isNotBlank() }
        if (name != null) names[id] = name
        all += RegistryEntity(id = id, name = name, domain = id.substringBefore('.'))
    }
    return RegistryIndex(names, all)
}

/** Build the index from an already-parsed config/entity_registry/list_for_display result. */
fun parseEntityRegistry(result: JsonElement): RegistryIndex =
    parseEntityRegistryFrame(buildJsonObject { put("result", result) }.toString()) ?: RegistryIndex(emptyMap())
