package com.rar.echodash.ui.model

import com.rar.echodash.sendspin.musicassistant.MaPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakersModelTest {

    /** A player with just the fields these helpers read; the rest are inert placeholders. */
    private fun player(
        id: String,
        name: String = id,
        available: Boolean = true,
        syncedTo: String? = null,
        groupMembers: List<String> = emptyList(),
        canGroupWith: List<String> = emptyList(),
    ): MaPlayer = MaPlayer(
        playerId = id, name = name, available = available, volumeLevel = 50, muted = false,
        syncedTo = syncedTo, groupMembers = groupMembers, canGroupWith = canGroupWith,
        playbackState = null, nowPlayingText = null,
    )

    // ---- speakerRows ----

    @Test
    fun speakerRowsPinsSelfFirst() {
        // self sorts last alphabetically but must still lead the list.
        val rows = speakerRows(
            listOf(player("a", "Aaa"), player("z", "Zzz"), player("m", "Mid")),
            selfId = "z",
        )
        assertEquals(listOf("z", "a", "m"), rows.map { it.playerId })
    }

    @Test
    fun speakerRowsPutsAvailableBeforeUnavailable() {
        val rows = speakerRows(
            listOf(player("off", "Aaa", available = false), player("on", "Zzz", available = true)),
            selfId = "none",
        )
        assertEquals(listOf("on", "off"), rows.map { it.playerId }) // available first despite A-Z
    }

    @Test
    fun speakerRowsSortsAlphaWithinTier() {
        val rows = speakerRows(
            listOf(player("c", "Zeta"), player("a", "Alpha"), player("b", "mid")),
            selfId = "none",
        )
        assertEquals(listOf("Alpha", "mid", "Zeta"), rows.map { it.name }) // case-insensitive A-Z
    }

    @Test
    fun speakerRowsWithSelfMissingJustSorts() {
        val rows = speakerRows(
            listOf(player("z", "Zzz"), player("a", "Aaa")),
            selfId = "not-here",
        )
        assertEquals(listOf("a", "z"), rows.map { it.playerId }) // no self pin, plain A-Z
    }

    // ---- inGroupWithSelf ----

    @Test
    fun inGroupWithSelfWhenPlayerSyncedToSelf() {
        val self = player("me")
        val other = player("kitchen", syncedTo = "me")
        assertTrue(inGroupWithSelf(other, self))
    }

    @Test
    fun inGroupWithSelfWhenSelfSyncedToPlayer() {
        val self = player("me", syncedTo = "kitchen") // other leadership direction
        val other = player("kitchen")
        assertTrue(inGroupWithSelf(other, self))
    }

    @Test
    fun inGroupWithSelfViaSelfGroupMembers() {
        val self = player("me", groupMembers = listOf("me", "kitchen")) // self leads the group
        val other = player("kitchen")
        assertTrue(inGroupWithSelf(other, self))
    }

    @Test
    fun inGroupWithSelfFalseWhenUnrelated() {
        assertFalse(inGroupWithSelf(player("kitchen"), player("me")))
    }

    @Test
    fun inGroupWithSelfFalseWhenSelfNull() {
        assertFalse(inGroupWithSelf(player("kitchen"), null))
    }

    // ---- canOfferGroup ----
    // The third arg is the set of currently-listed player ids. A canGroupWith entry that is NOT
    // one of them is treated as a provider-instance grant (permissive); a pure allowlist of other
    // players' ids that excludes us is a real "can't group with us".

    @Test
    fun canOfferGroupFalseForSelf() {
        val self = player("me")
        assertFalse(canOfferGroup(self, self, setOf("me", "kitchen")))
    }

    @Test
    fun canOfferGroupTrueWhenCanGroupWithEmpty() {
        // Empty canGroupWith = MA omitted restrictions => permissive.
        assertTrue(canOfferGroup(player("kitchen"), player("me"), setOf("me", "kitchen")))
    }

    @Test
    fun canOfferGroupTrueWhenSelfListed() {
        assertTrue(canOfferGroup(
            player("kitchen", canGroupWith = listOf("me", "living")), player("me"),
            setOf("me", "kitchen", "living"),
        ))
    }

    @Test
    fun canOfferGroupTrueForProviderGrant() {
        // "airplay--abc" is not a listed player id => a provider-instance grant => permissive.
        assertTrue(canOfferGroup(
            player("kitchen", canGroupWith = listOf("airplay--abc")), player("me"),
            setOf("me", "kitchen", "living"),
        ))
    }

    @Test
    fun canOfferGroupTrueForMixedListWithSelf() {
        // Self explicitly listed wins even alongside a provider grant.
        assertTrue(canOfferGroup(
            player("kitchen", canGroupWith = listOf("me", "airplay--abc")), player("me"),
            setOf("me", "kitchen"),
        ))
    }

    @Test
    fun canOfferGroupFalseForForeignPlayerIdAllowlist() {
        // Every entry is a real OTHER player id (all listed), and we're not among them.
        assertFalse(canOfferGroup(
            player("kitchen", canGroupWith = listOf("living", "study")), player("me"),
            setOf("me", "kitchen", "living", "study"),
        ))
    }

    @Test
    fun canOfferGroupFalseWhenUnavailable() {
        assertFalse(canOfferGroup(player("kitchen", available = false), player("me"), setOf("me", "kitchen")))
    }

    @Test
    fun canOfferGroupFalseWhenAlreadyGrouped() {
        val self = player("me")
        val other = player("kitchen", syncedTo = "me") // already in self's group
        assertFalse(canOfferGroup(other, self, setOf("me", "kitchen")))
    }

    @Test
    fun canOfferGroupFalseWhenSelfNull() {
        assertFalse(canOfferGroup(player("kitchen"), null, setOf("me", "kitchen")))
    }
}
