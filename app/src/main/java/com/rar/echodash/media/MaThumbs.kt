package com.rar.echodash.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Bounded LRU of decoded thumbnails, keyed by URL. Pure Kotlin so the eviction contract is
 * plain-JVM pinned (ThumbCacheTest). 48 entries of ≤128 px ARGB bitmaps is ~3 MB worst case —
 * sized for the 1 GB Echo, where the browser must never accumulate unbounded art.
 */
class ThumbCache(private val maxEntries: Int = 48) {
    // accessOrder=true makes get() refresh recency; removeEldestEntry turns the LinkedHashMap
    // into an LRU with eviction for free.
    private val map = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(url: String): ImageBitmap? = map[url]

    @Synchronized
    fun put(url: String, bmp: ImageBitmap) {
        map[url] = bmp
    }
}

/**
 * Token-less small-image loader for Music Assistant thumbnails. [ArtFetcher] can't serve these:
 * it attaches the HA bearer token to every request and holds a single slot, while the browser
 * needs a grid of MA image-proxy URLs (already absolute, plain GETs — see the design spec).
 */
class MaThumbs(
    private val http: OkHttpClient,
    private val cache: ThumbCache = ThumbCache(),
) {
    /** GET + downsample to ≤[MAX_EDGE] px long edge; cache-first; null on any failure. */
    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = fetch(url) ?: return@withContext null
            val bmp = decode(bytes) ?: return@withContext null
            cache.put(url, bmp)
            bmp
        }
    }

    private fun fetch(url: String): ByteArray? =
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "thumb fetch failed: $url")
            null
        }

    // Bounds-decode then inSampleSize then exact downscale, mirroring ArtFetcher.decode —
    // full-size covers can be 1000 px+, and decoding them whole for a 96 dp cell would chew
    // through the Echo's heap.
    private fun decode(bytes: ByteArray): ImageBitmap? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longEdge > 0 && longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            decoded?.let { downscaleLongEdge(it, MAX_EDGE).asImageBitmap() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "thumb decode failed")
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
        const val TAG = "MaThumbs"
        const val MAX_EDGE = 128 // 96 dp shelf cell; leaves headroom for the tablet's density
    }
}
