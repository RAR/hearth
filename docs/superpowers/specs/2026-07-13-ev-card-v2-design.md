# EV Card v2: Plugged-In Trigger, Charging Animation, Session Energy (2026-07-13)

Amends the EV charging box (spec 2026-07-13-ev-charging-box-design.md, shipped @ 972e80f with the simplified 2-row card).

## User request

Show the card whenever the car is **plugged in** (not only charging); while actually **charging**, animate the SOC bar so it looks like it's flowing toward full, and show the session **energy**.

## Changes

### 1. Config — `EvConfig` gains two optional entities

```kotlin
val plugged: String? = null,  // entity whose truthy state shows the card (cable connected)
val energy: String? = null,   // session energy sensor (Wh or kWh, unit-aware)
```

Field order in the class: name, plugged, charging, soc, power, energy, eta. `ids()` includes both new fields. `clamped()` cleaning covers them (same trim/blank→null); all-blank-drop rule now spans all six ids. `referencedEntityIds()` unchanged (walks ids()).

### 2. Model — `EvModel.kt`

- Two truthy sets (lowercased, trimmed state):
  - `PLUGGED_TRUTHY = {"on", "true", "connected", "charging", "b", "c"}`
  - `CHARGING_TRUTHY = {"on", "true", "charging", "c"}`
  - The `b`/`c` letters support EVCC's loadpoint status sensor (A=disconnected, B=connected, C=charging) — the SAME entity can be assigned to both pickers.
- Card visibility: produced when the `plugged` entity is PLUGGED-truthy **or** the `charging` entity is CHARGING-truthy. (Either may be unconfigured; a config with only `charging` behaves exactly as v1.)
- `EvCard` gains `val charging: Boolean`.
- `statusLine` is built **only while charging**: segments power ("7.2 kW"), energy ("4.3 kWh"), eta ("1h05 left") joined with " · "; null when not charging (a plugged-idle card shows just gauge + %; EVCC power reads 0 W when idle — noise).
- `formatEnergy`: unit-aware like power — attr `unit_of_measurement` "kWh" used as-is, else treated as Wh and divided by 1000; one decimal below 10 ("4.3 kWh"), integer at ≥10 ("12 kWh"); unparseable → null.

### 3. View — `EvCardView` in HomeView.kt

- Detail row unchanged in structure (`soc% · statusLine` joined).
- While `card.charging` and the gauge is shown: a shimmer overlays the FILLED portion — an `rememberInfiniteTransition` float 0→1 (tween 1800ms, LinearEasing, Restart), driving a horizontal gradient band (transparent → white 35% → transparent, band ~24dp) that sweeps left-to-right across the fill, clipped to the fill's rounded shape. Not charging → static bar exactly as today.

### 4. Web config page

Per-slot pickers become (order): Name; "Plugged in when on" `entityPicker(["binary_sensor","sensor","switch"], slot.plugged, ...)`; "Charging when on" (unchanged domains); "Battery %"; "Charge power"; "Session energy" `entityPicker(["sensor"], slot.energy, ...)`; "Time remaining". Muted help text updated: card shows while plugged in or charging; power/energy/time only display while charging; EVCC's status sensor (A/B/C) can drive both trigger pickers.

## Tests (EvModelTest updates + additions; DashConfigTest touch-ups)

1. Existing tests keep passing (charging-only config unchanged) — statusLine tests all use charging entities, still valid.
2. `pluggedShowsCardWithoutChargingAndHidesStatus` — plugged=on, charging entity absent/off, power entity configured and reporting 7240 W: card exists, `charging=false`, `statusLine=null`, soc still shown.
3. `statusLetterEntityDrivesBothStates` — same entity id in plugged+charging: state "B" → card, charging=false; "C" → card, charging=true; "A" → no card.
4. `energyUnitAwareInStatusLine` — charging, power 7240 W + energy "4300" Wh + eta 65 → `"7.2 kW · 4.3 kWh · 1h05 left"`; energy "4.3" kWh same result; energy "12.4" kWh → "12 kWh" segment.
5. `chargingFlagSetWhileCharging` — charging=on → `charging=true`.
6. DashConfigTest: extend `evSlotsClampedTrimmedAndCapped` inputs to cover plugged/energy trim/blank→null (keep assertions equivalent); `referencedEntityIdsIncludeEvEntities` extended with plugged+energy ids.

## Constraints

Unchanged project constraints (Kotlin 2.1.0, compileSdk 34, no new deps, plain-JVM tests, gate `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` exit 0, commit trailer `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`). Config back-compat: old saved configs (no plugged/energy keys) load via defaults and behave as v1.
