package com.rar.hearth.device

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

class HearthMessagesTest {

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun parsesHandshakeEvents() {
        assertEquals(HearthIncoming.Describe, HearthParser.parse(WyomingEvent("describe")))
        assertEquals(HearthIncoming.CapabilitiesRequest, HearthParser.parse(WyomingEvent("capabilities")))
        assertEquals(HearthIncoming.RunSatellite, HearthParser.parse(WyomingEvent("run-satellite")))
        assertEquals(HearthIncoming.Ping("x"), HearthParser.parse(WyomingEvent("ping", json("""{"text":"x"}"""))))
        assertEquals(HearthIncoming.Ping(null), HearthParser.parse(WyomingEvent("ping", json("""{"text":null}"""))))
    }

    @Test
    fun parsesFlattenedSettingsCustomEvent() {
        // HA->device shape: settings live NEXT TO event_type, not nested under "data"
        val e = WyomingEvent("custom-event",
            json("""{"event_type":"settings","settings":{"screen_on":true,"screen_brightness":70}}"""))
        val msg = HearthParser.parse(e) as HearthIncoming.SettingsChanged
        assertEquals(true, msg.settings["screen_on"]!!.jsonPrimitive.boolean)
        assertEquals(70, msg.settings["screen_brightness"]!!.jsonPrimitive.int)
    }

    @Test
    fun parsesActionCustomEvent() {
        val e = WyomingEvent("custom-event",
            json("""{"event_type":"action","action":"play-media","payload":{"url":"http://r/s.mp3","volume":80}}"""))
        val msg = HearthParser.parse(e) as HearthIncoming.Action
        assertEquals("play-media", msg.action)
        assertEquals("http://r/s.mp3", msg.payload!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesAudioEvents() {
        val start = HearthParser.parse(WyomingEvent("audio-start",
            json("""{"rate":22050,"width":2,"channels":1,"timestamp":0}"""))) as HearthIncoming.AudioStart
        assertEquals(22050, start.rate)
        assertEquals(2, start.width)
        assertEquals(1, start.channels)
        val chunk = HearthParser.parse(WyomingEvent("audio-chunk",
            json("""{"rate":22050,"width":2,"channels":1}"""), byteArrayOf(9, 8))) as HearthIncoming.AudioChunk
        assertTrue(chunk.pcm.contentEquals(byteArrayOf(9, 8)))
        assertEquals(HearthIncoming.AudioStop, HearthParser.parse(WyomingEvent("audio-stop")))
    }

    @Test
    fun unknownEventsAreNotFatal() {
        assertEquals(HearthIncoming.Unknown("pause-satellite"), HearthParser.parse(WyomingEvent("pause-satellite")))
        assertEquals(HearthIncoming.Unknown("timer-started"), HearthParser.parse(WyomingEvent("timer-started")))
        assertTrue(HearthParser.parse(WyomingEvent("custom-event",
            json("""{"event_type":"mystery"}"""))) is HearthIncoming.Unknown)
    }

    @Test
    fun infoEventDeclaresInstalledSatelliteWithAllFields() {
        val e = HearthOutgoing.info("0.2", "Test Device")
        assertEquals("info", e.type)
        for (key in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) {
            assertEquals(0, e.data[key]!!.jsonArray.size)
        }
        val sat = e.data["satellite"]!!.jsonObject
        assertEquals("Test Device", sat["name"]!!.jsonPrimitive.content)
        assertEquals(true, sat["installed"]!!.jsonPrimitive.boolean)
        assertEquals("0.2", sat["version"]!!.jsonPrimitive.content)
        assertEquals(false, sat["supports_trigger"]!!.jsonPrimitive.boolean)
        assertTrue(sat.containsKey("attribution"))
        assertTrue(sat.containsKey("description"))
        assertTrue(sat.containsKey("area"))
    }

    @Test
    fun capabilitiesGateLightSensor() {
        val with = HearthOutgoing.buildCapabilities("0.2", hasLightSensor = true)
        assertEquals(1, with["sensors"]!!.jsonArray.size)
        assertEquals(5, with["sensors"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.int)
        val without = HearthOutgoing.buildCapabilities("0.2", hasLightSensor = false)
        assertEquals(0, without["sensors"]!!.jsonArray.size)
        assertEquals(false, without["has_battery"]!!.jsonPrimitive.boolean)
        assertEquals(false, without["has_front_camera"]!!.jsonPrimitive.boolean)
        assertEquals(false, without["has_dnd"]!!.jsonPrimitive.boolean)
        assertEquals(10, without["audio"]!!.jsonObject["max_music_volume"]!!.jsonPrimitive.int)
    }

    @Test
    fun deviceToHaEventsNestUnderDataKey() {
        val fb = HearthOutgoing.settingsFeedback(buildJsonObject { put("screen_on", false) })
        assertEquals("custom-event", fb.type)
        assertEquals("settings", fb.data["event_type"]!!.jsonPrimitive.content)
        assertEquals(false, fb.data["data"]!!.jsonObject["settings"]!!.jsonObject["screen_on"]!!.jsonPrimitive.boolean)

        val st = HearthOutgoing.status(buildJsonObject {
            put("sensors", buildJsonObject { put("light", 42) })
        })
        assertEquals("status", st.data["event_type"]!!.jsonPrimitive.content)
        assertEquals(42, st.data["data"]!!.jsonObject["sensors"]!!.jsonObject["light"]!!.jsonPrimitive.int)

        assertEquals("played", HearthOutgoing.played().type)
        assertEquals("pong", HearthOutgoing.pong("t").type)
        assertEquals("t", HearthOutgoing.pong("t").data["text"]!!.jsonPrimitive.content)
    }
}
