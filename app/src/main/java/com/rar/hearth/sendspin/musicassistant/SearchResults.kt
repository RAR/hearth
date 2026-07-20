// Vendored from SendSpinDroid (see NOTICE); Hearth adaptations documented in AGENTS.md.
package com.rar.hearth.sendspin.musicassistant

/**
 * Aggregated search results from Music Assistant.
 *
 * Contains results grouped by media type. Each list may be empty if no
 * matches were found for that type, or if the type was filtered out.
 *
 * Hearth adaptation: the upstream `podcasts`/`audiobooks` result lists are
 * dropped — those media types (and their model classes) are out of scope for
 * the on-panel library browser, so `music/search` never requests them.
 */
data class SearchResults(
    val artists: List<MaArtist> = emptyList(),
    val albums: List<MaAlbum> = emptyList(),
    val tracks: List<MaTrack> = emptyList(),
    val playlists: List<MaPlaylist> = emptyList(),
    val radios: List<MaRadio> = emptyList()
) {
    /**
     * Check if all result lists are empty.
     */
    fun isEmpty(): Boolean =
        artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() &&
        playlists.isEmpty() && radios.isEmpty()

    /**
     * Get total count of all results.
     */
    fun totalCount(): Int =
        artists.size + albums.size + tracks.size + playlists.size + radios.size
}
