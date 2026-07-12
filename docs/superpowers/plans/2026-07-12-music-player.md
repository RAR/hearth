# Music Player + Home-Screen Now-Playing — Implementation Plan (TDD)

**Date:** 2026-07-12
**Spec:** `docs/superpowers/specs/2026-07-12-music-player-design.md` (binding)
**Status:** Plan — not yet implemented

> Note for agentic workers: execute tasks in order. Each task is independently compilable and
> testable and ends with the full build/test gate plus a commit. Pure-logic deliverables
> (NowPlayingStore, ICY parsing, URL resolution, config) are written test-first. Android/Compose
> deliverables (ExoPlayerEngine, ArtFetcher, MediaPanel, HomeView, App.kt) have no JVM test
> harness in this project and are compile-gated by `assembleDebug` + verified on-device — this is
> called out explicitly wherever it applies. Do not bump any dependency or SDK level.

## Goal

Turn the bare Media panel into a real music player — album art, track metadata, full transport —
and take over the home screen with an album-art now-playing layout while the device is playing.
Scope is device playback only (what the Echo plays through its own VACA/ExoPlayer engine). Metadata
is a hybrid merge of ExoPlayer local metadata (ICY `StreamTitle`, embedded ID3 art) and an optional
HA companion `media_player` entity (real title/artist/album/`entity_picture`). The device engine's
state is always the master gate for whether the player UI shows; the entity never activates it.

## Architecture

- **`media/NowPlayingStore.kt`** (new, pure JVM): merges three inputs — `onEngine(active, playing,
  volume)`, `onLocalMeta(icyOrTagTitle, artworkData)`, `onEntity(EntityState?)` — into one
  `StateFlow<NowPlayingState>`. Precedence, ICY `"Artist - Title"` parsing, and `canSkip` all live
  here. No Android imports (it imports only `com.rar.echodash.ha.EntityState`, which is itself pure
  kotlinx-serialization). Unit-tested.
- **`media/ArtUrl.kt`** (new, pure JVM): top-level `resolveArtUrl(raw, baseUrl)` — resolves a raw
  `entity_picture` against the HA base URL (relative → prefixed, absolute → passthrough, null →
  null). Unit-tested; used by ArtFetcher.
- **`vaca/ExoPlayerEngine.kt`** (modify): gains `onMeta` (from `MediaEngine`) invoked from
  `Player.Listener.onMediaMetadataChanged` — ICY StreamTitle surfaces as `mediaMetadata.title`,
  embedded art as `mediaMetadata.artworkData`.
- **`vaca/MediaBridge.kt`** (modify): forwards engine state and local metadata into an injected
  `NowPlayingStore`. MediaBridge is pure JVM, so this forwarding is unit-tested.
- **`media/ArtFetcher.kt`** (new, Android): collects `NowPlayingState`, resolves/fetches
  (`entity_picture` over OkHttp with a `Bearer` header) or decodes embedded bytes, produces
  `ArtBitmaps(sharp, blurred)` where the blurred bitmap is a manual 24×12 downsample (API 30 has no
  `Modifier.blur`). Exposes `StateFlow<ArtBitmaps?>`. Compile-gated only.
- **`ui/panels/MediaPanel.kt`** (rewrite): album-art card + title/artist—album + transport (⏮ ⏯ ⏹
  ⏭, ⏮/⏭ only when `canSkip`) + volume. Compile-gated only.
- **`ui/NowPlayingHome.kt`** (new) + **`ui/HomeView.kt`** (modify): home-screen takeover behind the
  photo backdrop (Crossfade on `active`), slideshow pause, dusk-gradient fallback. Compile-gated
  only.
- **`config/DashConfig.kt`** (modify): new `media: MediaSettings(companionEntity)`, added to
  `referencedEntityIds()` (so EntityHub watches it automatically — no EntityHub.kt change) and
  `clamped()`.
- **`assets/config/index.html` + `assets/config/app.js`** (modify): a "Media" card with a
  companion-`media_player` picker following the existing AQI-sensor picker pattern.
- **`App.kt`** (modify): wires the store into MediaBridge, feeds the companion entity from
  EntityHub, starts ArtFetcher, adds next/prev via `callService` gated on `canSkip`, and a
  screen-wake re-arm loop while `playing`.

## Tech Stack

Kotlin 2.1.0, Jetpack Compose, media3 1.4.1 (ExoPlayer), kotlinx.serialization, kotlinx.coroutines,
OkHttp, NanoHTTPD (config server), plain-JVM JUnit4 tests, vanilla JS config page.

## Global Constraints

- **Kotlin 2.1.0**, **compileSdk 34** — never bump either.
- **media3 pinned exactly 1.4.1**, **NanoHTTPD 2.3.1** — pinned. **NO new dependencies** of any
  kind. Only media3 1.4.1 APIs are used: `Player.Listener.onMediaMetadataChanged(MediaMetadata)`,
  `MediaMetadata.title` (CharSequence?), `MediaMetadata.artworkData` (ByteArray?) — all present in
  1.4.1, none 1.5+-only.
- **Device is Android 11 / API 30.** `Modifier.blur` / `RenderEffect` is a silent no-op below API
  31 — the blurred now-playing background MUST be a manual downsample (decode to ~24×12, let the GPU
  bilinear-upscale under a dark scrim). Never call `Modifier.blur`.
- Tests are **plain-JVM JUnit4 only** (no Robolectric, no instrumentation). `NowPlayingStore`,
  `resolveArtUrl`, `MediaBridge`, and `DashConfig`/`MediaSettings` are all reachable in plain JVM and
  ARE unit-tested. `ExoPlayerEngine` (media3), `ArtFetcher` (`android.graphics.Bitmap`), `MediaPanel`
  / `NowPlayingHome` / `HomeView` (Compose), and `App.kt` touch Android and are **not** unit-tested
  — compile-gated by `assembleDebug`, verified on-device. Never touch AudioTrack/AudioRecord/
  Compose/ExoPlayer classes from a JVM test.
- **Gate** (must pass before each commit), always `./gradlew` from repo root
  `/home/rar/android_simpla_ha_dash`:
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
- **Branch:** all work on `feature/music-player` (Task 1 creates it).
- **KDoc hazard:** never write a literal end-of-block-comment sequence (asterisk-slash) *inside* a
  block/KDoc comment — it closes the comment early and breaks the build.
- Follow existing idioms exactly: `@Serializable data class` + `clamped()` with `copy(...)`; plain
  Composables with `Color(0xFF...)` constants (no extra Material theming); `el`/`labeledRow`/
  `entityPicker`/`clear` DOM helpers in app.js; `Icons.Outlined.*` from material-icons-extended
  (already a dependency).

## Data contract (identical everywhere it appears)

```kotlin
data class NowPlayingState(
    val active: Boolean = false,      // engine has media loaded (play() until stop()/error)
    val playing: Boolean = false,     // actually playing (false while paused)
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artUrl: String? = null,       // RAW entity_picture attribute (may be relative), or null
    val localArt: ByteArray? = null,  // embedded ID3 artwork bytes, or null
    val volume: Int = 90,
    val canSkip: Boolean = false,     // companion entity actively driving (media_title non-blank)
)
```

Precedence (only while `active`): if the companion entity's `media_title` is non-blank, entity
metadata wins (title/artist/album from entity, art = raw `entity_picture`, `localArt` ignored);
otherwise local ICY/ID3 is used (title/artist from ICY split, art = `localArt`). When `!active`, all
of title/artist/album/artUrl/localArt are null but `volume` is retained. `canSkip` = entity
`media_title` non-blank.

---

# Task 1 — NowPlayingStore pure core + tests (branch, TDD)

Creates the feature branch and the merge/precedence/ICY-parse core with full unit tests. Everything
here is plain JVM.

## Files

- **NEW** `app/src/main/java/com/rar/echodash/media/NowPlayingStore.kt`
- **NEW** `app/src/test/java/com/rar/echodash/media/NowPlayingStoreTest.kt`

## Interfaces

- **Produces:** `data class NowPlayingState(...)` (contract above); `class NowPlayingStore` with
  `val state: StateFlow<NowPlayingState>`, `fun onEngine(active: Boolean, playing: Boolean, volume:
  Int)`, `fun onLocalMeta(icyOrTagTitle: String?, artworkData: ByteArray?)`, `fun onEntity(entity:
  EntityState?)`, and `companion object { fun parseIcy(raw: String?): Pair<String?, String?> }`.
- **Consumes:** `com.rar.echodash.ha.EntityState` (pure; `EntityModels.kt:20` `attr(key)`).

## Steps

### 1.1 — Create the branch

```
git checkout -b feature/music-player
```

### 1.2 — Write `NowPlayingStoreTest.kt` first (TDD: will not compile until 1.3)

Create `app/src/test/java/com/rar/echodash/media/NowPlayingStoreTest.kt`:

