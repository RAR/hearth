package com.rar.hearth.photos

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * ImageDecoder-based photo decode, ISOLATED in its own class so it is never loaded on API < 28.
 *
 * [ImageDecoder] and its `OnHeaderDecodedListener` are API 28+. The listener compiles to a synthetic
 * lambda class; if that lambda lived inside [AndroidPhotoDownloader], ART would eagerly preload it
 * when the downloader loads and log a `NoClassDefFoundError` on API 27 (the Shelly Wall Display).
 * Keeping it here means the class — and its lambda — only load when [decode] actually runs, which
 * callers must gate behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.P`.
 */
@TargetApi(Build.VERSION_CODES.P)
internal object ImageDecoderPhotos {
    /**
     * Decode [bytes] with EXIF/HEIF orientation applied, fill-scaled to cover [target]. Returns null
     * if ImageDecoder rejects the bytes (caller falls back to the BitmapFactory path).
     */
    fun decode(bytes: ByteArray, target: PhotoTarget): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // hardware bitmaps can't be compressed
            val w = info.size.width
            val h = info.size.height
            val scale = maxOf(
                target.width.toFloat() / w,
                target.height.toFloat() / h,
            )
            if (scale < 1f) {
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }.getOrNull()
}
