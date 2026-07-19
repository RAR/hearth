# Library A-Z Browsing + Drill-In Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add real library browsing to the MEDIA view's MusicBrowser — Artists and Albums A-Z lists behind a `Home · Artists · Albums` tab row, with drill-in pages (artist → their albums grid, album → its tracklist) reached by tapping (artist row) or the long-press menu ("View album" / "View artist").

**Architecture:** Data layer first — `MaCommandClient` gains four read methods (`getLibraryArtists`/`getLibraryAlbums` paged A-Z, `getArtistAlbums`/`getAlbumTracks` drill-in) reusing the existing `parseArtistsArray`/`parseAlbumsArray`/`parseTracksArray` parsers; `MaLibrary` wraps them via its `withClient` idiom (`libraryArtists`/`libraryAlbums`/`artistAlbums`/`albumTracks`), unpacking the typed `MaArtist`/`MaAlbum` id+provider so the UI never touches provider strings. A new pure module `ui/model/BrowserNavModel.kt` holds a `BrowserPage` sealed type plus `pushPage`/`popPage`/`tabTarget` (plain-JVM tested). `MusicBrowser` replaces its implicit two-state content with a `pageStack` back-stack: a tab row, A-Z pages with end-of-list paging (generic `PagedLibraryColumn`), two detail pages (`ArtistDetail` grid reusing `MediaCell`, `AlbumDetail` tracklist), a back chip, and long-press "View album"/"View artist" entries on `EnqueueMenu` threaded wherever the concrete typed object exists. Search keeps its existing ≥2-char override; the queue overlay + `openQueueSignal` behavior are untouched.

**Tech Stack:** Kotlin + Jetpack Compose (native Android kiosk). Pure model logic in `com.rar.echodash.ui.model` (plain-JVM JUnit4). MA API JSON over the vendored `MaCommandClient` (kotlinx.serialization.json). Gradle (`:app`), JDK 21 (Amazon Corretto).

## Global Constraints

