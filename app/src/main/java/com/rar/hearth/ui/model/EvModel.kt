package com.rar.hearth.ui.model

import com.rar.hearth.config.EvConfig
import com.rar.hearth.ha.EntityState
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One EV's card. Fields are pre-formatted display strings; null = omit that line. */
data class EvCard(
    val slot: Int,           // 0-based config slot this card came from; survives list compaction
    val name: String,        // config name, or "EV" when blank
    val charging: Boolean,   // true while actually charging: animates the gauge + shows the charge/eta lines
    val socPct: Int?,        // 0..100 for the gauge + "%" text; null hides the gauge row
    val limitPct: Int?,      // 0..100 tick position on the gauge; null hides the tick
    val chargeLine: String?, // "7.2 kW · 4.3 kWh" (power · energy); null when idle or neither sensor readable
    val etaText: String?,    // "1h05" / "45m"; null when idle or unparseable
)

/** States (lowercased, trimmed) that mean "plugged in" (cable connected), incl. EVCC status B/C. */
private val PLUGGED_TRUTHY = setOf("on", "true", "connected", "charging", "b", "c")

/** States (lowercased, trimmed) that mean "actively charging", incl. EVCC status C. */
private val CHARGING_TRUTHY = setOf("on", "true", "charging", "c")

/**
 * Build a card per config slot whose car is plugged in OR charging. Either trigger entity may be
 * unconfigured; a slot with only `charging` behaves exactly as v1. The card is shown when the
 * `plugged` entity is plugged-truthy or the `charging` entity is charging-truthy. Order follows
 * config order; slots that are neither plugged nor charging are skipped. The charge line (power ·
 * energy) and eta text are built only while charging — idle readings are noise.
 *
 * The returned list is COMPACTED -- slots that are neither plugged nor charging are skipped -- so
 * each card carries its `slot` index. Consumers that key off the config slot (the home card
 * ordering) must use `slot`, never the list position.
 */
fun evCards(cfgs: List<EvConfig>, entities: Map<String, EntityState>, nowMs: Long): List<EvCard> =
    cfgs.mapIndexedNotNull { slot, cfg ->
        val pluggedState = cfg.plugged?.let { entities[it] }?.state?.trim()?.lowercase(Locale.US)
        val chargingState = cfg.charging?.let { entities[it] }?.state?.trim()?.lowercase(Locale.US)
        val charging = chargingState != null && chargingState in CHARGING_TRUTHY
        val plugged = pluggedState != null && pluggedState in PLUGGED_TRUTHY
        if (!plugged && !charging) return@mapIndexedNotNull null

        val socPct = cfg.soc?.let { entities[it] }?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)
        val limitPct = cfg.limit?.let { entities[it] }?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)
        val chargeLine: String?
        val etaText: String?
        if (charging) {
            val power = cfg.power?.let { entities[it] }?.let { formatPower(it) }
            val energy = cfg.energy?.let { entities[it] }?.let { formatEnergy(it) }
            chargeLine = listOfNotNull(power, energy).joinToString(" · ").ifBlank { null }
            etaText = cfg.eta?.let { entities[it] }?.let { formatEta(it, nowMs) }
        } else {
            chargeLine = null
            etaText = null
        }

        EvCard(
            slot = slot,
            name = cfg.name.trim().ifBlank { "EV" },
            charging = charging,
            socPct = socPct,
            limitPct = limitPct,
            chargeLine = chargeLine,
            etaText = etaText,
        )
    }

/** Charge power as kW: unit-aware ("kW" as-is, else W/1000). One decimal below 10 kW, integer at/above. */
private fun formatPower(state: EntityState): String? {
    val unit = state.attr("unit_of_measurement") ?: "W"
    val v = state.state.toDoubleOrNull() ?: return null
    val kw = if (unit.equals("kW", ignoreCase = true)) abs(v) else abs(v) / 1000.0
    return if (kw < 10.0) String.format(Locale.US, "%.1f kW", kw) else "${kw.roundToInt()} kW"
}

/** Session energy as kWh: unit-aware ("kWh" as-is, else Wh/1000). One decimal below 10 kWh, integer at/above. */
private fun formatEnergy(state: EntityState): String? {
    val unit = state.attr("unit_of_measurement") ?: "Wh"
    val v = state.state.toDoubleOrNull() ?: return null
    val kwh = if (unit.equals("kWh", ignoreCase = true)) abs(v) else abs(v) / 1000.0
    return if (kwh < 10.0) String.format(Locale.US, "%.1f kWh", kwh) else "${kwh.roundToInt()} kWh"
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
