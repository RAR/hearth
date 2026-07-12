package com.rar.echodash.ui.model

import com.rar.echodash.config.SolarConfig
import com.rar.echodash.ha.EntityState
import java.util.Locale
import kotlin.math.abs

data class SolarNode(val label: String, val watts: String)

/** [gridImporting] null = no grid sensor (two-node flow). [todayLine] null = no `-today` sensors. */
data class SolarFlow(
    val pv: SolarNode?,
    val home: SolarNode?,
    val grid: SolarNode?,
    val gridImporting: Boolean?,
    val todayLine: String?,
)

fun solarFlow(cfg: SolarConfig, entities: Map<String, EntityState>): SolarFlow {
    fun get(id: String?): EntityState? = id?.let { entities[it] }

    val pv = get(cfg.pv)
    val load = get(cfg.load)
    val grid = get(cfg.grid)
    val pvToday = get(cfg.pvToday)
    val loadToday = get(cfg.loadToday)

    val todayLine = buildString {
        pvToday?.let { append("${it.state} ${it.attr("unit_of_measurement") ?: "kWh"} produced") }
        loadToday?.let {
            if (isNotEmpty()) append(" · ")
            append("${it.state} ${it.attr("unit_of_measurement") ?: "kWh"} used")
        }
    }.takeIf { it.isNotEmpty() }?.let { "Today: $it" }

    val gridValue = grid?.state?.toDoubleOrNull()

    return SolarFlow(
        pv = pv?.let { SolarNode("Solar", formatWatts(it)) },
        home = load?.let { SolarNode("Home", formatWatts(it)) },
        grid = grid?.let { SolarNode("Grid", formatWatts(it)) },
        gridImporting = gridValue?.let { it >= 0 },
        todayLine = todayLine,
    )
}

/** Format a live power sensor by its own unit; W magnitudes >= 1000 roll up to kW (magnitude only). */
private fun formatWatts(state: EntityState): String {
    val unit = state.attr("unit_of_measurement") ?: "W"
    val v = state.state.toDoubleOrNull() ?: return "${state.state} $unit"
    val mag = abs(v)
    return when {
        unit.equals("kW", ignoreCase = true) -> String.format(Locale.US, "%.2f kW", mag)
        mag >= 1000 -> String.format(Locale.US, "%.1f kW", mag / 1000.0)
        else -> "${mag.toInt()} W"
    }
}
