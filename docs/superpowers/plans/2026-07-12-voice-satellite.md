# HA Voice Satellite (Wyoming) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Echo Dashboard into a first-class Home Assistant Assist voice satellite. The app runs a second Wyoming TCP server (port 10600, advertised `_wyoming._tcp.`) that HA connects into. It streams mic audio continuously; HA does VAD, wake word, STT, intent and TTS. A small bottom-center overlay shows "Listening…", the recognized transcript, and the spoken response.

**Architecture:** A pure-JVM protocol state machine (`SatelliteSession`) is driven by decoded `WyomingEvent`s plus lifecycle signals (connected/disconnected/mic-chunk/playback-finished/clock-tick/alert-dismiss) and returns a list of `SatelliteAction`s (events to send, mic start/stop, playback commands, voice-overlay updates, timer-UI updates). It also owns device-local timer state (countdown chips + "Timer done" alert) that survives HA disconnects. A thin coroutine TCP server (`SatelliteServer`, VacaServer pattern, newest-connection-wins) owns one long-lived session, reads frames, and dispatches actions. Android-only edges — `MicStreamer` (AudioRecord), `TimerChime` (generated-tone AudioTrack), and the `VoiceOverlay`/`TimerChips`/`TimerFinishedOverlay` composables — carry no logic. Reuse the existing `WyomingCodec`/`WyomingEvent`, `AndroidPcmSink`/`AnnouncePlayer`/`PcmSink`, and `NsdAdvertiser` (parameterized). Wiring lives in `AppDeps`, started reactively on `config.voice.enabled`.

**Tech Stack:** Kotlin 2.1.0, compileSdk 34, kotlinx-serialization-json, kotlinx-coroutines, Jetpack Compose (media3 1.4.1 & NanoHTTPD 2.3.1 untouched). JUnit4 plain-JVM tests only. No new Gradle dependencies.

## Global Constraints

- Fixed TCP port **10600**; advertised service type **`_wyoming._tcp.`**; newest-connection-wins.
- Satellite name fixed **"Echo Dashboard"**. `info` advertises **no** local asr/tts/handle/intent/wake/mic/snd services; only a `satellite` block with `installed=true`. HA does VAD/wake/STT/intent/TTS.
- Mic: AudioRecord source **VOICE_RECOGNITION, 16000 Hz / 16-bit / mono, ~30 ms chunks (960 bytes)**. Runs only while voice enabled AND connection active AND streaming requested; released otherwise.
- Config: new top-level `voice: VoiceSettings = VoiceSettings()`, `@Serializable data class VoiceSettings(val enabled: Boolean = false)`. Nothing to clamp. Default OFF.
- Server starts/stops **reactively** on `config.voice.enabled` (collect the ConfigStore StateFlow) — no app restart.
- `RECORD_AUDIO`: manifest permission + MainActivity runtime request when voice enabled and permission missing. Missing/denied permission → satellite stays connectable, emits a Wyoming `error` event, streams nothing, never crashes.
- Overlay: bottom-center pill; hidden → "Listening…" (on `detection`, wakes screen) → transcript text (on `transcript`) → response text (on `synthesize`, during TTS) → fades **~4 s** after playback ends. Appearing counts as user activity (`deps.kiosk.onUserInteraction()` + `idleTimer.onInteraction()`, same as the doorbell popup). Renders above panels, below the doorbell popup.
- TTS replies play through a dedicated `AndroidPcmSink` via an `AnnouncePlayer` (same mixing/ducking behavior as VACA announcements); `played` is emitted after playback finishes.
- **Timers (device-local countdown):** HA routes timer intents to the satellite via Wyoming `timer-started`/`timer-updated`/`timer-cancelled`/`timer-finished` events. Session holds timer state; multiple concurrent timers; countdown math against an injected clock. Timers **survive HA disconnects** (device-local) but **not app restart** (accepted). On-screen stacked countdown chips; on `timer-finished`, a full-attention "Timer done" overlay that wakes the screen (same kiosk/idle wiring as `detection`), a locally **generated** chime (AudioTrack tone — no bundled asset, no new dependency), tap-to-dismiss, auto-silence after **60 s**. No `info` flag required (see Protocol facts).
- Kotlin 2.1.0, compileSdk 34 (never bump), media3 pinned 1.4.1, NanoHTTPD 2.3.1. **No new Gradle dependencies.**
- Build gate per task: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` (always `./gradlew`, never system gradle).
- Kotlin hazard: never place a literal end-of-block-comment sequence inside a KDoc/block comment.

## Protocol facts (verified)

Verified 2026-07-12 against `rhasspy/wyoming` (`master`), `rhasspy/wyoming-satellite` (`master`), and `home-assistant/core` (`dev`, `homeassistant/components/wyoming/`). Sources govern; spec deviations are called out.

### Event shapes (type + data fields; audio bytes ride the binary payload)

- **describe** — `{"type":"describe"}`, no data. Sent by HA (both the discovery probe and the runtime connection).
- **info** — `{"type":"info","data":{...}}`. Data has the seven service-list keys and an optional `satellite`:
  ```json
  {"asr":[],"tts":[],"handle":[],"intent":[],"wake":[],"mic":[],"snd":[],
   "satellite":{"name":"Echo Dashboard",
                "attribution":{"name":"Echo Dashboard","url":"https://github.com/rar/echo-dashboard"},
                "installed":true,
                "description":"Home Assistant voice satellite",
                "version":"<appVersion>",
                "area":null,
                "has_vad":false,
                "active_wake_words":[],
                "max_active_wake_words":0,
                "supports_trigger":false}}
  ```
  `attribution` is a nested `{"name","url"}` object. **There is NO `snd_format` field** on the Wyoming `Satellite` info dataclass (fields are name, attribution, installed, description, version, area, has_vad, active_wake_words, max_active_wake_words, supports_trigger). TTS audio format is carried by the `audio-start` event instead (see below). This matches the existing `VacaOutgoing.info()` shape.
- **run-satellite** — `{"type":"run-satellite"}`, no data. HA→satellite: "server ready to run a pipeline."
- **pause-satellite** — `{"type":"pause-satellite"}`, no data. HA→satellite: "server not ready" (also sent when the HA device is muted and on teardown).
- **run-pipeline** — satellite→HA: `{"type":"run-pipeline","data":{"start_stage":"wake","end_stage":"tts","restart_on_end":true}}`. `PipelineStage` serializes to the lowercase strings `"wake"`,`"asr"`,`"intent"`,`"handle"`,`"tts"`. For an always-streaming satellite with no local wake and no local VAD: `start_stage="wake"`, `end_stage="tts"` (we play TTS), `restart_on_end = not vad.enabled = true`. Optional fields (`wake_word_name`, `wake_word_names`, `announce_text`) omitted.
- **audio-chunk** — `{"type":"audio-chunk","data":{"rate":16000,"width":2,"channels":1,"timestamp":<ms>},"payload":<pcm s16le bytes>}`. `timestamp` is **optional** (milliseconds). Mic audio is sent as bare `audio-chunk` events continuously — see deviation #3.
- **audio-start** — `{"type":"audio-start","data":{"rate":<hz>,"width":<bytes>,"channels":<n>,"timestamp":<ms?>}}`. HA→satellite before a TTS stream; carries the PCM format the satellite must play.
- **audio-stop** — `{"type":"audio-stop","data":{"timestamp":<ms?>}}`. HA→satellite end of TTS stream.
- **detection** — HA→satellite: `{"type":"detection","data":{"name":<model>,"timestamp":<ms>,"speaker":<str?>}}`. Wake word heard.
- **transcript** — HA→satellite: `{"type":"transcript","data":{"text":<str>,"language":<str?>,"context":<obj?>}}`. STT result.
- **synthesize** — HA→satellite: `{"type":"synthesize","data":{"text":<str>,"voice":<obj?>}}`. Response text about to be spoken (precedes the TTS audio stream).
- **played** — satellite→HA: `{"type":"played"}`, no data. Reply after TTS playback completes.
- **ping** — HA→satellite: `{"type":"ping","data":{"text":<str?>}}`. **pong** — satellite→HA: `{"type":"pong","data":{"text":<same text, may be null>}}`. `text` optional; copy request→response.
- **error** — satellite→HA: `{"type":"error","data":{"text":<str>}}`; optional `"code"`. Used for the mic-unavailable path.
- **timer-started** — HA→satellite: `{"type":"timer-started","data":{"id":<str>,"total_seconds":<int>,"name":<str?>,"start_hours":<int?>,"start_minutes":<int?>,"start_seconds":<int?>}}`. `total_seconds` here is the **initial/total** duration (not remaining). We use `id`, `total_seconds`, `name`.
- **timer-updated** — HA→satellite: `{"type":"timer-updated","data":{"id":<str>,"is_active":<bool>,"total_seconds":<int>}}`. Here `total_seconds` is the **seconds remaining** at the moment of the update, and `is_active` is the running/paused state. Pause = `is_active:false`; resume / add-time / remove-time = a `timer-updated` with the new `is_active` + `total_seconds`. (Verified from HA `assist_satellite._handle_timer`: `TimerUpdated(id=timer.id, is_active=timer.is_active, total_seconds=timer.seconds)`.)
- **timer-cancelled** — HA→satellite: `{"type":"timer-cancelled","data":{"id":<str>}}`.
- **timer-finished** — HA→satellite: `{"type":"timer-finished","data":{"id":<str>}}`. Note: carries **only `id`** — the satellite must remember the timer's name from `timer-started` to label the "Timer done" alert.
- **streaming-started** / **streaming-stopped** — `{"type":"streaming-started"}` / `{"type":"streaming-stopped"}`, no data. Informational; reference `AlwaysStreamingSatellite` emits streaming-started on run-satellite. HA does not require them (unknown/irrelevant events are ignored) — we emit them for parity, harmless.

### Ordering & HA-side behavior (verified)

- **HA connects OUTBOUND** to the satellite host:port (`AsyncTcpClient`). Same direction as VACA (HA is the client).
- **Zeroconf:** HA's `wyoming` manifest lists `zeroconf: ["_wyoming._tcp.local."]`, `iot_class: local_push`. HA **auto-discovers** any `_wyoming._tcp.` service, connects, sends `describe`, reads `info`, and classifies it as a satellite when `info.satellite is not None AND info.satellite.installed is True`. Manual host:port entry (`CONF_HOST`/`CONF_PORT`) is also supported and aborts `cannot_connect` if `info` can't be loaded. Config-flow aborts `no_services` if the discovered `info` exposes nothing usable. → Our empty service lists + `satellite.installed=true` classify correctly.
- **Runtime connection sequence (HA `WyomingAssistSatellite`):** after connecting HA sends **`run-satellite` FIRST, then `describe`**, then begins its ping loop. So on the persistent connection the satellite can receive `run-satellite` before `describe`. Our session handles both in any order (describe → reply info regardless of streaming state).
- **HA does not send `run-pipeline`** — the **satellite** sends `run-pipeline` (we send it on `run-satellite`). With `restart_on_end=true` HA keeps the pipeline (wake stage) alive/restarting server-side, so we send `run-pipeline` exactly once per `run-satellite` and stream mic continuously.
- **Ping/pong:** HA constants `_PING_SEND_DELAY = 2 s`, `_PING_TIMEOUT = 5 s`. HA pings roughly every ~2 s after each response and disconnects if no `pong` within 5 s. → `pong` must be written promptly; never block the reader thread behind blocking playback (playback is offloaded to `AnnouncePlayer`, same rule as VACA).
- **Reconnect:** HA `_RECONNECT_SECONDS = 10` (retry after `ConnectionError`), `_RESTART_SECONDS = 3` (retry after other errors). → On disconnect our server keeps listening; HA reconnects ~every 10 s.
- **PauseSatellite:** stops streaming, no pipeline-stop event. HA sends it on mute/teardown. Design: `pause-satellite` → stop mic; streaming resumes on the next `run-satellite` (spec-confirmed).
- **TTS from HA:** HA streams `audio-start` → `audio-chunk`+ (2048-byte chunks) → `audio-stop`, then waits for `played`. `AndroidPcmSink.start(rate,width,channels)` already adapts to the `audio-start` format.
- **Incoming `audio-chunk` on the satellite connection is always TTS** (mic chunks flow the opposite direction), so it is unambiguously routed to playback.
- **Timer routing (verified).** HA's `WyomingAssistSatellite.run()` calls `intent.async_register_timer_handler(self.hass, self.device.device_id, self._handle_timer)` **unconditionally** for every connected satellite — there is **no `info` flag** (no `supports_timers` / no timer field on the Wyoming `Satellite` info dataclass). When an Assist timer intent targets this satellite's device/area, HA forwards `timer-started`/`timer-updated`/`timer-cancelled`/`timer-finished` to the connection. `_handle_timer` drops the event if the satellite is disconnected. → **Our satellite needs zero `info` changes for timers**; it only has to handle the incoming timer events. The handler is unregistered on final teardown.

### UNVERIFIED / flag for runtime confirmation

- **U1 — Unmute re-arm.** HA sends `pause-satellite` on mute and (per source) `run-satellite` once on connect. Whether HA re-sends `run-satellite` on **unmute** was not positively confirmed in `assist_satellite.py`. Our design already treats any `run-satellite` as "resume streaming + send run-pipeline", so this is safe either way. Implementer: confirm on-device that unmuting the HA satellite entity resumes mic streaming.
- **U2 — audio-chunk `timestamp` necessity.** `timestamp` is optional in the schema; the reference satellite includes a running ms timestamp. We include one for parity. If HA ever rejects chunks, dropping `timestamp` is the first thing to try. Confirm mic audio reaches HA STT on-device.

### Spec deviations forced by sources

1. **Handshake order.** Spec step 1 says "HA sends describe; satellite replies info … [then] HA sends run-satellite." Source: on the runtime connection HA sends **run-satellite first, then describe**. Session is order-independent and answers `describe` with `info` at any time.
2. **Satellite drives run-pipeline.** Spec phrases it as "satellite replies run-pipeline"; confirmed the satellite *initiates* `run-pipeline` on `run-satellite` (HA never sends it). `restart_on_end=true` keeps it continuous — sent once per run-satellite.
3. **No audio-format framing before mic audio.** Spec step 2 says mic audio is "preceded by the appropriate audio-format metadata." Source: `AlwaysStreamingSatellite` sends **bare `audio-chunk` events** (each carrying rate/width/channels/timestamp) with **no** `audio-start`/`audio-stop` wrapper for the mic stream. We do the same.
4. **No `snd_format` in info.** The plan-header question "snd_format?" resolves to: not present on the Wyoming `Satellite` info dataclass. TTS format comes from `audio-start`.
5. **Timers need no `info` declaration.** Spec ("Timers" section) says "The satellite's `info` block declares timer support so HA routes timer intents to the device." Source: HA registers the timer handler **unconditionally** per connected satellite (`intent.async_register_timer_handler` in `run()`), with no `info` flag and no timer field on the `Satellite` dataclass. Routing is by the satellite's HA device/area, not by an advertised capability. → We add **no** timer field to `info`; we only handle the incoming timer events. (`total_seconds` semantics also differ between `timer-started` (total) and `timer-updated` (remaining) — handled in the session.)

---

## Task 1 — DashConfig `voice` settings

**Files**
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt` (add `VoiceSettings` data class near the other `@Serializable` models ~line 10; add `voice` field to `DashConfig` ~line 89).
- Test: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` (append cases).

**Interfaces**
- Produces: `data class VoiceSettings(val enabled: Boolean = false)` and `DashConfig.voice: VoiceSettings`.
- `clamped()` is unchanged: it rebuilds via `copy(...)`, which preserves `voice` automatically (nothing to clamp).

Steps:

- [ ] Write failing tests — append to `DashConfigTest.kt`:
  ```kotlin
  @Test
  fun voiceDefaultsOff() {
      assertEquals(false, DashConfig().voice.enabled)
      // absent from JSON -> default off, unknown-key tolerant
      val cfg = decodeConfig("""{"version":1}""")
      assertEquals(false, cfg.voice.enabled)
  }

  @Test
  fun voiceRoundTrips() {
      val cfg = DashConfig(voice = VoiceSettings(enabled = true))
      val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
      assertEquals(cfg, decodeConfig(text))
      assertEquals(true, decodeConfig(text).voice.enabled)
  }

  @Test
  fun voiceSurvivesClamped() {
      assertEquals(true, DashConfig(voice = VoiceSettings(enabled = true)).clamped().voice.enabled)
  }
  ```
- [ ] Run to see it fail — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`. Expected: compile error / unresolved reference `VoiceSettings` and `voice`.
- [ ] Minimal implementation — in `DashConfig.kt`, add above `DashConfig` (e.g. after `HomeSettings`/`PanelOptions`):
  ```kotlin
  @Serializable
  data class VoiceSettings(val enabled: Boolean = false)
  ```
  and add the field to `DashConfig`:
  ```kotlin
  @Serializable
  data class DashConfig(
      val version: Int = 1,
      val panels: Panels = Panels(),
      val entities: Entities = Entities(),
      val home: HomeSettings = HomeSettings(),
      val panelOptions: PanelOptions = PanelOptions(),
      val voice: VoiceSettings = VoiceSettings(),
  ) {
  ```
  (Leave `clamped()` untouched — `copy(...)` carries `voice` through.)
