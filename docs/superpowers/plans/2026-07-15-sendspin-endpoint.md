# SendSpin Playback Endpoint Implementation Plan (Sub-project A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Hearth appear in Music Assistant as a synchronized multi-room SendSpin speaker, by vendoring the MIT SendSpinDroid engine into the app and wiring it to Hearth's config, now-playing, and audio-coordination.

**Architecture:** Vendor the reference engine (protocol + Kalman time-sync + `SyncAudioPlayer`/`AudioTrackSink` + MediaCodec decoders + NSD discovery) into `com.rar.echodash.sendspin`, trimmed to LOCAL-only (no WebRTC/proxy). A new `SendspinEndpoint` owns a `SendSpin` facade + `SyncAudioPlayer` — mirroring the reference `PlaybackService.SendSpinClientCallback` wiring — and is owned by `AppDeps` like `VacaServer`. Hearth glue adds: mutual-exclusion with the existing ExoPlayer `media_player`, announce ducking via `SyncAudioPlayer.setVolume`, and metadata→`NowPlayingStore`.

**Tech Stack:** Kotlin, Ktor WebSocket client (new), kotlinx-coroutines + kotlinx-serialization (existing), platform MediaCodec + AudioTrack (no codec dep). Spec: `docs/superpowers/specs/2026-07-15-sendspin-endpoint-design.md`.

**Reference clone (read-only, the code to vendor):** `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/d000a73b-ee34-49d1-9dfa-da8abd6cc32d/scratchpad/SendSpinDroid/android` (MIT, `com.sendspindroid.*`). If that scratch path is gone at execution time, re-clone: `git clone --depth 1 https://github.com/chrisuthe/SendSpinDroid <scratch>`.

## Global Constraints

- Kotlin 2.1.0, JVM target 17. compileSdk & targetSdk = 34 — NEVER bump. minSdk 28.
- Work directly on `master`.
- App unit tests are plain-JVM JUnit4 only (no instrumented tests, no Robolectric).
- `custom_components/hearth/` is UNTOUCHED (MA talks to the endpoint directly).
- New dependencies ARE permitted (Ktor). No WebRTC, no Noise crypto lib, no codec lib.
- Reused code is MIT (chrisuthe/SendSpinDroid) — add a `NOTICE` file with attribution + record the vendored upstream commit; note it in the README.
- Do not touch the voice/Wyoming-core path.
- Gate (run at the end of EVERY task): `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` (JDK 17+; plain-JVM suite is 454 tests before this work). Every commit's final trailer line: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`.

---

## Note on plan shape (read before starting)

This plan mixes two kinds of task. **Vendor tasks (2–3)** copy ~15k LOC of existing MIT code — steps are exact `cp`/rename/trim/compile-and-fix commands, and verification is "compiles + ported unit tests pass". Do NOT paste vendored code into commits by hand. **Glue tasks (4–7)** are new Hearth code — full TDD with real test + implementation code in the steps.

Package rename rule for all vendored files: `com.sendspindroid` → `com.rar.echodash.sendspin` (so `com.sendspindroid.sendspin.X` → `com.rar.echodash.sendspin.sendspin.X`, etc.; keep the sub-package structure). Do the rename with:
```bash
grep -rl 'com\.sendspindroid' <dest> | xargs sed -i 's/com\.sendspindroid/com.rar.echodash.sendspin/g'
```

## File Structure

- `app/build.gradle.kts` — add Ktor deps (modify).
- `NOTICE` — MIT attribution + vendored upstream commit (create).
- `app/src/main/java/com/rar/echodash/sendspin/…` — vendored engine (create; sub-packages: `sendspin/`, `sendspin/protocol/`, `sendspin/protocol/message/`, `sendspin/protocol/timesync/`, `sendspin/transport/`, `sendspin/latency/`, `sendspin/audio/`, `sendspin/decoder/`, `coordinator/`, `discovery/`, `network/`, `model/`, plus a trimmed `UserSettings`/`AppLog` shim).
- `app/src/main/java/com/rar/echodash/sendspin/SendspinEndpoint.kt` — the Hearth-owned facade wiring (create).
- `app/src/main/java/com/rar/echodash/config/DashConfig.kt` — add `SendspinConfig` (modify).
- `app/src/main/java/com/rar/echodash/media/NowPlayingStore.kt` — add `onSendspin(...)` (modify).
- `app/src/main/java/com/rar/echodash/App.kt` — wire `SendspinEndpoint` into `AppDeps` + arbitration + duck routing (modify).
- `app/src/main/assets/config/app.js` — add `renderSendspin()` (modify).
- `app/src/main/java/com/rar/echodash/web/ConfigServer.kt` — extend `/api/status` with SendSpin status (modify).
- `app/src/test/java/com/rar/echodash/sendspin/…` — ported engine tests + new glue tests (create).
- `README.md` / `AGENTS.md` — attribution + a feature line (modify).