```kotlin
package com.rar.echodash.media

import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingStoreTest {

    /** Build a media_player EntityState from attribute pairs. */
    private fun entity(vararg attrs: Pair<String, String>): EntityState =
        EntityState(
            entityId = "media_player.ma",
            state = "playing",
            attributes = buildJsonObject { attrs.forEach { (k, v) -> put(k, v) } },
            lastUpdatedMs = 0L,
        )

    private fun emptyEntity(): EntityState =
        EntityState("media_player.ma", "idle", JsonObject(emptyMap()), 0L)

    @Test
    fun icySplitsOnFirstSeparator() {
        assertEquals("Daft Punk" to "Get Lucky", NowPlayingStore.parseIcy("Daft Punk - Get Lucky"))
    }

    @Test
    fun icyWithNoSeparatorIsAllTitle() {
        assertEquals(null to "Radio Paradise", NowPlayingStore.parseIcy("Radio Paradise"))
    }

    @Test
    fun icyWithMultipleSeparatorsSplitsOnFirst() {
        assertEquals("A" to "B - C", NowPlayingStore.parseIcy("A - B - C"))
    }

    @Test
    fun icyBlankOrNullIsNullPair() {
        assertEquals(null to null, NowPlayingStore.parseIcy("   "))
        assertEquals(null to null, NowPlayingStore.parseIcy(null))
    }

    @Test
    fun localIcyDrivesTitleAndArtistWhenNoEntity() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Miles Davis - So What", null)
        val v = s.state.value
        assertTrue(v.active)
        assertTrue(v.playing)
        assertEquals("So What", v.title)
        assertEquals("Miles Davis", v.artist)
        assertNull(v.album)
        assertFalse(v.canSkip)
    }

    @Test
    fun entityTitleBeatsLocalMetadata() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Local Artist - Local Title", byteArrayOf(1, 2, 3))
        s.onEntity(entity(
            "media_title" to "Real Title",
            "media_artist" to "Real Artist",
            "media_album_name" to "Real Album",
            "entity_picture" to "/api/media_player_proxy/media_player.ma?token=abc",
        ))
        val v = s.state.value
        assertEquals("Real Title", v.title)
        assertEquals("Real Artist", v.artist)
        assertEquals("Real Album", v.album)
        assertEquals("/api/media_player_proxy/media_player.ma?token=abc", v.artUrl)
        assertNull("entity art wins so localArt must be ignored", v.localArt)
        assertTrue(v.canSkip)
    }

    @Test
    fun fallsBackToLocalWhenEntityTitleBlankOrNull() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Local Artist - Local Title", null)
        // blank media_title -> entity does not win
        s.onEntity(entity("media_title" to "  "))
        assertEquals("Local Title", s.state.value.title)
        assertEquals("Local Artist", s.state.value.artist)
        assertFalse(s.state.value.canSkip)
        // null entity -> still local
        s.onEntity(null)
        assertEquals("Local Title", s.state.value.title)
        assertFalse(s.state.value.canSkip)
    }

    @Test
    fun artFollowsTextPrecedence() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Track", byteArrayOf(9, 9, 9))
        // no entity -> local art present, no url
        assertNull(s.state.value.artUrl)
        assertArrayEqualsOrNull(byteArrayOf(9, 9, 9), s.state.value.localArt)
        // entity wins -> url present, local art dropped
        s.onEntity(entity("media_title" to "Real", "entity_picture" to "/p.jpg"))
        assertEquals("/p.jpg", s.state.value.artUrl)
        assertNull(s.state.value.localArt)
    }

    @Test
    fun canSkipIsFalseWhenEntityUnconfigured() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Track", null)
        assertFalse(s.state.value.canSkip)
    }

    @Test
    fun canSkipTrueOnlyWithNonBlankEntityTitle() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onEntity(emptyEntity())
        assertFalse(s.state.value.canSkip)
        s.onEntity(entity("media_title" to "Song"))
        assertTrue(s.state.value.canSkip)
    }

    @Test
    fun pauseKeepsActiveButClearsPlaying() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 55)
        s.onLocalMeta("Some Title", null)
        s.onEngine(active = true, playing = false, volume = 55)
        val v = s.state.value
        assertTrue("paused still shows the player", v.active)
        assertFalse(v.playing)
        assertEquals("Some Title", v.title)
    }

    @Test
    fun stopClearsMetadataButKeepsVolume() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 42)
        s.onLocalMeta("Artist - Title", byteArrayOf(1))
        s.onEntity(entity("media_title" to "Real", "entity_picture" to "/p.jpg"))
        s.onEngine(active = false, playing = false, volume = 42)
        val v = s.state.value
        assertFalse(v.active)
        assertFalse(v.playing)
        assertNull(v.title)
        assertNull(v.artist)
        assertNull(v.album)
        assertNull(v.artUrl)
        assertNull(v.localArt)
        assertEquals("volume survives stop", 42, v.volume)
    }

    @Test
    fun volumeTracksEngine() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 30)
        assertEquals(30, s.state.value.volume)
        s.onEngine(active = true, playing = true, volume = 88)
        assertEquals(88, s.state.value.volume)
    }

    private fun assertArrayEqualsOrNull(expected: ByteArray, actual: ByteArray?) {
        assertTrue("expected bytes present", actual != null && actual.contentEquals(expected))
    }
}
```

### 1.3 — Write `NowPlayingStore.kt` to make the test pass

Create `app/src/main/java/com/rar/echodash/media/NowPlayingStore.kt`:

```kotlin
package com.rar.echodash.media

import com.rar.echodash.ha.EntityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-side snapshot of the on-device player merged with an optional HA companion media_player
 * entity. See NowPlayingState fields. localArt is embedded ID3/tag artwork bytes; artUrl is the RAW
 * entity_picture string (ArtFetcher resolves relative paths against the HA base URL).
 */
data class NowPlayingState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artUrl: String? = null,
    val localArt: ByteArray? = null,
    val volume: Int = 90,
    val canSkip: Boolean = false,
) {
    // ByteArray in a data class defaults to identity equals/hashCode; override so StateFlow dedups by
    // content and tests compare by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NowPlayingState) return false
        return active == other.active && playing == other.playing && title == other.title &&
            artist == other.artist && album == other.album && artUrl == other.artUrl &&
            volume == other.volume && canSkip == other.canSkip &&
            (localArt?.contentEquals(other.localArt ?: ByteArray(0)) ?: (other.localArt == null))
    }

    override fun hashCode(): Int {
        var r = active.hashCode()
        r = 31 * r + playing.hashCode()
        r = 31 * r + (title?.hashCode() ?: 0)
        r = 31 * r + (artist?.hashCode() ?: 0)
        r = 31 * r + (album?.hashCode() ?: 0)
        r = 31 * r + (artUrl?.hashCode() ?: 0)
        r = 31 * r + (localArt?.contentHashCode() ?: 0)
        r = 31 * r + volume
        r = 31 * r + canSkip.hashCode()
        return r
    }
}

/**
 * Merges three inputs into one [state]: the device engine (active/playing/volume), local ExoPlayer
 * metadata (ICY StreamTitle text or embedded tag artwork), and an optional HA companion media_player
 * entity. Pure JVM (only kotlinx + the pure EntityState) so it is unit-testable. The engine's active
 * flag is the master gate: when inactive, no metadata is exposed regardless of the entity. Inputs
 * arrive on different threads (VACA server thread, ExoPlayer main-thread callbacks, Compose
 * collectors), so the mutators are synchronized.
 */
class NowPlayingStore {
    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state

    private var engineActive = false
    private var enginePlaying = false
    private var engineVolume = 90
    private var localTitle: String? = null
    private var localArt: ByteArray? = null
    private var entity: EntityState? = null

    @Synchronized
    fun onEngine(active: Boolean, playing: Boolean, volume: Int) {
        engineActive = active
        enginePlaying = playing
        engineVolume = volume
        // Going inactive (stop/error) drops stale local metadata so the next session starts clean.
        if (!active) { localTitle = null; localArt = null }
        recompute()
    }

    @Synchronized
    fun onLocalMeta(icyOrTagTitle: String?, artworkData: ByteArray?) {
        localTitle = icyOrTagTitle?.takeIf { it.isNotBlank() }
        localArt = artworkData
        recompute()
    }

    @Synchronized
    fun onEntity(entity: EntityState?) {
        this.entity = entity
        recompute()
    }

    private fun recompute() {
        val entityTitle = entity?.attr("media_title")?.takeIf { it.isNotBlank() }
        val canSkip = entityTitle != null
        if (!engineActive) {
            _state.value = NowPlayingState(volume = engineVolume, canSkip = canSkip)
            return
        }
        _state.value = if (entityTitle != null) {
            NowPlayingState(
                active = true,
                playing = enginePlaying,
                title = entityTitle,
                artist = entity?.attr("media_artist")?.takeIf { it.isNotBlank() },
                album = entity?.attr("media_album_name")?.takeIf { it.isNotBlank() },
                artUrl = entity?.attr("entity_picture")?.takeIf { it.isNotBlank() },
                localArt = null,
                volume = engineVolume,
                canSkip = true,
            )
        } else {
            val (artist, title) = parseIcy(localTitle)
            NowPlayingState(
                active = true,
                playing = enginePlaying,
                title = title,
                artist = artist,
                album = null,
                artUrl = null,
                localArt = localArt,
                volume = engineVolume,
                canSkip = false,
            )
        }
    }

    companion object {
        /**
         * Split an ICY/tag "Artist - Title" string on the FIRST " - " (artist left, title right).
         * No separator -> the whole string is the title, artist null. Blank/null -> (null, null).
         */
        fun parseIcy(raw: String?): Pair<String?, String?> {
            val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null to null
            val idx = s.indexOf(" - ")
            if (idx < 0) return null to s
            val artist = s.substring(0, idx).trim().takeIf { it.isNotBlank() }
            val title = s.substring(idx + 3).trim().takeIf { it.isNotBlank() } ?: s
            return artist to title
        }
    }
}
```

