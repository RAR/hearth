// Vendored from SendSpinDroid (see NOTICE); Hearth adaptations documented in AGENTS.md.
package com.rar.echodash.sendspin.musicassistant

import com.rar.echodash.sendspin.musicassistant.model.MaMediaType
import com.rar.echodash.sendspin.musicassistant.transport.MaApiTransport
import com.rar.echodash.sendspin.musicassistant.transport.optBoolean
import com.rar.echodash.sendspin.musicassistant.transport.optInt
import com.rar.echodash.sendspin.musicassistant.transport.optJsonArray
import com.rar.echodash.sendspin.musicassistant.transport.optJsonObject
import com.rar.echodash.sendspin.musicassistant.transport.optLong
import com.rar.echodash.sendspin.musicassistant.transport.optString
import com.rar.echodash.sendspin.shared.log.Log
import com.rar.echodash.sendspin.shared.platform.Platform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.rar.echodash.sendspin.musicassistant.transport.MaTransportException

/**
 * Shared command client for the Music Assistant API.
 *
 * Encapsulates all MA command methods and JSON parsing logic in a
 * platform-independent class. The transport, API URL, and connection
 * mode are injected by the platform-specific manager.
 *
 * ## Design
 * - All parsing uses `kotlinx.serialization.json.JsonObject/JsonArray`
 * - Image URL extraction is parameterized (no Android dependencies)
 * - Transport is set externally via [setTransport]
 *
 * ## Usage
 * ```kotlin
 * val client = MaCommandClient()
 * client.setTransport(transport, "ws://host:8095/ws", isRemoteMode = false)
 * val results = client.search("beatles")
 * ```
 */
class MaCommandClient {

    companion object {
        private const val TAG = "MaCommandClient"
        private const val COMMAND_TIMEOUT_MS = 15000L

        /**
         * Scheme used for proxying image URLs through the MA API DataChannel.
         * Matches MaProxyImageFetcher.SCHEME on Android.
         */
        const val IMAGE_PROXY_SCHEME = "ma-proxy"

        /**
         * Thumbnail size requested from MA's imageproxy. MA only accepts a fixed
         * set of sizes ({0, 80, 160, 256, 512, 1024}); any other value is rejected
         * with HTTP 400. 256 suits the browse/search grid thumbnails.
         */
        private const val IMAGE_PROXY_SIZE = 256
    }

    /**
     * Immutable snapshot of the active transport and its connection context.
     *
     * Bundled into a single data class so that [setTransport] publishes all
     * three values atomically via a single @Volatile write. Readers always
     * see a consistent combination of transport, API URL, and remote mode.
     */
    internal data class TransportContext(
        val transport: MaApiTransport? = null,
        val apiUrl: String? = null,
        val isRemoteMode: Boolean = false
    )

    @Volatile
    private var transportContext = TransportContext()

    /**
     * Set the active transport and connection context.
     *
     * All three fields are published atomically as a single [TransportContext]
     * reference. This prevents readers from observing a stale apiUrl paired
     * with a new transport or vice-versa.
     *
     * @param transport The connected MA API transport, or null to disconnect
     * @param apiUrl The MA API URL (used for image URL construction)
     * @param isRemoteMode Whether connected via WebRTC (affects image proxy URLs)
     */
    fun setTransport(transport: MaApiTransport?, apiUrl: String?, isRemoteMode: Boolean) {
        transportContext = TransportContext(transport, apiUrl, isRemoteMode)
    }

    // ========================================================================
    // Transport Command Sending
    // ========================================================================

    /**
     * Send a command to the MA API via the active transport.
     *
     * @param command The MA command (e.g., "players/all")
     * @param args Command arguments
     * @return The JSON response
     * @throws MaTransportException if transport is not connected
     */
    internal suspend fun sendCommand(
        command: String,
        args: Map<String, Any> = emptyMap()
    ): JsonObject {
        val ctx = transportContext
        val t = ctx.transport ?: throw MaTransportException("MA API transport not connected")
        return t.sendCommand(command, args, COMMAND_TIMEOUT_MS)
    }

    // ========================================================================
    // Player Commands
    // ========================================================================

