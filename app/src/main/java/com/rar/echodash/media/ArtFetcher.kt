package com.rar.echodash.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

/** A sharp album-art bitmap plus a tiny blurred copy for the full-screen background. */
data class ArtBitmaps(val sharp: ImageBitmap, val blurred: ImageBitmap)

/**
 * Produces album art for the current NowPlayingState. Fetches entity_picture over OkHttp with a
 * Bearer header (entity_picture URLs are same-origin to HA and safe to send the token to), or
 * decodes embedded ID3 artwork bytes. Keeps ONE current bitmap in memory keyed by URL/bytes-hash:
 * re-emitting the same key is a no-op; a new key cancels any in-flight fetch and replaces the art.
 * No disk cache. Failures (HTTP error, decode failure) resolve to null (a placeholder shows); logged
 * at warn, never crashes, never retry-loops (the next metadata change naturally retries).
 *
 * Blur is MANUAL: the Echo is Android 11 (API 30) where Compose Modifier.blur is a silent no-op.
 * The background bitmap is decoded/scaled to 24x12 and the GPU bilinear-upscales it under a dark
 * scrim (applied by the UI). Sharp art is decoded downsampled to <= 480 px on the long edge.
 */
class ArtFetcher(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val baseUrl: () -> String?,
    private val token: suspend () -> String?,
) {
    private val _art = MutableStateFlow<ArtBitmaps?>(null)
    val art: StateFlow<ArtBitmaps?> = _art

    private var currentKey: String? = null
    private var job: Job? = null

    /** Collect [source] and keep [art] in sync with the current track's artwork. */
    fun start(source: StateFlow<NowPlayingState>) {
        scope.launch { source.collect { onState(it) } }
    }

    private fun onState(st: NowPlayingState) {
        val resolvedUrl = resolveArtUrl(st.artUrl, baseUrl())
        val localArt = st.localArt
        val key = when {
            !st.active -> null
            resolvedUrl != null -> "url:$resolvedUrl"
            localArt != null -> "bytes:${localArt.contentHashCode()}"
            else -> null
        }
        if (key == currentKey) return
        currentKey = key
        job?.cancel()
        if (key == null) { _art.value = null; return }
        job = scope.launch(Dispatchers.IO) {
            val bytes = if (resolvedUrl != null) fetch(resolvedUrl) else localArt
            val bmps = bytes?.let { decode(it) }
            if (isActive) _art.value = bmps
        }
    }

    private suspend fun fetch(url: String): ByteArray? =
        try {
            val builder = Request.Builder().url(url)
            token()?.let { builder.header("Authorization", "Bearer $it") }
            http.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "art fetch failed: $url", e)
            null
        }

    private fun decode(bytes: ByteArray): ArtBitmaps? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longEdge > 0 && longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (decoded == null) null else {
                val sharp = downscaleLongEdge(decoded, MAX_EDGE)
                val blurred = Bitmap.createScaledBitmap(sharp, BLUR_W, BLUR_H, true)
                ArtBitmaps(sharp.asImageBitmap(), blurred.asImageBitmap())
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "art decode failed", e)
            null
        }

    /** Scale [bmp] so its long edge is at most [maxEdge]; returns [bmp] unchanged when already small. */
    private fun downscaleLongEdge(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / longEdge
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private companion object {
        const val TAG = "ArtFetcher"
        const val MAX_EDGE = 480 // sharp art card is ~360 px tall; screen is 960x480
        const val BLUR_W = 24
        const val BLUR_H = 12
    }
}
