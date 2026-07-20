package com.rar.hearth.ui.model

import com.rar.hearth.ha.EntityState
import com.rar.hearth.ha.RegistryIndex
import com.rar.hearth.ha.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ThermostatState(
    val entityId: String,
    val name: String,
    val current: Double?,
    val target: Double?,
    val minTemp: Double,
    val maxTemp: Double,
    val step: Double,
    val hvacAction: String?,
    val hvacModes: List<String>,
    val mode: String,
    val available: Boolean,
)

const val SETPOINT_STEP = 0.5

/** Build thermostats from the configured climate id list; non-`climate.*` ids are ignored. */
fun thermostats(
    ids: List<String>,
    registry: RegistryIndex,
    entities: Map<String, EntityState>,
    step: Double = SETPOINT_STEP,
): List<ThermostatState> =
    ids.filter { it.startsWith("climate.") }.map { id ->
        val s = entities[id]
        ThermostatState(
            entityId = id,
            name = registry.displayName(id, s),
            current = s?.attrDouble("current_temperature"),
            target = s?.attrDouble("temperature"),
            minTemp = s?.attrDouble("min_temp") ?: 7.0,
            maxTemp = s?.attrDouble("max_temp") ?: 35.0,
            step = step,
            hvacAction = s?.attr("hvac_action"),
            hvacModes = s?.attrStringList("hvac_modes") ?: emptyList(),
            mode = s?.state ?: "unknown",
            available = s != null && s.state != "unavailable" && s.state != "unknown",
        )
    }

/**
 * Accumulates rapid +/- setpoint taps and commits the final clamped target [debounceMs] after the
 * last tap, so five quick taps make one service call. Confined to [scope]'s dispatcher.
 */
class SetpointDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 800,
    private val onCommit: (Double) -> Unit,
) {
    private var target = 0.0
    private var min = 7.0
    private var max = 35.0
    private var step = SETPOINT_STEP
    private var job: Job? = null

    fun reset(current: Double, min: Double, max: Double, step: Double = SETPOINT_STEP) {
        this.target = current
        this.min = min
        this.max = max
        this.step = step
        job?.cancel(); job = null
    }

    fun nudge(direction: Int) {
        target = (target + direction * step).coerceIn(min, max)
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            onCommit(target)
        }
    }

    fun displayTarget(): Double = target

    fun cancel() { job?.cancel(); job = null }
}
