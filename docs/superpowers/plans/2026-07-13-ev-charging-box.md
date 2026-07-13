# EV Charging Box Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Top-right home-view card(s) showing SOC/power/ETA while an EV charges, fed by EVCC's HA entities, configured on the web config page.

**Architecture:** Solar-triad pattern: EvConfig slots in Entities → pure-JVM evCards() computer in ui/model/EvModel.kt → cards rendered in HomeView's non-takeover branch. Hidden during takeover; explicitly NOT a night-mode override.

**Tech Stack:** Kotlin 2.1.0, Compose, kotlinx-serialization, plain-JVM JUnit4, vanilla JS config page.

## Global Constraints

- Kotlin 2.1.0; compileSdk 34 NEVER bump; media3 exactly 1.4.1; NanoHTTPD 2.3.1; NO new dependencies.
- Tests are plain-JVM JUnit4 only; no Robolectric; no android.* imports in tests.
- Build gate from repo root: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` must exit 0.
- Every commit message ends with the trailer line: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Night-mode override expression in App.kt, NightModeController, KioskController: DO NOT TOUCH.

---

## Task 1 — EvConfig in DashConfig + clamped/referencedEntityIds + config tests

**Files**
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
- Modify: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`

**Interfaces**
- Produces: `data class EvConfig(name: String = "", charging: String? = null, soc: String? = null, power: String? = null, eta: String? = null)` with `fun ids(): List<String>`.
- `Entities` gains `val evs: List<EvConfig> = emptyList()`.
- `DashConfig.clamped()` cleans each slot (trim name; each id `?.trim()?.ifBlank { null }`), drops slots whose name is blank AND all four ids are null, then caps at `take(2)`.
- `DashConfig.referencedEntityIds()` includes every EV slot's ids.

### Steps

- [ ] **Write the two failing config tests.** Append these two `@Test` methods inside `class DashConfigTest` in `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`, immediately before the final closing brace (after the `nightSurvivesClampedAndDefaultsOnOldConfig` test):

```kotlin
    @Test
    fun evSlotsClampedTrimmedAndCapped() {
        val cfg = DashConfig(
            entities = Entities(
                evs = listOf(
                    EvConfig(name = "  Ioniq  ", charging = "  binary_sensor.charging  ",
                        soc = "sensor.soc", power = "  ", eta = null),
                    EvConfig(name = "", charging = "switch.c2", soc = "", power = null, eta = "  "),
                    EvConfig(name = "Kona", charging = "sensor.c3"),                 // 3rd valid -> capped out
                    EvConfig(name = "   ", charging = "  ", soc = " ", power = null, eta = ""), // all blank -> dropped
                ),
            ),
        ).clamped()
        assertEquals(2, cfg.entities.evs.size)
        assertEquals(EvConfig("Ioniq", "binary_sensor.charging", "sensor.soc", null, null), cfg.entities.evs[0])
        assertEquals(EvConfig("", "switch.c2", null, null, null), cfg.entities.evs[1])
    }

    @Test
    fun referencedEntityIdsIncludeEvEntities() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                evs = listOf(
                    EvConfig(name = "Ioniq", charging = "binary_sensor.charging",
                        soc = "sensor.soc", power = "sensor.power", eta = "sensor.eta"),
                    EvConfig(charging = "switch.c2"),
                ),
            ),
        )
        assertEquals(
            listOf("sensor.t", "binary_sensor.charging", "sensor.soc", "sensor.power", "sensor.eta", "switch.c2"),
            cfg.referencedEntityIds(),
        )
    }
```

