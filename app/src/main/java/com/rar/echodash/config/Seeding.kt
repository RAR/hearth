package com.rar.echodash.config

import com.rar.echodash.ha.RegistryIndex
import java.util.Locale

private const val LIGHTS_LABEL = "echo-lights"
private const val LIGHTS_PREFIX = "echo-lights-"

/**
 * Build the first DashConfig from current echo-* labels. Called once, when no config.json exists.
 * Mirrors the dashboard-shell grouping: bare `echo-lights` becomes a group named "Lights" (listed
 * first); each `echo-lights-<suffix>` becomes a title-cased group, ordered alphabetically by title.
 */
fun seedConfig(registry: RegistryIndex): DashConfig {
    val l = registry.labelToEntities
    fun first(label: String): String? = l[label]?.firstOrNull()

    val groups = buildList {
        l[LIGHTS_LABEL]?.let { add(LightGroup(name = "Lights", entities = it)) }
        l.keys
            .filter { it.startsWith(LIGHTS_PREFIX) && it.length > LIGHTS_PREFIX.length }
            .map { it to titleCase(it.removePrefix(LIGHTS_PREFIX)) }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
            .forEach { (label, title) -> add(LightGroup(name = title, entities = l[label].orEmpty())) }
    }

    return DashConfig(
        entities = Entities(
            tempSensor = first("echo-temp"),
            weather = first("echo-weather"),
            climate = l["echo-climate"].orEmpty().filter { it.startsWith("climate.") },
            solar = SolarConfig(
                pv = first("echo-solar-pv"),
                load = first("echo-solar-load"),
                grid = first("echo-solar-grid"),
                pvToday = first("echo-solar-pv-today"),
                loadToday = first("echo-solar-load-today"),
            ),
            lightGroups = groups,
        ),
    )
}

private fun titleCase(slug: String): String =
    slug.split('-', '_').filter { it.isNotEmpty() }.joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
