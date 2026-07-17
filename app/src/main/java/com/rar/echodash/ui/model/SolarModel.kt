package com.rar.echodash.ui.model

import com.rar.echodash.config.SolarConfig
import com.rar.echodash.ha.EntityState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class FlowNodeId { SOLAR, HOME, GRID, BATTERY }

/** One active power flow between two present nodes; watts always positive. */
data class FlowEdge(val from: FlowNodeId, val to: FlowNodeId, val watts: Double)

/** Everything the flow renderer needs. Nodes with null text (and socPct for battery) are absent
 *  from the diagram. [edges] holds ACTIVE flows only (> FLOW_DEADBAND_W); the renderer draws the
 *  full inactive line structure itself so the diagram's shape never jumps as flows start/stop. */
data class SolarFlowGraph(
    val solarText: String?,       // formatted PV watts; null = no pv sensor
    val homeText: String?,        // formatted load watts; null = no load sensor
    val gridText: String?,        // formatted |grid| watts; null = no numeric grid sensor
    val socPct: Int?,             // battery ring sweep; node present if socPct or battText
    val battText: String?,        // formatted |battery| watts, drawn below the node
    val battFlow: BattFlow,       // ring color language (existing enum, unchanged)
    val edges: List<FlowEdge>,
    val todayLine: String?,       // "Today: X kWh produced · Y kWh used"
    val gridTodayLine: String?,   // "↓ 3.2 kWh · ↑ 2.2 kWh" — panel-scale only
    val battTodayLine: String?,   // "↓ 5.1 kWh · ↑ 4.2 kWh" (charged/discharged) — panel-scale
    val arraysLine: String?,      // "A 447 W · B 768 W · C 395 W · D 276 W" — panel-scale
)

/**
 * Derive the flow diagram from the configured sensors. Null when no solar sensor resolves (same
 * rule as solarCard(): none of pv/load/grid/battSoc present — battPower alone never conjures a
 * diagram, matching the pill). Sensors give node totals only, so per-line flows are derived by
 * solar-first attribution; each edge is derived independently, so measurement noise or missing
 * sensors can make inflows != node totals (documented, not a bug).
 */
fun solarFlowGraph(cfg: SolarConfig, entities: Map<String, EntityState>): SolarFlowGraph? {
    fun get(id: String?): EntityState? = id?.let { entities[it] }
    val pv = get(cfg.pv)
    val load = get(cfg.load)
    val grid = get(cfg.grid)
    val soc = get(cfg.battSoc)
    if (pv == null && load == null && grid == null && soc == null) return null

    val battPower = get(cfg.battPower)
    val gridWatts = grid?.let { powerWatts(it) }
    val battWatts = battPower?.let { powerWatts(it) }
    val batteryPresent = soc != null || battPower != null

    // Node totals; absent sensors contribute 0. Sign conventions (existing): grid positive =
    // importing; battery positive = discharging (evcc: negative = charging). pv clamps to 0 to
    // drop inverter standby draw.
    val pvW = (pv?.let { powerWatts(it) } ?: 0.0).coerceAtLeast(0.0)
    val chargeW = (battWatts ?: 0.0).let { if (it < 0) -it else 0.0 }
    val dischargeW = (battWatts ?: 0.0).coerceAtLeast(0.0)
    val importW = (gridWatts ?: 0.0).coerceAtLeast(0.0)
    val exportW = (gridWatts ?: 0.0).let { if (it < 0) -it else 0.0 }

    val solarToBattery = minOf(pvW, chargeW)
    val solarToGrid = minOf(pvW - solarToBattery, exportW)
    val solarToHome = (pvW - solarToBattery - solarToGrid).coerceAtLeast(0.0)
    val batteryToHome = dischargeW
    val gridToBattery = (chargeW - solarToBattery).coerceAtLeast(0.0)
    val gridToHome = (importW - gridToBattery).coerceAtLeast(0.0)

    val solarP = pv != null
    val homeP = load != null
    val gridP = gridWatts != null
    val edges = buildList {
        fun edge(from: FlowNodeId, to: FlowNodeId, watts: Double, fromP: Boolean, toP: Boolean) {
            if (watts > FLOW_DEADBAND_W && fromP && toP) add(FlowEdge(from, to, watts))
        }
        edge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, solarToBattery, solarP, batteryPresent)
        edge(FlowNodeId.SOLAR, FlowNodeId.GRID, solarToGrid, solarP, gridP)
        edge(FlowNodeId.SOLAR, FlowNodeId.HOME, solarToHome, solarP, homeP)
        edge(FlowNodeId.BATTERY, FlowNodeId.HOME, batteryToHome, batteryPresent, homeP)
        edge(FlowNodeId.GRID, FlowNodeId.BATTERY, gridToBattery, gridP, batteryPresent)
        edge(FlowNodeId.GRID, FlowNodeId.HOME, gridToHome, gridP, homeP)
    }

    return SolarFlowGraph(
        solarText = pv?.let { formatWatts(it) },
        homeText = load?.let { formatWatts(it) },
        gridText = if (grid != null && gridWatts != null) formatWatts(grid) else null,
        socPct = soc?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100),
        battText = battPower?.let { formatWatts(it) },
        battFlow = when {
            battWatts == null -> BattFlow.IDLE
            battWatts < -CHARGE_DEADBAND_W -> BattFlow.CHARGING
            battWatts > CHARGE_DEADBAND_W -> BattFlow.DISCHARGING
            else -> BattFlow.IDLE
        },
        edges = edges,
        todayLine = todayLine(get(cfg.pvToday), get(cfg.loadToday)),
        gridTodayLine = arrowLine(get(cfg.gridImportToday), get(cfg.gridExportToday)),
        battTodayLine = arrowLine(get(cfg.battInToday), get(cfg.battOutToday)),
        arraysLine = arraysLine(cfg, entities),
    )
}

