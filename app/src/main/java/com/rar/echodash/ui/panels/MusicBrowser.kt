package com.rar.echodash.ui.panels

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rar.echodash.media.MaThumbs
import com.rar.echodash.sendspin.MaLibrary
import com.rar.echodash.sendspin.MaLibraryState
import com.rar.echodash.sendspin.musicassistant.EnqueueMode
import com.rar.echodash.sendspin.musicassistant.MaAlbum
import com.rar.echodash.sendspin.musicassistant.MaPlaylist
import com.rar.echodash.sendspin.musicassistant.MaQueueItem
import com.rar.echodash.sendspin.musicassistant.MaQueueState
import com.rar.echodash.sendspin.musicassistant.MaRadio
import com.rar.echodash.sendspin.musicassistant.MaTrack
import com.rar.echodash.sendspin.musicassistant.SearchResults
import com.rar.echodash.sendspin.musicassistant.model.MaLibraryItem
import com.rar.echodash.sendspin.musicassistant.model.MaMediaType
import com.rar.echodash.ui.model.currentItemOf
import com.rar.echodash.ui.model.nextRepeatMode
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "MusicBrowser"
private const val SWIPE_DISMISS_FRACTION = 0.30f

/** The three quick-pick shelves shown when no search is active. */
data class BrowserShelves(
    val playlists: List<MaPlaylist>,
    val radios: List<MaRadio>,
    val recent: List<MaTrack>,
) {
    fun isEmpty(): Boolean = playlists.isEmpty() && radios.isEmpty() && recent.isEmpty()
}

/** What the browser area shows; the mapping from state is pure and pinned by tests. */
sealed interface BrowserContent {
    data class Shelves(val shelves: BrowserShelves) : BrowserContent
    data class Results(val results: SearchResults) : BrowserContent
    data class Notice(val message: String) : BrowserContent // disabled / signed-out / offline
    data object Loading : BrowserContent
}

/**
 * Pure MaLibraryState/query/data -> content mapping (MusicBrowserStateTest pins it).
 * A query below 2 characters never fires a search, so shelves stay up even when stale
 * results linger from a longer query.
 */
fun browserContent(
    state: MaLibraryState,
    query: String,
    shelves: BrowserShelves?,
    results: SearchResults?,
): BrowserContent = when (state) {
    is MaLibraryState.Disabled ->
        BrowserContent.Notice("Sign in to Music Assistant on the config page.")
    is MaLibraryState.AuthFailed ->
        BrowserContent.Notice("Music Assistant sign-in failed — sign in again on the config page.")
    is MaLibraryState.Offline ->
        BrowserContent.Notice("Music Assistant offline — retrying…")
    is MaLibraryState.Connecting -> BrowserContent.Loading
    is MaLibraryState.Connected -> when {
        query.trim().length >= 2 -> results?.let { BrowserContent.Results(it) } ?: BrowserContent.Loading
        shelves != null -> BrowserContent.Shelves(shelves)
        else -> BrowserContent.Loading
    }
}

/**
 * Music Assistant library browser: search box over shelves (playlists / radio / recently
 * played), grouped search results, and an upcoming-queue overlay. All state lives in
 * remember{} so leaving the MEDIA view drops it — no resident library cache on the 1 GB Echo.
 */
