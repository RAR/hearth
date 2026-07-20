package com.rar.hearth.ui.model

import com.rar.hearth.config.LightGroup as ConfigLightGroup
import com.rar.hearth.ha.EntityState
import com.rar.hearth.ha.RegistryIndex
import com.rar.hearth.ha.displayName

data class LightTile(
    val entityId: String,
    val name: String,
    val domain: String,
    val on: Boolean,
    val available: Boolean,
)

/** A section of light tiles; [title] is the configured group name. */
data class LightGroup(val title: String?, val tiles: List<LightTile>)

/** Build display sections from explicit configured groups, in configured order. */
fun lightSections(
    groups: List<ConfigLightGroup>,
    registry: RegistryIndex,
    entities: Map<String, EntityState>,
): List<LightGroup> =
    groups.map { g ->
        LightGroup(
            title = g.name,
            tiles = g.entities.map { id ->
                val state = entities[id]
                val s = state?.state
                LightTile(
                    entityId = id,
                    name = registry.displayName(id, state),
                    domain = id.substringBefore('.'),
                    on = s == "on",
                    available = s != null && s != "unavailable" && s != "unknown",
                )
            },
        )
    }
