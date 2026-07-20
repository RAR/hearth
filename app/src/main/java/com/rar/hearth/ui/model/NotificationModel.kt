package com.rar.hearth.ui.model

import com.rar.hearth.ha.EntityState
import com.rar.hearth.notify.PushedNotification
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Notification severity; display order is CRITICAL first. Ordinal order = ascending severity. */
enum class NotifSeverity { INFO, WARNING, CRITICAL }

/**
 * One notification row. [key] is the stable identity (NWS alert ID) used for dismissal and list keys.
 * [detail] null means the row is not expandable.
 */
data class NotificationItem(
    val key: String,
    val severity: NotifSeverity,
    val title: String,
    val detail: String?,
    val timestampMs: Long?,
)

/** Map a config min-severity string to the enum. Unknown/blank -> INFO (show everything). */
fun notifSeverityOf(configValue: String): NotifSeverity =
    when (configValue.trim().lowercase(Locale.US)) {
        "severe" -> NotifSeverity.CRITICAL
        "moderate" -> NotifSeverity.WARNING
        else -> NotifSeverity.INFO
    }

/** Map an NWS `Severity` attribute value to the enum. Extreme/Severe -> CRITICAL, Moderate/Minor ->
 *  WARNING, Unknown/anything else -> INFO. Minor rides with Moderate because NWS advisories (e.g.
 *  Flood Advisory) are graded Minor but still deserve amber, not the INFO auto-dismiss bucket. */
private fun severityOfAlert(raw: String?): NotifSeverity =
    when (raw?.trim()?.lowercase(Locale.US)) {
        "extreme", "severe" -> NotifSeverity.CRITICAL
        "moderate", "minor" -> NotifSeverity.WARNING
        else -> NotifSeverity.INFO
    }

private val DAY_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH)
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** Parse an ISO-8601-with-offset string; null on absent/blank/unparseable. */
private fun parseOffset(raw: String?): OffsetDateTime? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

/** Compact absolute label for a notification timestamp: same local day as [nowMs] -> "6:12 PM",
 *  otherwise "Wed 6:12 PM". Null in -> null out. */
fun notifTimestampLabel(timestampMs: Long?, nowMs: Long): String? {
    if (timestampMs == null) return null
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return local.format(if (local.toLocalDate() == nowDate) TIME_FMT else DAY_TIME_FMT)
}

/** Effective end instant for an alert: Ends, falling back to Expires; null when neither parses.
 *  Shared by the expiry check and the title's "until" suffix so both agree on one value. */
private fun resolveEnd(endsRaw: String?, expiresRaw: String?): OffsetDateTime? =
    parseOffset(endsRaw) ?: parseOffset(expiresRaw)

/** "until <time>" suffix for an already-resolved [end]. Weekday dropped when the end lands on the
 *  same local day as [nowMs]. */
private fun untilSuffix(end: OffsetDateTime, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val local = end.atZoneSameInstant(zone)
    val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val fmt = if (local.toLocalDate() == nowDate) TIME_FMT else DAY_TIME_FMT
    return "until " + local.format(fmt)
}

/**
 * Derive notification rows from the NWS alerts sensor. Returns an empty list when [sensorId] is
 * null, the entity is missing, the state is non-numeric (unavailable/unknown), or the `Alerts`
 * attribute is absent/not an array. Never throws: malformed entries are skipped. Alerts whose
 * effective end (Ends, fallback Expires) is at or before [nowMs] are dropped; an unparseable or
 * absent end is treated as not-expired and kept.
 */
fun nwsNotifications(
    sensorId: String?,
    minSeverity: NotifSeverity,
    entities: Map<String, EntityState>,
    nowMs: Long,
): List<NotificationItem> {
    val entity = sensorId?.let { entities[it] } ?: return emptyList()
    if (entity.state.toIntOrNull() == null) return emptyList()
    val alerts = entity.attributes["Alerts"] as? JsonArray ?: return emptyList()

    data class Row(val item: NotificationItem, val onset: Instant?)

    val rows = alerts.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        fun field(k: String): String? = (obj[k] as? JsonPrimitive)?.contentOrNull
        val event = field("Event")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val id = field("ID")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val severity = severityOfAlert(field("Severity"))
        if (severity.ordinal < minSeverity.ordinal) return@mapNotNull null

        val end = resolveEnd(field("Ends"), field("Expires"))
        if (end != null && end.toInstant().toEpochMilli() <= nowMs) return@mapNotNull null

        val title = buildString {
            append(event)
            end?.let { append(" · ").append(untilSuffix(it, nowMs)) }
        }
        val detail = listOf(field("Headline"), field("Description"), field("Instruction"))
            .mapNotNull { it?.takeIf { s -> s.isNotBlank() } }
            .joinToString("\n\n")
            .ifBlank { null }
        val onset = parseOffset(field("Onset"))?.toInstant()

        Row(
            item = NotificationItem(
                key = id,
                severity = severity,
                title = title,
                detail = detail,
                timestampMs = onset?.toEpochMilli(),
            ),
            onset = onset,
        )
    }

    return rows
        .sortedWith(
            compareByDescending<Row> { it.item.severity.ordinal }
                .thenBy(nullsLast()) { it.onset }
                .thenBy { it.item.title },
        )
        .map { it.item }
}

