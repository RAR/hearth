package com.rar.echodash.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** Build the index from a config/entity_registry/list result array. */
fun parseEntityRegistry(result: JsonElement): RegistryIndex {
    val names = LinkedHashMap<String, String>()
    val all = ArrayList<RegistryEntity>()
    for (el in result.jsonArray) {
        val obj = el.jsonObject
        val id = (obj["entity_id"] as? JsonPrimitive)?.contentOrNull ?: continue
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (obj["original_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (name != null) names[id] = name
        all += RegistryEntity(id = id, name = name, domain = id.substringBefore('.'))
    }
    return RegistryIndex(names, all)
}
