package com.rar.hearth.device

import com.rar.hearth.config.PanelConfig
import com.rar.hearth.config.Panels
import com.rar.hearth.ui.DashView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashActionParserTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // --- set-view parsing ---

    @Test
    fun parseSetViewMapsLowercaseName() {
        assertEquals(DashView.CAMERAS, DashActionParser.parseSetView(json("""{"view":"cameras"}""")))
        assertEquals(DashView.HOME, DashActionParser.parseSetView(json("""{"view":"home"}""")))
    }

    @Test
    fun parseSetViewIsCaseInsensitiveAndTrims() {
        assertEquals(DashView.SOLAR, DashActionParser.parseSetView(json("""{"view":"  SOLAR "}""")))
    }

    @Test
    fun parseSetViewRejectsUnknownMissingOrNonObject() {
        assertNull(DashActionParser.parseSetView(json("""{"view":"bogus"}""")))
        assertNull(DashActionParser.parseSetView(json("""{}""")))
        assertNull(DashActionParser.parseSetView(json(""""cameras"""")))  // primitive, not object
        assertNull(DashActionParser.parseSetView(null))
    }

    // --- allowed-view policy (railViews oracle) ---

    @Test
    fun isViewAllowedFollowsRailViews() {
        val panels = Panels()  // defaults: HOME + lights/climate/media/weather/solar/calendar enabled, cameras off
        assertTrue(DashActionParser.isViewAllowed(DashView.HOME, panels, camerasConfigured = false))
        assertTrue(DashActionParser.isViewAllowed(DashView.LIGHTS, panels, camerasConfigured = false))
        // Cameras: off by default and not configured -> not allowed.
        assertFalse(DashActionParser.isViewAllowed(DashView.CAMERAS, panels, camerasConfigured = false))
        // A disabled panel is not allowed even though the view name is valid.
        val noLights = Panels(lights = PanelConfig(false, 2))
        assertFalse(DashActionParser.isViewAllowed(DashView.LIGHTS, noLights, camerasConfigured = false))
        // Cameras enabled AND configured -> allowed.
        val cams = Panels(cameras = PanelConfig(true, 6))
        assertTrue(DashActionParser.isViewAllowed(DashView.CAMERAS, cams, camerasConfigured = true))
    }

    // --- notify parsing (mirrors ConfigServer.handleNotify) ---

    @Test
    fun parseNotifyRejectsBlankOrMissingTitle() {
        assertNull(DashActionParser.parseNotify(json("""{"message":"hi"}""")))
        assertNull(DashActionParser.parseNotify(json("""{"title":"   "}""")))
        assertNull(DashActionParser.parseNotify(null))
    }

    @Test
    fun parseNotifyPassesFieldsThrough() {
        val cmd = DashActionParser.parseNotify(
            json("""{"id":"laundry","title":" Done ","message":"dry","severity":"warning","timeout":120}""")
        )!!
        assertEquals("laundry", cmd.id)
        assertEquals("Done", cmd.title)              // trimmed
        assertEquals("dry", cmd.message)
        assertEquals("warning", cmd.severity)        // raw string; store parses it
        assertEquals(120, cmd.timeoutSeconds)
    }

    @Test
    fun parseNotifyDropsNonPositiveTimeout() {
        val cmd = DashActionParser.parseNotify(json("""{"title":"A","timeout":0}"""))!!
        assertNull(cmd.timeoutSeconds)
        val cmd2 = DashActionParser.parseNotify(json("""{"title":"A","timeout":-5}"""))!!
        assertNull(cmd2.timeoutSeconds)
        val cmd3 = DashActionParser.parseNotify(json("""{"title":"A"}"""))!!
        assertNull(cmd3.timeoutSeconds)
    }

    // --- notify-clear parsing (mirrors ConfigServer.handleNotifyClear) ---

    @Test
    fun parseNotifyClearAllWins() {
        assertEquals(DashActionParser.NotifyClear.All,
            DashActionParser.parseNotifyClear(json("""{"all":true}""")))
        // all:true takes precedence even if an id is also present.
        assertEquals(DashActionParser.NotifyClear.All,
            DashActionParser.parseNotifyClear(json("""{"all":true,"id":"x"}""")))
    }

    @Test
    fun parseNotifyClearOneById() {
        assertEquals(DashActionParser.NotifyClear.One("laundry"),
            DashActionParser.parseNotifyClear(json("""{"id":" laundry "}""")))
    }

    @Test
    fun parseNotifyClearRejectsNeither() {
        assertNull(DashActionParser.parseNotifyClear(json("""{}""")))
        assertNull(DashActionParser.parseNotifyClear(json("""{"id":"   "}""")))
        assertNull(DashActionParser.parseNotifyClear(json("""{"all":false}""")))
        assertNull(DashActionParser.parseNotifyClear(null))
    }
}