---

## Task 1: Dependencies, package skeleton, attribution

**Files:**
- Modify: `app/build.gradle.kts` (dependencies block)
- Create: `NOTICE`
- Modify: `README.md`

**Interfaces:**
- Produces: the `io.ktor:ktor-client-*` deps and an empty `com.rar.echodash.sendspin` source root available to later tasks.

- [ ] **Step 1: Add Ktor deps.** In `app/build.gradle.kts` `dependencies { }`, after the existing `okhttp` line, add:
```kotlin
implementation("io.ktor:ktor-client-core:3.1.1")
implementation("io.ktor:ktor-client-websockets:3.1.1")
implementation("io.ktor:ktor-client-okhttp:3.1.1")
```
- [ ] **Step 2: Create the `NOTICE` file.** Record attribution + the exact upstream commit (run `git -C <scratch>/SendSpinDroid rev-parse HEAD` and paste it):
```
Hearth bundles a vendored copy of the SendSpin client engine from
chrisuthe/SendSpinDroid (https://github.com/chrisuthe/SendSpinDroid),
MIT License, Copyright (c) 2024-2026 Chris Uthe.
Vendored from upstream commit <HASH>, adapted into com.rar.echodash.sendspin
(local-only transport; UI, MediaSession, WebRTC, and proxy paths removed).
```
- [ ] **Step 3: Add a README line** under the integration/features section noting SendSpin multi-room playback and the vendored NOTICE.
- [ ] **Step 4: Run the gate.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL, 454 tests (Ktor on classpath, nothing uses it yet).
- [ ] **Step 5: Commit.** `git add -A && git commit` — "feat(sendspin): add Ktor deps + vendor NOTICE".

---

## Task 2: Vendor the pure-Kotlin engine (protocol, time-sync, transport, network)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/sendspin/{sendspin,network}/…` from the reference `android/shared/src/commonMain/kotlin/com/sendspindroid/{sendspin,network}/…`
- Create: `app/src/test/java/com/rar/echodash/sendspin/…` (ported tests)

**Interfaces:**
- Produces: `SendspinTimeFilter`, `SyncErrorFilter`, `AdaptiveBufferPolicy`, `SendSpinProtocol`, `MessageParser`/`MessageBuilder`/`BinaryMessageParser`, `TimeSyncManager`, `OutputLatencyEstimator`, `WebSocketTransport`/`SendSpinTransport`/`BaseWebSocketTransport`, `WebSocketUrlBuilder`, `ConnectionSelector` — all in `com.rar.echodash.sendspin.*`.

- [ ] **Step 1: Copy the shared engine.** `mkdir -p app/src/main/java/com/rar/echodash/sendspin` then copy `sendspin/` and `network/` trees from `<scratch>/SendSpinDroid/android/shared/src/commonMain/kotlin/com/sendspindroid/` into it.
- [ ] **Step 2: Rename packages** with the `sed` rule from "Note on plan shape".
- [ ] **Step 3: Resolve KMP `expect`/`actual`.** These files are Kotlin-Multiplatform `commonMain`. For each `expect` declaration, inline the Android `actual` from `shared/src/androidMain` (there are only ~3: `Platform.android.kt`, `Log.android.kt`, `HttpClientFactory.android.kt`) as the single concrete implementation. Delete the `expect`/`actual` keywords.
- [ ] **Step 4: Ktor `HttpClientFactory`.** Keep the OkHttp Ktor engine (`ktor-client-okhttp`) added in Task 1. Confirm the transport uses `HttpClient(OkHttp) { install(WebSockets) }`.
- [ ] **Step 5: Compile-and-fix.** Run the gate; resolve missing imports / dropped-package references iteratively until it compiles. If a file drags in `remote/` or `musicassistant/` types, stub or remove that reference (those packages are dropped — see Task 3 drop list).
- [ ] **Step 6: Port the engine's unit tests.** From `<scratch>/…/shared/src/*Test` and `app/src/test/.../sendspin`, copy the tests for `SendspinTimeFilter` (convergence/offset), `MessageParser`/`MessageBuilder` round-trips, `BinaryMessageParser`, `AdaptiveBufferPolicy`, `WebSocketUrlBuilder`, `ConnectionSelector` into `app/src/test/java/com/rar/echodash/sendspin/…`; rename packages; drop any that need mockk/instrumentation not already on Hearth's test classpath (Hearth uses plain JUnit4 — keep only pure-JVM tests).
- [ ] **Step 7: Run the gate.** Expected: BUILD SUCCESSFUL; the ported tests pass (count rises above 454).
- [ ] **Step 8: Commit** — "feat(sendspin): vendor protocol + time-sync engine".

