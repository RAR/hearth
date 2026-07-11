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
    fun throwsOnNonStringType() {
        try {
            WyomingCodec.read(ByteArrayInputStream("""{"type":{"x":1}}
""".toByteArray()))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun throwsOnNonNumericDataLength() {
        try {
            WyomingCodec.read(ByteArrayInputStream("""{"type":"x","data_length":"abc"}
""".toByteArray()))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun throwsOnNegativeDataLength() {
        try {
            WyomingCodec.read(ByteArrayInputStream("""{"type":"x","data_length":-1}
""".toByteArray()))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun throwsOnOversizedDataLength() {
        try {
            WyomingCodec.read(ByteArrayInputStream("""{"type":"x","data_length":2000000}
""".toByteArray()))
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
