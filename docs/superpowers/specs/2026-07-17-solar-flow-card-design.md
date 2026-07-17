# Solar Flow Card & Panel — Design

2026-07-17. Builds on the adaptive-sizing work (2026-07-16 spec): the home solar card on the
big width tiers (card width ≥ 300 dp — Echo Show 8 and Tab M9) becomes an **animated
energy-flow diagram**, and the full-screen SOLAR panel is re-rendered with the **same shared
renderer**. The Echo Show 5 (248 dp tier) keeps the current compact pill byte-for-byte.

User decisions (2026-07-17 brainstorm):

- Flow diagram over an expanded text pill — "more graphical, with lines showing where and
  direction of flow".
- **Diamond, HA-energy-distribution style**: Solar top, Grid left, Home right, Battery bottom.
- Battery SOC as a **ring on the battery node only** — the big card drops the horizontal gauge
  bar (the Show 5 pill keeps its bar unchanged).
- **"Today" footer** on the card (existing pvToday/loadToday line, currently panel-only).
- **Panel shares the renderer** — SOLAR panel finally gains the battery node and animation.
- Sensor expansion invited ("we have a ton of other sensors") → new optional sensors:
  **grid import/export today**, **battery charged/discharged today**, and up to four
  **per-array PV power** slots (the user's TigoMonitor publishes `sensor.solar_array_a`–`d`),
  all shown at panel scale only. Deeper detail is a non-goal (see Non-goals).

### Real-sensor findings (2026-07-17, via HA MCP)

The user's stack: Solar Assistant → MQTT (`sensor.luxpower_lxp_x_2_*`, what the dashboard
uses today), Monitor My Solar dongles (`sensor.eg4_gridboss_*`), TigoMonitor
(`sensor.solar_array_a`–`d`, unit string "watts"), plus an evcc layer.

**Bug found:** every `luxpower_*_energy` sensor is a `total_increasing` *lifetime* counter
(history: no midnight reset, 63.5 kWh already on the meter at 9 AM July 15) — so the
already-shipped pvToday/loadToday assignments have been rendering cumulative totals in the
"Today:" line. Fix is HA-side: six daily `utility_meter` helpers were created 2026-07-17 via
the HA config-flow API over the lifetime counters:

| Helper entity | Source |
|---|---|
| `sensor.luxpower_lxp_x_2_pv_energy_today` | `…_pv_energy` |
| `sensor.luxpower_lxp_x_2_load_energy_today` | `…_load_energy` |
| `sensor.luxpower_lxp_x_2_grid_import_today` | `…_grid_energy_in` |
| `sensor.luxpower_lxp_x_2_grid_export_today` | `…_grid_energy_out` |
| `sensor.luxpower_lxp_x_2_battery_charged_today` | `…_battery_energy_in` |
| `sensor.luxpower_lxp_x_2_battery_discharged_today` | `…_battery_energy_out` |

These are what the config page's -today pickers (existing and new) should be assigned to.
No app-side change follows from this — the app renders whatever sensor is assigned.

## 1. Data model — `ui/model/SolarModel.kt`

`SolarFlow` + `solarFlow()` (the old panel model; SolarPanel is its only consumer) are
**deleted** and replaced by:

```kotlin
enum class FlowNodeId { SOLAR, HOME, GRID, BATTERY }

/** One active power flow between two present nodes; watts always positive. */
data class FlowEdge(val from: FlowNodeId, val to: FlowNodeId, val watts: Double)

/** Everything the flow renderer needs. Nodes with null text (and socPct for battery) are
 *  absent from the diagram. [edges] holds ACTIVE flows only (> FLOW_DEADBAND_W); the
 *  renderer draws the full inactive line structure itself so the diagram's shape never
 *  jumps as flows start and stop. */
data class SolarFlowGraph(
    val solarText: String?,      // formatted PV watts; null = no pv sensor
    val homeText: String?,       // formatted load watts; null = no load sensor
    val gridText: String?,       // formatted |grid| watts; null = no numeric grid sensor
    val socPct: Int?,            // battery ring sweep; node present if socPct or battText
    val battText: String?,       // formatted |battery| watts, drawn below the node
    val battFlow: BattFlow,      // ring color language (existing enum, unchanged)
    val edges: List<FlowEdge>,
    val todayLine: String?,      // existing "Today: X kWh produced · Y kWh used"
    val gridTodayLine: String?,  // "↓ 3.2 kWh · ↑ 2.2 kWh" — panel-scale only
    val battTodayLine: String?,  // "↓ 5.1 kWh · ↑ 4.2 kWh" (charged/discharged) — panel-scale
    val arraysLine: String?,     // "A 447 W · B 768 W · C 395 W · D 276 W" — panel-scale
)

fun solarFlowGraph(cfg: SolarConfig, entities: Map<String, EntityState>): SolarFlowGraph?
```

