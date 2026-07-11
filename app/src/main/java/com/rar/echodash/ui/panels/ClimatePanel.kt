package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.rar.echodash.ui.model.SetpointDebouncer
import com.rar.echodash.ui.model.ThermostatState

@Composable
fun ClimatePanel(
    thermostats: List<ThermostatState>,
    connected: Boolean,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
) {
    PanelSurface {
        if (thermostats.isEmpty()) {
            EmptyHint("Label a thermostat with `echo-climate` in Home Assistant")
            return@PanelSurface
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            thermostats.forEach { t ->
                Thermostat(t, connected, onSetTemperature, onSetHvacMode, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Thermostat(
    t: ThermostatState,
    connected: Boolean,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var shown by remember(t.entityId) { mutableDoubleStateOf(t.target ?: t.minTemp) }
    val debouncer = remember(t.entityId) {
        SetpointDebouncer(scope) { onSetTemperature(t.entityId, it) }
    }
    // keep local display in sync with incoming target until the user starts nudging
    remember(t.target) { t.target?.let { debouncer.reset(it, t.minTemp, t.maxTemp, t.step); shown = it }; 0 }
    val enabled = connected && t.available

    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1B1F2A))
            .alpha(if (enabled) 1f else 0.5f)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.name, color = Color.White, fontSize = 20.sp)
        Text(t.current?.let { "${it}°" } ?: "--", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StepButton("−", enabled) { debouncer.nudge(-1); shown = debouncer.displayTarget() }
            Text("${shown}°", color = Color(0xFF7FB2FF), fontSize = 28.sp)
            StepButton("+", enabled) { debouncer.nudge(+1); shown = debouncer.displayTarget() }
        }
        Text(t.hvacAction ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            t.hvacModes.forEach { mode ->
                val active = mode == t.mode
                Text(
                    mode,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) Color(0xFF3A6EA5) else Color(0xFF232733))
                        .clickable(enabled = enabled) { onSetHvacMode(t.entityId, mode) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled) { onClick() },
    ) {
        Text(label, color = Color.White, fontSize = 28.sp)
    }
}