@Composable
fun MusicBrowser(
    library: MaLibrary,
    thumbs: MaThumbs,
    modifier: Modifier = Modifier,
    openQueueSignal: Int = 0,
    onSetRepeat: (String) -> Unit = {},
    onSetShuffle: (Boolean) -> Unit = {},
    onFavoriteToggle: (MaQueueItem?) -> Unit = {},
) {
    val maState by library.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var shelves by remember { mutableStateOf<BrowserShelves?>(null) }
    var shelvesFailed by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var queueVisible by remember { mutableStateOf(false) }
    var queueState by remember { mutableStateOf<MaQueueState?>(null) }
    var queueVersion by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val showError: (String) -> Unit = { msg -> error = msg; errorVersion++ }

    // Transient command errors self-clear so a stale failure doesn't haunt the panel.
    LaunchedEffect(errorVersion) {
        if (error != null) {
            delay(4_000)
            error = null
        }
    }

    // Shelves: fetch on connect, drop on disconnect — a reconnect refetches because library
    // content (and the panel's grouping) may have changed while we were away.
    val isConnected = maState is MaLibraryState.Connected
    LaunchedEffect(isConnected) {
        shelves = null
        shelvesFailed = false
        if (!isConnected) return@LaunchedEffect
        val playlists = library.playlists()
        val radios = library.radios()
        val recent = library.recentlyPlayed()
        if (playlists.isFailure && radios.isFailure && recent.isFailure) {
            // All three failed: most likely the socket just dropped mid-fetch; the state flow
            // flips to Offline and this effect re-runs on the next connect.
            shelvesFailed = true
            android.util.Log.w(TAG, "all shelf fetches failed: ${playlists.exceptionOrNull()?.message}")
            return@LaunchedEffect
        }
        // Partial failure: omit the failed shelf (it renders as absent) and log.
        listOf("playlists" to playlists, "radios" to radios, "recent" to recent).forEach { (name, r) ->
            r.exceptionOrNull()?.let { android.util.Log.w(TAG, "$name shelf fetch failed: ${it.message}") }
        }
        shelves = BrowserShelves(
            playlists = playlists.getOrElse { emptyList() },
            radios = radios.getOrElse { emptyList() },
            recent = recent.getOrElse { emptyList() },
        )
    }

    // Search: 400 ms debounce, minimum 2 characters; collectLatest cancels the in-flight
    // delay+search on every keystroke so only the settled query hits the server.
    LaunchedEffect(Unit) {
        snapshotFlow { query.trim() }
            .distinctUntilChanged()
            .collectLatest { q ->
                if (q.length < 2) {
                    results = null
                    return@collectLatest
                }
                delay(400)
                library.search(q)
                    .onSuccess { results = it }
                    .onFailure {
                        showError(it.message ?: "Search failed")
                        results = SearchResults() // renders "No matches" under the error line
                    }
            }
    }

    // Open the queue overlay when DashboardShell bumps the signal (up-next line tapped). 0 is the
    // never-requested value; a nonzero value opens the queue on (re)composition.
    LaunchedEffect(openQueueSignal) {
        if (openQueueSignal > 0) queueVisible = true
    }

    // Queue: poll every 5 s while the overlay is visible; queueVersion bumps restart the
    // effect for an immediate refetch after every mutation (jump / clear / enqueue).
    LaunchedEffect(queueVisible, queueVersion) {
        if (!queueVisible) return@LaunchedEffect
        while (true) {
            library.queue()
                .onSuccess { queueState = it }
                .onFailure { showError(it.message ?: "Queue unavailable") }
            delay(5_000)
        }
    }

    val playItem: (MaLibraryItem, EnqueueMode) -> Unit = { item, mode ->
        val uri = item.uri
        if (uri == null) {
            showError("Item can't be played (no URI)")
        } else {
            scope.launch {
                library.play(uri, item.mediaType.name.lowercase(), mode)
                    .onSuccess { queueVersion++ } // the upcoming list changed; refresh if visible
                    .onFailure { showError(it.message ?: "Playback failed") }
            }
        }
    }

    // Radio is a separate axis from EnqueueMode (it always PLAYs, self-refilling), so it rides
    // its own lambda landing on MaLibrary.playRadio rather than an EnqueueMode value.
    val startRadio: (MaLibraryItem) -> Unit = { item ->
        val uri = item.uri
        if (uri == null) {
            showError("Item can't be played (no URI)")
        } else {
            scope.launch {
                library.playRadio(uri, item.mediaType.name.lowercase())
                    .onSuccess { queueVersion++ } // the queue was replaced; refresh if visible
                    .onFailure { showError(it.message ?: "Couldn't start radio") }
            }
        }
    }

    val content = browserContent(maState, query, shelves, results)

    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(query, { query = it }, enabled = isConnected, modifier = Modifier.weight(1f))
            QueueToggleButton(active = queueVisible) { queueVisible = !queueVisible }
        }
        error?.let {
            Text(
                it, color = Color(0xFFE08080), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (queueVisible) {
                QueuePane(
                    queue = queueState,
                    thumbs = thumbs,
                    onJump = { id ->
                        scope.launch {
                            library.jumpTo(id)
                                .onSuccess { queueVersion++ }
                                .onFailure { showError(it.message ?: "Couldn't jump to item") }
                        }
                    },
                    onClear = {
                        scope.launch {
                            library.clearQueue()
                                .onSuccess { queueVersion++ }
                                .onFailure { showError(it.message ?: "Couldn't clear the queue") }
                        }
                    },
                    // Suspend, Boolean-returning: QueueRow already animated the row fully
                    // off-screen before calling this, then awaits the result to decide whether
                    // to animate it back (see QueueRow's onDragEnd) — so this runs inside
                    // QueueRow's own coroutine, not scope.launch'd here like onJump/onClear.
                    // queueVersion bumps only on success, never synchronously.
                    onRemove = { item ->
                        val result = library.removeQueueItem(item.queueItemId)
                        result.onSuccess { queueVersion++ }
                        result.onFailure { showError(it.message ?: "Couldn't remove item") }
                        result.isSuccess
                    },
                    // Computed off the queue's OWN state (not local now-playing), so a tap
                    // always toggles what the chip is currently displaying — no cross-source
                    // divergence between what's shown and what gets flipped.
                    onToggleShuffle = { queueState?.let { q -> onSetShuffle(!q.shuffleEnabled) }; queueVersion++ },
                    onCycleRepeat = { queueState?.let { q -> onSetRepeat(nextRepeatMode(q.repeatMode)) }; queueVersion++ },
                    // Favorite the queue's current item (the App callback decides add vs remove).
                    onToggleFavorite = {
                        queueState?.let { onFavoriteToggle(currentItemOf(it)) }
                        queueVersion++
                    },
                )
            } else when (content) {
                is BrowserContent.Notice -> EmptyHint(content.message)
                BrowserContent.Loading ->
                    // UI-level override, not a BrowserContent case: all shelves failing while
                    // Connected would otherwise look like an endless load.
                    EmptyHint(if (shelvesFailed) "Couldn't load the library — check Music Assistant." else "Loading…")
                is BrowserContent.Shelves -> ShelvesPane(content.shelves, thumbs, playItem, startRadio)
                is BrowserContent.Results -> ResultsPane(content.results, thumbs, playItem, startRadio)
            }
        }
    }
}

