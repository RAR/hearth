package com.rar.echodash.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SolarPower
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.BattFlow
import com.rar.echodash.ui.model.FlowEdge
import com.rar.echodash.ui.model.FlowNodeId
import com.rar.echodash.ui.model.SolarFlowGraph
import com.rar.echodash.ui.model.flowLapMs

// Geometry, all as fractions of min(width, height) so one composable serves card and panel scale.
private const val NODE_RADIUS_FRAC = 0.13f
private const val DOT_RADIUS_FRAC = 0.016f
private const val RING_STROKE_FRAC = 0.02f
private const val ICON_FRAC = 0.10f
private const val PRIMARY_SP_FRAC = 0.06f
private const val DETAIL_SP_FRAC = 0.045f
// Diagonal Béziers bow 25% from their midpoint toward the box center (the HA-distribution look).
private const val BEZIER_BOW = 0.25f
// Master phase period; every edge derives its own lap from flowLapMs(watts).
private const val FLOW_MASTER_MS = 4000

private val GaugeGreen = Color(0xFF7BC67E)
private val GaugeAmber = Color(0xFFE0A030)

private data class Conn(val a: FlowNodeId, val b: FlowNodeId, val diagonal: Boolean)

// The six canonical connections: four diagonals bow toward center; SOLAR–BATTERY and GRID–HOME
// are straight center lines that cross mid-box.
private val CONNECTIONS = listOf(
    Conn(FlowNodeId.SOLAR, FlowNodeId.GRID, true),
    Conn(FlowNodeId.SOLAR, FlowNodeId.HOME, true),
    Conn(FlowNodeId.GRID, FlowNodeId.BATTERY, true),
    Conn(FlowNodeId.BATTERY, FlowNodeId.HOME, true),
    Conn(FlowNodeId.SOLAR, FlowNodeId.BATTERY, false),
    Conn(FlowNodeId.GRID, FlowNodeId.HOME, false),
)

private fun nodeFrac(id: FlowNodeId): Pair<Float, Float> = when (id) {
    FlowNodeId.SOLAR -> 0.50f to 0.15f
    FlowNodeId.GRID -> 0.15f to 0.50f
    FlowNodeId.HOME -> 0.85f to 0.50f
    FlowNodeId.BATTERY -> 0.50f to 0.85f
}

private fun nodeColor(id: FlowNodeId): Color = when (id) {
    FlowNodeId.SOLAR -> Color(0xFFE0A030)
    FlowNodeId.HOME -> Color(0xFF3A6EA5)
    FlowNodeId.GRID -> Color(0xFF6B7280)
    FlowNodeId.BATTERY -> Color(0xFF2A2F3C) // neutral so the SOC ring reads
}

// Edge/dot source colors: grid brightened from its node gray for dark-bg visibility; battery uses
// its identity green (the neutral node circle would render invisible dots).
private fun edgeColor(id: FlowNodeId): Color = when (id) {
    FlowNodeId.SOLAR -> Color(0xFFE0A030)
    FlowNodeId.GRID -> Color(0xFF8892A0)
    FlowNodeId.BATTERY -> GaugeGreen
    FlowNodeId.HOME -> Color.White // home is never a source; defensive
}

private fun nodeIcon(id: FlowNodeId): ImageVector = when (id) {
    FlowNodeId.SOLAR -> Icons.Outlined.SolarPower
    FlowNodeId.HOME -> Icons.Outlined.Home
    FlowNodeId.GRID -> Icons.Outlined.Bolt
    FlowNodeId.BATTERY -> Icons.Outlined.BatteryStd
}

private fun primaryText(graph: SolarFlowGraph, id: FlowNodeId): String? = when (id) {
    FlowNodeId.SOLAR -> graph.solarText
    FlowNodeId.HOME -> graph.homeText
    FlowNodeId.GRID -> graph.gridText
    FlowNodeId.BATTERY -> graph.socPct?.let { "$it%" }
}

