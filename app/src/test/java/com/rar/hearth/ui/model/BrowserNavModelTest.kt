package com.rar.hearth.ui.model

import com.rar.hearth.sendspin.musicassistant.MaAlbum
import com.rar.hearth.sendspin.musicassistant.MaArtist
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
