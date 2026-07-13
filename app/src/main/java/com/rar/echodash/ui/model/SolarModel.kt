package com.rar.echodash.ui.model

import com.rar.echodash.config.SolarConfig
import com.rar.echodash.ha.EntityState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** Home solar/battery pill card. Null when no solar entities resolve (card hidden). */
data class SolarCard(
    val pvText: String?,       // formatted PV output for the header; null = no pv sensor
    val socPct: Int?,          // battery SOC 0-100; null = no gauge
    val battCharging: Boolean, // battery power below -CHARGE_DEADBAND_W (evcc: negative = charging)
    val statsLine: String?,    // "Home 1.4 kW · Export 1.8 kW"; null when empty
)

private const val CHARGE_DEADBAND_W = 50.0

fun solarCard(cfg: SolarConfig, entities: Map<String, EntityState>): SolarCard? {
    fun get(id: String?): EntityState? = id?.let { entities[it] }
    val pv = get(cfg.pv)
    val load = get(cfg.load)
    val grid = get(cfg.grid)
    val soc = get(cfg.battSoc)
    if (pv == null && load == null && grid == null && soc == null) return null

    val gridValue = grid?.state?.toDoubleOrNull()
    val statsLine = buildList {
        load?.let { add("Home ${formatWatts(it)}") }
        if (grid != null && gridValue != null) {
            add((if (gridValue >= 0) "Import " else "Export ") + formatWatts(grid))
        }
    }.joinToString(" · ").takeIf { it.isNotEmpty() }

    val battWatts = get(cfg.battPower)?.let { powerWatts(it) }
    return SolarCard(
        pvText = pv?.let { formatWatts(it) },
        socPct = soc?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100),
        battCharging = battWatts != null && battWatts < -CHARGE_DEADBAND_W,
        statsLine = statsLine,
    )
}

/** Raw signed watts from a power sensor, respecting a kW unit; null if non-numeric. */
private fun powerWatts(state: EntityState): Double? {
    val v = state.state.toDoubleOrNull() ?: return null
    val unit = state.attr("unit_of_measurement") ?: "W"
    return if (unit.equals("kW", ignoreCase = true)) v * 1000 else v
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
