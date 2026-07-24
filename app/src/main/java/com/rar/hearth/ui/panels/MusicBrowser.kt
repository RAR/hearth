package com.rar.hearth.ui.panels

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.rar.hearth.media.MaThumbs
import com.rar.hearth.media.formatTrackTime
import com.rar.hearth.sendspin.MaLibrary
import com.rar.hearth.sendspin.MaLibraryState
import com.rar.hearth.sendspin.musicassistant.EnqueueMode
import com.rar.hearth.sendspin.musicassistant.MaAlbum
import com.rar.hearth.sendspin.musicassistant.MaArtist
import com.rar.hearth.sendspin.musicassistant.MaPlayer
import com.rar.hearth.sendspin.musicassistant.MaPlaylist
import com.rar.hearth.sendspin.musicassistant.MaQueueItem
import com.rar.hearth.sendspin.musicassistant.MaQueueState
import com.rar.hearth.sendspin.musicassistant.MaRadio
import com.rar.hearth.sendspin.musicassistant.MaTrack
import com.rar.hearth.sendspin.musicassistant.SearchResults
import com.rar.hearth.sendspin.musicassistant.model.MaLibraryItem
import com.rar.hearth.sendspin.musicassistant.model.MaMediaType
import com.rar.hearth.ui.model.BrowserPage
import com.rar.hearth.ui.model.canOfferGroup
import com.rar.hearth.ui.model.currentItemOf
import com.rar.hearth.ui.model.inGroupWithSelf
import com.rar.hearth.ui.model.nextRepeatMode
import com.rar.hearth.ui.model.popPage
import com.rar.hearth.ui.model.pushPage
import com.rar.hearth.ui.model.speakerRows
import com.rar.hearth.ui.model.tabTarget
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "MusicBrowser"
private const val SWIPE_DISMISS_FRACTION = 0.30f
private const val LIBRARY_PAGE_SIZE = 200

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
    var speakersVisible by remember { mutableStateOf(false) }
    var speakers by remember { mutableStateOf<List<MaPlayer>?>(null) }
    var speakersVersion by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Browser back-stack; current page = pageStack.last(). Home is always at the bottom.
    var pageStack by remember { mutableStateOf<List<BrowserPage>>(listOf(BrowserPage.Home)) }

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
        // Also close Speakers: the overlays are mutually exclusive at every other open site, and
        // the content branch renders Speakers first — without this the requested queue would
        // silently not appear if the signal ever fired while Speakers was open.
        if (openQueueSignal > 0) { queueVisible = true; speakersVisible = false }
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

    // Speakers: poll every 5 s while the overlay is visible; speakersVersion bumps restart the
    // effect for an immediate refetch after a group/ungroup/transfer mutation.
    LaunchedEffect(speakersVisible, speakersVersion) {
        if (!speakersVisible) return@LaunchedEffect
        while (true) {
            library.players()
                .onSuccess { speakers = it }
                .onFailure { showError(it.message ?: "Speakers unavailable") }
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

    // Drill into an artist. Clears any active search first so the pushed detail page isn't
    // hidden by the ≥2-char search override (also used by the "View artist" menu entry in
    // Task 4, where a search is typically active). pushPage dedups a repeated drill.
    val openArtist: (MaArtist) -> Unit = { artist ->
        query = ""
        pageStack = pushPage(pageStack, BrowserPage.ArtistDetail(artist))
    }

    // Drill into an album's tracklist (from "View album"). Clears any active search first, same
    // as openArtist, so the pushed detail page isn't hidden by the search override.
    val openAlbum: (MaAlbum) -> Unit = { album ->
        query = ""
        pageStack = pushPage(pageStack, BrowserPage.AlbumDetail(album))
    }

    val content = browserContent(maState, query, shelves, results)

    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(query, { query = it }, enabled = isConnected, modifier = Modifier.weight(1f))
            // Speakers + Queue overlays are mutually exclusive: opening one closes the other.
            SpeakersToggleButton(active = speakersVisible) {
                speakersVisible = !speakersVisible
                if (speakersVisible) queueVisible = false
            }
            QueueToggleButton(active = queueVisible) {
                queueVisible = !queueVisible
                if (queueVisible) speakersVisible = false
            }
        }
        Spacer(Modifier.height(8.dp))
        // Tab row: Home · Artists · Albums. Tapping a tab resets the stack to [Home,<tab>] (or
        // [Home]) and closes the queue overlay so the picked page is visible; it does NOT clear
        // an active search (clearing search then returns to the freshly-picked stack top).
        BrowserTabs(
            active = tabTarget(pageStack),
            onSelect = { target ->
                queueVisible = false
                speakersVisible = false
                pageStack = if (target is BrowserPage.Home) listOf(BrowserPage.Home)
                            else listOf(BrowserPage.Home, target)
            },
        )
        error?.let {
            Text(
                it, color = Color(0xFFE08080), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val searching = query.trim().length >= 2
            when {
                speakersVisible -> SpeakersPane(
                    players = speakers,
                    selfId = library.selfPlayerId,
                    // Volume: fire-and-forget; the 5 s poll reconciles the shown level, so we do
                    // NOT bump speakersVersion here (an immediate refetch would fight the drag).
                    onSetVolume = { id, v ->
                        scope.launch {
                            library.setPlayerVolume(id, v)
                                .onFailure { showError(it.message ?: "Couldn't set volume") }
                        }
                    },
                    onGroup = { id ->
                        scope.launch {
                            library.groupWithMe(id)
                                .onSuccess { speakersVersion++ }
                                .onFailure { showError(it.message ?: "Couldn't group speaker") }
                        }
                    },
                    onUngroup = { id ->
                        scope.launch {
                            library.ungroupPlayer(id)
                                .onSuccess { speakersVersion++ }
                                .onFailure { showError(it.message ?: "Couldn't ungroup speaker") }
                        }
                    },
                    onSendQueue = { id ->
                        scope.launch {
                            library.transferMyQueueTo(id)
                                .onSuccess { speakersVersion++; speakersVisible = false } // music left this device
                                .onFailure { showError(it.message ?: "Couldn't send the queue") }
                        }
                    },
                )
                queueVisible -> QueuePane(
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
                // Not connected (disabled / auth-failed / offline / connecting): show the state notice.
                maState !is MaLibraryState.Connected -> when (val c = content) {
                    is BrowserContent.Notice -> EmptyHint(c.message)
                    else -> EmptyHint("Loading…")
                }
                // Search overrides any page (existing behavior); the stack is preserved underneath.
                searching -> when (val c = content) {
                    is BrowserContent.Results ->
                        ResultsPane(c.results, thumbs, playItem, startRadio, onViewAlbum = openAlbum, onViewArtist = openArtist)
                    else -> EmptyHint("Loading…")
                }
                // Otherwise render the current page in the stack.
                else -> when (val page = pageStack.last()) {
                    BrowserPage.Home -> {
                        val s = shelves
                        if (s != null) ShelvesPane(s, thumbs, playItem, startRadio)
                        else EmptyHint(
                            if (shelvesFailed) "Couldn't load the library — check Music Assistant."
                            else "Loading…",
                        )
                    }
                    BrowserPage.Artists -> ArtistsPage(
                        library = library, thumbs = thumbs, onOpenArtist = openArtist,
                        onPlay = playItem, onStartRadio = startRadio, onError = showError,
                    )
                    BrowserPage.Albums -> AlbumsPage(
                        library = library, thumbs = thumbs, onOpenAlbum = openAlbum,
                        onPlay = playItem, onStartRadio = startRadio, onError = showError,
                    )
                    is BrowserPage.ArtistDetail -> ArtistDetailPage(
                        artist = page.artist, library = library, thumbs = thumbs,
                        onBack = { pageStack = popPage(pageStack) },
                        onPlay = playItem, onStartRadio = startRadio,
                        onOpenAlbum = openAlbum, onError = showError,
                    )
                    is BrowserPage.AlbumDetail -> AlbumDetailPage(
                        album = page.album, library = library, thumbs = thumbs,
                        onBack = { pageStack = popPage(pageStack) },
                        onPlay = playItem, onStartRadio = startRadio, onError = showError,
                    )
                }
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

@Composable
private fun SpeakersToggleButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF3A4152) else Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.SpeakerGroup, contentDescription = "Speakers",
            tint = Color.White, modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun BrowserTabs(active: BrowserPage, onSelect: (BrowserPage) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TabChip("Home", active is BrowserPage.Home) { onSelect(BrowserPage.Home) }
        TabChip("Artists", active is BrowserPage.Artists) { onSelect(BrowserPage.Artists) }
        TabChip("Albums", active is BrowserPage.Albums) { onSelect(BrowserPage.Albums) }
    }
}

/** A text tab chip styled like the queue "Clear" chip; the active tab's label uses the accent. */
@Composable
private fun TabChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.8f),
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
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
    onViewAlbum: (MaAlbum) -> Unit = {},
    onViewArtist: (MaArtist) -> Unit = {},
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
            onViewAlbum = (item as? MaAlbum)?.let { al -> { onViewAlbum(al) } },
            onViewArtist = (item as? MaArtist)?.let { ar -> { onViewArtist(ar) } },
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
    onViewAlbum: (MaAlbum) -> Unit,
    onViewArtist: (MaArtist) -> Unit,
) {
    if (results.isEmpty()) {
        EmptyHint("No matches")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        resultGroup("Tracks", results.tracks, thumbs, onPlay, onStartRadio, onViewAlbum, onViewArtist)
        resultGroup("Albums", results.albums, thumbs, onPlay, onStartRadio, onViewAlbum, onViewArtist)
        resultGroup("Artists", results.artists, thumbs, onPlay, onStartRadio, onViewAlbum, onViewArtist)
        resultGroup("Playlists", results.playlists, thumbs, onPlay, onStartRadio, onViewAlbum, onViewArtist)
        resultGroup("Radio", results.radios, thumbs, onPlay, onStartRadio, onViewAlbum, onViewArtist)
    }
}

private fun LazyListScope.resultGroup(
    title: String,
    items: List<MaLibraryItem>,
    thumbs: MaThumbs,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
    onViewAlbum: (MaAlbum) -> Unit,
    onViewArtist: (MaArtist) -> Unit,
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
        ResultRow(item, thumbs, onPlay, onStartRadio, onViewAlbum, onViewArtist)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    item: MaLibraryItem,
    thumbs: MaThumbs,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
    onViewAlbum: (MaAlbum) -> Unit,
    onViewArtist: (MaArtist) -> Unit,
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
            onViewAlbum = (item as? MaAlbum)?.let { al -> { onViewAlbum(al) } },
            onViewArtist = (item as? MaArtist)?.let { ar -> { onViewArtist(ar) } },
        )
    }
}