Returns null when no solar sensor resolves (same rule as `solarCard()`; panel shows its
EmptyHint, card hides). `solarCard()`, `SolarCard`, and `BattFlow` are untouched — the Show 5
pill keeps consuming them.

### Edge derivation (solar-first attribution)

The sensors give node totals only, so per-line flows are derived. Sign conventions (existing):
grid positive = importing; battery positive = **discharging** (evcc: negative = charging).
Inputs, with absent sensors contributing 0:

```
pvW       = max(0, pv watts)          // clamp inverter standby draw
chargeW   = max(0, -battW)
dischargeW= max(0,  battW)
importW   = max(0,  gridW)
exportW   = max(0, -gridW)
```

Derived edges, in this order:

```
SOLAR→BATTERY = min(pvW, chargeW)
SOLAR→GRID    = min(pvW - SOLAR→BATTERY, exportW)
SOLAR→HOME    = max(0, pvW - SOLAR→BATTERY - SOLAR→GRID)
BATTERY→HOME  = dischargeW
GRID→BATTERY  = max(0, chargeW - SOLAR→BATTERY)
GRID→HOME     = max(0, importW - GRID→BATTERY)
```

An edge enters `edges` iff its watts > `FLOW_DEADBAND_W = 50.0` (new constant beside the
existing 50 W deadbands) AND both endpoint nodes are present. Node presence: SOLAR iff
`cfg.pv` resolves, HOME iff `cfg.load`, GRID iff `cfg.grid` resolves numerically, BATTERY iff
`cfg.battSoc` or `cfg.battPower` resolves. BATTERY→GRID is deliberately not modeled.

**No forced balance:** each edge is derived independently; measurement noise or missing
sensors can make inflows ≠ node totals (e.g. with no grid/battery sensors, SOLAR→HOME shows
the full PV output even if the load sensor reads less). Documented behavior, not a bug.

Unit handling reuses `powerWatts()` (kW sensors ×1000) and `formatWatts()` unchanged.

### Dot speed

```kotlin
/** Lap time for a flow dot: 50 W → 4000 ms, 4000 W → 1200 ms, linear, clamped. */
fun flowLapMs(watts: Double): Int =
    (4000 - ((watts - 50.0) / 3950.0) * 2800.0).roundToInt().coerceIn(1200, 4000)
```

Pure and unit-tested (midpoint: 2025 W → 2600 ms).

## 2. Config — `config/DashConfig.kt` + web config page

`SolarConfig` gains optional fields (defaults → old configs deserialize fine; export/import
carries them automatically):

```kotlin
val gridImportToday: String? = null, // kWh energy sensor, grid → house today
val gridExportToday: String? = null, // kWh energy sensor, house → grid today
val battInToday: String? = null,     // kWh energy sensor, charged into battery today
val battOutToday: String? = null,    // kWh energy sensor, discharged from battery today
val arrays: List<SolarArrayConfig> = emptyList(), // up to 4 per-array PV power sensors

@Serializable
data class SolarArrayConfig(
    val name: String = "",        // shown label; blank falls back to "A".."D" by slot index
    val power: String? = null,    // W/kW power sensor for this array/string
)
```

`ids()` includes all of them (arrays contribute their power ids). The config page's Solar
card gains pickers "Grid import today (kWh)", "Grid export today (kWh)", "Battery charged
today (kWh)", "Battery discharged today (kWh)" following the pvToday/loadToday pattern, and
four array slots (name text + entity picker each), following the EV card's fixed-slot
pattern.

