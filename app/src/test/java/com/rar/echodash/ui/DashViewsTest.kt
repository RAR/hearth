package com.rar.echodash.ui

import com.rar.echodash.config.ClockFormat
import com.rar.echodash.config.PanelConfig
import com.rar.echodash.config.Panels
import java.util.Calendar
import java.util.Locale
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
    fun clockIs24HonorsFormatThenSystem() {
        assertEquals(true, clockIs24(ClockFormat.H24, systemIs24 = false))
        assertEquals(false, clockIs24(ClockFormat.H12, systemIs24 = true))
        assertEquals(true, clockIs24(ClockFormat.AUTO, systemIs24 = true))
        assertEquals(false, clockIs24(ClockFormat.AUTO, systemIs24 = false))
    }

    @Test
    fun dateLineFormatsWeekdayMonthOrdinal() {
        val millis = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 12, 12, 0, 0)
        }.timeInMillis
        assertEquals("Sunday, July 12th", dateLine(millis, Locale.US))
    }

    @Test
    fun ordinalSuffixCoversTeensAndDigits() {
        assertEquals("st", ordinalSuffix(1))
        assertEquals("nd", ordinalSuffix(2))
        assertEquals("rd", ordinalSuffix(3))
        assertEquals("th", ordinalSuffix(4))
        assertEquals("th", ordinalSuffix(11))
        assertEquals("th", ordinalSuffix(12))
        assertEquals("th", ordinalSuffix(13))
        assertEquals("st", ordinalSuffix(21))
        assertEquals("nd", ordinalSuffix(22))
        assertEquals("rd", ordinalSuffix(23))
        assertEquals("st", ordinalSuffix(31))
    }
}
