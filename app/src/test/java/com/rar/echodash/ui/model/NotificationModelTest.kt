package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationModelTest {

    // Time-formatting tests convert to the JVM default zone. Pin it to UTC so the
    // crafted "+00:00" alert times render at the same wall-clock we assert on.
    private var savedTz: TimeZone? = null

    @Before
    fun pinUtc() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTz() {
        savedTz?.let { TimeZone.setDefault(it) }
    }

    // Tuesday 2026-07-14 12:00 UTC — the "now" anchor for the time tests.
    private val nowMs = java.time.Instant.parse("2026-07-14T12:00:00Z").toEpochMilli()

    private val SENSOR = "sensor.nws_alerts_alerts"

    /** Build the sensor entity: [count] state + an Alerts array assembled from [alerts]. */
    private fun sensor(count: String, alerts: JsonObject): EntityState =
        EntityState(SENSOR, count, alerts, 0L)

    /** One Alerts object with the whole array wrapper, from field pairs. Null values are omitted. */
    private fun alertsAttr(vararg objects: Map<String, String?>): JsonObject =
        buildJsonObject {
            putJsonArray("Alerts") {
                objects.forEach { fields ->
                    addJsonObject {
                        fields.forEach { (k, v) -> if (v != null) put(k, v) }
                    }
                }
            }
        }

    private fun run(
        state: String,
        alerts: JsonObject,
        minSeverity: NotifSeverity = NotifSeverity.INFO,
        sensorId: String? = SENSOR,
        now: Long = nowMs,
    ): List<NotificationItem> =
        nwsNotifications(sensorId, minSeverity, mapOf(SENSOR to sensor(state, alerts)), now)

    // ---- config severity mapping ----

    @Test
    fun notifSeverityOfMapsConfigStrings() {
        assertEquals(NotifSeverity.INFO, notifSeverityOf("minor"))
        assertEquals(NotifSeverity.WARNING, notifSeverityOf("moderate"))
        assertEquals(NotifSeverity.CRITICAL, notifSeverityOf("severe"))
        assertEquals(NotifSeverity.CRITICAL, notifSeverityOf("  SEVERE  ")) // trim + case
        assertEquals(NotifSeverity.INFO, notifSeverityOf("bogus"))          // unknown -> INFO
    }

    // ---- severity mapping incl. unknown ----

    @Test
    fun severityMappingIncludingUnknown() {
        fun sevOf(raw: String): NotifSeverity =
            run("1", alertsAttr(mapOf("Event" to "E", "ID" to "1", "Severity" to raw))).single().severity
        assertEquals(NotifSeverity.CRITICAL, sevOf("Extreme"))
        assertEquals(NotifSeverity.CRITICAL, sevOf("Severe"))
        assertEquals(NotifSeverity.WARNING, sevOf("Moderate"))
        assertEquals(NotifSeverity.INFO, sevOf("Minor"))
        assertEquals(NotifSeverity.INFO, sevOf("Unknown"))
        assertEquals(NotifSeverity.INFO, sevOf("wat"))
    }

    // ---- min-severity filtering ----

    @Test
    fun minSeverityFiltersAtEachThreshold() {
        val attr = alertsAttr(
            mapOf("Event" to "Minor thing", "ID" to "m", "Severity" to "Minor"),
            mapOf("Event" to "Moderate thing", "ID" to "o", "Severity" to "Moderate"),
            mapOf("Event" to "Severe thing", "ID" to "s", "Severity" to "Severe"),
        )
        assertEquals(3, run("3", attr, NotifSeverity.INFO).size)
        assertEquals(setOf("o", "s"), run("3", attr, NotifSeverity.WARNING).map { it.key }.toSet())
        assertEquals(setOf("s"), run("3", attr, NotifSeverity.CRITICAL).map { it.key }.toSet())
    }

    // ---- sort: severity, then onset asc (unparseable last), then title ----

    @Test
    fun sortsBySeverityThenOnsetThenTitle() {
        val attr = alertsAttr(
            mapOf("Event" to "Bravo", "ID" to "b", "Severity" to "Moderate", "Onset" to "2026-07-14T10:00:00+00:00"),
            mapOf("Event" to "Alpha", "ID" to "a", "Severity" to "Severe", "Onset" to "2026-07-14T09:00:00+00:00"),
            mapOf("Event" to "Charlie", "ID" to "c", "Severity" to "Severe", "Onset" to "2026-07-14T08:00:00+00:00"),
            mapOf("Event" to "Delta", "ID" to "d", "Severity" to "Moderate", "Onset" to "not-a-date"),
        )
        // CRITICAL first, earliest onset first within a tier; unparseable onset sinks to the tier's end.
        assertEquals(listOf("c", "a", "b", "d"), run("4", attr).map { it.key })
    }

    @Test
    fun sortTieBreaksOnTitleWhenSeverityAndOnsetEqual() {
        val attr = alertsAttr(
            mapOf("Event" to "Zebra", "ID" to "z", "Severity" to "Severe"),
            mapOf("Event" to "Apple", "ID" to "a", "Severity" to "Severe"),
        )
        // Same severity, both onsets unparseable/absent -> title ascending.
        assertEquals(listOf("Apple", "Zebra"), run("2", attr).map { it.title })
    }

    // ---- title "until" formatting ----

    @Test
    fun titleUsesEndsWhenPresentOtherDay() {
        val attr = alertsAttr(mapOf(
            "Event" to "Winter Storm Warning", "ID" to "1", "Severity" to "Severe",
            "Ends" to "2026-07-16T19:00:00+00:00", // Thursday
        ))
        assertEquals("Winter Storm Warning · until Thu 7:00 PM", run("1", attr).single().title)
    }

    @Test
    fun titleFallsBackToExpiresWhenEndsNull() {
        val attr = alertsAttr(mapOf(
            "Event" to "Flood Watch", "ID" to "1", "Severity" to "Moderate",
            "Ends" to null, "Expires" to "2026-07-16T19:00:00+00:00",
        ))
        assertEquals("Flood Watch · until Thu 7:00 PM", run("1", attr).single().title)
    }

    @Test
    fun titleSameDayOmitsWeekday() {
        val attr = alertsAttr(mapOf(
            "Event" to "Heat Advisory", "ID" to "1", "Severity" to "Minor",
            "Ends" to "2026-07-14T19:00:00+00:00", // same UTC day as nowMs
        ))
        assertEquals("Heat Advisory · until 7:00 PM", run("1", attr).single().title)
    }

    @Test
    fun titleBareEventWhenBothUnparseable() {
        val attr = alertsAttr(mapOf(
            "Event" to "Special Weather Statement", "ID" to "1",
            "Ends" to "junk", "Expires" to "",
        ))
        assertEquals("Special Weather Statement", run("1", attr).single().title)
    }

    // ---- detail assembly ----

    @Test
    fun detailJoinsPresentPartsWithBlankLines() {
        val attr = alertsAttr(mapOf(
            "Event" to "E", "ID" to "1",
            "Headline" to "Head", "Description" to "Desc", "Instruction" to "Do this",
        ))
        assertEquals("Head\n\nDesc\n\nDo this", run("1", attr).single().detail)
    }

    @Test
    fun detailSkipsNullAndBlankParts() {
        val attr = alertsAttr(mapOf(
            "Event" to "E", "ID" to "1",
            "Headline" to "Head", "Description" to "  ", "Instruction" to null,
        ))
        assertEquals("Head", run("1", attr).single().detail)
    }

    @Test
    fun detailNullWhenAllPartsBlank() {
        val attr = alertsAttr(mapOf(
            "Event" to "E", "ID" to "1",
            "Headline" to "", "Description" to "   ", "Instruction" to null,
        ))
        assertNull(run("1", attr).single().detail)
    }

    // ---- empty / degraded inputs ----

    @Test
    fun unavailableStateYieldsEmpty() {
        val attr = alertsAttr(mapOf("Event" to "E", "ID" to "1"))
        assertTrue(run("unavailable", attr).isEmpty())
        assertTrue(run("unknown", attr).isEmpty())
    }

    @Test
    fun missingAlertsAttrYieldsEmpty() {
        // Numeric state but no Alerts key at all.
        val noAlerts = buildJsonObject { put("friendly_name", "NWS") }
        assertTrue(run("2", noAlerts).isEmpty())
    }

    @Test
    fun alertsNotAnArrayYieldsEmpty() {
        val wrong = buildJsonObject { put("Alerts", "nope") }
        assertTrue(run("1", wrong).isEmpty())
    }

    @Test
    fun nullSensorIdOrMissingEntityYieldsEmpty() {
        val attr = alertsAttr(mapOf("Event" to "E", "ID" to "1"))
        assertTrue(run("1", attr, sensorId = null).isEmpty())
        assertTrue(nwsNotifications("sensor.absent", NotifSeverity.INFO, emptyMap(), nowMs).isEmpty())
    }

    // ---- malformed entries skipped ----

    @Test
    fun malformedEntriesSkippedSiblingsKept() {
        val attr = buildJsonObject {
            putJsonArray("Alerts") {
                addJsonObject { put("Event", "Missing ID") }              // no ID -> skipped
                addJsonObject { put("ID", "x") }                          // no Event -> skipped
                add("not-an-object")                                      // wrong type -> skipped
                addJsonObject { put("Event", "Good"); put("ID", "g"); put("Severity", "Severe") }
            }
        }
        val items = run("4", attr)
        assertEquals(1, items.size)
        assertEquals("g", items.single().key)
        assertEquals("Good", items.single().title)
    }
}
