package com.rar.echodash.ui.model

import com.rar.echodash.voice.TimerChip

/**
 * One timer ready to render in the takeover panel. [label] is already resolved (local rename >
 * voice name > duration fallback); [remainingSec] and [active] pass straight through from the chip.
 */
data class TakeoverTimer(
    val id: String,
    val label: String,
    val remainingSec: Long,
    val active: Boolean,
)

/**
 * Kitchen timer takeover state. Maps live [TimerChip]s to display rows and holds the transient local
 * state — per-id renames and the ids the user has dismissed. Plain Kotlin (no Android) so it is
 * JUnit-testable; the composable owns recomposition. Timers are ephemeral: once every timer is gone
 * the renames and dismissals reset for a fresh session.
 */
class TimerTakeoverModel {
    private val renames = HashMap<String, String>()
    private val dismissedIds = HashSet<String>()
    private var currentIds: List<String> = emptyList()

    /**
     * Map the live chips to display rows. Prunes rename/dismiss entries for ids no longer present;
     * when no timers remain, resets the transient state. Call on every timers emission.
     */
    fun update(timers: List<TimerChip>): List<TakeoverTimer> {
        if (timers.isEmpty()) {
            renames.clear()
            dismissedIds.clear()
            currentIds = emptyList()
            return emptyList()
        }
        val ids = timers.map { it.id }
        val idSet = ids.toSet()
        renames.keys.retainAll(idSet)
        dismissedIds.retainAll(idSet)
        currentIds = ids
        return timers.map { chip ->
            TakeoverTimer(chip.id, labelFor(chip), chip.remainingSec, chip.active)
        }
    }

    /** Record every currently-known id as dismissed; the takeover hides until a NEW id arrives. */
    fun dismiss() {
        dismissedIds.addAll(currentIds)
    }

    /** Set a local display label for [id]. A blank label clears the rename (back to the default). */
    fun rename(id: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) renames.remove(id) else renames[id] = trimmed
    }

    /** Visible while at least one known timer has not been dismissed (a new id re-shows it). */
    val visible: Boolean
        get() = currentIds.any { it !in dismissedIds }

    private fun labelFor(chip: TimerChip): String =
        renames[chip.id] ?: chip.name.trim().ifBlank { defaultTimerLabel(chip.remainingSec) }
}

/** Human fallback name for an unnamed timer, from its remaining time (e.g. 600 -> "10 min timer"). */
fun defaultTimerLabel(remainingSec: Long): String {
    val s = remainingSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 && m > 0 -> "$h hr $m min timer"
        h > 0 -> "$h hr timer"
        m > 0 -> "$m min timer"
        else -> "$sec sec timer"
    }
}

/** m:ss (or h:mm:ss past an hour) countdown — the shared TimerChips / takeover formatter. */
fun formatTimer(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}
