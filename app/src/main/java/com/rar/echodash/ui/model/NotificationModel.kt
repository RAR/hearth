package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
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
)

/** Map a config min-severity string to the enum. Unknown/blank -> INFO (show everything). */
fun notifSeverityOf(configValue: String): NotifSeverity =
    when (configValue.trim().lowercase(Locale.US)) {
        "severe" -> NotifSeverity.CRITICAL
        "moderate" -> NotifSeverity.WARNING
        else -> NotifSeverity.INFO
    }

/** Map an NWS `Severity` attribute value to the enum. Extreme/Severe -> CRITICAL, Moderate -> WARNING,
 *  Minor/Unknown/anything else -> INFO. */
private fun severityOfAlert(raw: String?): NotifSeverity =
    when (raw?.trim()?.lowercase(Locale.US)) {
        "extreme", "severe" -> NotifSeverity.CRITICAL
        "moderate" -> NotifSeverity.WARNING
        else -> NotifSeverity.INFO
    }

private val DAY_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH)
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** Parse an ISO-8601-with-offset string; null on absent/blank/unparseable. */
private fun parseOffset(raw: String?): OffsetDateTime? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

/** "until <time>" suffix from Ends (fallback Expires); null when neither parses. Weekday dropped
 *  when the end lands on the same local day as [nowMs]. */
private fun untilSuffix(endsRaw: String?, expiresRaw: String?, nowMs: Long): String? {
    val odt = parseOffset(endsRaw) ?: parseOffset(expiresRaw) ?: return null
    val zone = ZoneId.systemDefault()
    val local = odt.atZoneSameInstant(zone)
    val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val fmt = if (local.toLocalDate() == nowDate) TIME_FMT else DAY_TIME_FMT
    return "until " + local.format(fmt)
}

/**
 * Derive notification rows from the NWS alerts sensor. Returns an empty list when [sensorId] is
 * null, the entity is missing, the state is non-numeric (unavailable/unknown), or the `Alerts`
 * attribute is absent/not an array. Never throws: malformed entries are skipped.
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

        val title = buildString {
            append(event)
            untilSuffix(field("Ends"), field("Expires"), nowMs)?.let { append(" · ").append(it) }
        }
        val detail = listOf(field("Headline"), field("Description"), field("Instruction"))
            .mapNotNull { it?.takeIf { s -> s.isNotBlank() } }
            .joinToString("\n\n")
            .ifBlank { null }

        Row(
            item = NotificationItem(key = id, severity = severity, title = title, detail = detail),
            onset = parseOffset(field("Onset"))?.toInstant(),
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
