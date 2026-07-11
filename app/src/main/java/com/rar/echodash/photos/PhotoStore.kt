package com.rar.echodash.photos

import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

object PhotoConfig {
    const val FOLDER = "echo-frame"
    const val MEDIA_CONTENT_ID = "media-source://media_source/local/echo-frame"
    const val CYCLE_MS = 5 * 60_000L
    const val SYNC_INTERVAL_MS = 6 * 60 * 60_000L
    const val MAX_W = 960
    const val MAX_H = 480
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

data class PhotoDiff(val toDownload: List<RemotePhoto>, val toDeleteKeys: List<String>)

/** Compare cached keys against remote; new photos to fetch, cached keys no longer remote to delete. */
fun diffPhotos(cachedKeys: Set<String>, remote: List<RemotePhoto>): PhotoDiff {
    val remoteKeys = remote.associateBy { cacheKey(it.contentId) }
    val toDownload = remote.filter { cacheKey(it.contentId) !in cachedKeys }
    val toDelete = cachedKeys.filter { it !in remoteKeys.keys }
    return PhotoDiff(toDownload, toDelete)
}

/** Filesystem-safe cache filename derived from a media content id. */
fun cacheKey(contentId: String): String =
    contentId.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(120)

interface PhotoDownloader {
    /** Resolve + download + downsample [contentId] to a cached file named [cacheKey]. Null on failure. */
    suspend fun download(contentId: String, cacheKey: String): File?
}

/**
 * Syncs HA's echo-frame media folder into [cacheDir] and publishes the cached files. Open for a test
 * subclass that overrides [sync]. Sync triggers: each CONNECTED transition + every [syncIntervalMs].
 */
open class PhotoStore(
    private val client: HaClient,
    private val downloader: PhotoDownloader,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val syncIntervalMs: Long = PhotoConfig.SYNC_INTERVAL_MS,
) {
    private val _photos = MutableStateFlow<List<File>>(emptyList())
    val photos: StateFlow<List<File>> = _photos

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        _photos.value = cacheDir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    fun start(connectionState: StateFlow<ConnState>) {
        scope.launch {
            connectionState.collect { if (it == ConnState.CONNECTED) sync() }
        }
        scope.launch {
            while (isActive) {
                delay(syncIntervalMs)
                sync()
            }
        }
    }

    open suspend fun sync() {
        val browse = runCatching {
            client.request("media_source/browse_media", buildJsonObject {
                put("media_content_id", JsonPrimitive(PhotoConfig.MEDIA_CONTENT_ID))
            })
        }.getOrNull() ?: return
        val remote = parseBrowseChildren(browse)
        val cachedKeys = cacheDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val diff = diffPhotos(cachedKeys, remote)
        diff.toDeleteKeys.forEach { File(cacheDir, it).delete() }
        diff.toDownload.forEach { photo ->
            runCatching { downloader.download(photo.contentId, cacheKey(photo.contentId)) }
        }
        _photos.value = cacheDir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }
}
