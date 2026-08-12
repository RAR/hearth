package com.rar.hearth.photos

import kotlin.random.Random

/**
 * What to fetch to refill the prefetch buffer.
 *
 * [epochReset] means every photo in the folder has now been displayed, so the caller should clear
 * the seen-ledger before recording anything further.
 */
data class PhotoBatch(val toDownload: List<RemotePhoto>, val epochReset: Boolean = false)

/**
 * Choose the next photos to prefetch.
 *
 * The buffer holds [depth] not-yet-displayed photos. Candidates are drawn uniformly at random from
 * `listing - seen - buffered`, which makes the whole slideshow a shuffle-without-replacement: no
 * photo repeats until the entire folder has been shown.
 *
 * When that pool runs dry the archive has been exhausted; [PhotoBatch.epochReset] is set and the
 * draw repeats against `listing - buffered` so a fresh epoch begins seamlessly. Excluding
 * `buffered` even on the reset draw keeps the photos already queued from being fetched twice.
 *
 * Replaces the previous rotatingSubset() model, which kept a bounded cache as a library and
 * rotated ~20% of it per sync. That guaranteed repeats (the cache was the whole visible universe)
 * and, because the rotation was a fraction OF the cache, scaled bandwidth with cache size. Under a
 * no-repeat rule a displayed photo has no further value, so the cache only needs to be a prefetch
 * buffer -- smaller on disk and repeat-free.
 *
 * [random] is injected so selection is deterministic under test.
 */
fun nextBatch(
    listing: List<RemotePhoto>,
    seen: Set<String>,
    buffered: Set<String>,
    depth: Int,
    random: Random,
): PhotoBatch {
    val want = depth - buffered.size
    if (want <= 0 || listing.isEmpty()) return PhotoBatch(emptyList())

    val notBuffered = listing.filter { cacheKey(it.contentId) !in buffered }
    // Every remote photo is already queued -- nothing to fetch, and NOT an exhausted epoch: the
    // buffered photos have not been displayed yet, so clearing the ledger here would be premature.
    if (notBuffered.isEmpty()) return PhotoBatch(emptyList())

    val unseen = notBuffered.filter { cacheKey(it.contentId) !in seen }

    if (unseen.isNotEmpty()) {
        return PhotoBatch(unseen.shuffled(random).take(want))
    }
    // Archive exhausted: everything not already queued has been shown. Start a new epoch.
    return PhotoBatch(notBuffered.shuffled(random).take(want), epochReset = true)
}