// ---- A-Z library pages ----

/**
 * A paged A-Z LazyColumn: fetches page 0 on first composition, then appends the next offset when
 * the list scrolls to its end and the previous page was full ([LIBRARY_PAGE_SIZE]). A trailing
 * "Loading…" row shows while a page is in flight; a fetch failure stops paging and keeps what
 * loaded (the caller's [onError] surfaces the toast). State is per-composition — leaving the page
 * and returning refetches page 0, matching the shelves' fetch-on-composition behavior.
 */
@Composable
private fun <T> PagedLibraryColumn(
    fetch: suspend (offset: Int) -> Result<List<T>>,
    key: (T) -> Any,
    emptyText: String,
    onError: (String) -> Unit,
    row: @Composable (T) -> Unit,
) {
    var items by remember { mutableStateOf<List<T>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var exhausted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadVersion by remember { mutableIntStateOf(0) } // 0 = initial page; bumps request the next

    LaunchedEffect(loadVersion) {
        if (exhausted) return@LaunchedEffect
        loading = true
        fetch(offset)
            .onSuccess { page ->
                items = items + page
                offset += page.size
                if (page.size < LIBRARY_PAGE_SIZE) exhausted = true
            }
            .onFailure {
                exhausted = true // stop paging on error; keep what loaded
                onError(it.message ?: "Couldn't load")
            }
        loading = false
    }

    val listState = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            items.isNotEmpty() && last >= items.size - 1
        }
    }
    LaunchedEffect(atEnd) {
        if (atEnd && !loading && !exhausted) loadVersion++
    }

    when {
        items.isEmpty() && loading -> EmptyHint("Loading…")
        items.isEmpty() -> EmptyHint(emptyText)
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items, key = { key(it) }) { row(it) }
            if (loading) item { LoadingRow() }
        }
    }
}

