# AGENTS.md

Guidance for AI coding agents working in this repo. Humans: see `README.md`.

## What this is

**Hearth** is one repo with two halves that talk over a small custom wire protocol:

- **`app/`** — a native Android kiosk (Kotlin + Jetpack Compose) that turns a
  landscape Android device into an always-on Home Assistant dashboard and
  Wyoming voice satellite. Configured entirely from a web page the device
  serves on the LAN (no YAML, no HA labels).
- **`custom_components/hearth/`** — a slim HA custom integration that gives HA
  control of each device (media player, screen, brightness, toasts, TTS
  announce, view select, notify). Installed via HACS.

The two connect over the Hearth wire protocol: a Wyoming-style TCP server the
app runs on port **10700**, advertised via mDNS `_hearth._tcp`.

## Build, test, run

JDK 17+ required (`JAVA_HOME` must point at one). The Android SDK path comes
from `local.properties` (`sdk.dir=…`).

```bash
# App — the gate. Both must pass before any commit.
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# Integration — protocol-layer tests (Python stdlib + pytest only)
python3 -m pytest tests/integration -q

# Install / iterate on a device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the full gate green **before every commit**. This repo works directly on
`master`; keep commits small and focused.

## Hard constraints — do not break these

- **`compileSdk` / `targetSdk` stay at 34. Never bump them.** Same for
  `minSdk = 28`.
- **No new dependencies** on either side without explicit human approval. The
  app's deps (in `app/build.gradle.kts`) are deliberately minimal — Compose BOM,
  coroutines, serialization, OkHttp, media3, NanoHTTPD, TensorFlow Lite. The
  integration has **zero runtime/pip dependencies** (`manifest.json`
  `requirements` is empty) — keep it that way; use only the Python stdlib.
- **App tests are plain-JVM JUnit4 only** — no instrumented tests, no
  Robolectric. `testOptions.unitTests.isReturnDefaultValues = true` is set so
  Android stubs return defaults; design testable logic as pure functions.
- **Integration `codec.py` / `client.py` are HA-free and unit-tested** — no
  `homeassistant` imports in them. Entity platforms (`*.py`) stay thin.
- Kotlin 2.1.0, JVM target 17, Compose compiler via the Kotlin Compose plugin.

## Conventions

- Match the style of the surrounding code — naming, comment density, idioms.
  Comments explain *why*, not *what*; the codebase leans on them for non-obvious
  device/protocol behavior. Keep that.
- Prefer small, focused files with one clear responsibility.
- Config is **web-driven**: a versioned `DashConfig` JSON in the app's
  `filesDir`, edited from the config page. There is no YAML/HA-label config path
  anymore — don't reintroduce one.

## App architecture (`app/src/main/java/com/rar/hearth/`)

- The Kotlin package is `com.rar.hearth`, but the **`applicationId` stays
  `com.rar.echodash`** (in `app/build.gradle.kts`) so the app updates in place on
  the fleet and keeps its `filesDir` auth/config/PIN. Never change `applicationId`;
  only `namespace` tracks the package. The on-device data path is therefore still
  `/data/data/com.rar.echodash/`.
- `App.kt` — `HearthApp` composable (top-level state, screen routing, splash
  overlay); `MainActivity`, `HearthApplication`, `BootReceiver`.
- `ha/` — Home Assistant WebSocket client, `EntityHub` (one `subscribe_entities`
  feed), connection state.
- `device/` — **the Hearth wire protocol + device integration.** `HearthServer` is
  the port-10700 server; `HearthMessages` the codec (`HearthIncoming`/`HearthParser`/
  `HearthOutgoing`); `MediaBridge`, `KioskController`, `SatelliteSession` handle
  HA-driven control. (Formerly the `vaca/` package — renamed 2026-07-20; the wire
  protocol itself, `_hearth._tcp.` + port 10700, is unchanged.)
- `ui/` — Compose screens; `ui/panels/` the right-rail panels; `ui/theme/` the
  Nunito type system and colors.
- `data/` — `SettingsStore` / `DashConfig` persistence.
- `web/` — the NanoHTTPD config server + JSON API (PIN-gated, LAN-only).
- `photos/`, `media/`, `voice/`, `night/`, `notify/`, `camera/`, `config/` —
  feature subsystems (slideshow, ExoPlayer, wake word + timers, night mode,
  push/NWS notifications, camera streams, config models).

Voice is deliberately separate from the Hearth integration: the satellite speaks
to HA **core's** Wyoming (port 10600) and works with or without Hearth installed.
Don't entangle the two.

`sendspin/` is a **vendored** copy of the MIT-licensed chrisuthe/SendSpinDroid
engine (see `NOTICE` for attribution and the exact upstream commit), trimmed to
the LOCAL WebSocket path only (no WebRTC/proxy/Noise) — Music Assistant
connects to Hearth by mDNS discovery, same as any other SendSpin player. The
vendored files carry small, documented Hearth adaptations: per-track ducking in
the three audio files (`AudioSink` / `AudioTrackSink` / `SyncAudioPlayer.setVolume`),
the stream-end role match + `isPlayerStreamEnd` extraction in
`SendSpinProtocolHandler`, and per-frame fault isolation + debug-level logging
in the transport — see `NOTICE` and git history for the exact delta. Keep that
in mind before reflexively re-syncing from upstream. The
`sendspin/musicassistant/` subpackage is vendored from the same commit: the MA
JSON-RPC API client (models, Ktor WebSocket transport, `MaCommandClient`,
`MaAuthHelper`), trimmed to the library search/shelves/queue command surface
(no players/groups/favorites, playlist editing, podcasts/audiobooks, browse
folders, or WebRTC/proxy; `SearchResults` drops those result lists). Hearth
drives it through `MaLibrary` with `isRemoteMode` hard-wired `false` (LOCAL
path only) and authenticates with the MA token the config page's sign-in
stores in the web config (`sendspin.maToken`).

## Device / hardware notes

Primary targets: **Echo Show 5** (LineageOS 18.1 / Android 11, MT8163, 960×480)
and **Lenovo Tab M9** (Android 13, 1340×800). Landscape kiosk only.

- **Echo audio HAL is fragile.** Prime the `AudioTrack` buffer *before* `play()`
  (an empty start renders silent); pad short one-shots with ≥300 ms trailing
  silence (bare chirps get destroyed unplayed). **Never run
  `dumpsys media.audio_flinger`** — it crashes the audio HAL.
- **Echo `screencap` can't read the Compose/hardware layer** — it returns a
  stale window-background buffer. Verify on-device UI via the tablet (its
  screencap works) or by inspecting the window-background frame.
- `res/font/nunito_variable.ttf` is the single variable font (weights via the
  `wght` axis). The **`melspectrogram.tflite` wake-word asset is pre-patched**
  (`tools/patch-melspec-shape.py`) — never replace it with a raw upstream copy.

## Branding / splash / icon

The Hearth mark is a dark rounded tile (`#12141C`) with an off-white masonry
fireplace (`#DCE0EA`) and an ember→gold gradient flame. Assets: `docs/logo.png`,
`ic_splash_lockup`, and the adaptive launcher icon
(`mipmap-anydpi-v26/ic_launcher` → `ic_launcher_background` + `ic_launcher_foreground`).

- The "Hearth" wordmark is **Nunito SemiBold baked to vector path outlines**
  (not live text) so it renders before Compose starts. If you regenerate it,
  extract outlines from `res/font/nunito_variable.ttf` instanced to `wght=600`.
- The splash is **version-split**: API 30 uses the legacy `windowBackground`
  (`splash_background` → `ic_splash_lockup`, wordmark baked in); **API 31+ runs
  the system SplashScreen and ignores `windowBackground`**, so the wordmark is
  supplied via `windowSplashScreenBrandingImage` in `res/values-v31/themes.xml`
  (`ic_wordmark`). Change both if you change the splash.
- The adaptive launcher icon's frame is **inset within the 72 dp safe zone** so
  no launcher mask (circle/squircle/rounded-square) clips it — it can't sit
  edge-to-edge like the splash tile.
