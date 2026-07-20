package com.rar.hearth.sendspin.musicassistant

/**
 * Represents the full queue state including settings.
 */
data class MaQueueState(
    val items: List<MaQueueItem>,
    val currentIndex: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: String     // "off", "one", "all"
)
