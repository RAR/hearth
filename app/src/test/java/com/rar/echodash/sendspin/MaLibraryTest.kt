package com.rar.echodash.sendspin

import com.rar.echodash.sendspin.musicassistant.EnqueueMode
import com.rar.echodash.sendspin.musicassistant.MaAlbum
import com.rar.echodash.sendspin.musicassistant.MaArtist
import com.rar.echodash.sendspin.musicassistant.MaAuthHelper
import com.rar.echodash.sendspin.musicassistant.MaPlayer
import com.rar.echodash.sendspin.musicassistant.transport.MaApiTransport
import com.rar.echodash.sendspin.musicassistant.transport.MaTransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

class MaLibraryTest {

    /**
     * Recording fake for the MA API transport. MaLibrary's default clientFactory builds a REAL
     * MaCommandClient wired to this via setTransport, so ops exercise real command building and
     * response parsing against canned JSON — the recorder pins command order and args.
     */
    private class FakeTransport(
        private val commands: MutableList<Pair<String, Map<String, Any>>>,
        private val failConnectWith: Exception? = null,
    ) : MaApiTransport {
        private val _state = MutableStateFlow<MaApiTransport.State>(MaApiTransport.State.Disconnected)
        override val state: StateFlow<MaApiTransport.State> = _state
        override var serverVersion: String? = null
        override val maServerId: String? = null
        override val baseUrl: String? = null
        var disconnects = 0
        var respond: (String) -> JsonObject = { cmd ->
            if (cmd == "player_queues/get_active_queue") json("""{"result":{"queue_id":"q-77"}}""")
            else json("{}")
        }

        override suspend fun connect(token: String) {
            failConnectWith?.let { throw it }
            serverVersion = "2.9.9"
            _state.value = MaApiTransport.State.Connected
        }

        override suspend fun connectWithCredentials(
            username: String,
            password: String,
        ): MaApiTransport.LoginResult = throw UnsupportedOperationException("not used by MaLibrary")

        override suspend fun sendCommand(
            command: String,
            args: Map<String, Any>,
            timeoutMs: Long,
        ): JsonObject {
            commands += command to args
            return respond(command)
        }

        override suspend fun httpProxy(
            method: String,
            path: String,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): MaApiTransport.HttpProxyResponse = throw UnsupportedOperationException("not used by MaLibrary")

        override fun setEventListener(listener: MaApiTransport.EventListener?) {}

        override fun disconnect() {
            disconnects++
            _state.value = MaApiTransport.State.Disconnected
        }

        /** Simulate an unexpected socket drop (the real transport surfaces drops as Error). */
        fun dropWithError(reason: String) {
            _state.value = MaApiTransport.State.Error(reason)
        }
    }

    /** Fake-wired MaLibrary plus the recorders the assertions read. */
    private class Harness(scope: CoroutineScope) {
        var host: String? = "10.75.1.54:8927" // carries the SendSpin port — MaLibrary must strip it
        val commands = mutableListOf<Pair<String, Map<String, Any>>>()
        val transports = mutableListOf<FakeTransport>()
        val urls = mutableListOf<String>()
        val connectFailures = ArrayDeque<Exception>() // each new transport consumes one, if queued
        val loginCalls = mutableListOf<Triple<String, String, String>>()
        var loginResult = MaAuthHelper.LoginResult(
            accessToken = "tok-abc",
            userId = "uid-1",
            userName = "Andrew",
            serverVersion = "2.9.9",
            maServerId = "srv-1",
            baseUrl = "http://10.75.1.54:8095",
        )
        val lib = MaLibrary(
            scope = scope,
            playerId = "player-1",
            hostProvider = { host },
            transportFactory = { url ->
                urls += url
                FakeTransport(commands, failConnectWith = connectFailures.removeFirstOrNull())
                    .also { transports += it }
            },
            loginForToken = { url, username, password ->
                loginCalls += Triple(url, username, password)
                loginResult
            },
        )
    }

    // ---- configure / lifecycle ----

