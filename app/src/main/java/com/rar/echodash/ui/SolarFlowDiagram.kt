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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.BattFlow
import com.rar.echodash.ui.model.FlowNodeId
import com.rar.echodash.ui.model.SolarFlowGraph
import com.rar.echodash.ui.model.flowLapMs

// Geometry, all as fractions of min(width, height) so one composable serves card and panel scale.
private const val DOT_RADIUS_FRAC = 0.016f
private const val PRIMARY_SP_FRAC = 0.05f
private const val DETAIL_SP_FRAC = 0.045f
// Diagonal Béziers bow 25% from their midpoint toward the box center (the HA-distribution look).
private const val BEZIER_BOW = 0.25f
// Master phase period; every edge derives its own lap from flowLapMs(watts).
private const val FLOW_MASTER_MS = 4000

// Per-node line-landing radii (× minDim): each flow endpoint is pulled this far toward the
// opposite node so the line vanishes at the silhouette edge (silhouettes don't cover ends the way
// the old circles did). SOLAR/GRID clear the ray tips / open lattice; HOME/BATTERY hug tighter.
private const val LAND_SOLAR = 0.17f
private const val LAND_GRID = 0.17f
private const val LAND_HOME = 0.15f
private const val LAND_BATTERY = 0.15f

private val GaugeGreen = Color(0xFF7BC67E)

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

// Edge/dot source colors: grid brightened from a node gray for dark-bg visibility; battery uses
// its identity green (a neutral node would render invisible dots).
private fun edgeColor(id: FlowNodeId): Color = when (id) {
    FlowNodeId.SOLAR -> Color(0xFFE0A030)
    FlowNodeId.GRID -> Color(0xFF8892A0)
    FlowNodeId.BATTERY -> GaugeGreen
    FlowNodeId.HOME -> Color.White // home is never a source; defensive
}

private fun landingFrac(id: FlowNodeId): Float = when (id) {
    FlowNodeId.SOLAR -> LAND_SOLAR
    FlowNodeId.GRID -> LAND_GRID
    FlowNodeId.HOME -> LAND_HOME
    FlowNodeId.BATTERY -> LAND_BATTERY
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

/**
 * Builds the flow path between two node centers, trimming each endpoint toward the opposite node
 * by that node's landing radius so the line lands at the silhouette edge. The Bézier control point
 * is still derived from the UNTRIMMED centers, so trimming shortens the visible span without
 * changing the bow.
 */
private fun edgePath(
    a: Offset,
    b: Offset,
    landingA: Float,
    landingB: Float,
    diagonal: Boolean,
    boxCenter: Offset,
): Path {
    val delta = b - a
    val len = delta.getDistance().coerceAtLeast(0.0001f)
    val unit = delta / len
    val start = a + unit * landingA
    val end = b - unit * landingB
    val p = Path()
    p.moveTo(start.x, start.y)
    if (diagonal) {
        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f) // untrimmed midpoint drives the bow
        val cp = Offset(
            mid.x + (boxCenter.x - mid.x) * BEZIER_BOW,
            mid.y + (boxCenter.y - mid.y) * BEZIER_BOW,
        )
        p.quadraticTo(cp.x, cp.y, end.x, end.y)
    } else {
        p.lineTo(end.x, end.y)
    }
    return p
}

/**
 * The animated HA-distribution diamond. Fills its incoming constraints; all geometry scales from
 * min(width, height), so the same composable renders at card size (~268×~190 dp after the label
 * strip) and panel size. The renderer draws the full inactive line structure among present nodes,
 * overlays active edges + flowing dots, then the four refined silhouettes on top; labels sit below
 * the shapes (never on them), with the battery's label stack in a reserved bottom strip.
 */
