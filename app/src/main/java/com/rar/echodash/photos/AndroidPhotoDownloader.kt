package com.rar.echodash.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.rar.echodash.ha.HaClient
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves a media content id to a signed URL, downloads it (no auth header — the authSig query
 * param authenticates), and decodes+scales it to fit within <=960x480 (preserving aspect ratio)
 * before caching as JPEG. Writes to a temp file first and renames on success, so an interrupted
 * compress never leaves a corrupt file under the final cache name.
 */
class AndroidPhotoDownloader(
    private val client: HaClient,
    private val http: OkHttpClient,
    private val baseUrl: () -> String?,
    private val cacheDir: File,
) : PhotoDownloader {

    override suspend fun download(contentId: String, cacheKey: String): File? = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext null
        val resolved = client.request("media_source/resolve_media", buildJsonObject {
            put("media_content_id", JsonPrimitive(contentId))
            put("expires", JsonPrimitive(300))
        }) as? JsonObject ?: return@withContext null
        val rel = (resolved["url"] as? JsonPrimitive)?.contentOrNull ?: return@withContext null
        // Signed relative URLs authenticate via the authSig query param — no Authorization header.
        val url = if (rel.startsWith("/")) base.trimEnd('/') + rel else rel
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.bytes() ?: return@withContext null
        }
        val bmp = decodeOriented(bytes) ?: return@withContext null
        val tmp = File(cacheDir, "$cacheKey.tmp")
        val out = File(cacheDir, cacheKey)
        val wrote = runCatching {
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }.isSuccess
        bmp.recycle()
        if (!wrote || !tmp.renameTo(out)) {
            tmp.delete()
            return@withContext null
        }
        out
    }

    /**
     * Decode [bytes] scaled to fit within MAX_W x MAX_H with orientation applied. BitmapFactory
     * ignores orientation metadata — EXIF and the HEIF container rotation iPhone HEICs carry — so
     * photos came out sideways/upside-down and got baked that way into the JPEG cache. ImageDecoder
     * applies both forms during decode. Falls back to the orientation-blind BitmapFactory path only
     * if ImageDecoder rejects the bytes outright.
     */
    private fun decodeOriented(bytes: ByteArray): Bitmap? =
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // hardware bitmaps can't be compressed
                val w = info.size.width
                val h = info.size.height
                if (w > PhotoConfig.MAX_W || h > PhotoConfig.MAX_H) {
                    val scale = minOf(
                        PhotoConfig.MAX_W.toFloat() / w,
                        PhotoConfig.MAX_H.toFloat() / h,
                    )
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        }.getOrNull() ?: decodeDownsampled(bytes)

    /** Decode [bytes] downsampled by a power-of-2 inSampleSize, then scale to fit within MAX_W x MAX_H. */
    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > PhotoConfig.MAX_W * 2 ||
            bounds.outHeight / sample > PhotoConfig.MAX_H * 2
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (decoded.width <= PhotoConfig.MAX_W && decoded.height <= PhotoConfig.MAX_H) return decoded
        // inSampleSize only guarantees within 2x of the cap; scale precisely down to fit.
        val scale = minOf(
            PhotoConfig.MAX_W.toFloat() / decoded.width,
            PhotoConfig.MAX_H.toFloat() / decoded.height,
        )
        val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
        val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        decoded.recycle()
        return scaled
    }
}