private fun presentNodes(graph: SolarFlowGraph): List<FlowNodeId> = buildList {
    if (graph.solarText != null) add(FlowNodeId.SOLAR)
    if (graph.gridText != null) add(FlowNodeId.GRID)
    if (graph.homeText != null) add(FlowNodeId.HOME)
    if (graph.socPct != null || graph.battText != null) add(FlowNodeId.BATTERY)
}

private fun isDiagonal(a: FlowNodeId, b: FlowNodeId): Boolean {
    val pair = setOf(a, b)
    return pair != setOf(FlowNodeId.SOLAR, FlowNodeId.BATTERY) &&
        pair != setOf(FlowNodeId.GRID, FlowNodeId.HOME)
}

private fun edgePath(a: Offset, b: Offset, diagonal: Boolean, boxCenter: Offset): Path {
    val p = Path()
    p.moveTo(a.x, a.y)
    if (diagonal) {
        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val cp = Offset(
            mid.x + (boxCenter.x - mid.x) * BEZIER_BOW,
            mid.y + (boxCenter.y - mid.y) * BEZIER_BOW,
        )
        p.quadraticBezierTo(cp.x, cp.y, b.x, b.y)
    } else {
        p.lineTo(b.x, b.y)
    }
    return p
}

/**
 * The animated HA-distribution diamond. Fills its incoming constraints; all geometry scales from
 * min(width, height), so the same composable renders at card size (~268×230 dp) and panel size.
 * The renderer always draws the full inactive line structure among present nodes, then overlays
 * active edges + flowing dots, so the shape never jumps as flows start and stop.
 */