@Composable
fun SolarFlowDiagram(
    graph: SolarFlowGraph,
    modifier: Modifier = Modifier,
    showDailyDetail: Boolean = false,
) {
    // The battery fill keeps the color of the last non-idle direction while idle (green until first
    // activity), mirroring the home pill's gauge.
    var lastFlow by remember { mutableStateOf(BattFlow.CHARGING) }
    LaunchedEffect(graph.battFlow) {
        if (graph.battFlow != BattFlow.IDLE) lastFlow = graph.battFlow
    }
    val battDirection = if (graph.battFlow != BattFlow.IDLE) graph.battFlow else lastFlow
    val discharging = battDirection == BattFlow.DISCHARGING

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
        // The diamond gets the box minus a bottom strip reserved for the battery's below-node label
        // stack: the 0.85 battery fraction leaves only ~2% of height under the node — nowhere near a
        // text line — so the strip is carved out up front. It now holds up to three lines: the "NN%"
        // primary plus battText and (panel-only) battTodayLine detail lines.
        val battPrimaryLines = if (graph.socPct != null) 1 else 0
        val battDetailLines = (if (graph.battText != null) 1 else 0) +
            (if (showDailyDetail && graph.battTodayLine != null) 1 else 0)
        val hRef = if (w < h) w else h // avoids the diagramH ⇄ minDim circular dependency
        val stripDp = hRef * (PRIMARY_SP_FRAC * 1.5f) * battPrimaryLines +
            hRef * (DETAIL_SP_FRAC * 1.5f) * battDetailLines
        val diagramH = h - stripDp
        val minDim = if (w < diagramH) w else diagramH
        fun cx(id: FlowNodeId): Dp = w * nodeFrac(id).first
        fun cy(id: FlowNodeId): Dp = diagramH * nodeFrac(id).second

        Canvas(Modifier.fillMaxWidth().height(diagramH)) {
            val md = size.minDimension
            val boxCenter = Offset(size.width / 2f, size.height / 2f)
            fun center(id: FlowNodeId) =
                Offset(size.width * nodeFrac(id).first, size.height * nodeFrac(id).second)
            fun landing(id: FlowNodeId) = landingFrac(id) * md
            val present = presentNodes(graph)

            // 1. Inactive structure among present nodes (endpoints trimmed to landing radii).
            for ((a, b, diag) in CONNECTIONS) {
                if (a in present && b in present) {
                    drawPath(
                        edgePath(center(a), center(b), landing(a), landing(b), diag, boxCenter),
                        color = Color.White.copy(alpha = 0.12f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // 2. Active edges + two dots each, half a lap apart, riding source-colored trimmed paths.
            val dotR = maxOf(3.dp.toPx(), md * DOT_RADIUS_FRAC)
            for (e in graph.edges) {
                val path = edgePath(
                    center(e.from), center(e.to),
                    landing(e.from), landing(e.to),
                    isDiagonal(e.from, e.to), boxCenter,
                )
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

            // 3. Refined silhouettes on top of the lines (the dots vanish at the shape edges).
            for (id in present) when (id) {
                FlowNodeId.SOLAR -> drawSun(center(id), md)
                FlowNodeId.HOME -> drawHouse(center(id), md)
                FlowNodeId.GRID -> drawPylon(center(id), md)
                FlowNodeId.BATTERY -> drawBattery(center(id), md, graph.socPct, discharging)
            }
        }

        // Labels below the shapes: primary watts/percent (white) with optional detail lines (dim).
        val present = presentNodes(graph)
        val primarySp: TextUnit = (minDim.value * PRIMARY_SP_FRAC).sp
        val detailSp: TextUnit = (minDim.value * DETAIL_SP_FRAC).sp
        val labelWidth = minDim * 0.9f
        val gap = minDim * 0.02f
        // Silhouette half-heights below their centers (mock units ÷ 220): ray tips 34, house body 26,
        // tower foot 34 — the spots the labels must clear.
        val solarHalf = minDim * (34f / 220f)
        val homeHalf = minDim * (26f / 220f)
        val gridHalf = minDim * (34f / 220f)

        if (FlowNodeId.SOLAR in present) {
            NodeLabelStack(
                x = cx(FlowNodeId.SOLAR) - labelWidth / 2, y = cy(FlowNodeId.SOLAR) + solarHalf + gap,
                width = labelWidth, primary = primaryText(graph, FlowNodeId.SOLAR), primarySp = primarySp,
                details = if (showDailyDetail) listOfNotNull(graph.arraysLine) else emptyList(),
                detailSp = detailSp,
            )
        }
        if (FlowNodeId.HOME in present) {
            NodeLabelStack(
                x = cx(FlowNodeId.HOME) - labelWidth / 2, y = cy(FlowNodeId.HOME) + homeHalf + gap,
                width = labelWidth, primary = primaryText(graph, FlowNodeId.HOME), primarySp = primarySp,
                details = emptyList(), detailSp = detailSp,
            )
        }
        if (FlowNodeId.GRID in present) {
            NodeLabelStack(
                x = cx(FlowNodeId.GRID) - labelWidth / 2, y = cy(FlowNodeId.GRID) + gridHalf + gap,
                width = labelWidth, primary = primaryText(graph, FlowNodeId.GRID), primarySp = primarySp,
                details = if (showDailyDetail) listOfNotNull(graph.gridTodayLine) else emptyList(),
                detailSp = detailSp,
            )
        }
        if (FlowNodeId.BATTERY in present) {
            // Battery label stack lives in the reserved bottom strip so it never clips.
            NodeLabelStack(
                x = cx(FlowNodeId.BATTERY) - labelWidth / 2, y = diagramH,
                width = labelWidth, primary = primaryText(graph, FlowNodeId.BATTERY), primarySp = primarySp,
                details = buildList {
                    graph.battText?.let { add(it) }
                    if (showDailyDetail) graph.battTodayLine?.let { add(it) }
                },
                detailSp = detailSp,
            )
        }
    }
}

@Composable
private fun NodeLabelStack(
    x: Dp,
    y: Dp,
    width: Dp,
    primary: String?,
    primarySp: TextUnit,
    details: List<String>,
    detailSp: TextUnit,
) {
    if (primary == null && details.isEmpty()) return
    Box(Modifier.offset(x = x, y = y).width(width), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (primary != null) {
                // >=10 kW labels ("10.2 kW", 7 chars) outgrow the column at the base size; shrink
                // proportionally past 6 chars (safety net, same rule as the in-circle labels were).
                val fit = if (primary.length > 6) 6f / primary.length else 1f
                Text(primary, color = Color.White, fontSize = primarySp * fit, maxLines = 1,
                    textAlign = TextAlign.Center)
            }
            details.forEach {
                Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = detailSp,
                    maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

// --- Silhouettes -------------------------------------------------------------------------------
// Transcribed from the mock's "B · refined, labels outside" SVG groups (each translate(x,46)), so
// in-group coordinates are offsets from the node center. Conversion: mock units ÷ 220 = fraction of
// minDim. Stroke widths floor at 1 dp.

/** SOLAR — 8 major + 8 minor gapped round-capped rays around a radially-lit disk. */
private fun DrawScope.drawSun(c: Offset, md: Float) {
    fun u(v: Float): Float = v / 220f * md
    fun off(x: Float, y: Float) = Offset(c.x + u(x), c.y + u(y))
    val rayColor = Color(0xFFE0A030)
    val majorW = maxOf(1.dp.toPx(), u(3.4f))
    val minorW = maxOf(1.dp.toPx(), u(2.4f))
    fun ray(x1: Float, y1: Float, x2: Float, y2: Float, wpx: Float, alpha: Float) =
        drawLine(rayColor.copy(alpha = alpha), off(x1, y1), off(x2, y2), strokeWidth = wpx,
            cap = StrokeCap.Round)

    // Major rays: 4 orthogonal + 4 diagonal.
    ray(0f, -27f, 0f, -34f, majorW, 1f); ray(0f, 27f, 0f, 34f, majorW, 1f)
    ray(-27f, 0f, -34f, 0f, majorW, 1f); ray(27f, 0f, 34f, 0f, majorW, 1f)
    ray(-19.1f, -19.1f, -24f, -24f, majorW, 1f); ray(19.1f, -19.1f, 24f, -24f, majorW, 1f)
    ray(-19.1f, 19.1f, -24f, 24f, majorW, 1f); ray(19.1f, 19.1f, 24f, 24f, majorW, 1f)
    // Minor rays: shorter, dimmer, filling the gaps.
    ray(-10.4f, -25f, -12.9f, -31f, minorW, 0.85f); ray(10.4f, -25f, 12.9f, -31f, minorW, 0.85f)
    ray(-10.4f, 25f, -12.9f, 31f, minorW, 0.85f); ray(10.4f, 25f, 12.9f, 31f, minorW, 0.85f)
    ray(-25f, -10.4f, -31f, -12.9f, minorW, 0.85f); ray(25f, -10.4f, 31f, -12.9f, minorW, 0.85f)
    ray(-25f, 10.4f, -31f, 12.9f, minorW, 0.85f); ray(25f, 10.4f, 31f, 12.9f, minorW, 0.85f)

    // Disk with r2sun radial gradient (mock cx 42% / cy 38% of the 42-unit box → offset center).
    val diskR = u(21f)
    val sunBrush = Brush.radialGradient(
        0f to Color(0xFFFFD98A), 0.55f to Color(0xFFE8AC3E), 1f to Color(0xFFC8862A),
        center = Offset(c.x + u(-3.36f), c.y + u(-5.04f)), radius = diskR,
    )
    drawCircle(sunBrush, radius = diskR, center = c)
}

/** HOME — overhung two-tone gable roof + eave shadow, capped chimney, mullioned windows, door. */
private fun DrawScope.drawHouse(c: Offset, md: Float) {
    fun u(v: Float): Float = v / 220f * md
    fun off(x: Float, y: Float) = Offset(c.x + u(x), c.y + u(y))
    fun sw(v: Float): Float = maxOf(1.dp.toPx(), u(v))
    // Vertical linear gradient across [topU, botU] (mock defs use x1=0,y1=0,x2=0,y2=1).
    fun vGrad(c0: Color, c1: Color, topU: Float, botU: Float) = Brush.linearGradient(
        listOf(c0, c1), start = Offset(c.x, c.y + u(topU)), end = Offset(c.x, c.y + u(botU)),
    )
    val body = vGrad(Color(0xFF4E82BC), Color(0xFF35659B), -4f, 26f)   // r2body
    val roof = vGrad(Color(0xFF3D6EA6), Color(0xFF2A5484), -25f, -2.5f) // r2roof (roof bbox)
    val roofChim = vGrad(Color(0xFF3D6EA6), Color(0xFF2A5484), -19f, -8f) // r2roof over chimney

    drawRoundRect(body, off(-20f, -4f), Size(u(40f), u(30f)), CornerRadius(u(2.5f)))
    // Eave shadow: thin dark band along the wall top.
    drawRect(Color(0xFF0A1220).copy(alpha = 0.28f), off(-20f, -4f), Size(u(40f), u(2.2f)))
    // Chimney + cap (behind the roof; only its top pokes above the roofline).
    drawRect(roofChim, off(10f, -19f), Size(u(6.5f), u(11f)))
    drawRoundRect(Color(0xFF4E82BC), off(8.8f, -21f), Size(u(8.9f), u(2.8f)), CornerRadius(u(1.2f)))
    // Overhung gable roof (outer eave line to inner underside).
    val roofPath = Path().apply {
        moveTo(off(-27f, -2.5f).x, off(-27f, -2.5f).y)
        lineTo(off(0f, -25f).x, off(0f, -25f).y)
        lineTo(off(27f, -2.5f).x, off(27f, -2.5f).y)
        lineTo(off(22f, -2.5f).x, off(22f, -2.5f).y)
        lineTo(off(0f, -20.4f).x, off(0f, -20.4f).y)
        lineTo(off(-22f, -2.5f).x, off(-22f, -2.5f).y)
        close()
    }
    drawPath(roofPath, roof)
    // Two mullioned warm windows.
    val windowFill = Color(0xFFF5E7C4).copy(alpha = 0.95f)
    val mullion = Color(0xFF35659B)
    drawRoundRect(windowFill, off(-15f, 1.5f), Size(u(9f), u(9f)), CornerRadius(u(1.2f)))
    drawLine(mullion, off(-10.5f, 1.5f), off(-10.5f, 10.5f), sw(1.1f))
    drawLine(mullion, off(-15f, 6f), off(-6f, 6f), sw(1.1f))
    drawRoundRect(windowFill, off(6f, 1.5f), Size(u(9f), u(9f)), CornerRadius(u(1.2f)))
    drawLine(mullion, off(10.5f, 1.5f), off(10.5f, 10.5f), sw(1.1f))
    drawLine(mullion, off(6f, 6f), off(15f, 6f), sw(1.1f))
    // Centered round-top door: sides up, half-ellipse over the top, knob.
    val door = Path().apply {
        moveTo(off(-4.5f, 26f).x, off(-4.5f, 26f).y)
        lineTo(off(-4.5f, 12.5f).x, off(-4.5f, 12.5f).y)
        arcTo(Rect(off(-4.5f, 8.5f), off(4.5f, 16.5f)), 180f, 180f, false)
        lineTo(off(4.5f, 26f).x, off(4.5f, 26f).y)
        close()
    }
    drawPath(door, Color(0xFF24486F))
    drawCircle(Color(0xFFF5E7C4).copy(alpha = 0.8f), radius = u(0.9f), center = off(2.4f, 19.5f))
}

/** GRID — filled tapered tower, lattice bays as negative space with X-braces, arms + insulators. */
private fun DrawScope.drawPylon(c: Offset, md: Float) {
    fun u(v: Float): Float = v / 220f * md
    fun off(x: Float, y: Float) = Offset(c.x + u(x), c.y + u(y))
    fun sw(v: Float): Float = maxOf(1.dp.toPx(), u(v))
    fun steel(topU: Float, botU: Float) = Brush.linearGradient(
        listOf(Color(0xFF9AA4B4), Color(0xFF6E7885)),
        start = Offset(c.x, c.y + u(topU)), end = Offset(c.x, c.y + u(botU)),
    )
    val steelLattice = steel(-7f, 31f)

    // Tower legs (outer trace down one side, inner trace up the other — center stays hollow).
    val tower = Path().apply {
        moveTo(off(-13f, 34f).x, off(-13f, 34f).y)
        lineTo(off(-4f, -26f).x, off(-4f, -26f).y)
        lineTo(off(4f, -26f).x, off(4f, -26f).y)
        lineTo(off(13f, 34f).x, off(13f, 34f).y)
        lineTo(off(8.4f, 34f).x, off(8.4f, 34f).y)
        lineTo(off(2.6f, -21.5f).x, off(2.6f, -21.5f).y)
        lineTo(off(-2.6f, -21.5f).x, off(-2.6f, -21.5f).y)
        lineTo(off(-8.4f, 34f).x, off(-8.4f, 34f).y)
        close()
    }
    drawPath(tower, steel(-26f, 34f))
    // Three lattice bays (stroked trapezoids).
    val bays = Path().apply {
        moveTo(off(-6.9f, 20f).x, off(-6.9f, 20f).y); lineTo(off(6.9f, 20f).x, off(6.9f, 20f).y)
        lineTo(off(8.1f, 31f).x, off(8.1f, 31f).y); lineTo(off(-8.1f, 31f).x, off(-8.1f, 31f).y); close()
        moveTo(off(-5.6f, 6f).x, off(-5.6f, 6f).y); lineTo(off(5.6f, 6f).x, off(5.6f, 6f).y)
        lineTo(off(6.6f, 16f).x, off(6.6f, 16f).y); lineTo(off(-6.6f, 16f).x, off(-6.6f, 16f).y); close()
        moveTo(off(-4.4f, -7f).x, off(-4.4f, -7f).y); lineTo(off(4.4f, -7f).x, off(4.4f, -7f).y)
        lineTo(off(5.3f, 2f).x, off(5.3f, 2f).y); lineTo(off(-5.3f, 2f).x, off(-5.3f, 2f).y); close()
    }
    drawPath(bays, steelLattice, style = Stroke(sw(1.6f)))
    // X-braces across each bay.
    val braceW = sw(1.3f)
    drawLine(steelLattice, off(-8.1f, 31f), off(6.9f, 20f), braceW)
    drawLine(steelLattice, off(8.1f, 31f), off(-6.9f, 20f), braceW)
    drawLine(steelLattice, off(-6.6f, 16f), off(5.6f, 6f), braceW)
    drawLine(steelLattice, off(6.6f, 16f), off(-5.6f, 6f), braceW)
    drawLine(steelLattice, off(-5.3f, 2f), off(4.4f, -7f), braceW)
    drawLine(steelLattice, off(5.3f, 2f), off(-4.4f, -7f), braceW)
    // Two crossarms + apex spike.
    drawRoundRect(steel(-15.5f, -12.3f), off(-22f, -15.5f), Size(u(44f), u(3.2f)), CornerRadius(u(1.6f)))
    drawRoundRect(steel(-4.5f, -1.7f), off(-15.5f, -4.5f), Size(u(31f), u(2.8f)), CornerRadius(u(1.4f)))
    drawLine(Color(0xFF9AA4B4), off(0f, -26f), off(0f, -31f), sw(2f), cap = StrokeCap.Round)
    // Insulator nubs hanging under the arms.
    val nub = Color(0xFF5A626E)
    drawRoundRect(nub, off(-20.5f, -12.3f), Size(u(2f), u(4.5f)), CornerRadius(u(1f)))
    drawRoundRect(nub, off(18.5f, -12.3f), Size(u(2f), u(4.5f)), CornerRadius(u(1f)))
    drawRoundRect(nub, off(-14f, -1.7f), Size(u(2f), u(4f)), CornerRadius(u(1f)))
    drawRoundRect(nub, off(12f, -1.7f), Size(u(2f), u(4f)), CornerRadius(u(1f)))
}

/** BATTERY — outlined case + terminal cap; liquid fill (height = SOC%) with meniscus + highlight. */
private fun DrawScope.drawBattery(c: Offset, md: Float, socPct: Int?, discharging: Boolean) {
    fun u(v: Float): Float = v / 220f * md
    fun off(x: Float, y: Float) = Offset(c.x + u(x), c.y + u(y))
    fun sw(v: Float): Float = maxOf(1.dp.toPx(), u(v))

    // Terminal cap (behind the case top), then the dark case with its bright outline.
    drawRoundRect(Color(0xFFAEB6C2), off(-7.5f, -31f), Size(u(15f), u(6f)), CornerRadius(u(2.5f)))
    drawRoundRect(Color(0xFF20242E), off(-16.5f, -26f), Size(u(33f), u(52f)), CornerRadius(u(7f)))
    drawRoundRect(Color(0xFFAEB6C2), off(-16.5f, -26f), Size(u(33f), u(52f)), CornerRadius(u(7f)),
        style = Stroke(sw(2f)))

    // Liquid fill replaces the old SOC ring. Inner fillable region calibrated so 63% reproduces the
    // mock's fill top of -5.5 (innerBottom 22.5, innerHeight 44.5).
    val soc = socPct ?: return
    val socF = (soc.coerceIn(0, 100)) / 100f
    if (socF <= 0f) return
    val innerBottom = 22.5f
    val innerHeight = 44.5f
    val fillTop = innerBottom - socF * innerHeight
    val fillHeight = socF * innerHeight
    // Green while charging / idle-after-charge; amber while discharging / idle-after-discharge.
    val c0 = if (discharging) Color(0xFFF0C46A) else Color(0xFF93D797)
    val c1 = if (discharging) Color(0xFFD89426) else Color(0xFF64AE69)
    val meniscus = if (discharging) Color(0xFFF6D28C) else Color(0xFFA5E0A9)
    val fillBrush = Brush.linearGradient(
        listOf(c0, c1), start = Offset(c.x, c.y + u(fillTop)), end = Offset(c.x, c.y + u(innerBottom)),
    )
    drawRoundRect(fillBrush, off(-13f, fillTop), Size(u(26f), u(fillHeight)), CornerRadius(u(3.5f)))
    // Meniscus ellipse riding the liquid surface.
    drawOval(meniscus, topLeft = Offset(c.x + u(-13f), c.y + u(fillTop) - u(2.6f)),
        size = Size(u(26f), u(5.2f)))
    // Left highlight stripe, clamped to stay inside the liquid at low SOC.
    val hlTop = fillTop + 4f
    val hlBot = minOf(fillTop + 23f, innerBottom - 1f)
    if (hlBot > hlTop) {
        drawRoundRect(Color.White.copy(alpha = 0.28f), off(-10.5f, hlTop),
            Size(u(3f), u(hlBot - hlTop)), CornerRadius(u(1.5f)))
    }
}
