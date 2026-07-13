# Auto-Hide Panel Bar Option — Design Spec (2026-07-13)

## Goal

A config toggle that makes the right-side icon rail auto-hide everywhere, exactly the way it already does on the music takeover: hidden by default, any touch slides it in, it slides back out after 8 seconds. Default off (current behavior unchanged).

## Changes

1. **`config/DashConfig.kt`** — `PanelOptions` gains `val autoHideRail: Boolean = false` (after `doorbellPopupSeconds`). No clamping (boolean). Old configs load with the default via `ignoreUnknownKeys`.

2. **`ui/DashboardShell.kt`** — the auto-hide gate (currently `val takeover = current == DashView.HOME && takeoverVisible` feeding `LaunchedEffect(takeover, railTouches)`) becomes:
   ```kotlin
   val autoHide = (current == DashView.HOME && takeoverVisible) || config.panelOptions.autoHideRail
   ```
   with the LaunchedEffect keyed on `(autoHide, railTouches)` and its `if (takeover)` → `if (autoHide)`. Rename the local from `takeover` to `autoHide`; update the comment above it to say the rail also auto-hides everywhere when the option is on. Nothing else changes — same `RAIL_HIDE_MS`, same slide/fade `AnimatedVisibility`.

3. **`assets/config/app.js`** — `renderOptions()` gains a checkbox (night-card toggle pattern: `el("input")`, `type="checkbox"`, `checked = !!o.autoHideRail`, aria-label, change listener) as `labeledRow("Auto-hide panel bar", toggle)` inserted after the doorbell popup row and before the muted hint; the muted hint text gains a trailing sentence: `"Auto-hide slides the panel bar away; any touch brings it back for 8 s."`

## Behavior notes (accepted)

- The revealing touch also reaches the panel underneath (touches are observed on the Initial pass, never consumed) — identical to today's takeover behavior.
- When the option is off, takeover behavior is unchanged; when on, the takeover case is subsumed.

## Tests

- `DashConfigTest.autoHideRailDefaultsFalseAndSurvivesClamped` — old config JSON without the key decodes to `false`; `clamped()` preserves `true`. (The shell logic is Compose-side, covered by the build gate.)

## Constraints

Kotlin 2.1.0; compileSdk 34; NO new dependencies; plain-JVM JUnit4; gate `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` exit 0; commit trailer `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`.
