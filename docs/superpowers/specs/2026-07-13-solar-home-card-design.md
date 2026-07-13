# Solar Home Card (2026-07-13)

A home-view solar/battery pill card styled like the EV charging cards, stacked **below** them in the same top-right column. User request: "lets create a similar blob for the house solar that goes below those" + clarification: "we have solar and a home battery, so we should see production, battery SOC, load, and grid load".

## Approved layout (AskUserQuestion, all recommendations accepted)

```
☀ Solar 3.2 kW         78%     ← SolarPower icon + PV output, battery SOC right-aligned
[███████████████░░░░░]         ← battery SOC gauge, shimmers while battery charges
Home 1.4 kW · Export 1.8 kW    ← house load + grid import/export
```

- **Visibility: always** — the card shows whenever any of pv/load/grid/battSoc resolve (battery/grid matter at night too). No producing-only rule, no toggle.
- Bar = battery SOC (EV-card gauge: same green, same shimmer, no limit tick).

## 1. Config — `SolarConfig` gains two optional entities

```kotlin
val battSoc: String? = null,   // home battery % sensor
val battPower: String? = null, // battery power W/kW; negative = charging (evcc convention)
```

`ids()` includes both. `clamped()` cleaning adds matching trim/blank→null lines. `referencedEntityIds()` unchanged (walks ids()). Old saved configs load via defaults.

## 2. Model — `SolarModel.kt` gains a pure card computer

```kotlin
data class SolarCard(
    val pvText: String?,       // formatted PV output for the header ("3.2 kW"); null = no pv sensor
    val socPct: Int?,          // battery SOC 0–100; null = no gauge
    val battCharging: Boolean, // battery power below -50 W (evcc: negative = charging)
    val statsLine: String?,    // "Home 1.4 kW · Export 1.8 kW"; null when empty
)

fun solarCard(cfg: SolarConfig, entities: Map<String, EntityState>): SolarCard?
```

- Returns **null** when none of pv/load/grid/battSoc resolve → card hidden.
- `socPct` = state.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100) (non-numeric → null → no gauge).
- `battCharging` from a new private `powerWatts(state)` helper (signed watts, kW unit → ×1000); true iff watts < −50 (deadband avoids shimmer flicker at ~0). No battPower sensor → false.
- `statsLine` segments joined " · ": "Home <formatWatts(load)>" then grid as "Import <mag>"/"Export <mag>" (existing convention: grid ≥ 0 = importing; `formatWatts` is magnitude-only). Unconfigured/unparseable segments drop out.
- Reuses the existing private `formatWatts`; `solarFlow()` untouched.

## 3. View — HomeView + DashboardShell

- Extract the EV bar (track + fill + shimmer + optional tick) into one shared private composable `GaugeBar(fillPct: Int, shimmer: Boolean, tickPct: Int? = null)`; `EvCardView` calls `GaugeBar(card.socPct, card.charging, card.limitPct)` — rendering byte-identical to today.
- New private `SolarCardView(card: SolarCard)`: same pill (248dp, black 0.35, 20dp radius, 16/10dp padding, 4dp row spacing); header row `Icons.Outlined.SolarPower` (18dp, white) + `"Solar ${pvText}"` (or "Solar" when pvText null, 16sp Medium, weight 1f) + `"${socPct}%"` (14sp) when socPct present; `GaugeBar(socPct, battCharging)` when socPct present; statsLine (14sp, white 0.9, maxLines 1, ellipsis) when present.
- `HomeView` gains `solar: SolarCard? = null`; the top-right `AnimatedVisibility` becomes `visible = evs.isNotEmpty() || solar != null`; inside the Column, `SolarCardView(solar)` renders after `evs.forEach { EvCardView(it) }` (same 10dp spacing).
- `DashboardShell` HOME block: `val solar = remember(entities, config.entities.solar) { solarCard(config.entities.solar, entities) }`, passed to HomeView.

## 4. Web config page — app.js

`solarSlots` gains `["battSoc", "Battery %"], ["battPower", "Battery power"]` (existing `entityPicker(["sensor"], …)` loop covers them). A muted hint after the solar rows: battery % + power add a gauge card to the home view; negative battery power = charging (evcc convention); grid positive = importing.

## Tests (plain JVM)

- `SolarModelTest`: full-data card (pvText/soc/statsLine/import-export labels); battCharging sign + deadband (−200 → true, −20 → false, +500 → false, kW unit −0.2 kW → true); missing pv → pvText null, card still present; soc non-numeric → socPct null; all-unconfigured → null; soc clamped to 0–100.
- `DashConfigTest`: clamped() trims/blanks battSoc/battPower; referencedEntityIds() includes them.

## Constraints

Kotlin 2.1.0; compileSdk 34 NEVER bump; NO new dependencies; plain-JVM JUnit4 tests only; gate `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` exit 0; commit trailer `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`. Config back-compat required.
