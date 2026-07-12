# Cameras Panel + Doorbell Popup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add live camera feeds to the Echo Dashboard — an on-demand Cameras panel plus a doorbell popup that overlays the live feed over any view when a configured doorbell is pressed.

**Architecture:** RTSP-first playback with a single one-step HLS-via-HA fallback, resolved by a pure JVM-testable `StreamResolver` returning a `StreamSource` sealed interface. A pure JVM-testable `DoorbellCoordinator` does rising-edge detection over the existing `subscribe_entities` state map (first-state-never-fires). Thin Android-only Compose files (`CameraPlayer`, `CameraFeed`, `CamerasPanel`, `DoorbellPopupView`) wrap ExoPlayer and just play what the logic tells them. New config fields ride the existing kotlinx JSON pipeline (`ignoreUnknownKeys` + `encodeDefaults`, normalized in `clamped()`).

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (Material3), AndroidX Media3/ExoPlayer 1.4.1 (exoplayer + rtsp + hls + ui), kotlinx.serialization, OkHttp, NanoHTTPD 2.3.1, vanilla-JS config page.

## Global Constraints

- Kotlin 2.1.0, compileSdk 34 — NEVER bump (media3 1.5.x needs compileSdk 35, which this project must not use).
- Media3 pinned exactly **1.4.1** for every media3 artifact. NanoHTTPD 2.3.1.
- New media3 dependencies, all at exactly `1.4.1`: `androidx.media3:media3-exoplayer-rtsp`, `androidx.media3:media3-exoplayer-hls`, and `androidx.media3:media3-ui` (the last is required because the plan uses `PlayerView`/`AspectRatioFrameLayout`; the spec named only the first two — see Interpretations at the end of this plan).
- Tests: plain-JVM JUnit4 only — no Robolectric, no androidText. All pure logic (config clamping, stream resolution, HLS URL construction, rising-edge/popup state machine) lives in files free of Android imports; ExoPlayer/`PlayerView`/Compose stays in thin Android-only files excluded from JVM tests.
- Build/test command (run from repo root): `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
- TDD: each task with pure logic = write failing test → run (fails) → implement → run (passes) → commit. Android-only UI tasks have no JVM test; their gate is a clean `:app:assembleDebug`.
- Follow existing code style: KDoc on non-obvious public functions; comments only for constraints the code can't express. Web config is a single self-contained `app.js` — extend it in place using its existing helper functions (`el`, `subhead`, `entityPicker`, `labeledRow`, `reorderButtons`, `numberInput`).
- HLS URL construction mirrors `AndroidPhotoDownloader`: `baseUrl.trimEnd('/') + relativePath`, fetched with **no** Authorization header (the signed query param authenticates).
- A camera is valid with only an `rtspUrl` (no HA entity). A `camera/stream` request failure or a missing `url` key maps to Unavailable/fallback — never a crash.
- Commit messages: conventional (`feat:`/`test:`/`docs:`), no trailers.

---

### Task 1: Config model — camera/doorbell fields, clamping, referenced ids

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
- Test: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`

**Interfaces:**
- Produces:
  - `data class CameraConfig(val name: String = "", val entity: String? = null, val rtspUrl: String? = null)`
  - `data class DoorbellConfig(val trigger: String? = null, val camera: String = "")`
  - `Entities.cameras: List<CameraConfig>`, `Entities.doorbells: List<DoorbellConfig>`
  - `PanelOptions.doorbellPopupSeconds: Int` (clamped 5–120)
  - `Panels.cameras: PanelConfig` (default `PanelConfig(false, 6)`)
  - `DashConfig.clamped()` normalizes all of the above; `DashConfig.referencedEntityIds()` includes camera entities then doorbell triggers.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` (inside the existing `class DashConfigTest`):

```kotlin
    @Test
    fun roundTripsCamerasAndDoorbells() {
        val cfg = DashConfig(
            entities = Entities(
                cameras = listOf(
                    CameraConfig(name = "Front Door", entity = "camera.front_door_fluent",
                        rtspUrl = "rtsp://frigate:8554/front_door_bell"),
                    CameraConfig(name = "Printer", entity = "camera.p1s"),
                ),
                doorbells = listOf(DoorbellConfig(trigger = "binary_sensor.front_door_visitor", camera = "Front Door")),
            ),
            panelOptions = PanelOptions(doorbellPopupSeconds = 45),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
    }

    @Test
    fun camerasPanelDefaultsDisabledAtOrderSix() {
        val cfg = DashConfig()
        assertEquals(false, cfg.panels.cameras.enabled)
        assertEquals(6, cfg.panels.cameras.order)
        assertEquals(30, cfg.panelOptions.doorbellPopupSeconds) // default
    }

    @Test
    fun clampedNormalizesCameras() {
        val cfg = DashConfig(
            entities = Entities(
                cameras = listOf(
                    CameraConfig(name = "  Front Door  ", entity = "  camera.fd  ", rtspUrl = "  "),
                    CameraConfig(name = "RtspOnly", entity = null, rtspUrl = " rtsp://h/x "),
                    CameraConfig(name = "  ", entity = "camera.blankname"), // blank name -> dropped
                    CameraConfig(name = "NoStream", entity = "", rtspUrl = ""), // no entity/url -> dropped
                ),
            ),
        ).clamped()
        assertEquals(2, cfg.entities.cameras.size)
        assertEquals(CameraConfig("Front Door", "camera.fd", null), cfg.entities.cameras[0])
        assertEquals(CameraConfig("RtspOnly", null, "rtsp://h/x"), cfg.entities.cameras[1])
    }

    @Test
    fun clampedDropsDoorbellsWithBlankTriggerOrUnknownCamera() {
        val cfg = DashConfig(
            entities = Entities(
                cameras = listOf(CameraConfig(name = "Front Door", rtspUrl = "rtsp://h/fd")),
                doorbells = listOf(
                    DoorbellConfig(trigger = " binary_sensor.v ", camera = " Front Door "), // trimmed, kept
                    DoorbellConfig(trigger = "  ", camera = "Front Door"),                   // blank trigger -> dropped
                    DoorbellConfig(trigger = "binary_sensor.x", camera = "Ghost"),           // unknown camera -> dropped
                ),
            ),
        ).clamped()
        assertEquals(listOf(DoorbellConfig("binary_sensor.v", "Front Door")), cfg.entities.doorbells)
    }

    @Test
    fun clampedCoercesDoorbellPopupSeconds() {
        assertEquals(120, DashConfig(panelOptions = PanelOptions(doorbellPopupSeconds = 999)).clamped().panelOptions.doorbellPopupSeconds)
        assertEquals(5, DashConfig(panelOptions = PanelOptions(doorbellPopupSeconds = 1)).clamped().panelOptions.doorbellPopupSeconds)
    }

    @Test
    fun referencedEntityIdsIncludesCameraEntitiesAndDoorbellTriggers() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                cameras = listOf(
                    CameraConfig(name = "Front Door", entity = "camera.fd", rtspUrl = "rtsp://h/fd"),
                    CameraConfig(name = "RtspOnly", rtspUrl = "rtsp://h/x"), // no entity -> contributes nothing
                ),
                doorbells = listOf(DoorbellConfig(trigger = "binary_sensor.v", camera = "Front Door")),
            ),
        )
        assertEquals(listOf("sensor.t", "camera.fd", "binary_sensor.v"), cfg.referencedEntityIds())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`
Expected: FAIL — compilation error, unresolved references `CameraConfig`, `DoorbellConfig`, `Entities.cameras`, `PanelOptions.doorbellPopupSeconds`, `Panels.cameras`.

- [ ] **Step 3: Add the model types and fields**

In `app/src/main/java/com/rar/echodash/config/DashConfig.kt`, add `cameras` to the `Panels` data class:

```kotlin
@Serializable
data class Panels(
    val lights: PanelConfig = PanelConfig(true, 1),
    val climate: PanelConfig = PanelConfig(true, 2),
    val media: PanelConfig = PanelConfig(true, 3),
    val weather: PanelConfig = PanelConfig(true, 4),
    val solar: PanelConfig = PanelConfig(true, 5),
    val cameras: PanelConfig = PanelConfig(false, 6),
)
```

Add the two new serializable types just above the `Entities` data class:

```kotlin
/** A configured camera. Valid with an [rtspUrl] alone (raw go2rtc stream HA doesn't know) or an
 * [entity] alone (HLS-via-HA). [name] is the display name and the key doorbells reference. */