/** Lap time for a flow dot: 50 W → 4000 ms, 4000 W → 1200 ms, linear, clamped. */
fun flowLapMs(watts: Double): Int =
    (4000 - ((watts - 50.0) / 3950.0) * 2800.0).roundToInt().coerceIn(1200, 4000)

/** Non-numeric states (HA "unknown"/"unavailable" — e.g. a fresh utility_meter that hasn't
 *  ticked yet) must never render verbatim as "unknown kWh"; the line builders skip them. */
private fun EntityState.numericOrNull(): EntityState? = takeIf { state.toDoubleOrNull() != null }

/** "Today: X produced · Y used"; null when neither -today sensor resolves numerically. Each value
 *  keeps its own unit (default kWh). */
private fun todayLine(pvToday: EntityState?, loadToday: EntityState?): String? =
    buildString {
        pvToday?.numericOrNull()
            ?.let { append("${it.state} ${it.attr("unit_of_measurement") ?: "kWh"} produced") }
        loadToday?.numericOrNull()?.let {
            if (isNotEmpty()) append(" · ")
            append("${it.state} ${it.attr("unit_of_measurement") ?: "kWh"} used")
        }
    }.takeIf { it.isNotEmpty() }?.let { "Today: $it" }

/** "↓ {in} {unit} · ↑ {out} {unit}"; either sensor alone renders alone; null when neither
 *  resolves numerically. For the battery, ↓ = charged, ↑ = discharged. */
private fun arrowLine(inSensor: EntityState?, outSensor: EntityState?): String? =
    buildString {
        inSensor?.numericOrNull()?.let { append("↓ ${it.state} ${it.attr("unit_of_measurement") ?: "kWh"}") }
        outSensor?.numericOrNull()?.let {
            if (isNotEmpty()) append(" · ")
            append("↑ ${it.state} ${it.attr("unit_of_measurement") ?: "kWh"}")
        }
    }.takeIf { it.isNotEmpty() }

/** "{name} {watts}" for each array whose power sensor resolves numerically, joined by " · ";
 *  blank names fall back to A..D by slot index. formatWatts handles the Tigo sensors' lowercase
 *  "watts" unit as W. Null when no array power sensor resolves. */
private fun arraysLine(cfg: SolarConfig, entities: Map<String, EntityState>): String? =
    cfg.arrays.mapIndexedNotNull { i, a ->
        val s = a.power?.let { entities[it] }?.numericOrNull() ?: return@mapIndexedNotNull null
        val name = a.name.ifBlank { ('A' + i).toString() }
        "$name ${formatWatts(s)}"
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** Active battery flow, deadband-filtered: drives the gauge shimmer and its direction. */
enum class BattFlow { CHARGING, DISCHARGING, IDLE }

/** Home solar/battery pill card. Null when no solar entities resolve (card hidden). */
data class SolarCard(
    val pvText: String?,        // formatted PV output for the header; null = no pv sensor
    val socPct: Int?,           // battery SOC 0-100; null = no gauge
    val battFlow: BattFlow,     // |power| > CHARGE_DEADBAND_W; negative = charging (evcc convention)
    val battText: String?,      // battery power magnitude (formatted); null = no battPower sensor
    val homeText: String?,       // house load watts, rendered behind a house icon; null = no load sensor
    val gridText: String?,       // grid watts, rendered behind an import/export arrow; null = no numeric grid
    val gridImporting: Boolean?, // arrow: true = import (left), false = export (right), null = balanced
)

private const val CHARGE_DEADBAND_W = 50.0
private const val GRID_DEADBAND_W = 50.0
// A derived flow edge is drawn only above this; same 50 W floor as the pill's deadbands.
private const val FLOW_DEADBAND_W = 50.0

fun solarCard(cfg: SolarConfig, entities: Map<String, EntityState>): SolarCard? {
    fun get(id: String?): EntityState? = id?.let { entities[it] }
    val pv = get(cfg.pv)
    val load = get(cfg.load)
    val grid = get(cfg.grid)
    val soc = get(cfg.battSoc)
    if (pv == null && load == null && grid == null && soc == null) return null

    val gridWatts = grid?.let { powerWatts(it) }
    val battPower = get(cfg.battPower)
    val battWatts = battPower?.let { powerWatts(it) }
    return SolarCard(
        pvText = pv?.let { formatWatts(it) },
        socPct = soc?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100),
        battFlow = when {
            battWatts == null -> BattFlow.IDLE
            battWatts < -CHARGE_DEADBAND_W -> BattFlow.CHARGING
            battWatts > CHARGE_DEADBAND_W -> BattFlow.DISCHARGING
            else -> BattFlow.IDLE
        },
        battText = battPower?.let { formatWatts(it) },
        homeText = load?.let { formatWatts(it) },
        gridText = if (grid != null && gridWatts != null) formatWatts(grid) else null,
        gridImporting = gridWatts?.takeIf { abs(it) > GRID_DEADBAND_W }?.let { it >= 0 },
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
