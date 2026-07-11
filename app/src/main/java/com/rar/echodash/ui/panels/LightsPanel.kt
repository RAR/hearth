package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.LightGroup
import com.rar.echodash.ui.model.LightTile

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightsPanel(groups: List<LightGroup>, connected: Boolean, onToggle: (String) -> Unit) {
    PanelSurface {
        if (groups.all { it.tiles.isEmpty() }) {
            EmptyHint("Label entities with `echo-lights` in Home Assistant")
            return@PanelSurface
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            groups.forEach { group ->
                group.title?.let { Text(it, color = Color.White, fontSize = 20.sp) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    group.tiles.forEach { tile -> LightTileView(tile, connected, onToggle) }
                }
            }
        }
    }
}

@Composable
private fun LightTileView(tile: LightTile, connected: Boolean, onToggle: (String) -> Unit) {
    val enabled = connected && tile.available
    val bg = if (tile.on) Color(0xFF3A6EA5) else Color(0xFF232733)
    val icon = when (tile.domain) {
        "switch" -> Icons.Outlined.Power
        "fan" -> Icons.Outlined.Cyclone
        else -> Icons.Outlined.Lightbulb
    }
    Column(
        Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled) { onToggle(tile.entityId) }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(tile.name, color = Color.White, fontSize = 16.sp)
        Text(if (tile.on) "On" else "Off", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
