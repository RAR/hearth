# Cameras Panel + Doorbell Popup — Design

**Date:** 2026-07-12
**Status:** Approved by user (brainstorming session)

## Goal

Show live camera feeds on the Echo Dashboard: a new **Cameras panel** for on-demand
viewing of any configured camera, and a **doorbell popup** that overlays the live
doorbell feed over whatever view is active when someone presses the doorbell.

## User's environment (facts verified from the device's entity registry)

- Reolink doorbells at front and back door. Press signal:
  `binary_sensor.front_door_visitor`, `binary_sensor.back_door_visitor`.
  Camera entities: `camera.front_door_fluent` / `_clear` (sub / main stream),
  `camera.back_door_fluent` / `_clear`.
- Frigate with additional cameras: `camera.front_door_bell`, `camera.office_door_bell`,
  `camera.lower_deck`, `camera.lower_garage_driveway`, `camera.lower_garage_interior`,
  `camera.side_yard`, `camera.upper_driveway`, `camera.upper_garage_interior`,
  `camera.back_driveway`. Frigate exposes RTSP restreams at
  `rtsp://<frigate-host>:8554/<camera_name>`.
- Also present: `camera.p1s_01p00c591200536_camera` (Bambu printer) — must work via
  the HLS fallback with zero extra config.
- Display is 960×480. Device is weak (MT8163); hardware H.264 decode is required for
  smooth video — no software per-frame decode paths.

## Streaming approach (decision: RTSP direct + HLS fallback)

Per camera, in order:

1. **RTSP** — if the camera config has an `rtspUrl`, play it directly with ExoPlayer's
   RTSP module. Sub-second latency, hardware decode, bypasses HA entirely. Intended for
   Frigate/go2rtc restreams on the LAN.
2. **HLS via HA** — otherwise (or when RTSP errors at play time), send
   `{"type":"camera/stream","entity_id":"<camera entity>"}` over the existing
   authenticated WebSocket. HA returns `{"url":"/api/hls/<token>/master_playlist.m3u8"}` —
   a signed relative path appended to the HA base URL, fetched with **no** Authorization
   header (same pattern as photo downloads). 5–10 s latency; acceptable for panel
   browsing and as a safety net.
3. **Error overlay** — if both fail, show the camera name + "stream unavailable" on the
   dusk-gradient background. A doorbell popup still appears in this state (the user must
   know someone rang even when video is broken).

New dependencies (both at the pinned media3 version **1.4.1** — do not bump; 1.5.x needs
compileSdk 35 which this project must not use):

- `androidx.media3:media3-exoplayer-rtsp:1.4.1`
- `androidx.media3:media3-exoplayer-hls:1.4.1`

Rejected alternatives: HLS-only (latency makes the doorbell popup show the past);
MJPEG proxy (software JPEG decode per frame on a weak CPU, transcode load on HA).

## Config model (`DashConfig`)

New fields, all backward/forward compatible via the existing kotlinx JSON settings
(`ignoreUnknownKeys` + `encodeDefaults`), all normalized in `clamped()`:

```kotlin
@Serializable
data class CameraConfig(
    val name: String = "",          // display name, e.g. "Front Door"
    val entity: String? = null,     // camera.* entity for HLS fallback / picker identity
    val rtspUrl: String? = null,    // optional direct stream, e.g. rtsp://frigate:8554/front_door_bell
)

@Serializable
data class DoorbellConfig(
    val trigger: String? = null,    // binary_sensor.*/event.* that fires on press
    val camera: String = "",        // CameraConfig.name of the feed to show
)
```

- `Entities` gains `cameras: List<CameraConfig> = emptyList()` and
  `doorbells: List<DoorbellConfig> = emptyList()`.
- `PanelOptions` gains `doorbellPopupSeconds: Int = 30`, clamped **5–120**.
- `Panels` gains `cameras: PanelToggle` (enabled = shown in the rail), default **disabled**
  so existing installs are unchanged until configured.
- `clamped()`: trim names/entities/urls; drop `CameraConfig` entries that have neither
  `entity` nor `rtspUrl` or a blank name; blank `entity`/`rtspUrl` → null; drop
  `DoorbellConfig` entries with a blank trigger or a camera name that matches no
  configured camera.
- `referencedEntityIds()` adds every camera `entity` and every doorbell `trigger`
  (distinct, existing ordering rules) — trigger states then arrive over the existing
  `subscribe_entities` subscription with no new WS machinery. Camera entities are
  included so the panel can show availability.

A camera is valid with only an `rtspUrl` (no HA entity) — e.g. a raw go2rtc stream that
HA doesn't know about.

## Stream resolution (`StreamResolver`, plain logic, JVM-testable)

```kotlin
sealed interface StreamSource {
    data class Rtsp(val url: String) : StreamSource
    data class Hls(val url: String) : StreamSource   // absolute URL, already signed
    object Unavailable : StreamSource
}
```

- `primary(camera): StreamSource` — Rtsp if `rtspUrl != null`, else Hls placeholder
  resolved by requesting `camera/stream` (suspend), else Unavailable.
- `fallback(camera, failed: StreamSource): StreamSource` — after an Rtsp failure, try
  Hls if `entity != null`; after an Hls failure, Unavailable. No retry loops: one
  fallback step per playback attempt, then the error overlay with a "Retry" tap target.
- HLS URL construction: `baseUrl.trimEnd('/') + relativePath` (mirror
  `AndroidPhotoDownloader`). A `camera/stream` request failure (or missing `url` key)
  maps to Unavailable/fallback — never a crash.