- [ ] Run to pass — same `--tests` command; all `DashConfigTest` green.
- [ ] Full gate — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`.
- [ ] Commit — `git add app/src/main/java/com/rar/echodash/config/DashConfig.kt app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` then `git commit -m "feat(config): add voice satellite settings (default off)"`.

---

## Task 2 — `SatelliteSession` protocol state machine (voice + timers)

The core. Pure JVM, no Android imports, fully unit-tested. Reuses `com.rar.echodash.vaca.WyomingEvent`. Owns both the voice-interaction state and the device-local timer state.

**Files**
- Create: `app/src/main/java/com/rar/echodash/voice/VoiceOverlayState.kt`
- Create: `app/src/main/java/com/rar/echodash/voice/TimerUi.kt`
- Create: `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt`
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt`

**Interfaces**
- Produces:
  - `enum class VoiceOverlayPhase { HIDDEN, LISTENING, TRANSCRIPT, RESPONSE }`
  - `data class VoiceOverlayState(val phase: VoiceOverlayPhase = VoiceOverlayPhase.HIDDEN, val text: String = "")`
  - `data class TimerChip(val id: String, val name: String, val remainingSec: Long, val active: Boolean)`
  - `data class TimerAlert(val label: String)`
  - `data class TimersUiState(val chips: List<TimerChip> = emptyList(), val alert: TimerAlert? = null)`
  - `sealed interface SatelliteAction` with `Send(WyomingEvent)`, `StartMic`, `StopMic`, `PlaybackStart(rate,width,channels)`, `PlaybackChunk(pcm)`, `PlaybackStop`, `Overlay(state)`, `Timers(state)`.
  - `class SatelliteSession(appVersion: String)` with:
    - `fun onConnected(): List<SatelliteAction>`
    - `fun onDisconnected(): List<SatelliteAction>` (does **not** clear timers)
    - `fun onEvent(event: WyomingEvent, nowMs: Long = 0L): List<SatelliteAction>` (nowMs anchors timers; default 0L keeps non-timer callers terse)
    - `fun onMicChunk(pcm: ByteArray): List<SatelliteAction>`
    - `fun onMicError(): List<SatelliteAction>`
    - `fun onPlaybackFinished(nowMs: Long): List<SatelliteAction>`
    - `fun onTimerAlertDismissed(nowMs: Long): List<SatelliteAction>`
    - `fun onTick(nowMs: Long): List<SatelliteAction>` (voice overlay auto-dismiss + live timer countdown + 60 s alert auto-silence)
    - `val overlay: VoiceOverlayState` (current snapshot)
- Consumes: `WyomingEvent` (from `vaca`), kotlinx-serialization-json builders.
- Constants: `AUDIO_RATE=16000`, `AUDIO_WIDTH=2`, `AUDIO_CHANNELS=1`, `DISMISS_MS=4000L`, `ALERT_SILENCE_MS=60000L`.
- Timer semantics: on `timer-started`, anchor `remaining=total_seconds` at `nowMs`, `active=true`. On `timer-updated`, re-anchor `remaining=total_seconds` at `nowMs`, `active=is_active`. Displayed remaining = `if active: max(0, anchorRemaining - (now-anchorMs)/1000) else anchorRemaining`. On `timer-cancelled`, drop the chip. On `timer-finished`, drop the chip and raise a `TimerAlert(name or "Timer")` auto-silenced at `now+60 s`.