- [ ] **Run them, expect a COMPILE failure** (`EvConfig` / `Entities.evs` do not exist yet):
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"
```

- [ ] **Add the `EvConfig` data class.** In `DashConfig.kt`, insert directly after the `SolarConfig` class (after its closing brace on line 31, before `@Serializable data class LightGroup`):

```kotlin
@Serializable
data class EvConfig(
    val name: String = "",
    val charging: String? = null, // entity whose truthy state shows the card
    val soc: String? = null,      // battery % sensor
    val power: String? = null,    // charge power sensor (W or kW, unit-aware)
    val eta: String? = null,      // time-to-finish sensor (minutes, H:MM:SS, or timestamp)
) {
    fun ids(): List<String> = listOfNotNull(charging, soc, power, eta)
}
```

- [ ] **Add `evs` to `Entities`.** Change the `Entities` data class — add the field after `doorbells`:

```kotlin
@Serializable
data class Entities(
    val tempSensor: String? = null,
    val weather: String? = null,
    val aqiSensor: String? = null,
    val climate: List<String> = emptyList(),
    val solar: SolarConfig = SolarConfig(),
    val lightGroups: List<LightGroup> = emptyList(),
    val cameras: List<CameraConfig> = emptyList(),
    val doorbells: List<DoorbellConfig> = emptyList(),
    val evs: List<EvConfig> = emptyList(),
)
```

- [ ] **Reference the EV ids in `referencedEntityIds()`.** In the `buildList { ... }` body, add one line after the `entities.doorbells.forEach { ... }` line and before `media.companionEntity?.let { add(it) }`:

```kotlin
        entities.doorbells.forEach { d -> d.trigger?.let { add(it) } }
        entities.evs.forEach { addAll(it.ids()) }
        media.companionEntity?.let { add(it) }
```

- [ ] **Clean EV slots in `clamped()`.** In `clamped()`, add the `cleanedEvs` computation right after the `cleanedDoorbells` block (after its `.filter { it.trigger != null && it.camera in cameraNames }` line, before `return copy(`):

```kotlin
        val cleanedEvs = entities.evs
            .map { ev ->
                ev.copy(
                    name = ev.name.trim(),
                    charging = ev.charging?.trim()?.ifBlank { null },
                    soc = ev.soc?.trim()?.ifBlank { null },
                    power = ev.power?.trim()?.ifBlank { null },
                    eta = ev.eta?.trim()?.ifBlank { null },
                )
            }
            .filter { it.name.isNotBlank() || it.ids().isNotEmpty() }
            .take(2)
```

  Then add `evs = cleanedEvs,` inside the `entities = entities.copy( ... )` block, immediately after the `doorbells = cleanedDoorbells,` line:

```kotlin
                cameras = cleanedCameras,
                doorbells = cleanedDoorbells,
                evs = cleanedEvs,
            ),
```

- [ ] **Run the two tests again, expect PASS:**
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"
```

