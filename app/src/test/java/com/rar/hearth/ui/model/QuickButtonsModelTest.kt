package com.rar.hearth.ui.model

import com.rar.hearth.config.QuickButtonConfig
import com.rar.hearth.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickButtonsModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject

    /** [friendly] present -> attributes carry a friendly_name; otherwise no attributes. */
    private fun st(id: String, state: String, friendly: String? = null): EntityState =
        EntityState(
            id, state,
            friendly?.let { attrs("""{"friendly_name":"$it"}""") } ?: JsonObject(emptyMap()),
            0L,
        )

    @Test
    fun emptyConfigYieldsEmptyList() {
        assertEquals(emptyList<QuickButton>(), quickButtons(emptyList(), emptyMap()))
    }

    @Test
    fun kindMappingByDomainIncludingUnknown() {
        val cfg = listOf(
            QuickButtonConfig(entity = "switch.a"),
            QuickButtonConfig(entity = "light.b"),
            QuickButtonConfig(entity = "input_boolean.c"),
            QuickButtonConfig(entity = "button.d"),
            QuickButtonConfig(entity = "script.e"),
            QuickButtonConfig(entity = "scene.f"),
            QuickButtonConfig(entity = "fan.g"), // unknown domain -> TOGGLE (homeassistant.toggle is domain-agnostic)
        )
        assertEquals(
            listOf(
                QuickButtonKind.TOGGLE, QuickButtonKind.TOGGLE, QuickButtonKind.TOGGLE,
                QuickButtonKind.PRESS, QuickButtonKind.PRESS, QuickButtonKind.PRESS,
                QuickButtonKind.TOGGLE,
            ),
            quickButtons(cfg, emptyMap()).map { it.kind },
        )
    }

    @Test
    fun iconMappingByDomain() {
        val cfg = listOf(
            QuickButtonConfig(entity = "light.a"),
            QuickButtonConfig(entity = "switch.b"),
            QuickButtonConfig(entity = "input_boolean.c"),
            QuickButtonConfig(entity = "script.d"),
            QuickButtonConfig(entity = "button.e"),
            QuickButtonConfig(entity = "scene.f"),
        )
        assertEquals(
            listOf(
                QuickButtonIcon.LIGHT, QuickButtonIcon.SWITCH, QuickButtonIcon.SWITCH,
                QuickButtonIcon.RUN, QuickButtonIcon.RUN, QuickButtonIcon.SCENE,
            ),
            quickButtons(cfg, emptyMap()).map { it.icon },
        )
    }

    @Test
    fun labelFallbackChainNameThenFriendlyThenTail() {
        val cfg = listOf(
            QuickButtonConfig(name = "  Desk Lamp  ", entity = "light.desk"), // explicit name wins (trimmed)
            QuickButtonConfig(entity = "switch.porch"),                       // -> friendly_name
            QuickButtonConfig(entity = "scene.movie_night"),                  // -> id tail (no friendly_name)
        )
        val entities = mapOf(
            "light.desk" to st("light.desk", "on", friendly = "Desk (registry)"),
            "switch.porch" to st("switch.porch", "off", friendly = "Porch Light"),
            "scene.movie_night" to st("scene.movie_night", "unknown"),
        )
        assertEquals(
            listOf("Desk Lamp", "Porch Light", "movie_night"),
            quickButtons(cfg, entities).map { it.label },
        )
    }

    @Test
    fun isOnReflectsToggleStateAndPressIsNull() {
        val cfg = listOf(
            QuickButtonConfig(entity = "switch.on"),
            QuickButtonConfig(entity = "switch.off"),
            QuickButtonConfig(entity = "button.press"),
        )
        val entities = mapOf(
            "switch.on" to st("switch.on", "on"),
            "switch.off" to st("switch.off", "off"),
            "button.press" to st("button.press", "2026-07-17T00:00:00+00:00"),
        )
        val out = quickButtons(cfg, entities)
        assertEquals(true, out[0].isOn)
        assertEquals(false, out[1].isOn)
        assertNull(out[2].isOn) // PRESS always null
    }

    @Test
    fun availabilityFalseForMissingAndUnavailable() {
        val cfg = listOf(
            QuickButtonConfig(entity = "switch.here"),
            QuickButtonConfig(entity = "switch.gone"),    // absent from map
            QuickButtonConfig(entity = "switch.unavail"),
        )
        val entities = mapOf(
            "switch.here" to st("switch.here", "off"),
            "switch.unavail" to st("switch.unavail", "unavailable"),
        )
        assertEquals(
            listOf(true, false, false),
            quickButtons(cfg, entities).map { it.available },
        )
    }

    @Test
    fun unknownDisablesToggleButNotPress() {
        // A scene/script that has never fired rests at "unknown" (or a timestamp) — that is normal,
        // not offline, so PRESS buttons stay tappable. For a TOGGLE, "unknown" means we don't know
        // on/off, so it dims. "unavailable" (integration offline) disables either kind.
        val cfg = listOf(
            QuickButtonConfig(entity = "switch.unknown"),   // TOGGLE + unknown -> unavailable
            QuickButtonConfig(entity = "scene.unknown"),    // PRESS + unknown -> available
            QuickButtonConfig(entity = "scene.unavail"),    // PRESS + unavailable -> unavailable
        )
        val entities = mapOf(
            "switch.unknown" to st("switch.unknown", "unknown"),
            "scene.unknown" to st("scene.unknown", "unknown"),
            "scene.unavail" to st("scene.unavail", "unavailable"),
        )
        assertEquals(
            listOf(false, true, false),
            quickButtons(cfg, entities).map { it.available },
        )
    }

    @Test
    fun orderPreservedAndNullEntitySlotsSkipped() {
        val cfg = listOf(
            QuickButtonConfig(name = "First", entity = "switch.a"),
            QuickButtonConfig(name = "NoEntity"),        // entity null -> skipped
            QuickButtonConfig(name = "Second", entity = "light.b"),
        )
        assertEquals(
            listOf("switch.a", "light.b"),
            quickButtons(cfg, emptyMap()).map { it.entityId },
        )
    }

    @Test
    fun quickButtonServiceMapsPerSpecTable() {
        assertEquals("button" to "press", quickButtonService("button.doorbell"))
        assertEquals("script" to "turn_on", quickButtonService("script.movie_night"))
        assertEquals("scene" to "turn_on", quickButtonService("scene.evening"))
        assertEquals("homeassistant" to "toggle", quickButtonService("switch.fan"))
        assertEquals("homeassistant" to "toggle", quickButtonService("light.desk"))
        assertEquals("homeassistant" to "toggle", quickButtonService("input_boolean.guest"))
        assertEquals("homeassistant" to "toggle", quickButtonService("fan.unknown_domain"))
    }
}
