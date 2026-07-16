package com.rar.echodash.sendspin.musicassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MaCommandClient track/media/album/artist parsing.
 */
class MaCommandClientMediaParsingTest {

    private lateinit var client: MaCommandClient

    @Before
    fun setUp() {
        client = MaCommandClient()
        // Set transport context for image URL generation
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)
    }

    // ========================================================================
    // parseMediaItem (tracks)
    // ========================================================================

    @Test
    fun `parseMediaItem standard track with all fields`() {
        val json = parseJson("""
        {
            "media_type": "track",
            "item_id": "track_001",
            "name": "Bohemian Rhapsody",
            "artist": "Queen",
            "album": {"name": "A Night at the Opera", "item_id": "album_42", "album_type": "album"},
            "uri": "library://music/track/001",
            "duration": 354,
            "image": "http://example.com/cover.jpg"
        }
        """)

        val track = client.parseMediaItem(json)
        assertNotNull(track)
        assertEquals("track_001", track!!.itemId)
        assertEquals("Bohemian Rhapsody", track.name)
        assertEquals("Queen", track.artist)
        assertEquals("A Night at the Opera", track.album)
        assertEquals("album_42", track.albumId)
        assertEquals("album", track.albumType)
        assertEquals("library://music/track/001", track.uri)
        assertEquals(354L, track.duration)
        assertEquals("http://example.com/cover.jpg", track.imageUri)
    }

    @Test
    fun `parseMediaItem skips non-track items`() {
        val json = parseJson("""
        {
            "media_type": "album",
            "item_id": "album_1",
            "name": "My Album"
        }
        """)

        assertNull(client.parseMediaItem(json))
    }

    @Test
    fun `parseMediaItem defaults to track if media_type missing`() {
        val json = parseJson("""
        {
            "item_id": "t1",
            "name": "Some Track"
        }
        """)

        val track = client.parseMediaItem(json)
        assertNotNull(track)
        assertEquals("t1", track!!.itemId)
    }

    @Test
    fun `parseMediaItem returns null for missing item_id`() {
        val json = parseJson("""
        {
            "name": "No ID Track"
        }
        """)

        assertNull(client.parseMediaItem(json))
    }

    @Test
    fun `parseMediaItem returns null for missing name`() {
        val json = parseJson("""
        {
            "item_id": "t1"
        }
        """)

        assertNull(client.parseMediaItem(json))
    }

    @Test
    fun `parseMediaItem falls back to track_id then uri`() {
        val json1 = parseJson("""{ "track_id": "tid1", "name": "T1" }""")
        assertEquals("tid1", client.parseMediaItem(json1)?.itemId)

        val json2 = parseJson("""{ "uri": "spotify:track:abc", "name": "T2" }""")
        assertEquals("spotify:track:abc", client.parseMediaItem(json2)?.itemId)
    }

    @Test
    fun `parseMediaItem extracts artist from artists array`() {
        val json = parseJson("""
        {
            "item_id": "t1",
            "name": "Track",
            "artists": [
                {"name": "First Artist"},
                {"name": "Second Artist"}
            ]
        }
        """)

        val track = client.parseMediaItem(json)
        assertEquals("First Artist", track?.artist)
    }

    @Test
    fun `parseMediaItem extracts artist from object`() {
        val json = parseJson("""
        {
            "item_id": "t1",
            "name": "Track",
            "artist": {"name": "Object Artist"}
        }
        """)

        val track = client.parseMediaItem(json)
        assertEquals("Object Artist", track?.artist)
    }

    @Test
    fun `parseMediaItem handles album as string`() {
        val json = parseJson("""
        {
            "item_id": "t1",
            "name": "Track",
            "album": "Simple Album Name"
        }
        """)

        val track = client.parseMediaItem(json)
        assertEquals("Simple Album Name", track?.album)
        assertNull(track?.albumId)
    }

    @Test
    fun `parseMediaItem zero duration treated as null`() {
        val json = parseJson("""
        {
            "item_id": "t1",
            "name": "Track",
            "duration": 0
        }
        """)

        assertNull(client.parseMediaItem(json)?.duration)
    }

    // ========================================================================
    // parseMediaItems
    // ========================================================================

    @Test
    fun `parseMediaItems extracts from result array`() {
        val json = parseJson("""
        {
            "result": [
                {"item_id": "t1", "name": "Track 1", "media_type": "track"},
                {"item_id": "t2", "name": "Track 2", "media_type": "track"},
                {"item_id": "a1", "name": "Album 1", "media_type": "album"}
            ]
        }
        """)

        val tracks = client.parseMediaItems(json)
        assertEquals(2, tracks.size)  // album is filtered out
        assertEquals("t1", tracks[0].itemId)
        assertEquals("t2", tracks[1].itemId)
    }

    @Test
    fun `parseMediaItems handles nested items object`() {
        val json = parseJson("""
        {
            "result": {
                "items": [
                    {"item_id": "t1", "name": "Track 1"}
                ]
            }
        }
        """)

        val tracks = client.parseMediaItems(json)
        assertEquals(1, tracks.size)
    }

    @Test
    fun `parseMediaItems returns empty for no result`() {
        val json = parseJson("""{ "message_id": "1" }""")
        assertTrue(client.parseMediaItems(json).isEmpty())
    }

    // ========================================================================
    // parseTracksArray
    // ========================================================================

    @Test
    fun `parseTracksArray parses tracks and filters invalid`() {
        val array = Json.parseToJsonElement("""
        [
            {"item_id": "t1", "name": "Good Track"},
            {"item_id": "t2"},
            {"name": "No ID"},
            {"item_id": "t3", "name": "Also Good"}
        ]
        """).jsonArray

        val tracks = client.parseTracksArray(array)
        assertEquals(2, tracks.size)
        assertEquals("t1", tracks[0].itemId)
        assertEquals("t3", tracks[1].itemId)
    }

    @Test
    fun `parseTracksArray returns empty for null`() {
        assertTrue(client.parseTracksArray(null).isEmpty())
    }

    // ========================================================================
    // parseAlbums
    // ========================================================================

    @Test
    fun `parseAlbumsArray filters out items without id or name`() {
        val array = Json.parseToJsonElement("""
        [
            {"item_id": "a1", "name": "Good Album"},
            {"item_id": "a2"},
            {"name": "No ID Album"}
        ]
        """).jsonArray

        val albums = client.parseAlbumsArray(array)
        assertEquals(1, albums.size)
        assertEquals("a1", albums[0].id)
    }

    // ========================================================================
    // parseArtists
    // ========================================================================

    @Test
    fun `parseArtistsArray filters out invalid entries`() {
        val array = Json.parseToJsonElement("""
        [
            {"item_id": "a1", "name": "Valid Artist"},
            {"item_id": "a2"},
            {}
        ]
        """).jsonArray

        val artists = client.parseArtistsArray(array)
        assertEquals(1, artists.size)
    }

    // ========================================================================
    // Provider extraction (online-provider search results, issue #160)
    // ========================================================================

    @Test
    fun `parseAlbumsArray reads explicit provider field`() {
        val array = Json.parseToJsonElement(
            """[{"item_id": "a1", "name": "Album", "provider": "spotify"}]"""
        ).jsonArray
        assertEquals("spotify", client.parseAlbumsArray(array)[0].provider)
    }

    @Test
    fun `parseAlbumsArray falls back to first provider_mapping`() {
        val array = Json.parseToJsonElement("""
        [{
            "item_id": "a1", "name": "Album",
            "provider_mappings": [{"provider_domain": "ytmusic"}, {"provider_domain": "spotify"}]
        }]
        """).jsonArray
        assertEquals("ytmusic", client.parseAlbumsArray(array)[0].provider)
    }

    @Test
    fun `parseAlbumsArray defaults provider to library`() {
        val array = Json.parseToJsonElement(
            """[{"item_id": "a1", "name": "Album"}]"""
        ).jsonArray
        assertEquals("library", client.parseAlbumsArray(array)[0].provider)
    }

    @Test
    fun `parseArtistsArray reads provider from provider_mappings`() {
        val array = Json.parseToJsonElement(
            """[{"item_id": "art1", "name": "Artist", "provider_mappings": [{"provider_domain": "spotify"}]}]"""
        ).jsonArray
        assertEquals("spotify", client.parseArtistsArray(array)[0].provider)
    }

    @Test
    fun `parseArtistsArray defaults provider to library`() {
        val array = Json.parseToJsonElement(
            """[{"item_id": "art1", "name": "Artist"}]"""
        ).jsonArray
        assertEquals("library", client.parseArtistsArray(array)[0].provider)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun parseJson(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject
}
