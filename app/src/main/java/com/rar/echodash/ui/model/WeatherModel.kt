package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

const val STALE_AFTER_MS = 15 * 60_000L

/** Fixed-decimals sensor value, e.g. 14.156 -> "14.2" at 1 decimal, "14" at 0. */
fun formatSensor(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

/** [formatSensor] for a raw state string; non-numeric states pass through untouched. */
fun formatSensorState(state: String, decimals: Int): String =
    state.toDoubleOrNull()?.let { formatSensor(it, decimals) } ?: state

enum class WeatherIcon { SUNNY, CLEAR_NIGHT, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW, STORM, FOG, WIND, UNKNOWN }

/** Maps a HA weather condition string to the app's icon set. */
fun conditionIcon(condition: String?): WeatherIcon = when (condition) {
    "sunny" -> WeatherIcon.SUNNY
    "clear-night" -> WeatherIcon.CLEAR_NIGHT
    "partlycloudy" -> WeatherIcon.PARTLY_CLOUDY
    "cloudy" -> WeatherIcon.CLOUDY
    "rainy", "pouring", "hail" -> WeatherIcon.RAIN
    "snowy", "snowy-rainy" -> WeatherIcon.SNOW
    "lightning", "lightning-rainy" -> WeatherIcon.STORM
    "fog" -> WeatherIcon.FOG
    "windy", "windy-variant" -> WeatherIcon.WIND
    else -> WeatherIcon.UNKNOWN
}

data class WeatherPill(
    val icon: WeatherIcon,
    val conditionText: String?,
    val temperature: String?,
    val stale: Boolean,
)

/** Pill temperature: configured temp sensor first, else weather entity's temperature attr, else hidden. */
fun weatherPill(
    tempSensorId: String?,
    weatherId: String?,
    entities: Map<String, EntityState>,
    nowMs: Long,
    decimals: Int = 1,
): WeatherPill? {
    val tempSensor = tempSensorId?.let { entities[it] }
    val weather = weatherId?.let { entities[it] }

    val temperature: String?
    val stale: Boolean
    when {
        tempSensor != null && tempSensor.state.toDoubleOrNull() != null -> {
            val unit = tempSensor.attr("unit_of_measurement")
            val t = formatSensorState(tempSensor.state, decimals)
            temperature = if (unit != null) "$t $unit" else t
            stale = nowMs - tempSensor.lastUpdatedMs > STALE_AFTER_MS
        }
        weather?.attrDouble("temperature") != null -> {
            val unit = weather.attr("temperature_unit")
            val t = formatSensor(weather.attrDouble("temperature")!!, decimals)
            temperature = if (unit != null) "$t $unit" else t
            stale = false
        }
        else -> { temperature = null; stale = false }
    }

    if (temperature == null && weather == null) return null

    return WeatherPill(
        icon = conditionIcon(weather?.state),
        conditionText = weather?.state,
        temperature = temperature,
        stale = stale,
    )
}

data class DailyForecast(val dayOfWeek: String, val icon: WeatherIcon, val high: Double?, val low: Double?)

/** Parse a weather.get_forecasts result into up to 5 daily columns for [entityId]. */
fun parseForecasts(result: JsonElement?, entityId: String): List<DailyForecast> {
    val forecast = ((result as? JsonObject)
        ?.get("response") as? JsonObject)
        ?.get(entityId)?.let { it as? JsonObject }
        ?.get("forecast") as? JsonArray
        ?: return emptyList()
    return forecast.take(5).mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        DailyForecast(
            dayOfWeek = dayOfWeek((o["datetime"] as? JsonPrimitive)?.contentOrNull),
            icon = conditionIcon((o["condition"] as? JsonPrimitive)?.contentOrNull),
            high = (o["temperature"] as? JsonPrimitive)?.doubleOrNull,
            low = (o["templow"] as? JsonPrimitive)?.doubleOrNull,
        )
    }
}

private fun dayOfWeek(datetime: String?): String =
    runCatching { OffsetDateTime.parse(datetime).dayOfWeek }
        .getOrDefault(DayOfWeek.MONDAY)
        .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
