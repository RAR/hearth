# VACA Protocol Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Echo Dashboard speaks the VACA device protocol (Wyoming events over TCP + mDNS) so the existing VACA HACS integration exposes HA-side controls, announcements, and a media player for the device.

**Architecture:** A pure-JVM Wyoming codec + TCP server (`vaca/` package) handles HA's probe and persistent satellite connections; a `KioskController` maps `settings`/`action` events onto the kiosk window/UI; `AnnouncePlayer` plays HA TTS announcement PCM via `AudioTrack`; `MediaBridge` drives ExoPlayer for the HA media_player entity. Android-specific edges (NSD, AudioTrack, ExoPlayer, light sensor, window flags) are thin adapters behind interfaces.

**Tech Stack:** Kotlin 2.1.0, coroutines 1.9.0, kotlinx-serialization-json 1.7.3, java.net sockets, Android NsdManager, AudioTrack, androidx.media3-exoplayer 1.4.1, JUnit4 + kotlinx-coroutines-test (plain JVM tests only).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-11-vaca-protocol-support-design.md`. Verified against VACA integration v0.12.1, wyoming lib 1.10.0 source, HA core wyoming component.
- Existing app constraints unchanged: minSdk 28, targetSdk 34, compileSdk 34, applicationId `com.rar.echodash`, label "Echo Dashboard", jvmTarget 17, SDK at `/home/rar/android-sdk`.
- Builds/tests need `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto` first. Run gradle from repo root `/home/rar/android_simpla_ha_dash`.
- Plain-JVM unit tests only. NO Robolectric, NO instrumentation tests. Thin Android wrappers (NsdManager, AudioTrack, ExoPlayer, SensorManager, window flags) stay untested.
- TCP port: **10700**. mDNS service type: **`_vaca._tcp.`** (Android NSD form; HA sees `_vaca._tcp.local.`). Service name: **`Echo Dashboard`**.
- Wyoming header `version` field we write: **"1.7.1"** (readers ignore it; VACA requires wyoming>=1.7.1).
- New dependency allowed: `androidx.media3:media3-exoplayer:1.4.1` ONLY. (1.5.x requires compileSdk 35 — do not upgrade.)
- Add `testOptions { unitTests.isReturnDefaultValues = true }` to `app/build.gradle.kts` (Task 1) so `android.util.Log` no-ops in JVM tests.
- kiosk settings defaults mirror the integration's entity defaults: screen_on=true, screen_brightness=50, screen_auto_brightness=true, screen_always_on=true, screen_saver=false, dark_mode=true, screen_timeout=60.
- App version string reported to HA: `BuildConfig.VERSION_NAME` (enable `buildConfig = true`; bump versionName to "0.2" in Task 9).

## Protocol Reference (pinned from source — normative for all tasks)

**Framing** (wyoming `event.py`): each event = one JSON header line terminated by `\n`, then optional bodies.
- Write: header `{"type": T, "version": "1.7.1", "data_length": N, "payload_length": M}` (omit `data_length` if no data, omit `payload_length` if no payload), then N bytes UTF-8 JSON data object, then M raw payload bytes.
- Read: must also accept inline `"data": {...}` in the header and merge the `data_length` block over it (block wins per-key).

**HA connection lifecycle** (HA is the TCP *client*; two kinds of connections):
1. *Probe* (`load_wyoming_info`, on integration setup, 2s timeout, 3 retries): sends `describe` → expects `info`; sends bare `capabilities` (no data) → expects `capabilities` reply with data.
2. *Persistent satellite session*: on connect HA sends `run-satellite`; VACA's hook then pushes a full `settings` custom event (`integration_version`, `min_required_apk_version`, `ha_url`, `ha_port`, `ha_dashboard`, `custom_files`, plus entity-backed keys). Then HA sends `describe` (expects `info`; VACA hook follows each `describe` with a bare `capabilities` request). Keepalive: HA sends `ping` ~2s after our last `pong`; **we must reply `pong` (copying `text`) or HA times out in 5s and reconnects** (reconnect delay 10s). HA may also send `pause-satellite`, `timer-started/updated/cancelled/finished` — ignore them.

**HA → device custom events** (`type: "custom-event"`, HA flattens data):
- Settings: `{"event_type": "settings", "settings": {key: value, ...}}`
- Action: `{"event_type": "action", "action": "<name>", "payload": <any|null>}`
- Actions: `screen-sleep`, `screen-wake`, `wake`, `refresh`, `toast-message`, `play-media` (payload `{"url": str, "volume": number 0-100}`), `play` (payload `{"volume": number}`), `pause`, `stop`, `set-volume` (payload `{"volume": int 0-100}`), `update-custom-files` (ignore).

**Device → HA custom events** (integration's `CustomEvent.from_event` reads the NESTED `data` key — asymmetric with HA→device!):
- Settings feedback: `{"event_type": "settings", "data": {"settings": {key: value, ...}}}` — syncs feedback entities (screen_on, screen_saver, music_volume).
- Status: `{"event_type": "status", "data": {"sensors": {"light": int, "orientation": str, "current_path": str}}}` and `{"event_type": "status", "data": {"media_player": {"playing": bool}}}`.

**Settings keys we honor:** `screen_on` (bool), `screen_brightness` (int 0-100), `screen_auto_brightness` (bool), `screen_always_on` (bool), `screen_saver` (bool), `dark_mode` (bool), `screen_timeout` (int seconds, one of 15/30/60/120/300/600/1800), `music_volume` (int 1-10, MediaBridge), `ducking_volume` (int 1-10, MediaBridge). All other keys (mute, wake_word*, mic_gain, noise_suppression_level, vad_sensitivity, zoom_level, text_size, screen_orientation_mode, notification_volume, ha_url, ...) are ignored.

**`info` reply data** (satellite must be installed; include every Artifact field — the python from_dict may not default missing fields):
```json
{"asr": [], "tts": [], "handle": [], "intent": [], "wake": [], "mic": [], "snd": [],
 "satellite": {"name": "Echo Dashboard",
   "attribution": {"name": "Echo Dashboard", "url": "https://github.com/rar/echo-dashboard"},
   "installed": true, "description": "Native Home Assistant dashboard", "version": "<versionName>",
   "area": null, "has_vad": false, "active_wake_words": [], "max_active_wake_words": 0,
   "supports_trigger": false}}
```

**`capabilities` reply data:**
```json
{"app_version": "<versionName>", "has_battery": false, "has_front_camera": false, "has_dnd": false,
 "sensors": [{"type": 5}],
 "audio": {"max_music_volume": 10, "max_notification_volume": 10}}
```
(`sensors` contains `{"type": 5}` only if the device has an ambient light sensor; type 5 = light. Never declare types 1/8.)

**Announce stream** (HA → device, after our entities exist): `audio-start` (`rate: 22050, width: 2, channels: 1, timestamp: 0`) → repeated `audio-chunk` (payload = raw PCM s16le) → `audio-stop`. Device replies `played` (type only, no data) when done — **always send `played`, even on error**, or HA's announce service call hangs for the audio duration.

## File Map

| File | Responsibility |
|---|---|
| `app/src/main/java/com/rar/echodash/vaca/WyomingEvent.kt` | Event type + codec (framing) |
| `app/src/main/java/com/rar/echodash/vaca/VacaMessages.kt` | Parse incoming events; build outgoing events; capabilities builder |
| `app/src/main/java/com/rar/echodash/vaca/VacaServer.kt` | TCP server, handshake, ping/pong, routing, send API |
| `app/src/main/java/com/rar/echodash/vaca/KioskController.kt` | KioskDevice interface + settings/action → device mapping, timeout, auto-brightness, feedback, persistence |
| `app/src/main/java/com/rar/echodash/vaca/AnnouncePlayer.kt` | PcmSink interface + announce stream state machine |
| `app/src/main/java/com/rar/echodash/vaca/MediaBridge.kt` | MediaEngine interface + media actions/volume/ducking/status |
| `app/src/main/java/com/rar/echodash/vaca/AndroidPcmSink.kt` | AudioTrack adapter (untested) |
| `app/src/main/java/com/rar/echodash/vaca/ExoPlayerEngine.kt` | ExoPlayer adapter (untested) |
| `app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt` | mDNS registration (untested) |
| `app/src/main/java/com/rar/echodash/vaca/LightSensorReporter.kt` | TYPE_LIGHT sensor → lux callback (untested) |
| `app/src/main/java/com/rar/echodash/vaca/AndroidKioskDevice.kt` | KioskDevice impl bridging Compose UI state + window hooks |
| `app/src/main/java/com/rar/echodash/ui/KioskOverlays.kt` | screen-off/screensaver/toast/bright-mode overlays |
| `app/src/main/java/com/rar/echodash/EchoDashApplication.kt` | Application singleton owning AppDeps, starts VACA server |
| Modified: `App.kt`, `MainActivity.kt`, `SettingsStore.kt`, `SetupScreen.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `README.md` | wiring, registration removal, docs |

---

### Task 1: Test options + Wyoming event codec

**Files:**
- Modify: `app/build.gradle.kts` (add testOptions)
- Create: `app/src/main/java/com/rar/echodash/vaca/WyomingEvent.kt`
- Test: `app/src/test/java/com/rar/echodash/vaca/WyomingCodecTest.kt`

**Interfaces:**
- Consumes: nothing (foundation task).
- Produces: `data class WyomingEvent(type: String, data: JsonObject = JsonObject(emptyMap()), payload: ByteArray = ByteArray(0))`; `object WyomingCodec { fun write(event: WyomingEvent, out: OutputStream); fun read(input: InputStream): WyomingEvent? }`. `read` returns null on clean EOF, throws `IOException` on garbage/mid-frame EOF.

- [ ] **Step 1: Add test options to `app/build.gradle.kts`**

Inside the `android { }` block (after `kotlinOptions`):