// ---- Header ----

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1B2030))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Search, contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Search the library", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                // Inherit the themed (Nunito) style; BasicTextField doesn't pick it up by itself.
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close, contentDescription = "Clear search",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun QueueToggleButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF3A4152) else Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.QueueMusic, contentDescription = "Queue",
            tint = Color.White, modifier = Modifier.size(20.dp),
        )
    }
}

// ---- Shelves ----

@Composable
private fun ShelvesPane(
    shelves: BrowserShelves,
    thumbs: MaThumbs,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
) {
    if (shelves.isEmpty()) {
        EmptyHint("Library empty or still syncing")
        return
    }
    // Vertical scroll of horizontal LazyRows: three shelves don't fit the Echo's 480 px height.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Shelf("Playlists", shelves.playlists, thumbs, onPlay, onStartRadio)
        Shelf("Radio", shelves.radios, thumbs, onPlay, onStartRadio)
        Shelf("Recently played", shelves.recent, thumbs, onPlay, onStartRadio)
    }
}

@Composable
private fun Shelf(
    title: String,
    items: List<MaLibraryItem>,
    thumbs: MaThumbs,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
) {
    if (items.isEmpty()) return // an empty shelf renders nothing, not a bare header
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.uri ?: it.id }) { item ->
                MediaCell(item, thumbs, onPlay, onStartRadio)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: MaLibraryItem,
    thumbs: MaThumbs,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .width(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = { onPlay(item, EnqueueMode.PLAY) },
                    onLongClick = { menuOpen = true },
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Thumb(item.imageUri, thumbs, 96.dp)
            Text(
                item.name, color = Color.White, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        EnqueueMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onPlayNext = { onPlay(item, EnqueueMode.NEXT) },
            onAdd = { onPlay(item, EnqueueMode.ADD) },
            onStartRadio = if (item.mediaType != MaMediaType.RADIO) ({ onStartRadio(item) }) else null,
        )
    }
}

