# Music Player + Home-Screen Now-Playing — Design

**Date:** 2026-07-12
**Status:** Approved by user (brainstorming complete)

## Goal

Turn the bare Media panel into a real music player — album art, track metadata, full transport —
and take over the home screen with an album-art now-playing layout while the device is playing.

Scope is **device playback only**: what the Echo itself plays through its VACA/ExoPlayer media
player. Other HA `media_player` entities are out of scope (no multi-source remote). Expected
music paths: Music Assistant streaming to the Echo, and HA media-browser casts (internet radio,
media-folder files).

## The metadata problem and the hybrid answer

The device player only ever receives a bare stream URL from HA (VACA `play-media` payload:
`url` + `volume`). Metadata comes from two places, merged:

1. **Local (ExoPlayer)** — media3 `Player.Listener.onMediaMetadataChanged`:
   - ICY `StreamTitle` for radio and Music Assistant streams → text like `"Artist - Title"`.
     Parse on the FIRST `" - "` separator: left = artist, right = title. No separator → whole
     string is the title, artist null.
   - Embedded ID3/tag artwork (`MediaMetadata.artworkData`) for plain file URLs.
2. **HA companion entity** — a new config field names the HA `media_player` entity that
   mirrors this device (the user picks their Music Assistant player entity for the Echo).
   EntityHub watches it; it carries `media_title`, `media_artist`, `media_album_name`, and
   `entity_picture` (real album art) while MA is driving.

**Precedence:** while the device engine is active, if the companion entity has a non-empty
`media_title` attribute, entity metadata (title/artist/album/art) wins; otherwise local
ICY/ID3 metadata is used. The device engine's state is always the master gate for "is the
player UI showing" — the entity never activates the UI by itself.

## Components

### 1. `voice`-style pure-JVM core: `NowPlayingStore` (new, `media/NowPlayingStore.kt`)

Merges the two inputs into one state; no Android imports (plain-JVM testable).

```kotlin
data class NowPlayingState(
    val active: Boolean = false,      // engine has media loaded (play() until stop()/error)
    val playing: Boolean = false,     // actually playing (false while paused)
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artUrl: String? = null,       // raw entity_picture attribute (may be relative), or null
    val localArt: ByteArray? = null,  // embedded ID3 artwork bytes, or null
    val volume: Int = 90,
    val canSkip: Boolean = false,     // show ⏮/⏭ (companion entity actively driving)
)
```

Inputs:
- `onEngine(active, playing, volume)` — from MediaBridge/ExoPlayerEngine.
- `onLocalMeta(icyOrTagTitle, artworkData)` — from ExoPlayerEngine metadata callback.
- `onEntity(EntityState?)` — from the EntityHub collector for the companion entity
  (null when unconfigured/unavailable).

Rules:
- `canSkip` = companion entity configured AND its `media_title` is non-blank.
- Art follows the same precedence as text: when entity metadata wins, art = `artUrl`
  (entity_picture) and `localArt` is ignored; otherwise art = `localArt`. The store holds the
  RAW `entity_picture` string; ArtFetcher resolves relative paths against the HA base URL.
- `stop()`/engine inactive clears title/artist/album/localArt but NOT volume.

### 2. Local metadata: `ExoPlayerEngine` grows a metadata callback

`var onMeta: ((title: String?, artworkData: ByteArray?) -> Unit)?` invoked from
`onMediaMetadataChanged` (media3 surfaces ICY StreamTitle as `mediaMetadata.title`; embedded
art as `artworkData`). MediaBridge forwards to NowPlayingStore. ICY parsing ("Artist - Title"
split) lives in NowPlayingStore (JVM-testable), not the engine.

### 3. Album art fetch: `media/ArtFetcher.kt` (Android)

- Input: art request = either `artUrl` (entity_picture) or `localArt` bytes.
- `entity_picture` fetch: OkHttp GET with the `Authorization: Bearer` header (same client/token
  plumbing as `AndroidPhotoDownloader`; entity_picture URLs from HA are same-origin and safe to
  send the token to). Resolve relative URLs against the HA base URL.
- Tiny cache: one current bitmap in memory, keyed by URL/bytes hash — re-emitting the same key
  is a no-op; a new key cancels any in-flight fetch and replaces the bitmap. No disk cache
  (art is small and re-fetchable; the device reboots rarely mid-song).
- Decode downsampled to ≤ 480 px on the long edge (screen is 960×480; the sharp art card is
  ~360 px tall).
- **Blur is manual:** the Echo is Android 11 (API 30) and Compose `Modifier.blur` is a silent
  no-op below API 31. Produce the blurred background by downscaling the art bitmap to ~24×12
  and letting the GPU upscale with bilinear filtering, under a dark scrim (~55% black). Both
  bitmaps (sharp + blurred) come out of ArtFetcher as one `ArtBitmaps(sharp, blurred)` value.