@Serializable
data class CameraConfig(
    val name: String = "",
    val entity: String? = null,
    val rtspUrl: String? = null,
)

/** A doorbell: [trigger] (binary_sensor.*/event.*) whose press shows the [camera] (a CameraConfig.name). */
@Serializable
data class DoorbellConfig(
    val trigger: String? = null,
    val camera: String = "",
)
```

Add the two lists to `Entities`:

```kotlin
@Serializable
data class Entities(
    val tempSensor: String? = null,
    val weather: String? = null,
    val aqiSensor: String? = null,
    val climate: List<String> = emptyList(),
    val solar: SolarConfig = SolarConfig(),
    val lightGroups: List<LightGroup> = emptyList(),
    val cameras: List<CameraConfig> = emptyList(),
    val doorbells: List<DoorbellConfig> = emptyList(),
)
```

Add `doorbellPopupSeconds` to `PanelOptions`:

```kotlin
@Serializable
data class PanelOptions(
    val thermostatStep: Double = 0.5,
    val forecastDays: Int = 5,
    val sensorDecimals: Int = 1,
    val doorbellPopupSeconds: Int = 30,
)
```

- [ ] **Step 4: Extend `referencedEntityIds()` and `clamped()`**

In `referencedEntityIds()`, add these two lines just before the closing `}.distinct()`:

```kotlin
        entities.cameras.forEach { c -> c.entity?.let { add(it) } }
        entities.doorbells.forEach { d -> d.trigger?.let { add(it) } }
