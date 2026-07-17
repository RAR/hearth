# Solar Flow Card & Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal**

On the big card-width tiers (card width ≥ 300 dp — Echo Show 8, Tab M9) replace the compact solar pill with an animated HA-energy-distribution diamond (Solar top, Grid left, Home right, Battery bottom) with flowing dots and a battery SOC ring, and re-render the full-screen SOLAR panel with the same shared renderer. The Echo Show 5 (248 dp tier) keeps its current pill byte-for-byte.

**Architecture**

A pure model (`ui/model/SolarModel.kt`) derives an immutable `SolarFlowGraph` (present nodes, active `FlowEdge`s, formatted texts, daily lines) from the configured sensors via solar-first attribution — plain-JVM unit-tested. A single Compose renderer (`ui/SolarFlowDiagram.kt`) draws that graph, scaling all geometry from `min(width, height)`, so the identical composable serves both the home card (`HomeView.SolarFlowCardView`) and the panel (`SolarPanel`). `DashboardShell` computes the graph and the tier gate (`AdaptiveGeometry.solarFlowCard`) selects diagram-vs-pill.

**Tech Stack**

Kotlin 2.1.0 (JVM target 17), Jetpack Compose (Compose BOM, material-icons-extended 1.7.6), kotlinx.serialization, JUnit4 (plain JVM). Package `com.rar.echodash`. No new dependencies.

## Global Constraints