Steps:

- [ ] Write failing test — create `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt`:
  ```kotlin
  package com.rar.echodash.voice

  import com.rar.echodash.vaca.WyomingEvent
  import kotlinx.serialization.json.Json
  import kotlinx.serialization.json.JsonObject
  import kotlinx.serialization.json.boolean
  import kotlinx.serialization.json.buildJsonObject
  import kotlinx.serialization.json.int
  import kotlinx.serialization.json.jsonObject
  import kotlinx.serialization.json.jsonPrimitive
  import kotlinx.serialization.json.put
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class SatelliteSessionTest {

      private fun session() = SatelliteSession(appVersion = "9.9")
      private fun event(type: String, json: String? = null, payload: ByteArray = ByteArray(0)) =
          WyomingEvent(type, json?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap()), payload)
      private inline fun <reified T> List<SatelliteAction>.only(): T {
          assertEquals("expected exactly one action, got $this", 1, size)
          return first() as T
      }
      private fun sends(a: List<SatelliteAction>) = a.filterIsInstance<SatelliteAction.Send>().map { it.event }

      @Test
      fun describeRepliesInfoWithInstalledSatellite() {
          val info = sends(session().onEvent(event("describe"))).single()
          assertEquals("info", info.type)
          val sat = info.data["satellite"]!!.jsonObject
          assertEquals("Echo Dashboard", sat["name"]!!.jsonPrimitive.content)
          assertEquals(true, sat["installed"]!!.jsonPrimitive.boolean)
          assertEquals("9.9", sat["version"]!!.jsonPrimitive.content)
          // no local services advertised
          for (k in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) {
              assertTrue(info.data[k]!!.jsonArray().isEmpty())
          }
      }

      private fun kotlinx.serialization.json.JsonElement.jsonArray() =
          (this as kotlinx.serialization.json.JsonArray)

      @Test
      fun runSatelliteStartsMicAndSendsRunPipeline() {
          val a = session().onEvent(event("run-satellite"))
          assertTrue(a.contains(SatelliteAction.StartMic))
          val types = sends(a).map { it.type }
          assertTrue(types.contains("run-pipeline"))
          assertTrue(types.contains("streaming-started"))
          val rp = sends(a).first { it.type == "run-pipeline" }
          assertEquals("wake", rp.data["start_stage"]!!.jsonPrimitive.content)
          assertEquals("tts", rp.data["end_stage"]!!.jsonPrimitive.content)
          assertEquals(true, rp.data["restart_on_end"]!!.jsonPrimitive.boolean)
      }

      @Test
      fun pingRepliesPongCopyingText() {
          val pong = sends(session().onEvent(event("ping", """{"text":"k7"}"""))).single()
          assertEquals("pong", pong.type)
          assertEquals("k7", pong.data["text"]!!.jsonPrimitive.content)
      }

      @Test
      fun detectionTranscriptSynthesizeDriveOverlay() {
          val s = session()
          s.onEvent(event("run-satellite"))
          assertEquals(VoiceOverlayState(VoiceOverlayPhase.LISTENING),
              (s.onEvent(event("detection", """{"name":"ok_nabu"}""")).last() as SatelliteAction.Overlay).state)
          assertEquals(VoiceOverlayState(VoiceOverlayPhase.TRANSCRIPT, "turn on the light"),
              (s.onEvent(event("transcript", """{"text":"turn on the light"}""")).last() as SatelliteAction.Overlay).state)
          assertEquals(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, "Okay"),
              (s.onEvent(event("synthesize", """{"text":"Okay"}""")).last() as SatelliteAction.Overlay).state)
      }

      @Test
      fun ttsAudioRoutesToPlaybackAndPlayedAfterFinish() {
          val s = session()
          s.onEvent(event("run-satellite"))
          assertEquals(SatelliteAction.PlaybackStart(22050, 2, 1),
              s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}""")).only())
          val chunk = s.onEvent(event("audio-chunk", """{"rate":22050,"width":2,"channels":1}""", ByteArray(8) { 1 }))
              .only<SatelliteAction.PlaybackChunk>()
          assertArrayEquals(ByteArray(8) { 1 }, chunk.pcm)
          assertEquals(SatelliteAction.PlaybackStop, s.onEvent(event("audio-stop")).only())
          // played is emitted only after playback actually finishes
          val played = sends(s.onPlaybackFinished(nowMs = 1_000)).single()
          assertEquals("played", played.type)
      }

      @Test
      fun overlayAutoDismissesFourSecondsAfterPlayback() {
          val s = session()
          s.onEvent(event("run-satellite"))
          s.onEvent(event("synthesize", """{"text":"Done"}"""))
          s.onPlaybackFinished(nowMs = 10_000)
          assertEquals(VoiceOverlayPhase.RESPONSE, s.overlay.phase)
          assertTrue(s.onTick(nowMs = 13_999).none { it is SatelliteAction.Overlay }) // before deadline
          assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
              (s.onTick(nowMs = 14_000).single() as SatelliteAction.Overlay).state) // at deadline
          assertEquals(VoiceOverlayPhase.HIDDEN, s.overlay.phase)
      }

      @Test
      fun pauseSatelliteStopsMic() {
          val s = session()
          s.onEvent(event("run-satellite"))
          val a = s.onEvent(event("pause-satellite"))
          assertTrue(a.contains(SatelliteAction.StopMic))
          assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
      }

      @Test
      fun disconnectStopsMicAndHidesOverlay() {
          val s = session()
          s.onEvent(event("run-satellite"))
          s.onEvent(event("detection", """{"name":"x"}"""))
          val a = s.onDisconnected()
          assertTrue(a.contains(SatelliteAction.StopMic))
          assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
              (a.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
      }

      @Test
      fun micChunkEmitsAudioChunkOnlyWhileStreaming() {
          val s = session()
          assertTrue(s.onMicChunk(ByteArray(960)).isEmpty()) // not streaming yet
          s.onEvent(event("run-satellite"))
          val e = sends(s.onMicChunk(ByteArray(960) { 7 })).single()
          assertEquals("audio-chunk", e.type)
          assertEquals(16000, e.data["rate"]!!.jsonPrimitive.int)
          assertEquals(2, e.data["width"]!!.jsonPrimitive.int)
          assertEquals(1, e.data["channels"]!!.jsonPrimitive.int)
          assertArrayEquals(ByteArray(960) { 7 }, e.payload)
      }

      @Test
      fun micErrorEmitsErrorEvent() {
          val e = sends(session().onMicError()).single()
          assertEquals("error", e.type)
          assertTrue(e.data["text"]!!.jsonPrimitive.content.isNotBlank())
      }

      // ---- timers ----
      private fun timers(a: List<SatelliteAction>) =
          (a.last { it is SatelliteAction.Timers } as SatelliteAction.Timers).state

      @Test
      fun timerStartedAddsChip() {
          val st = timers(session().onEvent(event("timer-started", """{"id":"t1","total_seconds":300,"name":"Pasta"}"""), nowMs = 0))
          assertEquals(1, st.chips.size)
          assertEquals("t1", st.chips[0].id)
          assertEquals("Pasta", st.chips[0].name)
          assertEquals(300L, st.chips[0].remainingSec)
          assertTrue(st.chips[0].active)
      }

      @Test
      fun countdownMathAgainstClock() {
          val s = session()
          s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300}"""), nowMs = 0)
          assertEquals(240L, timers(s.onTick(nowMs = 60_000)).chips[0].remainingSec)
      }

      @Test
      fun pauseFreezesAndResumeReAnchors() {
          val s = session()
          s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300}"""), nowMs = 0)
          s.onEvent(event("timer-updated", """{"id":"t1","is_active":false,"total_seconds":240}"""), nowMs = 60_000)
          val frozen = timers(s.onTick(nowMs = 120_000)).chips[0]
          assertEquals(240L, frozen.remainingSec) // frozen while paused
          assertEquals(false, frozen.active)
          s.onEvent(event("timer-updated", """{"id":"t1","is_active":true,"total_seconds":240}"""), nowMs = 120_000)
          assertEquals(210L, timers(s.onTick(nowMs = 150_000)).chips[0].remainingSec)
      }

      @Test
      fun cancelRemovesChip() {
          val s = session()
          s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300}"""), nowMs = 0)
          assertTrue(timers(s.onEvent(event("timer-cancelled", """{"id":"t1"}"""), nowMs = 1_000)).chips.isEmpty())
      }

      @Test
      fun finishedRaisesAlertRemovesChipAndAutoSilences() {
          val s = session()
          s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300,"name":"Tea"}"""), nowMs = 0)
          val fin = timers(s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 300_000))
          assertTrue(fin.chips.isEmpty())
          assertEquals("Tea", fin.alert!!.label)
          assertEquals("Tea", timers(s.onTick(nowMs = 330_000)).alert!!.label) // still alerting before 60 s
          assertNull(timers(s.onTick(nowMs = 360_000)).alert)                   // auto-silenced at +60 s
      }

      @Test
      fun dismissClearsAlert() {
          val s = session()
          s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10,"name":"X"}"""), nowMs = 0)
          s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
          assertNull(timers(s.onTimerAlertDismissed(nowMs = 11_000)).alert)
      }

      @Test
      fun multipleConcurrentTimers() {
          val s = session()
          s.onEvent(event("timer-started", """{"id":"a","total_seconds":120}"""), nowMs = 0)
          s.onEvent(event("timer-started", """{"id":"b","total_seconds":300,"name":"Eggs"}"""), nowMs = 0)
          assertEquals(2, timers(s.onTick(nowMs = 0)).chips.size)
      }

      @Test
      fun timersSurviveDisconnectAndReconnect() {
          val s = session()
          s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300}"""), nowMs = 0)
          s.onDisconnected()
          assertEquals(240L, timers(s.onTick(nowMs = 60_000)).chips[0].remainingSec)
          s.onConnected()
          assertEquals(210L, timers(s.onTick(nowMs = 90_000)).chips[0].remainingSec)
      }
  }
  ```
  (Uses `assertArrayEquals` / `assertEquals` / `assertNull` from `org.junit.Assert` — add `import org.junit.Assert.assertArrayEquals`.)
