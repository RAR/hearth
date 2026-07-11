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

/** Label id (lowercased, echo-* only) -> entity ids, plus registry display names per entity. */
data class RegistryIndex(
    val labelToEntities: Map<String, List<String>>,
    val registryNames: Map<String, String>,
) {
    /** Every entity referenced by any echo-* label, first-seen order, de-duplicated. */
    val allEntityIds: List<String>
        get() = labelToEntities.values.flatten().distinct()
}

/** Display name: registry name/original_name, else live friendly_name, else the entity id. */
fun RegistryIndex.displayName(entityId: String, state: EntityState?): String =
    registryNames[entityId] ?: state?.attr("friendly_name") ?: entityId

/** Build the label index from a config/entity_registry/list result array. */
fun parseEntityRegistry(result: JsonElement): RegistryIndex {
    val labelToEntities = LinkedHashMap<String, MutableList<String>>()
    val names = LinkedHashMap<String, String>()
    for (el in result.jsonArray) {
        val obj = el.jsonObject
        val id = (obj["entity_id"] as? JsonPrimitive)?.contentOrNull ?: continue
        val labels = (obj["labels"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
            ?.filter { it.startsWith("echo-") }
            .orEmpty()
        if (labels.isEmpty()) continue
        for (label in labels) labelToEntities.getOrPut(label) { mutableListOf() }.add(id)
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (obj["original_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (name != null) names[id] = name
    }
    return RegistryIndex(labelToEntities, names)
}
