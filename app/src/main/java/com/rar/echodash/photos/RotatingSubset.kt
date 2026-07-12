package com.rar.echodash.photos

import kotlin.math.ceil
import kotlin.random.Random

/** A sync plan: which remote items to fetch and which cached keys to delete. */
data class PhotoPlan(val toDownload: List<RemotePhoto>, val toDeleteKeys: List<String>)

/**
 * Choose the next cached subset for a photo folder.
 *
 * - listing.size <= cap: sync-all — download every not-yet-cached remote item, delete every cached
 *   key that is no longer in the folder.
 * - listing.size > cap: keep a bounded random subset. Always evict files that left the folder, then
 *   evict ceil(20%) of the surviving cache, then refill to [cap] with random never-cached remote
 *   items. Over successive syncs the whole archive rotates through; storage stays bounded.
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

    val evictCount = ceil(survivors.size * 0.20).toInt()
    val evicted = survivors.shuffled(random).take(evictCount)
    val keptKeys = survivors.toSet() - evicted.toSet()

    val neverCached = listing.filter { cacheKey(it.contentId) !in cachedKeys }
    val refillCount = (cap - keptKeys.size).coerceAtLeast(0)
    val toDownload = neverCached.shuffled(random).take(refillCount)

    return PhotoPlan(toDownload = toDownload, toDeleteKeys = removed + evicted)
}