### 1.4 — Run the store tests

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.media.NowPlayingStoreTest"
```
Expected: BUILD SUCCESSFUL, all NowPlayingStoreTest cases pass.

### 1.5 — Full gate + commit

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

```
git add app/src/main/java/com/rar/echodash/media/NowPlayingStore.kt \
        app/src/test/java/com/rar/echodash/media/NowPlayingStoreTest.kt
git commit -m "feat(media): add NowPlayingStore merge/precedence core with ICY parsing

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

# Task 2 — MediaSettings config + web Media card (pure JVM + assets)

Adds the companion-entity config field (so EntityHub watches it automatically) and the config-page
picker. Config logic is fully tested.

## Files

- **MODIFY** `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
  - Add `MediaSettings` data class after `VoiceSettings` (after line 99).
  - `DashConfig` data class: add `media` field (currently lines 102–110).
  - `referencedEntityIds()`: add the companion id (currently lines 112–121).
  - `clamped()`: add `media = media.clamped(),` to the `copy(...)` (currently the `voice =
    voice.clamped(),` line at 172).
- **MODIFY** `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` (append new @Test methods).
- **MODIFY** `app/src/main/assets/config/index.html` (add a Media section after the entities
  section, lines 97–111).
- **MODIFY** `app/src/main/assets/config/app.js` (add `renderMedia()`, call it from `render()` at
  lines 270–276).

## Interfaces

- **Produces:** `@Serializable data class MediaSettings(val companionEntity: String? = null)` with
  `fun clamped(): MediaSettings`; `DashConfig.media: MediaSettings`.
- **Consumes:** existing `entityPicker` / `labeledRow` / `subhead` helpers; existing config PUT/GET
  routes (no ConfigServer change — the spec confirms the entity registry list already reaches the
  page via `/api/entities`, `ConfigServer.kt:55`, so the `media_player` picker is populated by the
  same shared datalist as every other picker).

### 2.1 — Add `MediaSettings` in `DashConfig.kt`

Immediately after the `VoiceSettings` class (after its closing brace at line 99, before the
`DashConfig` doc comment at line 101) insert:

```kotlin
@Serializable
data class MediaSettings(
    val companionEntity: String? = null,
) {
    /** Trim the companion entity id; blank -> null (unconfigured). */
    fun clamped(): MediaSettings = copy(companionEntity = companionEntity?.trim()?.ifBlank { null })
}
```

### 2.2 — Add `media` to the `DashConfig` data class

The current declaration (lines 102–110) is:

```kotlin
@Serializable
data class DashConfig(
    val version: Int = 1,
    val panels: Panels = Panels(),
    val entities: Entities = Entities(),
    val home: HomeSettings = HomeSettings(),
    val panelOptions: PanelOptions = PanelOptions(),
    val voice: VoiceSettings = VoiceSettings(),
) {
```

Change to add `media` (absent in old JSON → default, thanks to `ignoreUnknownKeys` +
`encodeDefaults`):

```kotlin
@Serializable
data class DashConfig(
    val version: Int = 1,
    val panels: Panels = Panels(),
    val entities: Entities = Entities(),
    val home: HomeSettings = HomeSettings(),
    val panelOptions: PanelOptions = PanelOptions(),
    val voice: VoiceSettings = VoiceSettings(),
    val media: MediaSettings = MediaSettings(),
) {
```

### 2.3 — Watch the companion entity via `referencedEntityIds()`

The current body (lines 112–121) ends:

```kotlin
        entities.cameras.forEach { c -> c.entity?.let { add(it) } }
        entities.doorbells.forEach { d -> d.trigger?.let { add(it) } }
    }.distinct()
```

Add the companion id before `.distinct()`:

```kotlin
        entities.cameras.forEach { c -> c.entity?.let { add(it) } }
        entities.doorbells.forEach { d -> d.trigger?.let { add(it) } }
        media.companionEntity?.let { add(it) }
    }.distinct()
```

EntityHub subscribes exactly `config.referencedEntityIds()` (`EntityHub.kt:64,73,118`), so adding
the id here makes the hub watch the companion `media_player` with no EntityHub.kt change — the same
mechanism as the AQI sensor.

### 2.4 — Clamp `media` in `DashConfig.clamped()`

The current tail of the `copy(...)` (line 172) is:

```kotlin
            voice = voice.clamped(),
        )
```

Change to append `media`:

```kotlin
            voice = voice.clamped(),
            media = media.clamped(),
        )
```

### 2.5 — Append config tests to `DashConfigTest.kt`

Add these methods just before the closing brace of the class (after line 265):

```kotlin
    @Test
    fun mediaDefaultsToNoCompanion() {
        assertEquals(null, DashConfig().media.companionEntity)
        // absent from JSON -> default, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1}""")
        assertEquals(null, cfg.media.companionEntity)
    }

    @Test
    fun mediaRoundTrips() {
        val cfg = DashConfig(media = MediaSettings(companionEntity = "media_player.ma_echo"))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("media_player.ma_echo", decodeConfig(text).media.companionEntity)
    }

    @Test
    fun clampedTrimsBlankCompanionToNull() {
        assertEquals(null, DashConfig(media = MediaSettings(companionEntity = "  ")).clamped().media.companionEntity)
        assertEquals(null, DashConfig(media = MediaSettings(companionEntity = "")).clamped().media.companionEntity)
        assertEquals("media_player.x",
            DashConfig(media = MediaSettings(companionEntity = "  media_player.x  ")).clamped().media.companionEntity)
    }

    @Test
    fun referencedEntityIdsIncludesCompanionMediaPlayer() {
        val cfg = DashConfig(
            entities = Entities(tempSensor = "sensor.t"),
            media = MediaSettings(companionEntity = "media_player.ma_echo"),
        )
        assertEquals(listOf("sensor.t", "media_player.ma_echo"), cfg.referencedEntityIds())
    }
```

### 2.6 — Add the Media section to `index.html`

Insert a new `<section>` immediately after the entities section's closing `</section>` (line 111,
before the home section at line 113):

```html
      <section id="media-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M10.2 8.3 16 12l-5.8 3.7Z" fill="currentColor" stroke="none"/></svg>
          </span>
          <div class="card-titles">
            <h2>Media</h2>
            <p>Album art and track info for on-device playback.</p>
          </div>
        </div>
        <div id="media"></div>
      </section>
```

### 2.7 — Add `renderMedia()` and call it in `app.js`

In `render()` (lines 270–276), add the media render call. Change:

```javascript
function render() {
  renderPanels();
  renderEntities();
  renderHome();
  renderOptions();
  renderVoice();
}
```

to:

```javascript
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderHome();
  renderOptions();
  renderVoice();
}
```

Then add `renderMedia()` immediately after `renderEntities()`'s closing brace (after line 378,
before `renderLightGroup` at line 380):

```javascript
function renderMedia() {
  const host = document.getElementById("media");
  clear(host);
  if (!config.media) config.media = { companionEntity: null };
  const m = config.media;
  // Same populated picker pattern as the AQI sensor: a shared media_player datalist; blank -> null.
  host.appendChild(labeledRow("Companion media player",
    entityPicker(["media_player"], m.companionEntity, v => m.companionEntity = v)));
  host.appendChild(el("div", "muted",
    "The HA media player entity that mirrors this device (pick your Music Assistant player for the Echo) — enables album art, track info, and next/previous."));
}
```

Note: `entityPicker(...)`'s `onChange` already maps a blank input to `null`
(`app.js:234`), so clearing the field unsets the companion. There is no JVM test harness for the
config page (consistent with the rest of the page); it is verified on-device.

### 2.8 — Run config tests

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"
```
Expected: BUILD SUCCESSFUL, the four new `media*`/`referenced*` tests pass with the existing suite.

### 2.9 — Full gate + commit

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

