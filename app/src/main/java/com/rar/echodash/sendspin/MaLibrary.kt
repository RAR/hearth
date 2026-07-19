package com.rar.echodash.sendspin

import android.util.Log
import com.rar.echodash.sendspin.musicassistant.EnqueueMode
import com.rar.echodash.sendspin.musicassistant.MaAuthHelper
import com.rar.echodash.sendspin.musicassistant.MaCommandClient
import com.rar.echodash.sendspin.musicassistant.MaPlaylist
import com.rar.echodash.sendspin.musicassistant.MaQueueState
import com.rar.echodash.sendspin.musicassistant.MaRadio
import com.rar.echodash.sendspin.musicassistant.MaTrack
import com.rar.echodash.sendspin.musicassistant.PlayerUnavailableException
import com.rar.echodash.sendspin.musicassistant.SearchResults
import com.rar.echodash.sendspin.musicassistant.transport.MaApiTransport
import com.rar.echodash.sendspin.musicassistant.transport.MaWebSocketTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Connection state of the MA API socket (the config card and MusicBrowser render off this). */
sealed interface MaLibraryState {
    /** SendSpin disabled or no MA token stored — the library feature is off. */
    data object Disabled : MaLibraryState
    data object Connecting : MaLibraryState
    data class Connected(val serverVersion: String?) : MaLibraryState
    /** Token rejected — parked with no auto-retry; the next [MaLibrary.configure] (new sign-in) recovers. */
    data object AuthFailed : MaLibraryState
    /** Connect failed / socket dropped / no host known yet — retrying with backoff while enabled. */
    data class Offline(val attempt: Int) : MaLibraryState
}

/**
 * Owns the Music Assistant JSON-RPC API socket, sibling of [SendspinEndpoint]'s audio socket.
 *
 * Runs only while SendSpin is enabled AND an MA token is stored ([configure]). The API address
 * is derived from the same server the audio path uses (hostProvider: manual config host, else
 * [SendspinEndpoint.connectedHost]) at MA's fixed API port 8095, path `/ws`. API-socket failures
 * never touch the audio path and vice versa — this class shares nothing with the engine.
 *
 * Library/queue ops are thin guards over the vendored [MaCommandClient]; every op returns
 * `Result` and requires [MaLibraryState.Connected]. Commands target the panel's *effective*
 * queue (`player_queues/get_active_queue`), so control lands on the group queue when the panel
 * is grouped in MA. The resolve is cached per connection — one extra round-trip per play
 * otherwise — and only a successful resolve is cached (see [withQueue]).
 */
