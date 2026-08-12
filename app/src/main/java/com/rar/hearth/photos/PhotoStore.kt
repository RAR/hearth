package com.rar.hearth.photos

import com.rar.hearth.config.DashConfig
import com.rar.hearth.ha.ConnState
import com.rar.hearth.ha.HaClient
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
    /** How many already-shown photos to keep on disk so a back-swipe has somewhere to go. */
    const val HISTORY_DEPTH = 3

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
 * Streams a HA media folder through a small on-disk prefetch buffer and owns the slideshow cursor.
 *
 * The folder is typically far larger than anything worth caching (thousands of photos), so the old
 * model -- cache a bounded subset, rotate ~20% of it every 6h -- meant the same few dozen photos
 * looped all day for weeks. Here a photo is downloaded, shown once, and deleted: [ledger] records
 * what has been shown so [nextBatch] never draws it again until the whole folder is exhausted.
 * Disk residency is [DashConfig.home].photoBufferDepth + [PhotoConfig.HISTORY_DEPTH] + the current
 * photo, which is smaller than the cache it replaces while never repeating.
 *
 * The cursor lives here rather than in HomeView because the buffer's contents change underneath
 * the display: a Compose-side `remember(photos)` over a changing list would reset its index on
 * every refill and jump the slideshow.
 *
 * Refill is driven by consumption ([advance]); the periodic timer, the CONNECTED transition and
 * config changes only re-browse the listing to pick up newly uploaded photos. [refresh] is
 * serialized with a mutex so a reconnect can't race the timer over the same cache dir.
 *
 * Open for a test subclass that overrides [refresh].
 */
open class PhotoStore(
    private val client: HaClient,
    private val downloader: PhotoDownloader,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val config: StateFlow<DashConfig>,
    private val ledger: PhotoLedger,
    private val random: Random = Random.Default,
) {
    private val _current = MutableStateFlow<File?>(null)

    /** The photo to display, or null before the first one is buffered. */
    val current: StateFlow<File?> = _current

    /** Downloaded, not yet displayed. */
    private val buffer = ArrayDeque<File>()

    /** Most recently displayed first; retained so a back-swipe has somewhere to go. */
    private val history = ArrayDeque<File>()

    private val mutex = Mutex()
    private var started = false

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        // Adopt whatever survived the last run so the screen has something to show before the
        // first refresh completes -- including on a cold boot with no network.
        cacheDir.listFiles()?.sortedBy { it.name }?.forEach { buffer.addLast(it) }
        promote(buffer.removeFirstOrNull())
    }

    fun start(connectionState: StateFlow<ConnState>) {
        if (started) return
        started = true
        scope.launch {
            connectionState.collect { if (it == ConnState.CONNECTED) refresh() }
        }
        scope.launch {
            // Re-browse when the folder, buffer depth, or slideshow-enabled flag changes (ignore
            // other config edits). The initial replayed value is dropped -- that state is already
            // covered by the CONNECTED trigger; only later changes should force a refresh.
            // Including slideshowEnabled means enabling the slideshow refreshes immediately
            // instead of waiting for the timer; a false-flip is harmless since refresh() no-ops
            // when disabled.
            config
                .map { Triple(it.home.photoFolder, it.home.photoBufferDepth, it.home.slideshowEnabled) }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
        scope.launch {
            // Re-read the interval each iteration so a config change takes effect on the next
            // tick rather than after the remainder of a stale sleep.
            while (isActive) {
                delay(config.value.home.photoSyncIntervalMinutes.toLong() * 60_000L)
                refresh()
            }
        }
    }

    /**
     * Move to the next photo: retire the current one to [history], promote the next buffered
     * photo, and top the buffer back up.
     *
     * When the buffer is empty -- HA unreachable, or downloads failing -- the retained photos are
     * cycled instead and nothing is deleted. At the default depth that is a couple of hours of
     * graceful degradation rather than a black screen.
     */
    fun advance() {
        val shown = _current.value
        val next = buffer.removeFirstOrNull()
        if (next == null) {
            cycleRetained(shown)
            scope.launch { refresh() }
            return
        }
        if (shown != null) {
            history.addFirst(shown)
            while (history.size > PhotoConfig.HISTORY_DEPTH) {
                history.removeLast().delete()
            }
        }
        promote(next)
        scope.launch { refresh() }
    }

    /** Step back to the previously shown photo. No-op when nothing is retained. */
    fun back() {
        val previous = history.removeFirstOrNull() ?: return
        _current.value?.let { buffer.addFirst(it) }
        _current.value = previous
    }

    /**
     * Make [file] the displayed photo and record it as shown. Every path that puts a photo on
     * screen for the first time goes through here, so nothing can be displayed without entering
     * the ledger -- [back] deliberately does not, since those photos are already recorded.
     */
    private fun promote(file: File?) {
        _current.value = file
        if (file != null) ledger.add(listOf(file.name))
    }

    /**
     * Offline path: rotate through what is still on disk without deleting anything, so the
     * slideshow keeps moving until downloads resume.
     */
    private fun cycleRetained(shown: File?) {
        val retained = history.toMutableList()
        if (retained.isEmpty()) return
        val next = retained.removeAt(0)
        history.clear()
        retained.forEach { history.addLast(it) }
        if (shown != null) history.addLast(shown)
        _current.value = next
    }

    /** Re-browse the folder and top the prefetch buffer back up to the configured depth. */
    open suspend fun refresh() = mutex.withLock {
        val home = config.value.home
        if (!home.slideshowEnabled) return@withLock
        val browse = runCatching {
            client.request("media_source/browse_media", buildJsonObject {
                put("media_content_id", JsonPrimitive(PhotoConfig.contentId(home.photoFolder)))
            })
        }.getOrNull() ?: return@withLock
        val remote = parseBrowseChildren(browse)

        // Everything currently on disk is excluded from the draw so nothing is fetched twice.
        // The current photo and the back-swipe history are on disk but are NOT buffer capacity --
        // they have been shown -- so the depth is raised by their count to keep the number of
        // UNSHOWN photos equal to the configured depth.
        val retained = history.map { it.name } + listOfNotNull(_current.value?.name)
        val onDisk = buffer.map { it.name }.toSet() + retained
        val batch = nextBatch(
            remote, ledger.read(), onDisk, home.photoBufferDepth + retained.size, random,
        )
        if (batch.epochReset) {
            // The archive has been exhausted. Start a fresh epoch, but keep what is still on
            // screen or one back-swipe away recorded: re-queuing the photo the user is currently
            // looking at is the one repeat that would actually be noticed.
            ledger.clear()
            ledger.add(retained)
        }

        batch.toDownload.forEach { photo ->
            val file = runCatching { downloader.download(photo.contentId, cacheKey(photo.contentId)) }
                .getOrNull() ?: return@forEach
            buffer.addLast(file)
            if (_current.value == null) promote(buffer.removeFirstOrNull())
        }

        // Sweep files that are neither buffered, current, nor retained -- left by a crash
        // mid-advance, or by a depth reduction.
        val live = buildSet {
            buffer.forEach { add(it.name) }
            history.forEach { add(it.name) }
            _current.value?.let { add(it.name) }
        }
        cacheDir.listFiles()?.forEach { if (it.name !in live) it.delete() }
    }
}
