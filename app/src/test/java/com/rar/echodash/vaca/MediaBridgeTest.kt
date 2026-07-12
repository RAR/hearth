package com.rar.echodash.vaca

import com.rar.echodash.media.NowPlayingStore
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
        private var _volume = -1f
        val volume get() = _volume
        override var onPlayingChanged: ((Boolean) -> Unit)? = null
        override var onMeta: ((String?, ByteArray?) -> Unit)? = null
        override var onEnded: (() -> Unit)? = null
        override fun play(url: String) { calls += "play:$url" }
        override fun resume() { calls += "resume" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun setVolume(fraction: Float) { _volume = fraction; calls += "volume:$fraction" }
    }

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun playMediaAppliesVolumeThenPlays() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine, NowPlayingStore()) {}
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
        val bridge = MediaBridge(engine, NowPlayingStore()) {}
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
        val bridge = MediaBridge(engine, NowPlayingStore()) {}
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
        val bridge = MediaBridge(engine, NowPlayingStore()) {}
        bridge.applySettings(json("""{"music_volume":4}""").jsonObject)
        assertEquals(0.4f, engine.volume, 0.001f)
    }

    @Test
    fun reportsPlayingStatus() {
        val engine = FakeEngine()
        var status: JsonObject? = null
        MediaBridge(engine, NowPlayingStore()) { status = it }
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
        val bridge = MediaBridge(engine, NowPlayingStore()) {}
        assertFalse(bridge.handleAction("screen-wake", null))
        assertFalse(bridge.handleAction("toast-message", null))
        assertEquals(0, engine.calls.size)
    }

    @Test
    fun uiStateTracksPlayNowPlayingVolumeAndStop() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine, NowPlayingStore()) {}
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
        val bridge = MediaBridge(engine, store) {}
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
        val bridge = MediaBridge(engine, store) {}
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
        val bridge = MediaBridge(engine, store) {}
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":70}"""))
        engine.onPlayingChanged!!.invoke(true)
        engine.onMeta!!.invoke("Artist - Title", null)
        engine.onEnded!!.invoke()
        val v = store.state.value
        assertFalse("track end or player error must dismiss the takeover", v.active)
        assertEquals(null, v.title)
        assertEquals("Nothing playing", bridge.ui.value.nowPlaying)
    }
}
