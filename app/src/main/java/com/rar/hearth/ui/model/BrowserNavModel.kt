package com.rar.hearth.ui.model

import com.rar.hearth.sendspin.musicassistant.MaAlbum
import com.rar.hearth.sendspin.musicassistant.MaArtist

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
