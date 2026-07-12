package com.rar.echodash.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RotatingSubsetTest {
    private fun photo(name: String) =
        RemotePhoto("media-source://media_source/local/f/$name", name)

    private fun keyOf(name: String) = cacheKey("media-source://media_source/local/f/$name")

    @Test
    fun underCapDownloadsNewAndDeletesRemovedLikeSyncAll() {
        val listing = listOf(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"))
        val cached = setOf(keyOf("a.jpg"), "gone-key")
        val plan = rotatingSubset(listing, cached, cap = 50, random = Random(0))
        assertEquals(setOf(keyOf("b.jpg"), keyOf("c.jpg")), plan.toDownload.map { cacheKey(it.contentId) }.toSet())
        assertEquals(listOf("gone-key"), plan.toDeleteKeys)
    }

    @Test
    fun overCapEvictsRemovedFilesFirst() {
        val listing = (1..10).map { photo("p$it.jpg") }         // 10 remote, cap 4
        // cache holds two remote survivors + one file no longer in the folder
        val cached = setOf(keyOf("p1.jpg"), keyOf("p2.jpg"), "removed-from-folder")
        val plan = rotatingSubset(listing, cached, cap = 4, random = Random(1))
        assertTrue("removed-from-folder must always be evicted", "removed-from-folder" in plan.toDeleteKeys)
    }

    @Test
    fun overCapRefillsToCapWithNeverCachedItems() {
        val listing = (1..20).map { photo("p$it.jpg") }         // 20 remote, cap 8
        val cached = (1..4).map { keyOf("p$it.jpg") }.toSet()   // 4 currently cached, all still remote
        val plan = rotatingSubset(listing, cached, cap = 8, random = Random(2))
        // ~20% of 4 surviving cached = ceil(0.8) = 1 evicted; final cache size lands on cap (8):
        // survivorsKept = 4 - evicted; downloads = 8 - survivorsKept
        val evicted = plan.toDeleteKeys.count { it in cached }
        val survivorsKept = 4 - evicted
        assertEquals(8, survivorsKept + plan.toDownload.size)
        // every download is a never-cached remote item
        assertTrue(plan.toDownload.none { cacheKey(it.contentId) in cached })
        // downloads are distinct
        assertEquals(plan.toDownload.size, plan.toDownload.map { it.contentId }.toSet().size)
    }

    @Test
    fun overCapEvictsAboutTwentyPercentOfSurvivingCache() {
        val listing = (1..100).map { photo("p$it.jpg") }
        val cached = (1..50).map { keyOf("p$it.jpg") }.toSet()  // 50 cached, all still remote, cap 50
        val plan = rotatingSubset(listing, cached, cap = 50, random = Random(3))
        val evicted = plan.toDeleteKeys.count { it in cached }
        assertEquals(10, evicted)                                // ceil(50 * 0.20) = 10
        assertEquals(10, plan.toDownload.size)                   // refill back to 50
    }

    @Test
    fun shrunkCapConvergesInOnePass() {
        // 100 cached keys, all still listed remotely, but the user lowered the cap to 50.
        val listing = (1..100).map { photo("p$it.jpg") }
        val cached = (1..100).map { keyOf("p$it.jpg") }.toSet()
        val plan = rotatingSubset(listing, cached, cap = 50, random = Random(4))
        val evicted = plan.toDeleteKeys.count { it in cached }
        val kept = 100 - evicted
        assertEquals(50, kept + plan.toDownload.size)
        assertEquals(50, kept)                                   // result never exceeds cap
    }

    @Test
    fun evictionNeverExceedsReplacements() {
        // cap 50, 50 surviving cached keys, but only 1 never-cached remote item is available.
        val listing = (1..50).map { photo("p$it.jpg") } + photo("new.jpg")
        val cached = (1..50).map { keyOf("p$it.jpg") }.toSet()
        val plan = rotatingSubset(listing, cached, cap = 50, random = Random(5))
        val evicted = plan.toDeleteKeys.count { it in cached }
        assertEquals(1, evicted)                                 // can't evict more than the 1 replacement
        assertEquals(1, plan.toDownload.size)
        assertEquals(50, (50 - evicted) + plan.toDownload.size)  // final cache size stays at cap
    }
}
