package com.rar.hearth.ui.model

import com.rar.hearth.config.ClaudeUsageConfig
import com.rar.hearth.ha.EntityState
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
    val resetLabel: String?, // "7:30p" today, "Sun" later, null when unreadable/unconfigured
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
 * "7:30p" when the reset lands on today's local date, otherwise the weekday ("Sun"). The date
 * comparison is done in [zone] — the sensors publish UTC instants, and a reset at 09:00Z is
 * "yesterday evening" or "this morning" depending on where you are.
 */
private fun formatReset(state: EntityState?, nowMs: Long, zone: ZoneId, is24: Boolean): String? {
    val raw = state?.state?.trim() ?: return null
    if (raw.lowercase(Locale.US) in DEAD_STATES) return null
    val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull() ?: return null
    val at = instant.atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return if (at.toLocalDate() == today) clockLabel(at.hour, at.minute, is24)
    else weekdayLabel(at.toLocalDate())
}

private fun clockLabel(hour: Int, minute: Int, is24: Boolean): String =
    if (is24) String.format(Locale.US, "%d:%02d", hour, minute)
    else {
        val h = when (hour % 12) { 0 -> 12; else -> hour % 12 }
        val suffix = if (hour < 12) "a" else "p"
        String.format(Locale.US, "%d:%02d%s", h, minute, suffix)
    }

private fun weekdayLabel(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