- Output: `StateFlow<ArtBitmaps?>` consumed by both the panel and the home takeover.
- Failures (HTTP error, decode failure) → state null (placeholder shown); log at warn, never
  crash, never retry-loop (next metadata change naturally retries).

### 4. Controls

- Play/pause/stop/volume: existing local MediaBridge paths, unchanged (HA status sync as today).
- Next/previous: `EntityHub.callService("media_player", "media_next_track" /
  "media_previous_track", target = companionEntity)`. Buttons render ONLY when
  `canSkip` is true — plain radio never shows dead buttons.

### 5. Media panel upgrade (`ui/panels/MediaPanel.kt`)

Replaces the raw-URL text. Layout: album art square on the left (sharp bitmap; music-note
icon placeholder when null), right column with title (bold, 1–2 lines ellipsized), artist —
album line (dimmed), transport row ⏮ ⏯ ⏹ ⏭ (existing `TransportButton` style; ⏮/⏭ only when
`canSkip`), volume slider as today. "On this device" header stays. When inactive: current
"Nothing playing" presentation.

### 6. Home-screen takeover (`ui/HomeView.kt` + new `ui/NowPlayingHome.kt`)

- Gate: `NowPlayingState.active` (shows while paused too; ⏯ reflects `playing`).
- While active: `PhotoBackdrop` is replaced (Crossfade) by the now-playing layer:
  - Background: blurred art bitmap stretched full-screen + dark scrim; dusk-gradient fallback
    when no art.
  - Right side: sharp art card (rounded corners, ~360 px tall, centered vertically with margin
    clear of the pills row).
  - Left-center: title (large), artist — album (dimmed), transport row ⏮ ⏯ ⏭ + volume slider
    (compact width).
  - Clock/date bottom-left and weather/AQI pills top-left render exactly as today (they are
    separate layers above the backdrop; unchanged).
- Slideshow: the photo-advance `LaunchedEffect` pauses while active (key on `active`); resumes
  and crossfades back to photos when playback stops.
- Z-order: the takeover is the Home backdrop layer, BELOW clock/pills and all app overlays
  (voice pill, timer chips, doorbell popup) — no overlay-ordering changes.

### 7. Screen wake while playing

While `playing` is true (NOT merely active/paused), a re-arm loop pokes the kiosk screen-wake
every 5 s — same pattern as the doorbell popup and timer alert, but **only**
`deps.kiosk.onUserInteraction()`; do NOT poke the idle-return timer. Result: the screen never
times out during music, and idle-return still bounces any panel back to Home, which shows the
player. Paused playback lets the screen sleep normally.

### 8. Config + web UI

- `DashConfig` gains `media: MediaSettings(companionEntity: String? = null)` (versioned like
  the other settings; absent → default).
- EntityHub's watched set includes `companionEntity` when set (config-driven watch already
  exists — same path as the AQI sensor).
- Web config Media card: "Companion media player" dropdown listing `media_player.*` entities
  from the registry (same population pattern as the AQI sensor picker) plus "None" (default).
  Help text: "The HA media player entity that mirrors this device (pick your Music Assistant
  player for the Echo) — enables album art, track info, and next/previous."

## Error handling

- Companion entity unavailable/removed → entity input null → local metadata fallback;
  `canSkip` false; no crash, no spinner.
- Art fetch failure → placeholder; no retry loop.
- `callService` failures already log-and-continue (existing EntityHub behavior).
- Engine error → `onPlayingChanged(false)` (existing) plus active=false → takeover dismisses.

## Testing (plain JVM, JUnit4 — no AudioTrack/Compose/ExoPlayer in JVM tests)

- NowPlayingStore: precedence (entity title beats local; falls back when entity blank/null),
  ICY "Artist - Title" parsing (separator, no separator, multiple " - "), canSkip rules,
  stop clears metadata but keeps volume, paused keeps active.
- Art URL resolution: relative entity_picture → base-URL-prefixed; absolute passthrough;
  null → null.
- Config: MediaSettings round-trip + default when absent.
- ConfigServer: entity list already served; no new routes expected. If the Media card needs a
  new payload field, cover it in ConfigServerTest.

## Constraints (binding, from project)

- Kotlin 2.1.0; compileSdk 34 (never bump); media3 pinned exactly 1.4.1; NanoHTTPD 2.3.1;
  **no new dependencies**.
- Device is API 30 (Android 11): no `Modifier.blur`/RenderEffect — manual downsample blur.
- Always build/test with `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q
  :app:testDebugUnitTest :app:assembleDebug`.