/** Key prefix distinguishing HA-pushed rows from NWS rows in the merged notification list. */
const val PUSH_KEY_PREFIX = "push:"

/** Map HA-pushed notifications (already newest-first) to display rows. Key = [PUSH_KEY_PREFIX] + id,
 *  detail = message (null -> not expandable). */
fun pushedNotificationItems(items: List<PushedNotification>): List<NotificationItem> =
    items.map {
        NotificationItem(
            key = PUSH_KEY_PREFIX + it.id,
            severity = it.severity,
            title = it.title,
            detail = it.message,
            timestampMs = it.receivedAtMs,
        )
    }

/**
 * Merge pushed + NWS rows for the notification area: concatenate pushed-then-NWS, then STABLE-sort by
 * severity descending (CRITICAL first). Kotlin's [sortedByDescending] is stable, so within a severity
 * band pushed rows keep their newest-first order ahead of the NWS rows, and NWS keeps its own order.
 */
fun mergeNotifications(pushed: List<NotificationItem>, nws: List<NotificationItem>): List<NotificationItem> =
    (pushed + nws).sortedByDescending { it.severity.ordinal }

/** Map the config auto-dismiss level to the highest severity it covers ("at or below" semantics):
 *  "info" -> INFO only, "warning" -> INFO+WARNING, "critical" -> everything. "off"/unknown -> null
 *  (auto-dismiss disabled). */
fun autoDismissCutoff(configValue: String): NotifSeverity? =
    when (configValue.trim().lowercase(Locale.US)) {
        "info" -> NotifSeverity.INFO
        "warning" -> NotifSeverity.WARNING
        "critical" -> NotifSeverity.CRITICAL
        else -> null
    }

/**
 * Keys of rows due for auto-dismissal: severity at or below [cutoff] AND on screen for at least
 * [timeoutMs] (per [firstSeenMs]; a row with no recorded first-seen is treated as just arrived).
 * Null [cutoff] disables the feature (empty result).
 */
fun autoDismissKeys(
    items: List<NotificationItem>,
    cutoff: NotifSeverity?,
    firstSeenMs: Map<String, Long>,
    timeoutMs: Long,
    nowMs: Long,
): List<String> {
    if (cutoff == null) return emptyList()
    return items
        .filter { it.severity.ordinal <= cutoff.ordinal }
        .filter { nowMs - (firstSeenMs[it.key] ?: nowMs) >= timeoutMs }
        .map { it.key }
}

/**
 * Whether the home now-playing takeover should be showing: only while a session is [active] and
 * neither the paused-timeout dismissal ([pausedTimedOut]) nor the user's manual home-button
 * dismissal ([manualDismissed]) is in effect. Pure so App.kt can pin the gate instead of inlining
 * the `&&` chain.
 */
fun takeoverVisibleOf(active: Boolean, pausedTimedOut: Boolean, manualDismissed: Boolean): Boolean =
    active && !pausedTimedOut && !manualDismissed

/**
 * Label for the pinned now-playing row: "Title — Artist", with the artist (and its " — ") dropped
 * when null/blank. A null/blank title falls back to "Now playing" (pre-metadata streams), and an
 * artist with no title still yields just "Now playing". Same em-dash join as the takeover up-next
 * line.
 */
fun nowPlayingRowLabel(title: String?, artist: String?): String {
    val t = title?.takeIf { it.isNotBlank() } ?: return "Now playing"
    val a = artist?.takeIf { it.isNotBlank() }
    return if (a != null) "$t — $a" else t
}

/**
 * Grace window the mini-player card lingers after playback pauses/stops (indistinguishable on the
 * SendSpin/MA wire -- both report `stopped`) before auto-hiding: 5 minutes, measured from the
 * pause/stop moment itself -- NOT from whenever the card happens to become visible (see
 * [miniPlayerVisible]'s `pausedSinceMs` contract). The takeover's own ~60s paused-timeout hides the
 * takeover well inside this window, handing off to the card for the remainder of the same pause.
 */
const val MINIPLAYER_GRACE_MS = 5 * 60_000L

/**
 * Mini-player card visibility. [active] and no [takeoverVisible] gate it off entirely. While
 * [playing] it always shows; once paused it lingers until [MINIPLAYER_GRACE_MS] has elapsed since
 * [pausedSinceMs] (the device-clock instant `playing` last went false this session -- 0 means
 * "never observed paused", which hides the card since there is nothing to resume). Callers must
 * stamp [pausedSinceMs] at the actual pause instant, not at whenever this composable happens to
 * (re)mount, so the grace budget survives e.g. the takeover's own hand-off.
 */
fun miniPlayerVisible(
    active: Boolean,
    playing: Boolean,
    takeoverVisible: Boolean,
    pausedSinceMs: Long,
    nowMs: Long,
): Boolean {
    if (!active || takeoverVisible) return false
    if (playing) return true
    if (pausedSinceMs <= 0) return false
    return nowMs - pausedSinceMs < MINIPLAYER_GRACE_MS
}
