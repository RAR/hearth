package com.rar.hearth.ui.model

import com.rar.hearth.sendspin.musicassistant.MaQueueItem
import com.rar.hearth.sendspin.musicassistant.MaQueueState

/**
 * Next repeat mode in the takeover's cycle: off -> all -> one -> off. A null or unrecognized
 * current value restarts at "all" (the engine's optimistic default), so a first tap always turns
 * repeat on rather than no-opping.
 */
fun nextRepeatMode(cur: String?): String = when (cur) {
    "all" -> "one"
    "one" -> "off"
    else -> "all" // null, "off", or any unknown value
}

/**
 * The queue item that plays after the current one, or null when the queue can't answer
 * "what's next": no item flagged [MaQueueItem.isCurrentItem], the current item is last, or the
 * list is empty. (With a 200-item page a current index past the page end also yields null --
 * acceptable, matching the queue pane's existing page behavior.)
 */
fun upNextOf(q: MaQueueState): MaQueueItem? {
    val idx = q.items.indexOfFirst { it.isCurrentItem }
    if (idx < 0 || idx >= q.items.lastIndex) return null
    return q.items[idx + 1]
}

/** The queue's current item (the one flagged [MaQueueItem.isCurrentItem]), or null. */
fun currentItemOf(q: MaQueueState): MaQueueItem? = q.items.firstOrNull { it.isCurrentItem }

/** Whether a heart tap should add a favorite or remove an existing one. */
sealed interface FavoriteAction {
    /** Add the current item to favorites (the server resolves which item); idempotent. */
    data object Add : FavoriteAction
    /** Remove an already-favorited library item, targeted by type + library id. */
    data class Remove(val mediaType: String, val libraryItemId: String) : FavoriteAction
}

/**
 * Decide add-vs-remove for a heart tap on [item]. Remove only when the item is known-favorited
 * AND carries a library id to target (favorite == true && mediaItemId != null), defaulting a
 * missing media_type to "track". Every other case — unknown favorite, favorited-but-no-id, or a
 * null item — falls back to [FavoriteAction.Add], which the server resolves and is idempotent.
 */
fun favoriteToggleAction(item: MaQueueItem?): FavoriteAction =
    if (item?.favorite == true && item.mediaItemId != null)
        FavoriteAction.Remove(item.mediaType ?: "track", item.mediaItemId)
    else
        FavoriteAction.Add