```

In `clamped()`, inside `entities = entities.copy(...)`, add the camera/doorbell normalization. Replace the existing `entities = entities.copy(` block so it also cleans cameras and doorbells (compute cleaned cameras first so doorbells can be filtered against the surviving names):

```kotlin
    fun clamped(): DashConfig {
        val cleanedCameras = entities.cameras
            .map { c ->
                c.copy(
                    name = c.name.trim(),
                    entity = c.entity?.trim()?.ifBlank { null },
                    rtspUrl = c.rtspUrl?.trim()?.ifBlank { null },
                )
            }
            .filter { it.name.isNotBlank() && (it.entity != null || it.rtspUrl != null) }
        val cameraNames = cleanedCameras.map { it.name }.toSet()
        val cleanedDoorbells = entities.doorbells
            .map { it.copy(trigger = it.trigger?.trim()?.ifBlank { null }, camera = it.camera.trim()) }
            .filter { it.trigger != null && it.camera in cameraNames }
        return copy(
            version = 1,
            entities = entities.copy(
                tempSensor = entities.tempSensor?.trim()?.ifBlank { null },
                weather = entities.weather?.trim()?.ifBlank { null },
                aqiSensor = entities.aqiSensor?.trim()?.ifBlank { null },
                climate = entities.climate.filter { it.isNotBlank() },
                solar = entities.solar.copy(
                    pv = entities.solar.pv?.trim()?.ifBlank { null },
                    load = entities.solar.load?.trim()?.ifBlank { null },
                    grid = entities.solar.grid?.trim()?.ifBlank { null },
                    pvToday = entities.solar.pvToday?.trim()?.ifBlank { null },
                    loadToday = entities.solar.loadToday?.trim()?.ifBlank { null },
                ),
                lightGroups = entities.lightGroups
                    .map { it.copy(entities = it.entities.filter { id -> id.isNotBlank() }) }
                    .filter { it.entities.isNotEmpty() || it.name.isNotBlank() },
                cameras = cleanedCameras,
                doorbells = cleanedDoorbells,
            ),
            home = home.copy(
                idleReturnSeconds = home.idleReturnSeconds.coerceIn(15, 3600),
                photoCacheCap = home.photoCacheCap.coerceIn(5, 500),
                slideshowSeconds = home.slideshowSeconds.coerceIn(10, 3600),
            ),
            panelOptions = panelOptions.copy(
                thermostatStep = panelOptions.thermostatStep.coerceIn(0.1, 5.0),
                forecastDays = panelOptions.forecastDays.coerceIn(1, 5),
                sensorDecimals = panelOptions.sensorDecimals.coerceIn(0, 3),
                doorbellPopupSeconds = panelOptions.doorbellPopupSeconds.coerceIn(5, 120),
            ),
        )
    }
```

Note: this converts `clamped()` from an expression body (`= copy(...)`) to a block body (`{ ... return copy(...) }`). Remove the old `fun clamped(): DashConfig = copy(` expression form entirely.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`
Expected: PASS (all existing and new tests green).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/config/DashConfig.kt app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "feat: add camera and doorbell config model with clamping"
```

---

### Task 2: StreamResolver — RTSP-first with one-step HLS fallback

**Files:**
- Create: `app/src/main/java/com/rar/echodash/camera/StreamResolver.kt`
- Test: `app/src/test/java/com/rar/echodash/camera/StreamResolverTest.kt`

**Interfaces:**
- Consumes: `CameraConfig` (from Task 1).
- Produces:
  - `sealed interface StreamSource { data class Rtsp(val url: String); data class Hls(val url: String); object Unavailable }`
  - `fun hlsUrl(base: String, relative: String): String`
  - `class StreamResolver(requestStream: suspend (entity: String) -> JsonElement?, baseUrl: () -> String?)` with:
    - `suspend fun primary(camera: CameraConfig): StreamSource`
    - `suspend fun fallback(camera: CameraConfig, failed: StreamSource): StreamSource`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/rar/echodash/camera/StreamResolverTest.kt`:

```kotlin
package com.rar.echodash.camera

import com.rar.echodash.config.CameraConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamResolverTest {

    private fun resolver(
        base: String? = "http://ha.local:8123",
        stream: suspend (String) -> JsonElement? = { null },
    ) = StreamResolver(requestStream = stream, baseUrl = { base })

    @Test
    fun primaryPrefersRtspWhenUrlPresent() = runTest {
        val cam = CameraConfig(name = "Front", entity = "camera.fd", rtspUrl = "rtsp://h/fd")
        assertEquals(StreamSource.Rtsp("rtsp://h/fd"), resolver().primary(cam))
    }

    @Test
    fun primaryResolvesHlsWhenNoRtsp() = runTest {
        val cam = CameraConfig(name = "Front", entity = "camera.fd")
        val r = resolver(stream = { Json.parseToJsonElement("""{"url":"/api/hls/abc/master_playlist.m3u8"}""") })
        assertEquals(StreamSource.Hls("http://ha.local:8123/api/hls/abc/master_playlist.m3u8"), r.primary(cam))
    }

    @Test
    fun primaryUnavailableWhenNoRtspAndNoEntity() = runTest {
        assertEquals(StreamSource.Unavailable, resolver().primary(CameraConfig(name = "X", rtspUrl = null)))
    }

    @Test
    fun primaryUnavailableWhenStreamRequestReturnsNull() = runTest {
        val cam = CameraConfig(name = "Front", entity = "camera.fd")
        assertEquals(StreamSource.Unavailable, resolver(stream = { null }).primary(cam))
    }

    @Test
    fun primaryUnavailableWhenStreamResponseMissingUrl() = runTest {
        val cam = CameraConfig(name = "Front", entity = "camera.fd")
        val r = resolver(stream = { Json.parseToJsonElement("""{"nope":true}""") })
        assertEquals(StreamSource.Unavailable, r.primary(cam))
    }

    @Test
    fun primaryUnavailableWhenStreamRequestThrows() = runTest {
        val cam = CameraConfig(name = "Front", entity = "camera.fd")
        val r = resolver(stream = { throw RuntimeException("ws closed") })
        assertEquals(StreamSource.Unavailable, r.primary(cam))
    }

    @Test
    fun fallbackAfterRtspTriesHlsWhenEntitySet() = runTest {
        val cam = CameraConfig(name = "Front", entity = "camera.fd", rtspUrl = "rtsp://h/fd")
        val r = resolver(stream = { Json.parseToJsonElement("""{"url":"/api/hls/tok/master_playlist.m3u8"}""") })
        assertEquals(
            StreamSource.Hls("http://ha.local:8123/api/hls/tok/master_playlist.m3u8"),
            r.fallback(cam, StreamSource.Rtsp("rtsp://h/fd")),
        )
    }

    @Test
    fun fallbackAfterRtspUnavailableWhenNoEntity() = runTest {
        val cam = CameraConfig(name = "Front", rtspUrl = "rtsp://h/fd")
        assertEquals(StreamSource.Unavailable, resolver().fallback(cam, StreamSource.Rtsp("rtsp://h/fd")))
    }

    @Test
    fun fallbackAfterHlsIsTerminalUnavailable() = runTest {
        val cam = CameraConfig(name = "Front", entity = "camera.fd")
        val r = resolver(stream = { Json.parseToJsonElement("""{"url":"/api/hls/x.m3u8"}""") })
        assertEquals(StreamSource.Unavailable, r.fallback(cam, StreamSource.Hls("http://ha.local:8123/api/hls/x.m3u8")))
    }

    @Test
    fun hlsUrlAppendsSignedRelativePathToTrimmedBase() {
        assertEquals("http://ha.local:8123/api/hls/x.m3u8", hlsUrl("http://ha.local:8123/", "/api/hls/x.m3u8"))
        assertEquals("http://ha.local:8123/api/hls/x.m3u8", hlsUrl("http://ha.local:8123", "/api/hls/x.m3u8"))
    }

    @Test
    fun hlsUrlPassesThroughAbsoluteUrl() {
        assertEquals("http://cdn/x.m3u8", hlsUrl("http://ha.local:8123", "http://cdn/x.m3u8"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.camera.StreamResolverTest"`
Expected: FAIL — unresolved references `StreamResolver`, `StreamSource`, `hlsUrl`.

- [ ] **Step 3: Implement StreamResolver**

Create `app/src/main/java/com/rar/echodash/camera/StreamResolver.kt`:

```kotlin
package com.rar.echodash.camera

import com.rar.echodash.config.CameraConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A resolved, ready-to-play stream for a camera. */
sealed interface StreamSource {
    /** Direct RTSP restream (Frigate/go2rtc); sub-second, hardware decode, bypasses HA. */
    data class Rtsp(val url: String) : StreamSource

    /** HLS via HA — an absolute, already-signed URL fetched with no Authorization header. */
    data class Hls(val url: String) : StreamSource

    /** No playable stream. */
    object Unavailable : StreamSource
}

/** Build the HLS URL the way AndroidPhotoDownloader does: signed relative path appended to the base. */
fun hlsUrl(base: String, relative: String): String =
    if (relative.startsWith("/")) base.trimEnd('/') + relative else relative

/**
 * Chooses a [StreamSource] for a camera. RTSP-first; a single HLS-via-HA fallback step; then
 * Unavailable. No retry loops — one fallback per playback attempt, then the error overlay's Retry
 * restarts from [primary]. [requestStream] performs the `camera/stream` WS request; a null result,
 * a thrown error, or a missing `url` key all resolve to Unavailable/fallback — never a crash.
 */
class StreamResolver(
    private val requestStream: suspend (entity: String) -> JsonElement?,
    private val baseUrl: () -> String?,
) {
    suspend fun primary(camera: CameraConfig): StreamSource = when {
        camera.rtspUrl != null -> StreamSource.Rtsp(camera.rtspUrl)
        camera.entity != null -> resolveHls(camera.entity)
        else -> StreamSource.Unavailable
    }

    suspend fun fallback(camera: CameraConfig, failed: StreamSource): StreamSource = when (failed) {
        is StreamSource.Rtsp -> camera.entity?.let { resolveHls(it) } ?: StreamSource.Unavailable
        else -> StreamSource.Unavailable
    }

    private suspend fun resolveHls(entity: String): StreamSource {
        val base = baseUrl() ?: return StreamSource.Unavailable
        val result = runCatching { requestStream(entity) }.getOrNull() as? JsonObject
            ?: return StreamSource.Unavailable
        val rel = (result["url"] as? JsonPrimitive)?.contentOrNull ?: return StreamSource.Unavailable
        return StreamSource.Hls(hlsUrl(base, rel))
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.camera.StreamResolverTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/camera/StreamResolver.kt app/src/test/java/com/rar/echodash/camera/StreamResolverTest.kt
git commit -m "feat: add StreamResolver with RTSP-first HLS fallback"
```

---

### Task 3: DoorbellCoordinator — rising-edge state machine

**Files:**
- Create: `app/src/main/java/com/rar/echodash/camera/DoorbellCoordinator.kt`
- Test: `app/src/test/java/com/rar/echodash/camera/DoorbellCoordinatorTest.kt`

**Interfaces:**
- Consumes: `DoorbellConfig` (Task 1), `com.rar.echodash.ha.EntityState`.
- Produces:
  - `sealed interface PopupCommand { data class Show(val cameraName: String, val untilMs: Long) : PopupCommand }`
  - `data class DoorbellPopup(val cameraName: String, val untilMs: Long)`
  - `class DoorbellCoordinator` with `fun onStates(doorbells: List<DoorbellConfig>, states: Map<String, EntityState>, popupSeconds: Int, nowMs: Long): PopupCommand?`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/rar/echodash/camera/DoorbellCoordinatorTest.kt`:

```kotlin
package com.rar.echodash.camera

import com.rar.echodash.config.DoorbellConfig
import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoorbellCoordinatorTest {

    private fun st(id: String, state: String) = EntityState(id, state, JsonObject(emptyMap()), 0L)
    private val doorbells = listOf(DoorbellConfig(trigger = "binary_sensor.front_visitor", camera = "Front Door"))

    @Test
    fun firstStateSeenNeverFires() {
        val c = DoorbellCoordinator()
        assertNull(c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 1_000L))
    }

    @Test
    fun offToOnFires() {
        val c = DoorbellCoordinator()
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 0L)
        val cmd = c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 1_000L)
        assertEquals(PopupCommand.Show("Front Door", 1_000L + 30_000L), cmd)
    }

    @Test
    fun onToOnAndOffToOffDoNotFire() {
        val c = DoorbellCoordinator()
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 0L)
        assertNull(c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 1_000L))
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 2_000L)
        assertNull(c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 3_000L))
    }

    @Test
    fun reTriggerFiresAgainWithExtendedUntil() {
        val c = DoorbellCoordinator()
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 0L)
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 1_000L)
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 2_000L)
        val cmd = c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 5_000L)
        assertEquals(PopupCommand.Show("Front Door", 5_000L + 30_000L), cmd)
    }

    @Test
    fun secondDoorbellSwitchesCamera() {
        val two = listOf(
            DoorbellConfig(trigger = "binary_sensor.front_visitor", camera = "Front Door"),
            DoorbellConfig(trigger = "binary_sensor.back_visitor", camera = "Back Door"),
        )
        val c = DoorbellCoordinator()
        val states0 = mapOf(
            "binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off"),
            "binary_sensor.back_visitor" to st("binary_sensor.back_visitor", "off"),
        )
        c.onStates(two, states0, 30, 0L)
        val back = c.onStates(two, mapOf(
            "binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off"),
            "binary_sensor.back_visitor" to st("binary_sensor.back_visitor", "on"),
        ), 30, 4_000L)
        assertEquals(PopupCommand.Show("Back Door", 4_000L + 30_000L), back)
    }

    @Test
    fun eventEntityFiresOnAnyStateChangeAfterFirstSeen() {
        val ev = listOf(DoorbellConfig(trigger = "event.doorbell_press", camera = "Front Door"))
        val c = DoorbellCoordinator()
        c.onStates(ev, mapOf("event.doorbell_press" to st("event.doorbell_press", "2026-07-12T10:00:00Z")), 30, 0L)
        val cmd = c.onStates(ev, mapOf("event.doorbell_press" to st("event.doorbell_press", "2026-07-12T10:05:00Z")), 30, 1_000L)
        assertEquals(PopupCommand.Show("Front Door", 1_000L + 30_000L), cmd)
    }

    @Test
    fun clearingStatesResetsFirstSeenSoReconnectDoesNotFire() {
        val c = DoorbellCoordinator()
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 0L)
        c.onStates(doorbells, emptyMap(), 30, 1_000L) // reconnect clears the map
        // First state after resubscribe is "on" but must be recorded, not fired.
        assertNull(c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 2_000L))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.camera.DoorbellCoordinatorTest"`
