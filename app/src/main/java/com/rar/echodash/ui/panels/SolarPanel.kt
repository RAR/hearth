package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.SolarFlow
import com.rar.echodash.ui.model.SolarNode

@Composable
fun SolarPanel(flow: SolarFlow) {
    PanelSurface {
        if (flow.pv == null && flow.home == null) {
            EmptyHint("Label solar sensors with `echo-solar-pv` / `echo-solar-load` in Home Assistant")
            return@PanelSurface
        }
        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                flow.pv?.let { Node(it, Color(0xFFE0A030)) }
                Arrow("→")
                flow.home?.let { Node(it, Color(0xFF3A6EA5)) }
                if (flow.grid != null) {
                    Arrow(if (flow.gridImporting == true) "←" else "→")
                    Node(flow.grid, Color(0xFF6B7280))
                }
            }
            flow.todayLine?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp) }
        }
    }
}

@Composable
private fun Node(node: SolarNode, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(96.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) { Text(node.label, color = Color.White, fontSize = 16.sp) }
        Text(node.watts, color = Color.White, fontSize = 18.sp)
    }
}

@Composable
private fun Arrow(glyph: String) {
    Text(glyph, color = Color.White.copy(alpha = 0.8f), fontSize = 32.sp)
}