---

## Task 3: Vendor the Android audio, protocol handler, coordinator, discovery

**Files:**
- Create: `app/src/main/java/com/rar/echodash/sendspin/{sendspin/audio,sendspin/decoder,coordinator,discovery,model}/…` and `sendspin/SyncAudioPlayer.kt`, `sendspin/SendSpin.kt`, `sendspin/protocol/SendSpinProtocolHandler.kt` from the reference `android/app/src/main/java/com/sendspindroid/…`
- Create: `app/src/main/java/com/rar/echodash/sendspin/UserSettings.kt` (trimmed shim)

**Interfaces:**
- Consumes: Task 2 engine types.
- Produces: `SendSpin` facade (`SendSpin(context, deviceName, callback: SendSpin.Callback)`, methods `connectLocal(address, path)`, `play()/pause()/stop()`, `setGroupVolume(Int)`, `setSyncAudioPlayer(SyncAudioPlayer?)`, `disconnect()`, `destroy()`, `connectionState: StateFlow<TransportState>`); `SyncAudioPlayer` (`initialize()`, `start()`, `stop()`, `enterIdle()`, `clearBuffer()`, `matchesFormat(sr,ch,bd)`, `setVolume(Float)`, `setSyncMuted(Boolean)`, `release()`, `setStateCallback(SyncAudioPlayerCallback)`); `SendSpin.Callback` (`onStreamStart(codec,sampleRate,channels,bitDepth,codecHeader)`, `onAudioChunk(serverTimeMicros,audioData)`, `onMetadataUpdate(title,artist,album,artworkUrl,durationMs,positionMs,playbackSpeed)`, `onArtwork(bytes)`, `onArtworkCleared()`, `onStreamClear()`, `onStreamEnd()`, `onStateChanged(state)`, `onGroupUpdate(...)`, `onVolumeChanged(Int)`, `onMutedChanged(Boolean)`, `onSyncMuteChanged(Boolean)`); `NsdDiscoveryManager`.

- [ ] **Step 1: Copy the audio + coordination trees.** Copy `sendspin/SyncAudioPlayer.kt`, `sendspin/SendSpin.kt`, `sendspin/audio/`, `sendspin/decoder/`, `sendspin/protocol/SendSpinProtocolHandler.kt`, `coordinator/`, `discovery/NsdDiscoveryManager.kt`, `network/` (pingers), `model/` from `<scratch>/…/app/src/main/java/com/sendspindroid/` into `com/rar/echodash/sendspin/`. Rename packages (sed rule).
- [ ] **Step 2: Trim `SendSpin.kt` to LOCAL-only.** Remove `connectRemote`/`createRemoteTransport` + all `WebRTCTransport`/`org.webrtc` references, and `connectProxy`/`createProxyTransport`/`ProxyWebSocketTransport` + the `PROXY`/`REMOTE` `ConnectionMode` branches and proxy-auth code in `TransportEventListener`. Keep `ConnectionMode.LOCAL` only. Remove `setProxyFallback`, `getMaApiDataChannel`, `drainMaApiMessageBuffer`, `connectRemote`.
- [ ] **Step 3: Replace `UserSettings`.** Create `com/rar/echodash/sendspin/UserSettings.kt` — a minimal object backing the four accessors the facade uses: `getPlayerId(): String` (persist a random id in Hearth prefs/`filesDir`), `getPreferredCodec(): String` (return `"flac"`), `lowMemoryMode: Boolean = false`, `highPowerMode: Boolean = false`. Repoint facade references to it.
- [ ] **Step 4: Replace `BuildConfig` + logging.** Replace `com.sendspindroid.BuildConfig.VERSION_NAME` with `com.rar.echodash.BuildConfig.VERSION_NAME`. Replace `AppLog.*` calls with `android.util.Log` (or a tiny `AppLog` shim in the package). Delete the `logging/` package if unused.
- [ ] **Step 5: Prune dropped references.** Remove any remaining imports of `ui/`, `musicassistant/`, `remote/`, `playback/`, MediaSession. `discovery/NsdDiscoveryManager` should keep only the server-discovery path.
- [ ] **Step 6: Compile-and-fix.** Run the gate; iterate until it compiles. FLAC/Opus decoders use `android.media.MediaCodec` — confirm no external codec dep crept in.
- [ ] **Step 7: Port audio-side pure-JVM tests** if any exist that don't need Android (`SyncErrorFilter`, buffer/format math). Skip AudioTrack-bound tests (they need the device).
- [ ] **Step 8: Run the gate + commit** — "feat(sendspin): vendor audio pipeline + facade (local-only)".

