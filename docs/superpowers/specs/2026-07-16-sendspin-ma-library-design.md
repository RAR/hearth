# SendSpin MA Library Control — Design Spec (Sub-project B)

Date: 2026-07-16. Follow-up to `2026-07-15-sendspin-endpoint-design.md` (sub-project A), which deferred this: on-screen Music Assistant library browse/search/queue from the wall panel.

## Overview

Today the panel is a remote *speaker*: it plays and controls whatever something else queued, but picking music still needs a phone. Sub-project B adds a Music view to the dashboard so a panel can search the MA library, start playback on itself, and manage its upcoming queue — making it a standalone music device.

User-approved scope (2026-07-16): **search + quick picks**. A search box over the MA library plus shelves (playlists, radio, recently played), tap-to-play with play-next/add-to-queue options, and a simple upcoming-queue view with clear. No artist/album drill-down, no queue reorder.

## Global Constraints

- compileSdk/targetSdk 34, minSdk 28 — unchanged.
- No new dependencies. The vendored MA client uses Ktor WebSockets + kotlinx-serialization, both already in-tree from sub-project A.
- Plain-JVM JUnit4 tests only; the usual gate (`:app:testDebugUnitTest :app:assembleDebug`) before every commit.
- Echo Show 5 is 960×480 landscape with 1 GB RAM: all lists paged, art as small thumbnails, no resident library cache.

## Scope & Boundaries

