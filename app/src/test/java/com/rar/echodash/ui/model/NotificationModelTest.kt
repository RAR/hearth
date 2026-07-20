package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.notify.PushedNotification
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(NotifSeverity.WARNING, sevOf("Minor"))
        assertEquals(NotifSeverity.INFO, sevOf("Unknown"))
        assertEquals(NotifSeverity.INFO, sevOf("wat"))
    }

    // ---- min-severity filtering ----

    @Test
    fun minSeverityFiltersAtEachThreshold() {
        val attr = alertsAttr(
            mapOf("Event" to "Unknown thing", "ID" to "u", "Severity" to "Unknown"),
            mapOf("Event" to "Minor thing", "ID" to "m", "Severity" to "Minor"),
            mapOf("Event" to "Moderate thing", "ID" to "o", "Severity" to "Moderate"),
            mapOf("Event" to "Severe thing", "ID" to "s", "Severity" to "Severe"),
        )
        assertEquals(4, run("4", attr, NotifSeverity.INFO).size)
        // Minor now rides with Moderate in the WARNING band (advisories deserve amber); only the
        // unrecognized "Unknown" severity stays INFO and is filtered out at the WARNING cutoff.
        assertEquals(setOf("m", "o", "s"), run("4", attr, NotifSeverity.WARNING).map { it.key }.toSet())
        assertEquals(setOf("s"), run("4", attr, NotifSeverity.CRITICAL).map { it.key }.toSet())
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

    // ---- expiry: rows past their displayed end time are dropped ----

    @Test
    fun expiredEndIsExcluded() {
        val attr = alertsAttr(mapOf(
            "Event" to "Winter Storm Warning", "ID" to "1", "Severity" to "Severe",
            "Ends" to "2026-07-14T11:00:00+00:00", // before nowMs (noon)
        ))
        assertTrue(run("1", attr).isEmpty())
    }

    @Test
    fun futureEndIsIncluded() {
        val attr = alertsAttr(mapOf(
            "Event" to "Winter Storm Warning", "ID" to "1", "Severity" to "Severe",
            "Ends" to "2026-07-14T13:00:00+00:00", // after nowMs (noon)
        ))
        assertEquals(1, run("1", attr).size)
    }

    @Test
    fun expiredExpiresFallbackIsExcludedWhenEndsAbsent() {
        val attr = alertsAttr(mapOf(
            "Event" to "Flood Watch", "ID" to "1", "Severity" to "Moderate",
            "Ends" to null, "Expires" to "2026-07-14T11:00:00+00:00", // before nowMs
        ))
        assertTrue(run("1", attr).isEmpty())
    }

    @Test
    fun neitherEndsNorExpiresIsIncluded() {
        val attr = alertsAttr(mapOf(
            "Event" to "Special Weather Statement", "ID" to "1", "Severity" to "Minor",
        ))
        assertEquals(1, run("1", attr).size)
    }

    @Test
    fun unparseableEndsAndExpiresIsIncluded() {
        val attr = alertsAttr(mapOf(
            "Event" to "Special Weather Statement", "ID" to "1", "Severity" to "Minor",
            "Ends" to "junk", "Expires" to "also-junk",
        ))
        assertEquals(1, run("1", attr).size)
    }

    // ---- timestampMs from Onset ----

    @Test
    fun onsetEpochCarriesIntoTimestampMs() {
        val attr = alertsAttr(mapOf(
            "Event" to "E", "ID" to "1", "Severity" to "Severe",
            "Onset" to "2026-07-14T09:30:00+00:00",
        ))
        val expected = java.time.Instant.parse("2026-07-14T09:30:00Z").toEpochMilli()
        assertEquals(expected, run("1", attr).single().timestampMs)
    }

    @Test
    fun missingOrUnparseableOnsetYieldsNullTimestamp() {
        val noOnset = alertsAttr(mapOf("Event" to "E", "ID" to "1", "Severity" to "Severe"))
        assertNull(run("1", noOnset).single().timestampMs)
        val badOnset = alertsAttr(mapOf(
            "Event" to "E", "ID" to "1", "Severity" to "Severe", "Onset" to "not-a-date",
        ))
        assertNull(run("1", badOnset).single().timestampMs)
    }

    // ---- notifTimestampLabel ----

    @Test
    fun notifTimestampLabelNullInputYieldsNull() {
        assertNull(notifTimestampLabel(null, nowMs))
    }

    @Test
    fun notifTimestampLabelSameDayOmitsWeekday() {
        val ts = java.time.Instant.parse("2026-07-14T18:12:00Z").toEpochMilli() // same UTC day as nowMs
        val zone = java.time.ZoneId.systemDefault()
        val expected = java.time.Instant.ofEpochMilli(ts).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))
        assertEquals(expected, notifTimestampLabel(ts, nowMs))
        assertTrue(expected.first().isDigit()) // no weekday prefix
    }

    @Test
    fun notifTimestampLabelDifferentDayPrependsWeekday() {
        val ts = java.time.Instant.parse("2026-07-16T18:12:00Z").toEpochMilli() // Thursday, different day
        val zone = java.time.ZoneId.systemDefault()
        val expected = java.time.Instant.ofEpochMilli(ts).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a", java.util.Locale.ENGLISH))
        assertEquals(expected, notifTimestampLabel(ts, nowMs))
        assertTrue(expected.startsWith("Thu"))
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

    // ---- pushed mapping + merge ----

    @Test
    fun pushedItemsMapKeyAndDetail() {
        val items = listOf(
            PushedNotification("a", NotifSeverity.WARNING, "Title A", "Msg A", 500L, null),
            PushedNotification("b", NotifSeverity.INFO, "Title B", null, 700L, 123L),
        )
        val rows = pushedNotificationItems(items)
        assertEquals(listOf("push:a", "push:b"), rows.map { it.key })
        assertEquals("Msg A", rows[0].detail)
        assertNull(rows[1].detail)
        assertEquals("Title A", rows[0].title)
        assertEquals(NotifSeverity.WARNING, rows[0].severity)
        assertEquals(500L, rows[0].timestampMs)
        assertEquals(700L, rows[1].timestampMs)
    }

    @Test
    fun mergeSortsBySeverityPushedFirstWithinBandStable() {
        val pushed = listOf(
            NotificationItem("push:p1", NotifSeverity.INFO, "P1", null, null),
            NotificationItem("push:p2", NotifSeverity.CRITICAL, "P2", null, null),
        )
        val nws = listOf(
            NotificationItem("n1", NotifSeverity.CRITICAL, "N1", null, null),
            NotificationItem("n2", NotifSeverity.INFO, "N2", null, null),
        )
        val merged = mergeNotifications(pushed, nws)
        // CRITICAL band first (pushed p2 before nws n1), then INFO band (pushed p1 before nws n2).
        assertEquals(listOf("push:p2", "n1", "push:p1", "n2"), merged.map { it.key })
    }

    @Test
    fun mergeConstant() {
        assertEquals("push:", PUSH_KEY_PREFIX)
    }

    // ---- auto-dismiss ----

    @Test
    fun autoDismissCutoffMapping() {
        assertEquals(NotifSeverity.INFO, autoDismissCutoff("info"))
        assertEquals(NotifSeverity.WARNING, autoDismissCutoff("  Warning "))
        assertEquals(NotifSeverity.CRITICAL, autoDismissCutoff("critical"))
        assertNull(autoDismissCutoff("off"))
        assertNull(autoDismissCutoff("bogus"))
        assertNull(autoDismissCutoff(""))
    }

    @Test
    fun autoDismissKeysRespectsCutoffAndDwell() {
        val items = listOf(
            NotificationItem("old-info", NotifSeverity.INFO, "A", null, null),
            NotificationItem("new-info", NotifSeverity.INFO, "B", null, null),
            NotificationItem("old-crit", NotifSeverity.CRITICAL, "C", null, null),
            NotificationItem("unseen", NotifSeverity.INFO, "D", null, null),
        )
        val firstSeen = mapOf("old-info" to 0L, "new-info" to 9_000L, "old-crit" to 0L)
        // cutoff WARNING: INFO rows past the 10s dwell go; CRITICAL never; unseen treated as new.
        assertEquals(
            listOf("old-info"),
            autoDismissKeys(items, NotifSeverity.WARNING, firstSeen, 10_000L, 10_000L),
        )
        // dwell boundary is inclusive: exactly timeoutMs counts.
        assertEquals(
            listOf("old-info", "new-info"),
            autoDismissKeys(items, NotifSeverity.WARNING, firstSeen, 10_000L, 19_000L),
        )
        // cutoff CRITICAL sweeps everything past the dwell.
        assertEquals(
            listOf("old-info", "old-crit"),
            autoDismissKeys(items, NotifSeverity.CRITICAL, firstSeen, 10_000L, 10_000L),
        )
        // null cutoff = feature off.
        assertEquals(
            emptyList<String>(),
            autoDismissKeys(items, null, firstSeen, 10_000L, 999_999L),
        )
    }

    // ---- takeoverVisibleOf ----

    @Test
    fun takeoverHiddenWheneverInactive() {
        // active=false -> always hidden, regardless of the two flags (4 combinations).
        assertFalse(takeoverVisibleOf(active = false, pausedTimedOut = false, manualDismissed = false))
        assertFalse(takeoverVisibleOf(active = false, pausedTimedOut = true, manualDismissed = false))
        assertFalse(takeoverVisibleOf(active = false, pausedTimedOut = false, manualDismissed = true))
        assertFalse(takeoverVisibleOf(active = false, pausedTimedOut = true, manualDismissed = true))
    }

    @Test
    fun takeoverVisibleOnlyWhenActiveAndNeitherFlagSet() {
        assertTrue(takeoverVisibleOf(active = true, pausedTimedOut = false, manualDismissed = false))
    }

    @Test
    fun takeoverHiddenByEitherFlagWhileActive() {
        assertFalse(takeoverVisibleOf(active = true, pausedTimedOut = true, manualDismissed = false))
        assertFalse(takeoverVisibleOf(active = true, pausedTimedOut = false, manualDismissed = true))
        assertFalse(takeoverVisibleOf(active = true, pausedTimedOut = true, manualDismissed = true))
    }

    // ---- nowPlayingRowLabel ----

    @Test
    fun nowPlayingRowLabelJoinsTitleAndArtist() {
        assertEquals("Song — Artist", nowPlayingRowLabel("Song", "Artist"))
    }

    @Test
    fun nowPlayingRowLabelTitleOnlyWhenArtistAbsent() {
        assertEquals("Song", nowPlayingRowLabel("Song", null))
        assertEquals("Song", nowPlayingRowLabel("Song", "   ")) // blank artist treated as absent
    }

    @Test
    fun nowPlayingRowLabelFallsBackWhenTitleBlank() {
        assertEquals("Now playing", nowPlayingRowLabel(null, null))
        assertEquals("Now playing", nowPlayingRowLabel("   ", "Artist")) // blank title, artist ignored
        assertEquals("Now playing", nowPlayingRowLabel(null, "Artist"))  // artist without title
    }

    // ---- miniPlayerVisible ----

    @Test
    fun miniPlayerGraceConstantIsFiveMinutes() {
        assertEquals(5 * 60_000L, MINIPLAYER_GRACE_MS)
    }

    @Test
    fun miniPlayerVisibleWhilePlayingAndAllGatesOpen() {
        assertTrue(
            miniPlayerVisible(
                active = true, playing = true, takeoverVisible = false,
                pausedSinceMs = 0L, nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun miniPlayerHiddenByAnyGateWhilePlaying() {
        // Each gate individually hides the card even while actively playing.
        assertFalse(
            miniPlayerVisible(
                active = false, playing = true, takeoverVisible = false,
                pausedSinceMs = 0L, nowMs = 1_000L,
            ),
        )
        assertFalse(
            miniPlayerVisible(
                active = true, playing = true, takeoverVisible = true,
                pausedSinceMs = 0L, nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun miniPlayerVisibleWithinGraceAfterPause() {
        // Paused 1 minute ago, well inside the 5-minute grace window.
        val pausedAt = 100_000L
        assertTrue(
            miniPlayerVisible(
                active = true, playing = false, takeoverVisible = false,
                pausedSinceMs = pausedAt, nowMs = pausedAt + 60_000L,
            ),
        )
    }

    @Test
    fun miniPlayerHiddenAtOrAfterGraceExpiry() {
        val pausedAt = 100_000L
        // Exactly at the boundary (comparison is strict "<") and past it both hide the card.
        assertFalse(
            miniPlayerVisible(
                active = true, playing = false, takeoverVisible = false,
                pausedSinceMs = pausedAt, nowMs = pausedAt + MINIPLAYER_GRACE_MS,
            ),
        )
        assertFalse(
            miniPlayerVisible(
                active = true, playing = false, takeoverVisible = false,
                pausedSinceMs = pausedAt, nowMs = pausedAt + MINIPLAYER_GRACE_MS + 1,
            ),
        )
    }

    @Test
    fun miniPlayerHiddenWhenNeverPlayingThisSession() {
        // pausedSinceMs=0 means "never observed paused/playing" -- nothing to resume, so hidden
        // regardless of how much time has passed.
        assertFalse(
            miniPlayerVisible(
                active = true, playing = false, takeoverVisible = false,
                pausedSinceMs = 0L, nowMs = 999_999L,
            ),
        )
    }

    @Test
    fun miniPlayerHiddenWheneverInactiveRegardlessOfOtherState() {
        val pausedAt = 100_000L
        assertFalse(
            miniPlayerVisible(
                active = false, playing = false, takeoverVisible = false,
                pausedSinceMs = pausedAt, nowMs = pausedAt + 60_000L,
            ),
        )
        assertFalse(
            miniPlayerVisible(
                active = false, playing = true, takeoverVisible = false,
                pausedSinceMs = 0L, nowMs = pausedAt,
            ),
        )
    }
}
