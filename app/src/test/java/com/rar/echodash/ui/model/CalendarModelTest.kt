package com.rar.echodash.ui.model

import com.rar.echodash.config.CalendarConfig
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarModelTest {
    private val zone: ZoneId = ZoneId.of("America/New_York")
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    private fun dayMs(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun timed(start: String, end: String, title: String = "E", color: Long = 0xFF64B5F6) =
        CalendarEvent("c", color, title, ms(start), ms(end), allDay = false)
    private fun allDay(start: String, end: String, title: String = "E") =
        CalendarEvent("c", 0xFF64B5F6, title, dayMs(start), dayMs(end), allDay = true)

    // ---- palette ----

    @Test
    fun paletteResolvesKnownKeysAndFallsBackToBlue() {
        assertEquals(0xFF64B5F6, calendarColorArgb("blue"))
        assertEquals(0xFF81C784, calendarColorArgb("green"))
        assertEquals(0xFFFFD54F, calendarColorArgb("amber"))
        assertEquals(0xFFE57373, calendarColorArgb("red"))
        assertEquals(0xFFBA68C8, calendarColorArgb("purple"))
        assertEquals(0xFF4DB6AC, calendarColorArgb("teal"))
        assertEquals(0xFFFFB74D, calendarColorArgb("orange"))
        assertEquals(0xFFF06292, calendarColorArgb("pink"))
        assertEquals(0xFF81C784, calendarColorArgb("  Green ")) // trim + lower-case
        assertEquals(0xFF64B5F6, calendarColorArgb("chartreuse")) // unknown -> blue
    }

    // ---- parseCalendarEvents ----

    @Test
    fun parsesTimedAndAllDayEventsWithConfigNameAndColor() {
        val result = json("""
          {"response":{"calendar.personal":{"events":[
            {"summary":"Dentist","start":"2026-07-14T09:00:00-04:00","end":"2026-07-14T10:00:00-04:00"},
            {"summary":"Vacation","start":"2026-07-15","end":"2026-07-17"}
          ]}}}
        """)
        val configs = listOf(CalendarConfig(entity = "calendar.personal", name = "Personal", color = "green"))
        val events = parseCalendarEvents(result, configs, zone)
        assertEquals(2, events.size)
        assertEquals("Dentist", events[0].title)
        assertEquals("Personal", events[0].calendarName)
        assertEquals(0xFF81C784, events[0].colorArgb)
        assertEquals(false, events[0].allDay)
        assertEquals(ms("2026-07-14T09:00:00-04:00"), events[0].startMs)
        assertEquals(true, events[1].allDay)
        assertEquals(dayMs("2026-07-15"), events[1].startMs) // all-day expands to local midnight
        assertEquals(dayMs("2026-07-17"), events[1].endMs)
    }

    @Test
    fun blankNameFallsBackToEntityTail() {
        val result = json("""{"response":{"calendar.andrew_s_personal":{"events":[
            {"summary":"X","start":"2026-07-14T09:00:00-04:00","end":"2026-07-14T10:00:00-04:00"}]}}}""")
        val events = parseCalendarEvents(result, listOf(CalendarConfig(entity = "calendar.andrew_s_personal")), zone)
        assertEquals("andrew_s_personal", events[0].calendarName)
    }

    @Test
    fun blankSummaryBecomesUntitled() {
        val result = json("""{"response":{"calendar.x":{"events":[
            {"summary":"   ","start":"2026-07-14T09:00:00-04:00","end":"2026-07-14T10:00:00-04:00"},
            {"start":"2026-07-14T11:00:00-04:00","end":"2026-07-14T12:00:00-04:00"}
          ]}}}""")
        val events = parseCalendarEvents(result, listOf(CalendarConfig(entity = "calendar.x")), zone)
        assertEquals(2, events.size)
        assertEquals("(untitled)", events[0].title)
        assertEquals("(untitled)", events[1].title)
    }

    @Test
    fun dropsMalformedReversedAndSkipsMissingEntity() {
        val result = json("""{"response":{"calendar.x":{"events":[
            {"summary":"bad date","start":"not-a-date","end":"2026-07-14T10:00:00-04:00"},
            {"summary":"no start","end":"2026-07-14T10:00:00-04:00"},
            {"summary":"reversed","start":"2026-07-14T12:00:00-04:00","end":"2026-07-14T11:00:00-04:00"},
            {"summary":"good","start":"2026-07-14T09:00:00-04:00","end":"2026-07-14T10:00:00-04:00"}
          ]}}}""")
        // calendar.missing is absent from the response -> skipped, no throw.
        val configs = listOf(CalendarConfig(entity = "calendar.x"), CalendarConfig(entity = "calendar.missing"))
        val events = parseCalendarEvents(result, configs, zone)
        assertEquals(1, events.size)
        assertEquals("good", events[0].title)
    }

    @Test
    fun mergesMultipleCalendarsSortedByStart() {
        val result = json("""{"response":{
            "calendar.a":{"events":[{"summary":"Late","start":"2026-07-14T15:00:00-04:00","end":"2026-07-14T16:00:00-04:00"}]},
            "calendar.b":{"events":[{"summary":"Early","start":"2026-07-14T08:00:00-04:00","end":"2026-07-14T09:00:00-04:00"}]}
          }}""")
        val configs = listOf(
            CalendarConfig(entity = "calendar.a", name = "A", color = "red"),
            CalendarConfig(entity = "calendar.b", name = "B", color = "teal"),
        )
        val events = parseCalendarEvents(result, configs, zone)
        assertEquals(listOf("Early", "Late"), events.map { it.title }) // sorted by startMs
        assertEquals(0xFF4DB6AC, events[0].colorArgb) // Early is from calendar.b (teal)
        assertEquals("A", events[1].calendarName)
    }

    @Test
    fun nullOrEmptyResponseYieldsEmpty() {
        val configs = listOf(CalendarConfig(entity = "calendar.x"))
        assertEquals(emptyList<CalendarEvent>(), parseCalendarEvents(null, configs, zone))
        assertEquals(emptyList<CalendarEvent>(), parseCalendarEvents(json("""{"response":{}}"""), configs, zone))
    }

    // ---- agendaDays ----

    @Test
    fun agendaProducesThreeLabeledDays() {
        val now = ms("2026-07-14T12:00:00-04:00") // Tuesday
        val days = agendaDays(emptyList(), now, zone)
        assertEquals(3, days.size)
        assertEquals("Today", days[0].label)
        assertEquals("Tomorrow", days[1].label)
        assertEquals("Thursday", days[2].label) // 2026-07-16
    }

    @Test
    fun multiDayEventSpansColumnsAllDayFirstEndedExcluded() {
        val now = ms("2026-07-14T12:00:00-04:00")
        val events = listOf(
            allDay("2026-07-14", "2026-07-16", "Conference"),            // end exclusive: Tue + Wed only
            timed("2026-07-14T13:00:00-04:00", "2026-07-14T14:00:00-04:00", "Lunch"),
            timed("2026-07-14T08:00:00-04:00", "2026-07-14T09:00:00-04:00", "Ended"),
        )
        val days = agendaDays(events, now, zone)
        assertEquals(listOf("Conference", "Lunch"), days[0].events.map { it.title }) // all-day first, ended dropped
        assertEquals(listOf("Conference"), days[1].events.map { it.title })          // still spans Wed
        assertEquals(emptyList<String>(), days[2].events.map { it.title })            // end exclusive -> not Thu
    }

    // ---- nextEventCard ----

    @Test
    fun nextEventPrefersRunningThenSkipsEnded() {
        val now = ms("2026-07-14T12:00:00-04:00")
        val running = timed("2026-07-14T11:30:00-04:00", "2026-07-14T12:30:00-04:00", "Running")
        val later = timed("2026-07-14T15:00:00-04:00", "2026-07-14T16:00:00-04:00", "Later")
        val ended = timed("2026-07-14T09:00:00-04:00", "2026-07-14T10:00:00-04:00", "Ended")
        assertEquals("Running", nextEventCard(listOf(ended, later, running), now)?.title)
        assertNull(nextEventCard(listOf(ended), now))
        assertNull(nextEventCard(emptyList(), now))
    }

    // ---- eventTimeLabel (home card) ----

    @Test
    fun eventTimeLabelVariants() {
        val now = ms("2026-07-14T12:00:00-04:00")
        assertEquals("Now", eventTimeLabel(timed("2026-07-14T11:00:00-04:00", "2026-07-14T13:00:00-04:00"), now, zone, is24h = false))
        assertEquals("All day", eventTimeLabel(allDay("2026-07-14", "2026-07-15"), now, zone, is24h = false))
        assertEquals("3:00 PM", eventTimeLabel(timed("2026-07-14T15:00:00-04:00", "2026-07-14T16:00:00-04:00"), now, zone, is24h = false))
        assertEquals("15:00", eventTimeLabel(timed("2026-07-14T15:00:00-04:00", "2026-07-14T16:00:00-04:00"), now, zone, is24h = true))
        assertEquals("Tomorrow 9:00 AM", eventTimeLabel(timed("2026-07-15T09:00:00-04:00", "2026-07-15T10:00:00-04:00"), now, zone, is24h = false))
        assertEquals("Thursday 9:00 AM", eventTimeLabel(timed("2026-07-16T09:00:00-04:00", "2026-07-16T10:00:00-04:00"), now, zone, is24h = false))
        assertEquals("Tomorrow All day", eventTimeLabel(allDay("2026-07-15", "2026-07-16"), now, zone, is24h = false))
    }

    // ---- eventClockLabel (panel) ----

    @Test
    fun eventClockLabelBareTimeNoDayPrefix() {
        val now = ms("2026-07-14T12:00:00-04:00")
        assertEquals("9:00 AM", eventClockLabel(timed("2026-07-14T09:00:00-04:00", "2026-07-14T10:00:00-04:00"), 0L, zone, is24h = false))
        assertEquals("09:00", eventClockLabel(timed("2026-07-14T09:00:00-04:00", "2026-07-14T10:00:00-04:00"), 0L, zone, is24h = true))
        assertEquals("All day", eventClockLabel(allDay("2026-07-14", "2026-07-15"), now, zone, is24h = false))
        assertEquals("Now", eventClockLabel(timed("2026-07-14T11:00:00-04:00", "2026-07-14T13:00:00-04:00"), now, zone, is24h = false))
        // A future event tomorrow shows only its time in the panel (no "Tomorrow" prefix).
        assertEquals("9:00 AM", eventClockLabel(timed("2026-07-15T09:00:00-04:00", "2026-07-15T10:00:00-04:00"), now, zone, is24h = false))
    }
}
