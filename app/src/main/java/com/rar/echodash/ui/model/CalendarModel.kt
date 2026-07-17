package com.rar.echodash.ui.model

import com.rar.echodash.config.CalendarConfig
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One calendar event resolved for display. All comparisons are epoch-millis so the model never
 *  reads the wall clock (testability); [colorArgb] is a 0xFFRRGGBB Long for Compose's Color(Long). */
data class CalendarEvent(
    val calendarName: String,   // display name from config
    val colorArgb: Long,        // resolved palette color from config
    val title: String,          // event summary; blank summary -> "(untitled)"
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
)

/** One agenda column: a day [label] ("Today"/"Tomorrow"/day name) and the events overlapping it. */
data class AgendaDay(val label: String, val events: List<CalendarEvent>)

/** Fixed calendar palette (Material 300/400 tints). Unknown/blank key -> blue. */
fun calendarColorArgb(key: String): Long = when (key.trim().lowercase(Locale.US)) {
    "blue" -> 0xFF64B5F6
    "green" -> 0xFF81C784
    "amber" -> 0xFFFFD54F
    "red" -> 0xFFE57373
    "purple" -> 0xFFBA68C8
    "teal" -> 0xFF4DB6AC
    "orange" -> 0xFFFFB74D
    "pink" -> 0xFFF06292
    else -> 0xFF64B5F6
}

/**
 * Parse a calendar.get_events return_response into a flat, start-sorted event list. Walks
 * `response.<entity>.events` for each configured calendar; entities absent from the response are
 * skipped. Timed events parse via [OffsetDateTime]; all-day events (a `start` with no `T`) parse
 * via [LocalDate] expanded to local midnight in [zone]. Malformed/reversed/missing-date entries are
 * dropped, never thrown.
 */
fun parseCalendarEvents(
    result: JsonElement?,
    configs: List<CalendarConfig>,
    zone: ZoneId,
): List<CalendarEvent> {
    val response = ((result as? JsonObject)?.get("response") as? JsonObject) ?: return emptyList()
    val events = ArrayList<CalendarEvent>()
    for (config in configs) {
        val entry = response[config.entity] as? JsonObject ?: continue
        val arr = entry["events"] as? JsonArray ?: continue
        val name = config.name.ifBlank { config.entity.substringAfter('.') }
        val color = calendarColorArgb(config.color)
        for (element in arr) {
            val o = element as? JsonObject ?: continue
            val startStr = (o["start"] as? JsonPrimitive)?.contentOrNull ?: continue
            val endStr = (o["end"] as? JsonPrimitive)?.contentOrNull ?: continue
            val allDay = !startStr.contains('T')
            val startMs = parseCalMs(startStr, zone) ?: continue
            val endMs = parseCalMs(endStr, zone) ?: continue
            if (endMs < startMs) continue // drop reversed spans
            val title = (o["summary"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: "(untitled)"
            events.add(CalendarEvent(name, color, title, startMs, endMs, allDay))
        }
    }
    return events.sortedBy { it.startMs }
}

private fun parseCalMs(value: String, zone: ZoneId): Long? = runCatching {
    if (value.contains('T')) {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } else {
        LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}.getOrNull()

/**
 * [dayCount] day columns (default 3) starting at [nowMs]'s local date. An event appears in every
 * column its `[startMs, endMs)` span overlaps; already-ended events (`endMs <= nowMs`) are excluded
 * everywhere. Within a column: all-day events first, then by `startMs`, stable (ties keep input order).
 */
fun agendaDays(events: List<CalendarEvent>, nowMs: Long, zone: ZoneId, dayCount: Int = 3): List<AgendaDay> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (0 until dayCount).map { offset ->
        val day = today.plusDays(offset.toLong())
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val label = when (offset) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        }
        val dayEvents = events
            .filter { it.endMs > nowMs && it.startMs < dayEnd && it.endMs > dayStart }
            .sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.startMs })
        AgendaDay(label, dayEvents)
    }
}

/** The earliest not-yet-ended event (a currently-running event counts); null when none. */
fun nextEventCard(events: List<CalendarEvent>, nowMs: Long): CalendarEvent? =
    events.filter { it.endMs > nowMs }.minByOrNull { it.startMs }

/**
 * Home-card time text with a day prefix: all-day -> "All day"; a running timed event -> "Now";
 * otherwise the start time. Same-day -> time only; next day -> "Tomorrow <time>"; later ->
 * "<Weekday> <time>". All-day is checked before running so an all-day event reads "All day".
 */
fun eventTimeLabel(event: CalendarEvent, nowMs: Long, zone: ZoneId, is24h: Boolean): String {
    val timePart = when {
        event.allDay -> "All day"
        event.startMs <= nowMs && event.endMs > nowMs -> "Now"
        else -> formatClockTime(event.startMs, zone, is24h)
    }
    if (timePart == "Now") return "Now" // a running timed event needs no day prefix
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val startDate = Instant.ofEpochMilli(event.startMs).atZone(zone).toLocalDate()
    val dayOffset = ChronoUnit.DAYS.between(today, startDate)
    return when {
        dayOffset <= 0L -> timePart
        dayOffset == 1L -> "Tomorrow $timePart"
        else -> "${startDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $timePart"
    }
}

/** Panel-row time text (no day prefix): all-day -> "All day"; running timed -> "Now"; else start time. */
fun eventClockLabel(event: CalendarEvent, nowMs: Long, zone: ZoneId, is24h: Boolean): String = when {
    event.allDay -> "All day"
    event.startMs <= nowMs && event.endMs > nowMs -> "Now"
    else -> formatClockTime(event.startMs, zone, is24h)
}

private fun formatClockTime(ms: Long, zone: ZoneId, is24h: Boolean): String {
    val time = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    val pattern = if (is24h) "HH:mm" else "h:mm a"
    return time.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
}
