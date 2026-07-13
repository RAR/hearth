package com.rar.echodash.ui.model

import com.rar.echodash.config.EvConfig
import com.rar.echodash.ha.EntityState
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One charging EV's card. Fields are pre-formatted display strings; null = omit that line. */
data class EvCard(
    val name: String,        // config name, or "EV" when blank
    val socPct: Int?,        // 0..100 for the gauge + "%" text; null hides the gauge row
    val statusLine: String?, // "7.2 kW · 1h05 left" / "7.2 kW" / "1h05 left"; null hides the row
)

/** States (lowercased, trimmed) that mean "charging" across binary_sensor and EVCC/string sensors. */
private val TRUTHY = setOf("on", "true", "charging")

/**
 * Build a card per config slot that is currently charging. The `charging` entity is the trigger:
 * no charging entity, missing entity, or non-truthy state -> no card. Order follows config order;
 * idle slots are skipped.
 */
fun evCards(cfgs: List<EvConfig>, entities: Map<String, EntityState>, nowMs: Long): List<EvCard> =
    cfgs.mapNotNull { cfg ->
        val chargingId = cfg.charging ?: return@mapNotNull null
        val charging = entities[chargingId] ?: return@mapNotNull null
        if (charging.state.trim().lowercase(Locale.US) !in TRUTHY) return@mapNotNull null

        val socPct = cfg.soc?.let { entities[it] }?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)
        val power = cfg.power?.let { entities[it] }?.let { formatPower(it) }
        val eta = cfg.eta?.let { entities[it] }?.let { formatEta(it, nowMs) }
        val statusLine = listOfNotNull(power, eta?.let { "$it left" }).joinToString(" · ").ifBlank { null }

        EvCard(
            name = cfg.name.trim().ifBlank { "EV" },
            socPct = socPct,
            statusLine = statusLine,
        )
    }

/** Charge power as kW: unit-aware ("kW" as-is, else W/1000). One decimal below 10 kW, integer at/above. */
private fun formatPower(state: EntityState): String? {
    val unit = state.attr("unit_of_measurement") ?: "W"
    val v = state.state.toDoubleOrNull() ?: return null
    val kw = if (unit.equals("kW", ignoreCase = true)) abs(v) else abs(v) / 1000.0
    return if (kw < 10.0) String.format(Locale.US, "%.1f kW", kw) else "${kw.roundToInt()} kW"
}

/** Remaining time as "1h05" / "45m"; null when unparseable or zero/negative (EVCC reports 0 near end). */
private fun formatEta(state: EntityState, nowMs: Long): String? {
    val minutes = etaMinutes(state.state.trim(), nowMs) ?: return null
    if (minutes <= 0L) return null
    return formatMinutes(minutes)
}

/** Parse an ETA sensor to whole minutes remaining, trying (in order) number, H:MM:SS, ISO timestamp. */
private fun etaMinutes(raw: String, nowMs: Long): Long? {
    // 1. plain number of minutes
    raw.toDoubleOrNull()?.let { return it.roundToInt().toLong() }
    // 2. H:MM:SS / HH:MM:SS duration
    if (raw.contains(':') && !raw.contains('T')) {
        val parts = raw.split(':')
        if (parts.size == 3) {
            val h = parts[0].toLongOrNull()
            val m = parts[1].toLongOrNull()
            val s = parts[2].toLongOrNull()
            if (h != null && m != null && s != null) return h * 60 + m + if (s >= 30) 1 else 0
        }
        return null
    }
    // 3. ISO-8601 timestamp
    if (raw.contains('T')) {
        val tsMs = parseTimestampMs(raw) ?: return null
        return (tsMs - nowMs) / 60_000L
    }
    return null
}

/** Whole minutes -> "1h05" (with hours) or "45m" (under an hour). */
private fun formatMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) String.format(Locale.US, "%dh%02d", h, m) else "${m}m"
}

/** Parse an ISO-8601 instant/offset timestamp to epoch millis; null if neither form parses. */
private fun parseTimestampMs(raw: String): Long? =
    try {
        OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            Instant.parse(raw).toEpochMilli()
        } catch (e2: Exception) {
            null
        }
    }