- [ ] **Run the full gate, expect exit 0:**
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Commit:**
```
git add app/src/main/java/com/rar/echodash/config/DashConfig.kt app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "$(cat <<'EOF'
Add EvConfig slots to DashConfig (clamp + referencedEntityIds)

Two fixed EV charging slots: per-slot trim, blank-id-to-null, drop
all-blank slots, cap at two. EV entity ids join the EntityHub watched
set so EVCC sensors get subscribed.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 2 — ui/model/EvModel.kt state computer + EvModelTest

**Files**
- Create: `app/src/main/java/com/rar/echodash/ui/model/EvModel.kt`
- Create: `app/src/test/java/com/rar/echodash/ui/model/EvModelTest.kt`

**Interfaces**
- Produces: `data class EvCard(name: String, socPct: Int?, statusLine: String?)`.
- Produces: `fun evCards(cfgs: List<EvConfig>, entities: Map<String, EntityState>, nowMs: Long): List<EvCard>`.
- Consumes: `EvConfig` (Task 1), `EntityState` (`state: String`, `attr(key): String?`).
- Pure JVM only: imports `java.time.Instant`, `java.time.OffsetDateTime`, `java.util.Locale`, `kotlin.math.abs`, `kotlin.math.roundToInt`. No Compose/Android.

### Steps

- [ ] **Write the failing test file.** Create `app/src/test/java/com/rar/echodash/ui/model/EvModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.config.EvConfig
import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, unit: String? = null): EntityState {
        val a = if (unit != null) attrs("""{"unit_of_measurement":"$unit"}""") else attrs("{}")
        return EntityState(id, state, a, 0L)
    }

    @Test
    fun noCardWhenChargingEntityMissingOrFalsy() {
        // charging not configured
        assertEquals(emptyList<EvCard>(),
            evCards(listOf(EvConfig(name = "A", soc = "sensor.soc")),
                mapOf("sensor.soc" to st("sensor.soc", "50")), 0L))
        // charging off
        assertEquals(emptyList<EvCard>(),
            evCards(listOf(EvConfig(charging = "binary_sensor.c")),
                mapOf("binary_sensor.c" to st("binary_sensor.c", "off")), 0L))
        // charging unavailable
        assertEquals(emptyList<EvCard>(),
            evCards(listOf(EvConfig(charging = "binary_sensor.c")),
                mapOf("binary_sensor.c" to st("binary_sensor.c", "unavailable")), 0L))
        // charging entity missing from the map
        assertEquals(emptyList<EvCard>(),
            evCards(listOf(EvConfig(charging = "binary_sensor.c")), emptyMap(), 0L))
    }

    @Test
    fun truthyVariantsProduceCard() {
        listOf("on", "true", "Charging").forEach { s ->
            val cards = evCards(listOf(EvConfig(name = "Car", charging = "binary_sensor.c")),
                mapOf("binary_sensor.c" to st("binary_sensor.c", s)), 0L)
            assertEquals(1, cards.size)
            assertEquals("Car", cards[0].name)
        }
    }

    @Test
    fun socClampedAndRounded() {
        fun soc(v: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", soc = "sensor.soc")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.soc" to st("sensor.soc", v, "%")),
            0L,
        ).single().socPct
        assertEquals(64, soc("63.6"))
        assertEquals(100, soc("104"))
        assertNull(soc("n/a"))
    }

    @Test
    fun powerUnitAwareFormatting() {
        fun status(state: String, unit: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.p" to st("sensor.p", state, unit)),
            0L,
        ).single().statusLine
        assertEquals("7.2 kW", status("7240", "W"))
        assertEquals("7.2 kW", status("7.24", "kW"))
        assertEquals("11 kW", status("11000", "W"))
    }

    @Test
    fun etaMinutesNumber() {
        fun status(v: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", v)),
            0L,
        ).single().statusLine
        assertEquals("1h05 left", status("65"))
        assertEquals("45m left", status("45"))
    }

    @Test
    fun etaDurationString() {
        val status = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", "1:05:00")),
            0L,
        ).single().statusLine
        assertEquals("1h05 left", status)
    }

    @Test
    fun etaTimestamp() {
        val now = 1_700_000_000_000L
        val future = java.time.Instant.ofEpochMilli(now + 65 * 60_000L).toString()
        val past = java.time.Instant.ofEpochMilli(now - 5 * 60_000L).toString()
        fun status(v: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", v)),
            now,
        ).single().statusLine
        assertEquals("1h05 left", status(future))
        assertNull(status(past)) // finishing/past -> eta omitted; card still shown with null statusLine
    }

    @Test
    fun statusLineJoinsAndOmits() {
        val base = mapOf("binary_sensor.c" to st("binary_sensor.c", "on"))
        val both = evCards(listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p", eta = "sensor.e")),
            base + mapOf("sensor.p" to st("sensor.p", "7240", "W"), "sensor.e" to st("sensor.e", "65")), 0L).single()
        assertEquals("7.2 kW · 1h05 left", both.statusLine)

        val p = evCards(listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p")),
            base + mapOf("sensor.p" to st("sensor.p", "7240", "W")), 0L).single()
        assertEquals("7.2 kW", p.statusLine)

        val e = evCards(listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.e")),
            base + mapOf("sensor.e" to st("sensor.e", "65")), 0L).single()
        assertEquals("1h05 left", e.statusLine)

        val none = evCards(listOf(EvConfig(name = "X", charging = "binary_sensor.c")), base, 0L).single()
        assertNull(none.statusLine)
        assertEquals("X", none.name)
    }

    @Test
    fun blankNameFallsBackToEV() {
        val card = evCards(listOf(EvConfig(name = "  ", charging = "binary_sensor.c")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on")), 0L).single()
        assertEquals("EV", card.name)
    }

    @Test
    fun twoChargingKeepConfigOrderAndSkipIdle() {
        val oneOn = mapOf(
            "binary_sensor.a" to st("binary_sensor.a", "off"),
            "binary_sensor.b" to st("binary_sensor.b", "on"),
        )
        val one = evCards(listOf(
            EvConfig(name = "A", charging = "binary_sensor.a"),
            EvConfig(name = "B", charging = "binary_sensor.b"),
        ), oneOn, 0L)
        assertEquals(listOf("B"), one.map { it.name })

        val bothOn = mapOf(
            "binary_sensor.a" to st("binary_sensor.a", "on"),
            "binary_sensor.b" to st("binary_sensor.b", "charging"),
        )
        val two = evCards(listOf(
            EvConfig(name = "A", charging = "binary_sensor.a"),
            EvConfig(name = "B", charging = "binary_sensor.b"),
        ), bothOn, 0L)
        assertEquals(listOf("A", "B"), two.map { it.name })
    }
}
```

- [ ] **Run it, expect a COMPILE failure** (`evCards` / `EvCard` do not exist yet):
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.EvModelTest"
```

- [ ] **Create `app/src/main/java/com/rar/echodash/ui/model/EvModel.kt`** with the complete implementation:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.config.EvConfig
import com.rar.echodash.ha.EntityState
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One charging EV's card. Fields are pre-formatted display strings; null = omit that line. */
data class EvCard(
    val name: String,        // config name, or "EV" when blank
    val socPct: Int?,        // 0..100 for the gauge + "%" text; null hides the gauge row
    val statusLine: String?, // "7.2 kW · 1h05 left" / "7.2 kW" / "1h05 left"; null hides the row
)

/** States (lowercased, trimmed) that mean "charging" across binary_sensor and EVCC/string sensors. */
private val TRUTHY = setOf("on", "true", "charging")

/**
 * Build a card per config slot that is currently charging. The `charging` entity is the trigger:
 * no charging entity, missing entity, or non-truthy state -> no card. Order follows config order;
 * idle slots are skipped.
 */
fun evCards(cfgs: List<EvConfig>, entities: Map<String, EntityState>, nowMs: Long): List<EvCard> =
    cfgs.mapNotNull { cfg ->
        val chargingId = cfg.charging ?: return@mapNotNull null
        val charging = entities[chargingId] ?: return@mapNotNull null
        if (charging.state.trim().lowercase(Locale.US) !in TRUTHY) return@mapNotNull null

        val socPct = cfg.soc?.let { entities[it] }?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)
        val power = cfg.power?.let { entities[it] }?.let { formatPower(it) }
        val eta = cfg.eta?.let { entities[it] }?.let { formatEta(it, nowMs) }
        val statusLine = listOfNotNull(power, eta?.let { "$it left" }).joinToString(" · ").ifBlank { null }

        EvCard(
            name = cfg.name.trim().ifBlank { "EV" },
            socPct = socPct,
            statusLine = statusLine,
        )
    }