---

## Task 4: SendspinConfig + web config card

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
- Test: `app/src/test/java/com/rar/echodash/config/SendspinConfigTest.kt`
- Modify: `app/src/main/assets/config/app.js`

**Interfaces:**
- Produces: `DashConfig.sendspin: SendspinConfig` with `enabled: Boolean`, `syncDelayMs: Int`, `serverAddress: String`; consumed by Tasks 5–7 and the web page.

- [ ] **Step 1: Write the failing test** at `app/src/test/java/com/rar/echodash/config/SendspinConfigTest.kt`:
```kotlin
package com.rar.echodash.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SendspinConfigTest {
    @Test fun clampsSyncDelayAndTrimsAddress() {
        val c = SendspinConfig(enabled = true, syncDelayMs = 9000, serverAddress = "  10.0.0.5:8927  ").clamped()
        assertEquals(2000, c.syncDelayMs)     // clamped to +/-2000
        assertEquals("10.0.0.5:8927", c.serverAddress)
    }
    @Test fun blankAddressStaysBlank() {
        assertEquals("", SendspinConfig(serverAddress = "   ").clamped().serverAddress)
    }
    @Test fun dashConfigClampedRunsSendspinClamp() {
        val d = DashConfig(sendspin = SendspinConfig(syncDelayMs = -9000)).clamped()
        assertEquals(-2000, d.sendspin.syncDelayMs)
    }
}
```
- [ ] **Step 2: Run it, expect FAIL** (`SendspinConfig` undefined): `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests '*SendspinConfigTest*'`.
- [ ] **Step 3: Implement.** In `DashConfig.kt`, add (mirroring `MediaSettings`):
```kotlin
@Serializable
data class SendspinConfig(
    val enabled: Boolean = false,
    val syncDelayMs: Int = 0,          // per-player fixed-latency offset for tuning
    val serverAddress: String = "",    // optional manual MA server host:port; blank = mDNS discovery
) {
    fun clamped(): SendspinConfig = copy(
        syncDelayMs = syncDelayMs.coerceIn(-2000, 2000),
        serverAddress = serverAddress.trim(),
    )
}
```
Add `val sendspin: SendspinConfig = SendspinConfig()` to `DashConfig` (after `notifications`), and add `sendspin = sendspin.clamped(),` inside the `copy(...)` returned by `DashConfig.clamped()`.
- [ ] **Step 4: Run the test, expect PASS.**
- [ ] **Step 5: Add the web config card.** In `app/src/main/assets/config/app.js`, add a `renderSendspin()` following the `renderVoice()` pattern: an enable checkbox bound to `config.sendspin.enabled`, a number input for `syncDelayMs`, a text input for `serverAddress` (placeholder `auto (mDNS)`), and a read-only status line element `id="sendspin-status"`. Call `renderSendspin();` inside `render()` next to `renderVoice();`.
- [ ] **Step 6: Run the gate + commit** — "feat(sendspin): config model + web config card".

---

## Task 5: SendspinEndpoint + AppDeps wiring

**Files:**
- Create: `app/src/main/java/com/rar/echodash/sendspin/SendspinEndpoint.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt`

**Interfaces:**
- Consumes: `SendSpin`, `SyncAudioPlayer`, `NsdDiscoveryManager` (Task 3); `DashConfig.sendspin` (Task 4); `MediaEngine`, `NowPlayingStore` (existing).
- Produces: `SendspinEndpoint` with `fun start()`, `fun stop()`, `fun setDuckGain(fraction: Float)`, `val status: StateFlow<SendspinStatus>` (enum/string: Disconnected/Connected/Playing); consumed by Tasks 6–7 and `AppDeps`.

