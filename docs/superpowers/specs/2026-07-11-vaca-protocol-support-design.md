# VACA Protocol Support — Design

**Date:** 2026-07-11
**Target device:** Amazon Echo Show 5 running LineageOS 18.1 (Android 11), 960×480 landscape
**Goal:** Echo Dashboard speaks the VACA (View Assist Companion App) device protocol so the existing VACA HACS integration exposes HA-side controls and sensors for the device — while keeping our fast native rendering instead of VACA's WebView.

## Background & decisions

- The user runs the [VACA HA integration](https://github.com/msp1974/ViewAssist_Companion_App) ecosystem; the official VACA Android app renders HA dashboards in a WebView and is too slow on the Echo. We keep our native Compose dashboard and implement the device side of VACA's protocol.
- **Verified from integration source** (`custom_components/vaca`, v0.12.1): a mic-less, display-only device works. HA is the TCP *client*; the device runs a Wyoming TCP server advertised via mDNS (`_vaca._tcp.local.`). Audio flows only when the device initiates a voice pipeline or HA sends an announcement. Entity creation is gated by a device-reported capabilities dict.
- **Scope chosen:** display controls + TTS announcements + media player. Voice satellite (wake word, mic streaming) deferred to a future project.
- **mobile_app registration dropped:** the VACA device entry supersedes it. `RegistrationClient` is removed from the setup flow (class and tests may be deleted). OAuth login and the authenticated WebSocket for dashboard data (temperature) are unchanged. The user deletes the old mobile_app device entry in HA once.

## Protocol summary (as implemented by the integration)

- **Transport:** Wyoming event framing over TCP — a JSON header line (`type`, optional inline `data`, optional `data_length`/`payload_length`), then optional JSON data bytes, then optional binary payload bytes. Exact framing to be pinned against the `wyoming` Python library (≥1.7.1) during planning.
- **Discovery:** zeroconf `_vaca._tcp.local.`; HA's config flow also allows manual host:port. We listen on **port 10700**.
- **Connections:** HA first probes with a short-lived connection (`describe` → expects `info`; then bare `capabilities` event → expects `capabilities` reply). The satellite entity then holds a persistent connection (same handshake, then `run-satellite`), reconnecting every 10 s on drop. Concurrent/sequential connections must each be handled independently.
- **After `run-satellite`:** HA pushes a `settings` custom event containing `integration_version`, `min_required_apk_version`, `ha_url`, `ha_port`, `ha_dashboard`, `custom_files`, plus all entity-backed settings. We apply what we support and ignore the rest (browser/dashboard settings are meaningless to a native renderer).
- **HA → device:** custom events `settings` (`{"settings": {key: value}}`) and `action` (`{"action": name, "payload": ...}`). Actions: `screen-sleep`, `screen-wake`, `wake`, `refresh`, `toast-message`, `play-media`, `play`, `pause`, `stop`, `set-volume`. (`update-custom-files` ignored.)
- **Device → HA:** custom events `settings` (feedback to sync entity state; an empty `settings` event from the device asks HA to re-send all settings), `status` (sensor values), plus `capabilities`/`info` re-sends when they change.
- **Announcements:** HA streams PCM 22 050 Hz / 16-bit / mono via `audio-start`/`audio-chunk`/`audio-stop`; device plays it and confirms with a `played` event (exact event name pinned during planning).

## Capabilities we declare

```json
{
  "app_version": "<our version string>",
  "has_battery": false,
  "has_front_camera": false,
  "has_dnd": false,
  "sensors": [{"type": 5}],
  "audio": {"max_music_volume": <from AudioManager>, "max_notification_volume": <from AudioManager>}
}
```

`sensors: [{type: 5}]` declares the Echo's ambient light sensor (Android `TYPE_LIGHT`), giving HA an illuminance sensor entity; we stream readings via `status`. If the device lacks a light sensor at runtime, omit it. No accelerometer/proximity declared (types 1/8) even if present — bump/proximity features are out of scope.

Resulting HA entities that map to real behavior: screen switch, auto-brightness, always-on, screensaver, screen timeout, brightness, dark mode, wake + refresh buttons, media player, volumes, light sensor, app version. Voice-centric entities the integration creates unconditionally (pipeline/wake-word selects, mic gain, mute, STT/TTS sensors, assist_satellite) exist but are inert — accepted as harmless.

## Architecture — new `vaca/` package

Six units:

1. **`WyomingCodec`** — encode/decode Wyoming events over `InputStream`/`OutputStream`. Pure Kotlin (kotlinx-serialization), no Android deps.
2. **`VacaServer`** — `ServerSocket(10700)`, one coroutine per accepted connection. Implements the handshake (`describe`→info, `capabilities`→capabilities, `run-satellite` acknowledgment of session start), then routes `settings`/`action`/audio events to registered handlers. Exposes a listener interface consumed by `KioskController`, `AnnouncePlayer`, `MediaBridge`; provides `sendSettingsFeedback(map)`, `sendStatus(map)`. Owns no Android APIs — pure JVM, testable with real sockets.
3. **`NsdAdvertiser`** — `NsdManager` registration of service `Echo Dashboard`, type `_vaca._tcp.`, port 10700. Registers on app start, re-registers on failure with backoff. Thin, untested wrapper.
4. **`KioskController`** — applies settings/actions to the device and reports feedback:

   | Settings key / action | Native behavior |
   |---|---|
   | `screen_on` / `screen-wake` / `wake` | Remove black overlay, restore brightness, `FLAG_TURN_SCREEN_ON` path |
   | `screen-sleep` (action) / `screen_on: false` | Full black overlay + window brightness floor (a normal app cannot power the panel off) |
   | `screen_brightness` (range per the integration's number entity; pinned during planning) | Window `screenBrightness` attribute |
   | `screen_auto_brightness` | Adjust window brightness from light-sensor readings |
   | `screen_always_on` | Our existing `FLAG_KEEP_SCREEN_ON` |
   | `screen_timeout` | Sleep screen (overlay) after N s of no interaction when not always-on |
   | `screen_saver` | Dim overlay (partial alpha) |
   | `dark_mode` | Dims the dashboard palette (our theme is already dark; maps to a dimmer variant) |
   | `toast-message` (action) | Transient on-screen message overlay |
   | `refresh` (action) | Reconnect HA WebSocket / re-fetch state |
   | voice/wake/mic/zoom/text-size keys | Ignored (logged at debug) |

   Feedback: after applying (or ignoring) a setting, current supported-settings map is sent back so HA entities stay in sync.
5. **`AnnouncePlayer`** — `AudioTrack` (22 050/16/mono, `USAGE_ASSISTANT`); plays the announce stream, always sends `played` on completion *or* error so HA's announce call never hangs; ducks `MediaBridge` volume while playing.
6. **`MediaBridge`** — Media3 ExoPlayer. Handles `play-media` (URL), `play`, `pause`, `stop`, `set-volume`; reports playback state changes via `status` events (exact keys pinned during planning from `media_player.py`). Failures report `idle` state, never crash.

**Persistence:** last-applied VACA settings stored via `SettingsStore` (new keys) and re-applied on app start, so a reboot restores HA-configured state before HA reconnects.

**Integration with existing app:** `AppDeps` constructs and starts `VacaServer` + `NsdAdvertiser` at app start regardless of auth/dashboard state. `KioskController` bridges to `MainActivity`'s window via a small interface to keep the server Android-free. Setup flow no longer calls `RegistrationClient`.

## Lifecycle

Boot → app start → dashboard as today + VACA server listening + mDNS advertised → (one-time) user adds VACA integration in HA via auto-discovery → HA connects, handshakes, entities appear → steady-state settings/actions/status flow. HA reconnects transparently after network blips or app restarts.

## Error handling

| Condition | Behavior |
|---|---|
| Malformed/unknown event | Log, drop event, keep connection |
| Connection drop / probe connections | Per-connection isolation; server keeps accepting |
| Port in use | Retry bind with backoff; logcat only |
| NSD registration failure | Retry with backoff; manual host:port still works in HA |
| Announce stream interrupted | Stop AudioTrack, still send `played` |
| Media URL unplayable | Report idle state to HA, no crash |
| Settings arrive with no visible Activity | Apply non-window settings immediately; queue window ops for next resume |
| `min_required_apk_version` from HA | Log at warn if we ever choose to compare; no enforcement |

## Testing

Plain-JVM JUnit4 only (no Robolectric), as in the MVP:

- `WyomingCodec`: framing round-trips — header-only, inline data, `data_length`, `payload_length`, combined; truncated-input errors.
- `VacaServer`: real `ServerSocket` + fake HA client — probe handshake, persistent handshake, settings/action dispatch to a recording listener, feedback/status sending, reconnect after drop, malformed-event survival.
- `KioskController`: mapping logic against a fake device interface — every table row above, ignored-keys behavior, feedback contents.
- `AnnouncePlayer`/`MediaBridge`: state-machine logic behind interfaces; `AudioTrack`/ExoPlayer/NSD edges stay thin and untested.
- **Planning-phase research task:** pin exact wire details — Wyoming framing bytes, `played` event name, ping/pong keepalive (if any) in HA core's Wyoming satellite loop, `run-satellite` timing, media status keys — against the `wyoming` Python library, HA core source, and `custom_components/vaca`; packet-capture the real VACA app against the user's HA if anything remains ambiguous. The plan must contain exact payloads.

Manual verification: add VACA integration in HA → entities appear; flip screen/brightness/screensaver from HA; `assist_satellite.announce` plays through the Echo speaker; play a radio URL via the media player; light sensor tracks room lighting; reboot → settings restored and HA reconnects.

## Out of scope (this phase)

Voice satellite (wake word, mic streaming, pipelines), gesture events, camera/motion detection, DND, alarms, bump/proximity wake, `update-custom-files`, View Assist dashboard/browser settings (`ha_dashboard`, `ha_url` ignored), zoom/text-size settings, version enforcement.

## References

- VACA integration source (read 2026-07-11, v0.12.1): `custom_components/vaca/` — `data.py` (probe handshake), `assist_satellite.py` (session, settings push, announce), `custom.py` (custom event types & actions), `devices.py` (capability gates), platform files (entity/settings keys).
- MVP spec: `docs/superpowers/specs/2026-07-10-echo-ha-dashboard-design.md`