/** Charge power as kW: unit-aware ("kW" as-is, else W/1000). One decimal below 10 kW, integer at/above. */
private fun formatPower(state: EntityState): String? {
    val unit = state.attr("unit_of_measurement") ?: "W"
    val v = state.state.toDoubleOrNull() ?: return null
    val kw = if (unit.equals("kW", ignoreCase = true)) abs(v) else abs(v) / 1000.0
    return if (kw < 10.0) String.format(Locale.US, "%.1f kW", kw) else "${kw.roundToInt()} kW"
}

/** Remaining time as "1h05" / "45m"; null when unparseable or zero/negative (EVCC reports 0 near end). */
private fun formatEta(state: EntityState, nowMs: Long): String? {
    val minutes = etaMinutes(state.state.trim(), nowMs) ?: return null
    if (minutes <= 0L) return null
    return formatMinutes(minutes)
}

/** Parse an ETA sensor to whole minutes remaining, trying (in order) number, H:MM:SS, ISO timestamp. */
private fun etaMinutes(raw: String, nowMs: Long): Long? {
    // 1. plain number of minutes
    raw.toDoubleOrNull()?.let { return it.roundToInt().toLong() }
    // 2. H:MM:SS / HH:MM:SS duration
    if (raw.contains(':') && !raw.contains('T')) {
        val parts = raw.split(':')
        if (parts.size == 3) {
            val h = parts[0].toLongOrNull()
            val m = parts[1].toLongOrNull()
            val s = parts[2].toLongOrNull()
            if (h != null && m != null && s != null) return h * 60 + m + if (s >= 30) 1 else 0
        }
        return null
    }
    // 3. ISO-8601 timestamp
    if (raw.contains('T')) {
        val tsMs = parseTimestampMs(raw) ?: return null
        return (tsMs - nowMs) / 60_000L
    }
    return null
}