Expected: FAIL — unresolved references `DoorbellCoordinator`, `PopupCommand`.

- [ ] **Step 3: Implement DoorbellCoordinator**

Create `app/src/main/java/com/rar/echodash/camera/DoorbellCoordinator.kt`:

```kotlin
package com.rar.echodash.camera

import com.rar.echodash.config.DoorbellConfig
import com.rar.echodash.ha.EntityState

/** What the coordinator asks the UI to do. */
sealed interface PopupCommand {
    /** Show [cameraName] until [untilMs] (epoch millis). A later Show extends/switches the popup. */
    data class Show(val cameraName: String, val untilMs: Long) : PopupCommand
}

/** UI-side popup state; identical fields to [PopupCommand.Show], kept separate as the render model. */
data class DoorbellPopup(val cameraName: String, val untilMs: Long)

/**
 * Pure rising-edge detector over the subscribe_entities state map. Fed the full state map on every
 * update. A popup fires only on an observed transition of a configured trigger:
 * - binary_sensor.*: off -> on (any non-"on" -> "on").
 * - event.*: any state change (the state is a timestamp that changes per fire).
 * The first state seen for a trigger is recorded but never fires (no phantom popup at app start).
 * When a trigger disappears from the map (reconnect clears it), its remembered state is dropped, so
 * its next appearance is again a first-seen and cannot fire.
 */
class DoorbellCoordinator {
    private val seen = HashMap<String, String>()

    fun onStates(
        doorbells: List<DoorbellConfig>,
        states: Map<String, EntityState>,
        popupSeconds: Int,
        nowMs: Long,
    ): PopupCommand? {
        // Drop remembered triggers no longer present (handles the reconnect emptyMap reset).
        seen.keys.retainAll { states.containsKey(it) }

        var command: PopupCommand? = null
        for (db in doorbells) {
            val trigger = db.trigger ?: continue
            val current = states[trigger]?.state ?: continue
            val prev = seen[trigger]
            seen[trigger] = current
            if (prev == null) continue // first-seen: record only
            val rising =
                if (trigger.substringBefore('.') == "event") current != prev
                else current == "on" && prev != "on"
            if (rising && command == null) {
                command = PopupCommand.Show(db.camera, nowMs + popupSeconds * 1000L)
            }
        }
        return command
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.camera.DoorbellCoordinatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/camera/DoorbellCoordinator.kt app/src/test/java/com/rar/echodash/camera/DoorbellCoordinatorTest.kt
git commit -m "feat: add DoorbellCoordinator rising-edge state machine"
```

---