    @Test
    fun disabledOrTokenlessConfigureIsDisabledAndOpsFail() = runTest {
        val h = Harness(this)
        assertTrue(h.lib.state.value is MaLibraryState.Disabled)
        h.lib.configure(enabled = true, token = "")
        runCurrent()
        assertTrue(h.lib.state.value is MaLibraryState.Disabled)
        h.lib.configure(enabled = false, token = "tok")
        runCurrent()
        assertTrue(h.lib.state.value is MaLibraryState.Disabled)
        assertTrue(h.transports.isEmpty())
        val res = h.lib.playlists()
        assertTrue(res.isFailure)
        assertEquals("MA library not connected", res.exceptionOrNull()!!.message)
        h.lib.stop()
    }

    @Test
    fun nullHostRetriesUntilHostAppears() = runTest {
        val h = Harness(this)
        h.host = null
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        // No server known yet (SendSpin not connected, no manual address): back off, no crash.
        assertTrue(h.lib.state.value is MaLibraryState.Offline)
        assertTrue(h.transports.isEmpty())
        h.host = "10.75.1.54"
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, h.transports.size)
        assertEquals("ws://10.75.1.54:8095/ws", h.urls[0])
        assertEquals(MaLibraryState.Connected("2.9.9"), h.lib.state.value)
        h.lib.stop()
    }

    @Test
    fun authFailureParksWithNoReconnect() = runTest {
        val h = Harness(this)
        h.connectFailures.add(MaApiTransport.AuthenticationException("token rejected"))
        h.lib.configure(enabled = true, token = "bad")
        runCurrent()
        assertTrue(h.lib.state.value is MaLibraryState.AuthFailed)
        assertEquals(1, h.transports.size)
        // Parked: no automatic retries, however long we wait.
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, h.transports.size)
        assertTrue(h.lib.state.value is MaLibraryState.AuthFailed)
        h.lib.stop()
    }

    @Test
    fun ioFailureBacksOffDoublingThenConnects() = runTest {
        val h = Harness(this)
        h.connectFailures.add(MaTransportException("connection refused"))
        h.connectFailures.add(MaTransportException("connection refused"))
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertEquals(MaLibraryState.Offline(1), h.lib.state.value)
        advanceTimeBy(5_001) // first backoff: 5s
        runCurrent()
        assertEquals(2, h.transports.size)
        assertEquals(MaLibraryState.Offline(2), h.lib.state.value)
        advanceTimeBy(5_001) // second backoff doubled to 10s — must NOT have fired yet
        runCurrent()
        assertEquals(2, h.transports.size)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, h.transports.size)
        assertEquals(MaLibraryState.Connected("2.9.9"), h.lib.state.value)
        h.lib.stop()
    }

    @Test
    fun configureIsIdempotentWhileHealthy() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertEquals(1, h.transports.size)
        h.lib.configure(enabled = true, token = "tok") // unchanged config: keep the live socket
        runCurrent()
        assertEquals(1, h.transports.size)
        assertEquals(MaLibraryState.Connected("2.9.9"), h.lib.state.value)
        h.lib.stop()
    }

    @Test
    fun stopTearsDownToDisabled() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertEquals(MaLibraryState.Connected("2.9.9"), h.lib.state.value)
        h.lib.stop()
        runCurrent()
        assertTrue(h.lib.state.value is MaLibraryState.Disabled)
        assertTrue(h.transports[0].disconnects >= 1)
        assertTrue(h.lib.search("beatles").isFailure)
    }

    // ---- ops ----

    @Test
    fun playResolvesEffectiveQueueThenPlaysOnIt() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        val res = h.lib.play("library://track/1", "track", EnqueueMode.NEXT)
        assertTrue(res.isSuccess)
        assertEquals(
            listOf("player_queues/get_active_queue", "player_queues/play_media"),
            h.commands.map { it.first },
        )
        assertEquals("player-1", h.commands[0].second["player_id"])
        val playArgs = h.commands[1].second
        assertEquals("q-77", playArgs["queue_id"])
        assertEquals("library://track/1", playArgs["media"])
        assertEquals("track", playArgs["media_type"])
        assertEquals("next", playArgs["option"])
        h.lib.stop()
    }

    @Test
    fun effectiveQueueIdCachedPerConnection() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.play("library://track/1", "track", EnqueueMode.PLAY).isSuccess)
        assertTrue(h.lib.play("library://track/2", "track", EnqueueMode.PLAY).isSuccess)
        assertEquals(1, h.commands.count { it.first == "player_queues/get_active_queue" })
        h.lib.stop()
    }

    @Test
    fun dropReconnectsAndInvalidatesQueueCache() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.play("library://track/1", "track", EnqueueMode.PLAY).isSuccess)
        h.transports[0].dropWithError("socket closed")
        runCurrent()
        assertTrue(h.lib.state.value is MaLibraryState.Offline)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(2, h.transports.size)
        assertEquals(MaLibraryState.Connected("2.9.9"), h.lib.state.value)
        assertTrue(h.lib.play("library://track/2", "track", EnqueueMode.PLAY).isSuccess)
        // A fresh connection must re-resolve the effective queue (grouping may have changed).
        assertEquals(2, h.commands.count { it.first == "player_queues/get_active_queue" })
        h.lib.stop()
    }

    @Test
    fun playerUnavailableSurfacesAsFailureAndIsNotCached() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        // MA answers get_active_queue with an empty queue_id -> the vendored client throws
        // PlayerUnavailableException: MA doesn't know this player yet (SendSpin endpoint not
        // connected/registered).
        h.transports[0].respond = { cmd ->
            if (cmd == "player_queues/get_active_queue") json("""{"result":{"queue_id":""}}""")
            else json("{}")
        }
        val res = h.lib.play("library://track/1", "track", EnqueueMode.PLAY)
        assertTrue(res.isFailure)
        assertEquals(
            "Panel not registered with Music Assistant — check SendSpin connection",
            res.exceptionOrNull()!!.message,
        )
        // An op failure never poisons the connection state.
        assertTrue(h.lib.state.value is MaLibraryState.Connected)
        // Once the player registers, the very next op re-resolves (no negative caching) — via
        // clearQueue to also pin that the shared queue-resolve path covers the other queue ops.
        h.transports[0].respond = { cmd ->
            if (cmd == "player_queues/get_active_queue") json("""{"result":{"queue_id":"q-77"}}""")
            else json("{}")
        }
        assertTrue(h.lib.clearQueue().isSuccess)
        assertEquals(2, h.commands.count { it.first == "player_queues/get_active_queue" })
        h.lib.stop()
    }

    @Test
    fun removeQueueItemResolvesEffectiveQueueThenSendsDeleteItem() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        val res = h.lib.removeQueueItem("qi-42")
        assertTrue(res.isSuccess)
        assertEquals(
            listOf("player_queues/get_active_queue", "player_queues/delete_item"),
            h.commands.map { it.first },
        )
        val deleteArgs = h.commands[1].second
        assertEquals("q-77", deleteArgs["queue_id"])
        assertEquals("qi-42", deleteArgs["item_id_or_index"])
        h.lib.stop()
    }

    // ---- signIn ----

    @Test
    fun signInDerivesApiUrlFromHostAndReturnsLogin() = runTest {
        val h = Harness(this) // host carries :8927 — sign-in must target ws://host:8095/ws
        val res = h.lib.signIn("andrew", "secret")
        assertTrue(res.isSuccess)
        assertEquals("tok-abc", res.getOrThrow().accessToken)
        assertEquals("Andrew", res.getOrThrow().userName)
        assertEquals(listOf(Triple("ws://10.75.1.54:8095/ws", "andrew", "secret")), h.loginCalls)
        // Sign-in happens before any token exists: it must not require or disturb the connection.
        assertTrue(h.lib.state.value is MaLibraryState.Disabled)
        h.lib.stop()
    }

    @Test
    fun signInWithoutKnownHostFails() = runTest {
        val h = Harness(this)
        h.host = null
        val res = h.lib.signIn("andrew", "secret")
        assertTrue(res.isFailure)
        assertEquals(
            "No Music Assistant server known yet — enable SendSpin and let it connect first",
            res.exceptionOrNull()!!.message,
        )
        assertTrue(h.loginCalls.isEmpty())
        h.lib.stop()
    }

    // ---- favorite / radio ops ----

    @Test
    fun favoriteCurrentSongSendsPlayerId() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.favoriteCurrentSong().isSuccess)
        assertEquals(listOf("players/add_currently_playing_to_favorites"), h.commands.map { it.first })
        assertEquals("player-1", h.commands[0].second["player_id"])
        h.lib.stop()
    }

    @Test
    fun unfavoriteSendsMediaTypeAndLibraryId() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.unfavorite("track", "123").isSuccess)
        assertEquals(listOf("music/favorites/remove_item"), h.commands.map { it.first })
        assertEquals("track", h.commands[0].second["media_type"])
        assertEquals("123", h.commands[0].second["library_item_id"])
        h.lib.stop()
    }

    @Test
    fun playRadioResolvesQueueAndSendsRadioMode() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.playRadio("library://artist/9", "artist").isSuccess)
        assertEquals(
            listOf("player_queues/get_active_queue", "player_queues/play_media"),
            h.commands.map { it.first },
        )
        val playArgs = h.commands[1].second
        assertEquals("q-77", playArgs["queue_id"])
        assertEquals("library://artist/9", playArgs["media"])
        assertEquals("artist", playArgs["media_type"])
        assertEquals(true, playArgs["radio_mode"])
        assertNull(playArgs["option"]) // radio starts fresh (PLAY): option omitted
        h.lib.stop()
    }

    @Test
    fun playOmitsRadioModeByDefault() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.play("library://track/1", "track", EnqueueMode.PLAY).isSuccess)
        val playArgs = h.commands.first { it.first == "player_queues/play_media" }.second
        assertNull(playArgs["radio_mode"]) // non-radio play must never send the flag
        h.lib.stop()
    }

    // ---- library browse / drill-in ops ----

    @Test
    fun libraryArtistsSendsLibraryItemsWithSortName() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.libraryArtists().isSuccess)
        assertEquals(listOf("music/artists/library_items"), h.commands.map { it.first })
        val args = h.commands[0].second
        assertEquals(200, args["limit"])
        assertEquals(0, args["offset"])
        assertEquals("sort_name", args["order_by"])
        h.lib.stop()
    }

    @Test
    fun libraryAlbumsPassesOffsetThrough() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.libraryAlbums(offset = 200).isSuccess)
        assertEquals(listOf("music/albums/library_items"), h.commands.map { it.first })
        val args = h.commands[0].second
        assertEquals(200, args["limit"])
        assertEquals(200, args["offset"])
        assertEquals("sort_name", args["order_by"])
        h.lib.stop()
    }

    @Test
    fun artistAlbumsSendsItemIdAndProviderFromArtist() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        val artist = MaArtist(
            artistId = "art-5", name = "Boards of Canada",
            imageUri = null, uri = "library://artist/art-5", provider = "spotify",
        )
        assertTrue(h.lib.artistAlbums(artist).isSuccess)
        assertEquals(listOf("music/artists/artist_albums"), h.commands.map { it.first })
        val args = h.commands[0].second
        assertEquals("art-5", args["item_id"])
        assertEquals("spotify", args["provider_instance_id_or_domain"])
        h.lib.stop()
    }

    @Test
    fun albumTracksSendsItemIdAndProviderFromAlbum() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        val album = MaAlbum(
            albumId = "alb-9", name = "Geogaddi", imageUri = null,
            uri = "library://album/alb-9", artist = "Boards of Canada",
            year = null, trackCount = null, albumType = null, provider = "library",
        )
        assertTrue(h.lib.albumTracks(album).isSuccess)
        assertEquals(listOf("music/albums/album_tracks"), h.commands.map { it.first })
        val args = h.commands[0].second
        assertEquals("alb-9", args["item_id"])
        assertEquals("library", args["provider_instance_id_or_domain"])
        h.lib.stop()
    }

    // ---- multi-room / speaker ops ----

    @Test
    fun playersSendsPlayersAllAndDoesNotRetryWhenSelfPresent() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        h.transports[0].respond = { cmd ->
            if (cmd == "players/all")
                json("""{"result":[{"player_id":"player-1","name":"This","available":true}]}""")
            else json("{}")
        }
        val res = h.lib.players()
        assertTrue(res.isSuccess)
        assertEquals(1, res.getOrThrow().size)
        // Self present on the first fetch: exactly one call, no protocol arg.
        assertEquals(listOf("players/all"), h.commands.map { it.first })
        assertNull(h.commands[0].second["return_protocol_players"])
        h.lib.stop()
    }

    @Test
    fun playersRetriesWithProtocolWhenSelfMissing() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        var calls = 0
        h.transports[0].respond = { cmd ->
            if (cmd == "players/all") {
                calls++
                if (calls == 1) json("""{"result":[{"player_id":"kitchen","name":"Kitchen","available":true}]}""")
                else json("""{"result":[{"player_id":"player-1","name":"This","available":true},{"player_id":"kitchen","name":"Kitchen","available":true}]}""")
            } else json("{}")
        }
        val res = h.lib.players()
        assertTrue(res.isSuccess)
        // The self-inclusive second fetch is what we return.
        assertEquals(2, res.getOrThrow().size)
        assertEquals(listOf("players/all", "players/all"), h.commands.map { it.first })
        assertNull(h.commands[0].second["return_protocol_players"]) // first: default
        assertEquals(true, h.commands[1].second["return_protocol_players"]) // retry: protocol on
        h.lib.stop()
    }

    @Test
    fun playersRetriesOnceThenReturnsListEvenIfSelfStillAbsent() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        // Self never appears (e.g. this device isn't a known MA player) — never fail for it.
        h.transports[0].respond = { cmd ->
            if (cmd == "players/all")
                json("""{"result":[{"player_id":"kitchen","name":"Kitchen","available":true}]}""")
            else json("{}")
        }
        val res = h.lib.players()
        assertTrue(res.isSuccess)
        assertEquals(1, res.getOrThrow().size)
        // Retried exactly once; no third attempt.
        assertEquals(listOf("players/all", "players/all"), h.commands.map { it.first })
        h.lib.stop()
    }

    @Test
    fun setPlayerVolumeSendsPlayerIdAndLevel() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.setPlayerVolume("kitchen", 42).isSuccess)
        assertEquals(listOf("players/cmd/volume_set"), h.commands.map { it.first })
        assertEquals("kitchen", h.commands[0].second["player_id"])
        assertEquals(42, h.commands[0].second["volume_level"])
        h.lib.stop()
    }

    @Test
    fun groupWithMeSendsTargetPlayerOwnId() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.groupWithMe("kitchen").isSuccess)
        assertEquals(listOf("players/cmd/group"), h.commands.map { it.first })
        assertEquals("kitchen", h.commands[0].second["player_id"])
        assertEquals("player-1", h.commands[0].second["target_player"]) // our own id
        h.lib.stop()
    }

    @Test
    fun ungroupPlayerSendsPlayerId() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.ungroupPlayer("kitchen").isSuccess)
        assertEquals(listOf("players/cmd/ungroup"), h.commands.map { it.first })
        assertEquals("kitchen", h.commands[0].second["player_id"])
        h.lib.stop()
    }

    @Test
    fun transferMyQueueToResolvesQueueThenTransfers() = runTest {
        val h = Harness(this)
        h.lib.configure(enabled = true, token = "tok")
        runCurrent()
        assertTrue(h.lib.transferMyQueueTo("kitchen").isSuccess)
        assertEquals(
            listOf("player_queues/get_active_queue", "player_queues/transfer"),
            h.commands.map { it.first },
        )
        val args = h.commands[1].second
        assertEquals("q-77", args["source_queue_id"]) // our effective queue id
        assertEquals("kitchen", args["target_queue_id"])
        assertNull(args["auto_play"]) // deliberately omitted
        h.lib.stop()
    }
}