// ---- Search results ----

@Composable
private fun ResultsPane(
    results: SearchResults,
    thumbs: MaThumbs,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
) {
    if (results.isEmpty()) {
        EmptyHint("No matches")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        resultGroup("Tracks", results.tracks, thumbs, onPlay, onStartRadio)
        resultGroup("Albums", results.albums, thumbs, onPlay, onStartRadio)
        resultGroup("Artists", results.artists, thumbs, onPlay, onStartRadio)
        resultGroup("Playlists", results.playlists, thumbs, onPlay, onStartRadio)
        resultGroup("Radio", results.radios, thumbs, onPlay, onStartRadio)
    }
}

private fun LazyListScope.resultGroup(
    title: String,
    items: List<MaLibraryItem>,
    thumbs: MaThumbs,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
) {
    if (items.isEmpty()) return
    // Keys are prefixed with the group title: a library item can appear in two groups
    // (and headers share the key space), and LazyColumn keys must be globally unique.
    item(key = "hdr-$title") {
        Text(
            title, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
    }
    items(items, key = { "$title-${it.uri ?: it.id}" }) { item ->
        ResultRow(item, thumbs, onPlay, onStartRadio)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    item: MaLibraryItem,
    thumbs: MaThumbs,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = { onPlay(item, EnqueueMode.PLAY) },
                    onLongClick = { menuOpen = true },
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(item.imageUri, thumbs, 40.dp, corner = 6.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    item.name, color = Color.White, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val sub = when (item) {
                    is MaTrack -> listOfNotNull(item.artist, item.album).joinToString(" — ")
                    is MaAlbum -> item.artist.orEmpty()
                    is MaPlaylist -> item.owner.orEmpty()
                    is MaRadio -> item.provider.orEmpty()
                    else -> ""
                }
                if (sub.isNotBlank()) {
                    Text(
                        sub, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        EnqueueMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onPlayNext = { onPlay(item, EnqueueMode.NEXT) },
            onAdd = { onPlay(item, EnqueueMode.ADD) },
            onStartRadio = if (item.mediaType != MaMediaType.RADIO) ({ onStartRadio(item) }) else null,
        )
    }
}

// ---- Queue overlay ----

@Composable
private fun QueuePane(
    queue: MaQueueState?,
    thumbs: MaThumbs,
    onJump: (String) -> Unit,
    onClear: () -> Unit,
    onRemove: suspend (MaQueueItem) -> Boolean,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Queue", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            // Toggle chips mirror the takeover: lit off the queue's own state.
            if (queue != null) {
                val favoriteLit = currentItemOf(queue)?.favorite == true
                QueueToggleChip(
                    if (favoriteLit) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    on = favoriteLit,
                ) { onToggleFavorite() }
                QueueToggleChip(Icons.Outlined.Shuffle, on = queue.shuffleEnabled) { onToggleShuffle() }
                val repeatIcon = if (queue.repeatMode == "one") Icons.Outlined.RepeatOne else Icons.Outlined.Repeat
                val repeatOn = queue.repeatMode == "all" || queue.repeatMode == "one"
                QueueToggleChip(repeatIcon, on = repeatOn) { onCycleRepeat() }
            }
            Text(
                "Clear", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A2F3C))
                    .clickable { onClear() }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        when {
            queue == null -> EmptyHint("Loading…")
            queue.items.isEmpty() -> EmptyHint("Queue is empty")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(queue.items, key = { it.queueItemId }) { qi ->
                    QueueRow(qi, thumbs, onJump, onRemove)
                }
            }
        }
    }
}

