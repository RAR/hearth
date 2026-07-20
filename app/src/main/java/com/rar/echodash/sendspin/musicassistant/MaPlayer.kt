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
