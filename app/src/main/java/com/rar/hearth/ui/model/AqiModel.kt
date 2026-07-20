package com.rar.hearth.ui.model

import com.rar.hearth.ha.EntityState

/** US EPA AQI bands; [colorArgb] is the band's display color for the reading. */
enum class AqiBand(val colorArgb: Long) {
    GOOD(0xFF66BB6A),
    MODERATE(0xFFFFD54F),
    UNHEALTHY_SENSITIVE(0xFFFFA726),
    UNHEALTHY(0xFFEF5350),
    VERY_UNHEALTHY(0xFFAB47BC),
    HAZARDOUS(0xFFD81B60),
}

fun aqiBand(value: Int): AqiBand = when {
    value <= 50 -> AqiBand.GOOD
    value <= 100 -> AqiBand.MODERATE
    value <= 150 -> AqiBand.UNHEALTHY_SENSITIVE
    value <= 200 -> AqiBand.UNHEALTHY
    value <= 300 -> AqiBand.VERY_UNHEALTHY
    else -> AqiBand.HAZARDOUS
}

data class AqiPill(val value: Int, val band: AqiBand)

/** Home-screen AQI pill from the configured sensor; hidden when unset, missing, or non-numeric.
 *  No staleness signal: AQI legitimately sits on one value for hours, so "hasn't changed lately"
 *  says nothing about sensor health. */
fun aqiPill(aqiSensorId: String?, entities: Map<String, EntityState>): AqiPill? {
    val sensor = aqiSensorId?.let { entities[it] } ?: return null
    val value = sensor.state.toDoubleOrNull()?.toInt() ?: return null
    return AqiPill(value = value, band = aqiBand(value))
}