Line formats (each null when its sensors are absent):
- `gridTodayLine` / `battTodayLine`: "↓ {in} {unit} · ↑ {out} {unit}" using each sensor's
  own unit (default kWh); either sensor alone renders alone. For the battery, ↓ = charged,
  ↑ = discharged.
- `arraysLine`: entries with a resolving power sensor as "{name} {watts}" joined by " · ",
  formatted via `formatWatts()` (handles the Tigo sensors' lowercase "watts" unit as W).

## 3. Renderer — new `ui/SolarFlowDiagram.kt`

```kotlin
@Composable
fun SolarFlowDiagram(
    graph: SolarFlowGraph,
    modifier: Modifier = Modifier,
    showDailyDetail: Boolean = false, // panel passes true: grid daily line under grid node
)
```

Fills its incoming constraints; **all geometry scales from `min(width, height)`** so the same
composable renders at card size (~268×230 dp) and panel size. No LocalConfiguration.

- **Node centers** (fractions of the diagram box): SOLAR (0.50, 0.15), GRID (0.15, 0.50),
  HOME (0.85, 0.50), BATTERY (0.50, 0.85). Node radius `r = 0.13 × min(w, h)`.
- **Nodes**: filled circles — Solar `0xFFE0A030`, Home `0xFF3A6EA5`, Grid `0xFF6B7280` (the
  panel's existing palette), Battery neutral `0xFF2A2F3C` so its ring reads. White outlined
  icon (SolarPower / Home / Bolt / BatteryStd) + primary text inside the circle: watts for
  solar/home/grid, "NN%" for battery. Battery watts (`battText`) in small dim text just below
  its circle. Absent nodes are not drawn.
- **Battery ring**: arc stroke `max(3dp, 0.02 × min(w,h))`, sweep = socPct% of 360°, starting
  at 12 o'clock; track = same stroke at white 0.15 alpha. Ring color keeps the gauge's
  language: `GaugeGreen 0xFF7BC67E` while charging, `GaugeAmber 0xFFE0A030` while
  discharging, and the last non-idle direction while idle (same remember/LaunchedEffect
  pattern the pill's gauge uses; green until first activity).
- **Lines** (drawn beneath nodes), the six canonical connections among present nodes:
  diagonals (S→G, S→H, G→B, B→H) as quadratic Béziers bowing 25 % toward the box center;
  S→B and G→H straight center lines that cross mid-box (the HA look). Inactive: 2 dp stroke,
  white 0.12 alpha. Active (edge in `graph.edges`): 2.5 dp stroke, source-node color at 0.55
  alpha.
- **Dots**: 2 per active edge, half a lap apart, radius `max(3dp, 0.016 × min(w,h))`, filled
  with the source node's color. Source colors for edges/dots: solar `0xFFE0A030`, grid
  `0xFF8892A0` (brightened from the node gray for dark-bg visibility), battery
  `GaugeGreen 0xFF7BC67E` (its identity color — the neutral node circle would render
  invisible dots). Approved via the animated mock 2026-07-17. One `rememberInfiniteTransition` master phase (0→1 over
  4000 ms, linear, restart) drives every edge: a dot's fraction along its path is
  `(masterPhase × 4000 / flowLapMs(edge.watts) + offset) % 1`, positioned via
  `androidx.compose.ui.graphics.PathMeasure`. Dot direction = edge direction. No new
  dependencies; animation only runs while the diagram is composed.
- `showDailyDetail = true` additionally draws, in small dim text: `gridTodayLine` under the
  grid node, `battTodayLine` under the battery node (below `battText`), and `arraysLine`
  under the solar node's watts label. All three are panel-scale only — the card never shows
  them.

## 4. Wiring

- **AdaptiveGeometry** (`ui/model/AdaptiveGeometry.kt`): add
  `fun solarFlowCard(cardWidthDp: Int): Boolean = cardWidthDp >= 300`. Golden pins:
  248 → false (**Show 5 renders the unchanged pill by construction**), 300 → true,
  320 → true.
- **DashboardShell**: compute
  `val solarGraph = remember(entities, config.entities.solar) { solarFlowGraph(config.entities.solar, entities) }`
  alongside the existing `solarCard` computation; pass to `HomeView(solarGraph = …)` and
  `SolarPanel(solarGraph)`.
- **HomeView**: new parameter `solarGraph: SolarFlowGraph? = null`. Card stack branch:

  ```kotlin
  if (solarFlowCard(cardWidth.value.toInt()) && solarGraph != null) {
      SolarFlowCardView(solarGraph, cardWidth)
  } else if (solar != null) {
      SolarCardView(solar, cardWidth)   // unchanged compact pill
  }
  ```

  `SolarFlowCardView(graph, cardWidth)`: same card chrome as the pill (black 0.35 bg,
  RoundedCornerShape(20), 16/10 padding), containing
  `SolarFlowDiagram(Modifier.fillMaxWidth().height(cardWidth * 0.78f))` then the `todayLine`
  footer (11 sp, white 0.7 alpha, centered, single line) when present. Card totals ≈ 280 dp
  tall at the 300 dp tier — worst-case stack (2 charging EVs + solar) ≈ 550 dp of the Show 8's
  601 dp. Fits; no cap needed.
- **SolarPanel** (`ui/panels/SolarPanel.kt`): signature becomes
  `SolarPanel(graph: SolarFlowGraph?)`. Null/empty → existing EmptyHint. Otherwise
  `SolarFlowDiagram(Modifier.weight(1f).fillMaxWidth(), showDailyDetail = true)` with
  `todayLine` beneath (16 sp, as today). The old Node/Arrow composables are deleted.
- Night mode, takeover, and card fade behavior are untouched (the flow card sits in the same
  AnimatedVisibility slot the pill uses; `reserveCardColumn` logic unchanged).

## 5. Testing (plain JVM, JUnit4)

`SolarModelTest` additions — derivation scenarios with exact expected edge sets/watts:

1. Sunny surplus (pv 5 kW, charge 1 kW, export 2 kW, load 2 kW): S→B 1000, S→G 2000,
   S→H 2000.
2. Night discharge (pv 0, discharge 800, import 300): B→H 800, G→H 300.
3. Grid-assisted charge (pv 200, charge 1500, import 1400): S→B 200, G→B 1300, G→H 100.
4. Deadband: a derived 49 W edge is absent; 51 W present.
5. No battery sensors: chargeW/dischargeW = 0, no battery edges, battery node absent.
6. No grid sensor: no grid node/edges; S→H = pv remainder.
7. kW-unit sensors (evcc style) scale ×1000 in edge watts.
8. Negative pv clamps to 0.
9. `gridTodayLine`/`battTodayLine`: both sensors, in-only, out-only, neither.
9b. `arraysLine`: 4 arrays, blank names fall back A–D, "watts" unit formats as W, none → null.
10. `flowLapMs`: 50 → 4000, 2025 → 2600, 4000 → 1200, 10 → 4000 (clamp), 9000 → 1200 (clamp).
11. Null graph when nothing resolves; graph non-null with pv alone.

`AdaptiveGeometryTest`: `solarFlowCard` pins (248/300/320). Existing `solarCard()` tests
unchanged and must stay green.

Renderer verification is on-device (no screenshot tests in this repo): Show 8 + M9 card and
panel eyeball, Show 5 must show the unchanged pill.

## 6. Non-goals

- Per-PANEL Tigo detail (per-array is in; individual optimizer sensors are not),
  inverter internals (voltages/frequencies/temps), tariffs/earnings, solar forecast, and
  history sparklines (needs a new HA history API surface) — all future asks.
- BATTERY→GRID edge (rare topology).
- Forcing the diagram's flows to balance against node totals.
- Any change to the Show 5 (248 dp tier) card, `solarCard()`, or the EV cards.

## 7. Verification

- Gate: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- Flash Show 8 + M9: home card shows the diamond with dots flowing (screencap both), SOLAR
  panel shows the large diagram + Today line (screencap). Show 5: pill unchanged (eyeball —
  its screencap is unreliable).
- Config page: assign the six `*_today` utility-meter helpers (table above — including
  REPLACING the pvToday/loadToday lifetime-counter assignments) and the four
  `sensor.solar_array_a`–`d` slots; confirm the `↓ · ↑` and array lines appear at panel
  scale and survive export/import. Helper values are partial until their first midnight
  rollover — verify magnitudes the day after assignment.
