package com.rar.echodash.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoTargetTest {

    @Test
    fun portraitInputNormalizesToLandscape() {
        assertEquals(PhotoTarget(1340, 800), photoTarget(800, 1340))
    }

    @Test
    fun landscapeInputPassesThrough() {
        assertEquals(PhotoTarget(1280, 800), photoTarget(1280, 800))
    }

    @Test
    fun squareInputPassesThrough() {
        assertEquals(PhotoTarget(800, 800), photoTarget(800, 800))
    }

    @Test
    fun zeroWidthFallsBackToShow5Size() {
        assertEquals(PhotoTarget(960, 480), photoTarget(0, 800))
    }

    @Test
    fun zeroHeightFallsBackToShow5Size() {
        assertEquals(PhotoTarget(960, 480), photoTarget(1280, 0))
    }

    @Test
    fun negativeInputFallsBackToShow5Size() {
        assertEquals(PhotoTarget(960, 480), photoTarget(-1, -1))
    }

    @Test
    fun dirNameFormatsAsPhotosWidthByHeight() {
        assertEquals("photos-1340x800", photoCacheDirName(PhotoTarget(1340, 800)))
    }

    @Test
    fun staleDirsIncludeLegacyUnstampedName() {
        val names = listOf("photos", "photos-1340x800", "http")
        assertEquals(listOf("photos"), stalePhotoCacheDirs(names, currentName = "photos-1340x800"))
    }

    @Test
    fun staleDirsIncludeOtherSizedCaches() {
        val names = listOf("photos-960x480", "photos-1340x800")
        assertEquals(listOf("photos-960x480"), stalePhotoCacheDirs(names, currentName = "photos-1340x800"))
    }

    @Test
    fun staleDirsExcludeCurrentName() {
        val names = listOf("photos-1340x800")
        assertEquals(emptyList<String>(), stalePhotoCacheDirs(names, currentName = "photos-1340x800"))
    }

    @Test
    fun staleDirsNeverMatchUnrelatedNames() {
        val names = listOf("http", "photos_tmp", "image_cache")
        assertEquals(emptyList<String>(), stalePhotoCacheDirs(names, currentName = "photos-1340x800"))
    }
}
