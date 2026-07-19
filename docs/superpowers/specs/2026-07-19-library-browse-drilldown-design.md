# Library A-Z Browsing + Drill-In — Design (SendSpin sweep, Batch C)

**Date:** 2026-07-19
**Status:** Approved scope (user chose "A-Z tabs + drill-in"). API facts from
`.superpowers/sdd/ma-api-recon.md` §4; server MA 2.9.9.

## Goal

Real library browsing in the MEDIA view's MusicBrowser:

1. **Artists** and **Albums** A-Z lists alongside the existing shelves (Playlists / Radio /
   Recently played), reachable from a small tab row.
2. **Drill-in pages**: artist → their albums; album → its tracklist. Tap-to-play stays
   everywhere (tapping an album cell still plays the whole album); drill-in rides the
   existing long-press menu — "View album" on album items, "View artist" on artist items.

## API surface (confirmed, recon §4)

- `music/artists/library_items` / `music/albums/library_items` — args used: `limit`,
  `offset`, `order_by: "sort_name"`. Returns a page of Artist/Album objects (server default
  limit 500; no total-count in the response).
- `music/artists/artist_albums` `{item_id, provider_instance_id_or_domain}` → list[Album].
- `music/albums/album_tracks` `{item_id, provider_instance_id_or_domain}` → list[Track]
  (sorted disc/track).
- `MaArtist` carries `artistId` + `provider` (default "library"); `MaAlbum` carries
  `albumId` + `provider` — both drill calls need no extra fetch. Existing parsers
  (`parseArtistsArray`, `parseAlbumsArray`, `parseTracksArray`) are reused.

## Data flow

### MaCommandClient — four new methods (same try/log/Result shape as `getPlaylists`)

```kotlin
suspend fun getLibraryArtists(limit: Int = 200, offset: Int = 0): Result<List<MaArtist>>
suspend fun getLibraryAlbums(limit: Int = 200, offset: Int = 0): Result<List<MaAlbum>>
suspend fun getArtistAlbums(artistId: String, provider: String): Result<List<MaAlbum>>
suspend fun getAlbumTracks(albumId: String, provider: String): Result<List<MaTrack>>
```

`library_items` calls pass `order_by: "sort_name"`. Page size 200 (matches the queue
page convention; the server cap is higher but 200 rows is plenty per fetch on a kiosk).

### MaLibrary — four `withClient` wrappers

```kotlin
suspend fun libraryArtists(offset: Int = 0): Result<List<MaArtist>>
suspend fun libraryAlbums(offset: Int = 0): Result<List<MaAlbum>>
suspend fun artistAlbums(artist: MaArtist): Result<List<MaAlbum>>
suspend fun albumTracks(album: MaAlbum): Result<List<MaTrack>>
```

(The last two unpack `artistId`/`albumId` + `provider` themselves so the UI never touches
provider strings.)

## UI (MusicBrowser.kt)

### Navigation model

A single back-stack replaces the implicit two-state content:

```kotlin
sealed interface BrowserPage {
    data object Home : BrowserPage                                  // shelves (existing)
    data object Artists : BrowserPage                               // A-Z artists
    data object Albums : BrowserPage                                // A-Z albums
    data class ArtistDetail(val artist: MaArtist) : BrowserPage     // artist's albums
    data class AlbumDetail(val album: MaAlbum) : BrowserPage        // album tracklist
}
```

- `var pageStack by remember { mutableStateOf(listOf<BrowserPage>(BrowserPage.Home)) }`;
  current page = `pageStack.last()`; push on drill-in/tab, pop via a back chip.
- **Search keeps its existing override**: a query ≥2 chars shows `ResultsPane` regardless
  of the stack (clearing the query returns to the stack top). The queue overlay also stays
  as-is (drawn above everything).
- Pure helpers in `ui/model/` (new `BrowserNavModel.kt`): `pushPage(stack, page)` (no-op
  when pushing the current page), `popPage(stack)` (never pops Home),
  `tabTarget(stack)` — which of Home/Artists/Albums the tab row should highlight
  (detail pages highlight the tab they descend from: ArtistDetail → Artists,
  AlbumDetail → Albums unless reached from an artist... KEEP SIMPLE: highlight by the
  nearest root page in the stack). All plain-JVM tested.

### Tab row

A compact chip row at the top of the browser (above the shelves/search results, below the
existing search field): `Home · Artists · Albums` — text chips styled like the existing
queue "Clear" chip (small rounded, dark bg, lit label on the active tab with the
`#4FC3F7` accent). Tapping a tab resets the stack to `[Home, <tab>]` (or `[Home]`).

### A-Z pages (Artists / Albums)