## Player (`CameraPlayer` composable, Android-only, not unit tested)

- Wraps ExoPlayer in an `AndroidView` (`PlayerView`, `useController = false`,
  `RESIZE_MODE_FIT` letterboxed on the dusk gradient).
- Built with both RTSP and HLS media-source factories. One instance at a time; created
  when a stream is shown, `release()`d in `onDispose` and on stream switch.
- Exposes `muted: Boolean` (volume 0/1) and an `onError` callback that drives the
  resolver's fallback step.
- Keep the composable thin: all decisions (which URL, mute state, fallback) live in
  JVM-testable logic; the composable just plays what it is told.

## Cameras panel (`CamerasPanel`, new `DashView.CAMERAS`)

- Rail gains a camera icon (`Icons.Outlined.Videocam`), position after MEDIA; shown only
  when `panels.cameras.enabled` and at least one camera is configured.
- Layout: fixed-width (~200 dp) selector column on the left listing camera display
  names (selected row highlighted, same styling family as the Lights panel); the live
  feed fills the remaining area. First camera auto-selected on entry.
- Muted by default; a speaker toggle icon overlays the feed's corner (per user choice:
  audio is popup-only by default, tap-to-unmute in the panel).
- Selecting another camera tears down the current player before starting the next.
- Leaving the panel (rail navigation, idle-return to Home) releases the player — no
  background streaming.
- Camera whose entity state is `unavailable` still attempts RTSP if configured
  (Frigate may be up while HA's camera entity is not); otherwise shows the error overlay.

## Doorbell popup (`DoorbellCoordinator` + overlay in `DashboardShell`)

- **Rising-edge detection:** the coordinator observes watched entity states. A popup
  fires only on an observed transition `off → on` (or event entity state change) of a
  configured trigger. The first state seen for an entity after (re)subscribe is recorded
  but never fires — no phantom popups at app start or reconnect. JVM-testable pure
  state machine: `fun onStates(states: Map<String, EntityState>, nowMs: Long): PopupCommand?`.
- **Popup state:** `data class DoorbellPopup(val cameraName: String, val untilMs: Long)`.
  On trigger: show the mapped camera full-screen above everything (including the icon
  rail), label with the camera display name, countdown until `now + doorbellPopupSeconds`.
  - Same trigger fires again while showing → extend `untilMs` (reset the countdown).
  - A *different* doorbell fires while showing → switch the popup to that camera and
    reset the countdown.
  - Tap anywhere on the popup → dismiss immediately.
  - Timer expiry → dismiss.
- **Audio:** unmuted in the popup (user choice), always.
- **Screen wake:** while a popup is visible the app forces the screen on via the
  existing screen-control plumbing (same mechanism VACA's screen command uses), so a
  ring wakes a blanked device; the previous screen state resumes after dismissal.
- Popup counts as activity for the idle-return timer (it should not race the popup).
- If the popup's stream fails entirely, the overlay still shows: camera name,
  "stream unavailable", countdown — the ring notification itself must never be lost.

## Web config page (`app.js`)

- **Cameras** card (list editor modeled on the light-groups editor): rows of
  [display name] [camera entity picker, domain `camera`] [RTSP URL text input] [remove];
  an "Add camera" button. Hint text: "RTSP plays direct from Frigate/go2rtc
  (rtsp://host:8554/name) for sub-second latency; leave blank to stream through
  Home Assistant (HLS, ~5–10 s behind). Tip: prefer sub/fluent streams — the screen is
  960×480."
- **Doorbells** card: rows of [trigger entity picker, domains `binary_sensor`,`event`]
  [camera dropdown listing configured camera names] [remove]; "Add doorbell" button.
- **Panel options**: "Doorbell popup (s)" number input (5–120, clamped on save).
- **Panels**: Cameras toggle joins the existing enable/order list.

## Error handling summary

| Failure | Behavior |
|---|---|
| RTSP connect/play error | One automatic fallback to HLS (if entity set), else error overlay |
| `camera/stream` request fails / no `url` | Error overlay (popup still shows) |
| HLS play error | Error overlay with Retry tap target |
| Trigger entity unavailable/missing | No popup (nothing to detect); panel unaffected |
| WS disconnected when doorbell pressed | No popup (no state updates arrive) — accepted limitation |

## Testing (plain-JVM JUnit4 only, per repo rule)

- `DashConfigTest` additions: camera/doorbell round-trip, clamp rules (drop invalid
  entries, popup seconds 5–120), `referencedEntityIds()` includes camera entities and
  triggers.
- `StreamResolverTest`: RTSP-first choice, HLS fallback ordering, Unavailable terminal,
  HLS URL construction from base + relative path, malformed `camera/stream` responses.
- `DoorbellCoordinatorTest`: first-state-never-fires, off→on fires, on→on/off→off
  don't, re-trigger extends, second doorbell switches camera, expiry math, tap dismiss.
- ExoPlayer/PlayerView and the composables are excluded from JVM tests (Android
  classes); verified on-device.

## Out of scope (YAGNI)

- Two-way audio / talk-back to the doorbell.
- Recording, snapshots, event history / Frigate event browsing.
- WebRTC.
- Multi-camera grid view (one stream at a time — device constraint).
- Chime/sound effect on ring (the Reolink chime already handles audible alerts).

## Build/toolchain constraints (unchanged, binding)

- Kotlin 2.1.0, compileSdk 34 (never bump), media3 pinned 1.4.1, NanoHTTPD 2.3.1.
- Build: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`.
