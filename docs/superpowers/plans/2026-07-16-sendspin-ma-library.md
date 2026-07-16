# SendSpin MA Library Control Implementation Plan (Sub-project B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On-panel Music Assistant library search/play/queue inside the existing MEDIA view, fed by a second WebSocket to the MA JSON-RPC API (port 8095), so a panel can start and manage its own music without a phone.

**Architecture:** Vendor upstream SendSpinDroid's `musicassistant/` client (transport + trimmed command client + auth helper + models) into `com.rar.echodash.sendspin.musicassistant`, same convention as the engine. A new `MaLibrary` manager (sibling of `SendspinEndpoint`) owns the API socket lifecycle keyed off config + a stored MA token. `MediaPanel` gains a `MusicBrowser` (search + shelves + queue) above a compact now-playing strip. Config page grows an MA sign-in that exchanges username/password for a token on the device.

**Tech Stack:** Kotlin/Compose, Ktor 3.1.1 WebSockets (in-tree), kotlinx-serialization 1.7.3, OkHttp 4.12.0, NanoHTTPD, plain-JVM JUnit4.

**Spec:** `docs/superpowers/specs/2026-07-16-sendspin-ma-library-design.md` (read it first).

## Global Constraints

- compileSdk/targetSdk 34, minSdk 28 — never bump. NO new Gradle dependencies (everything needed is in-tree).
- Plain-JVM JUnit4 only (no Robolectric, no instrumentation). Style: `org.junit.Assert.*` + `@Test fun`, see `app/src/test/java/com/rar/echodash/sendspin/sendspin/protocol/message/MessageBuilderTest.kt`.
- Gate before EVERY commit: `./gradlew :app:testDebugUnitTest :app:assembleDebug` (if JVM issues: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto`). Baseline 738 tests green; never commit red.
- Commit trailer on every commit: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Why-comments only; match surrounding idiom. Web-driven config (no on-device settings UI).
- Upstream source of truth: clone at `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/sendspindroid` checked out at `594251f` (the NOTICE commit). If missing: `git clone https://github.com/chrisuthe/SendSpinDroid && git checkout 594251fb4b45201208a474efb3ba5ad30bd22153`. Upstream paths below are relative to `android/shared/src/commonMain/kotlin/com/sendspindroid/` unless noted.

## Note on plan shape (read before starting)

Vendoring tasks specify exact file lists + rewrite rules instead of inline code — the code comes from the pinned upstream clone. New-code tasks give exact signatures and skeletons; fill bodies following the conventions of the named neighboring files. When trimming, delete whole functions/sections — never leave stubs. Every vendored file keeps its upstream doc comments and gains a one-line header note: `// Vendored from SendSpinDroid (see NOTICE); Hearth adaptations documented in AGENTS.md.` only where a file is adapted beyond package/import rewrites.

## File Structure

```
app/src/main/java/com/rar/echodash/sendspin/musicassistant/
  EnqueueMode.kt MaTrack.kt MaAlbum.kt MaArtist.kt MaPlaylist.kt MaRadio.kt
  MaQueueItem.kt MaQueueState.kt SearchResults.kt ArtistDetails.kt      (vendored, Task 1)
  model/MaLibraryItem.kt model/MaServerInfo.kt                          (vendored, Task 1)
  transport/MaApiTransport.kt transport/MaWebSocketTransport.kt
  transport/MaCommandMultiplexer.kt transport/MaJsonExtensions.kt
  transport/MaTransportException.kt                                     (vendored, Task 1)
  MaCommandClient.kt MaAuthHelper.kt                                    (vendored+trimmed, Task 2)
app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt                (new, Task 3)
app/src/main/java/com/rar/echodash/media/MaThumbs.kt                    (new, Task 5)
app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt            (new, Task 5)
Modified: SendspinEndpoint.kt (Task 3), config/DashConfig.kt + web/ConfigServer.kt +
  assets/config/app.js + index.html (Task 4), ui/panels/MediaPanel.kt (Task 5),
  App.kt + DashboardShell.kt + HomeView.kt + NowPlayingHome.kt (Task 6), AGENTS.md + NOTICE (Tasks 1, 6)
Tests: app/src/test/java/com/rar/echodash/sendspin/musicassistant/ (vendored, Task 2),
  MaLibraryTest.kt (Task 3), SendspinConfigTest.kt + ConfigServerTest.kt additions (Task 4),
  MusicBrowserStateTest.kt + ThumbCacheTest.kt (Task 5)
```

---

## Task 1: Vendor MA models + transport + attribution

**Files:** Create the `musicassistant/` model/transport files listed above. Modify: `NOTICE`.

**Interfaces (produces):** package `com.rar.echodash.sendspin.musicassistant[.model|.transport]` compiling with no consumers. Key types later tasks use: `MaTrack/MaAlbum/MaArtist/MaPlaylist/MaRadio` (all expose `name`, `imageUri: String?`, `uri: String?` via `MaLibraryItem`), `MaQueueItem(queueItemId, name, artist, album, imageUri, duration, uri, isCurrentItem)`, `MaQueueState(items, currentIndex, shuffleEnabled, repeatMode)`, `SearchResults(artists, albums, tracks, playlists, radios, …)`, `EnqueueMode.PLAY/ADD/NEXT/REPLACE`, `MaWebSocketTransport` (`connect(token)`, `connectWithCredentials(username, password)`, `disconnect()`, `state: StateFlow`), `MaApiTransport.EventListener.onEvent(JsonObject)`, `MaTransportException`, `MaApiTransport.AuthenticationException`.

- [ ] **Step 1:** Copy from upstream `musicassistant/`: the ten model files above + `model/MaLibraryItem.kt` + `model/MaServerInfo.kt` + all five `transport/` files. Do NOT copy: `MaApiEndpoint.kt` (URL derivation is Hearth-side), `MaConnectionMode.kt`, `MaSettingsProvider.kt`, `MaAudiobook*.kt`, `MaPodcast*.kt`, `MaBrowseFolder.kt`, `model/MaPlayer.kt`, `PlayerUnavailableException.kt` — unless a kept file fails to compile without one; then keep the minimum and note it. `MaPodcastEpisode`/`MaAudiobookChapter` referenced only from dropped code stay dropped.
- [ ] **Step 2:** Rewrite packages/imports: `com.sendspindroid.musicassistant` → `com.rar.echodash.sendspin.musicassistant`; `com.sendspindroid.network.WebSocketUrlBuilder` → `com.rar.echodash.sendspin.network.WebSocketUrlBuilder`; `com.sendspindroid.shared.log.Log` → the existing shim `com.rar.echodash.sendspin.shared.log.Log`. Strip any WebRTC/proxy/remote-mode branches in the transport (`isRemoteMode` stays as a plumbed boolean where removal would cascade — hard-wire callers to `false` in Task 2/3 instead of rewriting the transport). If `kotlin.uuid.Uuid` fails to compile on our toolchain, swap to `java.util.UUID.randomUUID().toString()` with a header adaptation note.
- [ ] **Step 3:** Append to `NOTICE` under the existing SendSpinDroid entry: one line noting the `musicassistant/` API client is vendored from the same commit, trimmed to library browse/search/queue (no podcasts/audiobooks/playlist-editing/WebRTC/proxy).
- [ ] **Step 4:** Gate: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 738 tests.
- [ ] **Step 5:** Commit: `feat(sendspin): vendor MA API models + transport (sub-project B foundation)`

## Task 2: Vendor MaCommandClient (trimmed) + MaAuthHelper + their tests

**Files:** Create `musicassistant/MaCommandClient.kt`, `musicassistant/MaAuthHelper.kt`. Create tests under `app/src/test/java/com/rar/echodash/sendspin/musicassistant/`: `MaCommandClientSearchQueueTest.kt`, `MaCommandClientLibraryParsingTest.kt`, `MaCommandClientMediaParsingTest.kt`, `MaCommandClientImageTest.kt` (upstream `android/shared/src/androidHostTest/kotlin/com/sendspindroid/musicassistant/`), trimmed to kept functions.

**Interfaces (produces):**
```kotlin
class MaCommandClient {                        // upstream class, trimmed
    fun setTransport(transport: MaApiTransport?, apiUrl: String?, isRemoteMode: Boolean)
    suspend fun getEffectiveQueueId(devicePlayerId: String): String   // falls back to devicePlayerId on error
    suspend fun playMedia(uri: String, queueId: String, mediaType: String? = null,
                          enqueueMode: EnqueueMode = EnqueueMode.PLAY): Result<Unit>
    suspend fun getQueueItems(queueId: String, limit: Int = 200, offset: Int = 0): Result<MaQueueState>
    suspend fun clearQueue(queueId: String): Result<Unit>
    suspend fun playQueueItem(queueId: String, queueItemId: String): Result<Unit>
    suspend fun getPlaylists(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaPlaylist>>
    suspend fun getRadioStations(/* upstream name/sig at MaCommandClient.kt:857-869 */): Result<List<MaRadio>>
    suspend fun getRecentlyPlayed(limit: Int = 15): Result<List<MaTrack>>
    suspend fun search(query: String, mediaTypes: List<MaMediaType>? = null, limit: Int = 25,
                       libraryOnly: Boolean = true): Result<SearchResults>
}
object MaAuthHelper { suspend fun loginForToken(url: String, username: String, password: String): LoginResult }
// LoginResult(accessToken, userId, userName, serverVersion, maServerId, baseUrl)
```

- [ ] **Step 1:** Copy upstream `MaCommandClient.kt` (2,450 lines) and DELETE whole sections: `getAllPlayers`/`autoSelectPlayer`/`parsePlayers`/`parsePlayer`/`parsePlayerFeature` + player models usage, `setGroupMembers`, `powerOnPlayer`, `ungroupPlayer`, `favoriteCurrentTrack`, `getTracks`, playlist get/create/delete/add/remove-tracks (`getPlaylist`, `getPlaylistTracks`, `createPlaylist`, `deletePlaylist`, `addPlaylistTracks`, `removePlaylistTracks`), albums/artists getters (`getAlbums`, `getAlbum`, `getAlbumTracks`, artist equivalents, `ArtistDetails` usage — drop `ArtistDetails.kt` from Task 1 if nothing else references it), all podcast/audiobook/browse-folder functions and their parsers. KEEP: `getEffectiveQueueId`, `playMedia`, queue functions (`getQueueItems`, `clearQueue`, `playQueueItem`; drop `removeQueueItem`/`moveQueueItem`/`setQueueShuffle`/`setQueueRepeat` — v1 has no reorder/remove/shuffle UI), `getPlaylists`, radio getter (~line 857), `getRecentlyPlayed`, `search`, `sendCommand`, `setTransport`/`TransportContext`, and the ENTIRE image section (`extractQueueItemImage`, `extractImageUri`, `maybeProxyImageUrl`, `buildImageProxyUrl`, `extractImageFromMetadata`, `IMAGE_PROXY_SIZE`) plus every parser the kept functions call. Kept parsers keep their `internal` visibility (tests use it).
- [ ] **Step 2:** Copy `MaAuthHelper.kt` unchanged (package/import rewrite only).
- [ ] **Step 3:** Copy the four test files; delete tests exercising dropped functions; rewrite packages. Match our JUnit4 style already used by upstream host tests (they are plain JUnit4 — verify imports).
- [ ] **Step 4:** Gate → BUILD SUCCESSFUL; test count rises (record new baseline in the commit message).
- [ ] **Step 5:** Commit: `feat(sendspin): vendor trimmed MaCommandClient + MaAuthHelper with tests`

## Task 3: `connectedHost()` accessor + `MaLibrary` manager + state tests

**Files:** Modify `sendspin/SendspinEndpoint.kt`. Create `sendspin/MaLibrary.kt`, test `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt`.

**Interfaces (produces):**
```kotlin
// SendspinEndpoint addition (next to `val status`, SendspinEndpoint.kt:83):
fun connectedHost(): String? = sendSpin?.getServerAddress()

sealed interface MaLibraryState {
    data object Disabled : MaLibraryState        // sendspin off or no token
    data object Connecting : MaLibraryState
    data class Connected(val serverVersion: String?) : MaLibraryState
    data object AuthFailed : MaLibraryState      // token rejected — no auto-retry
    data class Offline(val attempt: Int) : MaLibraryState   // retrying with backoff
}

class MaLibrary(
    private val scope: CoroutineScope,           // app scope, like SendspinEndpoint
    private val playerId: String,                // UserSettings.getPlayerId()
    private val hostProvider: () -> String?,     // App wires: manual config host ?: endpoint.connectedHost()
    // test seam — default builds real transport+client:
    private val clientFactory: () -> MaCommandClient = { MaCommandClient() },
    private val transportFactory: (String) -> MaApiTransport = { url -> MaWebSocketTransport(url) },
) {
    val state: StateFlow<MaLibraryState>
    fun configure(enabled: Boolean, token: String)   // idempotent; (re)connects or tears down
    fun stop()
    suspend fun signIn(username: String, password: String): Result<LoginResult>  // caller persists token
    suspend fun playlists(): Result<List<MaPlaylist>>
    suspend fun radios(): Result<List<MaRadio>>
    suspend fun recentlyPlayed(): Result<List<MaTrack>>
    suspend fun search(query: String): Result<SearchResults>
    suspend fun play(uri: String, mediaType: String?, mode: EnqueueMode): Result<Unit>  // resolves effective queue first
    suspend fun queue(): Result<MaQueueState>
    suspend fun jumpTo(queueItemId: String): Result<Unit>
    suspend fun clearQueue(): Result<Unit>
}
```

- [ ] **Step 1 (failing tests first):** `MaLibraryTest` with fake factories: (a) `configure(enabled=false, …)` and `configure(true, token="")` → `Disabled`; (b) `configure(true, "tok")` with null host stays non-crashing and retries once host appears (fake hostProvider flips) → reaches `Connected`; (c) transport throwing `AuthenticationException` → `AuthFailed` and NO reconnect attempts; (d) transport throwing IO error → `Offline(attempt)` then retry (advance a fake/injected delay — follow the delay-injection idiom used in `MediaBridgeTest` for the rejoin timer); (e) `play(...)` calls `getEffectiveQueueId(playerId)` then `playMedia(uri, thatQueueId, mediaType, mode)` in order (recording fake client); (f) `signIn` derives `ws://host:8095/ws` from hostProvider and returns the fake `LoginResult`; null host → failure with a clear message. Run: `./gradlew :app:testDebugUnitTest --tests '*MaLibraryTest*'` → FAIL (class missing).
- [ ] **Step 2:** Implement `MaLibrary`. URL: `"ws://" + host.substringBefore(':') + ":8095/ws"` (manual `serverAddress` config carries the 8927 port — always strip). Connect flow: `Connecting` → transport `connect(token)` → wire `client.setTransport(transport, apiUrl = "http://host:8095", isRemoteMode = false)` → `Connected(serverVersion)`. Backoff on failure: 5s doubling to 60s cap while enabled (match `SendspinEndpoint`'s reconnect idiom). `AuthFailed` parks until next `configure` call with a (new) token. All ops guard on `Connected` else return `Result.failure(IllegalStateException("MA library not connected"))`. `getEffectiveQueueId` result cached per connection (invalidate on reconnect) — it's one extra round-trip otherwise on every play.
- [ ] **Step 3:** Gate; all new tests PASS, 738+ green.
- [ ] **Step 4:** Commit: `feat(sendspin): MaLibrary manager — MA API socket lifecycle + library ops`

## Task 4: Config fields + sign-in endpoints + config page card

**Files:** Modify `config/DashConfig.kt` (SendspinConfig, `DashConfig.kt:195-205`), `web/ConfigServer.kt`, `app/src/main/assets/config/app.js` (`renderSendspin()` at `app.js:772-805`), `app/src/main/assets/config/index.html` (sendspin section `:216-230`). Tests: extend `config/SendspinConfigTest.kt`, `web/ConfigServerTest.kt`.

**Interfaces (produces):** `SendspinConfig` gains `val maToken: String = ""`, `val maUser: String = ""` (both trimmed in `clamped()`). `ConfigServer` constructor gains `maSignIn: suspend (username: String, password: String) -> Result<String>` (returns display name; App-side lambda persists token+user before returning) and `maSignOut: () -> Unit` (App-side clears both fields). New session-gated routes: `POST /api/sendspin/login` body `{"username","password"}` → `{"ok":true,"userName":…}` or 502 `{"ok":false,"error":…}`; `POST /api/sendspin/logout` → `{"ok":true}`.

- [ ] **Step 1 (failing tests):** SendspinConfigTest: decode of `{}` yields `maToken == ""` (back-compat with existing device configs); `clamped()` trims both new fields. ConfigServerTest (follow its existing harness/PIN-session pattern): login route returns `userName` from a fake `maSignIn` and 502 + error string when the lambda returns failure; logout returns ok; both 401 without session cookie. Run targeted tests → FAIL.
- [ ] **Step 2:** Implement. `ConfigServer` handlers bridge suspend with `runBlocking` (NanoHTTPD worker thread; upstream auth timeout is 10s so the request returns promptly — add the why-comment). Never log the password; do not echo the token to the browser (signed-in state travels via `GET /api/config`'s `maUser`/`maToken`-nonempty).
- [ ] **Step 3:** `app.js` `renderSendspin()`: when `config.sendspin.maToken` is empty show username + password inputs + **Sign in** button → `api("POST","/api/sendspin/login",{username,password})` (pattern: `app.js:189`), on ok `load()` to re-pull config; when set show `Signed in as <maUser>` + **Sign out** → `api("POST","/api/sendspin/logout")` then `load()`. Add a hint line when sendspin is disabled or has no connected server: "Enable SendSpin and let it connect before signing in." Matching minimal markup additions in `index.html` sendspin section if `renderSendspin` doesn't fully own the DOM.
- [ ] **Step 4:** Gate; commit: `feat(sendspin): MA sign-in — config fields, PIN-gated login/logout routes, config card`

## Task 5: MusicBrowser UI + thumb loader + MediaPanel rework

**Files:** Create `media/MaThumbs.kt`, `ui/panels/MusicBrowser.kt`, tests `media/ThumbCacheTest.kt`, `ui/panels/MusicBrowserStateTest.kt`. Modify `ui/panels/MediaPanel.kt:44`.

**Interfaces (produces):**
```kotlin
// MaThumbs.kt — token-less small-image loader (ArtFetcher is HA-token-bound; see spec Open Checks #2)
class ThumbCache(private val maxEntries: Int = 48) {      // pure Kotlin, plain-JVM testable LRU
    fun get(url: String): ImageBitmap?
    fun put(url: String, bmp: ImageBitmap)                // evicts eldest beyond maxEntries
}
class MaThumbs(private val http: OkHttpClient, private val cache: ThumbCache = ThumbCache()) {
    suspend fun load(url: String): ImageBitmap?           // GET, BitmapFactory downsample ≤128px long edge, null on any failure
}

// MusicBrowser.kt — pure state holder (testable) + composable
data class BrowserShelves(val playlists: List<MaPlaylist>, val radios: List<MaRadio>, val recent: List<MaTrack>)
sealed interface BrowserContent {                          // what the browser area shows
    data class Shelves(val shelves: BrowserShelves) : BrowserContent
    data class Results(val results: SearchResults) : BrowserContent
    data class Notice(val message: String) : BrowserContent   // disabled / signed-out / offline / empty
    data object Loading : BrowserContent
}
fun browserContent(state: MaLibraryState, query: String, shelves: BrowserShelves?,
                   results: SearchResults?): BrowserContent   // pure mapping, pinned by tests

@Composable fun MusicBrowser(library: MaLibrary, thumbs: MaThumbs, modifier: Modifier = Modifier)
```
`MediaPanel` (`MediaPanel.kt:44`) gains trailing params `library: MaLibrary?`, `thumbs: MaThumbs?`: when `library == null` or state is `Disabled` render the current layout unchanged; otherwise `Column { MusicBrowser(weight 1f); NowPlayingStrip(64dp) }` where `NowPlayingStrip` is the existing panel content condensed to one row (art thumb via existing `art`, title/artist, prev/play-pause/next reusing the callbacks MediaPanel already receives).

- [ ] **Step 1 (failing tests):** `ThumbCacheTest` — LRU eviction at maxEntries, get refreshes recency, replaces on same key. `MusicBrowserStateTest` — `browserContent` pins: `Disabled`→Notice(sign-in copy), `Offline`→Notice, `Connected`+blank query+null shelves→Loading, shelves present→Shelves, query.length≥2 + results→Results, query≥2 + null results→Loading, query of 1 char→Shelves (search not fired). Run → FAIL.
- [ ] **Step 2:** Implement `ThumbCache` (LinkedHashMap accessOrder=true + removeEldestEntry) and pure `browserContent`; tests PASS.
- [ ] **Step 3:** Implement `MaThumbs.load` (Dispatchers.IO, `inSampleSize` from bounds decode — mirror the downsample approach in `ArtFetcher.decode`, `ArtFetcher.kt:81-98`) and the `MusicBrowser` composable: `TextField` search (400ms debounce via `snapshotFlow`+`collectLatest`+`delay`, min 2 chars), three `LazyRow` shelves with 96dp cells (thumb + one-line name), grouped `LazyColumn` results, item tap → `library.play(item.uri, item.mediaType.name.lowercase(), EnqueueMode.PLAY)`; long-press menu (`DropdownMenu`) → Play next (`EnqueueMode.NEXT`) / Add to queue (`EnqueueMode.ADD`). Queue icon button top-right toggles the queue overlay: `LazyColumn` of `MaQueueItem` (highlight `isCurrentItem`), tap → `jumpTo`, header Clear button → `clearQueue`; while visible re-fetch `queue()` every 5s (`LaunchedEffect` loop) and after every mutation. Shelf fetch in `LaunchedEffect(state is Connected)`; command failures → small inline `Text` error that auto-clears after 4s. Fetch limits are fixed (playlists/radios 25, recent 15, search 25, queue 200) with NO load-more UI in v1 — the limits exist to bound memory on the 1GB Echo, not to page through. All fetches launch in the composition scope; state hoisted in `remember` so leaving the MEDIA view drops it (spec: no resident cache).
- [ ] **Step 4:** Update `MediaPanel` + its `DashboardShell.kt:249` callsite (pass nulls for now — wiring lands in Task 6; nullable params default to null so this compiles without touching DashboardShell yet — still update the callsite explicitly in Task 6).
- [ ] **Step 5:** Gate; commit: `feat(sendspin): MusicBrowser UI + thumb loader inside the media view`

## Task 6: App wiring, takeover browse button, on-device verify, docs

**Files:** Modify `App.kt` (deps ~`:220-240`, sendspin config collector ~`:560-580` region, `DashboardShell(...)` call `:750-810`), `ui/DashboardShell.kt` (`:230-253`), `ui/HomeView.kt` (`:209-219`), `ui/NowPlayingHome.kt` (`:54-147`), `AGENTS.md` (vendoring paragraph `:90-99`).

**Interfaces (consumes):** everything above. **Produces:** running feature.

- [ ] **Step 1:** Construct in `AppDeps`: `val maThumbs = MaThumbs(OkHttpClient())` (or reuse an existing shared OkHttp instance if one is exposed — check `ArtFetcher`'s); `val maLibrary = MaLibrary(scope, UserSettings.getPlayerId(), hostProvider = { cfg.sendspin.serverAddress.substringBefore(':').trim().ifBlank { null } ?: sendspin.connectedHost() })`. NOTE: `UserSettings.initialize(context)` must already have run — it happens in `SendspinEndpoint.init` (`SendspinEndpoint.kt:77`); construct `maLibrary` after `sendspin`.
- [ ] **Step 2:** Config collector (add beside the existing sendspin collectors, pattern `App.kt` syncDelay collector): `config.map { it.sendspin.enabled to it.sendspin.maToken }.distinctUntilChanged().collect { (en, tok) -> maLibrary.configure(en, tok) }`. Wire `ConfigServer(maSignIn = { u, p -> maLibrary.signIn(u, p).map { r -> configStore.update { c -> c.copy(sendspin = c.sendspin.copy(maToken = r.accessToken, maUser = r.userName)) }; r.userName } }, maSignOut = { configStore.update { c -> c.copy(sendspin = c.sendspin.copy(maToken = "", maUser = "")) } })` — adapt to the actual `ConfigStore.update` signature.
- [ ] **Step 3:** Thread `library`/`thumbs` App → `DashboardShell` → `MediaPanel` (`DashboardShell.kt:249`). Takeover button: `NowPlayingHome` gains `onBrowse: () -> Unit`; place an icon button (`Icons.AutoMirrored.Outlined.QueueMusic`, reuse `NpTransportButton` styling `NowPlayingHome.kt:150-162`) at `Alignment.TopEnd` (TopStart is occupied by the clock drawn in `HomeView.kt:232-236`). Thread NowPlayingHome → HomeView → DashboardShell → App; in App: `deps.currentView.value = DashView.MEDIA; deps.kiosk.onUserInteraction()` (mirror `onSelect`, `App.kt:752-755`).
- [ ] **Step 4:** `AGENTS.md`: extend the vendored-adaptations paragraph — `musicassistant/` subpackage vendored same commit, trimmed to search/shelves/queue command surface, transport hard-wired local (`isRemoteMode=false`), auth via token from config.
- [ ] **Step 5:** Gate. Commit: `feat(sendspin): wire MA library into app — media view browser + takeover browse button`
- [ ] **Step 6 (device verify — report results, do not skip):** Build once, flash BOTH devices: `adb -s 10.75.1.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk && adb -s 10.75.1.98:5555 shell am force-stop com.rar.echodash && adb -s 10.75.1.98:5555 shell am start -n com.rar.echodash/.MainActivity` (repeat with `-s HA1TREYR`). Verify on the Echo (MA server 10.75.1.54, PIN 379199 for config page): (1) config page shows sign-in; sign in with user-provided MA credentials — if the user isn't around, verify the error path (bad credentials → clear error, no crash) and STOP there, flagging sign-in as user-pending; (2) after sign-in: MEDIA view shows shelves with art; (3) search "the" → grouped results; (4) tap a track → plays on the panel (existing takeover appears); (5) long-press → Play next lands after current track (check queue view); (6) queue view: jump + clear work; (7) takeover browse button lands on MEDIA view; (8) sign out → browser shows sign-in notice, audio path unaffected; (9) only if the user is driving MA anyway: group the panel with another player in MA, then tap a track — playback must land on the group queue (pins `getEffectiveQueueId`), otherwise mark user-pending like sign-in. Watch `adb logcat -d` for SS.* errors after each step. HARD RULES: never `dumpsys media.audio_flinger`; logcat dumps only (`-d`), no streaming greps.

## Deferred / follow-up (out of scope for this plan)

Artist/album drill-down; queue reorder/remove; shuffle/repeat UI; favorites; event-driven queue refresh (v1 polls while visible); takeover polish batch (#11) and hardening batch ride separately.
