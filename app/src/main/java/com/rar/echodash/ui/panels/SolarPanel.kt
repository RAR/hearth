package com.rar.echodash.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.SolarFlowDiagram
import com.rar.echodash.ui.model.SolarFlowGraph

/** Full-screen SOLAR panel: the shared animated flow diagram at panel scale (with per-node daily
 *  detail) and the "Today" line beneath. Null graph → EmptyHint. */
@Composable
fun SolarPanel(graph: SolarFlowGraph?) {
    PanelSurface {
        if (graph == null) {
            EmptyHint("Assign solar sensors in the web config")
            return@PanelSurface
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SolarFlowDiagram(
                graph,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                showDailyDetail = true,
            )
            graph.todayLine?.let {
                Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            }
        }
    }
}
