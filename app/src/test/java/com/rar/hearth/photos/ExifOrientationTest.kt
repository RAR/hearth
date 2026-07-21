package com.rar.hearth.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationTest {

    @Test
    fun rotatedOrientationsMapToDegrees() {
        assertEquals(90, exifRotationDegrees(6))   // ORIENTATION_ROTATE_90
        assertEquals(180, exifRotationDegrees(3))  // ORIENTATION_ROTATE_180
        assertEquals(270, exifRotationDegrees(8))  // ORIENTATION_ROTATE_270
    }

    @Test
    fun normalAndMirrorAndUnknownMapToZero() {
        assertEquals(0, exifRotationDegrees(1))  // ORIENTATION_NORMAL
        assertEquals(0, exifRotationDegrees(0))  // ORIENTATION_UNDEFINED
        assertEquals(0, exifRotationDegrees(2))  // FLIP_HORIZONTAL (mirror — not corrected)
        assertEquals(0, exifRotationDegrees(5))  // TRANSPOSE (mirror — not corrected)
        assertEquals(0, exifRotationDegrees(99)) // out-of-range
    }
}
