package com.rar.echodash.web

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ha.displayName
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Render the /api/entities payload: every registry entity as {id, name, domain, state}. State is the
 * last-known live state (or "unavailable" for entities not in the watched set). Pure — no Android.
 */
fun buildEntityListJson(registry: RegistryIndex, entities: Map<String, EntityState>): String =
    buildJsonArray {
        registry.allEntities.forEach { e ->
            add(buildJsonObject {
                put("id", e.id)
                put("name", registry.displayName(e.id, entities[e.id]))
                put("domain", e.domain)
                put("state", entities[e.id]?.state ?: "unavailable")
            })
        }
    }.toString()
