package com.rar.hearth.device

import com.rar.hearth.media.NowPlayingStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBridgeTest {

    private class FakeEngine : MediaEngine {
        val calls = mutableListOf<String>()
        private var _volume = -1f
        val volume get() = _volume
        var duckGain = -1f
        var sysVolume = 90
        override var onPlayingChanged: ((Boolean) -> Unit)? = null
        override var onMeta: ((String?, ByteArray?) -> Unit)? = null
        override var onEnded: (() -> Unit)? = null
        override var onVolumeChanged: ((Int) -> Unit)? = null
        override fun play(url: String) { calls += "play:$url" }
        override fun resume() { calls += "resume" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        var volumeSteps: Int? = null // simulate a device with N discrete volume steps
        // Mirrors the requested fraction back as the system volume (quantized to volumeSteps
        // when set) — the bridge reads this back after every set, like the real AudioManager.
        override fun setVolume(fraction: Float) {
            _volume = fraction
            val steps = volumeSteps
            sysVolume = if (steps == null) Math.round(fraction * 100)
            else Math.round(Math.round(fraction * steps) * 100f / steps)
            calls += "volume:$fraction"
        }
        override fun setDucking(fraction: Float) { duckGain = fraction; calls += "ducking:$fraction" }
        override fun currentVolumePercent() = sysVolume
    }

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun playMediaAppliesVolumeThenPlays() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine, NowPlayingStore(), sendStatus = {})
        assertTrue(bridge.handleAction("play-media",
            json("""{"url":"http://radio/stream.mp3","volume":80}""")))
        assertEquals(0.8f, engine.volume, 0.001f)
        assertTrue(engine.calls.contains("play:http://radio/stream.mp3"))
        assertTrue(engine.calls.indexOf("play:http://radio/stream.mp3") >
            engine.calls.indexOfFirst { it.startsWith("volume:") })
    }

    @Test
    fun playMediaWithUrlInvokesOnStartUrlBeforePlayExactlyOnce() {
        val engine = FakeEngine()
        var startCount = 0
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            sendStatus = {},
            // Reverse mutual-exclusion: starting a local URL must stop SendSpin first.
            onStartUrl = { startCount++; engine.calls += "start-sendspin" },
        )
        assertTrue(bridge.handleAction("play-media",
            json("""{"url":"http://radio/stream.mp3","volume":80}""")))
        assertEquals(1, startCount)
        assertTrue("onStartUrl must run before engine.play()",
            engine.calls.indexOf("start-sendspin") <
                engine.calls.indexOf("play:http://radio/stream.mp3"))
    }

    @Test
    fun playMediaWithoutUrlDoesNotInvokeOnStartUrl() {
        val engine = FakeEngine()
        var started = false
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            sendStatus = {},
            onStartUrl = { started = true },
        )
        // No "url" key -- e.g. a play-media call that only updates volume.
        assertTrue(bridge.handleAction("play-media", json("""{"volume":80}""")))
        assertFalse(started)
    }

    @Test
    fun stopActionInvokesOnUrlEnded() {
        val engine = FakeEngine()
        var endedCount = 0
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            sendStatus = {},
            // Symmetric re-arm: a local URL session ending must let SendSpin rejoin its MA group.
            onUrlEnded = { endedCount++ },
        )
        assertTrue(bridge.handleAction("stop", null))
        assertEquals(1, endedCount)
    }

    @Test
    fun engineEndedInvokesOnUrlEnded() {
        val engine = FakeEngine()
        var endedCount = 0
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            sendStatus = {},
            onUrlEnded = { endedCount++ },
        )
        // Natural end-of-media and a terminal player error both surface via engine.onEnded.
        engine.onEnded!!.invoke()
        assertEquals(1, endedCount)
    }

    @Test
    fun pauseDoesNotInvokeOnUrlEnded() {
        val engine = FakeEngine()
        var ended = false
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            sendStatus = {},
            onUrlEnded = { ended = true },
        )
        // A paused radio session is still the active local source -- SendSpin must stay down.
        assertTrue(bridge.handleAction("pause", null))
        assertFalse(ended)
    }

    @Test
    fun playMediaDoesNotInvokeOnUrlEnded() {
        val engine = FakeEngine()
        var startCount = 0
        var endedCount = 0
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            sendStatus = {},
            onStartUrl = { startCount++ },
            onUrlEnded = { endedCount++ },
        )
        assertTrue(bridge.handleAction("play-media",
            json("""{"url":"http://radio/stream.mp3","volume":80}""")))
        // Starting a URL fires the reverse exclusion (onStartUrl) but must NOT fire onUrlEnded.
        assertEquals(1, startCount)
        assertEquals(0, endedCount)
    }

    @Test
    fun transportActionsMapToEngine() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine, NowPlayingStore(), sendStatus = {})
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
    fun duckingScalesEngineGainAndRestores() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine, NowPlayingStore(), sendStatus = {})
        bridge.handleAction("set-volume", json("""{"volume":90}"""))
        assertEquals(0.9f, engine.volume, 0.001f)
        bridge.applySettings(json("""{"ducking_volume":1}""").jsonObject)
        // Ducking is an engine-side gain (duckingVolume/10); it never touches the system volume.
        bridge.setDucked(DuckSource.ANNOUNCE, true)
        assertEquals(0.1f, engine.duckGain, 0.001f)
        assertEquals(0.9f, engine.volume, 0.001f)
        bridge.setDucked(DuckSource.ANNOUNCE, false)
        assertEquals(1.0f, engine.duckGain, 0.001f)
        assertEquals(0.9f, engine.volume, 0.001f)
    }

    @Test
    fun musicVolumeSettingSetsSystemVolume() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine, NowPlayingStore(), sendStatus = {})
        bridge.applySettings(json("""{"music_volume":5}""").jsonObject)
        assertEquals(0.5f, engine.volume, 0.001f)
    }

    @Test
    fun constructionSeedsVolumeFromEngine() {
        val engine = FakeEngine().apply { sysVolume = 60 }
        val bridge = MediaBridge(engine, NowPlayingStore(), sendStatus = {})
        assertEquals(60, bridge.ui.value.volume)
    }

    @Test
    fun hardwareVolumeChangeUpdatesStateWithoutFeedback() {
        val engine = FakeEngine()
        val store = NowPlayingStore()
        val bridge = MediaBridge(engine, store, sendStatus = {})
        engine.calls.clear()
        engine.onVolumeChanged!!.invoke(30)
        assertEquals(30, bridge.ui.value.volume)
        assertEquals(30, store.state.value.volume)
        assertTrue("a system-volume change must not call setVolume back into the engine",
            engine.calls.none { it.startsWith("volume:") })
    }

    @Test
    fun reportsPlayingStatus() {
        val engine = FakeEngine()
        var status: JsonObject? = null
        MediaBridge(engine, NowPlayingStore(), sendStatus = { status = it })
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
        val bridge = MediaBridge(engine, NowPlayingStore(), sendStatus = {})
        engine.calls.clear() // drop the construction-time seed (setDucking)
        assertFalse(bridge.handleAction("screen-wake", null))
        assertFalse(bridge.handleAction("toast-message", null))
        assertEquals(0, engine.calls.size)
    }

    @Test
    fun uiStateTracksPlayNowPlayingVolumeAndStop() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine, NowPlayingStore(), sendStatus = {})
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":80}"""))
        assertEquals("http://radio/stream.mp3", bridge.ui.value.nowPlaying)
        assertEquals(80, bridge.ui.value.volume)
        engine.onPlayingChanged!!.invoke(true)
        assertTrue(bridge.ui.value.playing)
        bridge.handleAction("stop", null)
        assertFalse(bridge.ui.value.playing)
        assertEquals("Nothing playing", bridge.ui.value.nowPlaying)
    }

    @Test
    fun forwardsEngineAndLocalMetaIntoStore() {
        val engine = FakeEngine()
        val store = NowPlayingStore()
        val bridge = MediaBridge(engine, store, sendStatus = {})
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":80}"""))
        engine.onPlayingChanged!!.invoke(true)
        engine.onMeta!!.invoke("Artist - Title", null)
        val v = store.state.value
        assertTrue("play-media makes the store active", v.active)
        assertTrue(v.playing)
        assertEquals(80, v.volume)
        assertEquals("Title", v.title)
        assertEquals("Artist", v.artist)
    }

    @Test
    fun stopDeactivatesStoreKeepingVolume() {
        val engine = FakeEngine()
        val store = NowPlayingStore()
        val bridge = MediaBridge(engine, store, sendStatus = {})
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":60}"""))
        engine.onPlayingChanged!!.invoke(true)
        engine.onMeta!!.invoke("Some Title", null)
        bridge.handleAction("stop", null)
        val v = store.state.value
        assertFalse(v.active)
        assertFalse(v.playing)
        assertEquals(null, v.title)
        assertEquals(60, v.volume)
    }

    @Test
    fun engineEndedOrErrorDeactivatesStore() {
        val engine = FakeEngine()
        val store = NowPlayingStore()
        val bridge = MediaBridge(engine, store, sendStatus = {})
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":70}"""))
        engine.onPlayingChanged!!.invoke(true)
        engine.onMeta!!.invoke("Artist - Title", null)
        engine.onEnded!!.invoke()
        val v = store.state.value
        assertFalse("track end or player error must dismiss the takeover", v.active)
        assertEquals(null, v.title)
        assertEquals("Nothing playing", bridge.ui.value.nowPlaying)
    }

    @Test
    fun playingCallbackReactivatesAfterStaleEnded() {
        val engine = FakeEngine()
        val store = NowPlayingStore()
        val bridge = MediaBridge(engine, store, sendStatus = {})
        bridge.handleAction("play-media", json("""{"url":"http://a.mp3","volume":70}"""))
        // Track A ends just as a new play-media was handled: stale onEnded fires after activate.
        engine.onEnded!!.invoke()
        assertFalse(store.state.value.active)
        assertFalse(bridge.ui.value.playing)
        // The new track's playback start must restore active.
        engine.onPlayingChanged!!.invoke(true)
        assertTrue(store.state.value.active)
        assertTrue(store.state.value.playing)
        // ui.playing is the signal App.kt's delayed auto-rejoin recheck reads after a stale
        // onEnded: once the new session is playing it must read true so the rejoin skips.
        assertTrue(bridge.ui.value.playing)
    }

    @Test
    fun restoredDuckingSurfacesInCurrentSettings() {
        val bridge = MediaBridge(FakeEngine(), NowPlayingStore(), restoredDucking = 7, sendStatus = {})
        assertEquals(7, bridge.currentSettings()["ducking_volume"]!!.jsonPrimitive.int)
    }

    @Test
    fun outOfRangeRestoredDuckingCoercesToTen() {
        val bridge = MediaBridge(FakeEngine(), NowPlayingStore(), restoredDucking = 42, sendStatus = {})
        assertEquals(10, bridge.currentSettings()["ducking_volume"]!!.jsonPrimitive.int)
    }

    @Test
    fun nullRestoredDuckingDefaultsToOne() {
        val bridge = MediaBridge(FakeEngine(), NowPlayingStore(), restoredDucking = null, sendStatus = {})
        assertEquals(1, bridge.currentSettings()["ducking_volume"]!!.jsonPrimitive.int)
    }

    @Test
    fun applyingDuckingPersistsAndEchoes() {
        var persisted: Int? = null
        var echoed: JsonObject? = null
        val bridge = MediaBridge(
            FakeEngine(), NowPlayingStore(),
            persistDucking = { persisted = it },
            sendSettings = { echoed = it },
            sendStatus = {},
        )
        bridge.applySettings(json("""{"ducking_volume":5}""").jsonObject)
        assertEquals(5, persisted)
        assertEquals(5, echoed!!["ducking_volume"]!!.jsonPrimitive.int)
    }

    @Test
    fun settingsWithoutDuckingNeitherPersistNorEcho() {
        var persisted: Int? = null
        var echoed: JsonObject? = null
        val bridge = MediaBridge(
            FakeEngine(), NowPlayingStore(),
            persistDucking = { persisted = it },
            sendSettings = { echoed = it },
            sendStatus = {},
        )
        bridge.applySettings(json("""{"music_volume":5}""").jsonObject)
        assertNull(persisted)
        assertNull(echoed)
    }

    @Test
    fun restoredDuckingDrivesEngineGain() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine, NowPlayingStore(), restoredDucking = 5, sendStatus = {})
        bridge.setDucked(DuckSource.ANNOUNCE, true)
        assertEquals(0.5f, engine.duckGain, 0.001f)
    }

    @Test
    fun duckedTrueFansOutSameFractionToSendspinAsEngine() {
        val engine = FakeEngine()
        var sendspinFraction: Float? = null
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            restoredDucking = 5,
            sendStatus = {},
            duckSendspin = { sendspinFraction = it },
        )
        bridge.setDucked(DuckSource.ANNOUNCE, true)
        assertEquals("SendSpin and the local engine must duck by the same fraction",
            engine.duckGain, sendspinFraction!!, 0.001f)
        assertEquals(0.5f, sendspinFraction!!, 0.001f)
    }

    @Test
    fun duckedFalseFansOutFullVolumeToSendspin() {
        val engine = FakeEngine()
        var sendspinFraction: Float? = null
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            restoredDucking = 5,
            sendStatus = {},
            duckSendspin = { sendspinFraction = it },
        )
        bridge.setDucked(DuckSource.ANNOUNCE, true)
        bridge.setDucked(DuckSource.ANNOUNCE, false)
        assertEquals(1f, sendspinFraction!!, 0.001f)
        assertEquals(engine.duckGain, sendspinFraction!!, 0.001f)
    }

    @Test
    fun overlappingDuckClaimsHoldUntilLastRelease() {
        val engine = FakeEngine()
        var sendspinFraction: Float? = null
        val bridge = MediaBridge(
            engine, NowPlayingStore(),
            restoredDucking = 3,
            sendStatus = {},
            duckSendspin = { sendspinFraction = it },
        )
        // A TTS announce fires WHILE the doorbell popup is up (the standard automation).
        bridge.setDucked(DuckSource.DOORBELL, true)
        bridge.setDucked(DuckSource.ANNOUNCE, true)
        // The announce ends first: its release must NOT restore full volume under the popup.
        bridge.setDucked(DuckSource.ANNOUNCE, false)
        assertEquals(0.3f, engine.duckGain, 0.001f)
        assertEquals(0.3f, sendspinFraction!!, 0.001f)
        // Only the LAST claim's release restores full volume, on both fan-out targets.
        bridge.setDucked(DuckSource.DOORBELL, false)
        assertEquals(1f, engine.duckGain, 0.001f)
        assertEquals(1f, sendspinFraction!!, 0.001f)
    }

    @Test
    fun setVolumeReadsBackQuantizedSystemPercent() {
        // A device with 15 volume steps: a 50% request lands on index 8 = 53%. The bridge must
        // report the real system value, not the requested one, or the two drift apart forever
        // (a same-index request fires no VOLUME_CHANGED broadcast to correct it).
        val engine = FakeEngine().apply { volumeSteps = 15 }
        val bridge = MediaBridge(engine, NowPlayingStore(), sendStatus = {})
        bridge.handleAction("set-volume", json("""{"volume":50}"""))
        assertEquals(53, bridge.ui.value.volume)
    }
}