/** Whole minutes -> "1h05" (with hours) or "45m" (under an hour). */
private fun formatMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) String.format(Locale.US, "%dh%02d", h, m) else "${m}m"
}

/** Parse an ISO-8601 instant/offset timestamp to epoch millis; null if neither form parses. */
private fun parseTimestampMs(raw: String): Long? =
    try {
        OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            Instant.parse(raw).toEpochMilli()
        } catch (e2: Exception) {
            null
        }
    }
```

- [ ] **Run the test, expect PASS:**
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.EvModelTest"
```

- [ ] **Run the full gate, expect exit 0:**
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Commit:**
```
git add app/src/main/java/com/rar/echodash/ui/model/EvModel.kt app/src/test/java/com/rar/echodash/ui/model/EvModelTest.kt
git commit -m "$(cat <<'EOF'
Add EvModel: pure-JVM evCards() state computer

Truthy charging trigger, clamped/rounded SOC, unit-aware kW power, and a
three-shape ETA parser (minutes, H:MM:SS, ISO timestamp). Mirrors the
SolarModel pattern; ten unit tests cover the spec cases.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 3 — HomeView EV cards column + DashboardShell wiring

**Files**
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`

**Interfaces**
- `HomeView` gains param `evs: List<EvCard> = emptyList()`.
- Consumes `EvCard` (Task 2) fields `name`, `socPct`, `statusLine`.
- `DashboardShell` computes `evCards(config.entities.evs, entities, System.currentTimeMillis())` and passes it as `evs`.

**No new JVM tests** — this is Compose UI. The `assembleDebug` half of the gate is the check (it must compile). Visual correctness is confirmed by the final reviewer reading the diff against the spec's styling.

### Steps

- [ ] **Add imports to `HomeView.kt`.** After the existing `import androidx.compose.animation.core.tween` line (line 8), add:

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
```

  After `import androidx.compose.foundation.layout.fillMaxSize` (line 18), add:

```kotlin
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
```

  After `import com.rar.echodash.ui.model.AqiPill` (line 53), add:

```kotlin
import com.rar.echodash.ui.model.EvCard
```

- [ ] **Add the `evs` parameter to `HomeView`.** In the signature, the existing lines read:

```kotlin
    pill: WeatherPill?,
    aqi: AqiPill?,
    clockFormat: ClockFormat,
```

  Change to:

```kotlin
    pill: WeatherPill?,
    aqi: AqiPill?,
    evs: List<EvCard> = emptyList(),
    clockFormat: ClockFormat,
