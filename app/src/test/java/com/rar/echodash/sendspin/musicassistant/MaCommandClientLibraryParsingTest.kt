package com.rar.echodash.sendspin.musicassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MaCommandClient library item parsing (playlists, radios).
 */
class MaCommandClientLibraryParsingTest {

    private lateinit var client: MaCommandClient

    @Before
    fun setUp() {
        client = MaCommandClient()
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)
    }

    // ========================================================================
    // Playlists
    // ========================================================================

    @Test
    fun `parsePlaylists extracts from result array`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "item_id": "pl1",
                    "name": "My Favorites",
                    "owner": "chris",
                    "is_editable": true,
                    "uri": "library://music/playlist/pl1"
                }
            ]
        }
        """)

        val playlists = client.parsePlaylists(json)
        assertEquals(1, playlists.size)
        assertEquals("pl1", playlists[0].id)
        assertEquals("My Favorites", playlists[0].name)
        assertEquals("chris", playlists[0].owner)
    }

    @Test
    fun `parsePlaylistsArray filters invalid entries`() {
        val array = Json.parseToJsonElement("""
        [
            {"item_id": "pl1", "name": "Good Playlist"},
            {"item_id": "pl2"},
            {"name": "No ID"},
            {}
        ]
        """).jsonArray

        val playlists = client.parsePlaylistsArray(array)
        assertEquals(1, playlists.size)
        assertEquals("pl1", playlists[0].id)
    }

    @Test
    fun `parsePlaylistsArray returns empty for null`() {
        assertTrue(client.parsePlaylistsArray(null).isEmpty())
    }

    // ========================================================================
    // Radio Stations
    // ========================================================================

    @Test
    fun `parseRadioStations extracts from result array`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "item_id": "radio1",
                    "name": "BBC Radio 1",
                    "uri": "tunein://station/s1234"
                }
            ]
        }
        """)

        val radios = client.parseRadioStations(json)
        assertEquals(1, radios.size)
        assertEquals("radio1", radios[0].id)
        assertEquals("BBC Radio 1", radios[0].name)
    }

    @Test
    fun `parseRadiosArray filters invalid entries`() {
        val array = Json.parseToJsonElement("""
        [
            {"item_id": "r1", "name": "Valid Radio"},
            {"item_id": "r2"},
            {}
        ]
        """).jsonArray

        val radios = client.parseRadiosArray(array)
        assertEquals(1, radios.size)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun parseJson(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject
}
