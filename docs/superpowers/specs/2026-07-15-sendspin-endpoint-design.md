# SendSpin Playback Endpoint — Design Spec (Sub-project A)

**Goal:** Make Hearth act as a SendSpin playback endpoint so it appears in Music Assistant as a synchronized multi-room speaker, replacing the separate chrisuthe/SendSpinDroid app on these devices.

## Overview

SendSpin (formerly "Resonate") is the Open Home Foundation's sample-accurate multi-room audio protocol, native to Music Assistant (MA). It is currently a technical preview. Hearth is a native Android kiosk app (Kotlin + Jetpack Compose, package `com.rar.echodash`) with its own HA integration under `custom_components/hearth/`. This spec covers the app side only: Hearth advertises itself on the LAN, MA discovers and connects to it, and Hearth plays MA's synchronized audio stream through a vendored copy of the MIT-licensed SendSpinDroid engine.

Sub-project B — on-screen MA library browsing/search/queue — is explicitly out of scope and gets its own spec later.

## Global Constraints

- Kotlin 2.1.0, JVM target 17. compileSdk & targetSdk = 34 — NEVER bump. minSdk 28.
- Work directly on `master`.
- App unit tests are plain-JVM JUnit4 only (no instrumented tests, no Robolectric).
- `custom_components/hearth/` is UNTOUCHED by this work (MA talks to the endpoint directly, not through the HA integration).
- New dependencies ARE now permitted (previously they weren't). Ktor WebSocket client is expected.
- Reused code is MIT-licensed (chrisuthe/SendSpinDroid) — add a `NOTICE` file with attribution and note it in the README.
- Do not touch the voice/Wyoming-core path.

## Scope & Boundaries

- App-side only; the HA integration is not involved. Hearth uses the reference app's **client-initiated** model: it discovers the Music Assistant SendSpin server via mDNS and connects out to it as a WebSocket client (MA's SendSpin server listens on port 8927). Hearth does not advertise itself or host a server. (The protocol also defines a server-in model where an appliance hosts `_sendspin._tcp`/8928 and MA connects in; Hearth does NOT implement that — the vendored transport is a WebSocket client, and adding a server is out of scope.)
- Local transport runs the protocol's plaintext mode. SendSpinDroid ships no crypto library, so the LAN path is unencrypted. This is acceptable: it is the same LAN-only trust posture as Hearth's existing config server (no TLS).
- No WebRTC (remote access), no Noise encryption, no Android Auto, no MediaSession, no HA entities for SendSpin. All out of scope for A.

## Reuse Strategy: Vendor the Engine

Vendor the MIT-licensed SendSpinDroid engine into a new Hearth package `com.rar.echodash.sendspin`, dropping all SendSpinDroid UI and service scaffolding. The engine is ~15k LOC and cleanly separated from UI. Codecs use platform MediaCodec — no codec dependency. The exact file-level vendor boundary is finalized during implementation planning; the lists below define what to bring and what to drop.

The reference clone is a Kotlin Multiplatform project: `android/shared` holds the pure-Kotlin engine, `android/app` holds the Android audio layer and UI.

### Vendor — pure-Kotlin, JVM-testable

From `android/shared/src/commonMain/kotlin/com/sendspindroid/`:

| Source package | Files |
| --- | --- |
| `sendspin/` | `SendspinTimeFilter.kt` (~646 LOC Kalman clock filter), `SyncErrorFilter.kt`, `AdaptiveBufferPolicy.kt` |
| `sendspin/protocol/` | `SendSpinProtocol.kt`; `message/` (`MessageParser`, `MessageBuilder`, `BinaryMessageParser`); `timesync/TimeSyncManager.kt` |
| `sendspin/latency/` | `OutputLatencyEstimator.kt`, `StaticDelaySource.kt` |
| `sendspin/transport/` | `BaseWebSocketTransport.kt`, `WebSocketTransport.kt`, `SendSpinTransport.kt`, `HttpClientFactory.kt` (Ktor-based) |
| `network/` | `WebSocketUrlBuilder.kt`, `ConnectionSelector.kt`, `NetworkState.kt` |

### Vendor — Android audio

From `android/app/src/main/java/com/sendspindroid/`:

| Source package | Files |
| --- | --- |
| `sendspin/` | `SendSpin.kt` (engine facade), `SyncAudioPlayer.kt`; `audio/` (`AudioSink`, `AudioTrackSink`); `decoder/` (`OpusDecoder`, `FlacDecoder`, `MediaCodecDecoder`, `AudioDecoderFactory`); `protocol/SendSpinProtocolHandler.kt` |
| `coordinator/` | `ConnectionCoordinator` plus `TransportState`/`SessionState`/`ReconnectStatus` |
| `discovery/` | `NsdDiscoveryManager.kt` |
| `network/` | pingers |
| `model/` | `SyncStats` etc. |

### Drop

- All `ui/`.
- `playback/PlaybackService` — replaced by Hearth's own lifecycle.
- `musicassistant/` — MA library API; sub-project B only.
- `remote/` — WebRTC.
- MediaSession / Android Auto.

Prune any references from `coordinator`/protocol code to the dropped `musicassistant`/`remote` packages during the port.

## Dependencies

- Add the Ktor WebSocket client: `ktor-client-core`, `ktor-client-websockets`, `ktor-client-okhttp` (3.x).
- Reuse existing `kotlinx-coroutines` and `kotlinx-serialization-json` — Hearth already has both.
- Codecs via platform MediaCodec — no dependency.
- DEFERRED optional simplification (not part of A): reimplement the transport on Hearth's existing OkHttp WebSocket to drop Ktor. Reusing Ktor first keeps the proven engine intact.