- **Gate before EVERY commit** — run both, each must show RC=0 before committing:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; RC=$?; echo "GATE RC=$RC"
  node --check app/src/main/assets/config/app.js; echo "NODE RC=$?"
  ```
  Capture gradle's OWN exit code via `RC=$?` immediately after the gradle invocation. NEVER pipe gradle to `tail`/`head`/any filter — that masks gradle's exit code behind the filter's. Redirect all gradle output to the scratchpad log and inspect the log if RC is non-zero. `node --check` guards the config bundle; no task here edits `app.js`, so it always passes, but it stays part of the gate.
- **Commit trailer** — every commit message ends with exactly this trailer line:
  `Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL`
- **NO new dependencies.** Plain-JVM JUnit4 only (`org.junit.Test`, `org.junit.Assert.*`), mirroring existing idioms.
- **Compose UI is NOT unit-tested in this repo.** Testable logic lives in pure model functions (`ui/model/*.kt`) and wrapper/parser methods; composables are verified only by the `:app:assembleDebug` compile inside the gate. UI-only tasks (3 & 4) explicitly have no unit test — do not invent fake Compose tests.
- **Do NOT push.** Commits stay local.
- **Mutation/refetch callbacks bump versions/refetch ONLY on completion** (`.onSuccess`), never synchronously.
- **A-Z paging:** page size 200, `order_by = "sort_name"`. Fetch page 0 on first show; when the list scrolls to its end and the last fetch returned a full page (200), fetch the next offset and append. Fetch failure → the existing `showError` toast; keep what loaded.
- **Row gestures:** artist row tap DRILLS IN (primary — an artist has no obvious single "play"); album row/cell tap PLAYS the album; track row tap plays that track. Drill-in menu entries ("View album" / "View artist") ride the `EnqueueMenu`; the back chip pops the stack.
- **Search override preserved:** a query ≥2 chars shows `ResultsPane` regardless of the page stack (clearing the query returns to the stack top). Queue overlay + `openQueueSignal` (up-next tap) behavior unchanged, drawn above everything.
- **Tab chips:** `Home · Artists · Albums`; the active tab's label is lit with the `#4FC3F7` accent (`Color(0xFF4FC3F7)`), same accent as `QueueToggleChip`.
- **Current suite is 992 tests.** Task 1 adds 4 (→996). Task 2 adds 11 (→1007). Tasks 3 and 4 add none (→1007). Each task states its expected count.
- Scratchpad for logs: `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/` (create with `mkdir -p` if absent).

## File Structure

- `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt` — four new read methods (`getLibraryArtists`/`getLibraryAlbums`/`getArtistAlbums`/`getAlbumTracks`) in the Library Commands section; reuses existing parsers.
- `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt` — four new `withClient` wrappers (`libraryArtists`/`libraryAlbums`/`artistAlbums`/`albumTracks`); two new imports (`MaArtist`, `MaAlbum`).
- `app/src/main/java/com/rar/echodash/ui/model/BrowserNavModel.kt` — **new** file: `BrowserPage` sealed interface + `pushPage`/`popPage`/`tabTarget` pure fns.
- `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — page-stack integration: tab row, A-Z pages with paging, detail pages, back chip, and (Task 4) the "View album"/"View artist" menu entries + their threading.
- Tests: `.../sendspin/MaLibraryTest.kt` (Task 1, +4), `.../ui/model/BrowserNavModelTest.kt` (Task 2, **new**, +11).

**Decomposition note (flagged for review):**
- **(a) `BrowserPage` lives in `ui/model/BrowserNavModel.kt`, not the UI layer.** The design spec draws `BrowserPage` conceptually in the UI section, but `pushPage`/`popPage`/`tabTarget` must be plain-JVM testable and they operate on `BrowserPage`. `BrowserPage` only references `MaArtist`/`MaAlbum`, which are pure Kotlin data classes (no Android deps), so the whole module is JVM-testable. Putting the sealed type beside its pure fns keeps the tested unit self-contained. `MusicBrowser` imports it.
- **(b) Four-task split kept even though Tasks 3 & 4 both touch only `MusicBrowser.kt`.** Task 3 = "pages, rendering, and tap-navigation" (browse A-Z, drill into an artist's albums, play, paging, back chip). Task 4 = "the long-press View-entry surface" (the `EnqueueMenu` drill-in entries + their threading, which also lights up reaching `AlbumDetail`). A reviewer can approve browsing (Task 3) while separately scrutinising the drill-in menu wiring (Task 4). Task 3 builds `AlbumDetailPage` (fully functional) but it is only *reachable* once Task 4 adds "View album" — building it in Task 3 keeps the `when (page)` exhaustive without a placeholder branch and makes the screen reviewable in isolation.

---

## Task 1 — Data layer: four MaCommandClient read methods + four MaLibrary wrappers

Add the paged A-Z list methods and the two drill-in methods to `MaCommandClient` (reusing the existing parsers), then wrap them in `MaLibrary`. Tested deliverable: the wrapper arg-building pins (MaLibraryTest, via the existing `FakeTransport` seam that drives the real `MaCommandClient`). No new parse tests — the existing `parseArtistsArray`/`parseAlbumsArray`/`parseTracksArray` pins stand.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt` — four new methods in the Library Commands section (after `getRadioStations`, ~line 355, before `search`).
- Modify: `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt` — two imports; four wrappers after `search()` (~line 137).
- Test: `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt` — two imports + 4 new `@Test` methods.

**Interfaces:**
- Consumes: `parseArtistsArray(JsonArray?)`, `parseAlbumsArray(JsonArray?)`, `parseTracksArray(JsonArray?)` (existing, same file); `optJsonArray`/`optJsonObject` (existing imports); `MaArtist.artistId`/`.provider`, `MaAlbum.albumId`/`.provider` (existing data classes).
- Produces:
  - `MaCommandClient.getLibraryArtists(limit: Int = 200, offset: Int = 0): Result<List<MaArtist>>` → `music/artists/library_items` `{limit, offset, order_by: "sort_name"}`.
  - `MaCommandClient.getLibraryAlbums(limit: Int = 200, offset: Int = 0): Result<List<MaAlbum>>` → `music/albums/library_items` `{limit, offset, order_by: "sort_name"}`.
  - `MaCommandClient.getArtistAlbums(artistId: String, provider: String): Result<List<MaAlbum>>` → `music/artists/artist_albums` `{item_id, provider_instance_id_or_domain}`.
  - `MaCommandClient.getAlbumTracks(albumId: String, provider: String): Result<List<MaTrack>>` → `music/albums/album_tracks` `{item_id, provider_instance_id_or_domain}`.
  - `MaLibrary.libraryArtists(offset: Int = 0): Result<List<MaArtist>>` — `withClient { it.getLibraryArtists(offset = offset) }`.
  - `MaLibrary.libraryAlbums(offset: Int = 0): Result<List<MaAlbum>>` — `withClient { it.getLibraryAlbums(offset = offset) }`.
  - `MaLibrary.artistAlbums(artist: MaArtist): Result<List<MaAlbum>>` — `withClient { it.getArtistAlbums(artist.artistId, artist.provider) }`.
  - `MaLibrary.albumTracks(album: MaAlbum): Result<List<MaTrack>>` — `withClient { it.getAlbumTracks(album.albumId, album.provider) }`.

### Steps

- [ ] **Step 1: Write the failing MaLibrary tests.** In `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt`, add two imports next to the existing `com.rar.echodash.sendspin.musicassistant.EnqueueMode` import (line 3):
  ```kotlin
  import com.rar.echodash.sendspin.musicassistant.MaAlbum
  import com.rar.echodash.sendspin.musicassistant.MaArtist
  ```
  Then append these 4 methods inside the class, after the last radio/favorite test `playOmitsRadioModeByDefault` (before the class's closing brace):
  ```kotlin
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
  ```
  (The `FakeTransport.respond` default returns `json("{}")` for these unknown commands → each parser gets a null result array → returns an empty list → `Result.success(emptyList())`, so `.isSuccess` holds while the recorder pins the command name + args.)

- [ ] **Step 2: Run the tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.sendspin.MaLibraryTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compile failure — `unresolved reference: libraryArtists` / `libraryAlbums` / `artistAlbums` / `albumTracks`. RC != 0.

- [ ] **Step 3: Add the four `MaCommandClient` methods.** In `MaCommandClient.kt`, insert these four methods immediately after `getRadioStations`'s closing brace (~line 355) and before the `/** Search Music Assistant library. */` doc comment (~line 357). They live in the same package as `MaArtist`/`MaAlbum`/`MaTrack`, so no imports are needed.
  ```kotlin
      /**
       * Get a page of library artists A-Z. Reuses [parseArtistsArray]; the response is either a
       * top-level `result` array or `result.items`. `order_by: "sort_name"` for stable A-Z order.
       */
      suspend fun getLibraryArtists(limit: Int = 200, offset: Int = 0): Result<List<MaArtist>> {
          return try {
              Log.d(TAG, "Fetching library artists (limit=$limit, offset=$offset)")
              val response = sendCommand(
                  "music/artists/library_items",
                  mapOf("limit" to limit, "offset" to offset, "order_by" to "sort_name"),
              )
              val array = response.optJsonArray("result")
                  ?: response.optJsonObject("result")?.optJsonArray("items")
              val artists = parseArtistsArray(array)
              Log.d(TAG, "Got ${artists.size} library artists")
              Result.success(artists)
          } catch (e: Exception) {
              Log.e(TAG, "Failed to fetch library artists", e)
              Result.failure(e)
          }
      }

      /**
       * Get a page of library albums A-Z. Reuses [parseAlbumsArray]; response shape as above.
       */
      suspend fun getLibraryAlbums(limit: Int = 200, offset: Int = 0): Result<List<MaAlbum>> {
          return try {
              Log.d(TAG, "Fetching library albums (limit=$limit, offset=$offset)")
              val response = sendCommand(
                  "music/albums/library_items",
                  mapOf("limit" to limit, "offset" to offset, "order_by" to "sort_name"),
              )
              val array = response.optJsonArray("result")
                  ?: response.optJsonObject("result")?.optJsonArray("items")
              val albums = parseAlbumsArray(array)
              Log.d(TAG, "Got ${albums.size} library albums")
              Result.success(albums)
          } catch (e: Exception) {
              Log.e(TAG, "Failed to fetch library albums", e)
              Result.failure(e)
          }
      }

      /**
       * Get an artist's albums (drill-in). [provider] is the artist's `provider_instance_id_or_domain`
       * ("library" for a library artist). Reuses [parseAlbumsArray].
       */
      suspend fun getArtistAlbums(artistId: String, provider: String): Result<List<MaAlbum>> {
          return try {
              Log.d(TAG, "Fetching artist albums (item_id=$artistId, provider=$provider)")
              val response = sendCommand(
                  "music/artists/artist_albums",
                  mapOf("item_id" to artistId, "provider_instance_id_or_domain" to provider),
              )
              val array = response.optJsonArray("result")
                  ?: response.optJsonObject("result")?.optJsonArray("items")
              Result.success(parseAlbumsArray(array))
          } catch (e: Exception) {
              Log.e(TAG, "Failed to fetch artist albums: $artistId", e)
              Result.failure(e)
          }
      }

      /**
       * Get an album's tracks (drill-in), server-sorted by disc/track. [provider] is the album's
       * `provider_instance_id_or_domain`. Reuses [parseTracksArray].
       */
      suspend fun getAlbumTracks(albumId: String, provider: String): Result<List<MaTrack>> {
          return try {
              Log.d(TAG, "Fetching album tracks (item_id=$albumId, provider=$provider)")
              val response = sendCommand(
                  "music/albums/album_tracks",
                  mapOf("item_id" to albumId, "provider_instance_id_or_domain" to provider),
              )
              val array = response.optJsonArray("result")
                  ?: response.optJsonObject("result")?.optJsonArray("items")
              Result.success(parseTracksArray(array))
          } catch (e: Exception) {
              Log.e(TAG, "Failed to fetch album tracks: $albumId", e)
              Result.failure(e)
          }
      }
  ```

- [ ] **Step 4: Add the two imports to `MaLibrary.kt`.** Insert next to the existing `com.rar.echodash.sendspin.musicassistant.*` imports (after the `MaCommandClient` import on line 6), keeping alphabetical-ish grouping:
  ```kotlin
  import com.rar.echodash.sendspin.musicassistant.MaAlbum
  import com.rar.echodash.sendspin.musicassistant.MaArtist
  ```

- [ ] **Step 5: Add the four `MaLibrary` wrappers.** In `MaLibrary.kt`, insert immediately after `search()` (~line 137), before the `// ---- Queue ops ... ----` banner:
  ```kotlin
      // ---- Library browse / drill-in ops (all read-only; withClient, not withQueue) ----

      /** A page of library artists A-Z (page size 200, sort_name order). */
      suspend fun libraryArtists(offset: Int = 0): Result<List<MaArtist>> =
          withClient { it.getLibraryArtists(offset = offset) }

      /** A page of library albums A-Z (page size 200, sort_name order). */
      suspend fun libraryAlbums(offset: Int = 0): Result<List<MaAlbum>> =
          withClient { it.getLibraryAlbums(offset = offset) }

      /** The albums of [artist] (drill-in); unpacks its id + provider so the UI never sees them. */
      suspend fun artistAlbums(artist: MaArtist): Result<List<MaAlbum>> =
          withClient { it.getArtistAlbums(artist.artistId, artist.provider) }

      /** The tracks of [album] (drill-in), server-sorted by disc/track. */
      suspend fun albumTracks(album: MaAlbum): Result<List<MaTrack>> =
          withClient { it.getAlbumTracks(album.albumId, album.provider) }
  ```

