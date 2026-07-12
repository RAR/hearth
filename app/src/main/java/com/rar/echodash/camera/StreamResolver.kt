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