    /**
     * Resolve the effective queue ID using the server's active queue resolution.
     *
     * Uses the server's player_queues/get_active_queue API which properly handles
     * synced_to, active_group, active_source, and protocol player fallbacks.
     *
     * @param devicePlayerId This device's player ID
     * @return The queue_id to use for queue operations
     */
    suspend fun getEffectiveQueueId(devicePlayerId: String): String {
        return try {
            val response = sendCommand(
                "player_queues/get_active_queue",
                mapOf("player_id" to devicePlayerId)
            )
            val result = response.optJsonObject("result")
            val queueId = result?.optString("queue_id")

            if (queueId.isNullOrEmpty()) {
                throw PlayerUnavailableException(devicePlayerId)
            }

            if (queueId != devicePlayerId) {
                Log.d(TAG, "Active queue resolved to: $queueId (our ID: $devicePlayerId)")
            }
            queueId
        } catch (e: PlayerUnavailableException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve active queue, using own player ID", e)
            devicePlayerId
        }
    }

    // ========================================================================
    // Playback Commands
    // ========================================================================

    /**
     * Play a media item on the specified queue.
     */
    suspend fun playMedia(
        uri: String,
        queueId: String,
        mediaType: String? = null,
        enqueueMode: EnqueueMode = EnqueueMode.PLAY,
        radioMode: Boolean = false,
    ): Result<Unit> {
        return try {
            Log.d(TAG, "${enqueueMode.name} media: $uri on queue: $queueId (radio=$radioMode)")
            val args = mutableMapOf<String, Any>(
                "queue_id" to queueId,
                "media" to uri
            )
            if (mediaType != null) {
                args["media_type"] = mediaType
            }
            enqueueMode.apiValue?.let { args["option"] = it }
            // MA 2.9.x: native dynamic-radio refill. (2.10+ deprecates-but-translates radio_mode.)
            if (radioMode) args["radio_mode"] = true

            sendCommand("player_queues/play_media", args)
            Log.i(TAG, "Successfully ${enqueueMode.name}: $uri")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ${enqueueMode.name} media: $uri", e)
            Result.failure(e)
        }
    }

    /**
     * Add the current queue item to library favorites. The server resolves the active queue's
     * current item itself (it even resolves a radio station's stream title to a real track);
     * raises PlayerCommandFailed — surfaced here as Result.failure — when nothing is resolvable.
     */
    suspend fun addCurrentToFavorites(playerId: String): Result<Unit> {
        return try {
            sendCommand(
                "players/add_currently_playing_to_favorites",
                mapOf("player_id" to playerId),
            )
            Log.i(TAG, "Favorited current item on player: $playerId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to favorite current item on player: $playerId", e)
            Result.failure(e)
        }
    }

