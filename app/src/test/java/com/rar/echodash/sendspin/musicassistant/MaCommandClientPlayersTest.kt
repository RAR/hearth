package com.rar.echodash.sendspin.musicassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for MaCommandClient.parsePlayers (players/all wire shape, incl. back-compat aliases). */
class MaCommandClientPlayersTest {

    private lateinit var client: MaCommandClient

    @Before
    fun setUp() {
        client = MaCommandClient()
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)
    }

    @Test
    fun `parsePlayers reads full payload with back-compat aliases`() {
        // display_name (alias for name), group_childs (alias for group_members), state (alias
        // for playback_state) exercise the fallback branches; current_media.title -> nowPlayingText.
        val json = parseJson("""
        {
            "result": [
                {
                    "player_id": "kitchen",
                    "display_name": "Kitchen",
                    "available": true,
                    "volume_level": 42,
                    "volume_muted": true,
                    "synced_to": "living",
                    "group_childs": ["living", "kitchen"],
                    "can_group_with": ["living", "study"],
                    "state": "playing",
                    "current_media": {"title": "Song X"}
                }
            ]
        }
        """)
        val players = client.parsePlayers(json)
        assertEquals(1, players.size)
        val p = players[0]
        assertEquals("kitchen", p.playerId)
        assertEquals("Kitchen", p.name)            // from display_name alias
        assertTrue(p.available)
        assertEquals(42, p.volumeLevel)
        assertTrue(p.muted)                          // from volume_muted
        assertEquals("living", p.syncedTo)
        assertEquals(listOf("living", "kitchen"), p.groupMembers) // from group_childs alias
        assertEquals(listOf("living", "study"), p.canGroupWith)
        assertEquals("playing", p.playbackState)     // from state alias
        assertEquals("Song X", p.nowPlayingText)     // from current_media.title
    }

    @Test
    fun `parsePlayers defaults a minimal payload`() {
        val json = parseJson("""{ "result": [ {"player_id": "bare", "name": "Bare"} ] }""")
        val players = client.parsePlayers(json)
        assertEquals(1, players.size)
        val p = players[0]
        assertEquals("bare", p.playerId)
        assertEquals("Bare", p.name)
        assertFalse(p.available)                     // absent -> false
        assertNull(p.volumeLevel)                    // absent -> null
        assertFalse(p.muted)
        assertNull(p.syncedTo)
        assertTrue(p.groupMembers.isEmpty())
        assertTrue(p.canGroupWith.isEmpty())
        assertNull(p.playbackState)
        assertNull(p.nowPlayingText)
    }

    @Test
    fun `parsePlayers keeps unavailable players and skips id-less rows`() {
        val json = parseJson("""
        {
            "result": [
                {"player_id": "off", "name": "Garage", "available": false, "volume_level": 0},
                {"name": "No Id"}
            ]
        }
        """)
        val players = client.parsePlayers(json)
        assertEquals(1, players.size)                // the id-less row is skipped
        val p = players[0]
        assertEquals("off", p.playerId)
        assertFalse(p.available)                     // unavailable players are still returned
        assertEquals(0, p.volumeLevel)               // an explicit 0 is a real level, not null
    }

    private fun parseJson(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject
}
