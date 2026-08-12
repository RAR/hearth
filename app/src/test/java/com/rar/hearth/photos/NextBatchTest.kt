package com.rar.hearth.photos

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextBatchTest {

    private fun photos(vararg names: String): List<RemotePhoto> =
        names.map { RemotePhoto("media-source://media_source/local/echo-frame/$it", it) }

    private fun keyOf(name: String) =
        cacheKey("media-source://media_source/local/echo-frame/$name")

    private fun keys(batch: PhotoBatch) = batch.toDownload.map { cacheKey(it.contentId) }.toSet()

    @Test
    fun drawsOnlyUnseenPhotos() {
        val listing = photos("a.jpg", "b.jpg", "c.jpg")
        val seen = setOf(keyOf("a.jpg"), keyOf("b.jpg"))

        val batch = nextBatch(listing, seen, buffered = emptySet(), depth = 5, random = Random(1))

        assertEquals(setOf(keyOf("c.jpg")), keys(batch))
        assertFalse(batch.epochReset)
    }

    @Test
    fun excludesAlreadyBufferedPhotos() {
        val listing = photos("a.jpg", "b.jpg", "c.jpg")

        val batch = nextBatch(
            listing, seen = emptySet(), buffered = setOf(keyOf("b.jpg")), depth = 5, random = Random(1),
        )

        assertEquals(setOf(keyOf("a.jpg"), keyOf("c.jpg")), keys(batch))
    }

    @Test
    fun fetchesOnlyEnoughToReachDepth() {
        val listing = photos("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg")

        val batch = nextBatch(
            listing, seen = emptySet(), buffered = setOf(keyOf("a.jpg")), depth = 3, random = Random(1),
        )

        // depth 3, one already buffered -> fetch 2.
        assertEquals(2, batch.toDownload.size)
        assertFalse(keys(batch).contains(keyOf("a.jpg")))
    }

    @Test
    fun bufferAlreadyAtDepthFetchesNothing() {
        val listing = photos("a.jpg", "b.jpg", "c.jpg")

        val batch = nextBatch(
            listing,
            seen = emptySet(),
            buffered = setOf(keyOf("a.jpg"), keyOf("b.jpg")),
            depth = 2,
            random = Random(1),
        )

        assertTrue(batch.toDownload.isEmpty())
        assertFalse(batch.epochReset)
    }

    @Test
    fun exhaustedArchiveStartsNewEpoch() {
        val listing = photos("a.jpg", "b.jpg")
        val seen = setOf(keyOf("a.jpg"), keyOf("b.jpg"))

        val batch = nextBatch(listing, seen, buffered = emptySet(), depth = 2, random = Random(1))

        assertTrue(batch.epochReset)
        assertEquals(setOf(keyOf("a.jpg"), keyOf("b.jpg")), keys(batch))
    }

    @Test
    fun everythingBufferedIsNotAnExhaustedEpoch() {
        // The buffered photos have not been DISPLAYED yet, so the ledger must not be cleared.
        val listing = photos("a.jpg", "b.jpg")

        val batch = nextBatch(
            listing,
            seen = emptySet(),
            buffered = setOf(keyOf("a.jpg"), keyOf("b.jpg")),
            depth = 10,
            random = Random(1),
        )

        assertTrue(batch.toDownload.isEmpty())
        assertFalse(batch.epochReset)
    }

    @Test
    fun emptyListingFetchesNothing() {
        val batch = nextBatch(emptyList(), emptySet(), emptySet(), depth = 10, random = Random(1))

        assertTrue(batch.toDownload.isEmpty())
        assertFalse(batch.epochReset)
    }

    @Test
    fun selectionIsDeterministicForAGivenSeed() {
        val listing = photos("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg", "f.jpg")

        val first = nextBatch(listing, emptySet(), emptySet(), depth = 3, random = Random(42))
        val second = nextBatch(listing, emptySet(), emptySet(), depth = 3, random = Random(42))

        assertEquals(first.toDownload, second.toDownload)
    }

    @Test
    fun neverExceedsDepthEvenWithALargeArchive() {
        val listing = photos(*Array(3000) { "p$it.jpg" })

        val batch = nextBatch(listing, emptySet(), emptySet(), depth = 20, random = Random(7))

        assertEquals(20, batch.toDownload.size)
    }
}
