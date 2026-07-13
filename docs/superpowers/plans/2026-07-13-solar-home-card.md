# Solar Home Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A home-view solar/battery pill card (PV output, battery SOC gauge with charging shimmer, home load + grid import/export) below the EV cards in the top-right column.

**Architecture:** Same triad as the EV cards — optional config entities on the existing `SolarConfig`, a pure-JVM card computer in `SolarModel.kt`, a private composable in `HomeView.kt` fed from `DashboardShell`. The EV gauge bar is extracted into a shared `GaugeBar` composable so both cards use one implementation.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose, kotlinx.serialization, JUnit4 (plain JVM), vanilla JS config page.

**Spec:** docs/superpowers/specs/2026-07-13-solar-home-card-design.md

## Global Constraints

- Kotlin 2.1.0; compileSdk 34 NEVER bump; NO new dependencies; media3 exactly 1.4.1; NanoHTTPD 2.3.1.
- Tests are plain-JVM JUnit4 only — no Robolectric, no android.* imports in tests.
- Build gate (repo root): `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` must exit 0.
- Every commit message ends with the trailer line: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Old saved configs (no battSoc/battPower keys) must load unchanged (defaults).
- Battery sign convention: **negative battery power = charging** (evcc). Charging deadband: watts < −50.
- EV card rendering must be byte-identical after the GaugeBar extraction (same sizes, colors, shimmer timing, tick math).

---

### Task 1: Config fields + card model + tests

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt` (SolarConfig ~line 23; clamped() solar copy ~line 207)
- Modify: `app/src/main/java/com/rar/echodash/ui/model/SolarModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/SolarModelTest.kt`
- Test: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`

**Interfaces:**
- Produces: `SolarConfig.battSoc: String?`, `SolarConfig.battPower: String?`; `data class SolarCard(pvText: String?, socPct: Int?, battCharging: Boolean, statsLine: String?)`; `fun solarCard(cfg: SolarConfig, entities: Map<String, EntityState>): SolarCard?` — Task 2 consumes all of these.

- [ ] **Step 1: Write the failing tests**

Append to `SolarModelTest.kt` (inside the existing class; it already has `st(id, state, unit)` and imports; add `import org.junit.Assert.assertFalse`, `import org.junit.Assert.assertTrue`, `import org.junit.Assert.assertNotNull`):

```kotlin
    @Test
    fun solarCardFullData() {
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            battSoc = "sensor.soc", battPower = "sensor.batt")
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "3200", "W"),
            "sensor.load" to st("sensor.load", "1400", "W"),
            "sensor.grid" to st("sensor.grid", "-1800", "W"),
            "sensor.soc" to st("sensor.soc", "78.4", "%"),
            "sensor.batt" to st("sensor.batt", "-200", "W"),
        )
        val card = solarCard(cfg, entities)!!
        assertEquals("3.2 kW", card.pvText)
        assertEquals(78, card.socPct)
        assertTrue(card.battCharging)
        assertEquals("Home 1.4 kW · Export 1.8 kW", card.statsLine)
    }

    @Test
    fun solarCardGridImportLabel() {
        val cfg = SolarConfig(load = "sensor.load", grid = "sensor.grid")
        val entities = mapOf(
            "sensor.load" to st("sensor.load", "900", "W"),
            "sensor.grid" to st("sensor.grid", "450", "W"),
        )
        assertEquals("Home 900 W · Import 450 W", solarCard(cfg, entities)!!.statsLine)
    }

    @Test
    fun battChargingSignAndDeadband() {
        val cfg = SolarConfig(battSoc = "sensor.soc", battPower = "sensor.batt")
        fun cardWith(state: String, unit: String) = solarCard(cfg, mapOf(
            "sensor.soc" to st("sensor.soc", "50", "%"),
            "sensor.batt" to st("sensor.batt", state, unit),
        ))!!
        assertTrue(cardWith("-200", "W").battCharging)
        assertFalse(cardWith("-20", "W").battCharging)   // inside deadband
        assertFalse(cardWith("500", "W").battCharging)   // discharging
        assertTrue(cardWith("-0.2", "kW").battCharging)  // kW unit-aware
    }

    @Test
    fun solarCardMissingPiecesDegrade() {
        // No pv sensor: card still produced, header has no output text.
        val noPv = solarCard(SolarConfig(load = "sensor.load"),
            mapOf("sensor.load" to st("sensor.load", "800", "W")))!!
        assertNull(noPv.pvText)
        assertEquals("Home 800 W", noPv.statsLine)
        // Non-numeric SOC: no gauge, card still produced.
        val badSoc = solarCard(SolarConfig(battSoc = "sensor.soc"),
            mapOf("sensor.soc" to st("sensor.soc", "unknown", "%")))
        assertNotNull(badSoc)
        assertNull(badSoc!!.socPct)
        assertNull(badSoc.statsLine)
        // SOC clamped to 0..100.
        val over = solarCard(SolarConfig(battSoc = "sensor.soc"),
            mapOf("sensor.soc" to st("sensor.soc", "104", "%")))!!
        assertEquals(100, over.socPct)
    }

    @Test
    fun solarCardNullWhenNothingResolves() {
        assertNull(solarCard(SolarConfig(), emptyMap()))
        // Configured but entity absent from the map:
        assertNull(solarCard(SolarConfig(pv = "sensor.pv"), emptyMap()))
        // battPower alone does not make a card:
        assertNull(solarCard(SolarConfig(battPower = "sensor.batt"),
            mapOf("sensor.batt" to st("sensor.batt", "-500", "W"))))
    }
```

