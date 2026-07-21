package com.rar.hearth.photos

/**
 * Map an EXIF orientation tag value to the clockwise rotation (degrees) needed to display the image
 * upright. Values are per the EXIF spec: 6 = rotate 90° CW, 3 = 180°, 8 = 270° (i.e. 90° CCW);
 * everything else — 1 (normal) and the mirror/transpose variants 2/4/5/7 — maps to 0 (no plain
 * rotation; mirroring is not corrected, which is negligible for a slideshow).
 *
 * Pure so it is JVM-unit-testable; used by the API < 28 BitmapFactory decode path, which (unlike
 * ImageDecoder on API 28+) does not apply orientation itself.
 */
fun exifRotationDegrees(orientation: Int): Int = when (orientation) {
    6 -> 90
    3 -> 180
    8 -> 270
    else -> 0
}
