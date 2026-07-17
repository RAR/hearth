package com.rar.echodash.ui.model

import com.rar.echodash.config.QuickButtonConfig
import com.rar.echodash.ha.EntityState

/** Whether a quick button toggles a stateful entity or fires a stateless action. */
enum class QuickButtonKind { TOGGLE, PRESS }

/** Glyph family for a quick button, chosen from the entity domain. */
enum class QuickButtonIcon { LIGHT, SWITCH, RUN, SCENE }

/** One derived quick button ready to render. [isOn] is null for PRESS (stateless actions have no
 *  on/off state); [available] false dims the chip and disables its tap when the entity is missing
 *  or reports unavailable/unknown. */
data class QuickButton(
    val entityId: String,
    val label: String,
    val icon: QuickButtonIcon,
    val kind: QuickButtonKind,
    val isOn: Boolean?,
    val available: Boolean,
)

/** Action domains fire (PRESS); everything else toggles. homeassistant.toggle is domain-agnostic,
 *  so unknown domains degrade safely to TOGGLE. */
private val PRESS_DOMAINS = setOf("button", "script", "scene")

/**
 * Derive the render list (config order = display order). Every configured slot with an entity
 * produces a QuickButton — unavailable ones render dimmed so the card's layout stays stable when a
 * device drops off. Slots with no entity are skipped; empty cfg -> empty list -> card hidden.
 */
fun quickButtons(cfg: List<QuickButtonConfig>, entities: Map<String, EntityState>): List<QuickButton> =
    cfg.mapNotNull { slot ->
        val id = slot.entity ?: return@mapNotNull null
        val domain = id.substringBefore('.')
        val state = entities[id]
        val kind = if (domain in PRESS_DOMAINS) QuickButtonKind.PRESS else QuickButtonKind.TOGGLE
        QuickButton(
            entityId = id,
            // name (trimmed) -> friendly_name -> entity-id tail (the calendar-name convention).
            label = slot.name.trim().ifBlank {
                state?.attr("friendly_name")?.takeIf { it.isNotBlank() } ?: id.substringAfter('.')
            },
            icon = when (domain) {
                "light" -> QuickButtonIcon.LIGHT
                "script", "button" -> QuickButtonIcon.RUN
                "scene" -> QuickButtonIcon.SCENE
                else -> QuickButtonIcon.SWITCH // incl. switch, input_boolean, unknown domains
            },
            kind = kind,
            isOn = if (kind == QuickButtonKind.TOGGLE) state?.state == "on" else null,
            // "unavailable" (integration offline) disables either kind. "unknown" only disables a
            // TOGGLE — a scene/script/button rests at "unknown" (or a timestamp) until first fired,
            // and must stay tappable so you can fire it.
            available = state != null && state.state != "unavailable" &&
                !(kind == QuickButtonKind.TOGGLE && state.state == "unknown"),
        )
    }

/** The HA service call for a quick button, keyed on entity domain (the design's dispatch table):
 *  button.press / script|scene.turn_on fire the action; everything else routes to the
 *  domain-agnostic homeassistant.toggle. Returns (domain, service). */
fun quickButtonService(entityId: String): Pair<String, String> =
    when (entityId.substringBefore('.')) {
        "button" -> "button" to "press"
        "script" -> "script" to "turn_on"
        "scene" -> "scene" to "turn_on"
        else -> "homeassistant" to "toggle"
    }