- **SDK pins:** `compileSdk`/`targetSdk` stay at **34** (never bump); `minSdk = 28` (never bump).
- **NO new dependencies** on either side (app deps in `app/build.gradle.kts` are deliberately minimal). Everything here uses already-present artifacts — `Icons.Outlined.Bolt` is confirmed present in the bundled `material-icons-extended-1.7.6` (`outlined/BoltKt.class`), so no fallback to `ElectricBolt` is needed.
- **Tests are plain-JVM JUnit4 only** — no instrumented tests, no Robolectric. Files under `ui/model` must have **zero android/androidx imports** (Compose-free kotlin stdlib only — `AdaptiveGeometry.kt`'s sole import is `kotlin.math.floor`; `SolarModel.kt` already imports only `config.SolarConfig`, `ha.EntityState`, `java.util.Locale`, `kotlin.math.*`). Keep that discipline for all model code. The renderer file (`ui/SolarFlowDiagram.kt`) is Compose and therefore **untested — its gate is compilation**, stated explicitly in that task.
- **Comments explain *why*, not *what*.** Match surrounding style.
- **The gate for every task** is `./gradlew :app:testDebugUnitTest :app:assembleDebug`. If `JAVA_HOME` is wrong, prefix `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto`. Run the full gate green before every commit.
- **Work directly on `master`.** Keep commits small and focused.
- **Every commit message ends with the trailer line** `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`.
- **GOLDEN RULE:** the Echo Show 5 (787×394 dp → 248 dp card tier) must render **pixel-identically** to today. `solarFlowCard(248)` returns `false` (pinned by test), so the Show 5 always takes the existing pill branch. `solarCard()`, `SolarCard`, `BattFlow`, `SolarCardView`, and the compact-pill path stay untouched.

---

## Task 1 — SolarModel: the flow graph

Adds `FlowNodeId`/`FlowEdge`/`SolarFlowGraph`, `solarFlowGraph()`, `flowLapMs()`, and three private line-builders to `ui/model/SolarModel.kt`, plus the `FLOW_DEADBAND_W = 50.0` constant. **The old `SolarFlow`/`solarFlow()`/`SolarNode` stay for now** (their consumers — `SolarPanel`, `DashboardShell`, two existing tests — are not migrated until Task 6, and every task must gate green). Deletion happens in Task 6.

This task also depends on Task 3's new `SolarConfig` fields (`gridImportToday`, `gridExportToday`, `battInToday`, `battOutToday`, `arrays`). To keep tasks independently gate-green, **do Task 3 before Task 1** OR fold the `SolarConfig` field additions into this task first. This plan sequences **Task 3 (config) before Task 1 (model)** in execution; the task numbers below are logical, execute config first. (If you prefer numeric order, add the five `SolarConfig` fields + `SolarArrayConfig` from Task 3 as the first step here.)

**Files**
- Modify: `app/src/main/java/com/rar/echodash/ui/model/SolarModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/SolarModelTest.kt`

**Interfaces**
- Consumes: `SolarConfig` (with Task 3 fields), `Map<String, EntityState>`; existing private `powerWatts(EntityState): Double?`, `formatWatts(EntityState): String`; existing `BattFlow`, `CHARGE_DEADBAND_W`.
- Produces:
  - `enum class FlowNodeId { SOLAR, HOME, GRID, BATTERY }`
  - `data class FlowEdge(val from: FlowNodeId, val to: FlowNodeId, val watts: Double)`
  - `data class SolarFlowGraph(solarText: String?, homeText: String?, gridText: String?, socPct: Int?, battText: String?, battFlow: BattFlow, edges: List<FlowEdge>, todayLine: String?, gridTodayLine: String?, battTodayLine: String?, arraysLine: String?)`
  - `fun solarFlowGraph(cfg: SolarConfig, entities: Map<String, EntityState>): SolarFlowGraph?`
  - `fun flowLapMs(watts: Double): Int`

### Steps

- [ ] Add the `FLOW_DEADBAND_W` constant beside the existing deadbands. Old block (lines ~62-63):

  ```kotlin
  private const val CHARGE_DEADBAND_W = 50.0
  private const val GRID_DEADBAND_W = 50.0
  ```

  New:

  ```kotlin
  private const val CHARGE_DEADBAND_W = 50.0
  private const val GRID_DEADBAND_W = 50.0
  // A derived flow edge is drawn only above this; same 50 W floor as the pill's deadbands.
  private const val FLOW_DEADBAND_W = 50.0
  ```

- [ ] Insert the graph model, derivation, dot-speed, and line-builders immediately after the existing `solarFlow(...)` function (after its closing `}` on line ~46, before `/** Active battery flow ... */ enum class BattFlow`). Complete inserted block:

  ```kotlin
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

  /** "Today: X produced · Y used"; null when neither -today sensor resolves. Each value keeps its
   *  own unit (default kWh). */
  private fun todayLine(pvToday: EntityState?, loadToday: EntityState?): String? =
      buildString {
          pvToday?.let { append("${it.state} ${it.attr("unit_of_measurement") ?: "kWh"} produced") }
          loadToday?.let {
              if (isNotEmpty()) append(" · ")
              append("${it.state} ${it.attr("unit_of_measurement") ?: "kWh"} used")
          }
      }.takeIf { it.isNotEmpty() }?.let { "Today: $it" }

  /** "↓ {in} {unit} · ↑ {out} {unit}"; either sensor alone renders alone; null when neither
   *  resolves. For the battery, ↓ = charged, ↑ = discharged. */
  private fun arrowLine(inSensor: EntityState?, outSensor: EntityState?): String? =
      buildString {
          inSensor?.let { append("↓ ${it.state} ${it.attr("unit_of_measurement") ?: "kWh"}") }
          outSensor?.let {
              if (isNotEmpty()) append(" · ")
              append("↑ ${it.state} ${it.attr("unit_of_measurement") ?: "kWh"}")
          }
      }.takeIf { it.isNotEmpty() }

  /** "{name} {watts}" for each array whose power sensor resolves, joined by " · "; blank names fall
   *  back to A..D by slot index. formatWatts handles the Tigo sensors' lowercase "watts" unit as W.
   *  Null when no array power sensor resolves. */
  private fun arraysLine(cfg: SolarConfig, entities: Map<String, EntityState>): String? =
      cfg.arrays.mapIndexedNotNull { i, a ->
          val s = a.power?.let { entities[it] } ?: return@mapIndexedNotNull null
          val name = a.name.ifBlank { ('A' + i).toString() }
          "$name ${formatWatts(s)}"
      }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  ```

- [ ] Add `import com.rar.echodash.config.SolarArrayConfig` to `SolarModelTest.kt` (after the existing `import com.rar.echodash.config.SolarConfig` line) — the new array test constructs it. `FlowNodeId`/`FlowEdge` need no import (same package).

- [ ] Add the derivation test scenarios to `SolarModelTest.kt`. Insert these methods inside the existing `class SolarModelTest { ... }` (reuse the existing `st(...)` helper). Complete methods:

  ```kotlin
      @Test
      fun sunnySurplusDerivationAndNodeTexts() {
          // pv 5 kW, charge 1 kW (batt -1000), export 2 kW (grid -2000), load 2 kW.
          val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
              battPower = "sensor.batt", pvToday = "sensor.pvday", loadToday = "sensor.loadday")
          val g = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "5000", "W"),
              "sensor.load" to st("sensor.load", "2000", "W"),
              "sensor.grid" to st("sensor.grid", "-2000", "W"),
              "sensor.batt" to st("sensor.batt", "-1000", "W"),
              "sensor.pvday" to st("sensor.pvday", "12.4", "kWh"),
              "sensor.loadday" to st("sensor.loadday", "9.1", "kWh"),
          ))!!
          assertEquals(
              listOf(
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, 1000.0),
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.GRID, 2000.0),
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 2000.0),
              ),
              g.edges,
          )
          assertEquals("5.0 kW", g.solarText)
          assertEquals("2.0 kW", g.homeText) // formatWatts rolls W magnitudes >= 1000 up to kW
          assertEquals("2.0 kW", g.gridText)
          assertEquals(BattFlow.CHARGING, g.battFlow)
          assertEquals("Today: 12.4 kWh produced · 9.1 kWh used", g.todayLine)
      }

      @Test
      fun nightDischargeDerivation() {
          // pv 0, discharge 800 (batt +800), import 300 (grid +300).
          val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
              battPower = "sensor.batt")
          val g = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "0", "W"),
              "sensor.load" to st("sensor.load", "1100", "W"),
              "sensor.grid" to st("sensor.grid", "300", "W"),
              "sensor.batt" to st("sensor.batt", "800", "W"),
          ))!!
          assertEquals(
              listOf(
                  FlowEdge(FlowNodeId.BATTERY, FlowNodeId.HOME, 800.0),
                  FlowEdge(FlowNodeId.GRID, FlowNodeId.HOME, 300.0),
              ),
              g.edges,
          )
          assertEquals(BattFlow.DISCHARGING, g.battFlow)
      }

      @Test
      fun gridAssistedChargeDerivation() {
          // pv 200, charge 1500 (batt -1500), import 1400 (grid +1400).
          val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
              battPower = "sensor.batt")
          val g = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "200", "W"),
              "sensor.load" to st("sensor.load", "100", "W"),
              "sensor.grid" to st("sensor.grid", "1400", "W"),
              "sensor.batt" to st("sensor.batt", "-1500", "W"),
          ))!!
          assertEquals(
              listOf(
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, 200.0),
                  FlowEdge(FlowNodeId.GRID, FlowNodeId.BATTERY, 1300.0),
                  FlowEdge(FlowNodeId.GRID, FlowNodeId.HOME, 100.0),
              ),
              g.edges,
          )
      }

      @Test
      fun deadbandEdgeThreshold() {
          // pv-only, no batt/grid: S→H = pvW. 49 W absent, 51 W present.
          val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load")
          val at49 = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "49", "W"),
              "sensor.load" to st("sensor.load", "49", "W"),
          ))!!
          assertEquals(emptyList<FlowEdge>(), at49.edges)
          val at51 = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "51", "W"),
              "sensor.load" to st("sensor.load", "51", "W"),
          ))!!
          assertEquals(listOf(FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 51.0)), at51.edges)
      }

      @Test
      fun noBatterySensorsHaveNoBatteryNodeOrEdges() {
          // pv 3 kW, export 1 kW (grid -1000), load 2 kW; no batt sensors.
          val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid")
          val g = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "3000", "W"),
              "sensor.load" to st("sensor.load", "2000", "W"),
              "sensor.grid" to st("sensor.grid", "-1000", "W"),
          ))!!
          assertNull(g.socPct)
          assertNull(g.battText)
          assertEquals(
              listOf(
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.GRID, 1000.0),
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 2000.0),
              ),
              g.edges,
          )
          assertTrue(g.edges.none { it.from == FlowNodeId.BATTERY || it.to == FlowNodeId.BATTERY })
      }

      @Test
      fun noGridSensorHasNoGridNodeOrEdges() {
          // pv 3 kW, charge 1 kW (batt -1000); no grid sensor.
          val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", battPower = "sensor.batt")
          val g = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "3000", "W"),
              "sensor.load" to st("sensor.load", "2000", "W"),
              "sensor.batt" to st("sensor.batt", "-1000", "W"),
          ))!!
          assertNull(g.gridText)
          assertEquals(
              listOf(
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, 1000.0),
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 2000.0),
              ),
              g.edges,
          )
          assertTrue(g.edges.none { it.from == FlowNodeId.GRID || it.to == FlowNodeId.GRID })
      }

      @Test
      fun kwUnitSensorsScaleToWatts() {
          // Same topology as sunnySurplus but every sensor in kW (evcc style).
          val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
              battPower = "sensor.batt")
          val g = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "5", "kW"),
              "sensor.load" to st("sensor.load", "2", "kW"),
              "sensor.grid" to st("sensor.grid", "-2", "kW"),
              "sensor.batt" to st("sensor.batt", "-1", "kW"),
          ))!!
          assertEquals(
              listOf(
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, 1000.0),
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.GRID, 2000.0),
                  FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 2000.0),
              ),
              g.edges,
          )
      }

      @Test
      fun negativePvClampsToZero() {
          // pv -100 (inverter standby), load only: no edges (pvW = 0), graph still non-null.
          val g = solarFlowGraph(SolarConfig(pv = "sensor.pv", load = "sensor.load"), mapOf(
              "sensor.pv" to st("sensor.pv", "-100", "W"),
              "sensor.load" to st("sensor.load", "800", "W"),
          ))!!
          assertEquals(emptyList<FlowEdge>(), g.edges)
          assertEquals("100 W", g.solarText) // formatWatts is magnitude-only
      }

      @Test
      fun gridTodayLineVariants() {
          val cfg = { imp: String?, exp: String? -> SolarConfig(pv = "sensor.pv",
              gridImportToday = imp?.let { "sensor.gi" }, gridExportToday = exp?.let { "sensor.ge" }) }
          fun line(imp: String?, exp: String?): String? {
              val e = buildMap {
                  put("sensor.pv", st("sensor.pv", "0", "W"))
                  imp?.let { put("sensor.gi", st("sensor.gi", it, "kWh")) }
                  exp?.let { put("sensor.ge", st("sensor.ge", it, "kWh")) }
              }
              return solarFlowGraph(cfg(imp, exp), e)!!.gridTodayLine
          }
          assertEquals("↓ 3.2 kWh · ↑ 2.2 kWh", line("3.2", "2.2"))
          assertEquals("↓ 3.2 kWh", line("3.2", null))
          assertEquals("↑ 2.2 kWh", line(null, "2.2"))
          assertNull(line(null, null))
      }

      @Test
      fun battTodayLineVariants() {
          val cfg = { i: String?, o: String? -> SolarConfig(pv = "sensor.pv",
              battInToday = i?.let { "sensor.bi" }, battOutToday = o?.let { "sensor.bo" }) }
          fun line(i: String?, o: String?): String? {
              val e = buildMap {
                  put("sensor.pv", st("sensor.pv", "0", "W"))
                  i?.let { put("sensor.bi", st("sensor.bi", it, "kWh")) }
                  o?.let { put("sensor.bo", st("sensor.bo", it, "kWh")) }
              }
              return solarFlowGraph(cfg(i, o), e)!!.battTodayLine
          }
          assertEquals("↓ 5.1 kWh · ↑ 4.2 kWh", line("5.1", "4.2")) // ↓ charged, ↑ discharged
          assertEquals("↓ 5.1 kWh", line("5.1", null))
          assertEquals("↑ 4.2 kWh", line(null, "4.2"))
          assertNull(line(null, null))
      }

      @Test
      fun arraysLineFormatsFallbackAndNull() {
          val cfg = SolarConfig(pv = "sensor.pv", arrays = listOf(
              SolarArrayConfig(power = "sensor.a"),
              SolarArrayConfig(power = "sensor.b"),
              SolarArrayConfig(power = "sensor.c"),
              SolarArrayConfig(power = "sensor.d"),
          ))
          val g = solarFlowGraph(cfg, mapOf(
              "sensor.pv" to st("sensor.pv", "0", "W"),
              "sensor.a" to st("sensor.a", "447", "watts"),
              "sensor.b" to st("sensor.b", "768", "watts"),
              "sensor.c" to st("sensor.c", "395", "watts"),
              "sensor.d" to st("sensor.d", "276", "watts"),
          ))!!
          assertEquals("A 447 W · B 768 W · C 395 W · D 276 W", g.arraysLine)
          // No array sensors -> null.
          assertNull(solarFlowGraph(SolarConfig(pv = "sensor.pv"),
              mapOf("sensor.pv" to st("sensor.pv", "0", "W")))!!.arraysLine)
      }

      @Test
      fun flowLapMsCurveAndClamps() {
          assertEquals(4000, flowLapMs(50.0))
          assertEquals(2600, flowLapMs(2025.0))
          assertEquals(1200, flowLapMs(4000.0))
          assertEquals(4000, flowLapMs(10.0))   // below range clamps up
          assertEquals(1200, flowLapMs(9000.0)) // above range clamps down
      }

      @Test
      fun nullWhenNothingResolvesAndNonNullWithPvAlone() {
          assertNull(solarFlowGraph(SolarConfig(), emptyMap()))
          // battPower alone never conjures a diagram (same rule as solarCard()).
          assertNull(solarFlowGraph(SolarConfig(battPower = "sensor.batt"),
              mapOf("sensor.batt" to st("sensor.batt", "-500", "W"))))
          val g = solarFlowGraph(SolarConfig(pv = "sensor.pv"),
              mapOf("sensor.pv" to st("sensor.pv", "1200", "W")))!!
          assertEquals("1.2 kW", g.solarText)
          assertNull(g.gridText)
      }
  ```

- [ ] Run the test file only to confirm the new derivation math: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.SolarModelTest"` — **expected: BUILD SUCCESSFUL, all SolarModelTest methods green** (old `solarFlow` tests still present and passing).

- [ ] **Gate:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` (prefix `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto` if needed) — expected BUILD SUCCESSFUL.

- [ ] **Commit:**

  ```
  feat(solar): SolarFlowGraph model + solar-first flow derivation

  Adds FlowNodeId/FlowEdge/SolarFlowGraph, solarFlowGraph() (solar-first
  edge attribution, FLOW_DEADBAND_W=50), flowLapMs(), and the today/grid/
  batt/arrays line builders. Old SolarFlow/solarFlow kept until SolarPanel
  migrates (Task 6). 13 new derivation tests.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

## Task 2 — AdaptiveGeometry.solarFlowCard tier gate

**Files**
- Modify: `app/src/main/java/com/rar/echodash/ui/model/AdaptiveGeometry.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/AdaptiveGeometryTest.kt`

**Interfaces**
- Produces: `fun solarFlowCard(cardWidthDp: Int): Boolean`

### Steps

- [ ] Add the gate function after `solarStatsCompact` (after line ~74). New block:

  ```kotlin
  /** True at the 300dp+ card tiers: the home solar card shows the animated flow diagram instead of
   *  the compact pill. False at the Show 5's 248dp tier by construction — the golden rule (the pill
   *  is untouched there). Discrete like homeCardWidthDp: cards are fixed-size, not scaled. */
  fun solarFlowCard(cardWidthDp: Int): Boolean = cardWidthDp >= 300
  ```

- [ ] Add the golden pins to `AdaptiveGeometryTest.kt` (new method inside the class):

  ```kotlin
      // ---- solarFlowCard ----

      @Test
      fun solarFlowCardTierPins() {
          assertFalse(solarFlowCard(248))  // Show 5 tier: unchanged pill by construction (golden rule)
          assertTrue(solarFlowCard(300))   // Show 8 tier
          assertTrue(solarFlowCard(320))   // Tab M9 tier
      }
  ```

- [ ] **Gate:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — expected BUILD SUCCESSFUL, `AdaptiveGeometryTest` green (existing `solarCard`/tier tests unchanged).

- [ ] **Commit:**

  ```
  feat(solar): solarFlowCard(300) tier gate + golden pins

  248→false (Show 5 keeps the pill), 300/320→true. Pinned by test.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

## Task 3 — Config: new solar sensors + array slots

Adds four `-today` fields, an `arrays` list, and a sibling `SolarArrayConfig` to `SolarConfig`; wires `ids()`, `clamped()`, the web config page, and round-trip tests. Serialization round-trips automatically (kotlinx `encodeDefaults=true`, `ignoreUnknownKeys=true` — GET/PUT `/api/config` and export/import all go through `ConfigJson`; **no `ConfigServer.kt` or `index.html` change needed**). `EntityHub` subscribes exactly what `referencedEntityIds()` → `SolarConfig.ids()` returns, so extending `ids()` is the only subscription hook.

**Note on execution order:** run this task **before Task 1** so `solarFlowGraph()` and its tests compile against the new fields.

**Files**
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
- Modify: `app/src/main/assets/config/app.js`
- Test: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`

**Interfaces**
- Produces: `SolarConfig` (+5 fields), `data class SolarArrayConfig(name: String = "", power: String? = null)`.

### Steps

- [ ] Extend `SolarConfig` and add `SolarArrayConfig`. Old block (lines ~23-34):

  ```kotlin
  @Serializable
  data class SolarConfig(
      val pv: String? = null,
      val load: String? = null,
      val grid: String? = null,
      val pvToday: String? = null,
      val loadToday: String? = null,
      val battSoc: String? = null,   // home battery % sensor
      val battPower: String? = null, // battery power W/kW; negative = charging (evcc convention)
  ) {
      fun ids(): List<String> = listOfNotNull(pv, load, grid, pvToday, loadToday, battSoc, battPower)
  }
  ```

  New:

  ```kotlin
  @Serializable
  data class SolarConfig(
      val pv: String? = null,
      val load: String? = null,
      val grid: String? = null,
      val pvToday: String? = null,
      val loadToday: String? = null,
      val battSoc: String? = null,   // home battery % sensor
      val battPower: String? = null, // battery power W/kW; negative = charging (evcc convention)
      val gridImportToday: String? = null, // kWh energy sensor, grid → house today
      val gridExportToday: String? = null, // kWh energy sensor, house → grid today
      val battInToday: String? = null,     // kWh energy sensor, charged into battery today
      val battOutToday: String? = null,    // kWh energy sensor, discharged from battery today
      val arrays: List<SolarArrayConfig> = emptyList(), // up to 4 per-array PV power sensors
  ) {
      fun ids(): List<String> = listOfNotNull(
          pv, load, grid, pvToday, loadToday, battSoc, battPower,
          gridImportToday, gridExportToday, battInToday, battOutToday,
      ) + arrays.mapNotNull { it.power }
  }

  /** One per-array/string PV power sensor. [name] blank falls back to "A".."D" by slot index. */
  @Serializable
  data class SolarArrayConfig(
      val name: String = "",
      val power: String? = null,
  )
  ```

- [ ] Extend the `solar` clean in `clamped()`. Old block (lines ~292-300):

  ```kotlin
                  solar = entities.solar.copy(
                      pv = entities.solar.pv?.trim()?.ifBlank { null },
                      load = entities.solar.load?.trim()?.ifBlank { null },
                      grid = entities.solar.grid?.trim()?.ifBlank { null },
                      pvToday = entities.solar.pvToday?.trim()?.ifBlank { null },
                      loadToday = entities.solar.loadToday?.trim()?.ifBlank { null },
                      battSoc = entities.solar.battSoc?.trim()?.ifBlank { null },
                      battPower = entities.solar.battPower?.trim()?.ifBlank { null },
                  ),
  ```

  New:

  ```kotlin
                  solar = entities.solar.copy(
                      pv = entities.solar.pv?.trim()?.ifBlank { null },
                      load = entities.solar.load?.trim()?.ifBlank { null },
                      grid = entities.solar.grid?.trim()?.ifBlank { null },
                      pvToday = entities.solar.pvToday?.trim()?.ifBlank { null },
                      loadToday = entities.solar.loadToday?.trim()?.ifBlank { null },
                      battSoc = entities.solar.battSoc?.trim()?.ifBlank { null },
                      battPower = entities.solar.battPower?.trim()?.ifBlank { null },
                      gridImportToday = entities.solar.gridImportToday?.trim()?.ifBlank { null },
                      gridExportToday = entities.solar.gridExportToday?.trim()?.ifBlank { null },
                      battInToday = entities.solar.battInToday?.trim()?.ifBlank { null },
                      battOutToday = entities.solar.battOutToday?.trim()?.ifBlank { null },
                      arrays = entities.solar.arrays
                          .map { it.copy(name = it.name.trim(), power = it.power?.trim()?.ifBlank { null }) }
                          .filter { it.name.isNotBlank() || it.power != null }
                          .take(4),
                  ),
  ```

- [ ] Extend the Solar card in the web config page. Old block (`app.js` lines ~435-444):

  ```javascript
    host.appendChild(subhead("solar", "Solar"));
    const solarSlots = [["pv", "PV power"], ["load", "Home load"], ["grid", "Grid power"],
      ["pvToday", "PV today"], ["loadToday", "Load today"],
      ["battSoc", "Battery %"], ["battPower", "Battery power"]];
    solarSlots.forEach(([k, lbl]) => {
      host.appendChild(labeledRow(lbl, entityPicker(["sensor"], e.solar[k], v => e.solar[k] = v)));
    });
    host.appendChild(el("div", "muted",
      "Battery % and battery power add a solar card to the home screen (gauge shimmers green while charging, amber in reverse while discharging). " +
      "Battery power: negative = charging (evcc convention). Grid power: positive = importing."));
  ```

  New:

  ```javascript
    host.appendChild(subhead("solar", "Solar"));
    const solarSlots = [["pv", "PV power"], ["load", "Home load"], ["grid", "Grid power"],
      ["pvToday", "PV today"], ["loadToday", "Load today"],
      ["gridImportToday", "Grid import today (kWh)"], ["gridExportToday", "Grid export today (kWh)"],
      ["battInToday", "Battery charged today (kWh)"], ["battOutToday", "Battery discharged today (kWh)"],
      ["battSoc", "Battery %"], ["battPower", "Battery power"]];
    solarSlots.forEach(([k, lbl]) => {
      host.appendChild(labeledRow(lbl, entityPicker(["sensor"], e.solar[k], v => e.solar[k] = v)));
    });
    host.appendChild(el("div", "muted",
      "Battery % and battery power add a solar card to the home screen (gauge shimmers green while charging, amber in reverse while discharging). " +
      "Battery power: negative = charging (evcc convention). Grid power: positive = importing."));

    // solar array slots (per-string PV power; panel-scale detail only). Fixed four slots like EVs.
    if (!Array.isArray(e.solar.arrays)) e.solar.arrays = [];
    const solarArrays = e.solar.arrays;
    while (solarArrays.length < 4) solarArrays.push({});
    solarArrays.slice(0, 4).forEach((slot, i) => {
      const box = el("div", "group");
      const head = el("div", "group-head");
      head.appendChild(el("span", "panel-name", "Array " + String.fromCharCode(65 + i)));
      box.appendChild(head);
      const name = el("input");
      name.value = slot.name || "";
      name.setAttribute("aria-label", "Array name");
      name.addEventListener("change", () => slot.name = name.value.trim());
      box.appendChild(labeledRow("Name", name));
      box.appendChild(labeledRow("PV power",
        entityPicker(["sensor"], slot.power, v => slot.power = v)));
      host.appendChild(box);
    });
    host.appendChild(el("div", "muted",
      "Per-array PV power (e.g. TigoMonitor sensor.solar_array_a–d) shows on the full-screen Solar panel only. " +
      "Blank name falls back to A–D. Empty slots are dropped on save."));
  ```

- [ ] Add config round-trip / clamp / referenced-id tests to `DashConfigTest.kt` (new methods inside the class):

  ```kotlin
      @Test
      fun solarTodayAndArrayFieldsRoundTrip() {
          val cfg = DashConfig(
              entities = Entities(
                  solar = SolarConfig(
                      pv = "sensor.pv",
                      gridImportToday = "sensor.gi", gridExportToday = "sensor.ge",
                      battInToday = "sensor.bi", battOutToday = "sensor.bo",
                      arrays = listOf(
                          SolarArrayConfig(name = "South", power = "sensor.solar_array_a"),
                          SolarArrayConfig(power = "sensor.solar_array_b"),
                      ),
                  ),
              ),
          )
          val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
          assertEquals(cfg, decodeConfig(text))
          // old configs (no keys) decode to defaults
          val old = decodeConfig("""{"version":1}""")
          assertEquals(emptyList<SolarArrayConfig>(), old.entities.solar.arrays)
          assertEquals(null, old.entities.solar.gridImportToday)
      }

      @Test
      fun clampedSolarArraysTrimmedDroppedAndCapped() {
          val cfg = DashConfig(
              entities = Entities(
                  solar = SolarConfig(
                      gridImportToday = "  sensor.gi  ", battOutToday = "  ",
                      arrays = listOf(
                          SolarArrayConfig(name = "  South  ", power = "  sensor.a  "),
                          SolarArrayConfig(name = "", power = "sensor.b"),
                          SolarArrayConfig(name = "  ", power = "  "),          // all blank -> dropped
                          SolarArrayConfig(name = "Named", power = null),       // named, no power -> kept
                          SolarArrayConfig(name = "Fifth", power = "sensor.e"), // 5th valid -> capped out
                      ),
                  ),
              ),
          ).clamped().entities.solar
          assertEquals("sensor.gi", cfg.gridImportToday)
          assertEquals(null, cfg.battOutToday)
          assertEquals(4, cfg.arrays.size)
          assertEquals(SolarArrayConfig("South", "sensor.a"), cfg.arrays[0])
          assertEquals(SolarArrayConfig("", "sensor.b"), cfg.arrays[1])
          assertEquals(SolarArrayConfig("Named", null), cfg.arrays[2])
          assertEquals(SolarArrayConfig("Fifth", "sensor.e"), cfg.arrays[3])
      }

      @Test
      fun referencedEntityIdsIncludeSolarTodayAndArrayPower() {
          val cfg = DashConfig(
              entities = Entities(
                  solar = SolarConfig(
                      pv = "sensor.pv",
                      gridImportToday = "sensor.gi", gridExportToday = "sensor.ge",
                      battInToday = "sensor.bi", battOutToday = "sensor.bo",
                      arrays = listOf(
                          SolarArrayConfig(power = "sensor.a"),
                          SolarArrayConfig(name = "b-only-name"), // no power -> contributes nothing
                          SolarArrayConfig(power = "sensor.c"),
                      ),
                  ),
              ),
          )
          assertEquals(
              listOf("sensor.pv", "sensor.gi", "sensor.ge", "sensor.bi", "sensor.bo", "sensor.a", "sensor.c"),
              cfg.referencedEntityIds(),
          )
      }
  ```

- [ ] **Gate:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — expected BUILD SUCCESSFUL, `DashConfigTest` green.

- [ ] **Commit:**

  ```
  feat(config): solar grid/batt -today sensors + 4 per-array power slots

  Adds gridImportToday/gridExportToday/battInToday/battOutToday and an
  arrays: List<SolarArrayConfig>; ids() + clamped() + web config page (4
  today pickers, 4 fixed array slots). Serialization/export-import round
  trips for free. 3 new config tests.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

## Task 4 — SolarFlowDiagram renderer (Compose; no JVM test — gate is compilation)

A new Compose file drawing the graph. **This task has no unit test — Compose is untestable in this repo's plain-JVM harness, so the gate is that `:app:assembleDebug` compiles.** All geometry scales from `min(width, height)`; no `LocalConfiguration`.

**Files**
- Create: `app/src/main/java/com/rar/echodash/ui/SolarFlowDiagram.kt`

**Interfaces**
- Consumes: `SolarFlowGraph`, `FlowNodeId`, `FlowEdge`, `flowLapMs`, `BattFlow` (from `ui.model`); `androidx.compose.ui.graphics.PathMeasure`; `Icons.Outlined.SolarPower/Home/Bolt/BatteryStd`.
- Produces: `@Composable fun SolarFlowDiagram(graph: SolarFlowGraph, modifier: Modifier = Modifier, showDailyDetail: Boolean = false)`.

### Steps

- [ ] Create `app/src/main/java/com/rar/echodash/ui/SolarFlowDiagram.kt` with this complete content:

  ```kotlin
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
  import androidx.compose.foundation.layout.fillMaxSize
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
                      Text(label, color = Color.White, fontSize = primarySp, maxLines = 1)
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
  ```

- [ ] **Gate (compilation only — no JVM test for this Compose file):** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — expected BUILD SUCCESSFUL. The file is not referenced yet, so success means it compiles. If `Icons.Outlined.Bolt` unexpectedly fails to resolve, substitute `Icons.Outlined.ElectricBolt` (also confirmed present) and note it — but `Bolt` is verified present in `material-icons-extended-1.7.6`.

- [ ] **Commit:**

  ```
  feat(solar): SolarFlowDiagram renderer (animated HA-distribution diamond)

  Compose-only; geometry scales from min(w,h). Nodes at the diamond corners,
  four bowed diagonals + two straight center lines, inactive structure always
  drawn, active edges get source-colored flowing dots (one master 4000ms
  transition, per-edge lap via flowLapMs, PathMeasure positioning). Battery
  node carries the SOC ring. showDailyDetail draws the panel-scale lines.
  No JVM test (Compose) — gate is compilation.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

## Task 5 — HomeView + DashboardShell wiring (card branch)

Adds the `solarGraph` parameter and `SolarFlowCardView` to `HomeView`, the tier-gated card branch, and the graph computation + pass-through in `DashboardShell`'s HOME branch. The SOLAR panel branch and `solarFlow` stay unchanged here (migrated in Task 6), so this gates green.

**Files**
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`

**Interfaces**
- Consumes: `SolarFlowGraph`, `solarFlowCard`, `solarFlowGraph`, `SolarFlowDiagram`.
- Produces: `HomeView(..., solarGraph: SolarFlowGraph? = null, ...)`; private `SolarFlowCardView(graph, cardWidth)`.

### Steps

- [ ] Add imports to `HomeView.kt`. After `import com.rar.echodash.ui.model.SolarCard` (line ~87) add:

  ```kotlin
  import com.rar.echodash.ui.model.SolarFlowGraph
  ```

  After `import com.rar.echodash.ui.model.solarStatsCompact` (line ~94) add:

  ```kotlin
  import com.rar.echodash.ui.model.solarFlowCard
  ```

  `TextAlign` is not yet imported (only `TextOverflow` is). After `import androidx.compose.ui.text.style.TextOverflow` (line ~73) add:

  ```kotlin
  import androidx.compose.ui.text.style.TextAlign
  ```

- [ ] Add the `solarGraph` parameter to `HomeView`. Old block (line ~164):

  ```kotlin
      solar: SolarCard? = null,
  ```

  New:

  ```kotlin
      solar: SolarCard? = null,
      solarGraph: SolarFlowGraph? = null,
  ```

- [ ] Switch the card-stack branch on the tier gate. Old block (line ~336):

  ```kotlin
                      if (solar != null) SolarCardView(solar, cardWidth)
  ```

  New:

  ```kotlin
                      // The 300dp+ tiers (Show 8 / Tab M9) show the animated flow diagram; the Show 5
                      // (248dp) fails solarFlowCard() and keeps the compact pill byte-for-byte.
                      if (solarFlowCard(cardWidth.value.toInt()) && solarGraph != null) {
                          SolarFlowCardView(solarGraph, cardWidth)
                      } else if (solar != null) {
                          SolarCardView(solar, cardWidth)
                      }
  ```

- [ ] Add the `SolarFlowCardView` composable. Insert immediately before `private fun SolarCardView(` (line ~551, before its `/** Home solar pill ... */` doc comment):

  ```kotlin
  /** Big-tier solar card: the shared flow diagram in the pill's chrome, with the "Today" line as a
   *  centered footer. Same black-0.35 / RoundedCornerShape(20) / 16×10 chrome as the pill. */
  @Composable
  private fun SolarFlowCardView(graph: SolarFlowGraph, cardWidth: Dp) {
      Column(
          Modifier
              .width(cardWidth)
              .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
              .padding(horizontal = 16.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
          SolarFlowDiagram(
              graph,
              modifier = Modifier.fillMaxWidth().height(cardWidth * 0.78f),
          )
          graph.todayLine?.let {
              Text(
                  it, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                  maxLines = 1, overflow = TextOverflow.Ellipsis,
                  textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
              )
          }
      }
  }
  ```

- [ ] Compute and pass the graph in `DashboardShell.kt`. Add the import after `import com.rar.echodash.ui.model.solarCard` (line ~47):

  ```kotlin
  import com.rar.echodash.ui.model.solarFlowGraph
  ```

  Old block (lines ~212-222, HOME branch):

  ```kotlin
                      val solar = remember(entities, config.entities.solar) {
                          solarCard(config.entities.solar, entities)
                      }
                      HomeView(
                          photos = if (config.home.slideshowEnabled) photos else emptyList(),
                          slideshowSeconds = config.home.slideshowSeconds,
                          pill = pill,
                          aqi = aqi,
                          rain = rain,
                          evs = evs,
                          solar = solar,
  ```

  New:

  ```kotlin
                      val solar = remember(entities, config.entities.solar) {
                          solarCard(config.entities.solar, entities)
                      }
                      val solarGraph = remember(entities, config.entities.solar) {
                          solarFlowGraph(config.entities.solar, entities)
                      }
                      HomeView(
                          photos = if (config.home.slideshowEnabled) photos else emptyList(),
                          slideshowSeconds = config.home.slideshowSeconds,
                          pill = pill,
                          aqi = aqi,
                          rain = rain,
                          evs = evs,
                          solar = solar,
                          solarGraph = solarGraph,
  ```

- [ ] **Gate:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — expected BUILD SUCCESSFUL. (The SOLAR panel branch still uses the old `solarFlow` — untouched here.)

- [ ] **Commit:**

  ```
  feat(solar): tier-gated flow card on the home screen

  HomeView gains solarGraph + SolarFlowCardView (diagram in the pill chrome +
  Today footer); the card branch picks diagram vs pill via solarFlowCard().
  DashboardShell computes solarGraph alongside solarCard and passes it. Show 5
  keeps the pill. SOLAR panel still on the old model (Task 6).

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

## Task 6 — SolarPanel rewrite + delete old model + on-device verify

Migrates the last `solarFlow` consumer, deletes `SolarFlow`/`solarFlow()`/`SolarNode` and the two old tests that used them, then flashes and eyeballs the three devices.

**Files**
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/SolarPanel.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/model/SolarModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/SolarModelTest.kt`

**Interfaces**
- Produces: `@Composable fun SolarPanel(graph: SolarFlowGraph?)`.
- Removes: `SolarFlow`, `solarFlow(...)`, `SolarNode`.

### Steps

- [ ] Rewrite `SolarPanel.kt` in full:

  ```kotlin
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
  ```

- [ ] Migrate the SOLAR branch in `DashboardShell.kt`. Old block (lines ~275-278):

  ```kotlin
                  DashView.SOLAR -> {
                      val flow = remember(entities, config.entities.solar) { solarFlow(config.entities.solar, entities) }
                      SolarPanel(flow)
                  }
  ```

  New:

  ```kotlin
                  DashView.SOLAR -> {
                      val graph = remember(entities, config.entities.solar) { solarFlowGraph(config.entities.solar, entities) }
                      SolarPanel(graph)
                  }
  ```

  Then remove the now-unused import (line ~48):

  ```kotlin
  import com.rar.echodash.ui.model.solarFlow
  ```

- [ ] Delete the old model. In `SolarModel.kt` remove `data class SolarNode`, `data class SolarFlow`, and `fun solarFlow(...)` — old block (lines ~9-46):

  ```kotlin
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
  ```

  Delete it entirely so the file now opens directly with the graph section (`enum class FlowNodeId`) added in Task 1. (The private `todayLine()` helper added in Task 1 now owns the Today-line logic.)

- [ ] Delete the two old tests in `SolarModelTest.kt` that referenced `solarFlow` (they no longer compile): `formatsWattsAndKwAndGridSignAndToday` and `noGridSensorGivesTwoNodeFlowAndPartialToday` (lines ~17-49). Their coverage (formatWatts/kW/grid-sign/todayLine) is subsumed by Task 1's `sunnySurplusDerivationAndNodeTexts`, `kwUnitSensorsScaleToWatts`, and the today-line tests. All `solarCard(...)` tests stay.

- [ ] **Gate:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — expected BUILD SUCCESSFUL; no remaining reference to `solarFlow`/`SolarFlow`/`SolarNode` (verify with `grep -rn "solarFlow\b\|SolarNode\|\\bSolarFlow\\b" app/src` returning only `solarFlowGraph`/`SolarFlowGraph`/`solarFlowCard`/`SolarFlowDiagram`/`SolarFlowCardView`).

- [ ] **Commit:**

  ```
  feat(solar): SolarPanel consumes SolarFlowGraph; drop old SolarFlow model

  SolarPanel now renders the shared SolarFlowDiagram at panel scale
  (showDailyDetail=true) with the Today line beneath. DashboardShell's SOLAR
  branch computes solarFlowGraph. Deletes SolarFlow/solarFlow/SolarNode and
  their two now-redundant tests.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

- [ ] **Build the APK for flashing** (already produced by the gate): `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Flash + verify Echo Show 8** (USB serial `G6G16D10041406ME`, clean A/B box). Screencap works here.

  ```bash
  APK=app/build/outputs/apk/debug/app-debug.apk
  adb -s G6G16D10041406ME install -r "$APK"
  adb -s G6G16D10041406ME shell "logcat -c; am start -n com.rar.echodash/.MainActivity"
  # wait ~6s for compose to settle
  adb -s G6G16D10041406ME exec-out screencap -p > /tmp/show8-solar-home.png
  adb -s G6G16D10041406ME shell "logcat -d" | grep -Ei "FATAL|AndroidRuntime" || echo "clean"
  ```

  Expect: home card shows the diamond (Solar top, Grid left, Home right, Battery bottom) with dots flowing along active edges and the SOC ring on the battery. Open the SOLAR panel (rail) and screencap again — expect the large diagram + Today line + panel-scale daily lines (`↓ · ↑`, arrays) under the nodes. **Never** run `dumpsys media.audio_flinger`. Use `logcat -d` dumps only, never streaming.

- [ ] **Flash + verify Tab M9** (USB serial `HA1TREYR`). Screencap works. **Gotcha:** its night-clock overlay may be active and consumes all taps — send one wake tap, then act within 60 s.

  ```bash
  adb -s HA1TREYR install -r "$APK"
  adb -s HA1TREYR shell "logcat -c; am start -n com.rar.echodash/.MainActivity"
  adb -s HA1TREYR shell input tap 670 400   # wake tap if the night overlay is up
  # act within 60s
  adb -s HA1TREYR exec-out screencap -p > /tmp/m9-solar-home.png
  adb -s HA1TREYR shell "logcat -d" | grep -Ei "FATAL|AndroidRuntime" || echo "clean"
  ```

  Expect: same diamond at the 300 dp tier (the M9 is 1072 dp wide — under the 1200 dp cutoff); SOLAR panel large diagram + Today/daily lines.

- [ ] **Flash + verify Echo Show 5** (network adb `10.75.1.98:5555`). **Screencap is unreliable here (stale buffer) — eyeball only.** Connect first; retry if asleep.

  ```bash
  adb connect 10.75.1.98:5555   # retry if the device is asleep
  adb -s 10.75.1.98:5555 install -r "$APK"
  adb -s 10.75.1.98:5555 shell "logcat -c; am start -n com.rar.echodash/.MainActivity"
  adb -s 10.75.1.98:5555 shell "logcat -d" | grep -Ei "FATAL|AndroidRuntime" || echo "clean"
  ```

  Eyeball: the home solar card is the **unchanged compact pill** (icon + PV text + battery gauge bar + battery/home/grid stats row) — the golden rule. The SOLAR panel now shows the flow diagram (the panel is shared across tiers; only the home card is tier-gated).

- [ ] **Config-page assignment (user/lead step at verify time, not app code):** in the device web config, assign the six `*_today` utility-meter helpers from the spec's real-sensor table — **including REPLACING the pvToday/loadToday lifetime-counter assignments** with `…_pv_energy_today` / `…_load_energy_today` — and the four `sensor.solar_array_a`–`d` slots. Confirm the `↓ · ↑` grid/battery lines and the array line appear at panel scale and survive export/import. Helper values are partial until their first midnight rollover — verify magnitudes the day after assignment.

- [ ] **Docs:** none needed beyond this plan and the existing spec. Do not add README changes.

---

## Done criteria

- Gate green: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- 17 new tests (13 SolarModel derivation + 1 AdaptiveGeometry pin + 3 config); the two old `solarFlow` tests removed; all `solarCard`/`AdaptiveGeometry`/`DashConfig` tests still green.
- Show 8 + M9: home diamond with flowing dots (screencapped), SOLAR panel large diagram + Today/daily lines (screencapped), crash-clean.
- Show 5: pill unchanged (eyeball), SOLAR panel shows the diagram.
- No reference to `SolarFlow`/`solarFlow`/`SolarNode` remains.