```

- [ ] **Render the EV column in the non-takeover branch.** The `else { ... }` branch currently ends with the pills block, followed by its closing brace. The existing tail is:

```kotlin
                    if (aqi != null) {
                        Row(
                            Modifier
                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val dim = if (aqi.stale) 0.4f else 1f
                            Text("AQI", color = Color.White.copy(alpha = 0.7f * dim), fontSize = 18.sp)
                            Text(
                                aqi.value.toString(),
                                color = Color(aqi.band.colorArgb).copy(alpha = dim),
                                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
```

  Insert the EV column between the pills `if (pill != null || aqi != null) { ... }` closing brace and the `}` that closes the `else` branch. Concretely, after the `}` that closes the pills `if`-block and before the `}` closing `else`:

```kotlin
                }
            }

            AnimatedVisibility(
                visible = evs.isNotEmpty(),
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    evs.forEach { EvCardView(it) }
                }
            }
        }
```

  (The last `}` shown is the existing one that closes the `else` branch — do not duplicate it. The `AnimatedVisibility` is a direct child of the outer `Box`, so `Modifier.align` resolves in `BoxScope`.)

- [ ] **Add the `EvCardView` composable.** Append at the very end of `HomeView.kt`, after the closing brace of `fun HomeView(...)`:

```kotlin
/** One EV charging pill: bolt+name, a battery gauge, and a status line — pill visual language. */
@Composable
private fun EvCardView(card: EvCard) {
    Column(
        Modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "⚡ " + card.name,
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        )
        val soc = card.socPct
        if (soc != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 96.dp, height = 8.dp)
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(soc / 100f)
                            .fillMaxHeight()
                            .background(Color(0xFF7BC67E), RoundedCornerShape(4.dp)),
                    )
                }
                Text("$soc%", color = Color.White, fontSize = 14.sp)
            }
        }
        val status = card.statusLine
        if (status != null) {
            Text(status, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
        }
    }
}
```

  (`Column`, `Row`, `Box`, `Text`, `background`, `padding`, `size`, `RoundedCornerShape`, `Alignment`, `Arrangement`, `Color`, `FontWeight`, `dp`, `sp` are all already imported in this file.)

- [ ] **Wire it in `DashboardShell.kt`.** After the existing `import com.rar.echodash.ui.model.aqiPill` line (line 30), add:

```kotlin
import com.rar.echodash.ui.model.evCards
```

  In the `DashView.HOME ->` block, the existing `aqi` computation reads:

```kotlin
                    val aqi = remember(entities, config.entities) {
                        aqiPill(config.entities.aqiSensor, entities, System.currentTimeMillis())
                    }
```

  Add the EV computation immediately after it:

```kotlin
                    val evs = remember(entities, config.entities.evs) {
                        evCards(config.entities.evs, entities, System.currentTimeMillis())
                    }
```

  Then pass it to `HomeView`. The existing call has:

```kotlin
                        pill = pill,
                        aqi = aqi,
                        clockFormat = config.home.clockFormat,
```

  Change to:

```kotlin
                        pill = pill,
                        aqi = aqi,
                        evs = evs,
                        clockFormat = config.home.clockFormat,
```

- [ ] **Run the full gate, expect exit 0** (compiles the new composable + wiring; existing tests still pass):
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Commit:**
```
git add app/src/main/java/com/rar/echodash/ui/HomeView.kt app/src/main/java/com/rar/echodash/ui/DashboardShell.kt
git commit -m "$(cat <<'EOF'
Render EV charging cards top-right on the home view

AnimatedVisibility column of pill-style cards (bolt+name, battery gauge,
status line) in the non-takeover branch only; crossfades in/out as cards
appear. DashboardShell computes evCards on each entity update. Night mode
untouched.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 4 — Web config page: ev-section + renderEv()

**Files**
- Modify: `app/src/main/assets/config/index.html`
- Modify: `app/src/main/assets/config/app.js`

**Interfaces**
- New `<section id="ev-section">` with host `<div id="ev"></div>`, after the night section.
- New `ICONS.ev` glyph.
- New `renderEv()` reading/writing `config.entities.evs` (two fixed slots, defensively padded to 2), wired into `render()`.

**No JVM tests** — vanilla JS/HTML. The gate run confirms nothing else broke; page correctness is verified by the final reviewer reading the diff. Server-side `clamped()` (Task 1) remains authoritative on Save.