- [ ] Run to see it fail — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteSessionTest"`. Expected: unresolved references `SatelliteSession`, `SatelliteAction`, `VoiceOverlayState`.
- [ ] Minimal implementation — create `VoiceOverlayState.kt`:
  ```kotlin
  package com.rar.echodash.voice

  /** UI-facing phase of the bottom-center voice pill. Pure data; read by the Compose overlay. */
  enum class VoiceOverlayPhase { HIDDEN, LISTENING, TRANSCRIPT, RESPONSE }

  data class VoiceOverlayState(
      val phase: VoiceOverlayPhase = VoiceOverlayPhase.HIDDEN,
      val text: String = "",
  )
  ```
  Create `TimerUi.kt` (pure, no Android imports):
  ```kotlin
  package com.rar.echodash.voice

  /** One on-screen countdown chip. [remainingSec] is already resolved against the clock. */
  data class TimerChip(val id: String, val name: String, val remainingSec: Long, val active: Boolean)

  /** Full-attention "Timer done" alert. [label] is the timer name, or "Timer" if unnamed. */
  data class TimerAlert(val label: String)

  data class TimersUiState(val chips: List<TimerChip> = emptyList(), val alert: TimerAlert? = null)
  ```
  Then create `SatelliteSession.kt`:
  ```kotlin
  package com.rar.echodash.voice

  import com.rar.echodash.vaca.WyomingEvent
  import kotlinx.serialization.json.JsonNull
  import kotlinx.serialization.json.JsonPrimitive
  import kotlinx.serialization.json.booleanOrNull
  import kotlinx.serialization.json.buildJsonObject
  import kotlinx.serialization.json.contentOrNull
  import kotlinx.serialization.json.int
  import kotlinx.serialization.json.jsonPrimitive
  import kotlinx.serialization.json.put
  import kotlinx.serialization.json.putJsonArray
  import kotlinx.serialization.json.putJsonObject

  /** Actions the pure session asks the outside world to perform. */
  sealed interface SatelliteAction {
      data class Send(val event: WyomingEvent) : SatelliteAction
      data object StartMic : SatelliteAction
      data object StopMic : SatelliteAction
      data class PlaybackStart(val rate: Int, val width: Int, val channels: Int) : SatelliteAction
      data class PlaybackChunk(val pcm: ByteArray) : SatelliteAction {
          override fun equals(other: Any?) = other is PlaybackChunk && pcm.contentEquals(other.pcm)
          override fun hashCode() = pcm.contentHashCode()
      }
      data object PlaybackStop : SatelliteAction
      data class Overlay(val state: VoiceOverlayState) : SatelliteAction
      data class Timers(val state: TimersUiState) : SatelliteAction
  }

  /**
   * Pure protocol/state machine for the always-streaming Wyoming voice satellite.
   * All decisions live here; the server and UI just obey the returned actions.
   * No Android or coroutine imports so it runs in plain-JVM tests.
   */
  class SatelliteSession(private val appVersion: String) {

      private var streaming = false
      private var micTimestampMs = 0L
      private var dismissAtMs: Long? = null
      var overlay: VoiceOverlayState = VoiceOverlayState()
          private set

      // Timer state persists across connect/disconnect (device-local); reset() never touches it.
      private class TimerRec(
          val id: String,
          val name: String,
          var anchorRemainingSec: Long,
          var anchorMs: Long,
          var active: Boolean,
      )
      private val timers = LinkedHashMap<String, TimerRec>()
      private var alert: TimerAlert? = null
      private var alertSilenceAtMs: Long? = null

      fun onConnected(): List<SatelliteAction> {
          reset()
          return emptyList()
      }

      fun onDisconnected(): List<SatelliteAction> {
          reset()
          return listOf(SatelliteAction.StopMic, overlayAction(VoiceOverlayState()))
      }

      fun onEvent(event: WyomingEvent, nowMs: Long = 0L): List<SatelliteAction> = when (event.type) {
          "describe" -> listOf(SatelliteAction.Send(infoEvent()))
          "ping" -> listOf(SatelliteAction.Send(pongEvent((event.data["text"] as? JsonPrimitive)?.contentOrNull)))
          "run-satellite" -> {
              streaming = true
              micTimestampMs = 0L
              listOf(
                  SatelliteAction.Send(runPipelineEvent()),
                  SatelliteAction.Send(WyomingEvent("streaming-started")),
                  SatelliteAction.StartMic,
              )
          }
          "pause-satellite" -> {
              streaming = false
              listOf(SatelliteAction.StopMic, SatelliteAction.Send(WyomingEvent("streaming-stopped")))
          }
          "detection" -> listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)))
          "transcript" -> listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.TRANSCRIPT, textOf(event))))
          "synthesize" -> listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, textOf(event))))
          "audio-start" -> listOf(
              SatelliteAction.PlaybackStart(
                  rate = event.data["rate"]?.jsonPrimitive?.int ?: 22050,
                  width = event.data["width"]?.jsonPrimitive?.int ?: 2,
                  channels = event.data["channels"]?.jsonPrimitive?.int ?: 1,
              ),
          )
          "audio-chunk" -> listOf(SatelliteAction.PlaybackChunk(event.payload))
          "audio-stop" -> listOf(SatelliteAction.PlaybackStop)
          "timer-started" -> {
              val id = strOf(event, "id")
              timers[id] = TimerRec(
                  id = id,
                  name = strOf(event, "name"),
                  anchorRemainingSec = longOf(event, "total_seconds"),
                  anchorMs = nowMs,
                  active = true,
              )
              listOf(SatelliteAction.Timers(timersState(nowMs)))
          }
          "timer-updated" -> {
              timers[strOf(event, "id")]?.let { rec ->
                  rec.anchorRemainingSec = longOf(event, "total_seconds")
                  rec.anchorMs = nowMs
                  rec.active = boolOf(event, "is_active", true)
              }
              listOf(SatelliteAction.Timers(timersState(nowMs)))
          }
          "timer-cancelled" -> {
              timers.remove(strOf(event, "id"))
              listOf(SatelliteAction.Timers(timersState(nowMs)))
          }
          "timer-finished" -> {
              val rec = timers.remove(strOf(event, "id"))
              alert = TimerAlert(label = rec?.name?.ifBlank { "Timer" } ?: "Timer")
              alertSilenceAtMs = nowMs + ALERT_SILENCE_MS
              listOf(SatelliteAction.Timers(timersState(nowMs)))
          }
          else -> emptyList()
      }

      fun onMicChunk(pcm: ByteArray): List<SatelliteAction> {
          if (!streaming || pcm.isEmpty()) return emptyList()
          val ts = micTimestampMs
          micTimestampMs += pcm.size.toLong() * 1000L / (AUDIO_WIDTH.toLong() * AUDIO_CHANNELS * AUDIO_RATE)
          return listOf(SatelliteAction.Send(audioChunkEvent(pcm, ts)))
      }

      fun onMicError(): List<SatelliteAction> = listOf(
          SatelliteAction.Send(
              WyomingEvent(
                  "error",
                  buildJsonObject {
                      put("text", "microphone unavailable")
                      put("code", "mic_unavailable")
                  },
              ),
          ),
      )

      fun onPlaybackFinished(nowMs: Long): List<SatelliteAction> {
          dismissAtMs = nowMs + DISMISS_MS
          return listOf(SatelliteAction.Send(WyomingEvent("played")))
      }

      fun onTimerAlertDismissed(nowMs: Long): List<SatelliteAction> {
          alert = null
          alertSilenceAtMs = null
          return listOf(SatelliteAction.Timers(timersState(nowMs)))
      }

      fun onTick(nowMs: Long): List<SatelliteAction> {
          val actions = mutableListOf<SatelliteAction>()
          // Voice overlay auto-dismiss (~4 s after playback).
          dismissAtMs?.let { if (nowMs >= it) { dismissAtMs = null; actions += overlayAction(VoiceOverlayState()) } }
          // Timer alert auto-silence after 60 s.
          var timersChanged = false
          alertSilenceAtMs?.let { if (nowMs >= it) { alert = null; alertSilenceAtMs = null; timersChanged = true } }
          // Re-emit live timer state while any timer or alert is present (StateFlow dedups no-ops).
          if (timers.isNotEmpty() || alert != null || timersChanged) {
              actions += SatelliteAction.Timers(timersState(nowMs))
          }
          return actions
      }

      private fun timersState(nowMs: Long) = TimersUiState(
          chips = timers.values.map { TimerChip(it.id, it.name, it.remainingSec(nowMs), it.active) },
          alert = alert,
      )

      private fun TimerRec.remainingSec(nowMs: Long): Long =
          if (active) (anchorRemainingSec - (nowMs - anchorMs) / 1000L).coerceAtLeast(0L) else anchorRemainingSec

      private fun strOf(event: WyomingEvent, key: String): String =
          (event.data[key] as? JsonPrimitive)?.contentOrNull ?: ""

      private fun longOf(event: WyomingEvent, key: String): Long =
          (event.data[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

      private fun boolOf(event: WyomingEvent, key: String, default: Boolean): Boolean =
          (event.data[key] as? JsonPrimitive)?.booleanOrNull ?: default

      private fun reset() {
          streaming = false
          micTimestampMs = 0L
          dismissAtMs = null
          overlay = VoiceOverlayState()
      }

      private fun overlayAction(state: VoiceOverlayState): SatelliteAction.Overlay {
          overlay = state
          return SatelliteAction.Overlay(state)
      }

      private fun textOf(event: WyomingEvent): String =
          (event.data["text"] as? JsonPrimitive)?.contentOrNull ?: ""

      private fun audioChunkEvent(pcm: ByteArray, timestampMs: Long) = WyomingEvent(
          "audio-chunk",
          buildJsonObject {
              put("rate", AUDIO_RATE)
              put("width", AUDIO_WIDTH)
              put("channels", AUDIO_CHANNELS)
              put("timestamp", timestampMs)
          },
          pcm,
      )

      private fun runPipelineEvent() = WyomingEvent(
          "run-pipeline",
          buildJsonObject {
              put("start_stage", "wake")
              put("end_stage", "tts")
              put("restart_on_end", true)
          },
      )

      private fun pongEvent(text: String?) = WyomingEvent(
          "pong",
          buildJsonObject { if (text != null) put("text", text) else put("text", JsonNull) },
      )

      private fun infoEvent(): WyomingEvent {
          val data = buildJsonObject {
              for (key in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) putJsonArray(key) {}
              putJsonObject("satellite") {
                  put("name", SATELLITE_NAME)
                  putJsonObject("attribution") {
                      put("name", SATELLITE_NAME)
                      put("url", "https://github.com/rar/echo-dashboard")
                  }
                  put("installed", true)
                  put("description", "Home Assistant voice satellite")
                  put("version", appVersion)
                  put("area", JsonNull)
                  put("has_vad", false)
                  putJsonArray("active_wake_words") {}
                  put("max_active_wake_words", 0)
                  put("supports_trigger", false)
              }
          }
          return WyomingEvent("info", data)
      }

      companion object {
          const val SATELLITE_NAME = "Echo Dashboard"
          const val AUDIO_RATE = 16000
          const val AUDIO_WIDTH = 2
          const val AUDIO_CHANNELS = 1
          const val DISMISS_MS = 4000L
          const val ALERT_SILENCE_MS = 60000L
      }
  }
  ```
  Note: `reset()` (called from `onConnected`/`onDisconnected`) resets only the voice-interaction fields; `timers`, `alert`, and `alertSilenceAtMs` are deliberately left untouched so device-local timers survive HA disconnects and reconnects.