```
git add app/src/main/java/com/rar/echodash/config/DashConfig.kt \
        app/src/test/java/com/rar/echodash/config/DashConfigTest.kt \
        app/src/main/assets/config/index.html \
        app/src/main/assets/config/app.js
git commit -m "feat(config): add media companion-entity setting and config-page picker

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

# Task 3 — Local metadata callback + MediaBridge forwarding + App wiring

Adds `onMeta` to the engine, forwards engine state and local metadata from MediaBridge into the
store, and feeds the companion entity from EntityHub. MediaBridge↔store forwarding is pure JVM and
tested; ExoPlayerEngine and App.kt are compile-gated.

## Files

- **MODIFY** `app/src/main/java/com/rar/echodash/vaca/MediaBridge.kt`
  - `MediaEngine` interface (lines 17–24): add `onMeta` and `onEnded`.
  - `MediaBridge` constructor (lines 37–40): inject `nowPlaying`.
  - init + handleAction + applySettings: forward into the store.
- **MODIFY** `app/src/main/java/com/rar/echodash/vaca/ExoPlayerEngine.kt`
  - add `onMeta` override + `onMediaMetadataChanged` listener.
- **MODIFY** `app/src/test/java/com/rar/echodash/vaca/MediaBridgeTest.kt`
  - `FakeEngine` gains `onMeta`; construct with a `NowPlayingStore`; add forwarding tests.
- **MODIFY** `app/src/main/java/com/rar/echodash/App.kt`
  - `AppDeps`: add `nowPlaying`; pass it into `MediaBridge`.
  - Dashboard composable: feed the companion entity into the store.

## Interfaces

- **Produces:** `MediaEngine.onMeta: ((String?, ByteArray?) -> Unit)?`;
  `MediaEngine.onEnded: (() -> Unit)?` (player error or natural end — deactivates the store);
  `MediaBridge(engine, nowPlaying, sendStatus)`.
- **Consumes:** `NowPlayingStore` (Task 1); `EntityHub.entities` (`EntityHub.kt:35`);
  `config.media.companionEntity` (Task 2); `MediaMetadata.title`/`artworkData` (media3 1.4.1).

### 3.1 — Update `MediaBridgeTest.kt` first (TDD)

`FakeEngine` must satisfy the new interface member, and the bridge now takes a store. Add the import
at the top of the test file with the other imports (after line 2, `import kotlinx.serialization.json.Json`):

```kotlin
import com.rar.echodash.media.NowPlayingStore
```

Replace the `FakeEngine` class (lines 15–25) with (adds the `onMeta` override):

```kotlin
    private class FakeEngine : MediaEngine {
        val calls = mutableListOf<String>()
        private var _volume = -1f
        val volume get() = _volume
        override var onPlayingChanged: ((Boolean) -> Unit)? = null
        override var onMeta: ((String?, ByteArray?) -> Unit)? = null
        override var onEnded: (() -> Unit)? = null
        override fun play(url: String) { calls += "play:$url" }
        override fun resume() { calls += "resume" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun setVolume(fraction: Float) { _volume = fraction; calls += "volume:$fraction" }
    }
```

Every existing test constructs the bridge as `MediaBridge(engine) {}` (or `MediaBridge(engine)
{ status = it }`). Update each of those constructions to pass a store as the new second argument:

- In `playMediaAppliesVolumeThenPlays`, `transportActionsMapToEngine`,
  `duckingScalesVolumeAndRestores`, `musicVolumeSettingSetsBaseVolume`,
  `nonMediaActionsReturnFalseUntouched`, `uiStateTracksPlayNowPlayingVolumeAndStop`: change
  `val bridge = MediaBridge(engine) {}` to `val bridge = MediaBridge(engine, NowPlayingStore()) {}`.
- In `reportsPlayingStatus`: change `MediaBridge(engine) { status = it }` to
  `MediaBridge(engine, NowPlayingStore()) { status = it }`.

Add these two forwarding tests before the class's closing brace (after line 109):

```kotlin
    @Test
    fun forwardsEngineAndLocalMetaIntoStore() {
        val engine = FakeEngine()
        val store = NowPlayingStore()
        val bridge = MediaBridge(engine, store) {}
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":80}"""))
        engine.onPlayingChanged!!.invoke(true)
        engine.onMeta!!.invoke("Artist - Title", null)
        val v = store.state.value
        assertTrue("play-media makes the store active", v.active)
        assertTrue(v.playing)
        assertEquals(80, v.volume)
        assertEquals("Title", v.title)
        assertEquals("Artist", v.artist)
    }

    @Test
    fun stopDeactivatesStoreKeepingVolume() {
        val engine = FakeEngine()
        val store = NowPlayingStore()
        val bridge = MediaBridge(engine, store) {}
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":60}"""))
        engine.onPlayingChanged!!.invoke(true)
        engine.onMeta!!.invoke("Some Title", null)
        bridge.handleAction("stop", null)
        val v = store.state.value
        assertFalse(v.active)
        assertFalse(v.playing)
        assertEquals(null, v.title)
        assertEquals(60, v.volume)
    }

    @Test
    fun engineEndedOrErrorDeactivatesStore() {
        val engine = FakeEngine()
        val store = NowPlayingStore()
        val bridge = MediaBridge(engine, store) {}
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":70}"""))
        engine.onPlayingChanged!!.invoke(true)
        engine.onMeta!!.invoke("Artist - Title", null)
        engine.onEnded!!.invoke()
        val v = store.state.value
        assertFalse("track end or player error must dismiss the takeover", v.active)
        assertEquals(null, v.title)
        assertEquals("Nothing playing", bridge.ui.value.nowPlaying)
    }
```

(`assertFalse`, `assertTrue`, `assertEquals` are already imported at lines 8–10.)

### 3.2 — Add `onMeta` to the `MediaEngine` interface

The current interface (lines 17–24) is:

```kotlin
/** Playback engine abstraction over ExoPlayer. Calls may arrive on any thread. */
interface MediaEngine {
    fun play(url: String)
    fun resume()
    fun pause()
    fun stop()
    fun setVolume(fraction: Float)
    var onPlayingChanged: ((Boolean) -> Unit)?
}
```

Change to:

```kotlin
/** Playback engine abstraction over ExoPlayer. Calls may arrive on any thread. */
interface MediaEngine {
    fun play(url: String)
    fun resume()
    fun pause()
    fun stop()
    fun setVolume(fraction: Float)
    var onPlayingChanged: ((Boolean) -> Unit)?

    /** Local metadata callback: ICY StreamTitle (or tag title) and embedded artwork bytes. */
    var onMeta: ((title: String?, artworkData: ByteArray?) -> Unit)?

    /** Playback reached a terminal state on its own: player error or natural end of the media. */
    var onEnded: (() -> Unit)?
}
```

### 3.3 — Inject the store and forward from `MediaBridge`

Change the constructor + add the import. Add the import with the others at the top (after line 5,
`import kotlinx.coroutines.flow.update`):

```kotlin
import com.rar.echodash.media.NowPlayingStore
```

Change the class header (lines 37–40) from:

```kotlin
class MediaBridge(
    private val engine: MediaEngine,
    private val sendStatus: (JsonObject) -> Unit,
) {
    private var volumePercent = 90 // HA media player default volume_level 0.9
    private var duckingVolume = 1  // 1..10 scale, integration default
    private var ducked = false
```

to:

```kotlin
class MediaBridge(
    private val engine: MediaEngine,
    private val nowPlaying: NowPlayingStore,
    private val sendStatus: (JsonObject) -> Unit,
) {
    private var volumePercent = 90 // HA media player default volume_level 0.9
    private var duckingVolume = 1  // 1..10 scale, integration default
    private var ducked = false
    private var active = false   // engine has media loaded (play-media until stop/error)
    private var playing = false  // mirrors the engine isPlaying callback

    /** Push the current engine snapshot into the NowPlayingStore. */
    private fun pushEngine() = nowPlaying.onEngine(active, playing, volumePercent)
```

Change the `init` block (lines 48–55) from:

```kotlin
    init {
        engine.onPlayingChanged = { playing ->
            _ui.update { it.copy(playing = playing) }
            sendStatus(buildJsonObject {
                putJsonObject("media_player") { put("playing", playing) }
            })
        }
    }
```

to (rename the lambda param to avoid shadowing the new field, forward into the store, and wire
`onMeta`):

```kotlin
    init {
        engine.onPlayingChanged = { isPlaying ->
            playing = isPlaying
            _ui.update { it.copy(playing = isPlaying) }
            sendStatus(buildJsonObject {
                putJsonObject("media_player") { put("playing", isPlaying) }
            })
            pushEngine()
        }
        engine.onMeta = { title, artworkData -> nowPlaying.onLocalMeta(title, artworkData) }
        // Player error or natural end-of-media: without this the home takeover would stay up
        // forever showing a dead player (spec: engine error -> active=false -> takeover dismisses).
        engine.onEnded = {
            active = false
            playing = false
            _ui.update { it.copy(playing = false, nowPlaying = "Nothing playing") }
            pushEngine()
        }
    }
```

Change the `play-media` arm (lines 59–70) from:

```kotlin
        "play-media" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            val url = payloadUrl(payload)
            if (url != null) {
                engine.play(url)
                _ui.update { it.copy(nowPlaying = url, volume = volumePercent) }
            } else {
                _ui.update { it.copy(volume = volumePercent) }
            }
            true
        }
```

to:

```kotlin
        "play-media" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            val url = payloadUrl(payload)
            if (url != null) {
                active = true
                engine.play(url)
                _ui.update { it.copy(nowPlaying = url, volume = volumePercent) }
            } else {
                _ui.update { it.copy(volume = volumePercent) }
            }
            pushEngine()
            true
        }
```

Change the `play` arm (lines 71–77) from:

```kotlin
        "play" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            engine.resume()
            _ui.update { it.copy(volume = volumePercent) }
            true
        }
```

to:

```kotlin
        "play" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            engine.resume()
            _ui.update { it.copy(volume = volumePercent) }
            pushEngine()
            true
        }
```

Change the `stop` arm (lines 79–83) from:

```kotlin
        "stop" -> {
            engine.stop()
            _ui.update { it.copy(playing = false, nowPlaying = "Nothing playing") }
            true
        }
```

to:

```kotlin
        "stop" -> {
            active = false
            playing = false
            engine.stop()
            _ui.update { it.copy(playing = false, nowPlaying = "Nothing playing") }
            pushEngine()
            true
        }
```

Change the `set-volume` arm (lines 84–88) from:

```kotlin
        "set-volume" -> {
            payloadVolume(payload)?.let { volumePercent = it; applyVolume() }
            _ui.update { it.copy(volume = volumePercent) }
            true
        }