@Composable
fun SolarFlowDiagram(
    graph: SolarFlowGraph,
    modifier: Modifier = Modifier,
    showDailyDetail: Boolean = false,
) {
    // Ring keeps the color of the last non-idle direction while idle (green until first activity),
    // mirroring the home pill's gauge.
    var lastFlow by remember { mutableStateOf(BattFlow.CHARGING) }
    LaunchedEffect(graph.battFlow) {
        if (graph.battFlow != BattFlow.IDLE) lastFlow = graph.battFlow
    }
    val ringDirection = if (graph.battFlow != BattFlow.IDLE) graph.battFlow else lastFlow

    val transition = rememberInfiniteTransition(label = "solarFlow")
    val masterPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(FLOW_MASTER_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "solarFlowPhase",
    )

    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        // The diamond gets the box minus a bottom strip reserved for the battery's below-node
        // labels (watts, plus the daily line at panel scale): the 0.85 battery fraction leaves
        // only ~2% of height under the node — nowhere near a text line — so the strip is carved
        // out up front. (The approved mock did the same: battery labels sat outside the diamond.)
        val battLabelLines = (if (graph.battText != null) 1 else 0) +
            (if (showDailyDetail && graph.battTodayLine != null) 1 else 0)
        val labelLineDp = (if (w < h) w else h) * (DETAIL_SP_FRAC * 1.5f)
        val diagramH = h - labelLineDp * battLabelLines
        val minDim = if (w < diagramH) w else diagramH
        val r = minDim * NODE_RADIUS_FRAC
        fun cx(id: FlowNodeId): Dp = w * nodeFrac(id).first
        fun cy(id: FlowNodeId): Dp = diagramH * nodeFrac(id).second

        Canvas(Modifier.fillMaxWidth().height(diagramH)) {
            val rp = size.minDimension * NODE_RADIUS_FRAC
            val boxCenter = Offset(size.width / 2f, size.height / 2f)
            fun center(id: FlowNodeId) =
                Offset(size.width * nodeFrac(id).first, size.height * nodeFrac(id).second)
            val present = presentNodes(graph)

            // 1. Inactive structure among present nodes.
            for ((a, b, diag) in CONNECTIONS) {
                if (a in present && b in present) {
                    drawPath(
                        edgePath(center(a), center(b), diag, boxCenter),
                        color = Color.White.copy(alpha = 0.12f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // 2. Active edges + two dots each, half a lap apart, riding source-colored paths.
            val dotR = maxOf(3.dp.toPx(), size.minDimension * DOT_RADIUS_FRAC)
            for (e in graph.edges) {
                val path = edgePath(center(e.from), center(e.to), isDiagonal(e.from, e.to), boxCenter)
                val src = edgeColor(e.from)
                drawPath(path, color = src.copy(alpha = 0.55f), style = Stroke(width = 2.5.dp.toPx()))
                val pm = PathMeasure()
                pm.setPath(path, false)
                val len = pm.length
                val lap = flowLapMs(e.watts).toFloat()
                for (offset in floatArrayOf(0f, 0.5f)) {
                    val frac = ((masterPhase * FLOW_MASTER_MS / lap) + offset).mod(1f)
                    drawCircle(src, radius = dotR, center = pm.getPosition(frac * len))
                }
            }

            // 3. Node circles cover the dot ends.
            for (id in present) drawCircle(nodeColor(id), radius = rp, center = center(id))

            // 4. Battery SOC ring: track + sweep from 12 o'clock.
            graph.socPct?.let { soc ->
                val bc = center(FlowNodeId.BATTERY)
                val ringStroke = maxOf(3.dp.toPx(), size.minDimension * RING_STROKE_FRAC)
                val topLeft = Offset(bc.x - rp, bc.y - rp)
                val arcSize = Size(rp * 2f, rp * 2f)
                drawArc(
                    color = Color.White.copy(alpha = 0.15f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(width = ringStroke),
                )
                drawArc(
                    color = if (ringDirection == BattFlow.DISCHARGING) GaugeAmber else GaugeGreen,
                    startAngle = -90f, sweepAngle = soc / 100f * 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(width = ringStroke),
                )
            }
        }

        // Overlay: icon + primary text centered in each present circle.
        val present = presentNodes(graph)
        val iconDp = minDim * ICON_FRAC
        val primarySp: TextUnit = (minDim.value * PRIMARY_SP_FRAC).sp
        val detailSp: TextUnit = (minDim.value * DETAIL_SP_FRAC).sp
        val diameter = r * 2
        for (id in present) {
            val label = primaryText(graph, id) ?: continue
            Box(
                Modifier.offset(x = cx(id) - r, y = cy(id) - r).size(diameter),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(nodeIcon(id), contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(iconDp))
                    // >=10 kW labels ("10.2 kW", 7 chars) outgrow the circle at the base size;
                    // shrink proportionally past 6 chars so the text always stays inside the node.
                    val fit = if (label.length > 6) 6f / label.length else 1f
                    Text(label, color = Color.White, fontSize = primarySp * fit, maxLines = 1)
                }
            }
        }

        // Below-node dim labels. Battery lines render in the reserved bottom strip (below the
        // diamond box) so they never clip; grid/solar lines sit mid-box where there's room.
        // Daily lines only when showDailyDetail.
        val labelWidth = minDim * 0.9f
        if (FlowNodeId.BATTERY in present) {
            BelowNodeLabels(
                x = cx(FlowNodeId.BATTERY) - labelWidth / 2,
                y = diagramH, width = labelWidth, fontSize = detailSp,
                lines = buildList {
                    graph.battText?.let { add(it) }
                    if (showDailyDetail) graph.battTodayLine?.let { add(it) }
                },
            )
        }
        if (showDailyDetail && FlowNodeId.GRID in present) {
            BelowNodeLabels(
                x = cx(FlowNodeId.GRID) - labelWidth / 2, y = cy(FlowNodeId.GRID) + r + 2.dp,
                width = labelWidth, fontSize = detailSp, lines = listOfNotNull(graph.gridTodayLine),
            )
        }
        if (showDailyDetail && FlowNodeId.SOLAR in present) {
            BelowNodeLabels(
                x = cx(FlowNodeId.SOLAR) - labelWidth / 2, y = cy(FlowNodeId.SOLAR) + r + 2.dp,
                width = labelWidth, fontSize = detailSp, lines = listOfNotNull(graph.arraysLine),
            )
        }
    }
}

@Composable
private fun BelowNodeLabels(x: Dp, y: Dp, width: Dp, fontSize: TextUnit, lines: List<String>) {
    if (lines.isEmpty()) return
    Box(Modifier.offset(x = x, y = y).width(width), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            lines.forEach {
                Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = fontSize,
                    maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}