- [ ] Run to pass — same `--tests` command; all `SatelliteSessionTest` green.
- [ ] Full gate — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`.
- [ ] Commit — `git add app/src/main/java/com/rar/echodash/voice/VoiceOverlayState.kt app/src/main/java/com/rar/echodash/voice/TimerUi.kt app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt` then `git commit -m "feat(voice): Wyoming satellite protocol state machine with local timers"`.

---

## Task 3 — `SatelliteServer` TCP + `NsdAdvertiser` parameterization

Thin coroutine TCP server (VacaServer pattern, newest-connection-wins) that owns one `SatelliteSession` per active connection, reads frames, and dispatches actions: `Send` → write to the active socket; everything else → the `Out` listener. Feeds mic chunks / playback-finished / clock ticks into the session too.

**Files**
- Create: `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt`
- Modify: `app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt` (add a `serviceType` constructor parameter, default `_vaca._tcp.` to keep the VACA call site behavior).
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt`

**Interfaces**
- Produces:
  - `class SatelliteServer(scope: CoroutineScope, port: Int = PORT, appVersion: String, out: Out)` with `companion object { const val PORT = 10600 }`.
  - `interface SatelliteServer.Out { fun onStartMic(); fun onStopMic(); fun onPlaybackStart(rate:Int,width:Int,channels:Int); fun onPlaybackChunk(pcm:ByteArray); fun onPlaybackStop(); fun onOverlay(state: VoiceOverlayState); fun onTimers(state: TimersUiState) }`
  - `fun start()`, `fun stop()`, `val boundPort: Int`.
  - `fun submitMicChunk(pcm: ByteArray)` (called by MicStreamer), `fun reportMicError()`, `fun onPlaybackFinished()` (called by the TTS player's onPlayed), `fun dismissTimerAlert()` (called on the "Timer done" overlay tap).
- Consumes: `SatelliteSession`, `SatelliteAction`, `WyomingCodec`/`WyomingEvent` (from `vaca`).
- Design: the server owns **one** long-lived `SatelliteSession` (created in the constructor with `appVersion`) reused across every connection, so device-local timers persist across disconnect/reconnect. `Connection` no longer holds a session. The tick loop runs whenever the server is started (not gated on an active connection) so timers keep counting down while HA is disconnected. `dispatch` takes a **nullable** connection: `Send` actions are dropped when there is no active socket (timers/overlay never produce network sends), everything else goes to `out`.
- Modified: `NsdAdvertiser(context, port, serviceType = "_vaca._tcp.")`.

Steps:

- [ ] Write failing test — create `app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt`:
  ```kotlin
  package com.rar.echodash.voice

  import com.rar.echodash.vaca.WyomingCodec
  import com.rar.echodash.vaca.WyomingEvent
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.cancel
  import kotlinx.serialization.json.Json
  import kotlinx.serialization.json.jsonObject
  import org.junit.After
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNotNull
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test
  import java.io.InputStream
  import java.io.OutputStream
  import java.net.Socket
  import java.util.concurrent.LinkedBlockingQueue
  import java.util.concurrent.TimeUnit

  class SatelliteServerTest {
      private class RecordingOut : SatelliteServer.Out {
          val calls = LinkedBlockingQueue<Any>()
          override fun onStartMic() { calls.put("start-mic") }
          override fun onStopMic() { calls.put("stop-mic") }
          override fun onPlaybackStart(rate: Int, width: Int, channels: Int) { calls.put("pb-start") }
          override fun onPlaybackChunk(pcm: ByteArray) { calls.put("pb-chunk") }
          override fun onPlaybackStop() { calls.put("pb-stop") }
          override fun onOverlay(state: VoiceOverlayState) { calls.put(state) }
          override fun onTimers(state: TimersUiState) { calls.put(state) }
          fun next(): Any? = calls.poll(5, TimeUnit.SECONDS)
      }
      private class TestClient(port: Int) : AutoCloseable {
          val socket = Socket("127.0.0.1", port)
          val input: InputStream = socket.getInputStream().buffered()
          val output: OutputStream = socket.getOutputStream().buffered()
          fun send(e: WyomingEvent) = WyomingCodec.write(e, output)
          fun read(): WyomingEvent? = WyomingCodec.read(input)
          override fun close() = socket.close()
      }

      private lateinit var scope: CoroutineScope
      private lateinit var out: RecordingOut
      private lateinit var server: SatelliteServer

      @Before fun setUp() {
          scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
          out = RecordingOut()
          server = SatelliteServer(scope, port = 0, appVersion = "0.3", out = out)
          server.start()
          val deadline = System.currentTimeMillis() + 5_000
          while (server.boundPort <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
          assertTrue("server did not bind", server.boundPort > 0)
      }
      @After fun tearDown() { server.stop(); scope.cancel() }

      @Test fun describeRepliesInfo() {
          TestClient(server.boundPort).use { c ->
              c.send(WyomingEvent("describe"))
              val info = c.read()!!
              assertEquals("info", info.type)
              assertEquals(true, info.data["satellite"]!!.jsonObject["installed"]!!.toString().contains("true"))
          }
      }

      @Test fun runSatelliteEmitsRunPipelineAndStartsMic() {
          TestClient(server.boundPort).use { c ->
              c.send(WyomingEvent("run-satellite"))
              val e1 = c.read()!!  // run-pipeline
              assertEquals("run-pipeline", e1.type)
              val e2 = c.read()!!  // streaming-started
              assertEquals("streaming-started", e2.type)
              assertEquals("start-mic", out.next())
          }
      }

      @Test fun pingRepliesPong() {
          TestClient(server.boundPort).use { c ->
              c.send(WyomingEvent("ping", Json.parseToJsonElement("""{"text":"z"}""").jsonObject))
              assertEquals("pong", c.read()!!.type)
          }
      }

      @Test fun micChunkAfterRunSatelliteReachesActiveSocket() {
          TestClient(server.boundPort).use { c ->
              c.send(WyomingEvent("run-satellite"))
              c.read(); c.read()            // run-pipeline, streaming-started
              assertEquals("start-mic", out.next())
              server.submitMicChunk(ByteArray(960) { 3 })
              val chunk = c.read()!!
              assertEquals("audio-chunk", chunk.type)
              assertEquals(960, chunk.payload.size)
          }
      }

      @Test fun disconnectStopsMicAndServerAcceptsNext() {
          val c = TestClient(server.boundPort)
          c.send(WyomingEvent("run-satellite"))
          c.read(); c.read()
          assertEquals("start-mic", out.next())
          c.close()
          // stop-mic arrives after disconnect (overlay-hidden also emitted)
          var sawStop = false
          repeat(4) { if (out.next() == "stop-mic") sawStop = true }
          assertTrue(sawStop)
          TestClient(server.boundPort).use { fresh ->
              fresh.send(WyomingEvent("describe"))
              assertEquals("info", fresh.read()!!.type)
          }
      }

      @Test fun survivesGarbageConnection() {
          Socket("127.0.0.1", server.boundPort).use { g ->
              g.getOutputStream().apply { write("not wyoming\n".toByteArray()); flush() }
          }
          TestClient(server.boundPort).use { c ->
              c.send(WyomingEvent("describe"))
              assertNotNull(c.read())
          }
      }
  }
  ```
- [ ] Run to see it fail — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteServerTest"`. Expected: unresolved reference `SatelliteServer`.
- [ ] Minimal implementation — create `SatelliteServer.kt`:
  ```kotlin
  package com.rar.echodash.voice

  import android.util.Log
  import com.rar.echodash.vaca.WyomingCodec
  import kotlinx.coroutines.CancellationException
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.isActive
  import kotlinx.coroutines.launch
  import java.io.IOException
  import java.io.OutputStream
  import java.net.ServerSocket
  import java.net.Socket

  /**
   * Wyoming TCP server for the voice satellite (port 10600). HA connects inbound.
   * Newest connection wins. Reader runs off the lock; the active [SatelliteSession]
   * and all socket writes are serialized on [lock] so pongs are never starved by
   * blocking playback (playback is offloaded to an AnnouncePlayer via [out]).
   */
  class SatelliteServer(
      private val scope: CoroutineScope,
      private val port: Int = PORT,
      private val appVersion: String,
      private val out: Out,
  ) {
      interface Out {
          fun onStartMic()
          fun onStopMic()
          fun onPlaybackStart(rate: Int, width: Int, channels: Int)
          fun onPlaybackChunk(pcm: ByteArray)
          fun onPlaybackStop()
          fun onOverlay(state: VoiceOverlayState)
          fun onTimers(state: TimersUiState)
      }

      companion object {
          const val PORT = 10600
          private const val TAG = "SatelliteServer"
          private const val BIND_RETRY_MS = 5_000L
          private const val TICK_MS = 500L
      }

      private class Connection(val socket: Socket, val out: OutputStream)

      @Volatile var boundPort: Int = -1
          private set

      // One session for the server's lifetime so device-local timers persist across connections.
      private val session = SatelliteSession(appVersion)
      private val lock = Any()
      @Volatile private var serverSocket: ServerSocket? = null
      private var active: Connection? = null
      private var acceptJob: Job? = null
      private var tickJob: Job? = null

      fun start() {
          if (acceptJob?.isActive == true) return
          acceptJob = scope.launch(Dispatchers.IO) {
              while (isActive) {
                  val server = try {
                      ServerSocket(port)
                  } catch (e: IOException) {
                      Log.w(TAG, "bind failed, retrying", e)
                      delay(BIND_RETRY_MS)
                      continue
                  }
                  serverSocket = server
                  boundPort = server.localPort
                  try {
                      while (isActive) {
                          val socket = server.accept()
                          launch { handle(socket) }
                      }
                  } catch (e: IOException) {
                      if (isActive) Log.w(TAG, "accept loop ended", e)
                  } finally {
                      runCatching { server.close() }
                      serverSocket = null
                      boundPort = -1
                  }
              }
          }
          // Runs regardless of connection state so timers keep counting down while HA is away.
          tickJob = scope.launch {
              while (isActive) {
                  delay(TICK_MS)
                  synchronized(lock) { dispatch(active, session.onTick(System.currentTimeMillis())) }
              }
          }
      }

      fun stop() {
          acceptJob?.cancel(); acceptJob = null
          tickJob?.cancel(); tickJob = null
          runCatching { serverSocket?.close() }
          synchronized(lock) {
              active?.let { runCatching { it.socket.close() } }
              active = null
          }
      }

      /** Feed a mic chunk; resulting audio-chunk is written to the active socket (dropped if none). */
      fun submitMicChunk(pcm: ByteArray) {
          synchronized(lock) {
              val conn = active ?: return
              dispatch(conn, session.onMicChunk(pcm))
          }
      }

      fun reportMicError() {
          synchronized(lock) {
              val conn = active ?: return
              dispatch(conn, session.onMicError())
          }
      }

      fun onPlaybackFinished() {
          synchronized(lock) {
              val conn = active ?: return
              dispatch(conn, session.onPlaybackFinished(System.currentTimeMillis()))
          }
      }

      /** Tap on the "Timer done" overlay: clear the alert (may run with no active connection). */
      fun dismissTimerAlert() {
          synchronized(lock) { dispatch(active, session.onTimerAlertDismissed(System.currentTimeMillis())) }
      }

      private fun handle(socket: Socket) {
          val conn = try {
              Connection(socket, socket.getOutputStream().buffered())
          } catch (e: IOException) {
              runCatching { socket.close() }
              return
          }
          synchronized(lock) {
              active?.let { runCatching { it.socket.close() } }  // newest wins
              active = conn
              dispatch(conn, session.onConnected())
          }
          try {
              val input = socket.getInputStream().buffered()
              while (true) {
                  val event = WyomingCodec.read(input) ?: break
                  synchronized(lock) {
                      if (active !== conn) return           // superseded
                      dispatch(conn, session.onEvent(event, System.currentTimeMillis()))
                  }
              }
          } catch (e: CancellationException) {
              throw e
          } catch (e: Exception) {
              Log.w(TAG, "connection error", e)
          } finally {
              synchronized(lock) {
                  if (active === conn) {
                      dispatch(conn, session.onDisconnected())
                      active = null
                  }
              }
              runCatching { socket.close() }
          }
      }

      /**
       * Must be called while holding [lock]. Writes are small Wyoming frames. [conn] may be null
       * (e.g. a timer tick while HA is disconnected): Send actions are then dropped — timer/overlay
       * actions never produce Sends, so nothing is lost.
       */
      private fun dispatch(conn: Connection?, actions: List<SatelliteAction>) {
          for (a in actions) when (a) {
              is SatelliteAction.Send ->
                  if (conn != null) {
                      try { WyomingCodec.write(a.event, conn.out) } catch (e: Exception) { Log.w(TAG, "write failed", e) }
                  }
              SatelliteAction.StartMic -> out.onStartMic()
              SatelliteAction.StopMic -> out.onStopMic()
              is SatelliteAction.PlaybackStart -> out.onPlaybackStart(a.rate, a.width, a.channels)
              is SatelliteAction.PlaybackChunk -> out.onPlaybackChunk(a.pcm)
              SatelliteAction.PlaybackStop -> out.onPlaybackStop()
              is SatelliteAction.Overlay -> out.onOverlay(a.state)
              is SatelliteAction.Timers -> out.onTimers(a.state)
          }
      }
  }
  ```
- [ ] Parameterize `NsdAdvertiser` — edit `app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt`:
  - Change the constructor to `class NsdAdvertiser(context: Context, private val port: Int, private val serviceType: String = "_vaca._tcp.")`.
  - In `register()`, replace `serviceType = "_vaca._tcp."` with `serviceType = this@NsdAdvertiser.serviceType`.
  (The existing VACA call site `NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT)` keeps working via the default.)
- [ ] Run to pass — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteServerTest"`; also rerun VacaServerTest to confirm no regression from the NsdAdvertiser change (`--tests "com.rar.echodash.vaca.*"`).
- [ ] Full gate — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`.
- [ ] Commit — `git add app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt` then `git commit -m "feat(voice): satellite TCP server + parameterize NsdAdvertiser"`.

---

## Task 4 — `MicStreamer` + RECORD_AUDIO manifest/permission

**Android-only. No JVM test steps** (AudioRecord cannot run in plain JVM — verified on-device). Gate on `assembleDebug` (plus the full test suite stays green).

**Files**
- Create: `app/src/main/java/com/rar/echodash/voice/MicStreamer.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add `RECORD_AUDIO` permission ~line 4, after `RECEIVE_BOOT_COMPLETED`).
- Modify: `app/src/main/java/com/rar/echodash/MainActivity.kt` (runtime request in `onCreate`).

