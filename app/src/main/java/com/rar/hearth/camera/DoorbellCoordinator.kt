package com.rar.hearth.camera

import com.rar.hearth.config.DoorbellConfig
import com.rar.hearth.ha.EntityState

/** What the coordinator asks the UI to do. */
sealed interface PopupCommand {
    /** Show [cameraName] until [untilMs] (epoch millis). A later Show extends/switches the popup. */
    data class Show(val cameraName: String, val untilMs: Long) : PopupCommand
}

/** UI-side popup state; identical fields to [PopupCommand.Show], kept separate as the render model. */
data class DoorbellPopup(val cameraName: String, val untilMs: Long)

/**
 * Pure rising-edge detector over the subscribe_entities state map. Fed the full state map on every
 * update. A popup fires only on an observed transition of a configured trigger:
 * - binary_sensor.* triggers: off -> on (any non-"on" -> "on").
 * - event.* triggers: any state change (the state is a timestamp that changes per fire).
 * The first state seen for a trigger is recorded but never fires (no phantom popup at app start).
 * When a trigger disappears from the map (reconnect clears it), its remembered state is dropped, so
 * its next appearance is again a first-seen and cannot fire.
 */
class DoorbellCoordinator {
    private val seen = HashMap<String, String>()

    fun onStates(
        doorbells: List<DoorbellConfig>,
        states: Map<String, EntityState>,
        popupSeconds: Int,
        nowMs: Long,
    ): PopupCommand? {
        // Drop remembered triggers no longer present (handles the reconnect emptyMap reset).
        seen.keys.retainAll { states.containsKey(it) }

        var command: PopupCommand? = null
        for (db in doorbells) {
            val trigger = db.trigger ?: continue
            val current = states[trigger]?.state ?: continue
            val prev = seen[trigger]
            seen[trigger] = current
            if (prev == null) continue // first-seen: record only
            val rising =
                if (trigger.substringBefore('.') == "event") current != prev
                else current == "on" && prev != "on"
            if (rising && command == null) {
                command = PopupCommand.Show(db.camera, nowMs + popupSeconds * 1000L)
            }
        }
        return command
    }
}