@Composable
private fun ArtistsPage(
    library: MaLibrary,
    thumbs: MaThumbs,
    onOpenArtist: (MaArtist) -> Unit,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
    onError: (String) -> Unit,
) {
    PagedLibraryColumn(
        fetch = { offset -> library.libraryArtists(offset) },
        key = { it.id },
        emptyText = "No artists in the library",
        onError = onError,
    ) { artist ->
        LibraryRow(
            item = artist,
            thumbs = thumbs,
            subtitle = null,
            onClick = { onOpenArtist(artist) }, // artist tap drills in (primary)
            onPlayNext = { onPlay(artist, EnqueueMode.NEXT) },
            onAdd = { onPlay(artist, EnqueueMode.ADD) },
            onStartRadio = { onStartRadio(artist) },
            onViewArtist = { onOpenArtist(artist) },
        )
    }
}

@Composable
private fun AlbumsPage(
    library: MaLibrary,
    thumbs: MaThumbs,
    onOpenAlbum: (MaAlbum) -> Unit,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
    onError: (String) -> Unit,
) {
    PagedLibraryColumn(
        fetch = { offset -> library.libraryAlbums(offset) },
        key = { it.id },
        emptyText = "No albums in the library",
        onError = onError,
    ) { album ->
        LibraryRow(
            item = album,
            thumbs = thumbs,
            subtitle = album.artist,
            onClick = { onPlay(album, EnqueueMode.PLAY) }, // album tap plays the album
            onPlayNext = { onPlay(album, EnqueueMode.NEXT) },
            onAdd = { onPlay(album, EnqueueMode.ADD) },
            onStartRadio = { onStartRadio(album) },
            onViewAlbum = { onOpenAlbum(album) },
        )
    }
}