    /** Remove a library item from favorites by media type + library item id. */
    suspend fun removeFavorite(mediaType: String, libraryItemId: String): Result<Unit> {
        return try {
            sendCommand(
                "music/favorites/remove_item",
                mapOf("media_type" to mediaType, "library_item_id" to libraryItemId),
            )
            Log.i(TAG, "Removed favorite: $mediaType/$libraryItemId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove favorite: $mediaType/$libraryItemId", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Queue Management Commands
    // ========================================================================

    /**
     * Get queue items for a player.
     */
    suspend fun getQueueItems(queueId: String, limit: Int = 200, offset: Int = 0): Result<MaQueueState> {
        return try {
            Log.d(TAG, "Fetching queue items for player: $queueId (limit=$limit, offset=$offset)")
            val queueResponse = sendCommand(
                "player_queues/get",
                mapOf("queue_id" to queueId)
            )
            val itemsResponse = sendCommand(
                "player_queues/items",
                mapOf("queue_id" to queueId, "limit" to limit, "offset" to offset)
            )
            val queueState = parseQueueState(queueResponse, itemsResponse)
            Log.i(TAG, "Fetched ${queueState.items.size} queue items (current index: ${queueState.currentIndex})")
            Result.success(queueState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch queue items", e)
            Result.failure(e)
        }
    }

    /**
     * Clear all items from the queue.
     */
    suspend fun clearQueue(queueId: String): Result<Unit> {
        return try {
            sendCommand("player_queues/clear", mapOf("queue_id" to queueId))
            Log.i(TAG, "Queue cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear queue", e)
            Result.failure(e)
        }
    }

    /**
     * Jump to and play a specific item in the queue.
     */
    suspend fun playQueueItem(queueId: String, queueItemId: String): Result<Unit> {
        return try {
            sendCommand(
                "player_queues/play_index",
                mapOf("queue_id" to queueId, "index" to queueItemId)
            )
            Log.i(TAG, "Jumped to queue item: $queueItemId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play queue item: $queueItemId", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Library Commands
    // ========================================================================

    /**
     * Get recently played items.
     */
    suspend fun getRecentlyPlayed(limit: Int = 15): Result<List<MaTrack>> {
        return try {
            Log.d(TAG, "Fetching recently played items (limit=$limit)")
            val response = sendCommand("music/recently_played_items", mapOf("limit" to limit))
            val items = parseMediaItems(response)
            Log.d(TAG, "Got ${items.size} recently played items")
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recently played", e)
            Result.failure(e)
        }
    }

    /**
     * Get playlists from the library.
     */
    suspend fun getPlaylists(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaPlaylist>> {
        return try {
            Log.d(TAG, "Fetching playlists (limit=$limit, offset=$offset, orderBy=$orderBy)")
            val response = sendCommand(
                "music/playlists/library_items",
                mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
            )
            val playlists = parsePlaylists(response)
            Log.d(TAG, "Got ${playlists.size} playlists")
            Result.success(playlists)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlists", e)
            Result.failure(e)
        }
    }

    /**
     * Get radio stations from the library.
     */
    suspend fun getRadioStations(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaRadio>> {
        return try {
            Log.d(TAG, "Fetching radio stations (limit=$limit, offset=$offset, orderBy=$orderBy)")
            val response = sendCommand(
                "music/radios/library_items",
                mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
            )
            val radios = parseRadioStations(response)
            Log.d(TAG, "Got ${radios.size} radio stations")
            Result.success(radios)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch radio stations", e)
            Result.failure(e)
        }
    }

    /**
     * Search Music Assistant library.
     */
    suspend fun search(
        query: String,
        mediaTypes: List<MaMediaType>? = null,
        limit: Int = 25,
        libraryOnly: Boolean = true
    ): Result<SearchResults> {
        if (query.length < 2) {
            return Result.failure(Exception("Query too short (minimum 2 characters)"))
        }

        return try {
            Log.d(TAG, "Searching for: '$query' (mediaTypes=$mediaTypes, limit=$limit, libraryOnly=$libraryOnly)")

            val args = mutableMapOf<String, Any>(
                "search_query" to query,
                "limit" to limit,
                "library_only" to libraryOnly
            )

            if (mediaTypes != null && mediaTypes.isNotEmpty()) {
                val typeStrings = mediaTypes.map { type ->
                    when (type) {
                        MaMediaType.TRACK -> "track"
                        MaMediaType.ALBUM -> "album"
                        MaMediaType.ARTIST -> "artist"
                        MaMediaType.PLAYLIST -> "playlist"
                        MaMediaType.RADIO -> "radio"
                        MaMediaType.PODCAST -> "podcast"
                        MaMediaType.AUDIOBOOK -> "audiobook"
                        MaMediaType.FOLDER -> "folder"
                    }
                }
                args["media_types"] = typeStrings
            }

            val response = sendCommand("music/search", args)
            val results = parseSearchResults(response)

            Log.d(TAG, "Search returned ${results.totalCount()} results " +
                    "(${results.artists.size} artists, ${results.albums.size} albums, " +
                    "${results.tracks.size} tracks, ${results.playlists.size} playlists, " +
                    "${results.radios.size} radios)")

            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for query: '$query'", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // JSON Parsing — Tracks / Media Items
    // ========================================================================

    internal fun parseMediaItems(response: JsonObject): List<MaTrack> {
        val items = mutableListOf<MaTrack>()

        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return items

        for (i in 0 until resultArray.size) {
            val item = (resultArray[i] as? JsonObject) ?: continue
            val mediaItem = parseMediaItem(item)
            if (mediaItem != null) {
                items.add(mediaItem)
            }
        }

        return items
    }

    internal fun parseMediaItem(json: JsonObject): MaTrack? {
        val mediaType = json.optString("media_type").ifEmpty { "track" }
        if (mediaType != "track") {
            Log.d(TAG, "Skipping non-track item: media_type=$mediaType, name=${json.optString("name")}")
            return null
        }

        val itemId = json.optString("item_id")
            .ifEmpty { json.optString("track_id") }
            .ifEmpty { json.optString("album_id") }
            .ifEmpty { json.optString("uri") }

        if (itemId.isEmpty()) return null

        val name = json.optString("name")
            .ifEmpty { json.optString("title") }

        if (name.isEmpty()) return null

        // Artist can be a string or an object with name
        val artist = extractArtistName(json)

        // Album can be string or object - extract both name and metadata
        val albumObj = json.optJsonObject("album")
        val album = if (albumObj != null) {
            albumObj.optString("name")
        } else {
            // Only use optString for album if it's NOT a json object
            val albumElement = json["album"]
            if (albumElement is JsonPrimitive) albumElement.contentOrNull ?: "" else ""
        }

        val albumId = albumObj?.optString("item_id")?.ifEmpty { null }
            ?: albumObj?.optString("album_id")?.ifEmpty { null }
        val albumType = albumObj?.optString("album_type")?.ifEmpty { null }

        val imageUri = extractImageUri(json)
        val uri = json.optString("uri")
        val duration = json.optLong("duration", 0L).takeIf { it > 0 }

        return MaTrack(
            itemId = itemId,
            name = name,
            artist = artist.ifEmpty { null },
            album = album.ifEmpty { null },
            imageUri = imageUri.ifEmpty { null },
            uri = uri.ifEmpty { null },
            duration = duration,
            albumId = albumId,
            albumType = albumType
        )
    }

    /**
     * Extract artist name from a JSON item. Handles string, object, and array forms.
     */
    private fun extractArtistName(json: JsonObject): String {
        // Check if "artist" is a primitive string
        val artistElement = json["artist"]
        if (artistElement is JsonPrimitive) {
            val direct = artistElement.contentOrNull ?: ""
            if (direct.isNotEmpty()) return direct
        }

        // Check if "artist" is an object with "name"
        val artistObj = json.optJsonObject("artist")
        if (artistObj != null) {
            val name = artistObj.optString("name")
            if (name.isNotEmpty()) return name
        }

        // Try artists array
        val artists = json.optJsonArray("artists")
        if (artists != null && artists.size > 0) {
            val firstArtist = artists[0] as? JsonObject
            val name = firstArtist?.optString("name") ?: ""
            if (name.isNotEmpty()) return name
        }

        return ""
    }

    /**
     * Extract item ID from a URI when item_id is not provided by API.
     * Handles formats like:
     * - "spotify://album/ALBUMID" -> "ALBUMID"
     * - "spotify://playlist/PLAYLISTID" -> "PLAYLISTID"
     * - "spotify://artist/ARTISTID" -> "ARTISTID"
     */
    private fun extractIdFromUri(uri: String): String {
        if (uri.isEmpty()) return ""
        
        // Format: "provider://type/ID" or "provider://type/ID/..."
        val parts = uri.split("/")
        // parts[0] = "spotify:", parts[1] = "", parts[2] = "album", parts[3] = "ID", ...
        return if (parts.size >= 4) parts[3] else ""
    }

    internal fun parseTracksArray(array: JsonArray?): List<MaTrack> {
        if (array == null) return emptyList()
        val tracks = mutableListOf<MaTrack>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue
            val track = parseMediaItem(item)
            if (track != null) {
                tracks.add(track)
            }
        }

        return tracks
    }

    // ========================================================================
    // JSON Parsing — Albums
    // ========================================================================

    internal fun parseAlbumsArray(array: JsonArray?): List<MaAlbum> {
        if (array == null) return emptyList()
        val albums = mutableListOf<MaAlbum>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val albumId = item.optString("item_id")
                .ifEmpty { item.optString("album_id") }
                .ifEmpty { 
                    // If no item_id, try to extract from URI (e.g., "spotify://album/MPREb_vVxj6mSH57Q" -> "MPREb_vVxj6mSH57Q")
                    extractIdFromUri(item.optString("uri"))
                }

            if (albumId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val artist = extractArtistName(item)

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { "library://album/$albumId" }
            val year = item.optInt("year", 0).takeIf { it > 0 }
            val trackCount = item.optInt("track_count", 0).takeIf { it > 0 }
            val albumType = item.optString("album_type").ifEmpty { null }
            val provider = item.optString("provider").ifEmpty {
                val mappings = item.optJsonArray("provider_mappings")
                if (mappings != null && mappings.size > 0) {
                    (mappings[0] as? JsonObject)?.optString("provider_domain") ?: "library"
                } else "library"
            }

            albums.add(MaAlbum(
                albumId = albumId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                artist = artist.ifEmpty { null },
                year = year,
                trackCount = trackCount,
                albumType = albumType,
                provider = provider
            ))
        }

        return albums
    }

    // ========================================================================
    // JSON Parsing — Artists
    // ========================================================================

    internal fun parseArtistsArray(array: JsonArray?): List<MaArtist> {
        if (array == null) return emptyList()
        val artists = mutableListOf<MaArtist>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val artistId = item.optString("item_id")
                .ifEmpty { item.optString("artist_id") }
                .ifEmpty { 
                    // If no item_id, try to extract from URI
                    extractIdFromUri(item.optString("uri"))
                }

            if (artistId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { "library://artist/$artistId" }
            val provider = item.optString("provider").ifEmpty {
                val mappings = item.optJsonArray("provider_mappings")
                if (mappings != null && mappings.size > 0) {
                    (mappings[0] as? JsonObject)?.optString("provider_domain") ?: "library"
                } else "library"
            }

            artists.add(MaArtist(
                artistId = artistId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                provider = provider
            ))
        }

        return artists
    }

    // ========================================================================
    // JSON Parsing — Playlists
    // ========================================================================

    internal fun parsePlaylists(response: JsonObject): List<MaPlaylist> {
        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return emptyList()

        return parsePlaylistsArray(resultArray)
    }

    internal fun parsePlaylistsArray(array: JsonArray?): List<MaPlaylist> {
        if (array == null) return emptyList()
        val playlists = mutableListOf<MaPlaylist>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val playlistId = item.optString("item_id")
                .ifEmpty { item.optString("playlist_id") }
                .ifEmpty { 
                    // If no item_id, try to extract from URI
                    extractIdFromUri(item.optString("uri"))
                }

            if (playlistId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val trackCount = item.optInt("track_count", 0)
            val owner = item.optString("owner").ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { null }
            val provider = item.optString("provider").ifEmpty {
                val mappings = item.optJsonArray("provider_mappings")
                if (mappings != null && mappings.size > 0) {
                    (mappings[0] as? JsonObject)?.optString("provider_domain") ?: "library"
                } else "library"
            }

            playlists.add(MaPlaylist(
                playlistId = playlistId,
                name = name,
                imageUri = imageUri,
                trackCount = trackCount,
                owner = owner,
                uri = uri,
                provider = provider
            ))
        }

        return playlists
    }

    // ========================================================================
    // JSON Parsing — Radios
    // ========================================================================

    internal fun parseRadioStations(response: JsonObject): List<MaRadio> {
        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return emptyList()

        return parseRadiosArray(resultArray)
    }

    internal fun parseRadiosArray(array: JsonArray?): List<MaRadio> {
        if (array == null) return emptyList()
        val radios = mutableListOf<MaRadio>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val radioId = item.optString("item_id")
                .ifEmpty { item.optString("radio_id") }
                .ifEmpty { 
                    // If no item_id, try to extract from URI
                    extractIdFromUri(item.optString("uri"))
                }

            if (radioId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { "library://radio/$radioId" }
            val provider = item.optString("provider").ifEmpty {
                val mappings = item.optJsonArray("provider_mappings")
                if (mappings != null && mappings.size > 0) {
                    (mappings[0] as? JsonObject)?.optString("provider_domain") ?: ""
                } else ""
            }

            radios.add(MaRadio(
                radioId = radioId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                provider = provider.ifEmpty { null }
            ))
        }

        return radios
    }

    // ========================================================================
    // JSON Parsing — Search
    // ========================================================================

    internal fun parseSearchResults(response: JsonObject): SearchResults {
        val result = response.optJsonObject("result") ?: return SearchResults()

        return SearchResults(
            artists = parseArtistsArray(result.optJsonArray("artists")),
            albums = parseAlbumsArray(result.optJsonArray("albums")),
            tracks = parseTracksArray(result.optJsonArray("tracks")),
            playlists = parsePlaylistsArray(result.optJsonArray("playlists")),
            radios = parseRadiosArray(result.optJsonArray("radio"))
        )
    }

    // ========================================================================
    // JSON Parsing — Queue
    // ========================================================================

    internal fun parseQueueState(queueResponse: JsonObject, itemsResponse: JsonObject): MaQueueState {
        val queueResult = queueResponse.optJsonObject("result") ?: queueResponse
        val shuffleEnabled = queueResult.optBoolean("shuffle_enabled", false)
        val repeatModeRaw = queueResult.optString("repeat_mode").ifEmpty { "off" }
        val repeatMode = when {
            repeatModeRaw.contains("one", ignoreCase = true) -> "one"
            repeatModeRaw.contains("all", ignoreCase = true) -> "all"
            else -> "off"
        }
        val currentIndex = queueResult.optInt("current_index", -1)
        val currentItemId = queueResult.optString("current_item")

        val items = mutableListOf<MaQueueItem>()
        val resultArray = itemsResponse.optJsonArray("result")
            ?: itemsResponse.optJsonObject("result")?.optJsonArray("items")
            ?: JsonArray(emptyList())

        for (i in 0 until resultArray.size) {
            val item = (resultArray[i] as? JsonObject) ?: continue

            val queueItemId = item.optString("queue_item_id")
                .ifEmpty { item.optString("item_id") }
                .ifEmpty { item.optString("id") }

            if (queueItemId.isEmpty()) continue

            val itemName = item.optString("name").ifEmpty {
                item.optJsonObject("media_item")?.optString("name") ?: ""
            }

            val mediaItem = item.optJsonObject("media_item")
            val artist = extractQueueItemArtist(item, mediaItem)
            val album = extractQueueItemAlbum(item, mediaItem)
            val imageUri = extractQueueItemImage(item, mediaItem)
            val duration = item.optLong("duration", 0L).let { if (it > 0) it else null }
            val uri = item.optString("uri")
                .ifEmpty { mediaItem?.optString("uri") ?: "" }
                .ifEmpty { null }

            // Favorite state + library identity live on the nested media_item (the queue-item
            // wrapper carries neither). item_id may arrive as a JSON number; optString coerces it.
            val favorite = mediaItem?.optBoolean("favorite", false) ?: false
            val mediaItemId = mediaItem?.optString("item_id")?.ifEmpty { null }
            val mediaType = mediaItem?.optString("media_type")?.ifEmpty { null }

            val isCurrentItem = currentItemId.isNotEmpty() && queueItemId == currentItemId

            items.add(MaQueueItem(
                queueItemId = queueItemId,
                name = itemName.ifEmpty { "Unknown Track" },
                artist = artist,
                album = album,
                imageUri = imageUri,
                duration = duration,
                uri = uri,
                isCurrentItem = isCurrentItem,
                favorite = favorite,
                mediaItemId = mediaItemId,
                mediaType = mediaType,
            ))
        }

        return MaQueueState(
            items = items,
            currentIndex = currentIndex,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode
        )
    }

    /**
     * Extract artist name from a queue item, handling string/object/array forms.
     */
    private fun extractQueueItemArtist(item: JsonObject, mediaItem: JsonObject?): String? {
        // Direct artist field (only if it's a string primitive, not an object/array)
        val artistElement = item["artist"]
        if (artistElement is JsonPrimitive) {
            val direct = artistElement.contentOrNull ?: ""
            if (direct.isNotEmpty()) return direct
        }

        // From media_item
        if (mediaItem != null) {
            val mediaArtistElement = mediaItem["artist"]
            if (mediaArtistElement is JsonPrimitive) {
                val mediaArtist = mediaArtistElement.contentOrNull ?: ""
                if (mediaArtist.isNotEmpty()) return mediaArtist
            }

            // From media_item.artists array
            val artists = mediaItem.optJsonArray("artists")
            if (artists != null && artists.size > 0) {
                val firstArtist = artists[0] as? JsonObject
                val artistName = firstArtist?.optString("name")
                if (!artistName.isNullOrEmpty()) return artistName
            }
        }

        // From item.artists array
        val artists = item.optJsonArray("artists")
        if (artists != null && artists.size > 0) {
            val firstArtist = artists[0] as? JsonObject
            val artistName = firstArtist?.optString("name")
            if (!artistName.isNullOrEmpty()) return artistName
        }

        // artist field might be an object with a name property
        item.optJsonObject("artist")?.let { artistObj ->
            val name = artistObj.optString("name")
            if (name.isNotEmpty()) return name
        }
        mediaItem?.optJsonObject("artist")?.let { artistObj ->
            val name = artistObj.optString("name")
            if (name.isNotEmpty()) return name
        }

        return null
    }

    /**
     * Extract album name from a queue item, handling string/object forms.
     */
    private fun extractQueueItemAlbum(item: JsonObject, mediaItem: JsonObject?): String? {
        // Direct album field (only if it's a string primitive)
        val albumElement = item["album"]
        if (albumElement is JsonPrimitive) {
            val direct = albumElement.contentOrNull ?: ""
            if (direct.isNotEmpty()) return direct
        }

        // From media_item
        if (mediaItem != null) {
            val mediaAlbumElement = mediaItem["album"]
            if (mediaAlbumElement is JsonPrimitive) {
                val mediaAlbum = mediaAlbumElement.contentOrNull ?: ""
                if (mediaAlbum.isNotEmpty()) return mediaAlbum
            }

            val albumObj = mediaItem.optJsonObject("album")
            if (albumObj != null) {
                val albumName = albumObj.optString("name")
                if (albumName.isNotEmpty()) return albumName
            }
        }

        // From item.album object
        val albumObj = item.optJsonObject("album")
        if (albumObj != null) {
            val albumName = albumObj.optString("name")
            if (albumName.isNotEmpty()) return albumName
        }

        return null
    }

    /**
     * Extract image URI from a queue item.
     */
    private fun extractQueueItemImage(item: JsonObject, mediaItem: JsonObject?): String? {
        val itemImage = extractImageUri(item)
        if (itemImage.isNotEmpty()) return itemImage

        if (mediaItem != null) {
            val mediaImage = extractImageUri(mediaItem)
            if (mediaImage.isNotEmpty()) return mediaImage

            val albumObj = mediaItem.optJsonObject("album")
            if (albumObj != null) {
                val albumImage = extractImageUri(albumObj)
                if (albumImage.isNotEmpty()) return albumImage
            }
        }

        val albumObj = item.optJsonObject("album")
        if (albumObj != null) {
            val albumImage = extractImageUri(albumObj)
            if (albumImage.isNotEmpty()) return albumImage
        }

        return null
    }

    // ========================================================================
    // Image URL Extraction
    // ========================================================================

    /**
     * Extract image URI from MA item JSON.
     *
     * Uses the current API URL and remote mode to construct appropriate image URLs.
     * In REMOTE mode, server-local URLs are rewritten to use the proxy scheme.
     */
    internal fun extractImageUri(json: JsonObject): String {
        val ctx = transportContext
        val apiUrl = ctx.apiUrl ?: ""
        val remoteMode = ctx.isRemoteMode
        val baseUrl = if (remoteMode) {
            "$IMAGE_PROXY_SCHEME://"
        } else {
            apiUrl
                .replace("/ws", "")
                .replace("wss://", "https://")
                .replace("ws://", "http://")
        }

        // Try direct image field - can be a URL string or a JsonObject with path/provider
        val imageField = json["image"]
        when (imageField) {
            is JsonPrimitive -> {
                val imageStr = imageField.contentOrNull ?: ""
                if (imageStr.startsWith("http")) {
                    return maybeProxyImageUrl(imageStr, remoteMode, baseUrl)
                }
            }
            is JsonObject -> {
                val url = buildImageProxyUrl(imageField, baseUrl)
                if (url.isNotEmpty()) return url
            }
            else -> { /* null, JsonArray — skip */ }
        }

        if (baseUrl.isEmpty()) return ""

        // Try metadata.images array
        val metadata = json.optJsonObject("metadata")
        if (metadata != null) {
            val imageUrl = extractImageFromMetadata(metadata, baseUrl)
            if (imageUrl.isNotEmpty()) return imageUrl
        }

        // Try album.image as fallback
        val album = json.optJsonObject("album")
        if (album != null) {
            val albumImageField = album["image"]
            when (albumImageField) {
                is JsonPrimitive -> {
                    val imageStr = albumImageField.contentOrNull ?: ""
                    if (imageStr.startsWith("http")) {
                        return maybeProxyImageUrl(imageStr, remoteMode, baseUrl)
                    }
                }
                is JsonObject -> {
                    val url = buildImageProxyUrl(albumImageField, baseUrl)
                    if (url.isNotEmpty()) return url
                }
                else -> { /* null, JsonArray — skip */ }
            }

            val albumMetadata = album.optJsonObject("metadata")
            if (albumMetadata != null) {
                val imageUrl = extractImageFromMetadata(albumMetadata, baseUrl)
                if (imageUrl.isNotEmpty()) return imageUrl
            }
        }

        return ""
    }

    /**
     * In REMOTE mode, rewrite server-local image URLs to use the proxy scheme.
     */
    private fun maybeProxyImageUrl(url: String, remoteMode: Boolean, baseUrl: String): String {
        if (!remoteMode) return url

        val proxyIndex = url.indexOf("/imageproxy")
        if (proxyIndex >= 0) {
            val pathAndQuery = url.substring(proxyIndex)
            return "$baseUrl$pathAndQuery"
        }

        return url
    }

    /**
     * Build an image URL from an MA image object (`path` / `provider` /
     * `remotely_accessible`).
     *
     * MA 2.x serves proxied images from the canonical path-style endpoint:
     * ```
     * {baseUrl}/imageproxy/{imageId}?size={size}&fmt=jpeg
     * ```
     * where `imageId = sha256("{provider}/{path}")` (MA's `create_thumb_hash`).
     * The older query form (`?provider=&path=`) is the deprecated legacy endpoint
     * and is rejected by MA 2.9+ with HTTP 400, so it must not be used.
     *
     * Remotely-accessible images already carry a reachable URL, so they are
     * returned as-is rather than round-tripped through the proxy.
     */
    private fun buildImageProxyUrl(imageObj: JsonObject, baseUrl: String): String {
        val path = imageObj.optString("path")
        if (path.isEmpty() || baseUrl.isEmpty()) return ""

        if (imageObj.optBoolean("remotely_accessible") && path.startsWith("http")) {
            return path
        }

        val provider = imageObj.optString("provider")
        if (provider.isEmpty()) return ""

        val imageId = Platform.sha256Hex("$provider/$path")
        return "$baseUrl/imageproxy/$imageId?size=$IMAGE_PROXY_SIZE&fmt=jpeg"
    }

    /**
     * Extract image URL from metadata.images array.
     *
     * Prefers image types in this order: thumb > cover > front > any valid image.
     */
    private fun extractImageFromMetadata(metadata: JsonObject, baseUrl: String): String {
        val images = metadata.optJsonArray("images")
        if (images == null || images.size == 0) return ""

        // Preference order for image types (lower index = higher priority)
        val typePreference = listOf("thumb", "cover", "front")

        var bestImage: JsonObject? = null
        var bestPriority = Int.MAX_VALUE

        for (i in 0 until images.size) {
            val img = (images[i] as? JsonObject) ?: continue
            val path = img.optString("path")
            if (path.isEmpty()) continue

            val imgType = img.optString("type").lowercase()
            val priority = typePreference.indexOf(imgType)

            when {
                // Known preferred type -- track the best
                priority >= 0 && priority < bestPriority -> {
                    bestImage = img
                    bestPriority = priority
                }
                // First fallback image (no preferred type matched yet and no fallback stored)
                bestImage == null -> {
                    bestImage = img
                    // Keep bestPriority at MAX_VALUE so any preferred type can replace it
                }
            }

            // Short-circuit: "thumb" is highest priority, no need to keep looking
            if (bestPriority == 0) break
        }

        if (bestImage == null) return ""

        return buildImageProxyUrl(bestImage, baseUrl)
    }

}