**Reference to mirror:** `<scratch>/…/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — its `SendSpinClientCallback` (`onStreamStart` creates the `SyncAudioPlayer`, `sendSpin.setSyncAudioPlayer(player)`, `player.start()`; `onMetadataUpdate`/`onArtwork` publish metadata; `onStreamClear/End` clears/idles) and `SyncAudioPlayerStateCallback`. Copy that wiring MINUS foreground-service/notification/lock/MediaSession bookkeeping.

- [ ] **Step 1: Write `SendspinEndpoint`.** A plain class (NOT a Service), constructed with `(context: Context, deviceName: () -> String, config: StateFlow<DashConfig>, mediaEngine: MediaEngine, nowPlaying: NowPlayingStore, scope: CoroutineScope)`. It:
  - lazily builds `SendSpin(context, deviceName(), callback)` where `callback` mirrors `SendSpinClientCallback`;
  - in `onStreamStart`, creates/reuses `SyncAudioPlayer(sampleRate, channels, bitDepth, …)`, applies `config.value.sendspin.syncDelayMs` as the static latency offset, calls `sendSpin.setSyncAudioPlayer(player)`, `player.start()`, and (mutual-exclusion) `mediaEngine.pause()`;
  - in `onMetadataUpdate`/`onArtwork`/`onStreamClear`/`onStreamEnd`, forwards to `nowPlaying` (Task 6) and clears the player on stream end;
  - `start()` uses `NsdDiscoveryManager` to find the server (or `config.sendspin.serverAddress` if non-blank) and calls `sendSpin.connectLocal(address, path)`; `stop()` calls `sendSpin.disconnect()`/`destroy()`;
  - `setDuckGain(fraction)` calls `syncAudioPlayer?.setVolume(fraction)`;
  - publishes `status` from `sendSpin.connectionState` + stream state.
- [ ] **Step 2: Wire into `AppDeps`** (`App.kt`, near the `vaca`/`configServer` fields ~:139–:292): add `val sendspin = SendspinEndpoint(appContext, { deviceName() }, configStore.config, mediaEngine, nowPlaying, scope)`, where `mediaEngine` is the `MediaEngine` instance `AppDeps` already constructs for the media_player (an `ExoPlayerEngine`, passed into `MediaBridge` — locate its field name in `App.kt`), and `nowPlaying`/`scope` are the existing `NowPlayingStore` and app coroutine scope. Add `fun startSendspin() { if (configStore.config.value.sendspin.enabled) sendspin.start() }` and call it where `startVaca()`/`startDashboard()` are called. Add a `LaunchedEffect`/collector so toggling `config.sendspin.enabled` starts/stops it (mirror how other config-driven components react).
- [ ] **Step 3: Manual bring-up check (documented, not automated).** Add a note in the task's commit body: flash, enable SendSpin in the config page, confirm Hearth appears in MA's players and audio plays. (No unit test — this is device-bound; the gate only compiles it.)
- [ ] **Step 4: Run the gate + commit** — "feat(sendspin): endpoint wiring into AppDeps".

---

## Task 6: Coordination glue — mutual exclusion, ducking, now-playing

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/media/NowPlayingStore.kt`
- Test: `app/src/test/java/com/rar/echodash/media/NowPlayingSendspinTest.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (duck routing + reverse mutual-exclusion)

**Interfaces:**
- Consumes: `SendspinEndpoint.setDuckGain` (Task 5); the existing announce duck signal + `MediaEngine.onPlayingChanged`.
- Produces: `NowPlayingStore.onSendspin(active, playing, title, artist, album, artworkData, volume)`.

- [ ] **Step 1: Write the failing test** at `app/src/test/java/com/rar/echodash/media/NowPlayingSendspinTest.kt`:
```kotlin
package com.rar.echodash.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSendspinTest {
    @Test fun sendspinMetadataPopulatesState() {
        val store = NowPlayingStore()
        store.onSendspin(active = true, playing = true, title = "Song", artist = "Artist",
            album = "Album", artworkData = byteArrayOf(1, 2, 3), volume = 55)
        val s = store.state.value
        assertTrue(s.active); assertTrue(s.playing)
        assertEquals("Song", s.title); assertEquals("Artist", s.artist)
        assertEquals("Album", s.album); assertEquals(55, s.volume)
    }
    @Test fun inactiveSendspinClears() {
        val store = NowPlayingStore()
        store.onSendspin(true, true, "Song", "Artist", "Album", null, 55)
        store.onSendspin(false, false, null, null, null, null, 55)
        assertEquals(false, store.state.value.active)
    }
}
```
- [ ] **Step 2: Run it, expect FAIL** (`onSendspin` undefined).
- [ ] **Step 3: Implement `onSendspin`** in `NowPlayingStore` (following `onEngine`/`onLocalMeta`, feeding the same private `_state`/`recompute()`), setting `active`, `playing`, `title`, `artist`, `album`, `localArt = artworkData`, `volume`. Reuse the existing `ByteArray` equals/hashCode handling.
- [ ] **Step 4: Run the test, expect PASS.**
- [ ] **Step 5: Route metadata.** In `SendspinEndpoint`'s callback (Task 5), call `nowPlaying.onSendspin(...)` from `onMetadataUpdate` (+ artwork from `onArtwork`, and `onSendspin(active=false,…)` from `onStreamEnd`).
- [ ] **Step 6: Duck routing.** In `AppDeps` (`App.kt`), find where the announce/TTS duck signal reaches the ExoPlayer engine (the `AnnouncePlayer` `setDucking` lambda / `MediaBridge` duck path). Extend that lambda so it ALSO calls `sendspin.setDuckGain(if (ducked) <duckFraction> else 1f)`, where `<duckFraction>` reuses the same fraction MediaBridge applies (`duckingVolume/10f`). Result: TTS ducks SendSpin audio identically.
- [ ] **Step 7: Reverse mutual-exclusion.** Where `MediaEngine.onPlayingChanged(true)` fires for a URL play (or where `MediaBridge` starts a URL), call `sendspin.stop()` so starting the URL `media_player` stops SendSpin (the forward direction — SendSpin pausing ExoPlayer — is already in Task 5 `onStreamStart`).
- [ ] **Step 8: Run the gate + commit** — "feat(sendspin): coordination glue (mutual exclusion, ducking, now-playing)".

---

## Task 7: Status line, on-device bring-up, docs

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/web/ConfigServer.kt` (`/api/status`)
- Modify: `app/src/main/assets/config/app.js` (status line fill)
- Modify: `README.md`, `AGENTS.md`