/**
 * An A-Z list row: 44dp thumb, name, optional subtitle. [onClick] differs by type (artist drills,
 * album plays). Long-press opens the enqueue menu. Task 4 adds optional "View album"/"View artist".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    item: MaLibraryItem,
    thumbs: MaThumbs,
    subtitle: String?,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAdd: () -> Unit,
    onStartRadio: () -> Unit,
    onViewAlbum: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(item.imageUri, thumbs, 44.dp, corner = 6.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    item.name, color = Color.White, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        EnqueueMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onPlayNext = onPlayNext,
            onAdd = onAdd,
            onStartRadio = onStartRadio,
            onViewAlbum = onViewAlbum,
            onViewArtist = onViewArtist,
        )
    }
}

/** A trailing "Loading…" row shown while the next A-Z page is in flight. */
@Composable
private fun LoadingRow() {
    Text(
        "Loading…", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    )
}

// ---- Detail pages (drill-in) ----

/**
 * Artist → their albums, in an adaptive 128dp grid reusing [MediaCell] (cell tap plays the album).
 * Task 4 adds a "View album" entry to those cells. Fetches on entry; a failure shows the toast and
 * an empty grid the user can back out of.
 */
@Composable
private fun ArtistDetailPage(
    artist: MaArtist,
    library: MaLibrary,
    thumbs: MaThumbs,
    onBack: () -> Unit,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
    onOpenAlbum: (MaAlbum) -> Unit,
    onError: (String) -> Unit,
) {
    var albums by remember { mutableStateOf<List<MaAlbum>?>(null) }
    LaunchedEffect(artist.artistId, artist.provider) {
        library.artistAlbums(artist)
            .onSuccess { albums = it }
            .onFailure { albums = emptyList(); onError(it.message ?: "Couldn't load albums") }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailHeader(onBack = onBack, title = artist.name, subtitle = null)
        when (val a = albums) {
            null -> EmptyHint("Loading…")
            else -> if (a.isEmpty()) {
                EmptyHint("No albums")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    gridItems(a, key = { it.id }) { album ->
                        MediaCell(album, thumbs, onPlay, onStartRadio, onViewAlbum = onOpenAlbum)
                    }
                }
            }
        }
    }
}