```

to:

```kotlin
        "set-volume" -> {
            payloadVolume(payload)?.let { volumePercent = it; applyVolume() }
            _ui.update { it.copy(volume = volumePercent) }
            pushEngine()
            true
        }
```

Change `applySettings` (lines 92–103), whose tail is:

```kotlin
        if (changed) { applyVolume(); _ui.update { it.copy(volume = volumePercent) } }
    }
```

to:

```kotlin
        if (changed) { applyVolume(); _ui.update { it.copy(volume = volumePercent) }; pushEngine() }
    }
```

(`setDucked` changes only the engine fraction, not `volumePercent`, so the store's volume is
unaffected — no `pushEngine()` there.)

### 3.4 — Emit local metadata from `ExoPlayerEngine`

In `ExoPlayerEngine.kt`, add the media3 import with the others (after line 6,
`import androidx.media3.common.MediaItem`):

```kotlin
import androidx.media3.common.MediaMetadata
```

Add the `onMeta` override next to `onPlayingChanged` (line 14). Change:

```kotlin
    private val main = Handler(Looper.getMainLooper())
    override var onPlayingChanged: ((Boolean) -> Unit)? = null
```

to:

```kotlin
    private val main = Handler(Looper.getMainLooper())
    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onMeta: ((String?, ByteArray?) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null
```

Add the metadata callback to the listener. Change the listener block (lines 17–24) from:

```kotlin
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged?.invoke(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlayingChanged?.invoke(false)
            }
        })
```

to:

```kotlin
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged?.invoke(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlayingChanged?.invoke(false)
                onEnded?.invoke()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // media3 surfaces ICY StreamTitle as title; embedded tag art as artworkData.
                onMeta?.invoke(mediaMetadata.title?.toString(), mediaMetadata.artworkData)
            }
        })
```

### 3.5 — Wire the store into `AppDeps` and feed the companion entity

In `App.kt`, add the import with the other `com.rar.echodash.*` imports (after line 47,
`import com.rar.echodash.vaca.MediaBridge`):

```kotlin
import com.rar.echodash.media.NowPlayingStore
```

Change the media engine/bridge block (lines 151–154) from:

```kotlin
    private val mediaEngine = ExoPlayerEngine(appContext)
    val media = MediaBridge(mediaEngine) { status ->
        scope.launch { vaca.sendStatus(status) }
    }
```

to:

```kotlin
    private val mediaEngine = ExoPlayerEngine(appContext)
    val nowPlaying = NowPlayingStore()
    val media = MediaBridge(mediaEngine, nowPlaying) { status ->
        scope.launch { vaca.sendStatus(status) }
    }
```

In the Dashboard composable, feed the companion entity into the store. Immediately after the
`config` collection (line 322, `val config by deps.configStore.config.collectAsStateWithLifecycle()`)
add a state collection, and add a feeding effect after the idle-timer effects. Concretely, insert
after line 322:

```kotlin
                    val nowPlayingState by deps.nowPlaying.state.collectAsStateWithLifecycle()
```

Then insert this effect immediately after the existing
`LaunchedEffect(idleTimer, view) { idleTimer.onViewChanged(view == DashView.HOME) }` (line 332):

```kotlin
                    // Feed the companion media_player entity (config-driven watched set) into the
                    // store. Null when unconfigured or not yet loaded -> local metadata fallback.
                    LaunchedEffect(entities, config.media.companionEntity) {
                        deps.nowPlaying.onEntity(config.media.companionEntity?.let { entities[it] })
                    }
```

(`nowPlayingState` is consumed by Tasks 5 and 6; it is declared here so the store's flow is
collected within the Dashboard lifecycle from this task forward. It compiles unused now — Kotlin
does not error on an unused local `val`.)

### 3.6 — Run the media tests

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.vaca.MediaBridgeTest" --tests "com.rar.echodash.media.NowPlayingStoreTest"
```
Expected: BUILD SUCCESSFUL; the three new `forwards*`/`stopDeactivates*`/`engineEnded*` tests pass
with the existing MediaBridge suite (all constructions now carry a store).

### 3.7 — Full gate + commit

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL (compiles the ExoPlayerEngine + App.kt Android changes).

```
git add app/src/main/java/com/rar/echodash/vaca/MediaBridge.kt \
        app/src/main/java/com/rar/echodash/vaca/ExoPlayerEngine.kt \
        app/src/test/java/com/rar/echodash/vaca/MediaBridgeTest.kt \
        app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat(media): forward engine + ICY/tag metadata and companion entity into NowPlayingStore

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

# Task 4 — ArtFetcher + URL resolution (pure fn tested, fetcher compile-gated)

Adds album-art fetching/decoding with a manual downsample blur, plus the pure URL resolver (tested).

## Files

- **NEW** `app/src/main/java/com/rar/echodash/media/ArtUrl.kt` (pure JVM).
- **NEW** `app/src/test/java/com/rar/echodash/media/ArtUrlTest.kt`.
- **NEW** `app/src/main/java/com/rar/echodash/media/ArtFetcher.kt` (Android; compile-gated).
- **MODIFY** `app/src/main/java/com/rar/echodash/App.kt`
  - `AppDeps`: add `artFetcher`; start it against `nowPlaying.state` in the `init` block (lines
    238–240).

## Interfaces

- **Produces:** `fun resolveArtUrl(raw: String?, baseUrl: String?): String?`;
  `data class ArtBitmaps(val sharp: ImageBitmap, val blurred: ImageBitmap)`;
  `class ArtFetcher(scope, http, baseUrl, token)` with `val art: StateFlow<ArtBitmaps?>` and
  `fun start(source: StateFlow<NowPlayingState>)`.
- **Consumes:** `NowPlayingState.artUrl/localArt/active` (Task 1); `settings.baseUrl`
  (`SettingsStore.kt:9`); `auth.validAccessToken()` (`AuthManager.kt:48`); `OkHttpClient`
  (`AppDeps.client`, `App.kt:85`).

### 4.1 — Write `ArtUrlTest.kt` first (TDD)

Create `app/src/test/java/com/rar/echodash/media/ArtUrlTest.kt`:

```kotlin
package com.rar.echodash.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtUrlTest {

    @Test
    fun relativeIsPrefixedWithBase() {
        assertEquals("http://ha:8123/api/x", resolveArtUrl("/api/x", "http://ha:8123"))
        // trailing slash on base is trimmed, not doubled
        assertEquals("http://ha:8123/api/x", resolveArtUrl("/api/x", "http://ha:8123/"))
    }

    @Test
    fun relativeWithoutLeadingSlashStillJoins() {
        assertEquals("http://ha:8123/x.jpg", resolveArtUrl("x.jpg", "http://ha:8123"))
    }

    @Test
    fun absoluteHttpPassesThrough() {
        assertEquals("http://cdn/a.jpg", resolveArtUrl("http://cdn/a.jpg", "http://ha:8123"))
        assertEquals("https://cdn/a.jpg", resolveArtUrl("https://cdn/a.jpg", null))
    }

    @Test
    fun nullOrBlankRawReturnsNull() {
        assertNull(resolveArtUrl(null, "http://ha:8123"))
        assertNull(resolveArtUrl("   ", "http://ha:8123"))
    }

    @Test
    fun relativeWithNullBaseReturnsNull() {
        assertNull(resolveArtUrl("/api/x", null))
    }
}
```

### 4.2 — Write `ArtUrl.kt`

Create `app/src/main/java/com/rar/echodash/media/ArtUrl.kt`:

```kotlin
package com.rar.echodash.media

/**
 * Resolve a raw HA entity_picture string to a fetchable absolute URL. Absolute http(s) URLs pass
 * through; a relative path is joined onto [baseUrl] (leading slash optional, trailing slash on the
 * base trimmed). Returns null when [raw] is blank/null, or when a relative path has no base to join
 * against. Pure JVM.
 */
fun resolveArtUrl(raw: String?, baseUrl: String?): String? {
    val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (s.startsWith("http://") || s.startsWith("https://")) return s
    val base = baseUrl?.trim()?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: return null
    return if (s.startsWith("/")) "$base$s" else "$base/$s"
}
```

### 4.3 — Run the resolver tests

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.media.ArtUrlTest"
```
Expected: BUILD SUCCESSFUL, all five cases pass.

### 4.4 — Write `ArtFetcher.kt`

There is **no JVM unit test** for this file: `android.graphics.Bitmap`/`BitmapFactory` have no
plain-JVM implementation and the constraints bar Robolectric. It is compile-gated by `assembleDebug`
and verified on-device. Create `app/src/main/java/com/rar/echodash/media/ArtFetcher.kt`:

```kotlin
package com.rar.echodash.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

/** A sharp album-art bitmap plus a tiny blurred copy for the full-screen background. */
data class ArtBitmaps(val sharp: ImageBitmap, val blurred: ImageBitmap)

/**
 * Produces album art for the current NowPlayingState. Fetches entity_picture over OkHttp with a
 * Bearer header (entity_picture URLs are same-origin to HA and safe to send the token to), or
 * decodes embedded ID3 artwork bytes. Keeps ONE current bitmap in memory keyed by URL/bytes-hash:
 * re-emitting the same key is a no-op; a new key cancels any in-flight fetch and replaces the art.
 * No disk cache. Failures (HTTP error, decode failure) resolve to null (a placeholder shows); logged
 * at warn, never crashes, never retry-loops (the next metadata change naturally retries).
 *
 * Blur is MANUAL: the Echo is Android 11 (API 30) where Compose Modifier.blur is a silent no-op.
 * The background bitmap is decoded/scaled to 24x12 and the GPU bilinear-upscales it under a dark
 * scrim (applied by the UI). Sharp art is decoded downsampled to <= 480 px on the long edge.
 */
class ArtFetcher(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val baseUrl: () -> String?,
    private val token: suspend () -> String?,
) {
    private val _art = MutableStateFlow<ArtBitmaps?>(null)
    val art: StateFlow<ArtBitmaps?> = _art

    private var currentKey: String? = null
    private var job: Job? = null

    /** Collect [source] and keep [art] in sync with the current track's artwork. */
    fun start(source: StateFlow<NowPlayingState>) {
        scope.launch { source.collect { onState(it) } }
    }

    private fun onState(st: NowPlayingState) {
        val resolvedUrl = resolveArtUrl(st.artUrl, baseUrl())
        val localArt = st.localArt
        val key = when {
            !st.active -> null
            resolvedUrl != null -> "url:$resolvedUrl"
            localArt != null -> "bytes:${localArt.contentHashCode()}"
            else -> null
        }
        if (key == currentKey) return
        currentKey = key
        job?.cancel()
        if (key == null) { _art.value = null; return }
        job = scope.launch(Dispatchers.IO) {
            val bytes = if (resolvedUrl != null) fetch(resolvedUrl) else localArt
            val bmps = bytes?.let { decode(it) }
            if (isActive) _art.value = bmps
        }
    }

    private suspend fun fetch(url: String): ByteArray? =
        try {
            val builder = Request.Builder().url(url)
            token()?.let { builder.header("Authorization", "Bearer $it") }
            http.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "art fetch failed: $url", e)
            null
        }

    private fun decode(bytes: ByteArray): ArtBitmaps? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longEdge > 0 && longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (decoded == null) null else {
                val sharp = downscaleLongEdge(decoded, MAX_EDGE)
                val blurred = Bitmap.createScaledBitmap(sharp, BLUR_W, BLUR_H, true)
                ArtBitmaps(sharp.asImageBitmap(), blurred.asImageBitmap())
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "art decode failed", e)
            null
        }

    /** Scale [bmp] so its long edge is at most [maxEdge]; returns [bmp] unchanged when already small. */
    private fun downscaleLongEdge(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / longEdge
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private companion object {
        const val TAG = "ArtFetcher"
        const val MAX_EDGE = 480 // sharp art card is ~360 px tall; screen is 960x480
        const val BLUR_W = 24
        const val BLUR_H = 12
    }
}
```

### 4.5 — Construct and start `ArtFetcher` in `AppDeps`

In `App.kt`, add `artFetcher` next to `nowPlaying`. Change the block just edited in Task 3
(lines 151–155 after Task 3) from:

```kotlin
    private val mediaEngine = ExoPlayerEngine(appContext)
    val nowPlaying = NowPlayingStore()
    val media = MediaBridge(mediaEngine, nowPlaying) { status ->
        scope.launch { vaca.sendStatus(status) }
    }
```

to:

```kotlin
    private val mediaEngine = ExoPlayerEngine(appContext)
    val nowPlaying = NowPlayingStore()
    val artFetcher = ArtFetcher(
        scope = scope,
        http = client,
        baseUrl = { settings.baseUrl },
        token = { runCatching { auth.validAccessToken() }.getOrNull() },
    )
    val media = MediaBridge(mediaEngine, nowPlaying) { status ->
        scope.launch { vaca.sendStatus(status) }
    }
```

Add the import with the other `com.rar.echodash.*` imports (after the `NowPlayingStore` import added
in Task 3):

```kotlin
import com.rar.echodash.media.ArtFetcher
```

Start the fetcher from the existing `init` block (lines 238–240). Change:

```kotlin
    init {
        kiosk.sendFeedback = { s -> scope.launch { vaca.sendSettingsFeedback(s) } }
    }
```

to:

```kotlin
    init {
        kiosk.sendFeedback = { s -> scope.launch { vaca.sendSettingsFeedback(s) } }
        artFetcher.start(nowPlaying.state)
    }
```

### 4.6 — Full gate + commit

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL (ArtUrlTest passes; ArtFetcher + App changes compile).

```
git add app/src/main/java/com/rar/echodash/media/ArtUrl.kt \
        app/src/test/java/com/rar/echodash/media/ArtUrlTest.kt \
        app/src/main/java/com/rar/echodash/media/ArtFetcher.kt \
        app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat(media): add ArtFetcher (fetch/decode/manual-blur) and URL resolver

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

# Task 5 — Media panel upgrade

Rewrites the Media panel into a real player and threads NowPlayingState + art + next/prev through
DashboardShell and App. Compile-gated (Compose); verified on-device.

## Files

- **MODIFY** `app/src/main/java/com/rar/echodash/ui/panels/MediaPanel.kt` (rewrite).
- **MODIFY** `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`
  - signature (lines 36–58): replace `mediaUi` with `nowPlaying` + `art`; add `onMediaNext` /
    `onMediaPrev`.
  - MEDIA branch (line 112): new call.
  - imports.
- **MODIFY** `app/src/main/java/com/rar/echodash/App.kt`
  - remove the `mediaUi` collection (line 321); collect `art`; pass new args + next/prev to
    `DashboardShell` (lines 361–409).

## Interfaces

- **Produces:** `MediaPanel(state: NowPlayingState, art: ArtBitmaps?, onPlay, onPause, onStop,
  onNext, onPrev, onVolume)`.
- **Consumes:** `NowPlayingState` (Task 1); `ArtBitmaps` + `ArtFetcher.art` (Task 4);
  `EntityHub.callService` (`EntityHub.kt:124`); `config.media.companionEntity` (Task 2).

### 5.1 — Rewrite `MediaPanel.kt`

Replace the whole file with:

```kotlin
package com.rar.echodash.ui.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState

