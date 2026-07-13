# Home-View Rain Pill (Event Rain Total) — Design

**Date:** 2026-07-13
**Status:** Approved by user (design conversation; icon amendment applied)

## Goal

While it's raining, show the running rain total for the current event on the
home view. Source: an "event rain" sensor (user's:
`sensor.weather_station_event_rain_rate`) that accumulates over a rain event
and resets to 0 when the station considers the event over — so `value > 0`
IS the "it's raining" signal; no separate rain-state entity is used.

## Non-goals (YAGNI)

- No rain rate, hourly/daily/weekly totals.
- No display during media takeover (hides with the other pills) and no
  night-mode override.
- No color coding — plain white like the weather pill (user amendment:
  same raindrop icon as the weather icon set, not a colored one).

## Design

**UI** — a third pill in the home view's top-left pill row, after AQI,
identical styling (black 35% rounded background, 18sp white text):
`weatherIcon(WeatherIcon.RAIN)` (= `Icons.Outlined.WaterDrop`) at 22dp,
white alpha 0.95, then the total with unit, e.g. "0.42 in". Stale sensor
(no update in 15 min, `STALE_AFTER_MS`) dims icon+text to 0.4 alpha like
the weather pill.

**Model** — in `ui/model/AqiModel.kt`'s sibling style (place in
`WeatherModel.kt`):

```kotlin
data class RainPill(val text: String, val stale: Boolean)

/** Event-rain pill; visible only while the event total is > 0. */
fun rainPill(rainSensorId: String?, entities: Map<String, EntityState>, nowMs: Long): RainPill?
```

Rules: null when unset / entity missing / state non-numeric; null when
value <= 0 (event over). Text = `formatSensor(value, 2)` + " " +
`unit_of_measurement` attr (unit omitted when the attr is absent).
Stale = `nowMs - lastUpdatedMs > STALE_AFTER_MS`.

**Config** — `entities.rainEvent: String? = null` on `Entities`
(DashConfig): trim/ifBlank in `clamped()`, added to
`referencedEntityIds()`. Config page Entities card gains an "Event rain
sensor" picker (sensor domain) directly after the AQI picker.

**Wiring** — DashboardShell HOME branch computes
`rainPill(config.entities.rainEvent, entities, now)` in a `remember`
block (keys: entities, config.entities) and passes `rain: RainPill?` to
`HomeView`; the pill row's visibility condition extends to
`pill != null || aqi != null || rain != null`.

## Testing

Pure-JVM JUnit4 in `WeatherModelTest` (or alongside existing model tests):
unset/missing/non-numeric → null; 0 and negative → null; positive →
text "0.42 in" (formatting to 2 decimals, unit appended, unit-less sensor →
bare number); stale flag both sides of the 15-min boundary. DashConfig
round-trip/clamp/referencedEntityIds additions in DashConfigTest.

On-device: pill hidden in dry weather (sensor reads 0); visual appearance
verifiable only during a real rain event (or by temporarily pointing the
picker at any positive-valued sensor).