- Vertical `LazyColumn` of rows: 44dp thumb (`MaThumbs`, same as search result rows),
  name, and for albums the artist subtitle — reusing the existing `ResultRow` visual
  language (the plan may extract a shared row composable if it stays byte-identical).
- Row primary actions differ by type: an **artist row tap drills in** to ArtistDetail
  (an artist has no single obvious "play"; drill-in is the useful primary), with the
  long-press menu still offering Play next / Add to queue / Start radio. An **album row
  tap plays the album** (consistent with search results today); its long-press menu
  gains "View album".
- Paging: fetch page 0 on first show; when the list scrolls to its end and the last fetch
  returned a full page (200), fetch the next offset and append. A trailing "Loading…" row
  while a page fetch is in flight. Fetch failure → existing `showError` toast; keep what
  loaded.
- Page state (`items`, `offset`, `exhausted`) lives in a `remember(page)` holder per page
  visit — leaving and returning refetches (matches the shelves' existing
  fetch-on-composition behavior; no global cache).

### Detail pages

- **ArtistDetail**: header row (back chip ← + artist name, 22sp) + album grid using the
  existing `MediaCell` shelf-cell composable in a `LazyVerticalGrid` (adaptive 128dp cells,
  same as shelf cell size). Cell tap = play album; long-press = existing menu + "View album".
- **AlbumDetail**: header row (back chip ← + album name + artist subtitle) + tracklist
  `LazyColumn`: track number, title, duration (m:ss, reuse `formatTrackTime`-style helper
  if one exists in scope, else minutes:seconds inline). Row tap = play that track
  (`playItem(track, PLAY)`); long-press = Play next / Add to queue / Start radio.
- Back chip: 32dp circle, `Icons.AutoMirrored.Outlined.ArrowBack`, pops the stack.

### Long-press menu additions

`EnqueueMenu` gains two optional entries (below "Start radio"):
- `onViewAlbum: (() -> Unit)? = null` — offered on ALBUM-type items everywhere they
  appear (shelves? albums don't appear in shelves today — search results, A-Z Albums,
  ArtistDetail grid).
- `onViewArtist: (() -> Unit)? = null` — offered on ARTIST-type items (search results,
  A-Z Artists rows — though the artist row's tap already drills, the menu entry keeps the
  gesture language uniform).
Both push the matching detail page. Items lacking the concrete type (a `MaLibraryItem`
that isn't backed by `MaAlbum`/`MaArtist`) simply don't get the entry — the drill-in needs
the typed object (id + provider), so the menu wiring passes the typed items where available.

## Degradation

| Condition | Behavior |
|---|---|
| MA socket down | Browser already shows its connect/sign-in states; tabs render but page fetches surface the standard error toast |
| Empty library page | Empty list + the existing "nothing here" treatment (plain empty column is acceptable) |
| Drill fetch fails | Error toast; page shows empty list with the back chip (user backs out) |
| Search active | Results override any page (existing behavior), stack preserved underneath |
| Queue overlay | Unchanged, draws above all pages |
| openQueueSignal (up-next tap) | Unchanged — opens the queue overlay regardless of page |

## Out of scope (deliberate)

- Tracks A-Z tab (search covers it; a 10k-row list has no kiosk value).
- Track → album drill (MaTrack lacks a provider field; not worth extending for v1).
- Favorites filter/tab, genre browsing, provider filters, letter-index fast-scroll bar.
- Global caching of pages across visits.

## Testing (plain-JVM JUnit4)

- `BrowserNavModel`: push dedup (same page no-op), pop never removes Home, tab targeting
  for nested stacks (Home / [Home,Artists] / [Home,Artists,ArtistDetail] /
  [Home,Albums,AlbumDetail]).
- MaLibraryTest (FakeTransport): `libraryArtists` sends `music/artists/library_items`
  w/ limit 200 offset 0 order_by sort_name; `artistAlbums` sends
  `music/artists/artist_albums` w/ item_id + provider from the MaArtist;
  `albumTracks` likewise for albums; `libraryAlbums` offset passthrough (offset = 200).
- Parser reuse means no new parse tests (existing `parseArtistsArray` etc. pins stand).

## Live-verify checklist

1. Artists tab → A-Z list loads; scroll to bottom of a >200-artist library → next page
   appends.
2. Tap an artist → album grid; tap an album cell → plays it; long-press → "View album" →
   tracklist; tap a track → plays that track.
3. Albums tab → A-Z with artist subtitles; long-press → View album works.
4. Search overrides any page; clearing returns to the same page; "View artist" from a
   search result artist works.
5. Back chip walks the stack to Home; tabs highlight correctly on detail pages.
6. Queue overlay + up-next jump still work from every page.