```kotlin
    testOptions { unitTests.isReturnDefaultValues = true }
```

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/com/rar/echodash/vaca/WyomingCodecTest.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class WyomingCodecTest {

    private fun bytesOf(event: WyomingEvent): ByteArray {
        val out = ByteArrayOutputStream()
        WyomingCodec.write(event, out)
        return out.toByteArray()
    }

    private fun roundTrip(event: WyomingEvent): WyomingEvent =
        WyomingCodec.read(ByteArrayInputStream(bytesOf(event)))!!

    @Test
    fun roundTripsHeaderOnlyEvent() {
        val e = WyomingEvent("run-satellite")
        assertEquals(e, roundTrip(e))
    }

    @Test
    fun roundTripsDataEvent() {
        val e = WyomingEvent("ping", buildJsonObject { put("text", "abc") })
        assertEquals(e, roundTrip(e))
    }

    @Test
    fun roundTripsDataAndPayloadEvent() {
        val e = WyomingEvent(
            "audio-chunk",
            buildJsonObject { put("rate", 22050); put("width", 2); put("channels", 1) },
            byteArrayOf(1, 2, 3, 0, -1, 127),
        )
        assertEquals(e, roundTrip(e))
    }

    @Test
    fun writesDataAsLengthPrefixedBlockWithVersion() {
        val bytes = bytesOf(WyomingEvent("info", buildJsonObject { put("k", "v") }))
        val headerLine = bytes.toString(Charsets.UTF_8).substringBefore('\n')
        val header = Json.parseToJsonElement(headerLine).jsonObject
        assertEquals("info", header["type"]!!.jsonPrimitive.content)
        assertEquals("1.7.1", header["version"]!!.jsonPrimitive.content)
        assertTrue(header.containsKey("data_length"))
        assertFalse(header.containsKey("data"))
        assertEquals(header["data_length"]!!.jsonPrimitive.int,
            bytes.size - headerLine.toByteArray(Charsets.UTF_8).size - 1)
    }

    @Test
    fun readMergesInlineDataWithDataBlockAndBlockWins() {
        // python wyoming may put data inline AND in a data_length block; block wins per key
        val block = """{"a":2,"b":3}""".toByteArray(Charsets.UTF_8)
        val header = """{"type":"custom-event","data":{"a":1,"c":9},"data_length":${block.size}}"""
        val stream = ByteArrayInputStream(header.toByteArray(Charsets.UTF_8) + '\n'.code.toByte() + block)
        val e = WyomingCodec.read(stream)!!
        assertEquals(2, e.data["a"]!!.jsonPrimitive.int)
        assertEquals(3, e.data["b"]!!.jsonPrimitive.int)
        assertEquals(9, e.data["c"]!!.jsonPrimitive.int)
    }

    @Test
    fun returnsNullOnCleanEof() {
        assertNull(WyomingCodec.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun throwsOnGarbageHeader() {
        try {
            WyomingCodec.read(ByteArrayInputStream("not json at all\n".toByteArray()))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun throwsOnTruncatedPayload() {
        val full = bytesOf(WyomingEvent("audio-chunk",
            buildJsonObject { put("rate", 22050); put("width", 2); put("channels", 1) },
            ByteArray(100)))
        try {
            WyomingCodec.read(ByteArrayInputStream(full.copyOf(full.size - 10)))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.WyomingCodecTest"`
Expected: FAIL — unresolved reference `WyomingEvent` (compile error).

- [ ] **Step 4: Write the implementation**

`app/src/main/java/com/rar/echodash/vaca/WyomingEvent.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One Wyoming event: JSON header line + optional JSON data block + optional binary payload. */
data class WyomingEvent(
    val type: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is WyomingEvent && type == other.type && data == other.data &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * type.hashCode() + data.hashCode()) + payload.contentHashCode()
}

object WyomingCodec {
    private val json = Json { ignoreUnknownKeys = true }
    const val WYOMING_VERSION = "1.7.1"
    private const val MAX_HEADER_BYTES = 1 shl 20

    fun write(event: WyomingEvent, out: OutputStream) {
        val dataBytes: ByteArray? = if (event.data.isNotEmpty()) {
            json.encodeToString(JsonObject.serializer(), event.data).toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        val header = buildJsonObject {
            put("type", event.type)
            put("version", WYOMING_VERSION)
            if (dataBytes != null) put("data_length", dataBytes.size)
            if (event.payload.isNotEmpty()) put("payload_length", event.payload.size)
        }
        out.write(json.encodeToString(JsonObject.serializer(), header).toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
        if (dataBytes != null) out.write(dataBytes)
        if (event.payload.isNotEmpty()) out.write(event.payload)
        out.flush()
    }

    /** Null on clean EOF; IOException on garbage headers or mid-frame EOF (framing is unrecoverable). */
    fun read(input: InputStream): WyomingEvent? {
        val line = readLine(input) ?: return null
        val header = try {
            json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw IOException("malformed wyoming header", e)
        }
        val type = (header["type"] ?: throw IOException("wyoming header missing type"))
            .jsonPrimitive.content
        var data = header["data"] as? JsonObject ?: JsonObject(emptyMap())
        val dataLength = header["data_length"]?.jsonPrimitive?.int ?: 0
        if (dataLength > 0) {
            val block = try {
                json.parseToJsonElement(
                    readExactly(input, dataLength).toString(Charsets.UTF_8)
                ).jsonObject
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("malformed wyoming data block", e)
            }
            data = JsonObject(data + block)
        }
        val payloadLength = header["payload_length"]?.jsonPrimitive?.int ?: 0
        val payload = if (payloadLength > 0) readExactly(input, payloadLength) else ByteArray(0)
        return WyomingEvent(type, data, payload)
    }

    private fun readLine(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (buf.size() == 0) return null
                throw IOException("EOF inside wyoming header")
            }
            if (b == '\n'.code) return buf.toByteArray()
            buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) throw IOException("wyoming header too long")
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = input.read(out, off, n - off)
            if (read == -1) throw IOException("EOF inside wyoming event body")
            off += read
        }
        return out
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.WyomingCodecTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/rar/echodash/vaca/WyomingEvent.kt app/src/test/java/com/rar/echodash/vaca/WyomingCodecTest.kt
git commit -m "feat: wyoming event codec for VACA protocol"
```

---

### Task 2: VACA message vocabulary

**Files:**
- Create: `app/src/main/java/com/rar/echodash/vaca/VacaMessages.kt`
- Test: `app/src/test/java/com/rar/echodash/vaca/VacaMessagesTest.kt`

**Interfaces:**
- Consumes: `WyomingEvent`, `WyomingCodec` (Task 1).
- Produces:
  - `sealed interface VacaIncoming` with members `Describe`, `CapabilitiesRequest`, `Ping(text: String?)`, `RunSatellite`, `SettingsChanged(settings: JsonObject)`, `Action(action: String, payload: JsonElement?)`, `AudioStart(rate: Int, width: Int, channels: Int)`, `AudioChunk(pcm: ByteArray)`, `AudioStop`, `Unknown(type: String)`.
  - `object VacaParser { fun parse(event: WyomingEvent): VacaIncoming }`
  - `object VacaOutgoing { fun info(appVersion: String): WyomingEvent; fun capabilities(caps: JsonObject): WyomingEvent; fun pong(text: String?): WyomingEvent; fun settingsFeedback(settings: JsonObject): WyomingEvent; fun status(status: JsonObject): WyomingEvent; fun played(): WyomingEvent; fun buildCapabilities(appVersion: String, hasLightSensor: Boolean, maxMusicVolume: Int = 10, maxNotificationVolume: Int = 10): JsonObject }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/vaca/VacaMessagesTest.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VacaMessagesTest {

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun parsesHandshakeEvents() {
        assertEquals(VacaIncoming.Describe, VacaParser.parse(WyomingEvent("describe")))
        assertEquals(VacaIncoming.CapabilitiesRequest, VacaParser.parse(WyomingEvent("capabilities")))
        assertEquals(VacaIncoming.RunSatellite, VacaParser.parse(WyomingEvent("run-satellite")))
        assertEquals(VacaIncoming.Ping("x"), VacaParser.parse(WyomingEvent("ping", json("""{"text":"x"}"""))))
        assertEquals(VacaIncoming.Ping(null), VacaParser.parse(WyomingEvent("ping", json("""{"text":null}"""))))
    }

    @Test
    fun parsesFlattenedSettingsCustomEvent() {
        // HA->device shape: settings live NEXT TO event_type, not nested under "data"
        val e = WyomingEvent("custom-event",
            json("""{"event_type":"settings","settings":{"screen_on":true,"screen_brightness":70}}"""))
        val msg = VacaParser.parse(e) as VacaIncoming.SettingsChanged
        assertEquals(true, msg.settings["screen_on"]!!.jsonPrimitive.boolean)
        assertEquals(70, msg.settings["screen_brightness"]!!.jsonPrimitive.int)
    }

    @Test
    fun parsesActionCustomEvent() {
        val e = WyomingEvent("custom-event",
            json("""{"event_type":"action","action":"play-media","payload":{"url":"http://r/s.mp3","volume":80}}"""))
        val msg = VacaParser.parse(e) as VacaIncoming.Action
        assertEquals("play-media", msg.action)
        assertEquals("http://r/s.mp3", msg.payload!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesAudioEvents() {
        val start = VacaParser.parse(WyomingEvent("audio-start",
            json("""{"rate":22050,"width":2,"channels":1,"timestamp":0}"""))) as VacaIncoming.AudioStart
        assertEquals(22050, start.rate)
        assertEquals(2, start.width)
        assertEquals(1, start.channels)
        val chunk = VacaParser.parse(WyomingEvent("audio-chunk",
            json("""{"rate":22050,"width":2,"channels":1}"""), byteArrayOf(9, 8))) as VacaIncoming.AudioChunk
        assertTrue(chunk.pcm.contentEquals(byteArrayOf(9, 8)))
        assertEquals(VacaIncoming.AudioStop, VacaParser.parse(WyomingEvent("audio-stop")))
    }

    @Test
    fun unknownEventsAreNotFatal() {
        assertEquals(VacaIncoming.Unknown("pause-satellite"), VacaParser.parse(WyomingEvent("pause-satellite")))
        assertEquals(VacaIncoming.Unknown("timer-started"), VacaParser.parse(WyomingEvent("timer-started")))
        assertTrue(VacaParser.parse(WyomingEvent("custom-event",
            json("""{"event_type":"mystery"}"""))) is VacaIncoming.Unknown)
    }

    @Test
    fun infoEventDeclaresInstalledSatelliteWithAllFields() {
        val e = VacaOutgoing.info("0.2")
        assertEquals("info", e.type)
        for (key in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) {
            assertEquals(0, e.data[key]!!.jsonArray.size)
        }
        val sat = e.data["satellite"]!!.jsonObject
        assertEquals("Echo Dashboard", sat["name"]!!.jsonPrimitive.content)
        assertEquals(true, sat["installed"]!!.jsonPrimitive.boolean)
        assertEquals("0.2", sat["version"]!!.jsonPrimitive.content)
        assertEquals(false, sat["supports_trigger"]!!.jsonPrimitive.boolean)
        assertTrue(sat.containsKey("attribution"))
        assertTrue(sat.containsKey("description"))
        assertTrue(sat.containsKey("area"))
    }

    @Test
    fun capabilitiesGateLightSensor() {
        val with = VacaOutgoing.buildCapabilities("0.2", hasLightSensor = true)
        assertEquals(1, with["sensors"]!!.jsonArray.size)
        assertEquals(5, with["sensors"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.int)
        val without = VacaOutgoing.buildCapabilities("0.2", hasLightSensor = false)
        assertEquals(0, without["sensors"]!!.jsonArray.size)
        assertEquals(false, without["has_battery"]!!.jsonPrimitive.boolean)
        assertEquals(false, without["has_front_camera"]!!.jsonPrimitive.boolean)
        assertEquals(false, without["has_dnd"]!!.jsonPrimitive.boolean)
        assertEquals(10, without["audio"]!!.jsonObject["max_music_volume"]!!.jsonPrimitive.int)
    }

    @Test
    fun deviceToHaEventsNestUnderDataKey() {
        val fb = VacaOutgoing.settingsFeedback(buildJsonObject { put("screen_on", false) })
        assertEquals("custom-event", fb.type)
        assertEquals("settings", fb.data["event_type"]!!.jsonPrimitive.content)
        assertEquals(false, fb.data["data"]!!.jsonObject["settings"]!!.jsonObject["screen_on"]!!.jsonPrimitive.boolean)

        val st = VacaOutgoing.status(buildJsonObject {
            put("sensors", buildJsonObject { put("light", 42) })
        })
        assertEquals("status", st.data["event_type"]!!.jsonPrimitive.content)
        assertEquals(42, st.data["data"]!!.jsonObject["sensors"]!!.jsonObject["light"]!!.jsonPrimitive.int)

        assertEquals("played", VacaOutgoing.played().type)
        assertEquals("pong", VacaOutgoing.pong("t").type)
        assertEquals("t", VacaOutgoing.pong("t").data["text"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.VacaMessagesTest"`
Expected: FAIL — unresolved reference `VacaIncoming` (compile error).

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/rar/echodash/vaca/VacaMessages.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

sealed interface VacaIncoming {
    data object Describe : VacaIncoming
    data object CapabilitiesRequest : VacaIncoming
    data class Ping(val text: String?) : VacaIncoming
    data object RunSatellite : VacaIncoming
    data class SettingsChanged(val settings: JsonObject) : VacaIncoming
    data class Action(val action: String, val payload: JsonElement?) : VacaIncoming
    data class AudioStart(val rate: Int, val width: Int, val channels: Int) : VacaIncoming
    data class AudioChunk(val pcm: ByteArray) : VacaIncoming {
        override fun equals(other: Any?) = other is AudioChunk && pcm.contentEquals(other.pcm)
        override fun hashCode() = pcm.contentHashCode()
    }
    data object AudioStop : VacaIncoming
    data class Unknown(val type: String) : VacaIncoming
}

object VacaParser {
    fun parse(event: WyomingEvent): VacaIncoming = when (event.type) {
        "describe" -> VacaIncoming.Describe
        "capabilities" -> VacaIncoming.CapabilitiesRequest
        "ping" -> VacaIncoming.Ping((event.data["text"] as? JsonPrimitive)?.contentOrNull)
        "run-satellite" -> VacaIncoming.RunSatellite
        "audio-start" -> VacaIncoming.AudioStart(
            rate = event.data["rate"]?.jsonPrimitive?.int ?: 22050,
            width = event.data["width"]?.jsonPrimitive?.int ?: 2,
            channels = event.data["channels"]?.jsonPrimitive?.int ?: 1,
        )
        "audio-chunk" -> VacaIncoming.AudioChunk(event.payload)
        "audio-stop" -> VacaIncoming.AudioStop
        "custom-event" -> parseCustom(event.data)
        else -> VacaIncoming.Unknown(event.type)
    }

    private fun parseCustom(data: JsonObject): VacaIncoming {
        val eventType = (data["event_type"] as? JsonPrimitive)?.contentOrNull
        return when (eventType) {
            // HA flattens custom event data: keys sit beside event_type
            "settings" -> VacaIncoming.SettingsChanged(
                data["settings"] as? JsonObject ?: JsonObject(emptyMap())
            )
            "action" -> VacaIncoming.Action(
                action = (data["action"] as? JsonPrimitive)?.contentOrNull ?: "",
                payload = data["payload"]?.takeIf { it !is JsonNull },
            )
            else -> VacaIncoming.Unknown("custom-event/$eventType")
        }
    }
}

object VacaOutgoing {
    fun info(appVersion: String): WyomingEvent {
        val data = buildJsonObject {
            for (key in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) {
                putJsonArray(key) {}
            }
            putJsonObject("satellite") {
                put("name", "Echo Dashboard")
                putJsonObject("attribution") {
                    put("name", "Echo Dashboard")
                    put("url", "https://github.com/rar/echo-dashboard")
                }
                put("installed", true)
                put("description", "Native Home Assistant dashboard")
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

    fun capabilities(caps: JsonObject) = WyomingEvent("capabilities", caps)

    fun pong(text: String?) = WyomingEvent("pong", buildJsonObject {
        if (text != null) put("text", text) else put("text", JsonNull)
    })

    // Device->HA custom events nest their body under "data" (the integration's
    // CustomEvent.from_event reads event.data["data"]) — asymmetric with HA->device.
    fun settingsFeedback(settings: JsonObject) = WyomingEvent(
        "custom-event",
        buildJsonObject {
            put("event_type", "settings")
            putJsonObject("data") { put("settings", settings) }
        },
    )

    fun status(status: JsonObject) = WyomingEvent(
        "custom-event",
        buildJsonObject {
            put("event_type", "status")
            put("data", status)
        },
    )

    fun played() = WyomingEvent("played")

    fun buildCapabilities(
        appVersion: String,
        hasLightSensor: Boolean,
        maxMusicVolume: Int = 10,
        maxNotificationVolume: Int = 10,
    ): JsonObject = buildJsonObject {
        put("app_version", appVersion)
        put("has_battery", false)
        put("has_front_camera", false)
        put("has_dnd", false)
        putJsonArray("sensors") {
            if (hasLightSensor) addJsonObject { put("type", 5) }
        }
        putJsonObject("audio") {
            put("max_music_volume", maxMusicVolume)
            put("max_notification_volume", maxNotificationVolume)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.VacaMessagesTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/vaca/VacaMessages.kt app/src/test/java/com/rar/echodash/vaca/VacaMessagesTest.kt
git commit -m "feat: VACA message parsing and builders"
```

---

### Task 3: VacaServer

**Files:**
- Create: `app/src/main/java/com/rar/echodash/vaca/VacaServer.kt`
- Test: `app/src/test/java/com/rar/echodash/vaca/VacaServerTest.kt`

**Interfaces:**
- Consumes: `WyomingEvent`, `WyomingCodec` (Task 1); `VacaParser`, `VacaOutgoing` (Task 2).
- Produces: `class VacaServer(scope: CoroutineScope, port: Int = 10700, infoEvent: () -> WyomingEvent, capabilitiesEvent: () -> WyomingEvent, listener: VacaServer.Listener)` with `fun start()`, `fun stop()`, `@Volatile var boundPort: Int` (−1 until bound), `suspend fun sendSettingsFeedback(settings: JsonObject)`, `suspend fun sendStatus(status: JsonObject)`, `suspend fun sendPlayed()`. Nested `interface Listener { fun onSessionStarted(); fun onSettings(settings: JsonObject); fun onAction(action: String, payload: JsonElement?); fun onAudioStart(rate: Int, width: Int, channels: Int); fun onAudioChunk(pcm: ByteArray); fun onAudioStop(); fun onSessionEnded() }`.
- Threading contract: listener callbacks run on server IO threads; audio callbacks may block (that paces the stream); `describe`/`capabilities`/`ping` are answered internally and never reach the listener.
- Error-handling refinement of the spec table: *unknown* event types are logged and dropped with the connection kept; *unparseable framing* (garbage header, truncated body) closes that connection — length-prefixed framing cannot resync, and HA reconnects within 10 s. This is the intended reading of the spec's "malformed/unknown event" row.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/vaca/VacaServerTest.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class VacaServerTest {

    private class RecordingListener : VacaServer.Listener {
        val events = LinkedBlockingQueue<Any>()
        override fun onSessionStarted() { events.put("session-started") }
        override fun onSettings(settings: JsonObject) { events.put(settings) }
        override fun onAction(action: String, payload: JsonElement?) { events.put(action to payload) }
        override fun onAudioStart(rate: Int, width: Int, channels: Int) { events.put("audio-start") }
        override fun onAudioChunk(pcm: ByteArray) { events.put("audio-chunk") }
        override fun onAudioStop() { events.put("audio-stop") }
        override fun onSessionEnded() { events.put("session-ended") }
        fun next(): Any? = events.poll(5, TimeUnit.SECONDS)
    }

    private class TestClient(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port)
        val input: InputStream = socket.getInputStream().buffered()
        val output: OutputStream = socket.getOutputStream().buffered()
        fun send(event: WyomingEvent) = WyomingCodec.write(event, output)
        fun read(): WyomingEvent? = WyomingCodec.read(input)
        override fun close() { socket.close() }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var listener: RecordingListener
    private lateinit var server: VacaServer

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listener = RecordingListener()
        server = VacaServer(
            scope = scope,
            port = 0, // ephemeral for tests
            infoEvent = { VacaOutgoing.info("0.2") },
            capabilitiesEvent = { VacaOutgoing.capabilities(VacaOutgoing.buildCapabilities("0.2", hasLightSensor = false)) },
            listener = listener,
        )
        server.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (server.boundPort <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("server did not bind", server.boundPort > 0)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    @Test
    fun answersDescribeWithInfoAndCapabilitiesRequest() {
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("describe"))
            val info = client.read()!!
            assertEquals("info", info.type)
            assertEquals(true,
                info.data["satellite"]!!.jsonObject["installed"]!!.jsonPrimitive.boolean)

            client.send(WyomingEvent("capabilities"))
            val caps = client.read()!!
            assertEquals("capabilities", caps.type)
            assertEquals("0.2", caps.data["app_version"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun respondsPongToPing() {
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("ping", Json.parseToJsonElement("""{"text":"k1"}""").jsonObject))
            val pong = client.read()!!
            assertEquals("pong", pong.type)
            assertEquals("k1", pong.data["text"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun dispatchesSettingsAndActionsAfterRunSatellite() {
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("run-satellite"))
            assertEquals("session-started", listener.next())

            client.send(WyomingEvent("custom-event",
                Json.parseToJsonElement("""{"event_type":"settings","settings":{"screen_brightness":30}}""").jsonObject))
            val settings = listener.next() as JsonObject
            assertEquals(30, settings["screen_brightness"]!!.jsonPrimitive.int)

            client.send(WyomingEvent("custom-event",
                Json.parseToJsonElement("""{"event_type":"action","action":"refresh","payload":null}""").jsonObject))
            @Suppress("UNCHECKED_CAST")
            val action = listener.next() as Pair<String, JsonElement?>
            assertEquals("refresh", action.first)
            assertNull(action.second)
        }
    }

    @Test
    fun sendStatusReachesActiveSessionAndIsNoopWithoutOne() = runBlocking {
        // no session yet: must not throw
        server.sendStatus(buildJsonObject { put("x", 1) })

        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("run-satellite"))
            assertEquals("session-started", listener.next())
            server.sendStatus(buildJsonObject {
                put("sensors", buildJsonObject { put("light", 7) })
            })
            val e = client.read()!!
            assertEquals("custom-event", e.type)
            assertEquals("status", e.data["event_type"]!!.jsonPrimitive.content)
            assertEquals(7, e.data["data"]!!.jsonObject["sensors"]!!.jsonObject["light"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun signalsSessionEndOnDisconnectAndAcceptsNewConnections() {
        val client = TestClient(server.boundPort)
        client.send(WyomingEvent("run-satellite"))
        assertEquals("session-started", listener.next())
        client.close()
        assertEquals("session-ended", listener.next())

        // server still alive for a fresh session (HA reconnects every 10s)
        TestClient(server.boundPort).use { fresh ->
            fresh.send(WyomingEvent("describe"))
            assertEquals("info", fresh.read()!!.type)
        }
    }

    @Test
    fun survivesGarbageConnection() {
        Socket("127.0.0.1", server.boundPort).use { garbage ->
            garbage.getOutputStream().apply {
                write("this is not wyoming\n".toByteArray())
                flush()
            }
        }
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("describe"))
            assertNotNull(client.read())
        }
    }

    @Test
    fun routesAudioEventsToListener() {
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("run-satellite"))
            assertEquals("session-started", listener.next())
            client.send(WyomingEvent("audio-start",
                Json.parseToJsonElement("""{"rate":22050,"width":2,"channels":1,"timestamp":0}""").jsonObject))
            client.send(WyomingEvent("audio-chunk",
                Json.parseToJsonElement("""{"rate":22050,"width":2,"channels":1}""").jsonObject, ByteArray(64)))
            client.send(WyomingEvent("audio-stop"))
            assertEquals("audio-start", listener.next())
            assertEquals("audio-chunk", listener.next())
            assertEquals("audio-stop", listener.next())
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.VacaServerTest"`
Expected: FAIL — unresolved reference `VacaServer` (compile error).

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/rar/echodash/vaca/VacaServer.kt`:

```kotlin
package com.rar.echodash.vaca

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Wyoming TCP server for the VACA integration. HA is the client: it makes a
 * short-lived probe connection (describe/capabilities) plus a persistent
 * satellite session that begins with run-satellite. Handshake and ping/pong
 * are handled here; settings/actions/audio are routed to [listener] on IO
 * threads (audio callbacks may block — that paces the announce stream).
 */
class VacaServer(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val infoEvent: () -> WyomingEvent,
    private val capabilitiesEvent: () -> WyomingEvent,
    private val listener: Listener,
) {
    interface Listener {
        fun onSessionStarted()
        fun onSettings(settings: JsonObject)
        fun onAction(action: String, payload: JsonElement?)
        fun onAudioStart(rate: Int, width: Int, channels: Int)
        fun onAudioChunk(pcm: ByteArray)
        fun onAudioStop()
        fun onSessionEnded()
    }

    companion object {
        const val DEFAULT_PORT = 10700
        private const val TAG = "VacaServer"
        private const val BIND_RETRY_MS = 5_000L
    }

    private class Connection(val socket: Socket, val out: OutputStream) {
        val writeMutex = Mutex()
    }

    @Volatile var boundPort: Int = -1
        private set

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var active: Connection? = null
    private var acceptJob: Job? = null

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val server = try {
                    ServerSocket(port)
                } catch (e: IOException) {
                    Log.w(TAG, "bind failed, retrying in ${BIND_RETRY_MS}ms", e)
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
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        runCatching { active?.socket?.close() }
        active = null
    }

    suspend fun sendSettingsFeedback(settings: JsonObject) =
        send(VacaOutgoing.settingsFeedback(settings))

    suspend fun sendStatus(status: JsonObject) = send(VacaOutgoing.status(status))

    suspend fun sendPlayed() = send(VacaOutgoing.played())

    private suspend fun send(event: WyomingEvent) {
        val conn = active ?: return
        try {
            withContext(Dispatchers.IO) {
                conn.writeMutex.withLock { WyomingCodec.write(event, conn.out) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
        }
    }

    private suspend fun handle(socket: Socket) {
        val conn = try {
            Connection(socket, socket.getOutputStream().buffered())
        } catch (e: IOException) {
            runCatching { socket.close() }
            return
        }
        suspend fun reply(event: WyomingEvent) =
            conn.writeMutex.withLock { WyomingCodec.write(event, conn.out) }

        var isSession = false
        try {
            val input = socket.getInputStream().buffered()
            while (true) {
                val event = WyomingCodec.read(input) ?: break
                when (val msg = VacaParser.parse(event)) {
                    VacaIncoming.Describe -> reply(infoEvent())
                    VacaIncoming.CapabilitiesRequest -> reply(capabilitiesEvent())
                    is VacaIncoming.Ping -> reply(VacaOutgoing.pong(msg.text))
                    VacaIncoming.RunSatellite -> {
                        active = conn
                        isSession = true
                        listener.onSessionStarted()
                    }
                    is VacaIncoming.SettingsChanged -> listener.onSettings(msg.settings)
                    is VacaIncoming.Action -> listener.onAction(msg.action, msg.payload)
                    is VacaIncoming.AudioStart ->
                        listener.onAudioStart(msg.rate, msg.width, msg.channels)
                    is VacaIncoming.AudioChunk -> listener.onAudioChunk(msg.pcm)
                    VacaIncoming.AudioStop -> listener.onAudioStop()
                    is VacaIncoming.Unknown -> Log.d(TAG, "ignoring event ${msg.type}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connection error", e)
        } finally {
            if (isSession && active === conn) {
                active = null
                listener.onSessionEnded()
            }
            runCatching { socket.close() }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.VacaServerTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Run the whole suite**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/vaca/VacaServer.kt app/src/test/java/com/rar/echodash/vaca/VacaServerTest.kt
git commit -m "feat: VACA wyoming TCP server with handshake and session routing"
```

---

### Task 4: Remove mobile_app registration

Per the approved spec, the VACA device entry supersedes the `mobile_app` registration. OAuth login and the HA WebSocket are unchanged.

**Files:**
- Delete: `app/src/main/java/com/rar/echodash/ha/RegistrationClient.kt`
- Delete: `app/src/test/java/com/rar/echodash/ha/RegistrationClientTest.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/SetupScreen.kt` (drop `registration` param + call)
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (drop `registration` from AppDeps + call site)
- Modify: `app/src/main/java/com/rar/echodash/data/SettingsStore.kt` (drop `webhookId`)
- Test: `app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt` (drop webhookId assertions)

**Interfaces:**
- Consumes: current MVP code.
- Produces: `SetupScreen(settings: SettingsStore, auth: AuthManager, onDone: () -> Unit)` — 3 params, registration gone. `SettingsStore` without `webhookId`. `AppDeps` without `registration`.

- [ ] **Step 1: Update the tests first**

In `app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt`, delete every line referencing `webhookId` (`s.webhookId = "wh"`, `assertEquals("wh", s.webhookId)`, `assertNull(s.webhookId)`). Delete the file `app/src/test/java/com/rar/echodash/ha/RegistrationClientTest.kt`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew test`
Expected: still PASSES (removals only) — this step just confirms nothing else referenced the deleted test.

- [ ] **Step 3: Remove production code**

1. Delete `app/src/main/java/com/rar/echodash/ha/RegistrationClient.kt`.
2. `app/src/main/java/com/rar/echodash/data/SettingsStore.kt`: remove `var webhookId: String?` from the interface, both implementations, and the `.remove("webhook_id")` line in `PrefsSettingsStore.clearAuth()` and the `webhookId = null` line in `InMemorySettingsStore.clearAuth()`.
3. `app/src/main/java/com/rar/echodash/ui/SetupScreen.kt`:
   - Remove imports `com.rar.echodash.ha.DeviceInfo`, `com.rar.echodash.ha.RegistrationClient`, `android.os.Build`.
   - Remove the `registration: RegistrationClient,` parameter.
   - In the `onCode` handler, replace the `auth.exchangeCode(code)` + `registration.register(...)` block so the try body is only:

```kotlin
                        try {
                            auth.exchangeCode(code)
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Login failed: ${e.message}"
                            phase = SetupPhase.EnterUrl
                        }
```

4. `app/src/main/java/com/rar/echodash/App.kt`: remove `import com.rar.echodash.ha.RegistrationClient`, remove `val registration = RegistrationClient(settings, auth, client)` from `AppDeps`, and change the call site to `SetupScreen(deps.settings, deps.auth) { screen = Screen.Picker }`.

- [ ] **Step 4: Run the whole suite**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew test`
Expected: BUILD SUCCESSFUL (RegistrationClientTest's 1 test gone; everything else green).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: drop mobile_app registration (superseded by VACA device entry)"
```

---

### Task 5: KioskController + settings persistence

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/data/SettingsStore.kt` (add `vacaSettingsJson`)
- Create: `app/src/main/java/com/rar/echodash/vaca/KioskController.kt`
- Test: `app/src/test/java/com/rar/echodash/vaca/KioskControllerTest.kt`
- Test (modify): `app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: nothing from Tasks 1–3 (pure logic; wired later).
- Produces:
  - `interface KioskDevice { fun setScreenOn(on: Boolean); fun setBrightness(percent: Int); fun setKeepScreenOn(alwaysOn: Boolean); fun setScreensaver(active: Boolean); fun setDarkMode(dark: Boolean); fun showToast(message: String); fun refresh() }`
  - `class KioskController(scope: CoroutineScope, device: KioskDevice, persist: (String) -> Unit = {}, restoredJson: String? = null)` with `var sendFeedback: (JsonObject) -> Unit`, `fun applySettings(settings: JsonObject)`, `fun handleAction(action: String, payload: JsonElement?)`, `fun onUserInteraction()`, `fun onLightLevel(lux: Float)`, `fun currentSettings(): JsonObject`, `fun pushToDevice()`.
  - `SettingsStore.vacaSettingsJson: String?` (survives `clearAuth()`).
- Threading contract: the controller is confined to `scope`'s dispatcher; callers hop into that scope (the app uses `Dispatchers.Main.immediate`; tests call directly on the test dispatcher).
- `handleAction` handles `screen-wake`/`wake`/`screen-sleep`/`refresh`/`toast-message`; media actions are handled by MediaBridge (Task 7) before reaching this.

- [ ] **Step 1: Add `vacaSettingsJson` to SettingsStore**

In `app/src/main/java/com/rar/echodash/data/SettingsStore.kt` add to the interface (after `temperatureEntityId`):

```kotlin
    var vacaSettingsJson: String?
```

`InMemorySettingsStore`: add `override var vacaSettingsJson: String? = null` (do NOT touch it in `clearAuth()`).

`PrefsSettingsStore`: add

```kotlin
    override var vacaSettingsJson: String?
        get() = string("vaca_settings"); set(v) = put("vaca_settings", v)
```

(also NOT cleared in `clearAuth()`).

- [ ] **Step 2: Write the failing tests**

Append to `app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt` inside the class:

```kotlin
    @Test
    fun vacaSettingsSurviveClearAuth() {
        val s: SettingsStore = InMemorySettingsStore()
        s.vacaSettingsJson = """{"screen_brightness":40}"""
        s.clearAuth()
        assertEquals("""{"screen_brightness":40}""", s.vacaSettingsJson)
    }
```

`app/src/test/java/com/rar/echodash/vaca/KioskControllerTest.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KioskControllerTest {

    private class FakeDevice : KioskDevice {
        val calls = mutableListOf<String>()
        override fun setScreenOn(on: Boolean) { calls += "screen:$on" }
        override fun setBrightness(percent: Int) { calls += "brightness:$percent" }
        override fun setKeepScreenOn(alwaysOn: Boolean) { calls += "keepOn:$alwaysOn" }
        override fun setScreensaver(active: Boolean) { calls += "saver:$active" }
        override fun setDarkMode(dark: Boolean) { calls += "dark:$dark" }
        override fun showToast(message: String) { calls += "toast:$message" }
        override fun refresh() { calls += "refresh" }
    }

    private fun settings(jsonText: String): JsonObject =
        Json.parseToJsonElement(jsonText).jsonObject

    @Test
    fun appliesScreenSettingsToDevice() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings(
            """{"screen_on":false,"dark_mode":false,"screen_saver":true,"screen_always_on":false}"""))
        assertTrue(device.calls.contains("screen:false"))
        assertTrue(device.calls.contains("dark:false"))
        assertTrue(device.calls.contains("saver:true"))
        assertTrue(device.calls.contains("keepOn:false"))
        kiosk.cancelTimers()
    }

    @Test
    fun manualBrightnessOnlyAppliesWhenAutoBrightnessOff() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_brightness":30}"""))
        assertTrue("auto on: no direct brightness", device.calls.none { it.startsWith("brightness:") })
        kiosk.applySettings(settings("""{"screen_auto_brightness":false}"""))
        assertTrue(device.calls.contains("brightness:30"))
        kiosk.cancelTimers()
    }

    @Test
    fun autoBrightnessFollowsLightLevel() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.onLightLevel(0f)
        kiosk.onLightLevel(400f)
        assertTrue(device.calls.contains("brightness:10"))
        assertTrue(device.calls.contains("brightness:100"))
        device.calls.clear()
        kiosk.applySettings(settings("""{"screen_auto_brightness":false}"""))
        device.calls.clear()
        kiosk.onLightLevel(400f)
        assertTrue("manual mode ignores lux", device.calls.none { it.startsWith("brightness:") })
        kiosk.cancelTimers()
    }

    @Test
    fun screenTimeoutSleepsScreenWhenNotAlwaysOn() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_always_on":false,"screen_timeout":15}"""))
        device.calls.clear()
        advanceTimeBy(15_001)
        runCurrent()
        assertTrue(device.calls.contains("screen:false"))
        // interaction wakes it again
        kiosk.onUserInteraction()
        assertTrue(device.calls.contains("screen:true"))
        kiosk.cancelTimers()
    }

    @Test
    fun alwaysOnPreventsTimeout() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_timeout":15}"""))
        device.calls.clear()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(device.calls.none { it == "screen:false" })
        kiosk.cancelTimers()
    }

    @Test
    fun userInteractionClearsScreensaverAndSendsFeedback() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        var feedback: JsonObject? = null
        kiosk.sendFeedback = { feedback = it }
        kiosk.applySettings(settings("""{"screen_saver":true}"""))
        device.calls.clear()
        kiosk.onUserInteraction()
        assertTrue(device.calls.contains("saver:false"))
        assertEquals(false, feedback!!["screen_saver"]!!.jsonPrimitive.boolean)
        kiosk.cancelTimers()
    }

    @Test
    fun persistsAndRestoresState() = runTest {
        var persisted: String? = null
        val kiosk = KioskController(this, FakeDevice(), persist = { persisted = it })
        kiosk.applySettings(settings("""{"screen_brightness":25,"dark_mode":false}"""))
        kiosk.cancelTimers()

        val device2 = FakeDevice()
        val restored = KioskController(this, device2, restoredJson = persisted)
        assertEquals(25, restored.currentSettings()["screen_brightness"]!!.jsonPrimitive.int)
        assertEquals(false, restored.currentSettings()["dark_mode"]!!.jsonPrimitive.boolean)
        restored.pushToDevice()
        assertTrue(device2.calls.contains("dark:false"))
        restored.cancelTimers()
    }

    @Test
    fun ignoresUnsupportedAndMalformedKeys() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        var feedbackCount = 0
        kiosk.sendFeedback = { feedbackCount++ }
        kiosk.applySettings(settings(
            """{"wake_word":"ok_nabu","mic_gain":5,"ha_url":"http://x","screen_on":"maybe"}"""))
        assertEquals("nothing supported changed -> no feedback", 0, feedbackCount)
        assertTrue(device.calls.isEmpty())
        kiosk.cancelTimers()
    }

    @Test
    fun feedbackContainsAllSupportedKeys() = runTest {
        val kiosk = KioskController(this, FakeDevice())
        var feedback: JsonObject? = null
        kiosk.sendFeedback = { feedback = it }
        kiosk.applySettings(settings("""{"screen_brightness":80}"""))
        val fb = feedback!!
        for (key in listOf("screen_on", "screen_brightness", "screen_auto_brightness",
            "screen_always_on", "screen_saver", "dark_mode", "screen_timeout")) {
            assertTrue("missing $key", fb.containsKey(key))
        }
        assertEquals(80, fb["screen_brightness"]!!.jsonPrimitive.int)
        kiosk.cancelTimers()
    }

    @Test
    fun actionsMapToDevice() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.handleAction("screen-sleep", null)
        assertTrue(device.calls.contains("screen:false"))
        kiosk.handleAction("screen-wake", null)
        assertTrue(device.calls.contains("screen:true"))
        kiosk.handleAction("wake", null)
        kiosk.handleAction("refresh", null)
        assertTrue(device.calls.contains("refresh"))
        kiosk.handleAction("toast-message", JsonPrimitive("hello"))
        assertTrue(device.calls.contains("toast:hello"))
        kiosk.handleAction("toast-message",
            Json.parseToJsonElement("""{"message":"dinner"}"""))
        assertTrue(device.calls.contains("toast:dinner"))
        kiosk.handleAction("update-custom-files", null) // ignored, no crash
        kiosk.cancelTimers()
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.KioskControllerTest" --tests "com.rar.echodash.data.SettingsStoreTest"`
Expected: FAIL — unresolved reference `KioskController` / `vacaSettingsJson` (compile error).

- [ ] **Step 4: Write the implementation**

`app/src/main/java/com/rar/echodash/vaca/KioskController.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Device-side operations. Implemented by AndroidKioskDevice (window + Compose UI state). */
interface KioskDevice {
    fun setScreenOn(on: Boolean)
    fun setBrightness(percent: Int)
    fun setKeepScreenOn(alwaysOn: Boolean)
    fun setScreensaver(active: Boolean)
    fun setDarkMode(dark: Boolean)
    fun showToast(message: String)
    fun refresh()
}

/**
 * Maps VACA settings/actions onto the kiosk. Confined to [scope]'s dispatcher —
 * callers must hop into that scope. Defaults mirror the integration's entities.
 */
class KioskController(
    private val scope: CoroutineScope,
    private val device: KioskDevice,
    private val persist: (String) -> Unit = {},
    restoredJson: String? = null,
) {
    private var screenOn = true
    private var brightness = 50
    private var autoBrightness = true
    private var alwaysOn = true
    private var screensaver = false
    private var darkMode = true
    private var timeoutSeconds = 60
    private var timeoutJob: Job? = null

    /** Pushes settings feedback to HA; set by app wiring. */
    var sendFeedback: (JsonObject) -> Unit = {}

    init {
        restoredJson
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.let { readInto(it) }
    }

    /** Re-apply state to the device (call after the window bridge attaches, and after restore). */
    fun pushToDevice() {
        device.setScreenOn(screenOn)
        if (!autoBrightness) device.setBrightness(brightness)
        device.setKeepScreenOn(alwaysOn)
        device.setScreensaver(screensaver)
        device.setDarkMode(darkMode)
        armTimeout()
    }

    fun applySettings(settings: JsonObject) {
        var changed = false
        settings.forEach { (key, value) ->
            when (key) {
                "screen_on" -> value.asBoolean()?.let {
                    screenOn = it; device.setScreenOn(it); changed = true
                }
                "screen_brightness" -> value.asInt()?.let {
                    brightness = it.coerceIn(0, 100)
                    if (!autoBrightness) device.setBrightness(brightness)
                    changed = true
                }
                "screen_auto_brightness" -> value.asBoolean()?.let {
                    autoBrightness = it
                    if (!it) device.setBrightness(brightness)
                    changed = true
                }
                "screen_always_on" -> value.asBoolean()?.let {
                    alwaysOn = it; device.setKeepScreenOn(it); changed = true
                }
                "screen_saver" -> value.asBoolean()?.let {
                    screensaver = it; device.setScreensaver(it); changed = true
                }
                "dark_mode" -> value.asBoolean()?.let {
                    darkMode = it; device.setDarkMode(it); changed = true
                }
                "screen_timeout" -> value.asInt()?.let {
                    timeoutSeconds = it; changed = true
                }
                else -> Unit // voice/browser/media keys: handled elsewhere or ignored
            }
        }
        if (changed) {
            armTimeout()
            persistAndFeedback()
        }
    }

    fun handleAction(action: String, payload: JsonElement?) {
        when (action) {
            "screen-wake", "wake" -> setScreen(true)
            "screen-sleep" -> setScreen(false)
            "refresh" -> device.refresh()
            "toast-message" -> device.showToast(toastText(payload))
            else -> Unit
        }
    }

    /** Call on any user touch: wakes the screen, clears the screensaver, re-arms the timeout. */
    fun onUserInteraction() {
        if (screensaver) {
            screensaver = false
            device.setScreensaver(false)
            persistAndFeedback()
        }
        if (!screenOn) setScreen(true) else armTimeout()
    }

    /** Ambient light in lux; drives brightness while auto-brightness is on. */
    fun onLightLevel(lux: Float) {
        if (!autoBrightness) return
        val percent = (10 + (lux.coerceIn(0f, 400f) / 400f) * 90).toInt()
        device.setBrightness(percent)
    }

    fun currentSettings(): JsonObject = buildJsonObject {
        put("screen_on", screenOn)
        put("screen_brightness", brightness)
        put("screen_auto_brightness", autoBrightness)
        put("screen_always_on", alwaysOn)
        put("screen_saver", screensaver)
        put("dark_mode", darkMode)
        put("screen_timeout", timeoutSeconds)
    }

    /** Tests call this so runTest doesn't hang on an armed timeout. */
    fun cancelTimers() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun setScreen(on: Boolean) {
        screenOn = on
        device.setScreenOn(on)
        armTimeout()
        persistAndFeedback()
    }

    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
        if (alwaysOn || !screenOn || timeoutSeconds <= 0) return
        timeoutJob = scope.launch {
            delay(timeoutSeconds * 1000L)
            setScreen(false)
        }
    }

    private fun persistAndFeedback() {
        val current = currentSettings()
        persist(current.toString())
        sendFeedback(current)
    }

    private fun readInto(saved: JsonObject) {
        saved["screen_on"]?.asBoolean()?.let { screenOn = it }
        saved["screen_brightness"]?.asInt()?.let { brightness = it }
        saved["screen_auto_brightness"]?.asBoolean()?.let { autoBrightness = it }
        saved["screen_always_on"]?.asBoolean()?.let { alwaysOn = it }
        saved["screen_saver"]?.asBoolean()?.let { screensaver = it }
        saved["dark_mode"]?.asBoolean()?.let { darkMode = it }
        saved["screen_timeout"]?.asInt()?.let { timeoutSeconds = it }
    }

    private fun toastText(payload: JsonElement?): String = when (payload) {
        is JsonPrimitive -> payload.contentOrNull ?: ""
        is JsonObject ->
            (payload["message"] as? JsonPrimitive)?.contentOrNull
                ?: (payload["text"] as? JsonPrimitive)?.contentOrNull
                ?: payload.toString()
        else -> ""
    }

    private fun JsonElement.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.KioskControllerTest" --tests "com.rar.echodash.data.SettingsStoreTest"`
Expected: PASS (10 + 3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/vaca/KioskController.kt app/src/main/java/com/rar/echodash/data/SettingsStore.kt app/src/test/java/com/rar/echodash/vaca/KioskControllerTest.kt app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt
git commit -m "feat: kiosk controller mapping VACA settings/actions to the device"
```

---

### Task 6: AnnouncePlayer

**Files:**
- Create: `app/src/main/java/com/rar/echodash/vaca/AnnouncePlayer.kt`
- Test: `app/src/test/java/com/rar/echodash/vaca/AnnouncePlayerTest.kt`

**Interfaces:**
- Consumes: nothing (wired to VacaServer audio callbacks in Task 9).
- Produces:
  - `interface PcmSink { fun start(rateHz: Int, widthBytes: Int, channels: Int); fun write(pcm: ByteArray); fun finish(); fun abort() }` — `finish()` stops playback after buffered audio drains and releases; `abort()` drops immediately.
  - `class AnnouncePlayer(sink: PcmSink, onPlayed: () -> Unit, setDucking: (Boolean) -> Unit)` with `fun onAudioStart(rate: Int, width: Int, channels: Int)`, `fun onAudioChunk(pcm: ByteArray)`, `fun onAudioStop()`, `fun onDisconnected()`.
- Contract: `onPlayed` fires exactly once per stream — on normal stop AND on sink failure (HA's announce call must never hang); not on disconnect (HA is gone). Ducking is on from audio-start until played/disconnect.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/vaca/AnnouncePlayerTest.kt`:

```kotlin
package com.rar.echodash.vaca

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class AnnouncePlayerTest {

    private class FakeSink(var failOnWrite: Boolean = false) : PcmSink {
        val calls = mutableListOf<String>()
        override fun start(rateHz: Int, widthBytes: Int, channels: Int) {
            calls += "start:$rateHz/$widthBytes/$channels"
        }
        override fun write(pcm: ByteArray) {
            if (failOnWrite) throw IOException("boom")
            calls += "write:${pcm.size}"
        }
        override fun finish() { calls += "finish" }
        override fun abort() { calls += "abort" }
    }

    private class Harness(failOnWrite: Boolean = false) {
        val sink = FakeSink(failOnWrite)
        var playedCount = 0
        val ducks = mutableListOf<Boolean>()
        val player = AnnouncePlayer(sink, onPlayed = { playedCount++ }, setDucking = { ducks += it })
    }

    @Test
    fun playsStreamThenSendsPlayedAndUnducks() {
        val h = Harness()
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(2048))
        h.player.onAudioChunk(ByteArray(1024))
        h.player.onAudioStop()
        assertEquals(listOf("start:22050/2/1", "write:2048", "write:1024", "finish"), h.sink.calls)
        assertEquals(1, h.playedCount)
        assertEquals(listOf(true, false), h.ducks)
    }

    @Test
    fun sinkFailureStillSendsPlayedExactlyOnce() {
        val h = Harness(failOnWrite = true)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(10))   // fails -> abort + played
        h.player.onAudioChunk(ByteArray(10))   // ignored
        h.player.onAudioStop()                 // ignored, no double played
        assertEquals(1, h.playedCount)
        assertEquals(listOf(true, false), h.ducks)
        assertEquals(listOf("start:22050/2/1", "abort"), h.sink.calls)
    }

    @Test
    fun disconnectAbortsWithoutPlayed() {
        val h = Harness()
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(10))
        h.player.onDisconnected()
        assertEquals(0, h.playedCount)
        assertEquals(listOf(true, false), h.ducks)
        assertEquals("abort", h.sink.calls.last())
    }

    @Test
    fun eventsOutsideAStreamAreIgnored() {
        val h = Harness()
        h.player.onAudioChunk(ByteArray(10))
        h.player.onAudioStop()
        h.player.onDisconnected()
        assertEquals(0, h.playedCount)
        assertEquals(0, h.sink.calls.size)
        assertEquals(0, h.ducks.size)
    }

    @Test
    fun restartMidStreamAbortsPreviousStream() {
        val h = Harness()
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioStart(22050, 2, 1)
        assertEquals(listOf("start:22050/2/1", "abort", "start:22050/2/1"), h.sink.calls)
        h.player.onAudioStop()
        assertEquals(1, h.playedCount)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.AnnouncePlayerTest"`
Expected: FAIL — unresolved reference `PcmSink` (compile error).

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/rar/echodash/vaca/AnnouncePlayer.kt`:

```kotlin
package com.rar.echodash.vaca

import android.util.Log

/** Raw PCM output. [finish] blocks/schedules drain of buffered audio then releases; [abort] drops everything now. */
interface PcmSink {
    fun start(rateHz: Int, widthBytes: Int, channels: Int)
    fun write(pcm: ByteArray)
    fun finish()
    fun abort()
}

/**
 * Plays an HA announce stream (audio-start/chunk/stop). Called on the VACA
 * server's connection-reader thread; blocking writes pace the stream.
 * `onPlayed` must fire exactly once per stream, even on failure — otherwise
 * HA's announce service call hangs for the full audio duration.
 */
class AnnouncePlayer(
    private val sink: PcmSink,
    private val onPlayed: () -> Unit,
    private val setDucking: (Boolean) -> Unit,
) {
    private var streaming = false

    fun onAudioStart(rate: Int, width: Int, channels: Int) {
        if (streaming) runCatching { sink.abort() }
        streaming = true
        setDucking(true)
        try {
            sink.start(rate, width, channels)
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun onAudioChunk(pcm: ByteArray) {
        if (!streaming) return
        try {
            sink.write(pcm)
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun onAudioStop() {
        if (!streaming) return
        streaming = false
        try {
            sink.finish()
        } catch (e: Exception) {
            Log.w(TAG, "finish failed", e)
            runCatching { sink.abort() }
        }
        setDucking(false)
        onPlayed()
    }

    fun onDisconnected() {
        if (!streaming) return
        streaming = false
        runCatching { sink.abort() }
        setDucking(false)
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "announce playback failed", e)
        streaming = false
        runCatching { sink.abort() }
        setDucking(false)
        onPlayed()
    }

    private companion object {
        const val TAG = "AnnouncePlayer"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.AnnouncePlayerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/vaca/AnnouncePlayer.kt app/src/test/java/com/rar/echodash/vaca/AnnouncePlayerTest.kt
git commit -m "feat: announce stream player with fail-safe played event"
```

---

### Task 7: MediaBridge

**Files:**
- Create: `app/src/main/java/com/rar/echodash/vaca/MediaBridge.kt`
- Test: `app/src/test/java/com/rar/echodash/vaca/MediaBridgeTest.kt`

**Interfaces:**
- Consumes: nothing (wired in Task 9).
- Produces:
  - `interface MediaEngine { fun play(url: String); fun resume(); fun pause(); fun stop(); fun setVolume(fraction: Float); var onPlayingChanged: ((Boolean) -> Unit)? }`
  - `class MediaBridge(engine: MediaEngine, sendStatus: (JsonObject) -> Unit)` with `fun handleAction(action: String, payload: JsonElement?): Boolean` (true if it was a media action), `fun applySettings(settings: JsonObject)` (honors `music_volume` 1-10 and `ducking_volume` 1-10), `fun setDucked(ducked: Boolean)`.
- Volume model: HA sends volume as 0–100 in `play-media`/`play`/`set-volume` payloads; the `music_volume` setting (1–10) also sets the base volume (×10). Effective engine fraction = base/100, multiplied by ducking_volume/10 while ducked.
- Status: on `onPlayingChanged`, emits exactly `{"media_player": {"playing": <bool>}}` via `sendStatus`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/vaca/MediaBridgeTest.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBridgeTest {

    private class FakeEngine : MediaEngine {
        val calls = mutableListOf<String>()
        var volume = -1f
        override var onPlayingChanged: ((Boolean) -> Unit)? = null
        override fun play(url: String) { calls += "play:$url" }
        override fun resume() { calls += "resume" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun setVolume(fraction: Float) { volume = fraction; calls += "volume:$fraction" }
    }

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun playMediaAppliesVolumeThenPlays() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine) {}
        assertTrue(bridge.handleAction("play-media",
            json("""{"url":"http://radio/stream.mp3","volume":80}""")))
        assertEquals(0.8f, engine.volume, 0.001f)
        assertTrue(engine.calls.contains("play:http://radio/stream.mp3"))
        assertTrue(engine.calls.indexOf("play:http://radio/stream.mp3") >
            engine.calls.indexOfFirst { it.startsWith("volume:") })
    }

    @Test
    fun transportActionsMapToEngine() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine) {}
        assertTrue(bridge.handleAction("pause", null))
        assertTrue(bridge.handleAction("play", json("""{"volume":50}""")))
        assertTrue(bridge.handleAction("stop", null))
        assertTrue(bridge.handleAction("set-volume", json("""{"volume":30}""")))
        assertTrue(engine.calls.contains("pause"))
        assertTrue(engine.calls.contains("resume"))
        assertTrue(engine.calls.contains("stop"))
        assertEquals(0.3f, engine.volume, 0.001f)
    }

    @Test
    fun duckingScalesVolumeAndRestores() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine) {}
        bridge.handleAction("set-volume", json("""{"volume":90}"""))
        bridge.applySettings(json("""{"ducking_volume":1}""").jsonObject)
        bridge.setDucked(true)
        assertEquals(0.09f, engine.volume, 0.001f)
        bridge.setDucked(false)
        assertEquals(0.9f, engine.volume, 0.001f)
    }

    @Test
    fun musicVolumeSettingSetsBaseVolume() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine) {}
        bridge.applySettings(json("""{"music_volume":4}""").jsonObject)
        assertEquals(0.4f, engine.volume, 0.001f)
    }

    @Test
    fun reportsPlayingStatus() {
        val engine = FakeEngine()
        var status: JsonObject? = null
        MediaBridge(engine) { status = it }
        engine.onPlayingChanged!!.invoke(true)
        assertEquals(true,
            status!!["media_player"]!!.jsonObject["playing"]!!.jsonPrimitive.boolean)
        engine.onPlayingChanged!!.invoke(false)
        assertEquals(false,
            status!!["media_player"]!!.jsonObject["playing"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun nonMediaActionsReturnFalseUntouched() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine) {}
        assertFalse(bridge.handleAction("screen-wake", null))
        assertFalse(bridge.handleAction("toast-message", null))
        assertEquals(0, engine.calls.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.MediaBridgeTest"`
Expected: FAIL — unresolved reference `MediaEngine` (compile error).

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/rar/echodash/vaca/MediaBridge.kt`:

```kotlin
package com.rar.echodash.vaca

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Playback engine abstraction over ExoPlayer. Calls may arrive on any thread. */
interface MediaEngine {
    fun play(url: String)
    fun resume()
    fun pause()
    fun stop()
    fun setVolume(fraction: Float)
    var onPlayingChanged: ((Boolean) -> Unit)?
}

/**
 * Drives the HA media_player entity: play-media/play/pause/stop/set-volume
 * actions, music_volume + ducking_volume settings, playing-state status.
 */
class MediaBridge(
    private val engine: MediaEngine,
    private val sendStatus: (JsonObject) -> Unit,
) {
    private var volumePercent = 90 // HA media player default volume_level 0.9
    private var duckingVolume = 1  // 1..10 scale, integration default
    private var ducked = false

    init {
        engine.onPlayingChanged = { playing ->
            sendStatus(buildJsonObject {
                putJsonObject("media_player") { put("playing", playing) }
            })
        }
    }

    /** Returns true when [action] was a media action (handled here). */
    fun handleAction(action: String, payload: JsonElement?): Boolean = when (action) {
        "play-media" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            payloadUrl(payload)?.let { engine.play(it) }
            true
        }
        "play" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            engine.resume()
            true
        }
        "pause" -> { engine.pause(); true }
        "stop" -> { engine.stop(); true }
        "set-volume" -> {
            payloadVolume(payload)?.let { volumePercent = it; applyVolume() }
            true
        }
        else -> false
    }

    fun applySettings(settings: JsonObject) {
        var changed = false
        (settings["music_volume"] as? JsonPrimitive)?.intOrNull?.let {
            volumePercent = (it.coerceIn(0, 10)) * 10
            changed = true
        }
        (settings["ducking_volume"] as? JsonPrimitive)?.intOrNull?.let {
            duckingVolume = it.coerceIn(0, 10)
            changed = true
        }
        if (changed) applyVolume()
    }

    fun setDucked(ducked: Boolean) {
        this.ducked = ducked
        applyVolume()
    }

    private fun applyVolume() {
        val base = volumePercent / 100f
        val fraction = if (ducked) base * (duckingVolume / 10f) else base
        engine.setVolume(fraction.coerceIn(0f, 1f))
    }

    private fun payloadVolume(payload: JsonElement?): Int? =
        ((payload as? JsonObject)?.get("volume") as? JsonPrimitive)?.doubleOrNull?.toInt()

    private fun payloadUrl(payload: JsonElement?): String? =
        ((payload as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.MediaBridgeTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/vaca/MediaBridge.kt app/src/test/java/com/rar/echodash/vaca/MediaBridgeTest.kt
git commit -m "feat: media bridge for HA media_player actions and ducking"
```

---

### Task 8: Android adapters (NSD, AudioTrack, ExoPlayer, light sensor)

Thin wrappers over Android APIs — no JVM tests; the deliverable is a clean compile (`assembleDebug`).

**Files:**
- Modify: `app/build.gradle.kts` (media3 dependency + buildConfig)
- Create: `app/src/main/java/com/rar/echodash/vaca/AndroidPcmSink.kt`
- Create: `app/src/main/java/com/rar/echodash/vaca/ExoPlayerEngine.kt`
- Create: `app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt`
- Create: `app/src/main/java/com/rar/echodash/vaca/LightSensorReporter.kt`

**Interfaces:**
- Consumes: `PcmSink` (Task 6), `MediaEngine` (Task 7).
- Produces: `class AndroidPcmSink : PcmSink`; `class ExoPlayerEngine(context: Context) : MediaEngine` (construct on the main thread); `class NsdAdvertiser(context: Context, port: Int)` with `fun register()`, `fun unregister()`; `class LightSensorReporter(context: Context, onLux: (Float) -> Unit)` with `val hasSensor: Boolean`, `fun start()`, `fun stop()`.

- [ ] **Step 1: Add dependencies and buildConfig**

In `app/build.gradle.kts`: change `buildFeatures { compose = true }` to

```kotlin
    buildFeatures {
        compose = true
        buildConfig = true
    }
```

and add to `dependencies`:

```kotlin
    implementation("androidx.media3:media3-exoplayer:1.4.1")
```

- [ ] **Step 2: Write the adapters**

`app/src/main/java/com/rar/echodash/vaca/AndroidPcmSink.kt`:

```kotlin
package com.rar.echodash.vaca

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** AudioTrack-backed PCM sink for announce streams (s16le only). */
class AndroidPcmSink : PcmSink {
    private var track: AudioTrack? = null

    override fun start(rateHz: Int, widthBytes: Int, channels: Int) {
        abort()
        val channelMask =
            if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(rateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(rateHz)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBuf, 8192) * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also { it.play() }
    }

    override fun write(pcm: ByteArray) {
        track?.write(pcm, 0, pcm.size) // blocking write paces the stream
    }

    override fun finish() {
        track?.let {
            runCatching { it.stop() } // MODE_STREAM: plays out buffered audio, then stops
            it.release()
        }
        track = null
    }

    override fun abort() {
        track?.let {
            runCatching { it.pause(); it.flush() }
            it.release()
        }
        track = null
    }
}
```

`app/src/main/java/com/rar/echodash/vaca/ExoPlayerEngine.kt`:

```kotlin
package com.rar.echodash.vaca

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** ExoPlayer-backed engine; must be constructed on the main thread. */
class ExoPlayerEngine(context: Context) : MediaEngine {
    private val main = Handler(Looper.getMainLooper())
    override var onPlayingChanged: ((Boolean) -> Unit)? = null

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged?.invoke(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlayingChanged?.invoke(false)
            }
        })
    }

    override fun play(url: String) = onMain {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    override fun resume() = onMain { player.play() }
    override fun pause() = onMain { player.pause() }
    override fun stop() = onMain {
        player.stop()
        player.clearMediaItems()
    }
    override fun setVolume(fraction: Float) = onMain { player.volume = fraction }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
```

`app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt`:

```kotlin
package com.rar.echodash.vaca

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Advertises the VACA server via mDNS so HA auto-discovers the device (retries every 30 s on failure). */
class NsdAdvertiser(context: Context, private val port: Int) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.RegistrationListener? = null
    private var stopped = false

    fun register() {
        if (listener != null) return
        stopped = false
        val info = NsdServiceInfo().apply {
            serviceName = "Echo Dashboard"
            serviceType = "_vaca._tcp."
            setPort(this@NsdAdvertiser.port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "registered as ${i.serviceName}")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, err: Int) {
                Log.w(TAG, "registration failed: $err (HA manual host:port setup still works)")
                listener = null
                if (!stopped) handler.postDelayed({ register() }, RETRY_MS)
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, err: Int) {}
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
        const val RETRY_MS = 30_000L
    }
}
```

`app/src/main/java/com/rar/echodash/vaca/LightSensorReporter.kt`:

```kotlin
package com.rar.echodash.vaca

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/** Ambient light readings, throttled: emit on >=20% change (min 5 lux) or every 30 s. */
class LightSensorReporter(
    context: Context,
    private val onLux: (Float) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var lastSentAt = 0L
    private var lastValue = -1f

    val hasSensor: Boolean get() = sensor != null

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values.firstOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        val changedEnough = lastValue < 0 || abs(lux - lastValue) > maxOf(5f, lastValue * 0.2f)
        if (changedEnough || now - lastSentAt >= 30_000) {
            lastSentAt = now
            lastValue = lux
            onLux(lux)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
```

- [ ] **Step 3: Verify it compiles and tests still pass**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/rar/echodash/vaca/AndroidPcmSink.kt app/src/main/java/com/rar/echodash/vaca/ExoPlayerEngine.kt app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt app/src/main/java/com/rar/echodash/vaca/LightSensorReporter.kt
git commit -m "feat: android adapters for VACA (nsd, audiotrack, exoplayer, light sensor)"
```

---

### Task 9: App integration (Application singleton, overlays, wiring)

Wires everything: `AppDeps` moves to an `Application` singleton (fixes the MVP's activity-recreation issue and gives the VACA server a process-long home), MainActivity bridges window operations, Compose overlays render screen-off/screensaver/toast, and the VACA server starts at app start.

**Files:**
- Create: `app/src/main/java/com/rar/echodash/EchoDashApplication.kt`
- Create: `app/src/main/java/com/rar/echodash/vaca/AndroidKioskDevice.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/KioskOverlays.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (full replacement below)
- Modify: `app/src/main/java/com/rar/echodash/MainActivity.kt` (full replacement below)
- Modify: `app/src/main/AndroidManifest.xml` (application name)
- Modify: `app/build.gradle.kts` (versionName "0.2")

**Interfaces:**
- Consumes: everything from Tasks 1–8.
- Produces: `EchoDashApplication.deps: AppDeps`; `AppDeps.startVaca()`; `AndroidKioskDevice` (implements `KioskDevice`) with nested `interface WindowHooks { fun setWindowBrightness(percent: Int); fun setKeepScreenOn(on: Boolean) }` and `fun attach(h: WindowHooks)` / `fun detach(h: WindowHooks)`; `class KioskUiState` (Compose state: `screenOff`, `screensaver`, `darkMode`, `toast`, `toastKey`); `@Composable fun KioskOverlays(ui: KioskUiState, onWakeTouch: () -> Unit)`.
- No new JVM tests (wiring + UI); deliverable is green suite + `assembleDebug`.

- [ ] **Step 1: Bump versionName**

In `app/build.gradle.kts` `defaultConfig`: change `versionName = "0.1"` to `versionName = "0.2"`.

- [ ] **Step 2: Create `EchoDashApplication.kt`**

```kotlin
package com.rar.echodash

import android.app.Application

class EchoDashApplication : Application() {
    lateinit var deps: AppDeps
        private set

    override fun onCreate() {
        super.onCreate()
        deps = AppDeps(this)
        deps.startVaca()
    }
}
```

Register it in `app/src/main/AndroidManifest.xml` by adding one attribute to the `<application>` element:

```xml
    <application
        android:name=".EchoDashApplication"
        android:label="Echo Dashboard"
        ...unchanged...>
```

- [ ] **Step 3: Create `AndroidKioskDevice.kt`**

`app/src/main/java/com/rar/echodash/vaca/AndroidKioskDevice.kt`:

```kotlin
package com.rar.echodash.vaca

import com.rar.echodash.ui.KioskUiState

/**
 * KioskDevice backed by Compose UI state (overlays) plus window-level hooks
 * that exist only while MainActivity is alive. All calls arrive on the main
 * thread (KioskController runs on Dispatchers.Main.immediate).
 */
class AndroidKioskDevice(
    private val ui: KioskUiState,
    private val onRefresh: () -> Unit,
) : KioskDevice {

    interface WindowHooks {
        /** 0-100 maps onto window brightness (floored at 0.01); negative restores system default. */
        fun setWindowBrightness(percent: Int)
        fun setKeepScreenOn(on: Boolean)
    }

    private var hooks: WindowHooks? = null
    private var lastBrightness: Int = -1
    private var keepOn = true

    fun attach(h: WindowHooks) {
        hooks = h
        h.setKeepScreenOn(keepOn)
        if (!ui.screenOff) h.setWindowBrightness(lastBrightness)
    }

    fun detach(h: WindowHooks) {
        if (hooks === h) hooks = null
    }

    override fun setScreenOn(on: Boolean) {
        ui.screenOff = !on
        hooks?.setWindowBrightness(if (on) lastBrightness else 0)
    }

    override fun setBrightness(percent: Int) {
        lastBrightness = percent
        if (!ui.screenOff) hooks?.setWindowBrightness(percent)
    }

    override fun setKeepScreenOn(alwaysOn: Boolean) {
        keepOn = alwaysOn
        hooks?.setKeepScreenOn(alwaysOn)
    }

    override fun setScreensaver(active: Boolean) {
        ui.screensaver = active
    }

    override fun setDarkMode(dark: Boolean) {
        ui.darkMode = dark
    }

    override fun showToast(message: String) {
        ui.toast = message
        ui.toastKey++
    }

    override fun refresh() = onRefresh()
}
```

- [ ] **Step 4: Create `KioskOverlays.kt`**

`app/src/main/java/com/rar/echodash/ui/KioskOverlays.kt`:

```kotlin
package com.rar.echodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Compose-observable kiosk state driven by AndroidKioskDevice. */
class KioskUiState {
    var screenOff by mutableStateOf(false)
    var screensaver by mutableStateOf(false)
    var darkMode by mutableStateOf(true)
    var toast by mutableStateOf<String?>(null)
    var toastKey by mutableIntStateOf(0)
}

/** Render order: bright-mode scrim < toast < screensaver < screen-off. */
@Composable
fun KioskOverlays(ui: KioskUiState, onWakeTouch: () -> Unit) {
    if (!ui.darkMode) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)))
    }
    ui.toast?.let { msg ->
        LaunchedEffect(ui.toastKey) {
            delay(4_000)
            ui.toast = null
        }
        Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xCC222222)) {
                Text(
                    msg,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White,
                )
            }
        }
    }
    if (ui.screensaver && !ui.screenOff) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .pointerInput(Unit) { detectTapGestures { onWakeTouch() } },
        )
    }
    if (ui.screenOff) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { onWakeTouch() } },
        )
    }
}
```

- [ ] **Step 5: Replace `App.kt`**

Full new content of `app/src/main/java/com/rar/echodash/App.kt`:

```kotlin
package com.rar.echodash

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rar.echodash.data.PrefsSettingsStore
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.AuthManager
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaWebSocket
import com.rar.echodash.ui.DashboardScreen
import com.rar.echodash.ui.EntityPickerScreen
import com.rar.echodash.ui.KioskOverlays
import com.rar.echodash.ui.KioskUiState
import com.rar.echodash.ui.SetupScreen
import com.rar.echodash.vaca.AndroidKioskDevice
import com.rar.echodash.vaca.AnnouncePlayer
import com.rar.echodash.vaca.AndroidPcmSink
import com.rar.echodash.vaca.ExoPlayerEngine
import com.rar.echodash.vaca.KioskController
import com.rar.echodash.vaca.LightSensorReporter
import com.rar.echodash.vaca.MediaBridge
import com.rar.echodash.vaca.NsdAdvertiser
import com.rar.echodash.vaca.VacaOutgoing
import com.rar.echodash.vaca.VacaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/** Process-wide dependencies; owned by EchoDashApplication, created on the main thread. */
class AppDeps(context: Context) {
    private val appContext = context.applicationContext

    val settings: SettingsStore = PrefsSettingsStore(appContext)
    val client = OkHttpClient()
    val auth = AuthManager(settings, client)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ws = HaWebSocket(settings, auth, client, scope)

    // --- VACA ---
    // Lambdas below reference `vaca`, declared last; they only run after
    // construction completes (playback/session events), never during init.
    val kioskUi = KioskUiState()
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val kioskDevice = AndroidKioskDevice(kioskUi) {
        settings.temperatureEntityId?.let { entity ->
            ws.stop()
            ws.start(entity)
        }
    }
    val kiosk = KioskController(
        mainScope,
        kioskDevice,
        persist = { settings.vacaSettingsJson = it },
        restoredJson = settings.vacaSettingsJson,
    )
    private val mediaEngine = ExoPlayerEngine(appContext)
    val media = MediaBridge(mediaEngine) { status ->
        scope.launch { vaca.sendStatus(status) }
    }
    val announce = AnnouncePlayer(
        AndroidPcmSink(),
        onPlayed = { scope.launch { vaca.sendPlayed() } },
        setDucking = { media.setDucked(it) },
    )
    val lightSensor = LightSensorReporter(appContext) { lux ->
        mainScope.launch { kiosk.onLightLevel(lux) }
        scope.launch {
            vaca.sendStatus(buildJsonObject {
                putJsonObject("sensors") { put("light", lux.toInt()) }
            })
        }
    }
    val vaca = VacaServer(
        scope = scope,
        infoEvent = { VacaOutgoing.info(BuildConfig.VERSION_NAME) },
        capabilitiesEvent = {
            VacaOutgoing.capabilities(
                VacaOutgoing.buildCapabilities(BuildConfig.VERSION_NAME, lightSensor.hasSensor)
            )
        },
        listener = object : VacaServer.Listener {
            override fun onSessionStarted() {
                mainScope.launch {
                    vaca.sendSettingsFeedback(kiosk.currentSettings())
                    vaca.sendStatus(statusSnapshot())
                }
            }

            override fun onSettings(settings: JsonObject) {
                mainScope.launch {
                    kiosk.applySettings(settings)
                    media.applySettings(settings)
                }
            }

            override fun onAction(action: String, payload: JsonElement?) {
                mainScope.launch {
                    if (!media.handleAction(action, payload)) {
                        kiosk.handleAction(action, payload)
                    }
                }
            }

            override fun onAudioStart(rate: Int, width: Int, channels: Int) =
                announce.onAudioStart(rate, width, channels)

            override fun onAudioChunk(pcm: ByteArray) = announce.onAudioChunk(pcm)
            override fun onAudioStop() = announce.onAudioStop()
            override fun onSessionEnded() = announce.onDisconnected()
        },
    )
    private val nsd = NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT)

    init {
        kiosk.sendFeedback = { s -> scope.launch { vaca.sendSettingsFeedback(s) } }
    }

    fun startVaca() {
        vaca.start()
        nsd.register()
        lightSensor.start()
    }

    private fun statusSnapshot(): JsonObject = buildJsonObject {
        putJsonObject("sensors") {
            put("orientation", "landscape")
            put("current_path", "dashboard")
        }
    }
}

sealed interface Screen {
    data object Setup : Screen
    data object Picker : Screen
    data object Dashboard : Screen
}

fun initialScreen(settings: SettingsStore): Screen = when {
    settings.refreshToken == null -> Screen.Setup
    settings.temperatureEntityId == null -> Screen.Picker
    else -> Screen.Dashboard
}

@Composable
fun EchoDashApp(deps: AppDeps) {
    var screen by remember { mutableStateOf(initialScreen(deps.settings)) }
    val connState by deps.ws.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(connState) {
        if (connState == ConnState.AUTH_FAILED) {
            deps.ws.stop()
            screen = Screen.Setup
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Setup -> SetupScreen(deps.settings, deps.auth) {
                    screen = Screen.Picker
                }
                Screen.Picker -> EntityPickerScreen(deps.settings, deps.ws) {
                    screen = Screen.Dashboard
                }
                Screen.Dashboard -> {
                    LaunchedEffect(Unit) { deps.ws.start(deps.settings.temperatureEntityId) }
                    val reading by deps.ws.reading.collectAsStateWithLifecycle()
                    DashboardScreen(
                        reading = reading,
                        connState = connState,
                        onChangeSensor = { screen = Screen.Picker },
                        onLogout = {
                            deps.ws.stop()
                            deps.settings.clearAuth()
                            screen = Screen.Setup
                        },
                    )
                }
            }
            KioskOverlays(deps.kioskUi, onWakeTouch = { deps.kiosk.onUserInteraction() })
        }
    }
}
```

- [ ] **Step 6: Replace `MainActivity.kt`**

Full new content of `app/src/main/java/com/rar/echodash/MainActivity.kt`:

```kotlin
package com.rar.echodash

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rar.echodash.vaca.AndroidKioskDevice

class MainActivity : ComponentActivity() {
    private lateinit var deps: AppDeps
    private var attachedHooks: AndroidKioskDevice.WindowHooks? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        deps = (application as EchoDashApplication).deps
        val hooks = object : AndroidKioskDevice.WindowHooks {
            override fun setWindowBrightness(percent: Int) {
                window.attributes = window.attributes.apply {
                    screenBrightness = if (percent < 0) {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    } else {
                        (percent.coerceIn(0, 100) / 100f).coerceAtLeast(0.01f)
                    }
                }
            }

            override fun setKeepScreenOn(on: Boolean) {
                if (on) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        attachedHooks = hooks
        deps.kioskDevice.attach(hooks)
        deps.kiosk.pushToDevice()
        setContent { EchoDashApp(deps) }
    }

    override fun onDestroy() {
        attachedHooks?.let { deps.kioskDevice.detach(it) }
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        deps.kiosk.onUserInteraction()
    }
}
```

- [ ] **Step 7: Run the whole suite and build the APK**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL, all tests green, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: wire VACA server, kiosk overlays, and application singleton"
```

---

### Task 10: README and verification checklist

**Files:**
- Modify: `README.md`

**Interfaces:** none (docs only).

- [ ] **Step 1: Update README.md**

1. Replace the first paragraph's last sentence so the intro reads (keep the rest of the intro paragraph):

```markdown
A native Android kiosk dashboard for an Amazon Echo Show 5 running LineageOS. Logs into Home Assistant via OAuth2 (HA's own login page) and shows a fullscreen dashboard: dusk-gradient background, minute clock, and a live temperature over a reconnecting WebSocket. Speaks the [VACA](https://github.com/msp1974/ViewAssist_Companion_App) device protocol, so the VACA HACS integration gives HA full control of the device — screen, brightness, screensaver, toasts, TTS announcements, and a media player — with native rendering instead of VACA's WebView.
```

2. Replace the "First-run flow" step 2 (the mobile_app registration line) with:

```markdown
2. **Add the device in HA** — install the [VACA integration](https://github.com/msp1974/ViewAssist_Companion_App) via HACS; the Echo is auto-discovered via mDNS (`_vaca._tcp.`, port 10700) under *Settings → Devices & Services*. Manual fallback: add a VACA device with the Echo's IP and port 10700.
```

3. Add a new section after "First-run flow":

```markdown
## HA-side controls (VACA)

Once the VACA integration connects, the device exposes in HA: screen on/off, brightness + auto-brightness (ambient light sensor), always-on, screen timeout, screensaver, dark mode, wake/refresh buttons, toast messages (`action: toast-message`), a media player (URLs/radio via ExoPlayer), and TTS announcements (`assist_satellite.announce`). Voice-pipeline entities (wake word, mic gain, pipeline select, mute) exist but are inert — this device is display-only for now. `assist_satellite.start_conversation` is unsupported (no microphone); plain `announce` works.
```

4. Replace the "On-device verification checklist (not yet run)" section content with:

```markdown
## On-device verification checklist

MVP items verified 2026-07-11: setup → login → dashboard works on the Echo.

- [ ] Keyboard doesn't cover the URL field / HA login form (IME insets under immersive mode)
- [ ] Temperature tracks the selected sensor; toggle Wi-Fi → offline dot appears, last value stays
- [ ] Delete the device in HA while the dashboard is live → app returns to Setup promptly
- [ ] Change sensor → watch for a brief stale value from the old sensor
- [ ] Set as default launcher, reboot → dashboard comes back on its own
- [ ] VACA: integration auto-discovers the Echo; device + entities appear
- [ ] VACA: screen switch, brightness, screensaver, dark mode respond from HA
- [ ] VACA: screen timeout sleeps the screen; touch wakes it and HA's screen switch follows
- [ ] VACA: `assist_satellite.announce` plays through the Echo speaker (media ducks and resumes)
- [ ] VACA: media player plays a radio URL; play/pause/stop/volume track in HA
- [ ] VACA: light sensor entity follows room lighting; auto-brightness adjusts the panel
- [ ] VACA: reboot the Echo → settings restored, HA reconnects within ~10 s
- [ ] Remove the old mobile_app "Echo Dashboard" device entry in HA (superseded by VACA)
```

5. In "Known post-MVP cleanups", delete the line "Hoist `AppDeps` to an Application singleton and release the socket/scope in `onDestroy`" (done in this feature).

- [ ] **Step 2: Final full check**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto && ./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: VACA setup and verification checklist"
```