- [ ] **Step 1: Extend `/api/status`.** In `ConfigServer.handleStatus()`, add a `sendspin` field sourced from `AppDeps.sendspin.status` (Disconnected/Connected/Playing). Thread the endpoint/status into `ConfigServer` the same way `connState` is provided today.
- [ ] **Step 2: Fill the status line** in `app.js` `renderSendspin()` from the `/api/status` `sendspin` field (the page already polls `/api/status`).
- [ ] **Step 3: Docs.** Add a SendSpin line to `README.md` features and an `AGENTS.md` note (the `com.rar.echodash.sendspin` package is vendored MIT engine — see `NOTICE`; local-only; MA connects via mDNS discovery). Confirm the `NOTICE` commit hash from Task 1 is filled in.
- [ ] **Step 4: On-device bring-up checklist** (manual; record results in the commit body):
  - Flash both devices; enable SendSpin in the config page.
  - Hearth appears in Music Assistant's player list; add/pair it if MA prompts.
  - Group it with another speaker; start playback → audio plays; now-playing takeover shows title/artist/art on the home screen.
  - Trigger a TTS announce → SendSpin audio ducks, then restores.
  - Start a URL on the existing media_player → SendSpin stops (mutual exclusion).
  - Note observed sync offset vs. the other speaker (tuning deferred — adjust `syncDelayMs` later if needed).
- [ ] **Step 5: Run the gate + commit** — "feat(sendspin): status endpoint + docs; on-device verified".

---

## Deferred / follow-up (out of scope for this plan)
- Latency tuning to hit tight sync on the Echo Show 5 (MT8163) — tune `syncDelayMs` / `OutputLatencyEstimator` when we get there.
- Background-audio lifecycle across screen-off/night — verify on-device in Task 7; add a foreground service ONLY if playback is killed.
- Optional: reimplement the transport on Hearth's OkHttp WebSocket to drop Ktor.
- Sub-project B: on-screen Music Assistant library browse/search/queue (its own spec).
