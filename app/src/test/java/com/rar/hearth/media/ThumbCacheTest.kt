package com.rar.hearth.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Minimal ImageBitmap stand-in: ThumbCache treats bitmaps as opaque values, so a bare
 * interface implementation (compose-ui's ImageBitmap is a plain interface) is all we need.
 */
private fun bmp(): ImageBitmap = object : ImageBitmap {
    override val width = 1
    override val height = 1
    override val config = ImageBitmapConfig.Argb8888
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha = false
    override fun readPixels(
        buffer: IntArray, startX: Int, startY: Int,
        width: Int, height: Int, bufferOffset: Int, stride: Int,
    ) {}
    override fun prepareToDraw() {}
}

class ThumbCacheTest {

    @Test fun evictsEldestBeyondMaxEntries() {
        val cache = ThumbCache(maxEntries = 2)
        val a = bmp(); val b = bmp(); val c = bmp()
        cache.put("a", a); cache.put("b", b); cache.put("c", c)
        assertNull(cache.get("a")) // eldest evicted
        assertSame(b, cache.get("b"))
        assertSame(c, cache.get("c"))
    }

    @Test fun getRefreshesRecency() {
        val cache = ThumbCache(maxEntries = 2)
        val a = bmp(); val b = bmp(); val c = bmp()
        cache.put("a", a); cache.put("b", b)
        cache.get("a") // touch: "b" is now the eldest
        cache.put("c", c)
        assertSame(a, cache.get("a"))
        assertNull(cache.get("b"))
        assertSame(c, cache.get("c"))
    }

    @Test fun putReplacesOnSameKeyWithoutEviction() {
        val cache = ThumbCache(maxEntries = 2)
        val a1 = bmp(); val a2 = bmp(); val b = bmp()
        cache.put("a", a1); cache.put("b", b)
        cache.put("a", a2) // replace in place: size stays 2, nothing evicted
        assertSame(a2, cache.get("a"))
        assertSame(b, cache.get("b"))
    }

    @Test fun missReturnsNull() {
        assertNull(ThumbCache().get("nope"))
    }
}
