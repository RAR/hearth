package com.rar.hearth.photos

import java.io.File

/**
 * Persisted set of photo cache keys that have already been displayed on this device.
 *
 * The slideshow draws only from photos NOT in this set, which makes the display order a
 * shuffle-without-replacement over the whole HA folder: nothing repeats until the entire archive
 * has been shown, at which point [clear] starts a fresh epoch.
 *
 * Stored as newline-delimited keys. At ~40 bytes a key a 3000-photo archive costs ~120 KB.
 *
 * Lives in filesDir, NOT the photo cache dir: the cache dir is subject to Android's
 * storage-pressure eviction and to the stale-resolution wipe in AppDeps, and its listing is the
 * buffer inventory -- a stray file there would be read as a photo.
 *
 * Every read path is total: a missing, unreadable, or truncated file reads as an empty set rather
 * than throwing. Losing the ledger costs some repeats, never a crash.
 */
class PhotoLedger(private val file: File) {

    /** Keys already displayed. Empty when the file is missing or unreadable. */
    fun read(): Set<String> = runCatching {
        if (!file.exists()) return emptySet()
        file.readLines().filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    /** Add [keys] to the ledger. No-op when [keys] is empty or already fully recorded. */
    fun add(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val existing = read()
        val merged = existing + keys.filter { it.isNotBlank() }
        if (merged.size == existing.size) return
        write(merged)
    }

    /** Start a fresh epoch: every photo becomes eligible again. */
    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * Write via temp-file rename so an interrupted write leaves the previous ledger intact rather
     * than a half-written one. Same reasoning as AndroidPhotoDownloader's tmp+rename.
     */
    private fun write(keys: Set<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(keys.joinToString("\n"))
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }
}