class MaLibrary(
    private val scope: CoroutineScope, // app scope, like SendspinEndpoint
    private val playerId: String, // UserSettings.getPlayerId()
    private val hostProvider: () -> String?, // App wires: manual config host ?: endpoint.connectedHost()
    // Test seams — defaults build the real vendored client + transport.
    private val clientFactory: () -> MaCommandClient = { MaCommandClient() },
    private val transportFactory: (String) -> MaApiTransport = { url -> MaWebSocketTransport(url) },
    // Seam for sign-in: MaAuthHelper is an `object` that opens a real socket internally, so the
    // lambda (defaulting to the real helper) is the only way to fake the login round-trip.
    private val loginForToken: suspend (url: String, username: String, password: String) -> MaAuthHelper.LoginResult =
        { url, username, password -> MaAuthHelper.loginForToken(url, username, password) },
) {
    private companion object {
        const val TAG = "MaLibrary"
        const val NOT_CONNECTED_MSG = "MA library not connected"
        const val NOT_REGISTERED_MSG = "Panel not registered with Music Assistant — check SendSpin connection"
        const val NO_HOST_MSG = "No Music Assistant server known yet — enable SendSpin and let it connect first"
    }

    private val _state = MutableStateFlow<MaLibraryState>(MaLibraryState.Disabled)
    val state: StateFlow<MaLibraryState> = _state.asStateFlow()

    // ---- Lifecycle state (configure/stop on the caller thread, not re-entrant — SendspinEndpoint idiom) ----

    private var connectJob: Job? = null
    private var activeToken: String? = null

    // Written by the connect loop, read by ops from UI coroutines — @Volatile for visibility.
    @Volatile private var activeTransport: MaApiTransport? = null
    @Volatile private var activeClient: MaCommandClient? = null
    // Effective queue id, resolved once per connection: grouping can change while we're away,
    // so every fresh connection must re-resolve. Exceptions never populate this (see withQueue).
    @Volatile private var cachedQueueId: String? = null

    /**
     * Idempotent (re)configuration: connects when enabled with a token, tears down otherwise.
     * An unchanged enabled+token with a live connect loop is a no-op, so config re-saves don't
     * bounce the socket. After [MaLibraryState.AuthFailed] the loop has exited, so the next
     * configure — normally carrying a fresh token from sign-in — retries.
     */
    fun configure(enabled: Boolean, token: String) {
        val key = token.takeIf { enabled && token.isNotBlank() }
        if (key != null && key == activeToken && connectJob?.isActive == true) return
        teardown()
        activeToken = key
        if (key == null) {
            _state.value = MaLibraryState.Disabled
            return
        }
        _state.value = MaLibraryState.Connecting
        connectJob = scope.launch { runConnectLoop(key) }
    }

    fun stop() {
        teardown()
        activeToken = null
        _state.value = MaLibraryState.Disabled
    }

    // ---- Sign-in (config page; runs before any token exists, so no Connected guard) ----

    /**
     * Exchange username/password for an access token against the derived API URL. The caller
     * (App via ConfigServer) persists the token; the password is used once and never stored.
     */
    suspend fun signIn(username: String, password: String): Result<MaAuthHelper.LoginResult> {
        val host = hostProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException(NO_HOST_MSG))
        return try {
            Result.success(loginForToken(apiWsUrl(host), username, password))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MA sign-in failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ---- Library ops (shelves + search) ----

    suspend fun playlists(): Result<List<MaPlaylist>> = withClient { it.getPlaylists() }

    suspend fun radios(): Result<List<MaRadio>> = withClient { it.getRadioStations() }

    suspend fun recentlyPlayed(): Result<List<MaTrack>> = withClient { it.getRecentlyPlayed() }

    suspend fun search(query: String): Result<SearchResults> = withClient { it.search(query) }

    // ---- Queue ops (all resolve the panel's effective queue first) ----

    suspend fun play(uri: String, mediaType: String?, mode: EnqueueMode): Result<Unit> =
        withQueue { client, queueId -> client.playMedia(uri, queueId, mediaType, mode) }

    suspend fun queue(): Result<MaQueueState> =
        withQueue { client, queueId -> client.getQueueItems(queueId) }

    suspend fun jumpTo(queueItemId: String): Result<Unit> =
        withQueue { client, queueId -> client.playQueueItem(queueId, queueItemId) }

    suspend fun clearQueue(): Result<Unit> =
        withQueue { client, queueId -> client.clearQueue(queueId) }

    suspend fun removeQueueItem(queueItemId: String): Result<Unit> =
        withQueue { client, queueId -> client.deleteQueueItem(queueId, queueItemId) }

    // ---- Favorite / radio ops ----

    /**
     * Add the current song to library favorites. Uses the device's own player id (not the
     * effective queue) — the server resolves the active queue's current item itself, so this
     * takes [withClient], not [withQueue].
     */
    suspend fun favoriteCurrentSong(): Result<Unit> =
        withClient { it.addCurrentToFavorites(playerId) }

    /** Remove an already-favorited library item (type + library id read off the queue item). */
    suspend fun unfavorite(mediaType: String, libraryItemId: String): Result<Unit> =
        withClient { it.removeFavorite(mediaType, libraryItemId) }

    /**
     * Start MA dynamic radio seeded from [uri]: replaces the queue and self-refills with similar
     * tracks (radio_mode on play_media, native on MA 2.9.x). Mirrors [play]'s effective-queue
     * resolve via [withQueue].
     */
    suspend fun playRadio(uri: String, mediaType: String?): Result<Unit> =
        withQueue { client, queueId -> client.playMedia(uri, queueId, mediaType, EnqueueMode.PLAY, radioMode = true) }

    // ---- Connection loop ----

    private suspend fun runConnectLoop(token: String) {
        var attempt = 0
        while (true) {
            val host = hostProvider()?.trim()?.takeIf { it.isNotBlank() }
            if (host != null) {
                _state.value = MaLibraryState.Connecting
                val transport = transportFactory(apiWsUrl(host))
                activeTransport = transport
                try {
                    transport.connect(token)
                    val client = clientFactory()
                    // isRemoteMode hard-wired false: Hearth vendors the LOCAL path only.
                    client.setTransport(transport, apiHttpUrl(host), isRemoteMode = false)
                    cachedQueueId = null
                    activeClient = client
                    attempt = 0
                    _state.value = MaLibraryState.Connected(transport.serverVersion)
                    Log.i(TAG, "MA API connected (server ${transport.serverVersion ?: "?"})")
                    // Park until the socket drops. Only Error signals a drop: Disconnected is
                    // always our own disconnect() (teardown), whose cancel unwinds this loop.
                    transport.state.first { it is MaApiTransport.State.Error }
                    Log.w(TAG, "MA API socket dropped")
                } catch (e: CancellationException) {
                    // teardown() already disconnects activeTransport; rethrow so a cancelled loop
                    // never writes another state (it would clobber the Disabled set by stop()).
                    throw e
                } catch (e: MaApiTransport.AuthenticationException) {
                    Log.w(TAG, "MA token rejected: ${e.message}")
                    cleanupConnection()
                    _state.value = MaLibraryState.AuthFailed
                    return // park — no auto-retry hammers a server that just refused the token
                } catch (e: Exception) {
                    Log.w(TAG, "MA API connect failed: ${e.message}")
                }
                cleanupConnection()
            }
            attempt += 1
            _state.value = MaLibraryState.Offline(attempt)
            delay(backoffMs(attempt))
        }
    }

    /** Drop client + queue cache and close the socket (from the loop and from teardown). */
    private fun cleanupConnection() {
        activeClient = null
        cachedQueueId = null
        activeTransport?.disconnect()
        activeTransport = null
    }

    private fun teardown() {
        connectJob?.cancel()
        connectJob = null
        cleanupConnection()
    }

    /** 5s doubling to a 60s cap — the sendspin-style reconnect pacing. */
    private fun backoffMs(attempt: Int): Long =
        (5_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(60_000L)

    // The MA API listens on fixed port 8095. A manual serverAddress carries the SendSpin port
    // (8927) and discovery may report host:port too — always strip the port and rebuild.
    private fun apiWsUrl(host: String) = "ws://${host.substringBefore(':')}:8095/ws"

    private fun apiHttpUrl(host: String) = "http://${host.substringBefore(':')}:8095"

    // ---- Op plumbing ----

    private fun connectedClient(): MaCommandClient? =
        if (_state.value is MaLibraryState.Connected) activeClient else null

    private suspend fun <T> withClient(op: suspend (MaCommandClient) -> Result<T>): Result<T> {
        val client = connectedClient()
            ?: return Result.failure(IllegalStateException(NOT_CONNECTED_MSG))
        return op(client)
    }

    private suspend fun <T> withQueue(op: suspend (MaCommandClient, String) -> Result<T>): Result<T> =
        withClient { client ->
            val queueId = try {
                // Cache only a successful resolve; one round-trip per connection instead of per op.
                cachedQueueId ?: client.getEffectiveQueueId(playerId).also { cachedQueueId = it }
            } catch (e: PlayerUnavailableException) {
                // MA has no queue for this player id: the SendSpin audio endpoint isn't
                // connected/registered (yet). Surface the real condition and never cache —
                // the next op re-resolves once the endpoint registers.
                return@withClient Result.failure(IllegalStateException(NOT_REGISTERED_MSG, e))
            }
            op(client, queueId)
        }
}