**Interfaces**
- Produces: `class MicStreamer(onChunk: (ByteArray) -> Unit, onError: () -> Unit)` with `fun start()` / `fun stop()`.
- 16000 Hz / 16-bit / mono, VOICE_RECOGNITION, ~30 ms (960-byte) reads. Wired to `SatelliteServer.submitMicChunk` / `reportMicError`.

Steps:

- [ ] (No failing JVM test — Android hardware component.) State explicitly in the task log: MicStreamer wraps `AudioRecord`; excluded from unit tests per repo rule; verified on device.
- [ ] Implementation — create `MicStreamer.kt`:
  ```kotlin
  package com.rar.echodash.voice

  import android.annotation.SuppressLint
  import android.media.AudioFormat
  import android.media.AudioRecord
  import android.media.MediaRecorder
  import android.util.Log
  import kotlin.concurrent.thread

  /**
   * Captures mic audio (16 kHz / 16-bit / mono, VOICE_RECOGNITION) in ~30 ms chunks and
   * pushes each to [onChunk]. Runs on its own thread while [start] is active. Any init or
   * read failure (including a missing RECORD_AUDIO grant surfacing as a failed init) calls
   * [onError] once and stops. Never throws to the caller.
   */
  class MicStreamer(
      private val onChunk: (ByteArray) -> Unit,
      private val onError: () -> Unit,
  ) {
      @Volatile private var running = false
      private var worker: Thread? = null

      @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO is granted; failure -> onError
      @Synchronized
      fun start() {
          if (running) return
          running = true
          worker = thread(name = "MicStreamer", isDaemon = true) {
              val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
              if (minBuf <= 0) { running = false; onError(); return@thread }
              val record = try {
                  AudioRecord(
                      MediaRecorder.AudioSource.VOICE_RECOGNITION,
                      RATE,
                      AudioFormat.CHANNEL_IN_MONO,
                      AudioFormat.ENCODING_PCM_16BIT,
                      maxOf(minBuf, CHUNK_BYTES * 4),
                  )
              } catch (e: Exception) {
                  Log.w(TAG, "AudioRecord init failed", e); running = false; onError(); return@thread
              }
              if (record.state != AudioRecord.STATE_INITIALIZED) {
                  Log.w(TAG, "AudioRecord not initialized (permission?)")
                  runCatching { record.release() }; running = false; onError(); return@thread
              }
              try {
                  record.startRecording()
                  val buf = ByteArray(CHUNK_BYTES)
                  while (running) {
                      val n = record.read(buf, 0, buf.size)
                      if (n <= 0) {
                          if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break
                          continue
                      }
                      onChunk(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                  }
              } catch (e: Exception) {
                  Log.w(TAG, "recording failed", e)
                  if (running) { running = false; onError() }
              } finally {
                  runCatching { record.stop() }
                  runCatching { record.release() }
              }
          }
      }

      @Synchronized
      fun stop() {
          running = false
          worker = null
      }

      private companion object {
          const val TAG = "MicStreamer"
          const val RATE = 16000
          const val CHUNK_BYTES = 960 // 30 ms of 16 kHz s16le mono
      }
  }
  ```
- [ ] Manifest — add after the `RECEIVE_BOOT_COMPLETED` line:
  ```xml
  <uses-permission android:name="android.permission.RECORD_AUDIO" />
  <uses-feature android:name="android.hardware.microphone" android:required="false" />
  ```
- [ ] MainActivity runtime request — in `onCreate`, after `deps = (application as EchoDashApplication).deps` and before `setContent`, add:
  ```kotlin
  if (deps.configStore.config.value.voice.enabled &&
      checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
          android.content.pm.PackageManager.PERMISSION_GRANTED
  ) {
      requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQ)
  }
  ```
  and add a companion constant to `MainActivity`:
  ```kotlin
  private companion object { const val RECORD_AUDIO_REQ = 4201 }
  ```
  (No `onRequestPermissionsResult` override needed — the reactive server collector re-checks and MicStreamer reports `onError` if still ungranted; the grant takes effect on next mic start.)