In `DashConfigTest.kt`, find the existing solar-related clamp/referenced tests and extend them the same way the EV fields were covered:
- In the test covering solar trimming in `clamped()` (or add `solarBattFieldsClampedAndTrimmed` following the file's existing style): a config with `battSoc = " sensor.soc "` and `battPower = ""` clamps to `battSoc = "sensor.soc"`, `battPower = null`.
- In the test covering `referencedEntityIds()`: a config with `solar = SolarConfig(battSoc = "sensor.soc", battPower = "sensor.batt")` must include both ids in the result.

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.SolarModelTest' --tests 'com.rar.echodash.config.DashConfigTest'`
Expected: compilation FAILS (no `battSoc` parameter, no `solarCard` function).

- [ ] **Step 3: Implement config fields**

In `DashConfig.kt`, `SolarConfig` becomes:

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

In `clamped()`, the `solar = entities.solar.copy(...)` block gains two lines after `loadToday`:

```kotlin
                    battSoc = entities.solar.battSoc?.trim()?.ifBlank { null },
                    battPower = entities.solar.battPower?.trim()?.ifBlank { null },
```

- [ ] **Step 4: Implement the card computer**

In `SolarModel.kt`, add `import kotlin.math.roundToInt` and append after `solarFlow` (before `formatWatts`):

```kotlin
/** Home solar/battery pill card. Null when no solar entities resolve (card hidden). */
data class SolarCard(
    val pvText: String?,       // formatted PV output for the header; null = no pv sensor
    val socPct: Int?,          // battery SOC 0-100; null = no gauge
    val battCharging: Boolean, // battery power below -CHARGE_DEADBAND_W (evcc: negative = charging)
    val statsLine: String?,    // "Home 1.4 kW · Export 1.8 kW"; null when empty
)

private const val CHARGE_DEADBAND_W = 50.0

fun solarCard(cfg: SolarConfig, entities: Map<String, EntityState>): SolarCard? {
    fun get(id: String?): EntityState? = id?.let { entities[it] }
    val pv = get(cfg.pv)
    val load = get(cfg.load)
    val grid = get(cfg.grid)
    val soc = get(cfg.battSoc)
    if (pv == null && load == null && grid == null && soc == null) return null

    val gridValue = grid?.state?.toDoubleOrNull()
    val statsLine = buildList {
        load?.let { add("Home ${formatWatts(it)}") }
        if (grid != null && gridValue != null) {
            add((if (gridValue >= 0) "Import " else "Export ") + formatWatts(grid))
        }
    }.joinToString(" · ").takeIf { it.isNotEmpty() }

    val battWatts = get(cfg.battPower)?.let { powerWatts(it) }
    return SolarCard(
        pvText = pv?.let { formatWatts(it) },
        socPct = soc?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100),
        battCharging = battWatts != null && battWatts < -CHARGE_DEADBAND_W,
        statsLine = statsLine,
    )
}

/** Raw signed watts from a power sensor, respecting a kW unit; null if non-numeric. */
private fun powerWatts(state: EntityState): Double? {
    val v = state.state.toDoubleOrNull() ?: return null
    val unit = state.attr("unit_of_measurement") ?: "W"
    return if (unit.equals("kW", ignoreCase = true)) v * 1000 else v
}
```

- [ ] **Step 5: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: exit 0, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/config/DashConfig.kt \
        app/src/main/java/com/rar/echodash/ui/model/SolarModel.kt \
        app/src/test/java/com/rar/echodash/ui/model/SolarModelTest.kt \
        app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "Add solar home card model + battery config fields

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

### Task 2: Card view, shell wiring, config page

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt` (signature ~line 137, EV column ~line 271, EvCardView ~line 315)
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` (HOME block ~line 112)
- Modify: `app/src/main/assets/config/app.js` (solarSlots ~line 355)

**Interfaces:**
- Consumes (from Task 1): `SolarCard(pvText, socPct, battCharging, statsLine)`, `solarCard(cfg, entities)`.
- Produces: `HomeView(..., solar: SolarCard? = null)`; private `GaugeBar(fillPct: Int, shimmer: Boolean, tickPct: Int? = null)`; private `SolarCardView(card: SolarCard)`.

No new unit tests (Compose-side; covered by the build gate). Steps:

- [ ] **Step 1: Extract GaugeBar from EvCardView**

In `HomeView.kt`, the entire `if (card.socPct != null) { Box(...track...) }` block inside `EvCardView` (the 216dp track Box with fill, shimmer, and limit tick — currently ~lines 339–397) moves verbatim into a new private composable placed directly below `EvCardView`, with `card.socPct` → `fillPct`, `card.charging` → `shimmer`, `card.limitPct` → `tickPct`, and the `label` strings renamed `"evShimmer"`/`"evShimmerX"` → `"gaugeShimmer"`/`"gaugeShimmerX"`:

```kotlin
/** 216dp gauge track: green fill, optional charging shimmer, optional limit tick. */
@Composable
private fun GaugeBar(fillPct: Int, shimmer: Boolean, tickPct: Int? = null) {
    Box(
        Modifier
            .fillMaxWidth()
            .size(width = 216.dp, height = 8.dp)
            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fillPct / 100f)
                .fillMaxHeight()
                // clip() is required: background() draws a rounded shape but does
                // not clip children, so the shimmer band would bleed past the fill.
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF7BC67E)),
        ) {
            // Only run the infinite animation while actually charging.
            if (shimmer) {
                val transition = rememberInfiniteTransition(label = "gaugeShimmer")
                val fraction by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "gaugeShimmerX",
                )
                Box(
                    Modifier
                        // Sweep a 24dp band left-to-right across the full 216dp track
                        // width; the fill's clip keeps it inside the filled region.
                        .offset(x = (fraction * (216 + 24)).dp - 24.dp)
                        .width(24.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        }
        if (tickPct != null) {
            Box(
                Modifier
                    // 2dp tick at the vehicle's charge limit; -1dp centers it on the fraction.
                    .offset(x = (216 * tickPct / 100 - 1).dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(1.dp)),
            )
        }
    }
}
```

In `EvCardView`, the moved block is replaced by:

```kotlin
        if (card.socPct != null) {
            GaugeBar(card.socPct, card.charging, card.limitPct)
        }
```

- [ ] **Step 2: Add SolarCardView and wire it into the column**

Below `GaugeBar`, add (imports needed: `androidx.compose.material.icons.outlined.SolarPower`, `com.rar.echodash.ui.model.SolarCard` — follow the file's existing import style):

```kotlin
/** Home solar pill: sun icon + PV output, battery gauge, home/grid line. */
@Composable
private fun SolarCardView(card: SolarCard) {
    Column(
        Modifier
            .width(248.dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Outlined.SolarPower, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(18.dp),
            )
            Text(
                if (card.pvText != null) "Solar ${card.pvText}" else "Solar",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (card.socPct != null) {
                Text("${card.socPct}%", color = Color.White, fontSize = 14.sp)
            }
        }
        if (card.socPct != null) {
            GaugeBar(card.socPct, card.battCharging)
        }
        if (card.statsLine != null) {
            Text(
                card.statsLine, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

`HomeView`'s parameter list gains `solar: SolarCard? = null` after `evs: List<EvCard> = emptyList()`. The top-right column (~line 271) becomes:

```kotlin
            AnimatedVisibility(
                visible = evs.isNotEmpty() || solar != null,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    evs.forEach { EvCardView(it) }
                    if (solar != null) SolarCardView(solar)
                }
            }
```

- [ ] **Step 3: Wire DashboardShell**

In `DashboardShell.kt` HOME block (right after the `evs` remember at ~line 112), add — plus `import com.rar.echodash.ui.model.solarCard` next to the existing `solarFlow` import:

```kotlin
                    val solar = remember(entities, config.entities.solar) {
                        solarCard(config.entities.solar, entities)
                    }
```

and pass `solar = solar,` to the `HomeView(...)` call after `evs = evs,`.

- [ ] **Step 4: Config page pickers**

In `app.js`, extend `solarSlots`:

```js
  const solarSlots = [["pv", "PV power"], ["load", "Home load"], ["grid", "Grid power"],
    ["pvToday", "PV today"], ["loadToday", "Load today"],
    ["battSoc", "Battery %"], ["battPower", "Battery power"]];
```

After the `solarSlots.forEach(...)` loop, add:

```js
  host.appendChild(el("div", "muted",
    "Battery % and battery power add a solar card to the home screen (gauge shimmers while the battery charges). " +
    "Battery power: negative = charging (evcc convention). Grid power: positive = importing."));
```

- [ ] **Step 5: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/HomeView.kt \
        app/src/main/java/com/rar/echodash/ui/DashboardShell.kt \
        app/src/main/assets/config/app.js
git commit -m "Render solar home card below EV cards; config pickers

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```
