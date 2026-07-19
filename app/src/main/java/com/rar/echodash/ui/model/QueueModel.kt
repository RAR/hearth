package com.rar.echodash.ui.model

import com.rar.echodash.sendspin.musicassistant.MaQueueItem
import com.rar.echodash.sendspin.musicassistant.MaQueueState

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