### Steps

- [ ] **Add the `ev-section` to `index.html`.** After the night section's closing `</section>` (the block ending with `<div id="night"></div>` then `</section>` on line 183) and before the `</div>` that closes `.content` (line 184), insert:

```html
      <section id="ev-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 17v-4l2-4.5A2 2 0 0 1 7.8 7.3h6.4A2 2 0 0 1 16 8.5L18 13v4"/>
              <path d="M4 17h2M18 17h2"/><circle cx="7.5" cy="17" r="1.5"/><circle cx="16.5" cy="17" r="1.5"/>
              <path d="M12 8.5 11 11.5h2L11.8 14.5"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>EV charging</h2>
            <p>Home-screen card while a car charges — assign EVCC entities.</p>
          </div>
        </div>
        <div id="ev"></div>
      </section>
```

- [ ] **Add the `ev` glyph to the `ICONS` map in `app.js`.** The map currently ends with the `home:` entry (line 37). After it (before the closing `};` on line 38), add:

```javascript
  ev: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 17v-4l2-4.5A2 2 0 0 1 7.8 7.3h6.4A2 2 0 0 1 16 8.5L18 13v4"/><path d="M4 17h2M18 17h2"/><circle cx="7.5" cy="17" r="1.5"/><circle cx="16.5" cy="17" r="1.5"/><path d="M12 8.5 11 11.5h2L11.8 14.5"/></svg>',
```

  (Ensure the previous `home:` line still ends with its trailing comma so the object literal stays valid.)

- [ ] **Wire `renderEv()` into `render()`.** The `render()` function currently reads:

```javascript
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
}
```

  Change to:

```javascript
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
  renderEv();
}
```

- [ ] **Add the `renderEv()` function.** Insert it directly after `renderNight()` (after its closing brace on line 596, before `function updateNightLux(status)`):

```javascript
function renderEv() {
  const host = document.getElementById("ev");
  clear(host);
  // Defensive: old configs and server responses may return 0/1/2 slots — always render exactly two.
  if (!Array.isArray(config.entities.evs)) config.entities.evs = [{}, {}];
  const evs = config.entities.evs;
  while (evs.length < 2) evs.push({});

  evs.slice(0, 2).forEach((slot, i) => {
    const box = el("div", "group");
    const head = el("div", "group-head");
    head.appendChild(el("span", "panel-name", "EV " + (i + 1)));
    box.appendChild(head);

    const name = el("input");
    name.value = slot.name || "";
    name.setAttribute("aria-label", "EV name");
    name.addEventListener("change", () => slot.name = name.value.trim());
    box.appendChild(labeledRow("Name", name));

    box.appendChild(labeledRow("Charging when on",
      entityPicker(["binary_sensor", "sensor", "switch"], slot.charging, v => slot.charging = v)));
    box.appendChild(labeledRow("Battery %",
      entityPicker(["sensor"], slot.soc, v => slot.soc = v)));
    box.appendChild(labeledRow("Charge power",
      entityPicker(["sensor"], slot.power, v => slot.power = v)));
    box.appendChild(labeledRow("Time remaining",
      entityPicker(["sensor"], slot.eta, v => slot.eta = v)));

    host.appendChild(box);
  });

  host.appendChild(el("div", "muted",
    "A card shows on the home screen while a car charges. “Charging when on” is required — its " +
    "on/charging state triggers the card. Battery %, charge power (W or kW), and time remaining " +
    "(minutes, H:MM:SS, or a timestamp) are optional. Empty slots are dropped on save."));
}
```

