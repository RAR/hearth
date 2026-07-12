package com.rar.echodash.ui

import com.rar.echodash.config.ClockFormat
import com.rar.echodash.config.PanelConfig
import com.rar.echodash.config.Panels
import org.junit.Assert.assertEquals
import org.junit.Test

class DashViewsTest {
    @Test
    fun railViewsPutHomeFirstThenEnabledPanelsByOrder() {
        val panels = Panels(
            lights = PanelConfig(true, 2),
            climate = PanelConfig(false, 1),   // disabled -> excluded
            media = PanelConfig(true, 3),
            weather = PanelConfig(true, 5),
            solar = PanelConfig(true, 4),
        )
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.MEDIA, DashView.SOLAR, DashView.WEATHER),
            railViews(panels),
        )
    }

    @Test
    fun clockPatternHonorsFormatThenSystem() {
        assertEquals("HH:mm", clockPattern(ClockFormat.H24, systemIs24 = false))
        assertEquals("h:mm a", clockPattern(ClockFormat.H12, systemIs24 = true))
        assertEquals("HH:mm", clockPattern(ClockFormat.AUTO, systemIs24 = true))
        assertEquals("h:mm a", clockPattern(ClockFormat.AUTO, systemIs24 = false))
    }
}
