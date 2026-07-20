# Multi-Room Speakers Pane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a **Speakers** overlay pane to the MusicBrowser (sibling of the Queue pane, opened by a "Speakers" chip next to the Queue chip) that lists every Music Assistant player with a per-player volume slider, join/leave-this-device grouping, and "Send my queue here" queue transfer.

**Architecture:** Data layer first — a new `MaPlayer` data class + `MaCommandClient.parsePlayers` parse the `players/all` wire shape (with its `display_name`/`group_childs`/`state` back-compat aliases); five new `MaCommandClient` suspend methods (`getPlayers`/`setPlayerVolume`/`groupPlayer`/`ungroupPlayer`/`transferQueue`) send the multi-room commands, each following the `CancellationException`-rethrow precedent already on the library-browse methods. `MaLibrary` wraps them (`players` with the self-missing→`includeProtocol` one-shot retry, `setPlayerVolume`, `groupWithMe`, `ungroupPlayer`, `transferMyQueueTo`) and exposes `selfPlayerId`. Three pure functions in `ui/model/SpeakersModel.kt` (`speakerRows`/`inGroupWithSelf`/`canOfferGroup`) are the tested brains. The `SpeakersPane` UI lives entirely inside `MusicBrowser.kt`, calling `library.*` directly and polling `library.players()` every 5 s while visible — so no `App`/`DashboardShell`/`MediaPanel` plumbing is touched.

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
- **NO new dependencies.** `material-icons-extended` (already a dependency) supplies `Icons.Outlined.SpeakerGroup` (confirmed present in 1.7.6). Plain-JVM JUnit4 tests only, mirroring existing idioms (`org.junit.Test`, `org.junit.Assert.*`).
- **Compose UI code is NOT unit-tested in this repo.** Testable logic lives in pure model functions (`ui/model/*.kt`) and parser/wrapper methods; composables are verified only by the `:app:assembleDebug` compile inside the gate. The UI task (Task 4) explicitly has no unit test — do not invent fake Compose tests.
- **All new `MaCommandClient` suspend methods rethrow `CancellationException` before the generic `catch`** — the precedent set at HEAD on `getLibraryArtists`/`getLibraryAlbums`/`getArtistAlbums`/`getAlbumTracks`. Swallowing cancellation as a failure would flash a spurious error toast on navigation-away.
- **Mutation callbacks bump versions / refetch ONLY on completion (`.onSuccess`), never synchronously.** (Volume is the deliberate exception: it does not bump — the 5 s poll reconciles it, so a drag doesn't fight an immediate refetch. Documented in Task 4.)
- **Queue and Speakers overlays are mutually exclusive** — opening one closes the other. The Speakers chip sits next to the Queue chip in the browser top bar. The pane polls `library.players()` every 5 s while visible; "Send queue" closes the pane on success.
- **The self-missing → `includeProtocol=true` one-shot retry lives in `MaLibrary.players()`.** `MaLibrary` exposes `selfPlayerId` (the UI marks/pins its own row).
- **The lit accent is `#4FC3F7`** (`Color(0xFF4FC3F7)`), matching the existing chips. Panel surface chips use `Color(0xFF2A2F3C)`.
- **Do NOT push.** Commits stay local.
- **Current suite is 1007 tests.** Task 1 adds 3 (→1010). Task 2 adds 7 (→1017). Task 3 adds 18 (→1035). Task 4 adds 0 (→1035). Each task states its expected count.
- Scratchpad for logs: `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/` (create with `mkdir -p` if absent).

## File Structure

- `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaPlayer.kt` — **new** data class (the player wire model the pane renders).
- `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt` — `parsePlayers` + a `parseStringArray` helper; five new suspend command methods.
- `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt` — `selfPlayerId` getter + five wrappers (`players` with retry, `setPlayerVolume`, `groupWithMe`, `ungroupPlayer`, `transferMyQueueTo`).
- `app/src/main/java/com/rar/echodash/ui/model/SpeakersModel.kt` — **new** pure fns `speakerRows` / `inGroupWithSelf` / `canOfferGroup`.
- `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — Speakers toggle button, `speakersVisible`/`speakers`/`speakersVersion` state + poll, mutual-exclusion wiring, `SpeakersPane`/`SpeakerRow`/`SpeakerActionChip`/`SpeakersToggleButton` composables.
- Tests: `app/src/test/java/com/rar/echodash/sendspin/musicassistant/MaCommandClientPlayersTest.kt` (**new**, Task 1); `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt` (Task 2); `app/src/test/java/com/rar/echodash/ui/model/SpeakersModelTest.kt` (**new**, Task 3).

**Decomposition note (flagged for review):** four tasks along the same layering the favorite/radio plan used — (1) parse + client commands (parse pinned in a dedicated new test file; command-sending pinned in Task 2 via the `MaLibrary` `FakeTransport` seam, because this repo never unit-tests command-sending at the `MaCommandClient` layer); (2) `MaLibrary` wrappers + their arg/retry pins; (3) `SpeakersModel` pure fns + pins; (4) the single-file `MusicBrowser` UI. Task 4 needs no `App`/`DashboardShell`/`MediaPanel` change because `SpeakersPane` reaches `MaLibrary` through the `library` param `MusicBrowser` already has.

---

## Task 1 — Data layer: MaPlayer + parsePlayers + five multi-room commands

Add the `MaPlayer` model, the `parsePlayers` parser (with `display_name`/`group_childs`/`state` alias fallbacks and nullable volume), and the five `players/*` + `player_queues/transfer` command methods. Tested deliverable: the `parsePlayers` pins (the new command-sending is exercised in Task 2 via the `MaLibrary` seam, matching this repo's layering).

**Files:**
- Create: `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaPlayer.kt`.
- Modify: `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt` — five command methods inserted after `getEffectiveQueueId` (after its closing brace at line 148, before the `// Playback Commands` banner at line 150); `parsePlayers` + `parseStringArray` inserted after `extractQueueItemImage` (after its closing brace at ~line 1044, before the `// Image URL Extraction` banner at ~line 1046).
- Test: `app/src/test/java/com/rar/echodash/sendspin/musicassistant/MaCommandClientPlayersTest.kt` (new).

**Interfaces:**
- Consumes: `optString`/`optInt`/`optBoolean`/`optJsonArray`/`optJsonObject` (already imported in `MaCommandClient` from `...transport.*`); `JsonArray`/`JsonObject`/`JsonPrimitive`/`contentOrNull`/`CancellationException`/`Log` (all already imported).
- Produces:
  - `data class MaPlayer(playerId, name, available, volumeLevel: Int?, muted, syncedTo: String?, groupMembers: List<String>, canGroupWith: List<String>, playbackState: String?, nowPlayingText: String?)`.
  - `MaCommandClient.getPlayers(includeProtocol: Boolean = false): Result<List<MaPlayer>>` — `players/all`; adds `"return_protocol_players" to true` only when `includeProtocol`.
  - `MaCommandClient.setPlayerVolume(playerId: String, volume: Int): Result<Unit>` → `players/cmd/volume_set` `{player_id, volume_level}`.
  - `MaCommandClient.groupPlayer(playerId: String, targetPlayer: String): Result<Unit>` → `players/cmd/group` `{player_id, target_player}`.
  - `MaCommandClient.ungroupPlayer(playerId: String): Result<Unit>` → `players/cmd/ungroup` `{player_id}`.
  - `MaCommandClient.transferQueue(sourceQueueId: String, targetQueueId: String): Result<Unit>` → `player_queues/transfer` `{source_queue_id, target_queue_id}` (auto_play deliberately omitted).
  - `MaCommandClient.parsePlayers(response: JsonObject): List<MaPlayer>` (internal, testable).

### Steps

- [ ] **Step 1: Write the failing parse tests.** Create `app/src/test/java/com/rar/echodash/sendspin/musicassistant/MaCommandClientPlayersTest.kt`:

  ```kotlin
  package com.rar.echodash.sendspin.musicassistant

  import kotlinx.serialization.json.Json
  import kotlinx.serialization.json.JsonObject
  import kotlinx.serialization.json.jsonObject
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test

  /** Tests for MaCommandClient.parsePlayers (players/all wire shape, incl. back-compat aliases). */
  class MaCommandClientPlayersTest {

      private lateinit var client: MaCommandClient

      @Before
      fun setUp() {
          client = MaCommandClient()
          client.setTransport(null, "ws://192.168.1.100:8095/ws", false)
      }

      @Test
      fun `parsePlayers reads full payload with back-compat aliases`() {
          // display_name (alias for name), group_childs (alias for group_members), state (alias
          // for playback_state) exercise the fallback branches; current_media.title -> nowPlayingText.
          val json = parseJson("""
          {
              "result": [
                  {
                      "player_id": "kitchen",
                      "display_name": "Kitchen",
                      "available": true,
                      "volume_level": 42,
                      "volume_muted": true,
                      "synced_to": "living",
                      "group_childs": ["living", "kitchen"],
                      "can_group_with": ["living", "study"],
                      "state": "playing",
                      "current_media": {"title": "Song X"}
                  }
              ]
          }
          """)
          val players = client.parsePlayers(json)
          assertEquals(1, players.size)
          val p = players[0]
          assertEquals("kitchen", p.playerId)
          assertEquals("Kitchen", p.name)            // from display_name alias
          assertTrue(p.available)
          assertEquals(42, p.volumeLevel)
          assertTrue(p.muted)                          // from volume_muted
          assertEquals("living", p.syncedTo)
          assertEquals(listOf("living", "kitchen"), p.groupMembers) // from group_childs alias
          assertEquals(listOf("living", "study"), p.canGroupWith)
          assertEquals("playing", p.playbackState)     // from state alias
          assertEquals("Song X", p.nowPlayingText)     // from current_media.title
      }

      @Test
      fun `parsePlayers defaults a minimal payload`() {
          val json = parseJson("""{ "result": [ {"player_id": "bare", "name": "Bare"} ] }""")
          val players = client.parsePlayers(json)
          assertEquals(1, players.size)
          val p = players[0]
          assertEquals("bare", p.playerId)
          assertEquals("Bare", p.name)
          assertFalse(p.available)                     // absent -> false
          assertNull(p.volumeLevel)                    // absent -> null
          assertFalse(p.muted)
          assertNull(p.syncedTo)
          assertTrue(p.groupMembers.isEmpty())
          assertTrue(p.canGroupWith.isEmpty())
          assertNull(p.playbackState)
          assertNull(p.nowPlayingText)
      }

      @Test
      fun `parsePlayers keeps unavailable players and skips id-less rows`() {
          val json = parseJson("""
          {
              "result": [
                  {"player_id": "off", "name": "Garage", "available": false, "volume_level": 0},
                  {"name": "No Id"}
              ]
          }
          """)
          val players = client.parsePlayers(json)
          assertEquals(1, players.size)                // the id-less row is skipped
          val p = players[0]
          assertEquals("off", p.playerId)
          assertFalse(p.available)                     // unavailable players are still returned
          assertEquals(0, p.volumeLevel)               // an explicit 0 is a real level, not null
      }

      private fun parseJson(text: String): JsonObject =
          Json.parseToJsonElement(text).jsonObject
  }
  ```

- [ ] **Step 2: Run the tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest \
    --tests 'com.rar.echodash.sendspin.musicassistant.MaCommandClientPlayersTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compile failure — `unresolved reference: MaPlayer` / `parsePlayers`. RC != 0.

- [ ] **Step 3: Create `MaPlayer.kt`.** Write `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaPlayer.kt`:

  ```kotlin
  package com.rar.echodash.sendspin.musicassistant

  /**
   * A Music Assistant player as rendered by the Speakers pane. Parsed from `players/all`
   * (see [MaCommandClient.parsePlayers]); nullable fields mirror the wire, where MA omits a
   * field for a player that has no such value (e.g. a player reporting no volume).
   */
  data class MaPlayer(
      val playerId: String,
      val name: String,
      val available: Boolean,
      val volumeLevel: Int?,          // null when the player reports none
      val muted: Boolean,
      val syncedTo: String?,          // sync-leader player_id when this row is a member
      val groupMembers: List<String>, // non-empty on a leader / group player
      val canGroupWith: List<String>, // player ids or provider-instance ids
      val playbackState: String?,     // "playing"|"paused"|"idle"|null
      val nowPlayingText: String?,    // current_media title — display only, nullable
  )
  ```

- [ ] **Step 4: Add the five command methods.** In `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt`, insert this block immediately after `getEffectiveQueueId`'s closing brace (line 148), before the `// ===== Playback Commands =====` banner (line 150):

  ```kotlin
      // ========================================================================
      // Player / Multi-room Commands
      // ========================================================================

      /**
       * List all players (players/all). Our own SendSpin protocol player is hidden by the
       * server default (return_protocol_players=false); [includeProtocol] flips it true so
       * MaLibrary can retry when the self row is missing.
       */
      suspend fun getPlayers(includeProtocol: Boolean = false): Result<List<MaPlayer>> {
          return try {
              val response = if (includeProtocol)
                  sendCommand("players/all", mapOf("return_protocol_players" to true))
              else
                  sendCommand("players/all")
              Result.success(parsePlayers(response))
          } catch (e: CancellationException) {
              throw e
          } catch (e: Exception) {
              Log.e(TAG, "Failed to fetch players (includeProtocol=$includeProtocol)", e)
              Result.failure(e)
          }
      }

      /** Set a single player's volume (0..100). */
      suspend fun setPlayerVolume(playerId: String, volume: Int): Result<Unit> {
          return try {
              sendCommand(
                  "players/cmd/volume_set",
                  mapOf("player_id" to playerId, "volume_level" to volume),
              )
              Result.success(Unit)
          } catch (e: CancellationException) {
              throw e
          } catch (e: Exception) {
              Log.e(TAG, "Failed to set volume on player: $playerId", e)
              Result.failure(e)
          }
      }

      /** Join [playerId] onto [targetPlayer]'s sync group. */
      suspend fun groupPlayer(playerId: String, targetPlayer: String): Result<Unit> {
          return try {
              sendCommand(
                  "players/cmd/group",
                  mapOf("player_id" to playerId, "target_player" to targetPlayer),
              )
              Result.success(Unit)
          } catch (e: CancellationException) {
              throw e
          } catch (e: Exception) {
              Log.e(TAG, "Failed to group $playerId onto $targetPlayer", e)
              Result.failure(e)
          }
      }

      /** Remove [playerId] from whatever group/sync it's in (server no-op if ungrouped). */
      suspend fun ungroupPlayer(playerId: String): Result<Unit> {
          return try {
              sendCommand("players/cmd/ungroup", mapOf("player_id" to playerId))
              Result.success(Unit)
          } catch (e: CancellationException) {
              throw e
          } catch (e: Exception) {
              Log.e(TAG, "Failed to ungroup player: $playerId", e)
              Result.failure(e)
          }
      }

      /**
       * Move a queue to another player. auto_play is deliberately omitted so the server keeps
       * the source queue's play state (recon §5). The server ungroups the target first if needed.
       */
      suspend fun transferQueue(sourceQueueId: String, targetQueueId: String): Result<Unit> {
          return try {
              sendCommand(
                  "player_queues/transfer",
                  mapOf("source_queue_id" to sourceQueueId, "target_queue_id" to targetQueueId),
              )
              Result.success(Unit)
          } catch (e: CancellationException) {
              throw e
          } catch (e: Exception) {
              Log.e(TAG, "Failed to transfer queue $sourceQueueId -> $targetQueueId", e)
              Result.failure(e)
          }
      }
  ```

- [ ] **Step 5: Add `parsePlayers` + `parseStringArray`.** In the same file, insert this block immediately after `extractQueueItemImage`'s closing brace (~line 1044), before the `// ===== Image URL Extraction =====` banner (~line 1046):

  ```kotlin
      // ========================================================================
      // JSON Parsing — Players
      // ========================================================================

      /**
       * Parse a players/all response. Reads name (or display_name alias), group_members (or
       * group_childs alias), playback_state (or state alias), and the current_media title.
       * volume_level is nullable — an absent/null level maps to null, but an explicit 0 is kept.
       * Unavailable players are returned (the pane dims them); rows with no player_id are skipped.
       */
      internal fun parsePlayers(response: JsonObject): List<MaPlayer> {
          val array = response.optJsonArray("result")
              ?: response.optJsonObject("result")?.optJsonArray("items")
              ?: return emptyList()
          val players = mutableListOf<MaPlayer>()
          for (i in 0 until array.size) {
              val item = (array[i] as? JsonObject) ?: continue
              val playerId = item.optString("player_id")
              if (playerId.isEmpty()) continue

              val name = item.optString("name")
                  .ifEmpty { item.optString("display_name") }
                  .ifEmpty { playerId }
              // optInt returns the sentinel for missing/null/non-numeric; >= 0 keeps a real 0.
              val volumeLevel = item.optInt("volume_level", -1).takeIf { it >= 0 }
              val groupMembers = parseStringArray(
                  item.optJsonArray("group_members") ?: item.optJsonArray("group_childs"),
              )
              val playbackState = item.optString("playback_state")
                  .ifEmpty { item.optString("state") }
                  .ifEmpty { null }
              val nowPlayingText = item.optJsonObject("current_media")
                  ?.optString("title")?.ifEmpty { null }

              players.add(MaPlayer(
                  playerId = playerId,
                  name = name,
                  available = item.optBoolean("available", false),
                  volumeLevel = volumeLevel,
                  muted = item.optBoolean("volume_muted", false),
                  syncedTo = item.optString("synced_to").ifEmpty { null },
                  groupMembers = groupMembers,
                  canGroupWith = parseStringArray(item.optJsonArray("can_group_with")),
                  playbackState = playbackState,
                  nowPlayingText = nowPlayingText,
              ))
          }
          return players
      }

      /** Collect the non-empty string elements of a JSON string array (skips non-primitives). */
      private fun parseStringArray(array: JsonArray?): List<String> {
          if (array == null) return emptyList()
          val out = mutableListOf<String>()
          for (i in 0 until array.size) {
              val s = (array[i] as? JsonPrimitive)?.contentOrNull
              if (!s.isNullOrEmpty()) out.add(s)
          }
          return out
      }
  ```

- [ ] **Step 6: Run the parse tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest \
    --tests 'com.rar.echodash.sendspin.musicassistant.MaCommandClientPlayersTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, the 3 new tests green.

- [ ] **Step 7: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (suite now 1010) and `NODE RC=0`.

- [ ] **Step 8: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaPlayer.kt \
          app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt \
          app/src/test/java/com/rar/echodash/sendspin/musicassistant/MaCommandClientPlayersTest.kt
  git commit -m "feat(media): MaPlayer + parsePlayers + multi-room player commands

New MaPlayer model + parsePlayers (players/all wire shape, display_name/group_childs/
state aliases, nullable volume). Five MaCommandClient methods — getPlayers (with
return_protocol_players flag), setPlayerVolume, groupPlayer, ungroupPlayer,
transferQueue — each rethrowing CancellationException (library-browse precedent).
Parsing pinned (3 tests); command-sending is exercised via MaLibrary next task.

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 2 — MaLibrary wrappers (players w/ retry, volume, group/ungroup, transfer) + selfPlayerId

Wrap the five ops in `MaLibrary` (following its `withClient`/`withQueue` idiom), implement the self-missing → `includeProtocol` one-shot retry inside `players()`, and expose `selfPlayerId`. Tested deliverable: the wrapper arg-building + retry pins (`MaLibraryTest`, through the existing `FakeTransport` seam that drives the real `MaCommandClient`).

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt` — new import for `MaPlayer`; `selfPlayerId` getter + five wrappers inserted after the `playRadio` wrapper (~line 196), before the `// ---- Connection loop ----` banner (~line 198).
- Test: `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt` — 7 new `@Test` methods appended inside the class (after the last favorite/radio test, before the class's closing brace); one new import.

**Interfaces:**
- Consumes: `MaCommandClient.getPlayers`/`setPlayerVolume`/`groupPlayer`/`ungroupPlayer`/`transferQueue` (Task 1); `MaPlayer` (Task 1); the existing `withClient` / `withQueue` seams and the class `private val playerId`.
- Produces:
  - `MaLibrary.selfPlayerId: String` (val getter = `playerId`).
  - `MaLibrary.players(): Result<List<MaPlayer>>` — `withClient`: `getPlayers()`; if the result lacks our `playerId`, refetch once with `includeProtocol = true` and return that.
  - `MaLibrary.setPlayerVolume(playerId: String, volume: Int): Result<Unit>` — `withClient`.
  - `MaLibrary.groupWithMe(playerId: String): Result<Unit>` — `withClient`: `groupPlayer(playerId, targetPlayer = selfPlayerId)`.
  - `MaLibrary.ungroupPlayer(playerId: String): Result<Unit>` — `withClient`.
  - `MaLibrary.transferMyQueueTo(targetPlayerId: String): Result<Unit>` — `withQueue`: `transferQueue(sourceQueueId = effective queue id, targetQueueId = targetPlayerId)`.

### Steps

- [ ] **Step 1: Write the failing MaLibrary tests.** In `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt`, add the `MaPlayer` import next to the existing `com.rar.echodash.sendspin.musicassistant.*` imports (near line 6):

  ```kotlin
  import com.rar.echodash.sendspin.musicassistant.MaPlayer
  ```

  Then append these 7 methods inside the class, after the last favorite/radio test (`playOmitsRadioModeByDefault`, ends ~line 402), before the class's closing brace. `assertNull` is already imported (line 19).

  ```kotlin
      // ---- multi-room / speaker ops ----

      @Test
      fun playersSendsPlayersAllAndDoesNotRetryWhenSelfPresent() = runTest {
          val h = Harness(this)
          h.lib.configure(enabled = true, token = "tok")
          runCurrent()
          h.transports[0].respond = { cmd ->
              if (cmd == "players/all")
                  json("""{"result":[{"player_id":"player-1","name":"This","available":true}]}""")
              else json("{}")
          }
          val res = h.lib.players()
          assertTrue(res.isSuccess)
          assertEquals(1, res.getOrThrow().size)
          // Self present on the first fetch: exactly one call, no protocol arg.
          assertEquals(listOf("players/all"), h.commands.map { it.first })
          assertNull(h.commands[0].second["return_protocol_players"])
          h.lib.stop()
      }

      @Test
      fun playersRetriesWithProtocolWhenSelfMissing() = runTest {
          val h = Harness(this)
          h.lib.configure(enabled = true, token = "tok")
          runCurrent()
          var calls = 0
          h.transports[0].respond = { cmd ->
              if (cmd == "players/all") {
                  calls++
                  if (calls == 1) json("""{"result":[{"player_id":"kitchen","name":"Kitchen","available":true}]}""")
                  else json("""{"result":[{"player_id":"player-1","name":"This","available":true},{"player_id":"kitchen","name":"Kitchen","available":true}]}""")
              } else json("{}")
          }
          val res = h.lib.players()
          assertTrue(res.isSuccess)
          // The self-inclusive second fetch is what we return.
          assertEquals(2, res.getOrThrow().size)
          assertEquals(listOf("players/all", "players/all"), h.commands.map { it.first })
          assertNull(h.commands[0].second["return_protocol_players"]) // first: default
          assertEquals(true, h.commands[1].second["return_protocol_players"]) // retry: protocol on
          h.lib.stop()
      }

      @Test
      fun playersRetriesOnceThenReturnsListEvenIfSelfStillAbsent() = runTest {
          val h = Harness(this)
          h.lib.configure(enabled = true, token = "tok")
          runCurrent()
          // Self never appears (e.g. this device isn't a known MA player) — never fail for it.
          h.transports[0].respond = { cmd ->
              if (cmd == "players/all")
                  json("""{"result":[{"player_id":"kitchen","name":"Kitchen","available":true}]}""")
              else json("{}")
          }
          val res = h.lib.players()
          assertTrue(res.isSuccess)
          assertEquals(1, res.getOrThrow().size)
          // Retried exactly once; no third attempt.
          assertEquals(listOf("players/all", "players/all"), h.commands.map { it.first })
          h.lib.stop()
      }

      @Test
      fun setPlayerVolumeSendsPlayerIdAndLevel() = runTest {
          val h = Harness(this)
          h.lib.configure(enabled = true, token = "tok")
          runCurrent()
          assertTrue(h.lib.setPlayerVolume("kitchen", 42).isSuccess)
          assertEquals(listOf("players/cmd/volume_set"), h.commands.map { it.first })
          assertEquals("kitchen", h.commands[0].second["player_id"])
          assertEquals(42, h.commands[0].second["volume_level"])
          h.lib.stop()
      }

      @Test
      fun groupWithMeSendsTargetPlayerOwnId() = runTest {
          val h = Harness(this)
          h.lib.configure(enabled = true, token = "tok")
          runCurrent()
          assertTrue(h.lib.groupWithMe("kitchen").isSuccess)
          assertEquals(listOf("players/cmd/group"), h.commands.map { it.first })
          assertEquals("kitchen", h.commands[0].second["player_id"])
          assertEquals("player-1", h.commands[0].second["target_player"]) // our own id
          h.lib.stop()
      }

      @Test
      fun ungroupPlayerSendsPlayerId() = runTest {
          val h = Harness(this)
          h.lib.configure(enabled = true, token = "tok")
          runCurrent()
          assertTrue(h.lib.ungroupPlayer("kitchen").isSuccess)
          assertEquals(listOf("players/cmd/ungroup"), h.commands.map { it.first })
          assertEquals("kitchen", h.commands[0].second["player_id"])
          h.lib.stop()
      }

      @Test
      fun transferMyQueueToResolvesQueueThenTransfers() = runTest {
          val h = Harness(this)
          h.lib.configure(enabled = true, token = "tok")
          runCurrent()
          assertTrue(h.lib.transferMyQueueTo("kitchen").isSuccess)
          assertEquals(
              listOf("player_queues/get_active_queue", "player_queues/transfer"),
              h.commands.map { it.first },
          )
          val args = h.commands[1].second
          assertEquals("q-77", args["source_queue_id"]) // our effective queue id
          assertEquals("kitchen", args["target_queue_id"])
          assertNull(args["auto_play"]) // deliberately omitted
          h.lib.stop()
      }
  ```

- [ ] **Step 2: Run the tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest \
    --tests 'com.rar.echodash.sendspin.MaLibraryTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compile failure — `unresolved reference: players` / `setPlayerVolume` / `groupWithMe` / `ungroupPlayer` / `transferMyQueueTo`. RC != 0.

- [ ] **Step 3: Add the `MaPlayer` import to `MaLibrary.kt`.** Next to the other `com.rar.echodash.sendspin.musicassistant.*` imports (the file already imports `MaPlaylist`/`MaQueueState`/etc.), add:

  ```kotlin
  import com.rar.echodash.sendspin.musicassistant.MaPlayer
  ```

- [ ] **Step 4: Add the wrappers + selfPlayerId.** In `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt`, insert this block immediately after the `playRadio` wrapper (ends ~line 196), before the `// ---- Connection loop ----` banner (~line 198):

  ```kotlin
      // ---- Multi-room / speaker ops ----

      /** This device's MA player id — the pane pins its own row first and marks it "this device". */
      val selfPlayerId: String get() = playerId

      /**
       * All MA players. Our own Echo is a SendSpin protocol player, hidden by the default
       * players/all (return_protocol_players=false). So if OUR [playerId] is absent from the first
       * result, refetch ONCE with includeProtocol = true and use that — the pane needs the self
       * row to pin it and gate self-only actions. A first-call failure is returned as-is; a list
       * that still lacks self after the retry is returned anyway (the pane degrades gracefully).
       */
      suspend fun players(): Result<List<MaPlayer>> = withClient { client ->
          val first = client.getPlayers()
          val list = first.getOrNull()
          if (list != null && list.none { it.playerId == playerId }) {
              client.getPlayers(includeProtocol = true)
          } else {
              first
          }
      }

      /** Set one player's volume (0..100). */
      suspend fun setPlayerVolume(playerId: String, volume: Int): Result<Unit> =
          withClient { it.setPlayerVolume(playerId, volume) }

      /** Join [playerId] onto THIS device's sync group (target_player = our own id). */
      suspend fun groupWithMe(playerId: String): Result<Unit> =
          withClient { it.groupPlayer(playerId, targetPlayer = selfPlayerId) }

      /** Remove [playerId] from whatever group/sync it's in. */
      suspend fun ungroupPlayer(playerId: String): Result<Unit> =
          withClient { it.ungroupPlayer(playerId) }

      /**
       * Move THIS device's queue to [targetPlayerId]: source = our effective queue id (the same
       * withQueue seam play/queue use), target = the row's player id (MA queue ids equal player ids).
       */
      suspend fun transferMyQueueTo(targetPlayerId: String): Result<Unit> =
          withQueue { client, queueId -> client.transferQueue(queueId, targetPlayerId) }
  ```

  (Note: `groupWithMe`'s parameter `playerId` shadows the class property, so its own-id target reads `selfPlayerId` — the getter resolves the property outside the parameter's scope. `players()` has no parameter, so its `playerId` reference is the class property.)

- [ ] **Step 5: Run the tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest \
    --tests 'com.rar.echodash.sendspin.MaLibraryTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, existing + 7 new tests green.

- [ ] **Step 6: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (suite now 1017) and `NODE RC=0`.

- [ ] **Step 7: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt \
          app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt
  git commit -m "feat(media): MaLibrary multi-room wrappers + selfPlayerId

players() (withClient) retries once with includeProtocol=true when our own player is
missing (the flagged protocol-player fallback); setPlayerVolume/groupWithMe (target =
our id)/ungroupPlayer (withClient) + transferMyQueueTo (withQueue: source = effective
queue, target = row id); selfPlayerId exposes our id to the pane. Pinned via the
FakeTransport seam (7 tests, incl. the self-missing retry and no-retry paths).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 3 — SpeakersModel pure fns (speakerRows / inGroupWithSelf / canOfferGroup)

Add the three tested pure functions that decide row ordering, group membership, and whether the Group action may be offered. Tested deliverable: the ordering + membership + offer-gating pins (`SpeakersModelTest`, plain-JVM).

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/SpeakersModel.kt`.
- Test: `app/src/test/java/com/rar/echodash/ui/model/SpeakersModelTest.kt` (new).

**Interfaces:**
- Consumes: `MaPlayer` (Task 1).
- Produces:
  - `fun speakerRows(players: List<MaPlayer>, selfId: String): List<MaPlayer>` — self pinned first, then available-before-unavailable, then A-Z (case-insensitive).
  - `fun inGroupWithSelf(player: MaPlayer, self: MaPlayer?): Boolean` — true when `player` shares a sync group with `self` in either leadership direction; false when `self` is null or `player` IS self.
  - `fun canOfferGroup(player: MaPlayer, self: MaPlayer?, allPlayerIds: Set<String>): Boolean` — true when not self, available, not already grouped with self, and self is a permitted target under the permissive heuristic (empty `canGroupWith` → true; else self listed → true; else any `canGroupWith` entry that is NOT one of `allPlayerIds` → true, treating it as a provider-instance grant; else false). `allPlayerIds` = the ids of the currently-listed players (SpeakersPane passes them).

### Steps

- [ ] **Step 1: Write the failing tests.** Create `app/src/test/java/com/rar/echodash/ui/model/SpeakersModelTest.kt`:

  ```kotlin
  package com.rar.echodash.ui.model

  import com.rar.echodash.sendspin.musicassistant.MaPlayer
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class SpeakersModelTest {

      /** A player with just the fields these helpers read; the rest are inert placeholders. */
      private fun player(
          id: String,
          name: String = id,
          available: Boolean = true,
          syncedTo: String? = null,
          groupMembers: List<String> = emptyList(),
          canGroupWith: List<String> = emptyList(),
      ): MaPlayer = MaPlayer(
          playerId = id, name = name, available = available, volumeLevel = 50, muted = false,
          syncedTo = syncedTo, groupMembers = groupMembers, canGroupWith = canGroupWith,
          playbackState = null, nowPlayingText = null,
      )

      // ---- speakerRows ----

      @Test
      fun speakerRowsPinsSelfFirst() {
          // self sorts last alphabetically but must still lead the list.
          val rows = speakerRows(
              listOf(player("a", "Aaa"), player("z", "Zzz"), player("m", "Mid")),
              selfId = "z",
          )
          assertEquals(listOf("z", "a", "m"), rows.map { it.playerId })
      }

      @Test
      fun speakerRowsPutsAvailableBeforeUnavailable() {
          val rows = speakerRows(
              listOf(player("off", "Aaa", available = false), player("on", "Zzz", available = true)),
              selfId = "none",
          )
          assertEquals(listOf("on", "off"), rows.map { it.playerId }) // available first despite A-Z
      }

      @Test
      fun speakerRowsSortsAlphaWithinTier() {
          val rows = speakerRows(
              listOf(player("c", "Zeta"), player("a", "Alpha"), player("b", "mid")),
              selfId = "none",
          )
          assertEquals(listOf("Alpha", "mid", "Zeta"), rows.map { it.name }) // case-insensitive A-Z
      }

      @Test
      fun speakerRowsWithSelfMissingJustSorts() {
          val rows = speakerRows(
              listOf(player("z", "Zzz"), player("a", "Aaa")),
              selfId = "not-here",
          )
          assertEquals(listOf("a", "z"), rows.map { it.playerId }) // no self pin, plain A-Z
      }

      // ---- inGroupWithSelf ----

      @Test
      fun inGroupWithSelfWhenPlayerSyncedToSelf() {
          val self = player("me")
          val other = player("kitchen", syncedTo = "me")
          assertTrue(inGroupWithSelf(other, self))
      }

      @Test
      fun inGroupWithSelfWhenSelfSyncedToPlayer() {
          val self = player("me", syncedTo = "kitchen") // other leadership direction
          val other = player("kitchen")
          assertTrue(inGroupWithSelf(other, self))
      }

      @Test
      fun inGroupWithSelfViaSelfGroupMembers() {
          val self = player("me", groupMembers = listOf("me", "kitchen")) // self leads the group
          val other = player("kitchen")
          assertTrue(inGroupWithSelf(other, self))
      }

      @Test
      fun inGroupWithSelfFalseWhenUnrelated() {
          assertFalse(inGroupWithSelf(player("kitchen"), player("me")))
      }

      @Test
      fun inGroupWithSelfFalseWhenSelfNull() {
          assertFalse(inGroupWithSelf(player("kitchen"), null))
      }

      // ---- canOfferGroup ----
      // The third arg is the set of currently-listed player ids. A canGroupWith entry that is NOT
      // one of them is treated as a provider-instance grant (permissive); a pure allowlist of other
      // players' ids that excludes us is a real "can't group with us".

      @Test
      fun canOfferGroupFalseForSelf() {
          val self = player("me")
          assertFalse(canOfferGroup(self, self, setOf("me", "kitchen")))
      }

      @Test
      fun canOfferGroupTrueWhenCanGroupWithEmpty() {
          // Empty canGroupWith = MA omitted restrictions => permissive.
          assertTrue(canOfferGroup(player("kitchen"), player("me"), setOf("me", "kitchen")))
      }

      @Test
      fun canOfferGroupTrueWhenSelfListed() {
          assertTrue(canOfferGroup(
              player("kitchen", canGroupWith = listOf("me", "living")), player("me"),
              setOf("me", "kitchen", "living"),
          ))
      }

      @Test
      fun canOfferGroupTrueForProviderGrant() {
          // "airplay--abc" is not a listed player id => a provider-instance grant => permissive.
          assertTrue(canOfferGroup(
              player("kitchen", canGroupWith = listOf("airplay--abc")), player("me"),
              setOf("me", "kitchen", "living"),
          ))
      }

      @Test
      fun canOfferGroupTrueForMixedListWithSelf() {
          // Self explicitly listed wins even alongside a provider grant.
          assertTrue(canOfferGroup(
              player("kitchen", canGroupWith = listOf("me", "airplay--abc")), player("me"),
              setOf("me", "kitchen"),
          ))
      }

      @Test
      fun canOfferGroupFalseForForeignPlayerIdAllowlist() {
          // Every entry is a real OTHER player id (all listed), and we're not among them.
          assertFalse(canOfferGroup(
              player("kitchen", canGroupWith = listOf("living", "study")), player("me"),
              setOf("me", "kitchen", "living", "study"),
          ))
      }

      @Test
      fun canOfferGroupFalseWhenUnavailable() {
          assertFalse(canOfferGroup(player("kitchen", available = false), player("me"), setOf("me", "kitchen")))
      }

      @Test
      fun canOfferGroupFalseWhenAlreadyGrouped() {
          val self = player("me")
          val other = player("kitchen", syncedTo = "me") // already in self's group
          assertFalse(canOfferGroup(other, self, setOf("me", "kitchen")))
      }

      @Test
      fun canOfferGroupFalseWhenSelfNull() {
          assertFalse(canOfferGroup(player("kitchen"), null, setOf("me", "kitchen")))
      }
  }
  ```

- [ ] **Step 2: Run the tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest \
    --tests 'com.rar.echodash.ui.model.SpeakersModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compile failure — `unresolved reference: speakerRows` / `inGroupWithSelf` / `canOfferGroup`. RC != 0.

- [ ] **Step 3: Create `SpeakersModel.kt`.** Write `app/src/main/java/com/rar/echodash/ui/model/SpeakersModel.kt`:

  ```kotlin
  package com.rar.echodash.ui.model

  import com.rar.echodash.sendspin.musicassistant.MaPlayer

  /**
   * Rows for the Speakers pane: self pinned to the very top, then available players before
   * unavailable, A-Z (case-insensitive) within each tier. Stable for a self that isn't in the
   * list (no pin, just the availability + A-Z ordering).
   */
  fun speakerRows(players: List<MaPlayer>, selfId: String): List<MaPlayer> =
      players.sortedWith(
          compareByDescending<MaPlayer> { it.playerId == selfId } // self (true) sorts first
              .thenByDescending { it.available }                  // available (true) before not
              .thenBy { it.name.lowercase() },                    // A-Z within tier
      )

  /**
   * True when [player] shares a sync group with [self], in either leadership direction: player is
   * synced to self, self is synced to player, or either lists the other in its group members
   * (a dedicated/virtual group). False when [self] is null or [player] IS self — a player is not
   * "grouped with itself" for the pane's Ungroup affordance (and MA lists a leader's own id in
   * its group_members, which this short-circuit avoids mis-reading).
   */
  fun inGroupWithSelf(player: MaPlayer, self: MaPlayer?): Boolean {
      if (self == null || player.playerId == self.playerId) return false
      return player.syncedTo == self.playerId ||
          self.syncedTo == player.playerId ||
          self.groupMembers.contains(player.playerId) ||
          player.groupMembers.contains(self.playerId)
  }

  /**
   * True when the "Group with me" action may be offered for [player]. Exclusions first: never self,
   * never an unavailable player, never one already grouped with self. Then a permissive heuristic
   * over [MaPlayer.canGroupWith] — the server enforces the real rule, so we err toward showing the
   * chip and let an illegal join surface as the pane's error toast:
   *  - EMPTY canGroupWith → true (MA omits the field for unrestricted players).
   *  - self's player id present → true (explicit allow).
   *  - any entry that is NOT one of [allPlayerIds] (the currently-listed players) → true: such an
   *    entry is a provider-instance grant covering that provider's players, which is commonly how
   *    MA expresses "groupable" rather than an individual player id.
   *  - otherwise → false: a pure allowlist of other players' ids that excludes us.
   * ([allPlayerIds] = the ids the pane is showing; live-verify item 5.)
   */
  fun canOfferGroup(player: MaPlayer, self: MaPlayer?, allPlayerIds: Set<String>): Boolean {
      if (self == null || player.playerId == self.playerId) return false
      if (!player.available) return false
      if (inGroupWithSelf(player, self)) return false
      val grants = player.canGroupWith
      if (grants.isEmpty()) return true
      if (grants.contains(self.playerId)) return true
      // A grant that isn't a listed player id is a provider-instance grant → permissive.
      return grants.any { it !in allPlayerIds }
  }
  ```

- [ ] **Step 4: Run the tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest \
    --tests 'com.rar.echodash.ui.model.SpeakersModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, the 18 new tests green.

- [ ] **Step 5: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (suite now 1035) and `NODE RC=0`.

- [ ] **Step 6: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/model/SpeakersModel.kt \
          app/src/test/java/com/rar/echodash/ui/model/SpeakersModelTest.kt
  git commit -m "feat(media): SpeakersModel pure fns (rows / group membership / offer gate)

speakerRows pins self first then available-before-unavailable then A-Z;
inGroupWithSelf detects a shared sync group in either leadership direction (self and
self==player short-circuited); canOfferGroup gates the Group action (not self,
available, not already grouped) with a permissive canGroupWith heuristic — empty or a
provider-instance grant or self-listed => offer; a pure foreign player-id allowlist =>
hide (the server enforces the truth). 18 plain-JVM pins.

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 4 — SpeakersPane UI + Speakers chip + mutual exclusion (MusicBrowser, single file)

Add the Speakers toggle button beside the Queue button (mutually exclusive), the `speakersVisible`/`speakers`/`speakersVersion` state with a 5 s poll, and the `SpeakersPane`/`SpeakerRow`/`SpeakerActionChip`/`SpeakersToggleButton` composables — all inside `MusicBrowser.kt`, calling `library.*` directly. **UI/wiring task: no unit test** — the decision logic is covered by Task 3's `SpeakersModel` tests and the wrappers by Task 2; composables + threading are verified only by the `:app:assembleDebug` compile.

**Design decision (flagged for review):** the per-player volume slider does NOT bump `speakersVersion` on success — it relies on the 5 s poll to reconcile, exactly like the takeover volume slider (`NowPlayingHome`), so a drag never fights an immediate refetch that would re-seed the slider mid-interaction. Group / Ungroup / Send-queue DO bump on `.onSuccess` (state actually changed and the user expects the row to update promptly). "Send queue" additionally closes the pane on success (the music left this device; the browser stays).

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — imports; state + poll; top-bar button + mutual exclusion; tab-row reset; the `speakersVisible` content branch; four new private composables.

**Interfaces:**
- Consumes: `MaLibrary.players`/`setPlayerVolume`/`groupWithMe`/`ungroupPlayer`/`transferMyQueueTo`/`selfPlayerId` (Task 2); `MaPlayer` (Task 1); `speakerRows`/`inGroupWithSelf`/`canOfferGroup` (Task 3); `EmptyHint` (already imported from `PanelScaffold`); `Slider` (new import).
- Produces (internal, MusicBrowser): `SpeakersToggleButton(active, onClick)`; `SpeakersPane(players, selfId, onSetVolume, onGroup, onUngroup, onSendQueue)`; `SpeakerRow(...)`; `SpeakerActionChip(label, onClick)`.

### Steps

- [ ] **Step 1: Read** the current `MusicBrowser.kt` anchors and confirm they still match: the import block (lines ~37–106), the state `var`s (`queueVisible`/`queueState`/`queueVersion` at lines 174–176), the queue poll `LaunchedEffect` (lines ~250–258), the top-bar `Row` (lines ~306–312), the `BrowserTabs` `onSelect` (lines ~317–324), the content `when` block's `queueVisible -> QueuePane(...)` branch (lines ~336–374), and the `QueueToggleButton` composable (lines ~468–483).

- [ ] **Step 2: Add imports.** Add these next to the matching existing import groups in `MusicBrowser.kt`:

  1. Material icon (next to the other `androidx.compose.material.icons.outlined.*` imports, ~lines 41–47):
  ```kotlin
  import androidx.compose.material.icons.outlined.SpeakerGroup
  ```
  2. Slider (next to the other `androidx.compose.material3.*` imports, ~lines 48–52):
  ```kotlin
  import androidx.compose.material3.Slider
  ```
  3. `mutableFloatStateOf` (next to `mutableIntStateOf`, ~line 57):
  ```kotlin
  import androidx.compose.runtime.mutableFloatStateOf
  ```
  4. `MaPlayer` (next to the other `com.rar.echodash.sendspin.musicassistant.*` imports, ~lines 85–93):
  ```kotlin
  import com.rar.echodash.sendspin.musicassistant.MaPlayer
  ```
  5. The three model fns (next to `currentItemOf`/`nextRepeatMode`, ~lines 97–101):
  ```kotlin
  import com.rar.echodash.ui.model.canOfferGroup
  import com.rar.echodash.ui.model.inGroupWithSelf
  import com.rar.echodash.ui.model.speakerRows
  ```

- [ ] **Step 3: Add the state + poll.** In the `MusicBrowser` composable body, add three `var`s immediately after the `queueVersion` line (line 176):

  ```kotlin
      var queueVersion by remember { mutableIntStateOf(0) }
      var speakersVisible by remember { mutableStateOf(false) }
      var speakers by remember { mutableStateOf<List<MaPlayer>?>(null) }
      var speakersVersion by remember { mutableIntStateOf(0) }
  ```

  Then add the poll `LaunchedEffect` immediately after the queue poll effect (after its closing `}` at line 258), mirroring it:

  ```kotlin
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
  ```

- [ ] **Step 4: Add the Speakers button + mutual exclusion in the top bar.** Replace the top-bar `Row` block (lines ~306–312) with:

  ```kotlin
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
  ```

- [ ] **Step 5: Close the pane on tab select.** In the `BrowserTabs` `onSelect` lambda (lines ~319–323), add `speakersVisible = false` next to the existing `queueVisible = false`:

  ```kotlin
              onSelect = { target ->
                  queueVisible = false
                  speakersVisible = false
                  pageStack = if (target is BrowserPage.Home) listOf(BrowserPage.Home)
                              else listOf(BrowserPage.Home, target)
              },
  ```

- [ ] **Step 6: Add the content branch.** In the content `when` block, insert the `speakersVisible` branch immediately BEFORE the `queueVisible -> QueuePane(...)` branch (before line 336). The two are mutually exclusive, so this ordering is defensive only:

  ```kotlin
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
  ```

  (Leave the rest of the `queueVisible -> QueuePane(...)` call and the remaining branches unchanged.)

- [ ] **Step 7: Add the `SpeakersToggleButton` composable.** Immediately after the `QueueToggleButton` composable (after its closing brace at line 483), add:

  ```kotlin
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
  ```

- [ ] **Step 8: Add the Speakers overlay composables.** Immediately after the `QueueToggleChip` composable (after its closing brace at line 1131), add the pane, row, and action chip:

  ```kotlin
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
  ```

- [ ] **Step 9: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (compile of the top-bar button, mutual exclusion, poll, and the four composables) and the 1035-test suite green. If the log shows an "unused parameter" or missing-import error, re-check Step 2's imports.

- [ ] **Step 10: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt
  git commit -m "feat(media): Speakers overlay pane (grouping + volume + queue transfer)

A Speakers chip beside the Queue chip opens an overlay (mutually exclusive with the
queue) listing every MA player: self pinned + labeled, per-player volume slider, Group
/ Ungroup relative to this device, and Send queue (closes the pane on success). Polls
library.players() every 5 s while visible; mutations refetch on .onSuccess (volume
relies on the poll to avoid drag jitter). UI/wiring (compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Self-Review

**1. Spec coverage:**
- New `MaPlayer` model with the exact spec fields — Task 1. ✓
- `parsePlayers` reads `display_name`/`group_childs`/`state` via fallback; nullable volume; pins full/minimal/unavailable payloads — Task 1. ✓
- Five `MaCommandClient` methods (`getPlayers` w/ `return_protocol_players`, `setPlayerVolume`, `groupPlayer`, `ungroupPlayer`, `transferQueue` w/ auto_play omitted) — Task 1. ✓
- All new suspend client methods rethrow `CancellationException` before the generic catch (library-browse precedent) — Task 1 (every method has the `catch (e: CancellationException) { throw e }` clause). ✓
- `MaLibrary` wrappers: `players()` with self-missing → `includeProtocol` one-shot retry, `setPlayerVolume`, `groupWithMe` (target = own id), `ungroupPlayer`, `transferMyQueueTo` (withQueue effective queue → target row id), plus `selfPlayerId` — Task 2. ✓
- MaLibraryTest pins: `players/all` no protocol arg on first fetch; self-missing retry with `return_protocol_players:true`; retry-once-then-return-even-if-absent; `groupWithMe` target = own id; `transferMyQueueTo` resolves queue then transfers with target = row id; `setPlayerVolume` args — Task 2 (7 tests). ✓ The self-missing retry test uses a call-counter closure on `FakeTransport.respond` (a reassignable `var`) to return different `players/all` payloads per call. ✓
- `SpeakersModel` pure fns: `speakerRows` (self first, availability tiers, A-Z), `inGroupWithSelf` (both leadership directions + self/null), `canOfferGroup` (self excluded, empty-permissive, explicit allow, provider-instance grant permissive, pure foreign player-id allowlist excluded, mixed-with-self allowed, unavailable, already-grouped, self-null) — Task 3 (18 tests). ✓
- Speakers chip next to the Queue chip; overlay mutually exclusive with the queue (opening one closes the other; tab select closes both) — Task 4. ✓
- SpeakersPane header "Speakers"; poll `library.players()` every 5 s while visible; `speakersVersion` immediate refetch after a mutation, completion-ordered (bump inside `.onSuccess`) — Task 4. ✓
- Row: name (+ "· this device" on self), dimmed when `!available`; 12 sp dimmed status line ("Playing — …" / "Grouped with <leader>" / "Idle"); volume slider 0..100 on `onValueChangeFinished`, hidden when `volumeLevel == null` or `!available`; Group / Ungroup / Send-queue chips gated by the model fns; self row = slider only, no action chips — Task 4. ✓
- "Send queue" closes the pane on success — Task 4 (`speakersVisible = false` inside `.onSuccess`). ✓
- Degradation table: MA down → pane shows the error toast on fetch (poll `.onFailure { showError }`); self absent after retry → list renders without the self pin, self-gated actions hidden (self-only chips are guarded by `!isSelf` + model fns which return false for a null self); unavailable player → dimmed, no slider/actions; a pure foreign player-id `canGroupWith` allowlist → no Group chip (an empty list or a provider-instance grant keeps it — permissive heuristic); transfer fails → toast, pane stays open, next poll reconciles; volume on a just-removed player → command fails → toast, next poll drops the row — Tasks 1–4. ✓
- `selfPlayerId` exposed on `MaLibrary` for the UI — Task 2. ✓
- The flagged RISK (protocol-player visibility) mitigation is coded exactly where the spec mandates (`MaLibrary.players()` one-shot retry) and pinned by tests — Task 2. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete code; every command has expected output. ✓

**3. Type consistency:** `MaPlayer(playerId, name, available, volumeLevel: Int?, muted, syncedTo: String?, groupMembers: List<String>, canGroupWith: List<String>, playbackState: String?, nowPlayingText: String?)` — identical in Task 1 (parse + test), Task 3 (test helper), Task 4 (row rendering). `getPlayers(includeProtocol: Boolean = false): Result<List<MaPlayer>>` — Task 1 defines, Task 2's `players()` calls (`getPlayers()` / `getPlayers(includeProtocol = true)`). `transferQueue(sourceQueueId, targetQueueId)` — Task 1 defines, Task 2's `transferMyQueueTo` calls `client.transferQueue(queueId, targetPlayerId)`. `speakerRows(players, selfId)` / `inGroupWithSelf(player, self)` / `canOfferGroup(player, self, allPlayerIds)` — Task 3 defines; Task 4 calls with matching arities (`speakerRows(players, selfId)`, `inGroupWithSelf(player, self)`, `canOfferGroup(player, self, allPlayerIds)`). `selfPlayerId` — Task 2 defines; Task 4 reads `library.selfPlayerId`. `SpeakersPane(players, selfId, onSetVolume, onGroup, onUngroup, onSendQueue)` and `SpeakerRow(player, self, isSelf, leaderName, allPlayerIds, onSetVolume, onGroup, onUngroup, onSendQueue)` — signatures match their call sites within Task 4. ✓

---

## Live-verify checklist (implementation end — not a task; run on-device)

Reproduced verbatim from the design spec:

1. Open Speakers while MA music plays → all MA players listed, this device pinned first and labeled; note whether the includeProtocol fallback fired (logcat).
2. Slide another player's volume → that speaker's volume changes in MA.
3. Group a kitchen/other speaker with this device → music plays synced on both; Ungroup returns it; MA UI agrees throughout.
4. Send my queue to another player → playback continues there, this device goes idle, pane closes; queue intact on the target (check MA UI).
5. A player MA says can't group with us shows no Group chip.
6. Companion-source day-to-day screens unaffected (pane is MA-only surface).
