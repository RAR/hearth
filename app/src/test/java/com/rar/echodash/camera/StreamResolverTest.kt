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