### Task 4: Playback infrastructure — media3 deps, camera/stream request, CameraPlayer + CameraFeed

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/rar/echodash/ha/EntityHub.kt`
- Create: `app/src/main/java/com/rar/echodash/camera/CameraPlayer.kt`

**Interfaces:**
- Consumes: `StreamSource`, `StreamResolver` (Task 2), `CameraConfig` (Task 1).
- Produces:
  - `EntityHub.cameraStream(entityId: String): JsonElement?` (suspend) — the `camera/stream` WS request.
  - `@Composable fun CameraPlayer(source: StreamSource, muted: Boolean, modifier: Modifier, onError: () -> Unit)`
  - `@Composable fun CameraFeed(camera: CameraConfig, resolver: StreamResolver, muted: Boolean, modifier: Modifier)` — resolves primary → one fallback on error → error overlay with Retry.

This task is Android-only; its gate is a clean `:app:assembleDebug` (there is no JVM unit test for ExoPlayer/Compose per the repo rule).

- [ ] **Step 1: Add media3 dependencies**

In `app/build.gradle.kts`, add three lines to `dependencies` immediately after the existing `androidx.media3:media3-exoplayer:1.4.1` line:

```kotlin
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
```

- [ ] **Step 2: Add the camera/stream request to EntityHub**

In `app/src/main/java/com/rar/echodash/ha/EntityHub.kt`, add this method just after `getForecasts(...)` (before the closing brace of the class):

```kotlin
    /** Ask HA to prepare an HLS stream. Returns e.g. {"url":"/api/hls/<token>/master_playlist.m3u8"}
     * or null on any failure — the StreamResolver maps null/missing url to Unavailable. */
    suspend fun cameraStream(entityId: String): JsonElement? =
        runCatching {
            client.request("camera/stream", buildJsonObject { put("entity_id", entityId) })
        }.getOrNull()
```

(`JsonElement`, `buildJsonObject`, and `put` are already imported in this file.)

- [ ] **Step 3: Implement CameraPlayer and CameraFeed**

Create `app/src/main/java/com/rar/echodash/camera/CameraPlayer.kt`:

```kotlin
package com.rar.echodash.camera

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.rar.echodash.config.CameraConfig
import kotlinx.coroutines.launch

/** The dusk letterbox background used behind every feed and error overlay. */
private val DUSK = Color(0xFF12141C)

/**
 * Plays a single [StreamSource] in one ExoPlayer instance wrapped in a letterboxed PlayerView.
 * The player is created once and released in onDispose; a source change re-prepares the same
 * instance. All playback decisions (which URL, fallback, mute) live in the caller — this composable
 * just plays what it is told and reports errors via [onError].
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraPlayer(
    source: StreamSource,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    val latestOnError by rememberUpdatedState(onError)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = latestOnError()
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val mediaSource = remember(source) { buildMediaSource(source) }
    LaunchedEffect(mediaSource) {
        mediaSource?.let {
            player.setMediaSource(it)
            player.prepare()
        }
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    AndroidView(
        modifier = modifier.fillMaxSize().background(DUSK),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(DUSK.toArgb())
            }
        },
        update = { it.player = player },
    )
}

@OptIn(UnstableApi::class)
private fun buildMediaSource(source: StreamSource): MediaSource? = when (source) {
    is StreamSource.Rtsp ->
        RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(source.url))
    is StreamSource.Hls ->
        HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(MediaItem.fromUri(source.url))
    StreamSource.Unavailable -> null
}

/**
 * Orchestrates playback for one [camera]: resolves the primary source, does a single fallback step
 * on the first playback error, and shows an error overlay (with Retry) when the stream is
 * Unavailable. Callers key this by camera identity so switching cameras disposes the old player.
 */
@Composable
fun CameraFeed(
    camera: CameraConfig,
    resolver: StreamResolver,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    var attempt by remember(camera) { mutableIntStateOf(0) }
    var source by remember(camera) { mutableStateOf<StreamSource?>(null) }
    var usedFallback by remember(camera) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(camera, attempt) {
        usedFallback = false
        source = resolver.primary(camera)
    }

    Box(modifier.fillMaxSize().background(DUSK), contentAlignment = Alignment.Center) {
        when (val s = source) {
            null -> {} // resolving; dusk background only
            StreamSource.Unavailable -> StreamUnavailable(camera.name) { attempt++ }
            else -> CameraPlayer(
                source = s,
                muted = muted,
                onError = {
                    if (!usedFallback) {
                        usedFallback = true
                        scope.launch { source = resolver.fallback(camera, s) }
                    } else {
                        source = StreamSource.Unavailable
                    }
                },
            )
        }
    }
}

/** Error overlay: camera name + "stream unavailable" on the dusk background, tap to retry. */
@Composable
private fun StreamUnavailable(cameraName: String, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(DUSK),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cameraName, color = Color.White, textAlign = TextAlign.Center)
            Text("stream unavailable", color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("Retry", color = Color(0xFF7FB2E5))
            }
        }
    }
}
```

- [ ] **Step 4: Verify the build compiles**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:assembleDebug`
Expected: BUILD SUCCESSFUL (media3-rtsp/hls/ui resolve; CameraPlayer/CameraFeed compile).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/rar/echodash/ha/EntityHub.kt app/src/main/java/com/rar/echodash/camera/CameraPlayer.kt
git commit -m "feat: add ExoPlayer camera playback with media3 rtsp/hls"
```

---

### Task 5: Rail wiring — DashView.CAMERAS, icon, gated railViews

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/DashViews.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/DashViewsTest.kt`

**Interfaces:**
- Consumes: `Panels` (with `cameras` from Task 1).
- Produces:
  - `DashView.CAMERAS` enum entry.
  - `railIcon(DashView.CAMERAS)` → `Icons.Outlined.Videocam`.
  - `railViews(panels: Panels, camerasConfigured: Boolean = false): List<DashView>` — includes CAMERAS only when `panels.cameras.enabled && camerasConfigured`.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/java/com/rar/echodash/ui/DashViewsTest.kt` (inside `class DashViewsTest`):

```kotlin
    @Test
    fun railViewsIncludesCamerasOnlyWhenEnabledAndConfigured() {
        val enabled = Panels(cameras = PanelConfig(true, 6))
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.CLIMATE, DashView.MEDIA,
                DashView.WEATHER, DashView.SOLAR, DashView.CAMERAS),
            railViews(enabled, camerasConfigured = true),
        )
        // Enabled but no cameras configured -> excluded.
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.CLIMATE, DashView.MEDIA,
                DashView.WEATHER, DashView.SOLAR),
            railViews(enabled, camerasConfigured = false),
        )
    }

    @Test
    fun railViewsExcludesDisabledCamerasEvenWhenConfigured() {
        val disabled = Panels(cameras = PanelConfig(false, 6))
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.CLIMATE, DashView.MEDIA,
                DashView.WEATHER, DashView.SOLAR),
            railViews(disabled, camerasConfigured = true),
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.DashViewsTest"`
Expected: FAIL — unresolved `DashView.CAMERAS`; `railViews` has no `camerasConfigured` parameter.

- [ ] **Step 3: Add the enum entry, icon, and gated railViews**

In `app/src/main/java/com/rar/echodash/ui/DashViews.kt`:

Add the `Videocam` import alongside the other `androidx.compose.material.icons.outlined.*` imports:

```kotlin
import androidx.compose.material.icons.outlined.Videocam
```

Add `CAMERAS` to the enum:

```kotlin
/** The rail destinations, top-to-bottom. */
enum class DashView { HOME, LIGHTS, CLIMATE, MEDIA, WEATHER, SOLAR, CAMERAS }
```