- App-side only; the hearth HA integration is untouched. (Resolved at plan time: the browser lives inside the existing MEDIA view, whose `media` option is already in the integration's hard-coded `VIEW_OPTIONS` — no new view, no integration bump.)
- LAN plaintext WebSocket, same trust posture as sub-project A and the config server.
- Targets the panel's own playback. Commands resolve the panel's *effective* queue (`player_queues/get_active_queue` with our SendSpin client id), so if the user grouped the panel in MA, control correctly lands on the group queue. No group management UI.

## Reuse Strategy: Vendor the MA Client, Trimmed

Same convention as the engine: vendor from chrisuthe/SendSpinDroid @ `594251f` (MIT; NOTICE already covers the repo), rehome to `com.rar.echodash.sendspin.musicassistant`, document adaptations in AGENTS.md.

### Vendor — keep

- `transport/` (~1.1k lines): `MaWebSocketTransport` (connect → ServerInfo → auth handshake → connected), `MaCommandMultiplexer` (request/response correlation + server events), `MaApiTransport` interface, JSON helpers, exceptions.
- `MaCommandClient` — trimmed to the command surface v1 uses: `music/search`, `music/playlists/library_items`, `music/radios/library_items`, `music/recently_played_items`, `player_queues/get_active_queue`, `player_queues/play_media` (enqueue modes), `player_queues/get` + `items`, `player_queues/play_index`, `player_queues/clear`.
- `MaAuthHelper` — username/password → access-token exchange.
- Models actually referenced: `MaTrack`, `MaAlbum`, `MaArtist`, `MaPlaylist`, `MaRadio`, `MaQueueItem`, `MaQueueState`, `SearchResults`, `EnqueueMode`, plus whatever the kept client code requires to compile.
- Upstream tests covering kept code (e.g. `MaCommandClientSearchQueueTest`), adapted to our package.

### Drop

- Podcasts, audiobooks, playlist create/edit/delete, favorites add, group `set_members`/`ungroup`/`power`, `MaApiEndpoint`'s PROXY/REMOTE/WebRTC modes (we derive the URL locally), `MaSettingsProvider`/`MaSettings`, and the 1.2k-line app-side `MusicAssistant.kt` manager — Hearth writes its own thin manager instead.

## Architecture & Components

1. **`MaLibrary` (new Hearth glue, sibling of `SendspinEndpoint`)** — owns the API WebSocket lifecycle. Runs only when `sendspin.enabled` **and** an MA token exists. Derives the URL from the same server the audio path uses (mDNS-discovered or manual address host) at fixed port 8095, path `/ws`. Exposes StateFlows: connection state (NoToken / AuthFailed / Offline / Connected), shelves, search results, queue. Reconnect with sendspin-style backoff. API-socket failures never touch the audio path, and vice versa.
2. **`MusicBrowser` (new Compose UI inside the existing MEDIA view)** — `MediaPanel` becomes browser-on-top + compact now-playing strip at the bottom (reusing the transport callbacks it already receives); when SendSpin is disabled or signed out it renders exactly as today. Browser: search field on top; below, horizontal shelves: Playlists, Radio, Recently Played. (Favorites shelf dropped — resolved at plan time: the MA API reference has no favorites *listing* command, only add.) An active query replaces shelves with grouped results (tracks / albums / artists / playlists / radio as returned by `music/search`). Tap = play now (playlists/albums/artists play whole via their URI); long-press = play next / add to queue. A queue button opens the upcoming list: tap an item jumps to it (`play_index`), plus a clear-queue action. A ninth rail view was rejected: 9 × 52dp rail icons don't fit the Echo's 480px height, and MEDIA already carries the MusicNote icon.
3. **Takeover browse button** — small affordance on the now-playing takeover that switches the dashboard to the MEDIA view (`currentView.value = DashView.MEDIA`, the existing hoisted-state pattern).
4. **View select** — no change needed; `media` is already an option, so "show me the media view" voice switching reaches the browser as-is.

## Auth & Config

- Config page SendSpin card grows: MA username + password fields with a **Sign in** action. The device performs `MaAuthHelper.loginForToken` against the derived URL, stores **only** the access token (plus display name for the status line) in `config.json` under `sendspin.maToken`/`sendspin.maUser`; the password is used once and never persisted. Card shows signed-in status and a sign-out (clears token).
- Config export/import carries the token like other secrets (same handling as the HA token today).
- No token → Music view renders a pointer card: "Sign in to Music Assistant on the config page."

## Data Flow

View opened → `MaLibrary` fetches shelves (paged, e.g. limit 25) → user taps item → resolve effective queue id → `player_queues/play_media(queue_id, uri, enqueue)` → MA streams to the panel over the existing sub-project A path → takeover appears as it does today. Queue view: fetch on open; refresh on queue-changed events if the multiplexer already surfaces them, else poll every few seconds while visible. Shelf/search state is dropped when the view is hidden.

## Error Handling

- AuthFailed (expired/revoked token) → status surfaces on the Music view and config card; no retry-spam (backoff), sign-in again to fix.
- Offline → shelves replaced by an offline card with auto-retry; audio path unaffected.
- Command failures (play/enqueue/clear) → brief inline error on the view; logged under the SS.* tag family via the existing AppLog shim.
- OOM discipline: paged fetches only, thumbnail-size art, no bitmap caching beyond what the existing art-loading path already does.

## Testing

- Kept vendored tests (transport handshake, search/queue client) adapted to our package.
- New plain-JVM pins: `MaLibrary` state machine (no-token / auth-fail / connected / offline transitions), enqueue-mode mapping, effective-queue-id resolution falling back to own player id on error, shelf/search → UI-state mapping.
- Manual verification on both devices: sign-in from config page, search-and-play, enqueue-next ordering, queue jump/clear, grouped-panel case (commands land on group queue).

## Open Checks — all resolved 2026-07-16 during planning

1. View select options are hard-coded in the integration (`const.py` `VIEW_OPTIONS`) — but moot: the browser lives in the existing MEDIA view, so no option is added and the integration is untouched.
2. Takeover art uses `ArtFetcher` (custom OkHttp → BitmapFactory), but it attaches the HA bearer token to every request and is single-slot — not reusable for MA thumbnails. A small separate token-less thumb loader with a bounded cache is part of the plan. Upstream's client emits `imageUri` values already resolved to absolute image-proxy URLs, so thumbnails are plain GETs.
3. No favorites listing in the reference client (only `favorites/add_item`) → Favorites shelf dropped from v1.
4. `music/search` returns radios (`SearchResults.radios`, parsed from the `radio` result array) — radio appears in search results as designed.

## Risks & Deferred Items

- **MA API version drift.** The vendored client matches the MA version the reference app tracked; if the user's MA server has diverged, command payloads may need small fixes at live-verify time. Mitigation: the transport surfaces server version at handshake — log it.
- **Auth requirement variance.** If the user's MA build predates mandatory auth, sign-in may be unnecessary; the flow still works (login endpoint exists either way).
- Deferred to a later sub-project: artist/album drill-down, queue reorder/remove, playlist editing, favorites editing, group management UI.

## Out of Scope

- Everything in Deferred above; podcasts/audiobooks; WebRTC/proxy/remote access; MediaSession/Android Auto; HA-side anything (beyond the possible View select option bump); encryption.