- [ ] **Step 6: Run the tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.sendspin.MaLibraryTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, existing + 4 new tests green.

- [ ] **Step 7: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (suite now 996) and `NODE RC=0`.

- [ ] **Step 8: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt \
          app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt \
          app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt
  git commit -m "feat(media): MA library A-Z list + drill-in read methods + MaLibrary wrappers

getLibraryArtists/getLibraryAlbums (library_items, page 200, order_by sort_name)
and getArtistAlbums/getAlbumTracks (drill-in) reuse the existing parsers; MaLibrary
wraps them via withClient (artistAlbums/albumTracks unpack the typed object's
id+provider). Arg-building pinned via the FakeTransport seam (4 tests).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 2 — Pure navigation model: BrowserPage + pushPage/popPage/tabTarget

Add the back-stack sealed type and its three pure operations in a new `ui/model` module, plain-JVM tested. Tested deliverable: push dedup, pop-never-removes-Home, and tab targeting across nested stacks.

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/BrowserNavModel.kt`.
- Create (test): `app/src/test/java/com/rar/echodash/ui/model/BrowserNavModelTest.kt`.

**Interfaces:**
- Consumes: `MaArtist`, `MaAlbum` (from `com.rar.echodash.sendspin.musicassistant`; pure data classes).
- Produces:
  - `sealed interface BrowserPage` with `data object Home`, `data object Artists`, `data object Albums`, `data class ArtistDetail(val artist: MaArtist)`, `data class AlbumDetail(val album: MaAlbum)`.
  - `fun pushPage(stack: List<BrowserPage>, page: BrowserPage): List<BrowserPage>` — appends `page`, no-op when `page` already equals the current top.
  - `fun popPage(stack: List<BrowserPage>): List<BrowserPage>` — drops the top; never shrinks below a single element (Home is always index 0).
  - `fun tabTarget(stack: List<BrowserPage>): BrowserPage` — the nearest root page (Home/Artists/Albums) scanning from the stack top; falls back to `Home`.

### Steps

- [ ] **Step 1: Write the failing tests.** Create `app/src/test/java/com/rar/echodash/ui/model/BrowserNavModelTest.kt`:
  ```kotlin
  package com.rar.echodash.ui.model

  import com.rar.echodash.sendspin.musicassistant.MaAlbum
  import com.rar.echodash.sendspin.musicassistant.MaArtist
  import org.junit.Assert.assertEquals
  import org.junit.Test

  class BrowserNavModelTest {

      private fun artist(id: String) =
          MaArtist(artistId = id, name = id, imageUri = null, uri = "library://artist/$id")

      private fun album(id: String) = MaAlbum(
          albumId = id, name = id, imageUri = null, uri = "library://album/$id",
          artist = null, year = null, trackCount = null, albumType = null,
      )

      // ---- pushPage ----

      @Test
      fun pushPageAppendsNewPage() {
          val out = pushPage(listOf(BrowserPage.Home), BrowserPage.Artists)
          assertEquals(listOf(BrowserPage.Home, BrowserPage.Artists), out)
      }

      @Test
      fun pushPageDedupsWhenPushingCurrentTop() {
          val stack = listOf(BrowserPage.Home, BrowserPage.Artists)
          assertEquals(stack, pushPage(stack, BrowserPage.Artists))
      }

      @Test
      fun pushPageAppendsDetailOntoArtists() {
          val detail = BrowserPage.ArtistDetail(artist("art-1"))
          val out = pushPage(listOf(BrowserPage.Home, BrowserPage.Artists), detail)
          assertEquals(3, out.size)
          assertEquals(detail, out.last())
      }

      // ---- popPage ----

      @Test
      fun popPageRemovesTop() {
          val out = popPage(listOf(BrowserPage.Home, BrowserPage.Artists))
          assertEquals(listOf(BrowserPage.Home), out)
      }

      @Test
      fun popPageNeverRemovesHome() {
          assertEquals(listOf(BrowserPage.Home), popPage(listOf(BrowserPage.Home)))
      }

      @Test
      fun popPageFromDetailReturnsToRoot() {
          val stack = listOf(BrowserPage.Home, BrowserPage.Artists, BrowserPage.ArtistDetail(artist("a")))
          assertEquals(listOf(BrowserPage.Home, BrowserPage.Artists), popPage(stack))
      }

      // ---- tabTarget ----

      @Test
      fun tabTargetHomeForBaseStack() {
          assertEquals(BrowserPage.Home, tabTarget(listOf(BrowserPage.Home)))
      }

      @Test
      fun tabTargetArtistsForArtistsPage() {
          assertEquals(BrowserPage.Artists, tabTarget(listOf(BrowserPage.Home, BrowserPage.Artists)))
      }

      @Test
      fun tabTargetArtistsForArtistDetail() {
          val stack = listOf(BrowserPage.Home, BrowserPage.Artists, BrowserPage.ArtistDetail(artist("a")))
          assertEquals(BrowserPage.Artists, tabTarget(stack))
      }

      @Test
      fun tabTargetAlbumsForAlbumDetail() {
          val stack = listOf(BrowserPage.Home, BrowserPage.Albums, BrowserPage.AlbumDetail(album("b")))
          assertEquals(BrowserPage.Albums, tabTarget(stack))
      }

      @Test
      fun tabTargetArtistsForAlbumReachedUnderArtist() {
          val stack = listOf(
              BrowserPage.Home, BrowserPage.Artists,
              BrowserPage.ArtistDetail(artist("a")), BrowserPage.AlbumDetail(album("b")),
          )
          assertEquals(BrowserPage.Artists, tabTarget(stack))
      }
  }
  ```

- [ ] **Step 2: Run the tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.BrowserNavModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compile failure — `unresolved reference: BrowserPage` / `pushPage` / `popPage` / `tabTarget`. RC != 0.

- [ ] **Step 3: Create `BrowserNavModel.kt`.** Write `app/src/main/java/com/rar/echodash/ui/model/BrowserNavModel.kt`:
  ```kotlin
  package com.rar.echodash.ui.model

  import com.rar.echodash.sendspin.musicassistant.MaAlbum
  import com.rar.echodash.sendspin.musicassistant.MaArtist

  /**
   * A page in the MusicBrowser back-stack. Home is always the bottom of the stack. The three
   * root pages (Home/Artists/Albums) are what the tab row targets; the two detail pages carry the
   * typed object needed to fetch their contents (and to drill further).
   *
   * Kept in ui/model (not the UI layer) so the pure navigation fns below stay plain-JVM testable —
   * MaArtist/MaAlbum are pure data classes with no Android dependencies.
   */
  sealed interface BrowserPage {
      data object Home : BrowserPage
      data object Artists : BrowserPage
      data object Albums : BrowserPage
      data class ArtistDetail(val artist: MaArtist) : BrowserPage
      data class AlbumDetail(val album: MaAlbum) : BrowserPage
  }

  /** Push [page] onto [stack]; a no-op when [page] already equals the current top (no dup drill). */
  fun pushPage(stack: List<BrowserPage>, page: BrowserPage): List<BrowserPage> =
      if (stack.lastOrNull() == page) stack else stack + page

  /** Pop the top page. Never shrinks below the single Home root at index 0. */
  fun popPage(stack: List<BrowserPage>): List<BrowserPage> =
      if (stack.size <= 1) stack else stack.dropLast(1)

  /**
   * Which of Home/Artists/Albums the tab row should light — the nearest root page scanning from the
   * stack top (detail pages highlight the root they descend from; an album reached under an artist
   * therefore lights Artists). Falls back to Home (defensive; Home is always present).
   */
  fun tabTarget(stack: List<BrowserPage>): BrowserPage =
      stack.lastOrNull {
          it is BrowserPage.Home || it is BrowserPage.Artists || it is BrowserPage.Albums
      } ?: BrowserPage.Home
  ```

- [ ] **Step 4: Run the tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.BrowserNavModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, 11 new tests green.

- [ ] **Step 5: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (suite now 1007) and `NODE RC=0`.

- [ ] **Step 6: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/model/BrowserNavModel.kt \
          app/src/test/java/com/rar/echodash/ui/model/BrowserNavModelTest.kt
  git commit -m "feat(media): BrowserPage back-stack model + pushPage/popPage/tabTarget

Pure navigation model for the MusicBrowser drill-in: a BrowserPage sealed type
(Home/Artists/Albums roots + ArtistDetail/AlbumDetail) with push (dedups the current
top), pop (never removes Home), and tabTarget (nearest root from the stack top).
Lives in ui/model so it stays plain-JVM tested (11 tests).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 3 — MusicBrowser page-stack: tab row, A-Z pages with paging, detail pages, back chip

Replace the browser's implicit two-state content with a `pageStack` back-stack. Add the tab row, the A-Z Artists/Albums pages (generic `PagedLibraryColumn` end-of-list paging), the `ArtistDetail` album grid (reusing `MediaCell`) and `AlbumDetail` tracklist, and the back chip. Artist row tap drills into `ArtistDetail`; album row/cell and track row taps play. Search keeps its ≥2-char override; the queue overlay is untouched. **UI task: no unit test** — the navigation logic is covered by Task 2; composables are verified only by the `:app:assembleDebug` compile.

`AlbumDetailPage` is built here (fully functional) but only becomes *reachable* in Task 4 (via the "View album" menu entry) — building it now keeps the `when (page)` exhaustive and the screen reviewable in isolation.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — new imports; `pageStack` state + `openArtist` push lambda; the `Column` body (tab row insert + `Box` page dispatch); new composables `BrowserTabs`/`TabChip`/`PagedLibraryColumn`/`ArtistsPage`/`AlbumsPage`/`LibraryRow`/`LoadingRow`/`ArtistDetailPage`/`AlbumDetailPage`/`DetailHeader`/`BackChip`/`TrackRow`; one file-level constant.

**Interfaces:**
- Consumes: `MaLibrary.libraryArtists`/`libraryAlbums`/`artistAlbums`/`albumTracks` (Task 1); `BrowserPage`/`pushPage`/`popPage`/`tabTarget` (Task 2); `formatTrackTime(ms: Long)` (`com.rar.echodash.media`); existing `MediaCell`/`EnqueueMenu`/`Thumb`/`EmptyHint`/`playItem`/`startRadio`/`showError`.
- Produces (internal to MusicBrowser): `pageStack: List<BrowserPage>` state; `openArtist: (MaArtist) -> Unit` (clears the query, pushes `ArtistDetail`); the page composables above. No changes to `MediaPanel`/`DashboardShell`/`App` — the entire drilldown is internal to `MusicBrowser`, which already receives `library` + `thumbs`.

### Steps

- [ ] **Step 1: Read** the current `MusicBrowser.kt`: the import block (lines ~1-93), the state block (lines ~155-165), the `playItem`/`startRadio` lambdas (lines ~243-269), the `Column(modifier) { ... }` body (lines ~273-340), and confirm `EmptyHint` (from `PanelScaffold.kt`, same package — no import) and `MediaCell`/`EnqueueMenu`/`Thumb` signatures still match the code shown below.

- [ ] **Step 2: Add imports.** In `MusicBrowser.kt`, add these next to the matching existing import groups:
  - Compose foundation/lazy/grid (near lines ~23-31):
    ```kotlin
    import androidx.compose.foundation.lazy.grid.GridCells
    import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
    import androidx.compose.foundation.lazy.grid.items as gridItems
    import androidx.compose.foundation.lazy.itemsIndexed
    import androidx.compose.foundation.lazy.rememberLazyListState
    ```
    (`androidx.compose.foundation.lazy.items` — the list variant — is already imported at line 26; the grid variant is aliased `gridItems` to avoid a same-name import clash.)
  - Icons (near lines ~32-41):
    ```kotlin
    import androidx.compose.material.icons.automirrored.outlined.ArrowBack
    ```
  - Runtime (near lines ~47-56):
    ```kotlin
    import androidx.compose.runtime.derivedStateOf
    ```
  - App types (near lines ~74-88):
    ```kotlin
    import com.rar.echodash.media.formatTrackTime
    import com.rar.echodash.sendspin.musicassistant.MaArtist
    import com.rar.echodash.ui.model.BrowserPage
    import com.rar.echodash.ui.model.popPage
    import com.rar.echodash.ui.model.pushPage
    import com.rar.echodash.ui.model.tabTarget
    ```
    (`MaAlbum` is already imported at line 78; `currentItemOf`/`nextRepeatMode` at lines 87-88.)

- [ ] **Step 3: Add the file-level page-size constant.** Below the existing `private const val SWIPE_DISMISS_FRACTION = 0.30f` (line 96):
  ```kotlin
  private const val LIBRARY_PAGE_SIZE = 200
  ```

- [ ] **Step 4: Add the `pageStack` state.** In `MusicBrowser`, immediately after `val scope = rememberCoroutineScope()` (line 165):
  ```kotlin
      // Browser back-stack; current page = pageStack.last(). Home is always at the bottom.
      var pageStack by remember { mutableStateOf<List<BrowserPage>>(listOf(BrowserPage.Home)) }
  ```

- [ ] **Step 5: Add the `openArtist` push lambda.** Immediately after the `startRadio` lambda's closing brace (line 269, before `val content = browserContent(...)`):
  ```kotlin
      // Drill into an artist. Clears any active search first so the pushed detail page isn't
      // hidden by the ≥2-char search override (also used by the "View artist" menu entry in
      // Task 4, where a search is typically active). pushPage dedups a repeated drill.
      val openArtist: (MaArtist) -> Unit = { artist ->
          query = ""
          pageStack = pushPage(pageStack, BrowserPage.ArtistDetail(artist))
      }
  ```

- [ ] **Step 6: Rewrite the `Column(modifier) { ... }` body.** Replace the entire `Column(modifier) { ... }` block (lines ~273-340) with this — it inserts the tab row and replaces the `Box`'s content dispatch with the page stack (the `SearchField`+`QueueToggleButton` row, `error` line, and `QueuePane` call are unchanged):
  ```kotlin
      Column(modifier) {
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
              SearchField(query, { query = it }, enabled = isConnected, modifier = Modifier.weight(1f))
              QueueToggleButton(active = queueVisible) { queueVisible = !queueVisible }
          }
          Spacer(Modifier.height(8.dp))
          // Tab row: Home · Artists · Albums. Tapping a tab resets the stack to [Home,<tab>] (or
          // [Home]) and closes the queue overlay so the picked page is visible; it does NOT clear
          // an active search (clearing search then returns to the freshly-picked stack top).
          BrowserTabs(
              active = tabTarget(pageStack),
              onSelect = { target ->
                  queueVisible = false
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
                      onRemove = { item ->
                          val result = library.removeQueueItem(item.queueItemId)
                          result.onSuccess { queueVersion++ }
                          result.onFailure { showError(it.message ?: "Couldn't remove item") }
                          result.isSuccess
                      },
                      onToggleShuffle = { queueState?.let { q -> onSetShuffle(!q.shuffleEnabled) }; queueVersion++ },
                      onCycleRepeat = { queueState?.let { q -> onSetRepeat(nextRepeatMode(q.repeatMode)) }; queueVersion++ },
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
                      is BrowserContent.Results -> ResultsPane(c.results, thumbs, playItem, startRadio)
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
                          library = library, thumbs = thumbs,
                          onPlay = playItem, onStartRadio = startRadio, onError = showError,
                      )
                      is BrowserPage.ArtistDetail -> ArtistDetailPage(
                          artist = page.artist, library = library, thumbs = thumbs,
                          onBack = { pageStack = popPage(pageStack) },
                          onPlay = playItem, onStartRadio = startRadio, onError = showError,
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
  ```

- [ ] **Step 7: Add the tab-row composables.** Insert after the `QueueToggleButton` composable (~line 405, the end of the `// ---- Header ----` section):
  ```kotlin
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
  ```

- [ ] **Step 8: Add the A-Z page composables + paging engine.** Insert a new section after the `// ---- Search results ----` block (after `ResultRow`, ~line 580):
  ```kotlin
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
          )
      }
  }

  @Composable
  private fun AlbumsPage(
      library: MaLibrary,
      thumbs: MaThumbs,
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
  ```

- [ ] **Step 9: Add the detail-page composables.** Insert a new section after the A-Z pages (before the `// ---- Queue overlay ----` block, ~line 582):
  ```kotlin
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
                          MediaCell(album, thumbs, onPlay, onStartRadio)
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
  ```

- [ ] **Step 10: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (compile of the tab row, A-Z pages, paging, detail pages, back chip; suite still 1007) and `NODE RC=0`. If gradle reports a `Conflicting import` on `items`, confirm the grid variant was imported as `gridItems` (Step 2) and that the grid call sites use `gridItems(...)`.

- [ ] **Step 11: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt
  git commit -m "feat(media): MusicBrowser A-Z tabs + drill-in pages (browse + play)

Replaces the implicit shelves/search two-state with a BrowserPage back-stack: a
Home·Artists·Albums tab row (accent-lit active tab), A-Z Artists/Albums pages with
end-of-list paging (generic PagedLibraryColumn, page 200), an ArtistDetail album grid
(reusing MediaCell) and an AlbumDetail tracklist, and a back chip. Artist row tap
drills in; album/track taps play. Search keeps its ≥2-char override; the queue overlay
is untouched. AlbumDetail renders here but is reached via 'View album' in the next task.
UI/wiring (compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 4 — Long-press drill-in: "View album" / "View artist" menu entries + threading

Add the two optional drill-in entries to `EnqueueMenu`, thread them (with the concrete typed object) through `MediaCell`, `ResultRow`, `LibraryRow`, and their parent panes, and wire the push lambdas so they appear wherever a `MaAlbum`/`MaArtist` object exists: search results, the A-Z Artists/Albums rows, and the ArtistDetail album grid. This lights up reaching `AlbumDetail` and adds the "View artist" affordance in search + A-Z artists. **UI task: no unit test** — the navigation is covered by Task 2; composables are verified only by the `:app:assembleDebug` compile.

**Design decision (flagged for review):** the "View" entries appear only where the concrete typed object (`MaAlbum`/`MaArtist`, carrying id + provider) is in scope. Search-result rows hold `MaLibraryItem`, so `ResultRow`/`MediaCell` compute the entry via `item as? MaAlbum` / `item as? MaArtist` (null for tracks/playlists/radios → entry hidden). The A-Z pages and the ArtistDetail grid already hold the typed object, so they pass a direct callback. `openArtist`/`openAlbum` clear the active search query before pushing so the pushed detail page isn't hidden by the search override.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — `openAlbum` push lambda; `EnqueueMenu` gains `onViewAlbum`/`onViewArtist`; `MediaCell`, `ResultRow`, `LibraryRow` gain typed View params + compute the entries; `ShelvesPane`/`Shelf` stay unchanged (their `MediaCell` calls use the new defaults); `ResultsPane`/`resultGroup` thread the two callbacks; `ArtistsPage`/`AlbumsPage`/`ArtistDetailPage` pass the View callbacks; the `ResultsPane(...)` call and the `Artists`/`Albums`/`ArtistDetail` page calls in the `Box` gain the new args.

**Interfaces:**
- Consumes: `openArtist` (Task 3); `pushPage`/`BrowserPage.AlbumDetail` (Task 2); `MaAlbum`/`MaArtist` (existing/Task 3 imports).
- Produces (internal): `openAlbum: (MaAlbum) -> Unit` (clears the query, pushes `AlbumDetail`); `EnqueueMenu(..., onViewAlbum: (() -> Unit)? = null, onViewArtist: (() -> Unit)? = null)`; `MediaCell(..., onViewAlbum: (MaAlbum) -> Unit = {}, onViewArtist: (MaArtist) -> Unit = {})`; `ResultRow(..., onViewAlbum: (MaAlbum) -> Unit, onViewArtist: (MaArtist) -> Unit)`; `LibraryRow(..., onViewAlbum: (() -> Unit)? = null, onViewArtist: (() -> Unit)? = null)`; `ResultsPane`/`resultGroup` gain the two typed callbacks; `ArtistsPage(..., onViewArtist)`, `AlbumsPage(..., onOpenAlbum)`, `ArtistDetailPage(..., onOpenAlbum)`.

### Steps

- [ ] **Step 1: Add the `openAlbum` push lambda.** In `MusicBrowser`, immediately after the `openArtist` lambda (added in Task 3):
  ```kotlin
      // Drill into an album's tracklist (from "View album"). Clears any active search first, same
      // as openArtist, so the pushed detail page isn't hidden by the search override.
      val openAlbum: (MaAlbum) -> Unit = { album ->
          query = ""
          pageStack = pushPage(pageStack, BrowserPage.AlbumDetail(album))
      }
  ```

- [ ] **Step 2: Extend `EnqueueMenu`.** Replace the `EnqueueMenu` composable (added-to in the favorite/radio batch, ~lines 787-803) with the version that adds the two optional View entries below "Start radio":
  ```kotlin
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
  ```

- [ ] **Step 3: Extend `MediaCell`.** Replace the `MediaCell` composable (~lines 450-484) so it accepts typed View callbacks and offers the entry when the item is the matching concrete type:
  ```kotlin
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
  ```
  (Shelf cells are tracks/playlists/radios, so `item as? MaAlbum`/`MaArtist` is null there and no View entry shows — the `ShelvesPane`/`Shelf` calls keep using `MediaCell`'s new defaults, no change needed. The ArtistDetail grid passes a real `onViewAlbum` in Step 7.)

- [ ] **Step 4: Extend `ResultsPane` + `resultGroup` + `ResultRow`.** Three edits in the `// ---- Search results ----` section.

  1. `ResultsPane` (~lines 488-506) — add the two callbacks and forward to each `resultGroup`:
  ```kotlin
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
  ```

  2. `resultGroup` (~lines 508-527) — add the two callbacks and forward to `ResultRow`:
  ```kotlin
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
  ```

  3. `ResultRow` (~lines 529-580) — add the two callbacks and compute the typed View entries:
  ```kotlin
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
  ```

- [ ] **Step 4b: Update the `ResultsPane(...)` call in the `Box`.** In the `searching -> when (val c = content)` branch (Task 3, Step 6), add the two callbacks:
  ```kotlin
                  searching -> when (val c = content) {
                      is BrowserContent.Results ->
                          ResultsPane(c.results, thumbs, playItem, startRadio, onViewAlbum = openAlbum, onViewArtist = openArtist)
                      else -> EmptyHint("Loading…")
                  }
  ```

- [ ] **Step 5: Add the View entries to `LibraryRow` (A-Z rows).** Replace `LibraryRow` (added in Task 3, Step 8) so its menu carries optional View entries:
  ```kotlin
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
  ```

- [ ] **Step 6: Wire the A-Z pages' View entries.** Two edits.

  1. `ArtistsPage` — add an `onViewArtist` param and pass it (reusing the drill callback so the menu mirrors the row tap):
  ```kotlin
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
              onClick = { onOpenArtist(artist) },
              onPlayNext = { onPlay(artist, EnqueueMode.NEXT) },
              onAdd = { onPlay(artist, EnqueueMode.ADD) },
              onStartRadio = { onStartRadio(artist) },
              onViewArtist = { onOpenArtist(artist) },
          )
      }
  }
  ```

  2. `AlbumsPage` — add an `onOpenAlbum` param and pass it as `onViewAlbum`:
  ```kotlin
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
              onClick = { onPlay(album, EnqueueMode.PLAY) },
              onPlayNext = { onPlay(album, EnqueueMode.NEXT) },
              onAdd = { onPlay(album, EnqueueMode.ADD) },
              onStartRadio = { onStartRadio(album) },
              onViewAlbum = { onOpenAlbum(album) },
          )
      }
  }
  ```

- [ ] **Step 7: Wire the ArtistDetail grid's View entry.** Replace `ArtistDetailPage` (Task 3, Step 9) so it takes `onOpenAlbum` and passes it to each `MediaCell`:
  ```kotlin
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
  ```

- [ ] **Step 8: Update the page calls in the `Box`.** In the `else -> when (val page = pageStack.last())` branch (Task 3, Step 6), update the `Artists`, `Albums`, and `ArtistDetail` cases to pass the new callbacks:
  ```kotlin
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
  ```
  (`ArtistsPage` gained no positional change; `AlbumsPage` and `ArtistDetail` gain `onOpenAlbum`. The `AlbumDetail` case is unchanged.)

- [ ] **Step 9: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (compile of the extended menu + all threading; suite still 1007) and `NODE RC=0`.

- [ ] **Step 10: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt
  git commit -m "feat(media): 'View album'/'View artist' drill-in menu entries

EnqueueMenu gains two optional View entries, threaded (with the concrete typed
object) through MediaCell/ResultRow/LibraryRow and their panes: search results and
A-Z rows compute them via item as? MaAlbum/MaArtist; the A-Z pages and ArtistDetail
grid pass a direct callback. openAlbum/openArtist clear the active search before
pushing the detail page. This makes AlbumDetail reachable and adds View-artist in
search + A-Z artists. UI/wiring (compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Self-Review

**1. Spec coverage:**
- Four `MaCommandClient` methods (`getLibraryArtists`/`getLibraryAlbums` with `limit`/`offset`/`order_by: "sort_name"`, `getArtistAlbums`/`getAlbumTracks` with `item_id` + `provider_instance_id_or_domain`) reusing existing parsers — Task 1. ✓
- Four `MaLibrary` `withClient` wrappers (`libraryArtists`/`libraryAlbums` offset passthrough; `artistAlbums`/`albumTracks` unpack id+provider) — Task 1. ✓
- MaLibraryTest pins (library_items sort_name + limit 200; offset passthrough = 200; artist_albums/album_tracks item_id+provider) — Task 1. ✓ No new parse tests (existing pins stand). ✓
- `BrowserPage` sealed type (Home/Artists/Albums/ArtistDetail/AlbumDetail) + `pushPage` (dedup current top) + `popPage` (never removes Home) + `tabTarget` (nearest root) — Task 2; placed in `ui/model` for JVM testability (flagged). ✓
- BrowserNavModel tests: push dedup, pop-never-removes-Home, tab targeting for [Home] / [Home,Artists] / [Home,Artists,ArtistDetail] / [Home,Albums,AlbumDetail] (+ nested album-under-artist) — Task 2. ✓
- Tab row `Home · Artists · Albums`, "Clear"-chip styling, `#4FC3F7` active label, tap resets stack to [Home,<tab>] / [Home] — Task 3. ✓
- A-Z pages: 44dp thumb rows; artist row tap drills, album row tap plays; end-of-list paging (full-page 200 → next offset); trailing Loading row; failure → showError, keep loaded; per-visit state (refetch on return) — Task 3. ✓
- ArtistDetail: back chip + name (22sp) + adaptive 128dp `MediaCell` grid (cell tap plays) — Task 3 (View-album on cells — Task 4). ✓
- AlbumDetail: back chip + name + artist subtitle + tracklist (number/title/duration; row tap plays track; long-press Play next/Add/Start radio) — Task 3. ✓
- Back chip: 32dp circle, `Icons.AutoMirrored.Outlined.ArrowBack`, pops the stack — Task 3. ✓
- Search override preserved (≥2 chars → ResultsPane; clearing returns to stack top); queue overlay + openQueueSignal unchanged — Task 3. ✓
- `EnqueueMenu` gains `onViewAlbum`/`onViewArtist` below Start radio; offered on album/artist items in search results, A-Z Albums (View album) + A-Z Artists (View artist) + ArtistDetail grid (View album); absent where the concrete type is missing — Task 4. ✓
- Both View entries push the matching detail page; the typed object flows via `item as? MaAlbum/MaArtist` (search/results) or a direct callback (A-Z pages, grid) — Task 4. ✓
- Degradation: MA down → not-Connected branch shows the notice (page fetches would toast); empty page → EmptyHint; drill fail → toast + empty + back chip; search active → Results override, stack preserved; queue overlay/up-next unchanged — Tasks 1/3/4. ✓
- Out-of-scope items (Tracks A-Z, track→album drill, favorites/genre/provider filters, letter-index, global page cache) — none added. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete code; every command has expected output. `AlbumDetailPage` unreachable-in-Task-3 is real complete code (not a placeholder), reached in Task 4 — noted. ✓

**3. Type consistency:** `getLibraryArtists(limit, offset): Result<List<MaArtist>>` / `getLibraryAlbums(...): Result<List<MaAlbum>>` / `getArtistAlbums(artistId, provider): Result<List<MaAlbum>>` / `getAlbumTracks(albumId, provider): Result<List<MaTrack>>` (Task 1) match the `MaLibrary` wrappers' calls (Task 1) and the pages' `library.libraryArtists`/`libraryAlbums`/`artistAlbums`/`albumTracks` (Tasks 3/4). `BrowserPage`/`pushPage`/`popPage`/`tabTarget` (Task 2) used identically in MusicBrowser (Tasks 3/4). `openArtist: (MaArtist) -> Unit` (Task 3) reused as `onOpenArtist`/`onViewArtist` (Tasks 3/4); `openAlbum: (MaAlbum) -> Unit` (Task 4) as `onOpenAlbum`/`onViewAlbum`. `EnqueueMenu(..., onViewAlbum, onViewArtist)` (Task 4) matched by every call site (MediaCell/ResultRow/LibraryRow). `PagedLibraryColumn<T>(fetch, key, emptyText, onError, row)` (Task 3) matched by ArtistsPage/AlbumsPage. `formatTrackTime(ms: Long)` fed `(track.duration ?: 0L) * 1000` (seconds→ms). Grid `gridItems` alias vs list `items` disambiguated. ✓

---

## Live-verify checklist (implementation end — not a task; run on-device)

Reproduced verbatim from the design spec:

1. Artists tab → A-Z list loads; scroll to bottom of a >200-artist library → next page appends.
2. Tap an artist → album grid; tap an album cell → plays it; long-press → "View album" → tracklist; tap a track → plays that track.
3. Albums tab → A-Z with artist subtitles; long-press → View album works.
4. Search overrides any page; clearing returns to the same page; "View artist" from a search result artist works.
5. Back chip walks the stack to Home; tabs highlight correctly on detail pages.
6. Queue overlay + up-next jump still work from every page.
