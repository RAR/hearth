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
        private var _volume = -1f
        val volume get() = _volume
        override var onPlayingChanged: ((Boolean) -> Unit)? = null
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