/**
 * Album → its tracklist (server-sorted by disc/track). Row tap plays that track; long-press offers
 * Play next / Add to queue / Start radio. The leading number is the 1-based list ordinal (MaTrack
 * carries no track_number and the parser is reused per the spec) — correct for single-disc albums
 * and monotonic overall. Duration reuses [formatTrackTime] (ms; MaTrack.duration is seconds).
 */
@Composable
private fun AlbumDetailPage(
    album: MaAlbum,
    library: MaLibrary,
    thumbs: MaThumbs,
    onBack: () -> Unit,
    onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
    onStartRadio: (MaLibraryItem) -> Unit,
    onError: (String) -> Unit,
) {
    var tracks by remember { mutableStateOf<List<MaTrack>?>(null) }
    LaunchedEffect(album.albumId, album.provider) {
        library.albumTracks(album)
            .onSuccess { tracks = it }
            .onFailure { tracks = emptyList(); onError(it.message ?: "Couldn't load tracks") }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailHeader(onBack = onBack, title = album.name, subtitle = album.artist)
        when (val t = tracks) {
            null -> EmptyHint("Loading…")
            else -> if (t.isEmpty()) {
                EmptyHint("No tracks")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(t, key = { _, track -> track.id }) { index, track ->
                        TrackRow(
                            number = index + 1,
                            track = track,
                            onClick = { onPlay(track, EnqueueMode.PLAY) },
                            onPlayNext = { onPlay(track, EnqueueMode.NEXT) },
                            onAdd = { onPlay(track, EnqueueMode.ADD) },
                            onStartRadio = { onStartRadio(track) },
                        )
                    }
                }
            }
        }
    }
}