@Composable
fun MediaPanel(
    state: NowPlayingState,
    art: ArtBitmaps?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onVolume: (Int) -> Unit,
) {
    PanelSurface {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("On this device", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            if (!state.active) {
                Text("Nothing playing", color = Color.White, fontSize = 22.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF11151F)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (art != null) {
                            Image(art.sharp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            state.title ?: "Playing",
                            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        val sub = listOfNotNull(state.artist, state.album).joinToString(" — ")
                        if (sub.isNotBlank()) {
                            Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.canSkip) TransportButton(Icons.Outlined.SkipPrevious) { onPrev() }
                    TransportButton(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) {
                        if (state.playing) onPause() else onPlay()
                    }
                    TransportButton(Icons.Outlined.Stop) { onStop() }
                    if (state.canSkip) TransportButton(Icons.Outlined.SkipNext) { onNext() }
                }
            }
            var slider by remember(state.volume) { mutableFloatStateOf(state.volume.toFloat()) }
            Text("Volume ${slider.toInt()}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { onVolume(slider.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}
```

### 5.2 — Update `DashboardShell.kt` signature + MEDIA branch

Add the imports with the others (after line 31, `import com.rar.echodash.vaca.MediaUiState` — which
becomes unused and MUST be removed):

- Remove `import com.rar.echodash.vaca.MediaUiState` (line 31).
- Add:

```kotlin
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState
```

Change the signature (lines 44–51). Replace:

```kotlin
    photos: List<File>,
    mediaUi: MediaUiState,
    onToggle: (String) -> Unit,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
    onMediaPlay: () -> Unit,
    onMediaPause: () -> Unit,
    onMediaStop: () -> Unit,
    onMediaVolume: (Int) -> Unit,
```

with:

```kotlin
    photos: List<File>,
    nowPlaying: NowPlayingState,
    art: ArtBitmaps?,
    onToggle: (String) -> Unit,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
    onMediaPlay: () -> Unit,
    onMediaPause: () -> Unit,
    onMediaStop: () -> Unit,
    onMediaNext: () -> Unit,
    onMediaPrev: () -> Unit,
    onMediaVolume: (Int) -> Unit,
```

Change the MEDIA branch (line 112) from:

```kotlin
                DashView.MEDIA -> MediaPanel(mediaUi, onMediaPlay, onMediaPause, onMediaStop, onMediaVolume)
```

to:

```kotlin
                DashView.MEDIA -> MediaPanel(
                    nowPlaying, art, onMediaPlay, onMediaPause, onMediaStop,
                    onMediaNext, onMediaPrev, onMediaVolume,
                )
```

### 5.3 — Update the `DashboardShell` call-site in `App.kt`

Remove the `mediaUi` collection (line 321):

```kotlin
                    val mediaUi by deps.media.ui.collectAsStateWithLifecycle()
```

Delete that line. (`nowPlayingState` is already collected — added in Task 3.5.) Add the art
collection right after it (so, after the `config` line 322 / the `nowPlayingState` line added in
Task 3):

```kotlin
                    val art by deps.artFetcher.art.collectAsStateWithLifecycle()
```

In the `DashboardShell(...)` call, replace the `mediaUi = mediaUi,` argument (line 372) with the two
new arguments:

```kotlin
                        nowPlaying = nowPlayingState,
                        art = art,
```

And replace the transport argument block (lines 388–395):

```kotlin
                        onMediaPlay = { deps.mainScope.launch { deps.media.handleAction("play", null) } },
                        onMediaPause = { deps.mainScope.launch { deps.media.handleAction("pause", null) } },
                        onMediaStop = { deps.mainScope.launch { deps.media.handleAction("stop", null) } },
                        onMediaVolume = { vol ->
                            deps.mainScope.launch {
                                deps.media.handleAction("set-volume", buildJsonObject { put("volume", vol) })
                            }
                        },
```

with (adding next/prev via `callService` gated on the companion entity; the panel/takeover only
render these buttons when `canSkip`, and `canSkip` implies a configured companion, but the
`?.let` guard is defensive):

```kotlin
                        onMediaPlay = { deps.mainScope.launch { deps.media.handleAction("play", null) } },
                        onMediaPause = { deps.mainScope.launch { deps.media.handleAction("pause", null) } },
                        onMediaStop = { deps.mainScope.launch { deps.media.handleAction("stop", null) } },
                        onMediaNext = {
                            config.media.companionEntity?.let {
                                deps.entityHub.callService("media_player", "media_next_track", entityId = it)
                            }
                        },
                        onMediaPrev = {
                            config.media.companionEntity?.let {
                                deps.entityHub.callService("media_player", "media_previous_track", entityId = it)
                            }
                        },
                        onMediaVolume = { vol ->
                            deps.mainScope.launch {
                                deps.media.handleAction("set-volume", buildJsonObject { put("volume", vol) })
                            }
                        },
```

### 5.4 — Full gate + commit

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. On-device: cast/stream to the Echo → Media panel shows art (or the
music-note placeholder), title, artist — album, transport (⏮/⏭ only when the companion is configured
and driving), and the volume slider; with nothing playing it shows "Nothing playing".

```
git add app/src/main/java/com/rar/echodash/ui/panels/MediaPanel.kt \
        app/src/main/java/com/rar/echodash/ui/DashboardShell.kt \
        app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat(media): upgrade Media panel to album art + full transport

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

# Task 6 — Home-screen takeover + screen-wake loop + slideshow pause

Adds the now-playing home backdrop, pauses the slideshow while active, and holds the screen awake
while playing. Compile-gated (Compose); verified on-device.

## Files

- **NEW** `app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt`.
- **MODIFY** `app/src/main/java/com/rar/echodash/ui/HomeView.kt`
  - `DuskBackground` (line 74): make `internal` (reused by NowPlayingHome, same package).
  - `HomeView` signature (lines 113–125): add now-playing params.
  - slideshow `LaunchedEffect` (lines 133–138): pause while active.
  - backdrop (lines 156–157): Crossfade photo backdrop ↔ takeover.
  - imports.
- **MODIFY** `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`
  - HOME branch (lines 88–99): pass the now-playing params to `HomeView`.
- **MODIFY** `app/src/main/java/com/rar/echodash/App.kt`
  - Dashboard composable: add the screen-wake re-arm loop keyed on `nowPlayingState.playing`.

## Interfaces

- **Produces:** `NowPlayingHome(state, art, onPlay, onPause, onNext, onPrev, onVolume)`.
- **Consumes:** `NowPlayingState`/`ArtBitmaps` (Tasks 1/4); `deps.kiosk.onUserInteraction()`
  (`App.kt:354`); the transport lambdas already threaded through `DashboardShell` (Task 5).

### 6.1 — Make `DuskBackground` reusable

In `HomeView.kt`, change (line 74):

```kotlin
@Composable
private fun DuskBackground() {
```

to:

```kotlin
@Composable
internal fun DuskBackground() {
```

### 6.2 — Write `NowPlayingHome.kt`

Create `app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt`:

```kotlin
package com.rar.echodash.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState

/**
 * Home-screen now-playing backdrop: blurred art (or dusk gradient) full-screen under a dark scrim,
 * a sharp art card on the right, and title/artist-album/transport/volume on the left-center. Sits
 * BELOW the clock/pills/overlays (those are separate layers drawn above in HomeView). The transport
 * omits stop (the panel keeps stop); prev/next show only when [NowPlayingState.canSkip].
 */
@Composable
fun NowPlayingHome(
    state: NowPlayingState,
    art: ArtBitmaps?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onVolume: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (art != null) {
            Image(art.blurred, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            DuskBackground()
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        // Sharp art card, right side, clear of the pills row.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
                .size(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF11151F)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(art.sharp, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Outlined.MusicNote, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(96.dp))
            }
        }

        // Left-center: metadata + transport + volume, held clear of the art card.
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp, end = 440.dp)
                .widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                state.title ?: "Playing",
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(state.artist, state.album).joinToString(" — ")
            if (sub.isNotBlank()) {
                Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.canSkip) NpTransportButton(Icons.Outlined.SkipPrevious) { onPrev() }
                NpTransportButton(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) {
                    if (state.playing) onPause() else onPlay()
                }
                if (state.canSkip) NpTransportButton(Icons.Outlined.SkipNext) { onNext() }
            }
            var slider by remember(state.volume) { mutableFloatStateOf(state.volume.toFloat()) }
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { onVolume(slider.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
        }
    }
}

@Composable
private fun NpTransportButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}
```

### 6.3 — Integrate the takeover into `HomeView.kt`

Add imports with the others (after line 42, `import androidx.compose.ui.graphics.asImageBitmap`):

```kotlin
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState
```

Change the `HomeView` signature (lines 113–125). Replace:

```kotlin
@Composable
fun HomeView(
    photos: List<File>,
    slideshowSeconds: Int,
    pill: WeatherPill?,
    aqi: AqiPill?,
    clockFormat: ClockFormat,
    connState: ConnState,
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

with:

```kotlin
@Composable
fun HomeView(
    photos: List<File>,
    slideshowSeconds: Int,
    pill: WeatherPill?,
    aqi: AqiPill?,
    clockFormat: ClockFormat,
    connState: ConnState,
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    nowPlaying: NowPlayingState,
    art: ArtBitmaps?,
    onMediaPlay: () -> Unit,
    onMediaPause: () -> Unit,
    onMediaNext: () -> Unit,
    onMediaPrev: () -> Unit,
    onMediaVolume: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Change the slideshow `LaunchedEffect` (lines 133–138) to pause while the takeover is active (keyed
on `nowPlaying.active` so it re-arms when playback stops). Replace:

```kotlin
    // Keying on photoIndex re-arms the countdown, so a manual swipe restarts the timer.
    LaunchedEffect(order, photoIndex, slideshowSeconds) {
        if (order.size > 1) {
            delay(slideshowSeconds * 1000L)
            photoIndex += 1
        }
    }
```

with:

```kotlin
    // Keying on photoIndex re-arms the countdown, so a manual swipe restarts the timer. Keying on
    // nowPlaying.active pauses advancing while the now-playing takeover is showing and resumes after.
    LaunchedEffect(order, photoIndex, slideshowSeconds, nowPlaying.active) {
        if (order.size > 1 && !nowPlaying.active) {
            delay(slideshowSeconds * 1000L)
            photoIndex += 1
        }
    }
```

Change the backdrop (lines 156–157). Replace:

```kotlin
        PhotoBackdrop(order.getOrNull(Math.floorMod(photoIndex, maxOf(order.size, 1))))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
```

with a Crossfade between the photo backdrop and the now-playing takeover (the takeover is the Home
backdrop layer only; clock/pills/menu below stay above it, unchanged):

```kotlin
        Crossfade(targetState = nowPlaying.active, animationSpec = tween(1000), label = "home-backdrop") { active ->
            if (active) {
                NowPlayingHome(
                    state = nowPlaying,
                    art = art,
                    onPlay = onMediaPlay,
                    onPause = onMediaPause,
                    onNext = onMediaNext,
                    onPrev = onMediaPrev,
                    onVolume = onMediaVolume,
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    PhotoBackdrop(order.getOrNull(Math.floorMod(photoIndex, maxOf(order.size, 1))))
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                }
            }
        }
```

(`Crossfade` and `tween` are already imported — lines 7–8.)

### 6.4 — Pass the now-playing params from `DashboardShell.kt`

Change the HOME branch's `HomeView(...)` call (lines 88–99). Replace:

```kotlin
                    HomeView(
                        photos = if (config.home.slideshowEnabled) photos else emptyList(),
                        slideshowSeconds = config.home.slideshowSeconds,
                        pill = pill,
                        aqi = aqi,
                        clockFormat = config.home.clockFormat,
                        connState = connState,
                        configUrl = configUrl,
                        configPin = configPin,
                        onLogout = onLogout,
                    )
```

with:

```kotlin
                    HomeView(
                        photos = if (config.home.slideshowEnabled) photos else emptyList(),
                        slideshowSeconds = config.home.slideshowSeconds,
                        pill = pill,
                        aqi = aqi,
                        clockFormat = config.home.clockFormat,
                        connState = connState,
                        configUrl = configUrl,
                        configPin = configPin,
                        onLogout = onLogout,
                        nowPlaying = nowPlaying,
                        art = art,
                        onMediaPlay = onMediaPlay,
                        onMediaPause = onMediaPause,
                        onMediaNext = onMediaNext,
                        onMediaPrev = onMediaPrev,
                        onMediaVolume = onMediaVolume,
                    )
```

(These params already exist on `DashboardShell` from Task 5 — no new shell params.)

### 6.5 — Add the screen-wake re-arm loop in `App.kt`

While `playing` (NOT merely active/paused), poke the kiosk screen-wake every 5 s — same pattern as
the doorbell popup (`App.kt:351`) and the timer alert (`App.kt:424`), but **only**
`deps.kiosk.onUserInteraction()`; do NOT poke `idleTimer` (idle-return still bounces panels back to
Home, which shows the player). Insert this effect after the companion-entity feeding effect added in
Task 3.5 (i.e., right after that `LaunchedEffect(entities, config.media.companionEntity) { ... }`):

```kotlin
                    // Hold the screen awake while music is actively playing (not while paused, so a
                    // paused player still lets the backlight sleep). Only wakes the screen; the
                    // idle-return timer is intentionally NOT poked, so a panel still returns Home.
                    LaunchedEffect(nowPlayingState.playing) {
                        if (nowPlayingState.playing) {
                            while (true) {
                                deps.kiosk.onUserInteraction()
                                delay(5_000)
                            }
                        }
                    }
```

(`delay` and `LaunchedEffect` are already imported.)

### 6.6 — Full gate + commit

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. On-device verification:
- Start playback → Home crossfades from photos to the now-playing layout (blurred art bg, sharp art
  card right, controls left); clock bottom-left and weather/AQI pills top-left unchanged.
- No art configured (plain radio, no companion) → dusk-gradient background + music-note placeholder,
  ⏮/⏭ hidden.
- Pause → takeover stays (⏯ shows play); screen is allowed to sleep. Resume → screen stays awake.
- Stop → crossfades back to the photo slideshow, which resumes advancing.
- During playback the screen never times out; leaving a panel idle still returns to Home (which
  shows the player).

```
git add app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt \
        app/src/main/java/com/rar/echodash/ui/HomeView.kt \
        app/src/main/java/com/rar/echodash/ui/DashboardShell.kt \
        app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat(media): home now-playing takeover, slideshow pause, screen-wake while playing

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

## Self-review

**Spec coverage (every section → task):**
- Hybrid merge + precedence + master-gate (§"metadata problem", §1) → Task 1 (NowPlayingStore) with
  tests for entity-beats-local, blank/null fallback, art follows text, active gate.
- ICY `"Artist - Title"` split on first separator (§1, §Testing) → Task 1 `parseIcy` + tests
  (separator / none / multiple).
- `canSkip` = companion configured AND `media_title` non-blank (§1) → Task 1 + tests.
- stop clears metadata but keeps volume; paused keeps active (§1, §Testing) → Task 1 + tests.
- Local metadata callback `onMeta` from `onMediaMetadataChanged` (§2) → Task 3 (ExoPlayerEngine +
  MediaBridge forwarding, JVM-tested).
- ArtFetcher: entity_picture fetch with Bearer, relative-URL resolution, one-bitmap cache keyed by
  URL/bytes-hash, ≤480 px decode, manual 24×12 blur, `StateFlow<ArtBitmaps?>`, failures → null no
  retry-loop (§3) → Task 4; URL resolution unit-tested (§Testing) via `resolveArtUrl`.
- Controls: local play/pause/stop/volume unchanged; next/prev via
  `callService("media_player","media_next_track"/"media_previous_track", target=companion)` gated on
  `canSkip` (§4) → Task 5 (App call-site + panel) and Task 6 (takeover).
- Media panel: art square, title, artist—album, transport (⏮ ⏯ ⏹ ⏭ with ⏮/⏭ on canSkip), volume,
  "On this device" header, "Nothing playing" when inactive (§5) → Task 5.
- Home takeover: gate on `active` (shows while paused, ⏯ reflects `playing`), Crossfade replacing
  PhotoBackdrop, blurred bg + scrim + dusk fallback, right art card, left controls, clock/pills
  unchanged, slideshow pause keyed on `active`, z-order = backdrop only (§6) → Task 6.
- Screen wake ONLY while `playing`, ONLY `onUserInteraction()`, NOT idle timer (§7) → Task 6.
- Config `MediaSettings(companionEntity)` versioned/default-when-absent; EntityHub watch via
  referenced set; web Media card picker + help text (§8) → Task 2.
- Error handling: entity unavailable → null → local fallback, canSkip false (Task 1); art failure →
  placeholder no retry (Task 4); callService log-and-continue (existing EntityHub); engine error OR
  natural end-of-media → `MediaEngine.onEnded` → MediaBridge clears active/playing → takeover
  dismisses (Task 3, tested by `engineEndedOrErrorDeactivatesStore`).
- Testing list (NowPlayingStore precedence/ICY/canSkip/stop, art URL resolution, MediaSettings
  round-trip/default, no new ConfigServer route) → Tasks 1/2/4; ConfigServer needs no change
  (registry list already served, confirmed `ConfigServer.kt:55`).

**Placeholder scan:** every code block is complete — full file contents for new files, exact
old→new snippets with surrounding context for edits, full test bodies, exact imports and anchors. No
"TBD"/"similar to"/ellipsis. (The one deliberately-labelled non-code stub in Task 3.1 is explicitly
called out to be deleted.)

**Type consistency across tasks:** `NowPlayingState` fields identical in the contract, store, panel,
takeover, and ArtFetcher; `NowPlayingStore.onEngine(Boolean,Boolean,Int)` /
`onLocalMeta(String?,ByteArray?)` / `onEntity(EntityState?)`; `MediaEngine.onMeta:
((String?,ByteArray?)->Unit)?`; `MediaBridge(engine, nowPlaying, sendStatus)`;
`resolveArtUrl(String?,String?):String?`; `ArtBitmaps(sharp:ImageBitmap, blurred:ImageBitmap)`;
`ArtFetcher(scope,http,baseUrl,token)` with `start(StateFlow<NowPlayingState>)`;
`MediaSettings(companionEntity:String?)`. Signatures match every call-site.

## Ambiguities resolved

1. **Where the config picker lives.** The spec says "Media card" and "same population pattern as the
   AQI sensor picker." Those are slightly different (AQI lives in the Entities card). Resolved: a new
   dedicated "Media" `<section>` in index.html + `renderMedia()` in app.js, using the existing
   `entityPicker(["media_player"], ...)` (the AQI picker's exact mechanism — shared datalist,
   blank→null). This honors both "Media card" and the AQI picker pattern, and needs no ConfigServer
   change (the `/api/entities` registry already feeds the datalist).
2. **EntityHub watch plumbing.** Rather than add code to EntityHub, the companion id is added to
   `DashConfig.referencedEntityIds()`, which is exactly the config-driven watched set EntityHub
   subscribes (`EntityHub.kt:64,73,118`) — identical to how the AQI sensor is watched. No EntityHub.kt
   edit needed; the spec's "same path as the AQI sensor" is satisfied structurally.
3. **`canSkip` when inactive.** Spec defines `canSkip` purely as "companion configured AND
   media_title non-blank," with no active-gate. Kept literal (canSkip can be true while active=false),
   which is harmless because the panel/takeover only render skip buttons within the active UI. Noted
   in NowPlayingStore.
4. **Stale local metadata across sessions.** `onEngine(active=false)` also clears `localTitle`/
   `localArt` so a new session starts clean before its first `onMediaMetadataChanged`, avoiding a
   flash of the previous track's title. Covered by `stopClearsMetadataButKeepsVolume`.
5. **Bearer on entity_picture.** Spec says use the "same client/token plumbing as
   AndroidPhotoDownloader." AndroidPhotoDownloader actually authenticates via a signed query param
   and sends NO header; but `entity_picture` for media_player proxies generally needs the token.
   Resolved per the spec's explicit instruction to send `Authorization: Bearer` (same-origin, safe) —
   ArtFetcher takes a `token: suspend () -> String?` bound to `auth.validAccessToken()`; a pre-signed
   URL simply ignores the extra header. The reused "plumbing" is the OkHttpClient + baseUrl lambda,
   as with AndroidPhotoDownloader.
6. **MediaUiState retirement.** The panel now consumes `NowPlayingState`, so `DashboardShell` and
   App stop consuming `MediaBridge.ui`. `MediaBridge._ui`/`MediaUiState` are kept (MediaBridgeTest
   asserts `bridge.ui`, and VACA-side behavior is unchanged) but no longer drive the UI.
7. **Stop button in the takeover.** Spec's home transport row is "⏮ ⏯ ⏭" (no stop); the panel keeps
   ⏹. Implemented exactly so — NowPlayingHome has no stop button.

## Could not verify in code

- **media3 1.4.1 `onMediaMetadataChanged` / `MediaMetadata.title` / `artworkData` runtime behavior.**
  The APIs exist and compile against 1.4.1 (confirmed by the pinned version and the spec's API notes),
  but whether a given ICY stream/Music Assistant source actually surfaces `StreamTitle` as
  `mediaMetadata.title` and embedded art as `artworkData` can only be confirmed on-device with real
  streams (Task 3/5/6 on-device checks).
- **Exact `entity_picture` shape from the user's Music Assistant player** (relative proxy path vs
  absolute, token query param vs needs-Bearer). `resolveArtUrl` + optional Bearer handle both forms,
  but the concrete URL is device/integration-specific and unverifiable from this repo.
- **On-device layout fit** (art card size 360 dp, left column `end = 440.dp`/`widthIn 460.dp` on the
  960×480 panel) is a considered estimate; final margins may need a nudge during on-device
  verification. No functional impact.
