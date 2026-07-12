package com.rar.echodash.photos

import com.rar.echodash.config.DashConfig
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaClient
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

object PhotoConfig {
    const val CYCLE_MS = 5 * 60_000L
    const val SYNC_INTERVAL_MS = 6 * 60 * 60_000L
    const val MAX_W = 960
    const val MAX_H = 480
    /** media-source content id for a folder relative to HA's media/ root. */
    fun contentId(folder: String): String = "media-source://media_source/local/$folder"
}

data class RemotePhoto(val contentId: String, val title: String)

/** Keep only image children of a media_source/browse_media result. */
fun parseBrowseChildren(result: JsonElement?): List<RemotePhoto> {
    val children = (result as? JsonObject)?.get("children") as? JsonArray ?: return emptyList()
    return children.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        if ((o["media_class"] as? JsonPrimitive)?.contentOrNull != "image") return@mapNotNull null
        val id = (o["media_content_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: id.substringAfterLast('/')
        RemotePhoto(id, title)
    }
}

/** Filesystem-safe cache filename derived from a media content id. */
fun cacheKey(contentId: String): String =
    contentId.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(120)

interface PhotoDownloader {
    /** Resolve + download + downsample [contentId] to a cached file named [cacheKey]. Null on failure. */
    suspend fun download(contentId: String, cacheKey: String): File?
}

/**
 * Syncs a HA media folder into [cacheDir] and publishes the cached files. The folder, the cache cap,
 * and the slideshow-enabled flag come from [config]. Sync triggers: each CONNECTED transition, every
 * [syncIntervalMs], and every change to the (folder, cap) pair. Large folders are kept as a bounded
 * rotating subset via [rotatingSubset]; folders within the cap sync fully. [sync] is serialized with
 * a mutex so a reconnect mid-sync can't race a config-change or periodic trigger over the same cache.
 * Open for a test subclass that overrides [sync].
 */
open class PhotoStore(
    private val client: HaClient,
    private val downloader: PhotoDownloader,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val config: StateFlow<DashConfig>,
    private val syncIntervalMs: Long = PhotoConfig.SYNC_INTERVAL_MS,
    private val random: Random = Random.Default,
) {
    private val _photos = MutableStateFlow<List<File>>(emptyList())
    val photos: StateFlow<List<File>> = _photos
    private val syncMutex = Mutex()
    private var started = false

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        _photos.value = cacheDir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    fun start(connectionState: StateFlow<ConnState>) {
        if (started) return
        started = true
        scope.launch {
            connectionState.collect { if (it == ConnState.CONNECTED) sync() }
        }
        scope.launch {
            // Resync when the folder, cap, or slideshow-enabled flag changes (ignore other
            // config edits). The initial replayed value on subscribe is dropped — that config
            // state is already covered by the CONNECTED-trigger sync above; only later changes
            // should force a resync. Including slideshowEnabled means enabling the slideshow
            // triggers an immediate sync instead of waiting for the periodic sync; a false-flip
            // triggering a sync is harmless since sync() no-ops when disabled.
            config
                .map { Triple(it.home.photoFolder, it.home.photoCacheCap, it.home.slideshowEnabled) }
                .distinctUntilChanged()
                .drop(1)
                .collect { sync() }
        }
        scope.launch {
            while (isActive) {
                delay(syncIntervalMs)
                sync()
            }
        }
    }

    open suspend fun sync() = syncMutex.withLock {
        val home = config.value.home
        if (!home.slideshowEnabled) return@withLock
        val browse = runCatching {
            client.request("media_source/browse_media", buildJsonObject {
                put("media_content_id", JsonPrimitive(PhotoConfig.contentId(home.photoFolder)))
            })
        }.getOrNull() ?: return@withLock
        val remote = parseBrowseChildren(browse)
        val cachedKeys = cacheDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val plan = rotatingSubset(remote, cachedKeys, home.photoCacheCap, random)
        plan.toDeleteKeys.forEach { File(cacheDir, it).delete() }
        plan.toDownload.forEach { photo ->
            runCatching { downloader.download(photo.contentId, cacheKey(photo.contentId)) }
        }
        _photos.value = cacheDir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }
}