Add the icon mapping to `railIcon`:

```kotlin
fun railIcon(view: DashView): ImageVector = when (view) {
    DashView.HOME -> Icons.Outlined.Home
    DashView.LIGHTS -> Icons.Outlined.Lightbulb
    DashView.CLIMATE -> Icons.Outlined.Thermostat
    DashView.MEDIA -> Icons.Outlined.MusicNote
    DashView.WEATHER -> Icons.Outlined.WbCloudy
    DashView.SOLAR -> Icons.Outlined.SolarPower
    DashView.CAMERAS -> Icons.Outlined.Videocam
}
```

Replace `railViews` with the gated version:

```kotlin
/** The rail destinations: HOME first, then enabled panels ordered by their configured `order`.
 * Cameras appears only when its panel is enabled AND at least one camera is configured. */
fun railViews(panels: Panels, camerasConfigured: Boolean = false): List<DashView> {
    val configured = listOf(
        DashView.LIGHTS to panels.lights,
        DashView.CLIMATE to panels.climate,
        DashView.MEDIA to panels.media,
        DashView.WEATHER to panels.weather,
        DashView.SOLAR to panels.solar,
        DashView.CAMERAS to panels.cameras,
    ).filter { (view, cfg) ->
        cfg.enabled && (view != DashView.CAMERAS || camerasConfigured)
    }.sortedBy { it.second.order }.map { it.first }
    return listOf(DashView.HOME) + configured
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.DashViewsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/DashViews.kt app/src/test/java/com/rar/echodash/ui/DashViewsTest.kt
git commit -m "feat: add Cameras rail destination gated on config"
```

---

### Task 6: Cameras panel + DashboardShell wiring + StreamResolver in AppDeps

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/panels/CamerasPanel.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt`

**Interfaces:**
- Consumes: `CameraFeed` (Task 4), `StreamResolver` (Task 2), `CameraConfig` (Task 1), `railViews(panels, camerasConfigured)` (Task 5).
- Produces:
  - `@Composable fun CamerasPanel(cameras: List<CameraConfig>, resolver: StreamResolver)`
  - `AppDeps.streamResolver: StreamResolver`
  - `DashboardShell(..., streamResolver: StreamResolver)` new trailing param and a `DashView.CAMERAS` branch.

Android-only; gate is a clean `:app:assembleDebug`.

- [ ] **Step 1: Implement CamerasPanel**

Create `app/src/main/java/com/rar/echodash/ui/panels/CamerasPanel.kt`:

```kotlin
package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.camera.CameraFeed
import com.rar.echodash.camera.StreamResolver
import com.rar.echodash.config.CameraConfig

/**
 * On-demand camera viewer: a fixed-width selector column on the left, the live feed filling the
 * rest. First camera auto-selected on entry; muted by default with a corner unmute toggle.
 * Switching cameras (via `key`) disposes the old player before starting the next; leaving the
 * panel disposes CameraFeed and releases the player.
 */