/** Detail-page header: back chip + title (22sp) + optional subtitle. */
@Composable
private fun DetailHeader(onBack: () -> Unit, title: String, subtitle: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BackChip(onBack)
        Column(Modifier.weight(1f)) {
            Text(
                title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A 32dp round back chip (pops the page stack). */
@Composable
private fun BackChip(onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back",
            tint = Color.White, modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    number: Int,
    track: MaTrack,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAdd: () -> Unit,
    onStartRadio: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$number", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                modifier = Modifier.width(24.dp),
            )
            Text(
                track.name, color = Color.White, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                formatTrackTime((track.duration ?: 0L) * 1000),
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
            )
        }
        EnqueueMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onPlayNext = onPlayNext,
            onAdd = onAdd,
            onStartRadio = onStartRadio,
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

// ---- Speakers overlay ----

/**
 * Speakers overlay: every MA player with a volume slider + group / send-queue actions. Same
 * panel chrome as [QueuePane]; closed via the top-bar Speakers chip. Rows come from
 * [speakerRows] (self pinned first). [players] == null renders "Loading…" on first open.
 */
@Composable
private fun SpeakersPane(
    players: List<MaPlayer>?,
    selfId: String,
    onSetVolume: (String, Int) -> Unit,
    onGroup: (String) -> Unit,
    onUngroup: (String) -> Unit,
    onSendQueue: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Speakers", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            players == null -> EmptyHint("Loading…")
            players.isEmpty() -> EmptyHint("No speakers found")
            else -> {
                val rows = speakerRows(players, selfId)
                val self = rows.firstOrNull { it.playerId == selfId }
                // The set of listed ids lets canOfferGroup tell a provider-instance grant (not a
                // listed id => permissive) from a pure foreign player-id allowlist.
                val allIds = rows.map { it.playerId }.toSet()
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(rows, key = { it.playerId }) { p ->
                        SpeakerRow(
                            player = p,
                            self = self,
                            isSelf = p.playerId == selfId,
                            // Resolve the sync leader's display name for the "Grouped with …" line.
                            leaderName = p.syncedTo?.let { id ->
                                rows.firstOrNull { it.playerId == id }?.name ?: id
                            },
                            allPlayerIds = allIds,
                            onSetVolume = onSetVolume,
                            onGroup = onGroup,
                            onUngroup = onUngroup,
                            onSendQueue = onSendQueue,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakerRow(
    player: MaPlayer,
    self: MaPlayer?,
    isSelf: Boolean,
    leaderName: String?,
    allPlayerIds: Set<String>,
    onSetVolume: (String, Int) -> Unit,
    onGroup: (String) -> Unit,
    onUngroup: (String) -> Unit,
    onSendQueue: (String) -> Unit,
) {
    val nameAlpha = if (player.available) 1f else 0.4f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelf) Color(0xFF2A2F3C) else Color.Transparent)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                player.name + if (isSelf) " · this device" else "",
                color = Color.White.copy(alpha = nameAlpha), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            // Self row shows no action chips (volume only).
            if (!isSelf && canOfferGroup(player, self, allPlayerIds)) {
                SpeakerActionChip("Group") { onGroup(player.playerId) }
            }
            if (!isSelf && inGroupWithSelf(player, self)) {
                SpeakerActionChip("Ungroup") { onUngroup(player.playerId) }
            }
            if (!isSelf && player.available) {
                SpeakerActionChip("Send queue") { onSendQueue(player.playerId) }
            }
        }
        val status = when {
            player.playbackState == "playing" ->
                "Playing" + (player.nowPlayingText?.let { " — $it" } ?: "")
            player.syncedTo != null -> "Grouped with ${leaderName ?: player.syncedTo}"
            else -> "Idle"
        }
        Text(
            status, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // Slider hidden when the player reports no volume or is unavailable. The remember key is
        // the polled level, so a fresh poll re-seeds the handle (drag-guard: same as the takeover
        // volume slider); on release we push once via onSetVolume.
        val level = player.volumeLevel
        if (player.available && level != null) {
            var vol by remember(level) { mutableFloatStateOf(level.toFloat()) }
            Slider(
                value = vol,
                onValueChange = { vol = it },
                onValueChangeFinished = { onSetVolume(player.playerId, vol.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A neutral text chip for a speaker-row action (styled like the queue "Clear" chip). */
@Composable
private fun SpeakerActionChip(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
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
    // Lint's ProduceStateDoesNotAssignValue misses the assignment when the right-hand
    // side is a conditional wrapping a suspend call; `value` is assigned on both branches.
    @Suppress("ProduceStateDoesNotAssignValue")
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
    onViewAlbum: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Play next") }, onClick = { onDismiss(); onPlayNext() })
        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { onDismiss(); onAdd() })
        // Radio seeds from a real media item (track/artist/album/playlist) — never a station.
        if (onStartRadio != null) {
            DropdownMenuItem(text = { Text("Start radio") }, onClick = { onDismiss(); onStartRadio() })
        }
        // Drill-in: offered only where the concrete typed object (album/artist) is in scope.
        if (onViewAlbum != null) {
            DropdownMenuItem(text = { Text("View album") }, onClick = { onDismiss(); onViewAlbum() })
        }
        if (onViewArtist != null) {
            DropdownMenuItem(text = { Text("View artist") }, onClick = { onDismiss(); onViewArtist() })
        }
    }
}
