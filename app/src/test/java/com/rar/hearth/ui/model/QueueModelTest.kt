package com.rar.hearth.ui.model

import com.rar.hearth.sendspin.musicassistant.MaQueueItem
import com.rar.hearth.sendspin.musicassistant.MaQueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueModelTest {

    /** A queue item with just the fields these helpers read; the rest are inert placeholders. */
    private fun item(
        name: String,
        current: Boolean,
        artist: String? = null,
        favorite: Boolean = false,
        mediaItemId: String? = null,
        mediaType: String? = null,
    ): MaQueueItem =
        MaQueueItem(
            queueItemId = name, name = name, artist = artist, album = null,
            imageUri = null, duration = null, uri = null, isCurrentItem = current,
            favorite = favorite, mediaItemId = mediaItemId, mediaType = mediaType,
        )

    private fun queue(vararg items: MaQueueItem): MaQueueState =
        MaQueueState(items = items.toList(), currentIndex = 0, shuffleEnabled = false, repeatMode = "off")

    // ---- nextRepeatMode ----

    @Test
    fun nextRepeatModeCyclesOffAllOneOff() {
        assertEquals("all", nextRepeatMode(null))
        assertEquals("all", nextRepeatMode("off"))
        assertEquals("one", nextRepeatMode("all"))
        assertEquals("off", nextRepeatMode("one"))
        assertEquals("all", nextRepeatMode("garbage")) // unrecognized restarts the cycle
    }

    // ---- upNextOf ----

    @Test
    fun upNextReturnsItemAfterCurrent() {
        val q = queue(item("A", current = false), item("B", current = true), item("C", current = false))
        assertEquals("C", upNextOf(q)?.name)
    }

    @Test
    fun upNextNullWhenCurrentIsLast() {
        val q = queue(item("A", current = false), item("B", current = true))
        assertNull(upNextOf(q))
    }

    @Test
    fun upNextNullWhenNoCurrentFlag() {
        val q = queue(item("A", current = false), item("B", current = false))
        assertNull(upNextOf(q))
    }

    @Test
    fun upNextNullWhenEmpty() {
        assertNull(upNextOf(queue()))
    }

    // ---- currentItemOf ----

    @Test
    fun currentItemOfReturnsFlaggedItem() {
        val q = queue(item("A", current = false), item("B", current = true), item("C", current = false))
        assertEquals("B", currentItemOf(q)?.name)
    }

    @Test
    fun currentItemOfNullWhenNoCurrentFlag() {
        val q = queue(item("A", current = false), item("B", current = false))
        assertNull(currentItemOf(q))
    }

    @Test
    fun currentItemOfNullWhenEmpty() {
        assertNull(currentItemOf(queue()))
    }

    // ---- favoriteToggleAction ----

    @Test
    fun favoriteToggleRemovesWhenFavoritedWithId() {
        val qi = item("A", current = true, favorite = true, mediaItemId = "123", mediaType = "track")
        assertEquals(FavoriteAction.Remove("track", "123"), favoriteToggleAction(qi))
    }

    @Test
    fun favoriteToggleRemoveDefaultsMediaTypeToTrack() {
        val qi = item("A", current = true, favorite = true, mediaItemId = "123", mediaType = null)
        assertEquals(FavoriteAction.Remove("track", "123"), favoriteToggleAction(qi))
    }

    @Test
    fun favoriteToggleAddsWhenFavoritedButNoId() {
        val qi = item("A", current = true, favorite = true, mediaItemId = null)
        assertEquals(FavoriteAction.Add, favoriteToggleAction(qi))
    }

    @Test
    fun favoriteToggleAddsWhenNotFavorited() {
        val qi = item("A", current = true, favorite = false, mediaItemId = "123", mediaType = "track")
        assertEquals(FavoriteAction.Add, favoriteToggleAction(qi))
    }

    @Test
    fun favoriteToggleAddsWhenItemNull() {
        assertEquals(FavoriteAction.Add, favoriteToggleAction(null))
    }
}
