# Favorite Current Song + Radio Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a favorite (heart) control for the currently-playing song — on the now-playing takeover beside shuffle/repeat AND in the MusicBrowser queue-pane header — plus a "Start radio" entry in the library long-press menu that plays an item with Music Assistant's dynamic-radio queue refill. Source of truth: `docs/superpowers/specs/2026-07-19-favorite-radio-mode-design.md`; API facts: `.superpowers/sdd/ma-api-recon.md`.

**Architecture:** Data layer first — `MaQueueItem` gains three media-item-derived fields (`favorite`/`mediaItemId`/`mediaType`) parsed from the queue item's nested `media_item`; `MaCommandClient` gains a `radioMode` arg on `playMedia` and two favorite commands. `MaLibrary` wraps them (`favoriteCurrentSong`/`unfavorite`/`playRadio`) following its `withClient`/`withQueue` idiom. Two pure fns in `ui/model/QueueModel.kt` (`currentItemOf`, `favoriteToggleAction`) are the tested brains: one finds the current queue item, the other decides add-vs-remove. The takeover heart's state rides the existing DashboardShell up-next poll (extended to also derive the current item), gated by `sendspin && maConnected`; a `favVersion` counter re-runs the poll immediately after a tap. One App-level `onFavoriteToggle: (MaQueueItem?) -> Unit` callback runs the pure decision and calls the right `MaLibrary` op on `deps.mainScope`; it is shared by the takeover (via HomeView → NowPlayingHome) and the queue pane (via MediaPanel → MusicBrowser). Radio mode threads a parallel `startRadio` callback (kept separate from `EnqueueMode` because radio_mode is orthogonal to MA's QueueOption).

**Tech Stack:** Kotlin + Jetpack Compose (native Android kiosk). Pure model logic in `com.rar.echodash.ui.model` (plain-JVM JUnit4). MA API JSON over the vendored `MaCommandClient` (kotlinx.serialization.json). Gradle (`:app`), JDK 21 (Amazon Corretto).

## Global Constraints

- **Gate before EVERY commit** — run both, each must show RC=0 before committing:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; RC=$?; echo "GATE RC=$RC"
  node --check app/src/main/assets/config/app.js; echo "NODE RC=$?"
  ```
  Check gradle's OWN exit code via `RC=$?` captured immediately after the gradle invocation. NEVER pipe gradle to `tail`/`head` (or any filter) — that masks gradle's exit code behind the filter's. Redirect all gradle output to the scratchpad log and inspect the log if RC is non-zero. `node --check` guards the config bundle; no task in this plan edits `app.js`, so it always passes here, but it remains part of the gate.
- **Commit trailer** — every commit message ends with exactly this trailer line:
  `Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL`
- **NO new dependencies.** `material-icons-extended` (already a dependency) supplies `Icons.Filled.Favorite` and `Icons.Outlined.FavoriteBorder`. Plain-JVM JUnit4 tests only, mirroring existing idioms (`org.junit.Test`, `org.junit.Assert.*`).
- **Compose UI code is NOT unit-tested in this repo.** Testable logic lives in pure model functions (`ui/model/*.kt`) and parser/wrapper methods; composables are verified only by the `:app:assembleDebug` compile inside the gate. UI-only tasks below explicitly have no unit test — do not invent fake Compose tests.
- **Do NOT push.** Commits stay local.
- **MA server is 2.9.9.** Use `radio_mode: true` on `player_queues/play_media` (native, non-deprecated on 2.9.x). Add a one-line code comment noting 2.10+ deprecates-but-translates it. Do NOT build against the `radio_playlist://` URI scheme (2.10-only, absent on 2.9.9).
- **The lit accent is `#4FC3F7`** (`Color(0xFF4FC3F7)`), matching the existing `NpToggleButton`/`QueueToggleChip`. Heart glyphs: `Icons.Outlined.FavoriteBorder` (unlit) / `Icons.Filled.Favorite` (lit).
- **Current suite is 977 tests.** Task 1 adds 2 (→979). Task 2 adds 12 (→991). Tasks 3 and 4 add none (→991). Each task states its expected count.
- Scratchpad for logs: `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/` (create with `mkdir -p` if absent).

## File Structure

- `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaQueueItem.kt` — 3 new defaulted fields (`favorite`, `mediaItemId`, `mediaType`).
- `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt` — `playMedia` gains a `radioMode` arg; new `addCurrentToFavorites` / `removeFavorite`; `parseQueueState` reads the 3 fields from `media_item`.
- `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt` — new `favoriteCurrentSong` / `unfavorite` / `playRadio` wrappers.
- `app/src/main/java/com/rar/echodash/ui/model/QueueModel.kt` — new pure `currentItemOf` fn + `FavoriteAction` sealed type + `favoriteToggleAction` fn.
- `app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt` — takeover heart chip (first in the toggle row) + 3 new params.
- `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — poll derives `favState` + `favVersion`; `showFavorite`/`favorite`/`onToggleFavorite` threaded to HomeView; `onFavoriteToggle` App-callback param forwarded to HomeView (Task 3) and MediaPanel (Task 4).
- `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — 3 new defaulted params forwarded to `NowPlayingHome`.
- `app/src/main/java/com/rar/echodash/App.kt` — `onFavoriteToggle` callback wiring (decision fn + `MaLibrary` op on `deps.mainScope`).
- `app/src/main/java/com/rar/echodash/ui/panels/MediaPanel.kt` — `onFavoriteToggle` pass-through to `MusicBrowser`.
- `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — queue-pane heart chip, `startRadio` plumbing, "Start radio" menu item.
- Tests: `.../musicassistant/MaCommandClientSearchQueueTest.kt` (Task 1), `.../ui/model/QueueModelTest.kt` + `.../sendspin/MaLibraryTest.kt` (Task 2).

**Decomposition note (flagged for review):** the design guidance grouped "parser/arg-building tests" and the toggle-decision fn under specific tasks; this plan makes two boundary choices and flags them in the return summary — (a) the `playMedia`/favorites **arg-building** pins live in Task 2 (through the existing `MaLibraryTest` `FakeTransport` seam that drives the real `MaCommandClient`), because this repo never unit-tests command-sending at the `MaCommandClient` layer (see `playMedia`/`clearQueue`/`playQueueItem` — all pinned via `MaLibrary`); Task 1 owns the `parseQueueState` parsing pins. (b) The `favoriteToggleAction` pure fn + its tests live in Task 2 (co-located with `currentItemOf` in `QueueModel.kt`), leaving Task 3 as pure UI wiring.

---

## Task 1 — Data layer: MaQueueItem fields + parseQueueState + playMedia radioMode + favorite commands

Add the three favorite/identity fields to `MaQueueItem`, parse them in `parseQueueState`, add the `radioMode` arg to `playMedia`, and add the two new `MaCommandClient` favorite commands. Tested deliverable: the parsing pins (the new command-sending code is exercised in Task 2 via the `MaLibrary` seam, matching this repo's layering).

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaQueueItem.kt` — 3 new fields.
- Modify: `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt` — `playMedia` radioMode arg (~lines 156-180); `parseQueueState` item loop (~lines 728-762); two new methods after `playMedia`.
- Test: `app/src/test/java/com/rar/echodash/sendspin/musicassistant/MaCommandClientSearchQueueTest.kt` — 2 new `@Test` methods (append before the `// Helpers` section, i.e. before `private fun parseJson`).

**Interfaces:**
- Consumes: nothing new. `optString`/`optBoolean` from `MaJsonExtensions.kt` (`optString` coerces a numeric `item_id` to its string content via `contentOrNull`).
- Produces:
  - `MaQueueItem(..., favorite: Boolean = false, mediaItemId: String? = null, mediaType: String? = null)` — new trailing defaulted fields (every existing construction site keeps compiling).
  - `MaCommandClient.playMedia(uri, queueId, mediaType?, enqueueMode = PLAY, radioMode: Boolean = false): Result<Unit>` — adds `"radio_mode" to true` only when `radioMode`.
  - `MaCommandClient.addCurrentToFavorites(playerId: String): Result<Unit>` → `players/add_currently_playing_to_favorites` `{player_id}`.
  - `MaCommandClient.removeFavorite(mediaType: String, libraryItemId: String): Result<Unit>` → `music/favorites/remove_item` `{media_type, library_item_id}`.

### Steps

- [ ] **Step 1: Write the failing tests.** Append these 2 methods to `app/src/test/java/com/rar/echodash/sendspin/musicassistant/MaCommandClientSearchQueueTest.kt`, inside the class, immediately before the `// ====== Helpers ======` block's `private fun parseJson`. The file already imports `org.junit.Assert.*` (so `assertTrue`/`assertFalse`/`assertNull`/`assertEquals` are all available).

  ```kotlin
      @Test
      fun `parseQueueState reads favorite fields from media_item`() {
          val queueJson = parseJson("""{ "result": { "current_index": 0 } }""")
          val itemsJson = parseJson("""
          {
              "result": [
                  {
                      "queue_item_id": "qi_1",
                      "media_item": {
                          "name": "Fav Track",
                          "favorite": true,
                          "item_id": 123,
                          "media_type": "track"
                      }
                  }
              ]
          }
          """)
          val state = client.parseQueueState(queueJson, itemsJson)
          assertEquals(1, state.items.size)
          assertTrue(state.items[0].favorite)
          assertEquals("123", state.items[0].mediaItemId) // numeric item_id coerced to string
          assertEquals("track", state.items[0].mediaType)
      }

      @Test
      fun `parseQueueState defaults favorite fields when media_item absent`() {
          val queueJson = parseJson("""{ "result": {} }""")
          val itemsJson = parseJson("""{ "result": [ {"queue_item_id": "qi_1", "name": "Bare"} ] }""")
          val state = client.parseQueueState(queueJson, itemsJson)
          assertEquals(1, state.items.size)
          assertFalse(state.items[0].favorite)
          assertNull(state.items[0].mediaItemId)
          assertNull(state.items[0].mediaType)
      }
  ```

- [ ] **Step 2: Run tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.sendspin.musicassistant.MaCommandClientSearchQueueTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compile failure — `unresolved reference: favorite` / `mediaItemId` / `mediaType` on `MaQueueItem`. RC != 0.

- [ ] **Step 3: Add the 3 fields to `MaQueueItem`.** Replace the whole data-class body in `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaQueueItem.kt` (note the added comma after `isCurrentItem: Boolean`):

  ```kotlin
  data class MaQueueItem(
      val queueItemId: String,
      val name: String,
      val artist: String?,
      val album: String?,
      val imageUri: String?,
      val duration: Long?,       // seconds
      val uri: String?,          // media URI (e.g., "library://track/123")
      val isCurrentItem: Boolean, // is this the currently playing track
      val favorite: Boolean = false,      // media_item.favorite; false when media_item absent
      val mediaItemId: String? = null,    // media_item.item_id (string-or-int on wire -> string)
      val mediaType: String? = null,      // media_item.media_type ("track"|"radio"|...)
  )
  ```

- [ ] **Step 4: Parse the fields in `parseQueueState`.** In `MaCommandClient.kt`, replace the item-loop tail — from the `val uri = item.optString("uri")` block through the `items.add(MaQueueItem(...))` block (currently ~lines 746-761) — with this (adds the 3 field reads and the 3 constructor args, plus commas):

  ```kotlin
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
  ```

- [ ] **Step 5: Add the `radioMode` arg to `playMedia`.** In `MaCommandClient.kt`, replace the `playMedia` function (currently ~lines 156-180) with:

  ```kotlin
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
  ```

- [ ] **Step 6: Add the two favorite commands.** In `MaCommandClient.kt`, insert these two methods immediately after `playMedia`'s closing brace (before the `// ===== Queue Management Commands =====` banner):

  ```kotlin
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
  ```

- [ ] **Step 7: Run tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.sendspin.musicassistant.MaCommandClientSearchQueueTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, existing + 2 new tests green.

- [ ] **Step 8: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (suite now 979) and `NODE RC=0`.

- [ ] **Step 9: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaQueueItem.kt \
          app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt \
          app/src/test/java/com/rar/echodash/sendspin/musicassistant/MaCommandClientSearchQueueTest.kt
  git commit -m "feat(media): MaQueueItem favorite fields + playMedia radioMode + favorite commands

parseQueueState reads favorite/mediaItemId/mediaType from the nested media_item
(defaults when absent); playMedia gains a radioMode arg (radio_mode:true, native
on MA 2.9.x); addCurrentToFavorites/removeFavorite added. Parsing pinned (2 tests);
the command-sending is exercised via MaLibrary in the next task, per repo layering.

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 2 — MaLibrary wrappers + pure fns (currentItemOf, favoriteToggleAction)

Wrap the three MA ops in `MaLibrary` (matching its `withClient`/`withQueue` idiom) and add the two pure fns that back the UI. Tested deliverable: `currentItemOf` + `favoriteToggleAction` pins (QueueModelTest) and the wrapper arg-building pins (MaLibraryTest, through the existing `FakeTransport` seam that drives the real `MaCommandClient`).

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt` — 3 new suspend wrappers after `clearQueue()` (~line 151).
- Modify: `app/src/main/java/com/rar/echodash/ui/model/QueueModel.kt` — `currentItemOf` fn + `FavoriteAction` sealed type + `favoriteToggleAction` fn (append at end).
- Test: `app/src/test/java/com/rar/echodash/ui/model/QueueModelTest.kt` — extend the `item()` helper + 8 new `@Test` methods.
- Test: `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt` — 4 new `@Test` methods.

**Interfaces:**
- Consumes: `MaCommandClient.addCurrentToFavorites`/`removeFavorite`/`playMedia(radioMode=…)` (Task 1); `MaQueueItem.favorite`/`mediaItemId`/`mediaType` (Task 1).
- Produces:
  - `MaLibrary.favoriteCurrentSong(): Result<Unit>` — `withClient { addCurrentToFavorites(playerId) }`.
  - `MaLibrary.unfavorite(mediaType: String, libraryItemId: String): Result<Unit>` — `withClient { removeFavorite(...) }`.
  - `MaLibrary.playRadio(uri: String, mediaType: String?): Result<Unit>` — `withQueue { playMedia(uri, queueId, mediaType, PLAY, radioMode = true) }`.
  - `fun currentItemOf(q: MaQueueState): MaQueueItem?` (in `ui/model`).
  - `sealed interface FavoriteAction { data object Add; data class Remove(val mediaType: String, val libraryItemId: String) }` (in `ui/model`).
  - `fun favoriteToggleAction(item: MaQueueItem?): FavoriteAction` (in `ui/model`).

### Steps

- [ ] **Step 1: Write the failing QueueModel tests.** In `app/src/test/java/com/rar/echodash/ui/model/QueueModelTest.kt`, first REPLACE the existing `item(...)` helper (lines ~12-16) so it can set the new fields:

  ```kotlin
      /** A queue item with just the fields these helpers read; the rest are inert placeholders. */
      private fun item(
          name: String,
          current: Boolean,
          artist: String? = null,
          favorite: Boolean = false,
          mediaItemId: String? = null,
          mediaType: String? = null,
      ): MaQueueItem =
          MaQueueItem(
              queueItemId = name, name = name, artist = artist, album = null,
              imageUri = null, duration = null, uri = null, isCurrentItem = current,
              favorite = favorite, mediaItemId = mediaItemId, mediaType = mediaType,
          )
  ```

  Then append these 8 methods inside the class, before its final closing brace:

  ```kotlin
      // ---- currentItemOf ----

      @Test
      fun currentItemOfReturnsFlaggedItem() {
          val q = queue(item("A", current = false), item("B", current = true), item("C", current = false))
          assertEquals("B", currentItemOf(q)?.name)
      }

      @Test
      fun currentItemOfNullWhenNoCurrentFlag() {
          val q = queue(item("A", current = false), item("B", current = false))
          assertNull(currentItemOf(q))
      }

      @Test
      fun currentItemOfNullWhenEmpty() {
          assertNull(currentItemOf(queue()))
      }

      // ---- favoriteToggleAction ----

      @Test
      fun favoriteToggleRemovesWhenFavoritedWithId() {
          val qi = item("A", current = true, favorite = true, mediaItemId = "123", mediaType = "track")
          assertEquals(FavoriteAction.Remove("track", "123"), favoriteToggleAction(qi))
      }

      @Test
      fun favoriteToggleRemoveDefaultsMediaTypeToTrack() {
          val qi = item("A", current = true, favorite = true, mediaItemId = "123", mediaType = null)
          assertEquals(FavoriteAction.Remove("track", "123"), favoriteToggleAction(qi))
      }

      @Test
      fun favoriteToggleAddsWhenFavoritedButNoId() {
          val qi = item("A", current = true, favorite = true, mediaItemId = null)
          assertEquals(FavoriteAction.Add, favoriteToggleAction(qi))
      }

      @Test
      fun favoriteToggleAddsWhenNotFavorited() {
          val qi = item("A", current = true, favorite = false, mediaItemId = "123", mediaType = "track")
          assertEquals(FavoriteAction.Add, favoriteToggleAction(qi))
      }

      @Test
      fun favoriteToggleAddsWhenItemNull() {
          assertEquals(FavoriteAction.Add, favoriteToggleAction(null))
      }
  ```

- [ ] **Step 2: Write the failing MaLibrary tests.** In `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt`, add `import org.junit.Assert.assertNull` next to the existing `import org.junit.Assert.assertEquals` / `assertTrue` (lines ~16-17). Then append these 4 methods inside the class, after the last `signIn` test (before the class's closing brace):

  ```kotlin
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
  ```

- [ ] **Step 3: Run the tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest \
    --tests 'com.rar.echodash.ui.model.QueueModelTest' --tests 'com.rar.echodash.sendspin.MaLibraryTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compile failure — `unresolved reference: currentItemOf` / `favoriteToggleAction` / `FavoriteAction` / `favoriteCurrentSong` / `unfavorite` / `playRadio`. RC != 0.

- [ ] **Step 4: Add the pure fns.** Append to the END of `app/src/main/java/com/rar/echodash/ui/model/QueueModel.kt` (the file already imports `MaQueueItem` and `MaQueueState`):

  ```kotlin
  /** The queue's current item (the one flagged [MaQueueItem.isCurrentItem]), or null. */
  fun currentItemOf(q: MaQueueState): MaQueueItem? = q.items.firstOrNull { it.isCurrentItem }

  /** Whether a heart tap should add a favorite or remove an existing one. */
  sealed interface FavoriteAction {
      /** Add the current item to favorites (the server resolves which item); idempotent. */
      data object Add : FavoriteAction
      /** Remove an already-favorited library item, targeted by type + library id. */
      data class Remove(val mediaType: String, val libraryItemId: String) : FavoriteAction
  }

  /**
   * Decide add-vs-remove for a heart tap on [item]. Remove only when the item is known-favorited
   * AND carries a library id to target (favorite == true && mediaItemId != null), defaulting a
   * missing media_type to "track". Every other case — unknown favorite, favorited-but-no-id, or a
   * null item — falls back to [FavoriteAction.Add], which the server resolves and is idempotent.
   */
  fun favoriteToggleAction(item: MaQueueItem?): FavoriteAction =
      if (item?.favorite == true && item.mediaItemId != null)
          FavoriteAction.Remove(item.mediaType ?: "track", item.mediaItemId)
      else
          FavoriteAction.Add
  ```

- [ ] **Step 5: Add the MaLibrary wrappers.** In `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt`, insert these after the `clearQueue()` wrapper (~line 151), before the `// ---- Connection loop ----` banner:

  ```kotlin
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
  ```

- [ ] **Step 6: Run the tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest \
    --tests 'com.rar.echodash.ui.model.QueueModelTest' --tests 'com.rar.echodash.sendspin.MaLibraryTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, existing + 12 new tests green.

- [ ] **Step 7: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (suite now 991) and `NODE RC=0`.

- [ ] **Step 8: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt \
          app/src/main/java/com/rar/echodash/ui/model/QueueModel.kt \
          app/src/test/java/com/rar/echodash/ui/model/QueueModelTest.kt \
          app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt
  git commit -m "feat(media): MaLibrary favorite/radio wrappers + currentItemOf/favoriteToggleAction

favoriteCurrentSong/unfavorite (withClient) + playRadio (withQueue, radio_mode:true)
wrap the new client ops; currentItemOf finds the flagged queue item and
favoriteToggleAction decides add-vs-remove (remove only when favorited AND id known).
Pure fns JVM-tested (8) + wrapper arg-building pinned via the FakeTransport seam (4).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 3 — Takeover heart (NowPlayingHome + DashboardShell poll + App callback + HomeView threading)

Add the heart chip as the FIRST chip in the takeover toggle row; derive its state from DashboardShell's existing up-next poll (extended to also compute the current item), gated by `sendspin && maConnected`; add a `favVersion` immediate-refetch key; and wire one App-level `onFavoriteToggle` callback that runs `favoriteToggleAction` and calls the right `MaLibrary` op. **UI/wiring task: no unit test** — the decision logic is covered by Task 2's `favoriteToggleAction` tests; composables + threading are verified only by the `:app:assembleDebug` compile.

**Design decision (flagged for review):** the heart reflects the last poll (no separate optimistic-flip state). `favVersion` re-runs the poll immediately after a tap, collapsing normal latency to ~one LAN round-trip; a lost tap-vs-refetch race self-corrects on the next 10 s tick. This matches the spec's degradation contract ("Nothing resolvable → lit state unchanged" and "self-corrects on next poll", same policy as up-next) and the live-verify allowance ("heart lights ≤10 s (or instantly via optimistic flip)"). A separate optimistic layer was rejected: it would light the heart even on a failed/unresolvable favorite, contradicting the "lit state unchanged on failure" row.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt` — 2 icon imports; 3 new params (~after `onToggleShuffle`, line 85); heart chip first in the toggle row (~lines 210-226).
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — `currentItemOf` import; `favState`/`favVersion` state (~after `upNext`, line 126); extend the poll (~lines 146-155); `onFavoriteToggle` param (~after `onMediaSetShuffle`, line 108); 3 new args on the `HomeView(...)` call (~after line 309).
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — 3 new defaulted params (~after `onMediaToggleShuffle`, line 208); forward them on the `NowPlayingHome(...)` call (~after line 265).
- Modify: `app/src/main/java/com/rar/echodash/App.kt` — 2 model imports; `onFavoriteToggle` callback on the `DashboardShell(...)` call (~after `onMediaSetShuffle`, line 939).

**Interfaces:**
- Consumes: `currentItemOf`, `favoriteToggleAction`, `FavoriteAction`, `MaLibrary.favoriteCurrentSong`/`unfavorite` (Task 2).
- Produces (`NowPlayingHome` new params): `favorite: Boolean? = null`, `showFavorite: Boolean = false`, `onToggleFavorite: () -> Unit = {}`.
- Produces (`HomeView` new params, forwarded down): `favorite: Boolean? = null`, `showFavorite: Boolean = false`, `onToggleFavorite: () -> Unit = {}`.
- Produces (`DashboardShell` new param, from App): `onFavoriteToggle: (MaQueueItem?) -> Unit = {}`. (Also forwarded to MediaPanel in Task 4.)

### Steps

- [ ] **Step 1: Read** the current `NowPlayingHome.kt` param list (lines ~72-88) and toggle-row block (lines ~210-226); the `DashboardShell.kt` `upNext` var (line 126), the up-next poll (lines ~146-155), the `onMediaSetShuffle` param (line 108), and the `HomeView(...)` call's `onMediaToggleShuffle`/`upNext` args (lines ~309-310); the `HomeView.kt` `onMediaToggleShuffle` param (line 208) and its `NowPlayingHome(...)` call (lines ~253-268); and the `App.kt` `DashboardShell(...)` block around `onMediaSetShuffle`/`library`/`onBrowse` (lines ~938-953). Confirm the anchors below still match.

- [ ] **Step 2: Implement `NowPlayingHome.kt`.** Three edits.

  1. Add the two heart-icon imports next to the other `androidx.compose.material.icons.*` imports (near lines 23-33):
  ```kotlin
  import androidx.compose.material.icons.filled.Favorite
  import androidx.compose.material.icons.outlined.FavoriteBorder
  ```

  2. Add the three params to `NowPlayingHome`, immediately after `onToggleShuffle: () -> Unit = {},` (line 85):
  ```kotlin
      onToggleShuffle: () -> Unit = {},
      favorite: Boolean? = null,
      showFavorite: Boolean = false,
      onToggleFavorite: () -> Unit = {},
  ```

  3. Replace the toggle-row block (currently lines ~210-226) with the version that adds `showFavorite` to the visibility gate and the heart chip FIRST:
  ```kotlin
                  val showShuffle = state.sendspin && state.shuffle != null && state.canShuffle
                  val showRepeat = state.sendspin && state.repeatMode != null && state.canRepeat
                  if (showFavorite || showShuffle || showRepeat) {
                      Row(
                          horizontalArrangement = Arrangement.spacedBy(16.dp),
                          verticalAlignment = Alignment.CenterVertically,
                      ) {
                          if (showFavorite) {
                              // Heart first: favorite the current track. Lit when it's already a
                              // library favorite; a null (unknown) favorite renders unlit.
                              val heartIcon = if (favorite == true) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder
                              NpToggleButton(heartIcon, on = favorite == true) { onToggleFavorite() }
                          }
                          if (showShuffle) {
                              NpToggleButton(Icons.Outlined.Shuffle, on = state.shuffle == true) { onToggleShuffle() }
                          }
                          if (showRepeat) {
                              val repeatIcon = if (state.repeatMode == "one") Icons.Outlined.RepeatOne else Icons.Outlined.Repeat
                              val repeatOn = state.repeatMode == "all" || state.repeatMode == "one"
                              NpToggleButton(repeatIcon, on = repeatOn) { onCycleRepeat() }
                          }
                      }
                  }
  ```
  (`Row`, `Arrangement`, `Alignment`, `Icons`, `NpToggleButton` are all already in this file. The lit tint `#4FC3F7` lives inside `NpToggleButton` already.)

- [ ] **Step 3: Implement `DashboardShell.kt`.** Four edits.

  1. Add the `currentItemOf` import next to the other `com.rar.echodash.ui.model.*` imports (alongside `upNextOf`, line 57):
  ```kotlin
  import com.rar.echodash.ui.model.currentItemOf
  ```

  2. Add the `onFavoriteToggle` param immediately after `onMediaSetShuffle: (Boolean) -> Unit = {},` (line 108):
  ```kotlin
      onMediaSetShuffle: (Boolean) -> Unit = {},
      onFavoriteToggle: (MaQueueItem?) -> Unit = {},
  ```

  3. Add the `favState` + `favVersion` state immediately after the `upNext` var (line 126):
  ```kotlin
      var upNext by remember { mutableStateOf<MaQueueItem?>(null) }
      // Current-track favorite state, from the same poll as upNext. favVersion bumps re-run the
      // poll immediately after a heart tap so the lit state catches up in ~one round-trip.
      var favState by remember { mutableStateOf<MaQueueItem?>(null) }
      var favVersion by remember { mutableIntStateOf(0) }
  ```

  4. Replace the up-next poll (currently lines ~146-155) so it fetches the queue once and derives BOTH lines, keyed additionally on `favVersion`:
  ```kotlin
      LaunchedEffect(takeoverVisible, nowPlaying.sendspin, nowPlaying.title, maConnected, favVersion) {
          if (!(takeoverVisible && nowPlaying.sendspin && library != null && maConnected)) {
              upNext = null
              favState = null
              return@LaunchedEffect
          }
          while (true) {
              val q = library.queue().getOrNull()
              upNext = q?.let { upNextOf(it) }
              favState = q?.let { currentItemOf(it) }
              delay(10_000)
          }
      }
  ```

  5. Add the three heart args to the `HomeView(...)` call, immediately after `onMediaToggleShuffle = onMediaToggleShuffle,` (line 309):
  ```kotlin
                          onMediaToggleShuffle = onMediaToggleShuffle,
                          // Heart shows on a SendSpin source with a live MA socket (companion sources
                          // can't be resolved by MA). favVersion bump = immediate refetch after a tap.
                          favorite = favState?.favorite,
                          showFavorite = nowPlaying.sendspin && maConnected,
                          onToggleFavorite = { onFavoriteToggle(favState); favVersion++ },
  ```

- [ ] **Step 4: Implement `HomeView.kt`.** Two edits.

  1. Add the three params immediately after `onMediaToggleShuffle: () -> Unit = {},` (line 208):
  ```kotlin
      onMediaToggleShuffle: () -> Unit = {},
      favorite: Boolean? = null,
      showFavorite: Boolean = false,
      onToggleFavorite: () -> Unit = {},
  ```

  2. Forward them on the `NowPlayingHome(...)` call, immediately after `onToggleShuffle = onMediaToggleShuffle,` (line 265):
  ```kotlin
                      onToggleShuffle = onMediaToggleShuffle,
                      favorite = favorite,
                      showFavorite = showFavorite,
                      onToggleFavorite = onToggleFavorite,
  ```

- [ ] **Step 5: Implement `App.kt`.** Two edits.

  1. Add the two model imports next to the other `com.rar.echodash.ui.model.*` imports (near lines 57-63):
  ```kotlin
  import com.rar.echodash.ui.model.FavoriteAction
  import com.rar.echodash.ui.model.favoriteToggleAction
  ```

  2. Add the `onFavoriteToggle` callback to the `DashboardShell(...)` call, immediately after `onMediaSetShuffle = { enabled -> deps.sendspin.transportSetShuffle(enabled) },` (line 939):
  ```kotlin
                          onMediaSetShuffle = { enabled -> deps.sendspin.transportSetShuffle(enabled) },
                          // Favorite/un-favorite the current song. Shared by the takeover heart and
                          // the queue-pane heart (each passes the item its own poll saw). The pure
                          // decision picks add vs remove; the op runs on the app scope (MA-socket I/O).
                          onFavoriteToggle = { favItem ->
                              deps.mainScope.launch {
                                  when (val action = favoriteToggleAction(favItem)) {
                                      FavoriteAction.Add -> deps.maLibrary.favoriteCurrentSong()
                                      is FavoriteAction.Remove ->
                                          deps.maLibrary.unfavorite(action.mediaType, action.libraryItemId)
                                  }
                              }
                          },
  ```
  (`favItem` is typed `MaQueueItem?` by inference from the `DashboardShell` param; `deps.mainScope.launch` mirrors the existing `onMediaVolume` else-branch; `deps.maLibrary` is the non-null `MaLibrary` built at App.kt:297.)

- [ ] **Step 6: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (compile of all four files + the takeover heart wiring) and the 991-test suite green.

- [ ] **Step 7: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt \
          app/src/main/java/com/rar/echodash/ui/DashboardShell.kt \
          app/src/main/java/com/rar/echodash/ui/HomeView.kt \
          app/src/main/java/com/rar/echodash/App.kt
  git commit -m "feat(media): takeover favorite heart (first chip in the toggle row)

The takeover toggle row gains a heart as its first chip, lit when the current
track is a library favorite (Icons.Filled.Favorite, #4FC3F7). State rides the
existing up-next poll (now also derives currentItemOf), gated on sendspin && MA
connected; a favVersion key re-runs the poll immediately after a tap. One App
onFavoriteToggle callback runs favoriteToggleAction + the MaLibrary op. UI/wiring
(compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 4 — Queue-pane heart chip + "Start radio" menu item (MediaPanel + MusicBrowser)

Add the heart mini-chip (first) to the queue-pane header, thread the shared `onFavoriteToggle` callback through MediaPanel → MusicBrowser → QueuePane, and add a "Start radio" long-press menu item (hidden for radio stations) backed by a parallel `startRadio` play-plumbing callback. **UI/wiring task: no unit test** — the decision + wrapper logic are covered by Task 2; composables + threading are verified only by the `:app:assembleDebug` compile.

**Design decision (flagged for review):** radio mode uses a **parallel** `startRadio: (MaLibraryItem) -> Unit` callback, NOT a new `EnqueueMode` value. `EnqueueMode` values map 1:1 to MA's `QueueOption` (`play`/`add`/`next`/`replace`); `radio_mode` is an orthogonal boolean on `play_media` (radio always PLAYs). Folding a `START_RADIO` into `EnqueueMode` would need a bogus `apiValue` or special-casing inside `playMedia`, muddying its option/radio axes — a parallel callback landing on `MaLibrary.playRadio` keeps them independent and mirrors the dedicated wrapper.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — forward `onFavoriteToggle` on the `MediaPanel(...)` call (~lines 329-336).
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/MediaPanel.kt` — new `onFavoriteToggle` param; pass to `MusicBrowser`.
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — imports; `onFavoriteToggle` param; `startRadio` plumbing; queue-pane heart chip; "Start radio" menu item threaded through the cell/row/pane chain.

**Interfaces:**
- Consumes: `onFavoriteToggle: (MaQueueItem?) -> Unit` (DashboardShell param, Task 3); `currentItemOf` (Task 2); `MaLibrary.playRadio` (Task 2); `Icons.Filled.Favorite`/`Icons.Outlined.FavoriteBorder`; `MaMediaType`.
- Produces (`MediaPanel` new param): `onFavoriteToggle: (MaQueueItem?) -> Unit = {}`.
- Produces (`MusicBrowser` new param): `onFavoriteToggle: (MaQueueItem?) -> Unit = {}`.
- Produces (internal, MusicBrowser): `QueuePane(..., onToggleFavorite: () -> Unit = {})`; `EnqueueMenu(..., onStartRadio: (() -> Unit)? = null)`; `onStartRadio: (MaLibraryItem) -> Unit` threaded through `ShelvesPane`/`Shelf`/`MediaCell`/`ResultsPane`/`resultGroup`/`ResultRow`.

### Steps

- [ ] **Step 1: Forward the callback into MediaPanel (`DashboardShell.kt`).** In the `DashView.MEDIA -> MediaPanel(...)` call (lines ~329-336), add `onFavoriteToggle = onFavoriteToggle,` after `onSetShuffle = onMediaSetShuffle,`:
  ```kotlin
                  DashView.MEDIA -> MediaPanel(
                      nowPlaying, art, onMediaPlay, onMediaPause, onMediaStop,
                      onMediaNext, onMediaPrev, onMediaVolume,
                      library = library, thumbs = thumbs,
                      openQueueSignal = openQueueSignal,
                      onSetRepeat = onMediaSetRepeat,
                      onSetShuffle = onMediaSetShuffle,
                      onFavoriteToggle = onFavoriteToggle,
                  )
  ```

- [ ] **Step 2: Thread through `MediaPanel.kt`.** Two edits.

  1. Add the param after `onSetShuffle: (Boolean) -> Unit = {},` (line 63). Add the import for `MaQueueItem` at the top (near the other `com.rar.echodash.sendspin.musicassistant` imports — the file currently imports `MaLibrary`/`MaLibraryState`; add):
  ```kotlin
  import com.rar.echodash.sendspin.musicassistant.MaQueueItem
  ```
  ```kotlin
      onSetShuffle: (Boolean) -> Unit = {},
      onFavoriteToggle: (MaQueueItem?) -> Unit = {},
  ```

  2. Pass it to `MusicBrowser` (the call at lines ~78-83):
  ```kotlin
              MusicBrowser(
                  library, thumbs, Modifier.weight(1f).fillMaxWidth(),
                  openQueueSignal = openQueueSignal,
                  onSetRepeat = onSetRepeat,
                  onSetShuffle = onSetShuffle,
                  onFavoriteToggle = onFavoriteToggle,
              )
  ```
  (The `ClassicMediaPanel` fallback path ignores the new param — correct, it has no queue pane.)

- [ ] **Step 3: Add imports + the `MusicBrowser` param (`MusicBrowser.kt`).** Two edits.

  1. Add three imports (the two heart icons next to the existing `androidx.compose.material.icons.outlined.*` imports at lines ~30-35, and `currentItemOf` next to `nextRepeatMode` at line 77):
  ```kotlin
  import androidx.compose.material.icons.filled.Favorite
  import androidx.compose.material.icons.outlined.FavoriteBorder
  ```
  ```kotlin
  import com.rar.echodash.ui.model.currentItemOf
  ```

  2. Add the `onFavoriteToggle` param to `MusicBrowser`, after `onSetShuffle: (Boolean) -> Unit = {},` (line 139):
  ```kotlin
      onSetShuffle: (Boolean) -> Unit = {},
      onFavoriteToggle: (MaQueueItem?) -> Unit = {},
  ```

- [ ] **Step 4: Add the `startRadio` plumbing (`MusicBrowser.kt`).** Immediately after the `playItem` lambda (ends ~line 240, `val playItem: (MaLibraryItem, EnqueueMode) -> Unit = { ... }`), add:
  ```kotlin
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
  ```

- [ ] **Step 5: Wire the queue-pane heart (`MusicBrowser.kt`).** Two edits.

  1. In the `QueuePane(...)` call (lines ~262-284), add `onToggleFavorite` after `onCycleRepeat = { ... }`:
  ```kotlin
                      onToggleShuffle = { queueState?.let { q -> onSetShuffle(!q.shuffleEnabled) }; queueVersion++ },
                      onCycleRepeat = { queueState?.let { q -> onSetRepeat(nextRepeatMode(q.repeatMode)) }; queueVersion++ },
                      // Favorite the queue's current item (the App callback decides add vs remove).
                      onToggleFavorite = {
                          queueState?.let { onFavoriteToggle(currentItemOf(it)) }
                          queueVersion++
                      },
  ```

  2. Add the `onToggleFavorite` param to `QueuePane` (signature at lines ~532-539) and render the heart chip FIRST inside the `if (queue != null)` header block (lines ~550-555):
  ```kotlin
  private fun QueuePane(
      queue: MaQueueState?,
      thumbs: MaThumbs,
      onJump: (String) -> Unit,
      onClear: () -> Unit,
      onToggleShuffle: () -> Unit,
      onCycleRepeat: () -> Unit,
      onToggleFavorite: () -> Unit = {},
  ) {
  ```
  ```kotlin
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
  ```

- [ ] **Step 6: Add the "Start radio" menu item (`MusicBrowser.kt`).** Replace `EnqueueMenu` (lines ~650-660) with the version that takes an optional radio action:
  ```kotlin
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
  ```

- [ ] **Step 7: Thread `onStartRadio` through the cell/row/pane chain (`MusicBrowser.kt`).** Six edits.

  1. `ShelvesPane` (lines ~364-383) — add the param and forward to each `Shelf`:
  ```kotlin
  private fun ShelvesPane(
      shelves: BrowserShelves,
      thumbs: MaThumbs,
      onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
      onStartRadio: (MaLibraryItem) -> Unit,
  ) {
  ```
  ```kotlin
          Shelf("Playlists", shelves.playlists, thumbs, onPlay, onStartRadio)
          Shelf("Radio", shelves.radios, thumbs, onPlay, onStartRadio)
          Shelf("Recently played", shelves.recent, thumbs, onPlay, onStartRadio)
  ```

  2. `Shelf` (lines ~385-401) — add the param and forward to `MediaCell`:
  ```kotlin
  private fun Shelf(
      title: String,
      items: List<MaLibraryItem>,
      thumbs: MaThumbs,
      onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
      onStartRadio: (MaLibraryItem) -> Unit,
  ) {
  ```
  ```kotlin
              items(items, key = { it.uri ?: it.id }) { item ->
                  MediaCell(item, thumbs, onPlay, onStartRadio)
              }
  ```

  3. `MediaCell` (lines ~404-435) — add the param and gate the radio item on media type:
  ```kotlin
  private fun MediaCell(
      item: MaLibraryItem,
      thumbs: MaThumbs,
      onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
      onStartRadio: (MaLibraryItem) -> Unit,
  ) {
  ```
  ```kotlin
          EnqueueMenu(
              expanded = menuOpen,
              onDismiss = { menuOpen = false },
              onPlayNext = { onPlay(item, EnqueueMode.NEXT) },
              onAdd = { onPlay(item, EnqueueMode.ADD) },
              onStartRadio = if (item.mediaType != MaMediaType.RADIO) ({ onStartRadio(item) }) else null,
          )
  ```

  4. `ResultsPane` (lines ~439-455) — add the param and forward to each `resultGroup`:
  ```kotlin
  private fun ResultsPane(
      results: SearchResults,
      thumbs: MaThumbs,
      onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
      onStartRadio: (MaLibraryItem) -> Unit,
  ) {
  ```
  ```kotlin
          resultGroup("Tracks", results.tracks, thumbs, onPlay, onStartRadio)
          resultGroup("Albums", results.albums, thumbs, onPlay, onStartRadio)
          resultGroup("Artists", results.artists, thumbs, onPlay, onStartRadio)
          resultGroup("Playlists", results.playlists, thumbs, onPlay, onStartRadio)
          resultGroup("Radio", results.radios, thumbs, onPlay, onStartRadio)
  ```

  5. `resultGroup` (lines ~458-476) — add the param and forward to `ResultRow`:
  ```kotlin
  private fun LazyListScope.resultGroup(
      title: String,
      items: List<MaLibraryItem>,
      thumbs: MaThumbs,
      onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
      onStartRadio: (MaLibraryItem) -> Unit,
  ) {
  ```
  ```kotlin
      items(items, key = { "$title-${it.uri ?: it.id}" }) { item ->
          ResultRow(item, thumbs, onPlay, onStartRadio)
      }
  ```

  6. `ResultRow` (lines ~479-527) — add the param and gate the radio item:
  ```kotlin
  private fun ResultRow(
      item: MaLibraryItem,
      thumbs: MaThumbs,
      onPlay: (MaLibraryItem, EnqueueMode) -> Unit,
      onStartRadio: (MaLibraryItem) -> Unit,
  ) {
  ```
  ```kotlin
          EnqueueMenu(
              expanded = menuOpen,
              onDismiss = { menuOpen = false },
              onPlayNext = { onPlay(item, EnqueueMode.NEXT) },
              onAdd = { onPlay(item, EnqueueMode.ADD) },
              onStartRadio = if (item.mediaType != MaMediaType.RADIO) ({ onStartRadio(item) }) else null,
          )
  ```

- [ ] **Step 8: Wire `startRadio` into the content branches (`MusicBrowser.kt`).** In the `else when (content)` block (lines ~285-293), pass `startRadio` to both panes:
  ```kotlin
                  is BrowserContent.Shelves -> ShelvesPane(content.shelves, thumbs, playItem, startRadio)
                  is BrowserContent.Results -> ResultsPane(content.results, thumbs, playItem, startRadio)
  ```

- [ ] **Step 9: Add the `MaMediaType` import (`MusicBrowser.kt`).** The file uses `item.mediaType` today only through the `MaLibraryItem` interface property (never naming the enum), so `MaMediaType` is NOT yet imported — but the new `!= MaMediaType.RADIO` comparison names it directly. Add the import next to the existing `com.rar.echodash.sendspin.musicassistant.model.MaLibraryItem` import (line 76):
  ```kotlin
  import com.rar.echodash.sendspin.musicassistant.model.MaMediaType
  ```
  (`MaQueueItem` is already imported at line 71, so the new `onFavoriteToggle: (MaQueueItem?)` param needs no additional import.)

- [ ] **Step 10: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (compile of the MediaPanel/MusicBrowser threading, queue-pane heart, and radio menu) and the 991-test suite green.

- [ ] **Step 11: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/DashboardShell.kt \
          app/src/main/java/com/rar/echodash/ui/panels/MediaPanel.kt \
          app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt
  git commit -m "feat(media): queue-pane favorite heart + Start radio menu item

Queue-pane header gains a heart mini-chip (first), lit off currentItemOf(queue),
sharing the App onFavoriteToggle callback (via MediaPanel). The long-press menu
gains 'Start radio' for track/artist/album/playlist items (hidden for radio
stations), backed by a parallel startRadio callback landing on MaLibrary.playRadio.
UI/wiring (compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Self-Review

**1. Spec coverage:**
- MaQueueItem 3 fields (`favorite`/`mediaItemId`/`mediaType`) from `media_item` — Task 1. ✓
- `playMedia` `radioMode` arg (`radio_mode:true`, 2.9.9 comment) — Task 1. ✓
- `addCurrentToFavorites` / `removeFavorite` MaCommandClient methods — Task 1. ✓
- parseQueueState pins (media_item present → 3 fields incl. numeric `item_id` → "123"; absent → defaults) — Task 1. ✓
- MaLibrary `favoriteCurrentSong` (withClient), `unfavorite` (withClient), `playRadio` (withQueue, radioMode=true) — Task 2. ✓
- arg-building pins (radio_mode present via playRadio / omitted via play; favorites command names + args) — Task 2 (via the FakeTransport seam). ✓
- `currentItemOf` pure fn (mid-list / no-current / empty) — Task 2. ✓
- Toggle-decision pure fn `favoriteToggleAction` (favorite && id → Remove; else Add — both branches, plus media_type default) — Task 2. ✓
- Takeover heart: first chip, `NpToggleButton` chrome, `FavoriteBorder`/`Favorite`, `#4FC3F7`, `favorite: Boolean?`/`showFavorite`/`onToggleFavorite` params, row gate `showFavorite || showShuffle || showRepeat` — Task 3. ✓
- Favorite state from the up-next poll + `favVersion` immediate-refetch key; `showFavorite = sendspin && maConnected` (companion hidden) — Task 3. ✓
- App callback with add-vs-remove decision (`favoriteToggleAction` + the right MaLibrary op on `deps.mainScope`) — Task 3. ✓
- Queue-pane heart mini-chip FIRST, lit from `currentItemOf(queueState)?.favorite`, tap → App callback + `queueVersion++`, rendered when the queue is loaded — Task 4. ✓
- "Start radio" menu item below Play next / Add to queue, hidden for radio stations, → `MaLibrary.playRadio(item.uri, item.mediaType)` — Task 4. ✓
- Degradation: companion → no heart (takeover gate false; queue pane is MA-only); MA down → heart hidden; nothing resolvable → Result.failure logged, lit state unchanged (no optimistic flip — see Task 3 note); no media_item → unlit, tap = add; lit but id null → add fallback (favoriteToggleAction); radio on a station → menu item not offered; 2.10+ → radio_mode still works (code comment) — covered across Tasks 1-4. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete code; every command has expected output. ✓

**3. Type consistency:** `MaQueueItem(..., favorite: Boolean, mediaItemId: String?, mediaType: String?)` used identically in Task 1 (parse) and Task 2 (test helper + favoriteToggleAction). `playMedia(uri, queueId, mediaType?, enqueueMode, radioMode: Boolean)` — Task 1 defines, Task 2's `playRadio` calls with `EnqueueMode.PLAY, radioMode = true`. `currentItemOf(q): MaQueueItem?` — Task 2 defines; Tasks 3 (DashboardShell) + 4 (MusicBrowser) call. `favoriteToggleAction(item: MaQueueItem?): FavoriteAction` with `FavoriteAction.Add` / `FavoriteAction.Remove(mediaType, libraryItemId)` — Task 2 defines; Task 3 App matches (`action.mediaType`/`action.libraryItemId`). `onFavoriteToggle: (MaQueueItem?) -> Unit` consistent App → DashboardShell → MediaPanel → MusicBrowser. `onToggleFavorite: () -> Unit` consistent HomeView/NowPlayingHome/QueuePane. `favorite: Boolean?` / `showFavorite: Boolean` consistent DashboardShell → HomeView → NowPlayingHome. Heart glyphs `Icons.Filled.Favorite` / `Icons.Outlined.FavoriteBorder` identical in Task 3 (takeover) and Task 4 (queue pane). ✓

---

## Live-verify checklist (implementation end — not a task; run on-device)

Reproduced verbatim from the design spec:

1. Play MA music → heart appears on takeover beside shuffle/repeat; lit state matches MA's favorites for the current track.
2. Tap unlit heart → MA favorites gains the track (check MA UI); heart lights ≤10s (or instantly via optimistic flip).
3. Tap lit heart → removed from MA favorites; heart unlights.
4. Queue pane heart mirrors takeover; tap works there too.
5. Long-press a track in search results → "Start radio" → queue refills with similar tracks (MA queue shows radio mode); same from an artist card.
6. Companion source: no heart, menu unchanged.
