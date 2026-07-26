package com.rar.hearth.ui.model

import com.rar.hearth.config.ClaudeUsageConfig
import com.rar.hearth.ha.EntityState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One usage bar: a 0..100 fill with its label and an optional "resets" suffix. */
data class UsageBar(
    val label: String,      // "Session" / "Week"
    val percent: Int,       // 0..100, drives both the fill fraction and the "%" text
    val resetLabel: String?, // "7:30p" when near, "Sun" when far, null when unreadable/unconfigured
)

/**
 * The Claude usage card. [bars] is empty only when the card is hidden entirely (the caller checks),
 * and [paceText] is null whenever the pace sensor is absent or unreadable.
 */
data class ClaudeUsageCard(
    val bars: List<UsageBar>,
    val paceText: String?,
)

/** States that mean "no value" rather than a number — both are skipped, not rendered as 0%. */
private val DEAD_STATES = setOf("unavailable", "unknown", "none", "")

/**
 * Build the card from config + entity states, or null when nothing is readable.
 *
 * Rows are dropped individually: the integration's per-bucket sensors go `unavailable` when the API
 * stops reporting that bucket (a model you haven't touched this week reads unavailable, not 0), so
 * rendering a dead sensor as an empty bar would claim "0% used" — a claim the data does not make.
 * A row with no readable percentage is omitted; if that leaves no rows, the whole card is hidden.
 */
fun claudeUsageCard(
    cfg: ClaudeUsageConfig,
    entities: Map<String, EntityState>,
    nowMs: Long,
    zone: ZoneId,
    is24: Boolean,
): ClaudeUsageCard? {
    val bars = listOfNotNull(
        usageBar("Session", cfg.session, cfg.sessionReset, entities, nowMs, zone, is24),
        usageBar("Week", cfg.week, cfg.weekReset, entities, nowMs, zone, is24),
    )
    if (bars.isEmpty()) return null
    return ClaudeUsageCard(bars = bars, paceText = paceText(cfg.pace, entities))
}

private fun usageBar(
    label: String,
    percentId: String?,
    resetId: String?,
    entities: Map<String, EntityState>,
    nowMs: Long,
    zone: ZoneId,
    is24: Boolean,
): UsageBar? {
    val percent = percentId?.let { readNumber(entities[it]) }?.roundToInt()?.coerceIn(0, 100)
        ?: return null
    return UsageBar(
        label = label,
        percent = percent,
        resetLabel = resetId?.let { formatReset(entities[it], nowMs, zone, is24) },
    )
}

/**
 * Pace is the integration's "how far ahead/behind the straight-line burn are you" figure: negative
 * is under budget for this point in the week. Rendered in words because a bare "-6%" next to two
 * other percentages reads as a third usage figure rather than a delta.
 */
private fun paceText(paceId: String?, entities: Map<String, EntityState>): String? {
    val pace = paceId?.let { readNumber(entities[it]) } ?: return null
    val rounded = pace.roundToInt()
    return when {
        rounded == 0 -> "on pace"
        rounded < 0 -> "${abs(rounded)}% under pace"
        else -> "$rounded% over pace"
    }
}

/** The numeric state, or null when the entity is missing, dead, or non-numeric. */
private fun readNumber(state: EntityState?): Double? {
    val raw = state?.state?.trim() ?: return null
    if (raw.lowercase(Locale.US) in DEAD_STATES) return null
    return raw.toDoubleOrNull()
}

/**
 * A clock time ("7:30p") for a reset within [NEAR_RESET_HOURS], otherwise the weekday ("Sun").
 *
 * Proximity, not calendar date. The 5-hour session window routinely resets after midnight, and
 * keying off the local date rendered a reset five hours away as "Mon" — which reads as days out.
 * The weekday form only earns its place when the reset really is far enough that a time of day
 * tells you nothing, which in practice means the weekly bucket.
 *
 * Still zone-dependent: the sensors publish UTC instants, and the weekday of 04:30Z differs by
 * where you are.
 */
private fun formatReset(state: EntityState?, nowMs: Long, zone: ZoneId, is24: Boolean): String? {
    val raw = state?.state?.trim() ?: return null
    if (raw.lowercase(Locale.US) in DEAD_STATES) return null
    val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull() ?: return null
    val at = instant.atZone(zone)
    val hoursAway = Duration.between(Instant.ofEpochMilli(nowMs), instant).toHours()
    return if (hoursAway < NEAR_RESET_HOURS) clockLabel(at.hour, at.minute, is24)
    else weekdayLabel(at.toLocalDate())
}

/**
 * How far out a reset can be and still read as a time. 18 hours covers any session window
 * (max 5) plus a full night, while leaving the 7-day weekly bucket on the weekday form.
 */
private const val NEAR_RESET_HOURS = 18L

private fun clockLabel(hour: Int, minute: Int, is24: Boolean): String =
    if (is24) String.format(Locale.US, "%d:%02d", hour, minute)
    else {
        val h = when (hour % 12) { 0 -> 12; else -> hour % 12 }
        val suffix = if (hour < 12) "a" else "p"
        String.format(Locale.US, "%d:%02d%s", h, minute, suffix)
    }

private fun weekdayLabel(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
