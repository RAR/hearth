# EV Charging Box — Design Spec (2026-07-13)

## Goal

When an EV is charging, the home view shows a top-right data card per charging vehicle: name, SOC battery gauge, charge power, and time-to-finish. Data comes from HA entities (EVCC integration) over the existing WebSocket; configuration lives on the web config page. Up to two EVs; both cards stack when both charge simultaneously. User-approved layout (960×480):

```
+----------------------------------+---+
| [72°F] [AQI 41]  +-------------+ | r |
|                  | ⚡ Ioniq    | | a |
|                  | ███░ 64%   | | i |
|                  | 7.2kW · 1h05| | l |
|                  +-------------+ |   |
| 9:41                             |   |
| Sunday, July 13th                |   |
+----------------------------------+---+
```

## User decisions (locked)

- Data source: **HA entities from the EVCC integration** (not EVCC REST/MQTT).
- **Two EV slots**, both cards stack when both charge.
- Fields: **SOC % (battery gauge), time to finish, charge power (kW)**. No session energy.
- Placement: **top-right** of the home view.
- **Home only; night untouched**: hidden during the music takeover; NOT a night-mode override (an overnight charge must not keep the screen lit or appear on the night clock).

## Out of scope

Session energy, tap-to-expand, charging mode (pv/now) display, an EV rail panel (`Panels` entry / IconRail icon), any EVCC-direct network path, notifications.

## Architecture

Follows the Solar triad pattern exactly: fixed-slot config in `Entities` → pure-JVM state computer in `ui/model` → dumb composable rendered by `HomeView`.

### 1. Config — `DashConfig.kt`

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

- `Entities` gains `val evs: List<EvConfig> = emptyList()`.
- `DashConfig.clamped()` cleans inline like solar (lines ~175-181 pattern): per slot, `name.trim()`, each entity id `?.trim()?.ifBlank { null }`, then `take(2)`. Slots whose four entity ids are all null AND name is blank are dropped.
- `DashConfig.referencedEntityIds()` adds `entities.evs.forEach { addAll(it.ids()) }` — without this EntityHub never subscribes (verified requirement, DashConfig.kt:138-148).

### 2. State computer — `ui/model/EvModel.kt` (pure JVM, no Compose/Android imports)

```kotlin
/** One charging EV's card. Fields are pre-formatted display strings; null = omit that line. */
data class EvCard(
    val name: String,        // config name, or "EV" when blank
    val socPct: Int?,        // 0..100 for the gauge + "%" text; null hides the gauge row
    val statusLine: String?, // "7.2 kW · 1h05 left" / "7.2 kW" / "1h05 left"; null hides the row
)

fun evCards(cfgs: List<EvConfig>, entities: Map<String, EntityState>, nowMs: Long): List<EvCard>
```

Rules:
- A card is produced only when the slot's `charging` entity exists and its state, lowercased, is one of `"on"`, `"true"`, `"charging"` (covers binary_sensor and EVCC/string sensors). No charging entity configured → no card ever (the charging entity is the trigger, not optional).
- `socPct`: `entities[soc]?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)`.
- Power: unit-aware exactly like `SolarModel.formatWatts` (unit_of_measurement `"W"` → divide by 1000 when ≥1000 shown as kW; `"kW"` used as-is). Display with one decimal below 10 kW (`"7.2 kW"`), integer at ≥10 (`"11 kW"`).
- ETA parsing (in priority order), producing `"1h05"` / `"45m"`:
  - numeric state → minutes (`"65"` → `1h05`);
  - `H:MM:SS` / `HH:MM:SS` duration string → hours+minutes;
  - ISO-8601 timestamp (contains `"T"`, parseable via `java.time.Instant`/`OffsetDateTime`) → `max(0, ts - nowMs)` as minutes;
  - anything else → null.
  Zero or negative remaining → null (hide the eta; EVCC reports 0/unknown while finishing).
- `statusLine` joins the non-null of (power, eta+" left") with `" · "`; both null → null.
- Order follows config slot order; a non-charging slot between two charging ones is simply skipped.

### 3. UI — `ui/HomeView.kt`

- New param `evs: List<EvCard> = emptyList()` on `HomeView`.
- Rendered only in the **`else` (non-takeover) branch** (HomeView.kt:186-253 structure), as a `Column` anchored `Alignment.TopEnd`, `padding(top = 20.dp, end = 28.dp)`, `verticalArrangement = Arrangement.spacedBy(10.dp)` — mirrors the pills row's paddings; sits clear of the 12dp offline dot corner (dot keeps its spot; the column starts below/inside of it, top=20 vs dot's 12, and the dot is 8dp — acceptable overlap-free by inspection on 480px height).
- Wrapped in `AnimatedVisibility(visible = evs.isNotEmpty(), enter = fadeIn(tween(600)), exit = fadeOut(tween(600)))` for the crossfade in/out.
- Each card: pill-style `Column` (background `Color.Black.copy(alpha = 0.35f)`, `RoundedCornerShape(20.dp)`, padding 16.dp horizontal / 10.dp vertical, `Arrangement.spacedBy(4.dp)`), matching the existing pill visual language:
  - Row 1: `"⚡ " + name`, white, 16sp, `FontWeight.Medium` (the ⚡ is a text glyph — no new icon assets).
  - Row 2 (when `socPct != null`): battery gauge — `Box` track 96dp × 8dp, `RoundedCornerShape(4.dp)`, `Color.White.copy(alpha = 0.25f)`, with a fill `Box` `fillMaxWidth(socPct / 100f)` in `Color(0xFF7BC67E)`; beside it `"64%"` white 14sp.
  - Row 3 (when `statusLine != null`): statusLine, `Color.White.copy(alpha = 0.9f)`, 14sp.

