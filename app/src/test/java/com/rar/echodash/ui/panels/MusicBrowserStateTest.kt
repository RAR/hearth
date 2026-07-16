package com.rar.echodash.ui.panels

import com.rar.echodash.sendspin.MaLibraryState
import com.rar.echodash.sendspin.musicassistant.MaTrack
import com.rar.echodash.sendspin.musicassistant.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the pure MaLibraryState/query/data -> browser-area content mapping. */
class MusicBrowserStateTest {

    private val connected = MaLibraryState.Connected("2.9.9")
    private val shelves = BrowserShelves(emptyList(), emptyList(), emptyList())
    private val someResults = SearchResults(
        tracks = listOf(MaTrack("t1", "Song", "Artist", null, null, "library://track/t1")),
    )

    @Test fun disabledMapsToSignInNotice() {
        assertEquals(
            BrowserContent.Notice("Sign in to Music Assistant on the config page."),
            browserContent(MaLibraryState.Disabled, "", null, null),
        )
    }

    @Test fun authFailedMapsToNotice() {
        assertEquals(
            BrowserContent.Notice("Music Assistant sign-in failed — sign in again on the config page."),
            browserContent(MaLibraryState.AuthFailed, "", shelves, null),
        )
    }

    @Test fun offlineMapsToNotice() {
        assertEquals(
            BrowserContent.Notice("Music Assistant offline — retrying…"),
            browserContent(MaLibraryState.Offline(3), "", shelves, null),
        )
    }

    @Test fun connectingMapsToLoading() {
        assertEquals(BrowserContent.Loading, browserContent(MaLibraryState.Connecting, "", null, null))
    }

    @Test fun connectedBlankQueryNullShelvesIsLoading() {
        assertEquals(BrowserContent.Loading, browserContent(connected, "", null, null))
    }

    @Test fun connectedShelvesPresentShowsShelves() {
        assertEquals(BrowserContent.Shelves(shelves), browserContent(connected, "", shelves, null))
    }

    @Test fun queryWithResultsShowsResults() {
        assertEquals(BrowserContent.Results(someResults), browserContent(connected, "ab", shelves, someResults))
    }

    @Test fun queryWithNullResultsIsLoading() {
        assertEquals(BrowserContent.Loading, browserContent(connected, "ab", shelves, null))
    }

    @Test fun oneCharQueryShowsShelves() {
        // Below the 2-char search threshold the shelves stay up — even stale results must not show.
        assertEquals(BrowserContent.Shelves(shelves), browserContent(connected, "a", shelves, someResults))
    }

    @Test fun whitespaceOnlyQueryCountsAsBlank() {
        assertEquals(BrowserContent.Shelves(shelves), browserContent(connected, "  a ", shelves, someResults))
    }
}