- [ ] Full gate — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`. Expected: builds; whole existing unit suite still green.
- [ ] Commit — `git add app/src/main/java/com/rar/echodash/voice/MicStreamer.kt app/src/main/AndroidManifest.xml app/src/main/java/com/rar/echodash/MainActivity.kt` then `git commit -m "feat(voice): mic streamer + RECORD_AUDIO permission"`.

---

## Task 5 — `VoiceOverlay` + timer composables

**Android-only (Compose). No JVM test steps** — composables are verified on-device per repo rule. Gate on `assembleDebug`.

**Files**
- Create: `app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt` (voice pill + timer chips + "Timer done" overlay).

**Interfaces**
- Consumes: `VoiceOverlayState`/`VoiceOverlayPhase`, `TimersUiState`/`TimerChip`/`TimerAlert` (from `com.rar.echodash.voice`).
- Produces:
  - `@Composable fun VoiceOverlay(state: VoiceOverlayState, modifier: Modifier = Modifier)` — bottom-center pill; renders nothing when `phase == HIDDEN`. Auto-dismiss timing owned by the session (`onTick`).
  - `@Composable fun TimerChips(state: TimersUiState, modifier: Modifier = Modifier)` — top-center stacked countdown chips; renders nothing when empty. Live seconds come from the session tick; the composable only formats.
  - `@Composable fun TimerFinishedOverlay(alert: TimerAlert, onDismiss: () -> Unit, modifier: Modifier = Modifier)` — full-attention "Timer done" card; tap anywhere to dismiss.

Steps:

- [ ] (No failing JVM test — Compose UI.) Note in the task log: these are presentational; state transitions and countdown math are covered by `SatelliteSessionTest`; visuals verified on device.
- [ ] Implementation — create `VoiceOverlay.kt`:
  ```kotlin
  package com.rar.echodash.ui

  import androidx.compose.foundation.background
  import androidx.compose.foundation.gestures.detectTapGestures
  import androidx.compose.foundation.layout.Box
  import androidx.compose.foundation.layout.Column
  import androidx.compose.foundation.layout.fillMaxSize
  import androidx.compose.foundation.layout.padding
  import androidx.compose.foundation.shape.RoundedCornerShape
  import androidx.compose.material3.Surface
  import androidx.compose.material3.Text
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.graphics.Color
  import androidx.compose.ui.input.pointer.pointerInput
  import androidx.compose.ui.text.style.TextAlign
  import androidx.compose.ui.unit.dp
  import androidx.compose.ui.unit.sp
  import com.rar.echodash.voice.TimerAlert
  import com.rar.echodash.voice.TimersUiState
  import com.rar.echodash.voice.VoiceOverlayPhase
  import com.rar.echodash.voice.VoiceOverlayState

  /**
   * Small bottom-center voice pill. Lighter than the doorbell popup: it does not cover the
   * screen and passes touches through the surrounding area. Renders nothing when hidden.
   */
  @Composable
  fun VoiceOverlay(state: VoiceOverlayState, modifier: Modifier = Modifier) {
      if (state.phase == VoiceOverlayPhase.HIDDEN) return
      val label = when (state.phase) {
          VoiceOverlayPhase.LISTENING -> "Listening…"
          VoiceOverlayPhase.TRANSCRIPT -> state.text.ifBlank { "…" }
          VoiceOverlayPhase.RESPONSE -> state.text.ifBlank { "…" }
          VoiceOverlayPhase.HIDDEN -> ""
      }
      Box(modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
          Surface(shape = RoundedCornerShape(22.dp), color = Color(0xE6101218)) {
              Text(
                  label,
                  color = Color.White,
                  fontSize = 18.sp,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
              )
          }
      }
  }

  private fun formatTimer(sec: Long): String {
      val s = sec.coerceAtLeast(0)
      val h = s / 3600
      val m = (s % 3600) / 60
      val ss = s % 60
      return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
  }

  /** Top-center stack of live countdown chips. Renders nothing when there are no timers. */
  @Composable
  fun TimerChips(state: TimersUiState, modifier: Modifier = Modifier) {
      if (state.chips.isEmpty()) return
      Box(modifier.fillMaxSize().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
              state.chips.forEach { chip ->
                  val text = buildString {
                      if (chip.name.isNotBlank()) append(chip.name).append("  ")
                      append(formatTimer(chip.remainingSec))
                      if (!chip.active) append("  ⏸")
                  }
                  Surface(
                      shape = RoundedCornerShape(18.dp),
                      color = Color(0xE61B1E27),
                      modifier = Modifier.padding(vertical = 4.dp),
                  ) {
                      Text(text, color = Color.White, fontSize = 18.sp,
                          modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                  }
              }
          }
      }
  }

  /** Full-attention "Timer done" overlay. Tap anywhere to dismiss. */
  @Composable
  fun TimerFinishedOverlay(alert: TimerAlert, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
      Box(
          modifier
              .fillMaxSize()
              .background(Color(0xCC000000))
              .pointerInput(Unit) { detectTapGestures { onDismiss() } },
          contentAlignment = Alignment.Center,
      ) {
          Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF2A2340)) {
              Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                  Text("Timer done", color = Color.White, fontSize = 30.sp)
                  if (alert.label.isNotBlank() && alert.label != "Timer") {
                      Text(alert.label, color = Color(0xFFCFC6F0), fontSize = 20.sp,
                          modifier = Modifier.padding(top = 8.dp))
                  }
                  Text("Tap to dismiss", color = Color(0x99FFFFFF), fontSize = 14.sp,
                      modifier = Modifier.padding(top = 16.dp))
              }
          }
      }
  }
  ```
- [ ] Full gate — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`.
- [ ] Commit — `git add app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt` then `git commit -m "feat(voice): bottom-center voice overlay composable"`.

---

## Task 6 — Wiring: `AppDeps` / `EchoDashApplication` / render tree

**Android-only. No JVM test steps** — this is app-lifetime wiring, verified on-device. Gate on `assembleDebug` (full suite stays green).

**Files**
- Create: `app/src/main/java/com/rar/echodash/voice/TimerChime.kt` (Android-only generated-tone chime).
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (AppDeps `// --- VACA ---` block ~lines 127-215 for construction + a `startVoice()`; `EchoDashApp` render tree ~lines 344-351 for the overlays + activity wiring).
- Modify: `app/src/main/java/com/rar/echodash/EchoDashApplication.kt` (call `deps.startVoice()` after `deps.startVaca()`).

**Interfaces**
- Consumes: `SatelliteServer`, `SatelliteServer.Out`, `MicStreamer`, `TimerChime`, `AnnouncePlayer`, `AndroidPcmSink`, `NsdAdvertiser("_wyoming._tcp.")`, `VoiceOverlayState`, `TimersUiState`, `ConfigStore.config` StateFlow.
- Produces on `AppDeps`: `val voiceOverlay: MutableStateFlow<VoiceOverlayState>`, `val timersUi: MutableStateFlow<TimersUiState>`, `val timerChime: TimerChime`, and `fun startVoice()`.

Steps:

- [ ] (No failing JVM test — wiring.) Note: correctness is covered by Task 2/3 unit tests; end-to-end verified on device against HA.
- [ ] Create the chime — `app/src/main/java/com/rar/echodash/voice/TimerChime.kt` (Android-only; generated tone, no bundled asset, no new dependency):
  ```kotlin
  package com.rar.echodash.voice

  import android.media.AudioFormat
  import android.media.AudioManager
  import android.media.AudioTrack
  import android.util.Log
  import kotlin.concurrent.thread
  import kotlin.math.PI
  import kotlin.math.sin

  /**
   * Repeating two-tone "timer done" chime synthesized on the fly and played through AudioTrack
   * on the alarm stream. [start] loops until [stop]; both are idempotent. No bundled audio asset.
   */
  class TimerChime {
      @Volatile private var playing = false
      private var worker: Thread? = null

      @Synchronized
      fun start() {
          if (playing) return
          playing = true
          worker = thread(name = "TimerChime", isDaemon = true) {
              val rate = 22050
              val tone = buildTone(rate)
              val gap = ShortArray(rate) // ~1 s silence between repeats
              val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
              val track = try {
                  @Suppress("DEPRECATION")
                  AudioTrack(
                      AudioManager.STREAM_ALARM, rate, AudioFormat.CHANNEL_OUT_MONO,
                      AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, tone.size * 2), AudioTrack.MODE_STREAM,
                  )
              } catch (e: Exception) {
                  Log.w(TAG, "chime init failed", e); playing = false; return@thread
              }
              try {
                  track.play()
                  while (playing) {
                      var off = 0
                      while (playing && off < tone.size) off += track.write(tone, off, tone.size - off)
                      off = 0
                      while (playing && off < gap.size) off += track.write(gap, off, gap.size - off)
                  }
              } catch (e: Exception) {
                  Log.w(TAG, "chime playback failed", e)
              } finally {
                  runCatching { track.stop() }
                  runCatching { track.release() }
              }
          }
      }

      @Synchronized
      fun stop() {
          playing = false
          worker = null
      }

      private fun buildTone(rate: Int): ShortArray {
          val beep = rate * 200 / 1000 // 200 ms per beep
          val out = ShortArray(beep * 2)
          for (i in 0 until beep) {
              out[i] = (sin(2 * PI * 880.0 * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort()
              out[beep + i] = (sin(2 * PI * 1320.0 * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort()
          }
          return out
      }

      private companion object { const val TAG = "TimerChime" }
  }
  ```