/** A 28 dp round toggle chip (16 dp icon) for the queue header: accent tint when [on]. */
@Composable
private fun QueueToggleChip(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (on) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * A queue row: tap to jump (unchanged), swipe left to remove — mechanics copied from
 * [NotificationArea.NotificationRow] (Animatable x-offset + detectHorizontalDragGestures,
 * SWIPE_DISMISS_FRACTION threshold, snap back on cancel or a below-threshold release). The
 * horizontal drag detector and the QueuePane's vertical LazyColumn scroll claim separate
 * gesture axes, same coexistence as the notification stack.
 *
 * Unlike NotificationRow's fire-and-forget onDismiss, removal is a server round trip that can
 * fail — so a completed swipe animates fully off-screen, *awaits* [onRemove]'s suspending
 * Boolean result, and animates back into view if it returns false. This keeps the row's own
 * animation state authoritative for both outcomes instead of relying on a later queue refetch
 * to reconcile a stuck offset.
 *
 * The CURRENT item never receives the swipe (guard below) — the server silently ignores
 * deletes of a buffered item anyway, so offering a gesture that can no-op is worse than not
 * offering it (recon: player_queues/delete_item). The offset/onSizeChanged scaffolding stays
 * in place for the current row too (harmless — it never moves without a drag detector), which
 * keeps one row layout instead of two divergent ones.
 */
@Composable
private fun QueueRow(
    item: MaQueueItem,
    thumbs: MaThumbs,
    onJump: (String) -> Unit,
    onRemove: suspend (MaQueueItem) -> Boolean,
) {
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .then(
                if (item.isCurrentItem) {
                    Modifier
                } else {
                    Modifier.pointerInput(item.queueItemId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val threshold = widthPx * SWIPE_DISMISS_FRACTION
                                if (widthPx > 0 && -offsetX.value >= threshold) {
                                    scope.launch {
                                        offsetX.animateTo(-widthPx.toFloat(), tween(200))
                                        val removed = onRemove(item)
                                        // Failed (or the server otherwise didn't actually drop
                                        // it): bring the row back into view instead of leaving
                                        // a blank slot around until the panel is closed and
                                        // reopened — LazyColumn's key = { it.queueItemId }
                                        // would otherwise keep this exact offset pinned to
                                        // this row across every later recomposition.
                                        if (!removed) {
                                            offsetX.animateTo(0f, tween(200))
                                        }
                                    }
                                } else {
                                    scope.launch { offsetX.animateTo(0f, tween(200)) }
                                }
                            },
                            // A cancelled drag (ancestor claims the gesture, extra pointer)
                            // never reaches onDragEnd — snap back so the row can't be left
                            // stranded mid-swipe.
                            onDragCancel = {
                                scope.launch { offsetX.animateTo(0f, tween(200)) }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            // Only left drags move the row; right drags clamp back to 0.
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceAtMost(0f))
                            }
                        }
                    }
                }
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (item.isCurrentItem) Color(0xFF2A2F3C) else Color.Transparent)
                .clickable { onJump(item.queueItemId) }
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(item.imageUri, thumbs, 36.dp, corner = 6.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    item.name, color = Color.White, fontSize = 14.sp,
                    fontWeight = if (item.isCurrentItem) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(item.artist, item.album).joinToString(" — ")
                if (sub.isNotBlank()) {
                    Text(
                        sub, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---- Shared bits ----

/** Per-cell thumbnail: cache-first via MaThumbs (IO-dispatched decode), music-note placeholder. */
@Composable
private fun Thumb(url: String?, thumbs: MaThumbs, size: Dp, corner: Dp = 8.dp) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (url == null) null else thumbs.load(url)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(Color(0xFF11151F)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.Outlined.MusicNote, contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(size / 3),
            )
        }
    }
}

@Composable
private fun EnqueueMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAdd: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Play next") }, onClick = { onDismiss(); onPlayNext() })
        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { onDismiss(); onAdd() })
        // Radio seeds from a real media item (track/artist/album/playlist) — never a station.
        if (onStartRadio != null) {
            DropdownMenuItem(text = { Text("Start radio") }, onClick = { onDismiss(); onStartRadio() })
        }
    }
}