### 4. Wiring — `ui/DashboardShell.kt` + `App.kt`

- `DashboardShell` computes next to the pills (DashboardShell.kt:104-109 pattern):
  ```kotlin
  val evs = remember(entities, config.entities.evs) {
      evCards(config.entities.evs, entities, System.currentTimeMillis())
  }
  ```
  and passes `evs = evs` to `HomeView`. Recomputation rides entity updates (EVCC sensors update continuously while charging), same as the weather/AQI pills; no dedicated ticker.
- `App.kt`: no changes (entities map + config already flow into DashboardShell).
- Explicitly NOT touched: the night-mode override expression in App.kt, `NightModeController`, `KioskController`.

### 5. Web config page — `assets/config/index.html` + `app.js`

- New standalone section after the Night card (night-section structure, index.html:172-183): `<section id="ev-section" class="card-section">`, head icon + `<h2>EV charging</h2>` + `<p>Home-screen card while a car charges — assign EVCC entities.</p>`, host `<div id="ev"></div>`.
- New `ev` glyph in the `ICONS` map (app.js) — simple inline-SVG car silhouette or reuse of the bolt path style already present; no external assets.
- Render two fixed slots ("EV 1", "EV 2") — fixed slots like solar, NOT an add/remove list. Per slot:
  - name: plain text `<input>` (`labeledRow("Name", ...)`),
  - `entityPicker(["binary_sensor", "sensor", "switch"], slot.charging, ...)` labeled "Charging when on",
  - `entityPicker(["sensor"], slot.soc, ...)` "Battery %",
  - `entityPicker(["sensor"], slot.power, ...)` "Charge power",
  - `entityPicker(["sensor"], slot.eta, ...)` "Time remaining".
- JS reads/writes `config.entities.evs` defensively: `config.entities.evs = config.entities.evs || [{}, {}]`; ensure two slot objects exist before rendering (`while (evs.length < 2) evs.push({})`). Server-side clamped() remains authoritative on Save (empty slots get dropped; the page re-renders from the server response — `render()` must tolerate a returned list of 0/1/2 by re-padding to 2).

## Error handling

- Missing/`unavailable`/`unknown` entity states: `toDoubleOrNull()` fails → that field null → line hidden; charging entity `unavailable` is not truthy → card hidden. No crashes on garbage states (tests cover).
- Config with >2 slots or blank ids: normalized by `clamped()`.
- Both EVs charging with partially-configured slots: each card renders independently with whatever fields it has.

## Tests (all plain-JVM JUnit4)

`ui/model/EvModelTest.kt` (style of SolarModelTest — private `st()` helper with unit attr):
1. `noCardWhenChargingEntityMissingOrFalsy` — unconfigured slot, `"off"`, `"unavailable"`, missing entity → empty list.
2. `truthyVariantsProduceCard` — `"on"`, `"true"`, `"Charging"` (case-insensitive) each produce a card.
3. `socClampedAndRounded` — `"63.6"` → 64; `"104"` → 100; garbage → null gauge.
4. `powerUnitAwareFormatting` — 7240 W → `"7.2 kW"`, `"7.24"` kW → `"7.2 kW"`, 11000 W → `"11 kW"`.
5. `etaMinutesNumber` — `"65"` → `1h05 left`; `"45"` → `45m left`.
6. `etaDurationString` — `"1:05:00"` → `1h05 left`.
7. `etaTimestamp` — ISO timestamp nowMs+65min → `1h05 left`; past timestamp → eta omitted.
8. `statusLineJoinsAndOmits` — power+eta joined with `·`; only power; only eta; neither → null statusLine but card still shown (name+gauge).
9. `blankNameFallsBackToEV`.
10. `twoChargingKeepConfigOrderAndSkipIdle` — slots [idle, charging] → one card; [charging, charging] → two, config order.

`config/DashConfigTest.kt` additions:
11. `evSlotsClampedTrimmedAndCapped` — three slots in → two out; ids trimmed, blank → null; all-blank slot dropped.
12. `referencedEntityIdsIncludeEvEntities`.

## Build gate

`JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` from repo root, exit 0.

## Global constraints (binding, from project)

Kotlin 2.1.0; compileSdk 34 (never bump); media3 exactly 1.4.1; NanoHTTPD 2.3.1; NO new dependencies; no Android classes in tests; commit trailer `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi` as the final line of every commit.