## Architecture & Components

- **`SendspinEndpoint`** — a long-lived component started from `AppDeps` (`app/src/main/java/com/rar/echodash/App.kt`), mirroring how `VacaServer` and the config server are owned there. It constructs and owns the vendored chain: coordinator → WebSocket transport → protocol handler → time-sync → `SyncAudioPlayer` → `AudioTrackSink`. Started when enabled in config; stopped when disabled.
- **mDNS discovery** — Hearth discovers the Music Assistant SendSpin server on the LAN using the vendored `NsdDiscoveryManager` (Android NSD). No advertising, and no change to the existing `_hearth._tcp` `NsdAdvertiser` (`app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt`). The SendSpin player name (sent in `client/hello`) = the configured Hearth device name.
- **Connection direction** — client-initiated, confirmed against the reference app: it discovers the server (`discoverServices`, not `registerService`) and connects out as a WebSocket client; it has no embedded server. Hearth reuses this exact model.

## Coordination — the Hearth-Specific New Glue

Everything above is vendored; the pieces below are new and specific to Hearth.

1. **Audio ownership.** SendSpin and the existing ExoPlayer `MediaEngine` (the URL/radio `media_player` entity; see `vaca/MediaBridge.kt`, `vaca/ExoPlayerEngine.kt`) are mutually exclusive — one music source at a time. Starting SendSpin playback pauses/stops the ExoPlayer engine; playing a URL on the media_player stops SendSpin. The existing `media_player` entity remains for radio/URLs and stays the `announce`/TTS path.
2. **Ducking.** Announce/TTS/timer-chime duck the SendSpin `AudioTrackSink` the same way they duck ExoPlayer today — reuse the existing duck signal that flows through `MediaBridge`/`AnnouncePlayer`.
3. **Now-playing.** Map SendSpin metadata and artwork into the existing `NowPlayingStore` (`app/src/main/java/com/rar/echodash/media/NowPlayingStore.kt`) so the home-screen now-playing takeover and album art work identically to local playback. Reuse `ArtFetcher`/`ArtUrl` for artwork.
4. **Lifecycle.** Audio must survive screen-off / night mode. Align with whatever the current media engine already relies on; add a foreground service ONLY if on-device testing shows background audio gets killed. This is a verify-on-device item, not a committed design choice.

## Config

- `DashConfig` (`app/src/main/java/com/rar/echodash/config/DashConfig.kt`) gains a `SendspinConfig`: `enabled: Boolean = false`, `syncDelayMs: Int = 0`, `serverAddress: String = ""` (optional manual MA server `host:port`; blank = mDNS auto-discovery), plus any small buffer/tuning field the engine needs. Persisted like the rest of `DashConfig`; validated/clamped in the same `clamped()` path.
- The web config page (`app/src/main/assets/config/app.js` plus the served page) gains a "SendSpin" card: an enable/disable toggle, a sync-delay (ms) numeric field (for the deferred latency tuning), an optional manual MA server address (`host:port`, fallback when mDNS discovery fails — the reference app offers the same), and a live connection-status line (disconnected / connected / playing).

## Data Flow

Hearth discovers the MA SendSpin server via mDNS → connects out as a WebSocket client → handshake (`client/init` → `server/init` → `client/hello`/`server/activate`) → continuous time-sync (`client/time` ↔ `server/time` fed into `SendspinTimeFilter`; the endpoint reports `available:true` only after the filter converges) → `stream/start` (codec + format: PCM/FLAC/Opus, 16-bit) → binary audio chunks, each carrying a server timestamp → MediaCodec decode → `AdaptiveBufferPolicy` jitter buffer → `SyncAudioPlayer` schedules PCM onto `AudioTrackSink` at the synced local time, soft-correcting drift via frame add/drop or resample → metadata/`group/update` → `NowPlayingStore` → home-screen takeover.

## Error Handling

- Reconnect/backoff via `ConnectionCoordinator`/`ReconnectStatus`; transport drop → rejoin.
- Time filter not converged → stay `available:false`; do not play until synced.
- Decoder error → skip/resync. One-shot resyncs must be rare per the protocol spec: ±1 ms sync floor, ±0.5% speed deviation.

## Testing

- Plain-JVM JUnit4 unit tests (Hearth's style) for the vendored pure-Kotlin logic: message parse/build round-trips, `BinaryMessageParser`, `SendspinTimeFilter` convergence/offset, `SyncErrorFilter`, `AdaptiveBufferPolicy`, `WebSocketUrlBuilder`, `ConnectionSelector`. Port the reference project's relevant unit tests where they fit.
- On-device (manual): the endpoint appears in MA, joins a group, and plays audio; sync is measured against another speaker. Latency TUNING is deferred.

## Risks & Deferred Items

- **Echo Show 5 (MT8163) sample-accurate sync.** Its audio HAL reports output latency poorly and the spec targets ±1 ms. Tunable via the sync-delay offset plus `OutputLatencyEstimator`; the Lenovo Tab M9 (Android 13) should be clean. Tuning is DEFERRED until we get there (explicit user decision).
- **Upstream protocol drift.** SendSpin is a technical preview and may change. Record the exact upstream commit vendored so resyncs are traceable.
- **Code size.** Large addition (~15k LOC vendored). Ktor dependency size — the optional future OkHttp swap is noted under Dependencies.
- **Background-audio lifecycle** across screen-off/night mode — verify on-device.

## Out of Scope

- MA library browse/search/queue UI (sub-project B).
- WebRTC remote access.
- Android Auto.
- MediaSession.
- Noise encryption (local transport stays plaintext).
- HA-integration entities for SendSpin.