@Composable
fun CamerasPanel(cameras: List<CameraConfig>, resolver: StreamResolver) {
    if (cameras.isEmpty()) {
        EmptyHint("Add a camera in the web config")
        return
    }
    var selected by remember(cameras) { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(true) }
    val current = cameras[selected.coerceIn(0, cameras.lastIndex)]

    Row(
        Modifier.fillMaxSize().background(Color(0xFF12141C)).padding(end = 84.dp),
    ) {
        Column(
            Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cameras.forEachIndexed { i, cam ->
                val isSel = i == selected
                Text(
                    cam.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) Color(0xFF3A6EA5) else Color(0xFF232733))
                        .clickable { selected = i }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            key(current.name) {
                CameraFeed(current, resolver, muted, Modifier.fillMaxSize())
            }
            IconButton(
                onClick = { muted = !muted },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = Color.White,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Wire CamerasPanel into DashboardShell**

In `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`:

Add imports:

```kotlin
import com.rar.echodash.camera.StreamResolver
import com.rar.echodash.ui.panels.CamerasPanel
```

Add a `streamResolver` parameter to `DashboardShell` (append after `onInteraction: () -> Unit,`):

```kotlin
    onInteraction: () -> Unit,
    streamResolver: StreamResolver,
) {
```

Update the `views` line to gate cameras on configuration:

```kotlin
    val views = remember(config.panels, config.entities.cameras) {
        railViews(config.panels, config.entities.cameras.isNotEmpty())
    }
```

Add the `CAMERAS` branch inside the `when (view)` block, after the `DashView.SOLAR` branch:

```kotlin
                DashView.CAMERAS -> CamerasPanel(config.entities.cameras, streamResolver)
```

- [ ] **Step 3: Construct the resolver in AppDeps and pass it down**

In `app/src/main/java/com/rar/echodash/App.kt`:

Add the import:

```kotlin
import com.rar.echodash.camera.StreamResolver
```

In `class AppDeps`, add the resolver just after the `entityHub` declaration:

```kotlin
    val streamResolver = StreamResolver(
        requestStream = { entityId -> entityHub.cameraStream(entityId) },
        baseUrl = { settings.baseUrl },
    )
```

In the `DashboardShell(...)` call, add the new argument at the end (after `onInteraction = { ... },`):

```kotlin
                        streamResolver = deps.streamResolver,
```

- [ ] **Step 4: Verify the build compiles**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Also run the full JVM suite to confirm nothing regressed: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest` → all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/panels/CamerasPanel.kt app/src/main/java/com/rar/echodash/ui/DashboardShell.kt app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat: add Cameras panel with muted-by-default live feed"
```

---

### Task 7: Doorbell popup overlay + coordinator wiring + screen wake

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/DoorbellPopupView.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt`

**Interfaces:**
- Consumes: `DoorbellCoordinator`, `PopupCommand`, `DoorbellPopup` (Task 3), `StreamResolver`, `CameraFeed` (Tasks 2/4), `CameraConfig` (Task 1).
- Produces: `@Composable fun DoorbellPopupView(popup: DoorbellPopup, camera: CameraConfig?, resolver: StreamResolver, onDismiss: () -> Unit)` and its wiring in `EchoDashApp`.

Android-only; gate is a clean `:app:assembleDebug`.

- [ ] **Step 1: Implement DoorbellPopupView**

Create `app/src/main/java/com/rar/echodash/ui/DoorbellPopupView.kt`:

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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.camera.CameraFeed
import com.rar.echodash.camera.DoorbellPopup
import com.rar.echodash.camera.StreamResolver
import com.rar.echodash.config.CameraConfig
import kotlinx.coroutines.delay

/**
 * Full-screen doorbell overlay above everything (including the rail). Shows the mapped camera's live
 * feed unmuted, labeled with a countdown. Tap anywhere to dismiss; auto-dismiss at [DoorbellPopup.untilMs].
 * A new [popup] value (re-trigger extends, other doorbell switches) restarts the countdown. If the
 * stream is dead, CameraFeed's own error overlay shows underneath — the ring notice is never lost.
 */
@Composable
fun DoorbellPopupView(
    popup: DoorbellPopup,
    camera: CameraConfig?,
    resolver: StreamResolver,
    onDismiss: () -> Unit,
) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    var remaining by remember(popup) {
        mutableIntStateOf(((popup.untilMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0))
    }
    LaunchedEffect(popup) {
        while (true) {
            val secs = ((popup.untilMs - System.currentTimeMillis()) / 1000L).toInt()
            remaining = secs.coerceAtLeast(0)
            if (secs <= 0) {
                latestDismiss()
                break
            }
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(popup) { detectTapGestures { latestDismiss() } },
    ) {
        if (camera != null) {
            key(popup.cameraName) {
                CameraFeed(camera, resolver, muted = false, modifier = Modifier.fillMaxSize())
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF12141C)), contentAlignment = Alignment.Center) {
                Text("${popup.cameraName}\nstream unavailable", color = Color.White)
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xCC000000),
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) {
            Text(
                "${popup.cameraName}  ·  ${remaining}s",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
```

- [ ] **Step 2: Wire the coordinator and overlay into EchoDashApp**

In `app/src/main/java/com/rar/echodash/App.kt`:

Add imports:

```kotlin
import com.rar.echodash.camera.DoorbellCoordinator
import com.rar.echodash.camera.DoorbellPopup
import com.rar.echodash.camera.PopupCommand
import com.rar.echodash.ui.DoorbellPopupView
```

In the `Screen.Dashboard` branch, add the coordinator and popup state next to the other `remember`s (e.g. just after `val idleTimer = remember(idleSeconds) { ... }` and its `DisposableEffect`/`LaunchedEffect`):

```kotlin
                    val doorbellCoordinator = remember { DoorbellCoordinator() }
                    var doorbellPopup by remember { mutableStateOf<DoorbellPopup?>(null) }
                    LaunchedEffect(entities, config.entities.doorbells, config.panelOptions.doorbellPopupSeconds) {
                        val cmd = doorbellCoordinator.onStates(
                            config.entities.doorbells,
                            entities,
                            config.panelOptions.doorbellPopupSeconds,
                            System.currentTimeMillis(),
                        )
                        if (cmd is PopupCommand.Show) {
                            doorbellPopup = DoorbellPopup(cmd.cameraName, cmd.untilMs)
                            deps.kiosk.onUserInteraction() // force the screen on for the ring
                            idleTimer.onInteraction()      // popup counts as activity; don't race idle-return
                        }
                    }
```

Render the overlay above `DashboardShell` but keep `KioskOverlays` outermost. Inside the top-level `Box(Modifier.fillMaxSize()) { ... }` (the one wrapping the `when (screen)`), the `Screen.Dashboard` block already renders `DashboardShell(...)`. Immediately after the `DashboardShell(...)` call (still inside the `Screen.Dashboard` block), add:

```kotlin
                    doorbellPopup?.let { popup ->
                        DoorbellPopupView(
                            popup = popup,
                            camera = config.entities.cameras.find { it.name == popup.cameraName },
                            resolver = deps.streamResolver,
                            onDismiss = { doorbellPopup = null },
                        )
                    }
```

(`KioskOverlays(deps.kioskUi, ...)` remains the last child of the outer `Box`, so a screen-off tap overlay still sits above; the popup already forced the screen on via `kiosk.onUserInteraction()`.)

- [ ] **Step 3: Verify the build compiles**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/DoorbellPopupView.kt app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat: add doorbell popup overlay with screen wake"
```

---

### Task 8: Web config page — Cameras + Doorbells editors, panel option, panels toggle

**Files:**
- Modify: `app/src/main/assets/config/app.js`

**Interfaces:**
- Consumes: the config JSON shape `entities.cameras[] = {name, entity, rtspUrl}`, `entities.doorbells[] = {trigger, camera}`, `panelOptions.doorbellPopupSeconds`, `panels.cameras` (served by the device with defaults via `encodeDefaults`).
- Produces: editors for cameras and doorbells, a doorbell-popup number input, and the Cameras entry in the Panels enable/order list.

No JVM test (the JS is not unit-tested); the gate is a clean `:app:assembleDebug` (the asset is bundled) plus the code following the existing helpers exactly. The PUT round-trips through `decodeConfig` → `clamped()` already covered by Task 1's tests.

- [ ] **Step 1: Register the Cameras panel key, label, and icon**

In `app/src/main/assets/config/app.js`, change `PANEL_KEYS` to include `"cameras"`:

```javascript
const PANEL_KEYS = ["lights", "climate", "media", "weather", "solar", "cameras"];
```

Add the label to `PANEL_LABELS`:

```javascript
const PANEL_LABELS = {
  lights: "Lights", climate: "Climate", media: "Media", weather: "Weather", solar: "Solar", cameras: "Cameras",
};
```

Add a `cameras` glyph to the `ICONS` object (a video-camera outline), after the `solar` entry:

```javascript
  cameras: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="6.5" width="12" height="11" rx="2"/><path d="M15 10l6-3v10l-6-3Z"/></svg>',
```

(`renderPanels()` already iterates `PANEL_KEYS`, so the Cameras enable/reorder row appears automatically once the key is present.)

- [ ] **Step 2: Add the Cameras and Doorbells editors to renderEntities**

In `app/src/main/assets/config/app.js`, inside `renderEntities()`, after the light-groups block (after `host.appendChild(addGroup);` and before the function's closing `}`), append:

```javascript
  // cameras
  host.appendChild(subhead("cameras", "Cameras"));
  e.cameras.forEach((c, ci) => host.appendChild(renderCamera(c, ci)));
  const addCam = el("button", "add", "Add camera");
  addCam.type = "button";
  addCam.addEventListener("click", () => { e.cameras.push({ name: "New camera", entity: null, rtspUrl: null }); renderEntities(); });
  host.appendChild(addCam);
  host.appendChild(el("div", "muted",
    "RTSP plays direct from Frigate/go2rtc (rtsp://host:8554/name) for sub-second latency; leave blank to stream through Home Assistant (HLS, ~5–10 s behind). Tip: prefer sub/fluent streams — the screen is 960×480."));

  // doorbells
  host.appendChild(subhead("cameras", "Doorbells"));
  e.doorbells.forEach((d, di) => host.appendChild(renderDoorbell(d, di)));
  const addDb = el("button", "add", "Add doorbell");
  addDb.type = "button";
  addDb.addEventListener("click", () => { e.doorbells.push({ trigger: null, camera: "" }); renderEntities(); });
  host.appendChild(addDb);
```

Then add the two render helpers. Place them immediately after `renderLightGroup(...)` (before `function numberInput(...)`):

```javascript
function renderCamera(c, ci) {
  const cams = config.entities.cameras;
  const box = el("div", "group");
  const head = el("div", "group-head");
  const name = el("input"); name.value = c.name; name.setAttribute("aria-label", "Camera name");
  name.addEventListener("change", () => c.name = name.value.trim());
  head.appendChild(name);
  head.appendChild(reorderButtons(
    ci !== 0, ci !== cams.length - 1,
    () => { const t = cams[ci]; cams[ci] = cams[ci - 1]; cams[ci - 1] = t; renderEntities(); },
    () => { const t = cams[ci]; cams[ci] = cams[ci + 1]; cams[ci + 1] = t; renderEntities(); },
  ));
  const del = el("button", "ghost small danger", "Delete");
  del.type = "button";
  del.setAttribute("aria-label", "Delete camera");
  del.addEventListener("click", () => { cams.splice(ci, 1); renderEntities(); });
  head.appendChild(del);
  box.appendChild(head);

  box.appendChild(labeledRow("Camera entity", entityPicker(["camera"], c.entity, v => c.entity = v)));
  const rtsp = el("input"); rtsp.value = c.rtspUrl || ""; rtsp.placeholder = "rtsp://host:8554/name";
  rtsp.setAttribute("autocomplete", "off");
  rtsp.addEventListener("change", () => c.rtspUrl = rtsp.value.trim() || null);
  box.appendChild(labeledRow("RTSP URL", rtsp));
  return box;
}

function renderDoorbell(d, di) {
  const dbs = config.entities.doorbells;
  const row = el("div", "row");
  row.appendChild(entityPicker(["binary_sensor", "event"], d.trigger, v => d.trigger = v));
  const sel = el("select");
  const none = el("option", null, "— camera —"); none.value = ""; sel.appendChild(none);
  config.entities.cameras.forEach(c => {
    const o = el("option", null, c.name); o.value = c.name;
    if (d.camera === c.name) o.selected = true;
    sel.appendChild(o);
  });
  sel.addEventListener("change", () => d.camera = sel.value);
  row.appendChild(sel);
  const del = el("button", "ghost small danger", "Remove");
  del.type = "button";
  del.setAttribute("aria-label", "Remove doorbell");
  del.addEventListener("click", () => { dbs.splice(di, 1); renderEntities(); });
  row.appendChild(del);
  return row;
}
```

- [ ] **Step 3: Add the doorbell-popup number input to Panel options**

In `renderOptions()`, add the doorbell-popup input after the "Sensor decimal places" row and update the hint text:

```javascript
  host.appendChild(labeledRow("Sensor decimal places", numberInput(o.sensorDecimals, v => o.sensorDecimals = Math.round(v))));
  host.appendChild(labeledRow("Doorbell popup (s)", numberInput(o.doorbellPopupSeconds, v => o.doorbellPopupSeconds = Math.round(v))));
  host.appendChild(el("div", "muted", "Step 0.1–5.0, forecast 1–5, doorbell popup 5–120 (clamped on save)."));
```

(Replace the existing final `host.appendChild(el("div", "muted", "Step 0.1–5.0, forecast 1–5 (clamped on save)."));` line with the updated hint above so it is not duplicated.)

- [ ] **Step 4: Verify the asset bundles and the app still builds**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:assembleDebug`
Expected: BUILD SUCCESSFUL (the edited `app.js` is packaged as an asset).

Manual smoke check (on device / browser at the config URL, not part of CI): the Panels list shows a Cameras row (default off); the Entities card shows Cameras and Doorbells editors; Panel options shows "Doorbell popup (s)"; Save round-trips (invalid rows/out-of-range popup seconds are dropped/clamped by the server).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/config/app.js
git commit -m "feat: add cameras and doorbells editors to web config"
```

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| `CameraConfig`, `DoorbellConfig`, `Entities.cameras/doorbells` | Task 1 |
| `PanelOptions.doorbellPopupSeconds` (5–120, default 30) | Task 1 |
| `Panels.cameras` default disabled | Task 1 |
| `clamped()` trims/drops invalid camera & doorbell entries | Task 1 |
| `referencedEntityIds()` adds camera entities + doorbell triggers | Task 1 |
| Camera valid with only `rtspUrl` | Task 1 (filter keeps `entity != null || rtspUrl != null`) + Task 2 (`primary` Rtsp branch) |
| `StreamSource` sealed interface | Task 2 |
| `primary()` RTSP-first, HLS via `camera/stream`, else Unavailable | Task 2 |
| `fallback()` one step, no retry loops | Task 2 |
| HLS URL = base.trimEnd('/') + relativePath | Task 2 (`hlsUrl`) |
| Malformed/missing `camera/stream` → Unavailable, never crash | Task 2 (runCatching + null checks) |
| `camera/stream` WS request | Task 4 (`EntityHub.cameraStream`) |
| media3 rtsp + hls at 1.4.1 (+ ui, see Interpretations) | Task 4 |
| CameraPlayer: AndroidView/PlayerView, useController=false, RESIZE_MODE_FIT, dusk letterbox, single instance, release on dispose/switch, mute, onError→fallback | Task 4 (CameraPlayer/CameraFeed) + Task 6 (`key` per camera) |
| `DashView.CAMERAS`, Videocam icon, rail gated on enabled + configured | Task 5 |
| Cameras panel: selector column, feed fills area, first auto-selected, muted default + unmute toggle, teardown on switch/leave | Task 6 |
| DoorbellCoordinator pure state machine, first-state-never-fires, off→on fires, re-trigger extends, other doorbell switches, event-entity changes, reconnect reset | Task 3 |
| `DoorbellPopup(cameraName, untilMs)` | Task 3 |
| Popup overlays everything incl. rail, unmuted, tap/timeout dismiss, error overlay keeps popup | Task 7 |
| Screen wake via existing kiosk plumbing; popup counts as idle activity | Task 7 (`deps.kiosk.onUserInteraction()` + `idleTimer.onInteraction()`) |
| Web: Cameras card, Doorbells card, hint text, popup number input, Panels toggle | Task 8 |
| Tests: DashConfig, StreamResolver, DoorbellCoordinator per repo JVM rule | Tasks 1–3 |

No gaps found. Error-handling table rows all map to Task 2 (resolver Unavailable/fallback) and Task 4/7 (error overlay, popup persists).

**2. Placeholder scan:** No "TBD/TODO/handle edge cases/similar to Task N" placeholders. Every code step shows complete code; every test step shows the actual assertions; every run step shows the exact command and expected outcome.

**3. Type consistency:** `StreamSource` (`.Rtsp`/`.Hls`/`.Unavailable`), `StreamResolver(requestStream, baseUrl)` with `primary`/`fallback`, `CameraConfig(name, entity, rtspUrl)`, `DoorbellConfig(trigger, camera)`, `PopupCommand.Show(cameraName, untilMs)`, `DoorbellPopup(cameraName, untilMs)`, `DoorbellCoordinator.onStates(doorbells, states, popupSeconds, nowMs)`, `railViews(panels, camerasConfigured)`, `EntityHub.cameraStream(entityId)`, `CameraFeed(camera, resolver, muted, modifier)`, `CameraPlayer(source, muted, modifier, onError)`, `CamerasPanel(cameras, resolver)`, `DoorbellPopupView(popup, camera, resolver, onDismiss)`, `DashboardShell(..., streamResolver)`, `AppDeps.streamResolver` — all names and signatures are used identically across the tasks that produce and consume them.
