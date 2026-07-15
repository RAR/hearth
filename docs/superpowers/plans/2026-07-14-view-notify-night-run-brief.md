# Night-run brief: Hearth sub-project B (view select + notify)

Operational handoff for the autonomous overnight run the user authorized on
2026-07-14 ("kick you off for a night run"). The DESIGN is settled and
committed — do not re-open it: docs/superpowers/specs/2026-07-14-hearth-view-notify-design.md.

## Workflow (the repo's established loop)

1. writing-plans skill: draft the implementation plan via an **opus** subagent
   (verify its anchors against real files first), save to
   docs/superpowers/plans/2026-07-14-hearth-view-notify.md, commit.
2. subagent-driven development: sonnet implementers (plan code is
   transcription-grade), opus reviewers for the HA layer / final review;
   ledger at .superpowers/sdd/progress.md (append under a new feature heading).
3. Gates: JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q
   :app:testDebugUnitTest :app:assembleDebug; python3 -m pytest
   tests/integration -q; py_compile + json.load over changed integration files.
4. Ship: push origin master; flash BOTH devices
   (adb -s 10.75.1.98:5555 / adb -s HA1TREYR, install -r then sleep 3 then
   force-stop + monkey-launch com.rar.echodash).
5. Live-verify app side per the spec's "Testing & verification" section
   (protocol script from the scratchpad against the tablet 10.75.0.183:10700;
   screenshots via adb exec-out screencap). Evicting HA's session is expected
   and self-heals — do it once, keep it short.
6. Update memory (project-state feature entry, future-features #6/#8, MEMORY.md)
   and leave a morning report as the final message: what shipped, verification
   evidence, and the user's HACS-update steps.

## Hard constraints (unchanged, verbatim from memory)

- NEVER use the HA MCP bridge (points at a foreign demo HA). The user's real
  HAOS is unreachable — HACS redownload + HA restart are MORNING USER STEPS.
- NEVER run `dumpsys media.audio_flinger` (crashes the Echo audio HAL).
- compileSdk 34 never bump; no new dependencies either side; integration stays
  zero-pip-requirements; codec.py/client.py stay HA-import-free.
- Every commit ends with:
  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
- Work directly on master. User-facing copy says "Hearth".

## Facts the plan needs (verified 2026-07-14, HEAD 42a04d6)

- DashView enum: HOME, LIGHTS, CLIMATE, MEDIA, CALENDAR, WEATHER, SOLAR,
  CAMERAS (app/src/main/java/com/rar/echodash/ui/DashViews.kt:31); shell takes
  (current: DashView, onSelect) — view state currently lives above
  DashboardShell; find the hoist point in MainActivity/composition root.
- statusSnapshot() at App.kt:370 currently sends sensors.orientation +
  hardcoded current_path "dashboard" (nothing consumes current_path — VACA is
  gone; replace with current_view, keep orientation).
- Action dispatch seam: VacaServer.Listener.onAction in AppDeps (App.kt ~228)
  routes media.handleAction then kiosk.handleAction — extend there.
- PushNotificationStore.post(id, title, message, severity, timeoutSeconds,
  nowMs) clamps everything; severityOf: info/warning/critical, unknown→INFO;
  clear(id) idempotent; clearAll(). HTTP handlers in web/ConfigServer.kt
  (handleNotify/handleNotifyClear) show the exact field semantics to mirror.
- Wake path: KioskController.onUserInteraction() (screensaver clear + wake +
  re-arm); night-mode touch-wake gives a 60 s window then re-enters if dark.
- Integration: entities follow switch.py's listener/availability pattern;
  services follow hearth.toast in __init__.py (async_extract_referenced_entity_ids);
  bump manifest version to 0.2.0; add select + notify to PLATFORMS in const.py.
  Stock NotifyEntity: from homeassistant.components.notify import NotifyEntity,
  platform "notify", async_send_message(message, title=None) is the override.
- Devices: Echo "Andrew Desk" 10.75.1.98 (config :8080 PIN 379199), tablet
  "Living Room Wall" 10.75.0.183 / adb USB HA1TREYR (PIN 489165). Both on the
  hearth integration (VACA removed from HA 2026-07-14).
- pytest 9.0.2 system-wide; tests import via the hearth_proto conftest trick.

## Definition of done (overnight)

- Plan committed; all tasks implemented, reviewed (spec + quality per task,
  final whole-feature review), gates green, pushed, both devices flashed.
- App-side live verification evidence captured (screenshots + observed
  current_view status events) for: set-view switches + wakes, disabled-panel
  ignore, notify posts/replaces/clears, snapshot carries current_view.
- Memory + ledger updated; morning report written. HA-side entity verification
  explicitly listed as the user's morning step — do NOT attempt to reach HA.