- [ ] **Run the full gate, expect exit 0** (assets are bundled; this confirms the build still assembles):
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Commit:**
```
git add app/src/main/assets/config/index.html app/src/main/assets/config/app.js
git commit -m "$(cat <<'EOF'
Add EV charging section to the web config page

Two fixed EV slots (name + four entity pickers) under a new EV card,
defensively padded to two so 0/1/2-slot server responses re-render cleanly.
Server-side clamped() stays authoritative on save.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Plan self-review (spec coverage)

- **Config (spec §1):** EvConfig with `ids()` — Task 1. `Entities.evs` — Task 1. `clamped()` per-slot trim + blank→null + all-blank drop + `take(2)` — Task 1. `referencedEntityIds()` adds ev ids — Task 1. Tests 11 & 12 — Task 1.
- **State computer (spec §2):** EvCard shape, `evCards()` signature, truthy set `{on,true,charging}`, missing/non-truthy → no card, SOC clamp+round, unit-aware kW power, three-shape ETA parser (minutes / H:MM:SS / ISO timestamp), zero/negative → null, statusLine join, config order / skip idle — all Task 2. Tests 1–10 — Task 2.
- **UI (spec §3):** `evs` param, non-takeover branch only, `Alignment.TopEnd` + `padding(top=20,end=28)`, `spacedBy(10.dp)`, `AnimatedVisibility` fade 600ms, per-card pill (0.35 black, 20dp radius, 16/10 padding, `spacedBy(4.dp)`), row1 bolt+name 16sp Medium, row2 gauge 96×8 track / 0.25 white / green `0xFF7BC67E` fill / 14sp %, row3 statusLine 0.9 white 14sp — Task 3.
- **Wiring (spec §4):** DashboardShell `remember(entities, config.entities.evs)` → `evCards(...)`, passes `evs`; App.kt untouched; night controllers untouched — Task 3.
- **Web config (spec §5):** ev-section after night-section, `ICONS.ev`, two fixed slots with name input + four pickers using the exact domain lists, defensive pad-to-2, wired into `render()` — Task 4.
- **Type/name consistency:** `EvConfig` field names (`name/charging/soc/power/eta`) identical across DashConfig, EvModel, tests, and JS keys. `EvCard` fields (`name/socPct/statusLine`) identical across EvModel and HomeView. `evCards(cfgs, entities, nowMs)` signature identical between EvModel and the DashboardShell call. No placeholders; every code block is complete.

### Resolved spec ambiguities

1. **`clamped()` filter/cap order.** Spec §1 lists "trim … then `take(2)`. Slots [all-blank] are dropped" without pinning order. Implemented `.map{clean}.filter{keep}.take(2)` (drop empties, then cap) — consistent with the camera cleaner's map-then-filter. Test 11's inputs are ordered so the result is identical under either filter/take ordering, so the test does not lock in the choice.
2. **Test 11 slot count.** Spec prose says "three slots in → two out," but the test name says "AndCapped," which can only be exercised with more than two *valid* slots. Used four input slots (3 valid + 1 all-blank): the blank is dropped and the third valid slot is capped out, so both behaviors are genuinely tested and the result is still two slots.
3. **Sub-1 kW power display.** Spec says power is "unit-aware like `formatWatts`" (which prints `W` below 1000) yet also says EV power displays as kW ("one decimal below 10 kW, integer at ≥10"). Since a charging EV's power is always ≥1 kW and every spec test case is ≥1 kW, `formatPower` always renders kW (W values are divided by 1000 unconditionally). This satisfies all listed test cases; the `W`-suffix branch of `formatWatts` is intentionally not reused.
4. **`ICONS.ev` usage.** The spec requires adding an `ev` glyph to the `ICONS` map, but the `ev-section` card-head (like every other section) uses an inline SVG, and `renderEv()` uses `group-head` rows (matching the camera/light slots) rather than `subhead()`. Resolved by adding `ICONS.ev` as specified and using the identical SVG path inline in the section head so the two stay visually consistent; the map entry is harmless if not otherwise referenced and is available to `glyph("ev")`/`subhead("ev", …)` if wanted later.
5. **ETA duration rounding.** Spec gives `"1:05:00" → 1h05` but is silent on sub-minute seconds. `etaMinutes` rounds the seconds component to the nearest minute (`+1` when `s ≥ 30`), which yields `1h05` for the spec case and is the least-surprising behavior for arbitrary `H:MM:SS` inputs.
