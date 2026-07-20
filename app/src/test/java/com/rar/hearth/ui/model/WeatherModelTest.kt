package com.rar.hearth.ui.model

import com.rar.hearth.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun pillPrefersTempSensor() {
        val entities = mapOf(
            "sensor.temp" to EntityState("sensor.temp", "14.1", attrs("""{"unit_of_measurement":"°C"}"""), 1_000L),
            "weather.home" to EntityState("weather.home", "rainy", attrs("""{"temperature":9.0}"""), 1_000L),
        )
        val pill = weatherPill("sensor.temp", "weather.home", entities, nowMs = 1_500L)!!
        assertEquals("14.1 °C", pill.temperature)
        assertEquals(WeatherIcon.RAIN, pill.icon)
        assertEquals("Rainy", pill.conditionText) // human label, not the raw state
        assertEquals(false, pill.stale)
    }

    @Test
    fun pillFallsBackToWeatherAttributeThenHides() {
        val onlyWeather = mapOf("weather.home" to EntityState("weather.home", "sunny",
            attrs("""{"temperature":24.0,"temperature_unit":"°C"}"""), 0L))
        val pill = weatherPill(null, "weather.home", onlyWeather, nowMs = 0L)!!
        assertEquals("24.0 °C", pill.temperature)
        assertEquals(WeatherIcon.SUNNY, pill.icon)
        assertNull(weatherPill(null, null, emptyMap(), nowMs = 0L))
    }

    @Test
    fun pillDimsWhenTempSensorStale() {
        val entities = mapOf("sensor.temp" to EntityState("sensor.temp", "10.0",
            attrs("""{"unit_of_measurement":"°C"}"""), lastUpdatedMs = 0L))
        val pill = weatherPill("sensor.temp", null, entities, nowMs = STALE_AFTER_MS + 1)!!
        assertEquals(true, pill.stale)
    }

    @Test
    fun formatSensorRoundsToConfiguredDecimals() {
        assertEquals("14.2", formatSensor(14.156, 1))
        assertEquals("14", formatSensor(14.156, 0))
        assertEquals("14.16", formatSensor(14.156, 2))
        assertEquals("14.16", formatSensorState("14.156", 2))
        assertEquals("unavailable", formatSensorState("unavailable", 2))
    }

    @Test
    fun pillTemperatureHonorsDecimals() {
        val entities = mapOf("sensor.temp" to EntityState("sensor.temp", "14.156",
            attrs("""{"unit_of_measurement":"°C"}"""), 1_000L))
        assertEquals("14.2 °C", weatherPill("sensor.temp", null, entities, nowMs = 1_500L)!!.temperature)
        assertEquals("14 °C", weatherPill("sensor.temp", null, entities, nowMs = 1_500L, decimals = 0)!!.temperature)
    }

    @Test
    fun parsesFiveDayForecast() {
        val result = Json.parseToJsonElement(
            """{"response":{"weather.home":{"forecast":[
              {"datetime":"2026-07-13T00:00:00+00:00","condition":"sunny","temperature":25.0,"templow":15.0},
              {"datetime":"2026-07-14T00:00:00+00:00","condition":"cloudy","temperature":22.0,"templow":14.0}
            ]}}}"""
        )
        val days = parseForecasts(result, "weather.home")
        assertEquals(2, days.size)
        assertEquals(WeatherIcon.SUNNY, days[0].icon)
        assertEquals(25.0, days[0].high!!, 0.001)
        assertEquals("Mon", days[0].dayOfWeek)
        assertEquals("Tue", days[1].dayOfWeek)
    }

    @Test
    fun forecastParseIsNullSafe() {
        assertEquals(emptyList<DailyForecast>(), parseForecasts(null, "weather.home"))
        assertEquals(emptyList<DailyForecast>(),
            parseForecasts(Json.parseToJsonElement("""{"response":{}}"""), "weather.home"))
    }

    @Test
    fun conditionMapping() {
        assertEquals(WeatherIcon.CLEAR_NIGHT, conditionIcon("clear-night"))
        assertEquals(WeatherIcon.PARTLY_CLOUDY, conditionIcon("partlycloudy"))
        assertEquals(WeatherIcon.RAIN, conditionIcon("pouring"))
        assertEquals(WeatherIcon.SNOW, conditionIcon("snowy-rainy"))
        assertEquals(WeatherIcon.STORM, conditionIcon("lightning-rainy"))
        assertEquals(WeatherIcon.WIND, conditionIcon("windy-variant"))
        assertEquals(WeatherIcon.UNKNOWN, conditionIcon(null))
        assertEquals(WeatherIcon.UNKNOWN, conditionIcon("exceptional"))
    }

    private fun rainEntities(state: String, attrsJson: String = """{"unit_of_measurement":"in"}""") =
        mapOf("sensor.rain" to EntityState("sensor.rain", state, attrs(attrsJson), 1_000L))

    @Test
    fun rainPillHiddenWhenUnsetMissingOrNonNumeric() {
        assertNull(rainPill(null, rainEntities("0.4")))
        assertNull(rainPill("sensor.other", rainEntities("0.4")))
        assertNull(rainPill("sensor.rain", rainEntities("unavailable")))
    }

    @Test
    fun rainPillHiddenWhenDryOrNegative() {
        assertNull(rainPill("sensor.rain", rainEntities("0")))
        assertNull(rainPill("sensor.rain", rainEntities("0.0")))
        assertNull(rainPill("sensor.rain", rainEntities("-0.1")))
    }

    @Test
    fun rainPillFormatsTwoDecimalsWithUnit() {
        assertEquals("0.42 in", rainPill("sensor.rain", rainEntities("0.416"))!!.text)
        assertEquals("1.50 in", rainPill("sensor.rain", rainEntities("1.5"))!!.text)
    }

    @Test
    fun rainPillOmitsMissingUnit() {
        assertEquals("0.40", rainPill("sensor.rain", rainEntities("0.4", "{}"))!!.text)
    }

    @Test
    fun weatherPillTextJoinsConditionTemperatureAndRain() {
        val pill = WeatherPill(WeatherIcon.RAIN, "Rainy", "68.7 °F", stale = false)
        val rain = RainPill("0.74 in")
        assertEquals("Rainy · 68.7 °F · 0.74 in", weatherPillText(pill, rain))
    }

    @Test
    fun weatherPillTextOmitsTrailingSeparatorWhenRainIsNull() {
        val pill = WeatherPill(WeatherIcon.SUNNY, "Sunny", "72.0 °F", stale = false)
        assertEquals("Sunny · 72.0 °F", weatherPillText(pill, null))
    }

    @Test
    fun weatherPillTextOmitsLeadingSeparatorWhenConditionIsNull() {
        val pill = WeatherPill(WeatherIcon.UNKNOWN, null, "68.7 °F", stale = false)
        val rain = RainPill("0.74 in")
        assertEquals("68.7 °F · 0.74 in", weatherPillText(pill, rain))
    }

    @Test
    fun conditionLabelsMatchHaTranslations() {
        assertEquals("Partly cloudy", conditionLabel("partlycloudy"))
        assertEquals("Clear, night", conditionLabel("clear-night"))
        assertEquals("Lightning, rainy", conditionLabel("lightning-rainy"))
        assertEquals("Snowy, rainy", conditionLabel("snowy-rainy"))
        assertEquals("Windy", conditionLabel("windy-variant"))
        assertEquals("Sunny", conditionLabel("sunny"))
    }

    @Test
    fun conditionLabelFallsBackForUnknownAndNull() {
        assertEquals("Freezing drizzle", conditionLabel("freezing-drizzle")) // unknown -> prettified
        assertEquals("Unavailable", conditionLabel("unavailable"))
        assertEquals(null, conditionLabel(null))
        assertEquals(null, conditionLabel("   "))
    }
}