- [ ] Implementation — in `AppDeps`, add imports:
  ```kotlin
  import com.rar.echodash.voice.MicStreamer
  import com.rar.echodash.voice.SatelliteServer
  import com.rar.echodash.voice.TimerChime
  import com.rar.echodash.voice.TimersUiState
  import com.rar.echodash.voice.VoiceOverlayState
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.distinctUntilChanged
  import kotlinx.coroutines.flow.map
  ```
  Then, inside the `// --- VACA ---` region (after `nsd` is declared, before `init {`), add the voice members. Order matters only for readability — the `onPlayed`/`out` lambdas capture `this` and run later, so forward references to `satellite`/`micStreamer` are fine (mirrors how `media`/`announce` reference `vaca`):
  ```kotlin
  // --- Voice satellite (Wyoming) ---
  val voiceOverlay = MutableStateFlow(VoiceOverlayState())
  val timersUi = MutableStateFlow(TimersUiState())
  val timerChime = TimerChime()
  private val voiceSink = AndroidPcmSink()
  private val voicePlayer = AnnouncePlayer(
      scope,
      voiceSink,
      onPlayed = { satellite.onPlaybackFinished() },
      setDucking = { ducked -> mainScope.launch { media.setDucked(ducked) } },
  )
  private val micStreamer = MicStreamer(
      onChunk = { pcm -> satellite.submitMicChunk(pcm) },
      onError = { satellite.reportMicError() },
  )
  val satellite = SatelliteServer(
      scope = scope,
      appVersion = BuildConfig.VERSION_NAME,
      out = object : SatelliteServer.Out {
          override fun onStartMic() = micStreamer.start()
          override fun onStopMic() = micStreamer.stop()
          override fun onPlaybackStart(rate: Int, width: Int, channels: Int) =
              voicePlayer.onAudioStart(rate, width, channels)
          override fun onPlaybackChunk(pcm: ByteArray) = voicePlayer.onAudioChunk(pcm)
          override fun onPlaybackStop() = voicePlayer.onAudioStop()
          override fun onOverlay(state: VoiceOverlayState) { voiceOverlay.value = state }
          override fun onTimers(state: TimersUiState) { timersUi.value = state }
      },
  )
  private val voiceNsd = NsdAdvertiser(appContext, SatelliteServer.PORT, "_wyoming._tcp.")
  ```
  Add the reactive starter method (next to `startVaca()`):
  ```kotlin
  /** Reactively run the voice satellite while config.voice.enabled; no app restart needed. */
  fun startVoice() {
      scope.launch {
          configStore.config
              .map { it.voice.enabled }
              .distinctUntilChanged()
              .collect { enabled ->
                  if (enabled) {
                      satellite.start()
                      voiceNsd.register()
                  } else {
                      voiceNsd.unregister()
                      satellite.stop()
                      micStreamer.stop()
                      timerChime.stop()
                      voiceOverlay.value = VoiceOverlayState()
                      timersUi.value = TimersUiState()
                  }
              }
      }
  }
  ```
- [ ] `EchoDashApplication.onCreate` — after `deps.startVaca()` add `deps.startVoice()`.
- [ ] Render tree — in `EchoDashApp`, `Screen.Dashboard` branch, immediately **BEFORE** the `doorbellPopup?.let { ... }` block (App.kt:344; later children of the enclosing `Box` draw ON TOP, so the voice/timer overlays must be declared first to stay below the doorbell popup) add:
  ```kotlin
  val voiceOverlayState by deps.voiceOverlay.collectAsStateWithLifecycle()
  val timersState by deps.timersUi.collectAsStateWithLifecycle()
  LaunchedEffect(voiceOverlayState.phase) {
      if (voiceOverlayState.phase != com.rar.echodash.voice.VoiceOverlayPhase.HIDDEN) {
          deps.kiosk.onUserInteraction()   // wakes screen + counts as activity
          idleTimer.onInteraction()
      }
  }
  // Timer-finished: hold the screen awake + chime for the alert's whole lifetime; stop the
  // chime when the alert clears (dismiss or auto-silence). One-shot wake is not enough: a
  // screen_timeout shorter than the 60 s alert would blank mid-alert (same fix as the
  // doorbell popup's re-arm loop).
  val alerting = timersState.alert != null
  LaunchedEffect(alerting) {
      if (alerting) {
          deps.timerChime.start()
          while (true) {
              deps.kiosk.onUserInteraction()
              idleTimer.onInteraction()
              kotlinx.coroutines.delay(5_000)
          }
      } else {
          deps.timerChime.stop()
      }
  }
  DisposableEffect(Unit) { onDispose { deps.timerChime.stop() } }
  TimerChips(timersState)
  VoiceOverlay(voiceOverlayState)
  timersState.alert?.let { alert ->
      TimerFinishedOverlay(alert, onDismiss = { deps.satellite.dismissTimerAlert() })
  }
  ```
  Add imports at the top of `App.kt`: `import com.rar.echodash.ui.VoiceOverlay`, `import com.rar.echodash.ui.TimerChips`, `import com.rar.echodash.ui.TimerFinishedOverlay`, and `import com.rar.echodash.voice.VoiceOverlayPhase`. (`DisposableEffect` is already imported in `App.kt`.)
  Rationale for placement: within the enclosing `Box`, LATER children draw ON TOP. `DashboardShell` is declared first, then these voice/timer overlays, then `doorbellPopup?.let` — so the overlays paint above the panels but the doorbell popup, when present, paints over them, matching the spec's "above panels, below the doorbell popup." The timer chips and voice pill are light; the "Timer done" overlay is full-attention within that tier. `KioskOverlays` (screen dim/wake) remains the outermost last sibling and stays on top of everything.
- [ ] Full gate — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`. Expected: builds; existing suite green.
- [ ] Commit — `git add app/src/main/java/com/rar/echodash/voice/TimerChime.kt app/src/main/java/com/rar/echodash/App.kt app/src/main/java/com/rar/echodash/EchoDashApplication.kt` then `git commit -m "feat(voice): wire satellite server, mic, TTS playback, overlays, and timer chime"`.

---

## Task 7 — Web config Voice card (`app.js` + `index.html`)

**No JVM unit test** (single-file vanilla JS asset). Gate on `assembleDebug` and a manual read-back of the served page. The config round-trips through the already-tested `DashConfig.voice`.

**Files**
- Modify: `app/src/main/assets/config/index.html` (add a `voice-section` card after `options-section` ~line 142).
- Modify: `app/src/main/assets/config/app.js` (add `renderVoice()`, call it from `render()` ~line 263).

**Interfaces**
- Consumes/produces: `config.voice.enabled` (boolean). Reuses the existing `labeledRow(...)`, `el(...)`, `save()` idioms; a checkbox mirrors the "Photo slideshow" toggle in `renderHome()`.

Steps:

- [ ] Add the card to `index.html` — after the `options-section` `</section>` and before the closing `</div></main>`:
  ```html
  <section id="voice-section" class="card-section">
    <div class="card-head">
      <span class="ic" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
          <rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0"/><path d="M12 18v3"/>
        </svg>
      </span>
      <div class="card-titles">
        <h2>Voice</h2>
        <p>Turn the dashboard into a Home Assistant voice satellite.</p>
      </div>
    </div>
    <div id="voice"></div>
  </section>
  ```
- [ ] Add `renderVoice()` to `app.js` and call it — extend `render()`:
  ```javascript
  function render() {
    renderPanels();
    renderEntities();
    renderHome();
    renderOptions();
    renderVoice();
  }
  ```
  and add the function (near `renderOptions`):
  ```javascript
  function renderVoice() {
    const host = document.getElementById("voice");
    clear(host);
    if (!config.voice) config.voice = { enabled: false };
    const v = config.voice;
    const toggle = el("input"); toggle.type = "checkbox"; toggle.checked = !!v.enabled;
    toggle.setAttribute("aria-label", "Voice satellite enabled");
    toggle.addEventListener("change", () => v.enabled = toggle.checked);
    host.appendChild(labeledRow("Voice satellite (Wyoming)", toggle));
    host.appendChild(el("div", "muted",
      "Home Assistant should auto-discover the satellite; otherwise add the Wyoming Protocol integration at <this-device-ip>:10600. Pick the pipeline and wake word in HA's Assist satellite settings."));
  }
  ```
  (Hint copy is verbatim from the spec's "Web config page" section.)
- [ ] Full gate — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`.
- [ ] Manual check — on device/emulator, open the config page, confirm the Voice card renders, toggling + Save persists `voice.enabled` (round-trips via `/api/config`), and reload reflects the saved state.
- [ ] Commit — `git add app/src/main/assets/config/index.html app/src/main/assets/config/app.js` then `git commit -m "feat(voice): add Voice card to web config"`.

---

## Self-review

**Spec coverage.** Every spec section maps to a task: Approach/protocol-flow → Protocol facts + Tasks 2/3; `SatelliteServer` → Task 3; `SatelliteSession` → Task 2; `MicStreamer` → Task 4; `VoiceOverlay` → Task 5; second `NsdAdvertiser`/parameterization → Task 3; **Timers** (spec "Timers" section) → session state/handlers/countdown in Task 2, single-session persistence + tick-without-connection + `dismissTimerAlert` in Task 3, chips/finished-overlay composables in Task 5, chime + screen-wake + dismiss wiring in Task 6, timer-routing facts (no `info` flag) in Protocol facts; Permissions → Task 4; Audio interplay (AnnouncePlayer/AndroidPcmSink + ducking) → Task 6; Config model + reactive start/stop → Tasks 1 & 6; Web config card → Task 7; Error-handling table → covered (disconnect stops mic + server keeps listening: Task 3 tests; malformed frame drops connection: `survivesGarbageConnection`; mic permission/error → `onMicError` Task 2/3, MicStreamer `onError` Task 4; mic-mute → silence, no code path needed; voice disabled → server not running/not advertised: Task 6; second connection → newest-wins: Task 3). Testing section (SatelliteSessionTest voice + timer cases, DashConfigTest additions) → Tasks 2 & 1; AudioRecord/AudioTrack/composables/TimerChime excluded → Tasks 4/5/6 explicitly carry no JVM tests. Out-of-scope: alarms and timer-persistence-across-restart are not implemented (session state is in-memory only).

**Placeholder scan.** No TBD/TODO/"appropriate error handling" — every function body is complete; error paths are concrete (`onMicError` sends a real `error` event; MicStreamer `onError`; TimerChime init/playback `try/catch`; server `try/catch` with `Log.w`).

**Type consistency.** `SatelliteSession`, `SatelliteAction` (with `Send`/`StartMic`/`StopMic`/`PlaybackStart`/`PlaybackChunk`/`PlaybackStop`/`Overlay`/`Timers`), `VoiceOverlayState`/`VoiceOverlayPhase`, `TimersUiState`/`TimerChip`/`TimerAlert`, `SatelliteServer(scope, port, appVersion, out)` + `Out` interface (incl. `onTimers`), `MicStreamer(onChunk, onError)`, `TimerChime.start()/stop()`, `NsdAdvertiser(context, port, serviceType)` names/signatures are identical across every task and call site (App.kt wiring, tests). `onEvent(event, nowMs = 0L)` and the `nowMs`-carrying `onTick`/`onPlaybackFinished`/`onTimerAlertDismissed` are used consistently (server passes `System.currentTimeMillis()`; tests pass explicit clocks). `SatelliteServer.PORT = 10600`; audio constants 16000/2/1 shared between `SatelliteSession` and `MicStreamer`; `ALERT_SILENCE_MS = 60000L` in the session matches the spec's 60 s. `config.voice.enabled` used identically in DashConfig, MainActivity, AppDeps, and app.js.
