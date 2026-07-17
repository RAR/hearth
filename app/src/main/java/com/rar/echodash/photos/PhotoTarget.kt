package com.rar.echodash.photos

/** Echo Show 5 screen size — used only as the fallback when the real display can't be queried. */
private const val FALLBACK_W = 960
private const val FALLBACK_H = 480

/**
 * Fill-scale/crop target for the photo downloader, in pixels. Landscape-normalized (width is
 * always >= height) since the dashboard is always drawn landscape regardless of how the device
 * reports its rotated display size.
 */
data class PhotoTarget(val width: Int, val height: Int)

/**
 * Derive the cache target from a device's real display size. Sized per-device (rather than a
 * single hardcoded constant) so each screen gets sharp, correctly-cropped photos instead of a
 * one-size-fits-all cache that's upscaled and over-cropped on larger panels. Landscape-normalizes
 * [displayW]/[displayH] (max becomes width, min becomes height) since a display can be queried in
 * either orientation. Falls back to the Echo Show 5's 960x480 when either input is <= 0 — a
 * defensive fallback for when the display query fails or returns nothing usable.
 */
fun photoTarget(displayW: Int, displayH: Int): PhotoTarget {
    if (displayW <= 0 || displayH <= 0) return PhotoTarget(FALLBACK_W, FALLBACK_H)
    return PhotoTarget(maxOf(displayW, displayH), minOf(displayW, displayH))
}

/**
 * Cache directory name stamped with [target]'s dimensions, e.g. "photos-1340x800". Stamping the
 * dir name (rather than reusing a fixed "photos" dir) means a target-size change automatically
 * invalidates the old cache instead of silently serving stale-size images alongside new ones.
 */
fun photoCacheDirName(target: PhotoTarget): String = "photos-${target.width}x${target.height}"

/**
 * Which of [existingNames] are stale photo-cache directories that should be deleted, given the
 * dir name currently in use ([currentName]). Matches the legacy unstamped "photos" dir (used
 * before size-stamping shipped) and any other "photos-*" dir that isn't the current one. Never
 * matches unrelated cache entries, so this is safe to run against the full cache-dir listing.
 */
fun stalePhotoCacheDirs(existingNames: List<String>, currentName: String): List<String> =
    existingNames.filter { name ->
        name != currentName && (name == "photos" || name.startsWith("photos-"))
    }
