package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
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
        assertEquals("rainy", pill.conditionText)
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
}
