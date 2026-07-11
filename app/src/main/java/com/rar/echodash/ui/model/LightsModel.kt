package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ha.displayName
import java.util.Locale

data class LightTile(
    val entityId: String,
    val name: String,
    val domain: String,
    val on: Boolean,
    val available: Boolean,
)

/** A section of light tiles. [title] null = the ungrouped bare `echo-lights` section (listed first). */
data class LightGroup(val title: String?, val tiles: List<LightTile>)

private const val LIGHTS_LABEL = "echo-lights"
private const val LIGHTS_PREFIX = "echo-lights-"

fun buildLightGroups(registry: RegistryIndex, entities: Map<String, EntityState>): List<LightGroup> {
    fun tilesFor(label: String): List<LightTile> =
        registry.labelToEntities[label].orEmpty().map { id ->
            val state = entities[id]
            val s = state?.state
            LightTile(
                entityId = id,
                name = registry.displayName(id, state),
                domain = id.substringBefore('.'),
                on = s == "on",
                available = s != null && s != "unavailable" && s != "unknown",
            )
        }

    val out = mutableListOf<LightGroup>()
    registry.labelToEntities[LIGHTS_LABEL]?.let {
        out += LightGroup(title = null, tiles = tilesFor(LIGHTS_LABEL))
    }
    registry.labelToEntities.keys
        .filter { it.startsWith(LIGHTS_PREFIX) && it.length > LIGHTS_PREFIX.length }
        .map { it to titleCase(it.removePrefix(LIGHTS_PREFIX)) }
        .sortedBy { it.second.lowercase(Locale.getDefault()) }
        .forEach { (label, title) -> out += LightGroup(title = title, tiles = tilesFor(label)) }
    return out
}

private fun titleCase(slug: String): String =
    slug.split('-', '_').filter { it.isNotEmpty() }.joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
