package com.rar.echodash.photos

import kotlin.math.ceil
import kotlin.random.Random

/** A sync plan: which remote items to fetch and which cached keys to delete. */
data class PhotoPlan(val toDownload: List<RemotePhoto>, val toDeleteKeys: List<String>)

/** Fraction of the surviving cache to rotate out per sync when over cap. */
private const val ROTATION_FRACTION = 0.20

/**
 * Choose the next cached subset for a photo folder.
 *
 * - listing.size <= cap: sync-all — download every not-yet-cached remote item, delete every cached
 *   key that is no longer in the folder.
 * - listing.size > cap: keep a bounded random subset. Always evict files that left the folder,
 *   then evict up to ceil(20%) of the surviving cache (rotation), then evict whatever more is
 *   needed to converge a shrunk cap in one pass, then refill to [cap] with random never-cached
 *   remote items. Over successive syncs the whole archive rotates through; storage stays bounded.
 *
 * Invariants: the resulting cache (kept + downloaded) never exceeds [cap], and rotation never
 * evicts more survivors than there are never-cached items available to replace them.
 *
 * [random] is injected so the selection is deterministic under test.
 */
fun rotatingSubset(
    listing: List<RemotePhoto>,
    cachedKeys: Set<String>,
    cap: Int,
    random: Random,
): PhotoPlan {
    val remoteByKey = listing.associateBy { cacheKey(it.contentId) }
    val removed = cachedKeys.filter { it !in remoteByKey }        // no longer in the folder
    val survivors = cachedKeys.filter { it in remoteByKey }        // cached AND still remote

    if (listing.size <= cap) {
        val toDownload = listing.filter { cacheKey(it.contentId) !in cachedKeys }
        return PhotoPlan(toDownload = toDownload, toDeleteKeys = removed)
    }

    val neverCached = listing.filter { cacheKey(it.contentId) !in cachedKeys }

    val rotationEvict = minOf(
        ceil(survivors.size * ROTATION_FRACTION).toInt(),   // ~20% rotation pressure
        neverCached.size,                                    // never evict more than we can replace
    )
    val overCapEvict = (survivors.size - rotationEvict - cap).coerceAtLeast(0)  // converge to a shrunk cap in one pass
    val evictCount = rotationEvict + overCapEvict

    val evicted = survivors.shuffled(random).take(evictCount)
    val keptKeys = survivors.toSet() - evicted.toSet()

    val refillCount = (cap - keptKeys.size).coerceAtLeast(0)
    val toDownload = neverCached.shuffled(random).take(refillCount)

    return PhotoPlan(toDownload = toDownload, toDeleteKeys = removed + evicted)
}
