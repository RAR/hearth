package com.rar.hearth.ui.model

import com.rar.hearth.config.ClaudeUsageConfig
import com.rar.hearth.ha.EntityState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class ClaudeUsageModelTest {
    private val ny: ZoneId = ZoneId.of("America/New_York")

    private fun st(id: String, state: String) = EntityState(id, state, JsonObject(emptyMap()), 0L)

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    /** The live shape from the hass_claude_usage integration on 2026-07-26. */
    private val cfg = ClaudeUsageConfig(
        session = "sensor.session",
        sessionReset = "sensor.session_reset",
        week = "sensor.week",
        weekReset = "sensor.week_reset",
        pace = "sensor.pace",
    )

    private fun live() = mapOf(
        "sensor.session" to st("sensor.session", "9.0"),
        "sensor.session_reset" to st("sensor.session_reset", "2026-07-26T23:30:00+00:00"),
        "sensor.week" to st("sensor.week", "2.0"),
        "sensor.week_reset" to st("sensor.week_reset", "2026-08-02T09:00:00+00:00"),
        "sensor.pace" to st("sensor.pace", "-6.2"),
    )

    @Test
    fun buildsBothBarsFromLiveSensorShape() {
        val card = claudeUsageCard(cfg, live(), ms("2026-07-26T18:00:00+00:00"), ny, is24 = false)!!
        assertEquals(listOf("Session", "Week"), card.bars.map { it.label })
        assertEquals(listOf(9, 2), card.bars.map { it.percent })
    }

    @Test
    fun nearResetRendersAsTimeAndFarOneAsWeekday() {
        // 23:30Z on the 26th is 19:30 in New York — 5.5h from the 14:00 local "now".
        val card = claudeUsageCard(cfg, live(), ms("2026-07-26T18:00:00+00:00"), ny, is24 = false)!!
        assertEquals("7:30p", card.bars[0].resetLabel)
        // The weekly reset is a week out, so it degrades to a weekday name.
        assertEquals("Sun", card.bars[1].resetLabel)
    }

    @Test
    fun resetTimeHonoursThe24HourSetting() {
        val card = claudeUsageCard(cfg, live(), ms("2026-07-26T18:00:00+00:00"), ny, is24 = true)
        assertEquals("19:30", card!!.bars[0].resetLabel)
    }

    /**
     * The live bug this guards: the 5-hour window rolled at 7:30pm and the next reset landed at
     * 00:30 the NEXT local day, which a same-date rule rendered as "Mon" for something five hours
     * away. Proximity decides the form, not the calendar date.
     */
    @Test
    fun aResetAfterMidnightButHoursAwayStillReadsAsATime() {
        val entities = live() + ("sensor.session_reset" to
            st("sensor.session_reset", "2026-07-27T04:30:00+00:00"))
        // 04:30Z is 00:30 Mon in New York; "now" is 19:39 Sun — a different date, 4.85h away.
        val card = claudeUsageCard(cfg, entities, ms("2026-07-26T23:39:00+00:00"), ny, is24 = false)
        assertEquals("12:30a", card!!.bars[0].resetLabel)
    }

    @Test
    fun aResetPastTheNearWindowFallsBackToTheWeekday() {
        // 19h out — just past the 18h boundary, so the weekday form takes over.
        val entities = live() + ("sensor.session_reset" to
            st("sensor.session_reset", "2026-07-27T14:00:00+00:00"))
        val card = claudeUsageCard(cfg, entities, ms("2026-07-26T19:00:00+00:00"), ny, is24 = false)
        assertEquals("Mon", card!!.bars[0].resetLabel)
    }

    /** The zone still decides which weekday a far-out UTC instant lands on. */
    @Test
    fun weekdayIsResolvedInTheSuppliedZoneNotUtc() {
        // 01:00Z Monday is still Sunday evening in New York.
        val entities = live() + ("sensor.week_reset" to
            st("sensor.week_reset", "2026-08-03T01:00:00+00:00"))
        val card = claudeUsageCard(cfg, entities, ms("2026-07-26T18:00:00+00:00"), ny, is24 = false)
        assertEquals("Sun", card!!.bars[1].resetLabel)
    }

    @Test
    fun paceRendersSignedDirectionInWords() {
        val card = claudeUsageCard(cfg, live(), ms("2026-07-26T18:00:00+00:00"), ny, is24 = false)
        assertEquals("6% under pace", card!!.paceText)
    }

    @Test
    fun positivePaceReadsAsOverAndZeroAsOnPace() {
        val over = live() + ("sensor.pace" to st("sensor.pace", "12.4"))
        assertEquals(
            "12% over pace",
            claudeUsageCard(cfg, over, ms("2026-07-26T18:00:00+00:00"), ny, false)!!.paceText,
        )
        val flat = live() + ("sensor.pace" to st("sensor.pace", "0.0"))
        assertEquals(
            "on pace",
            claudeUsageCard(cfg, flat, ms("2026-07-26T18:00:00+00:00"), ny, false)!!.paceText,
        )
    }

    /**
     * The live bug this guards: Weekly Sonnet read "unavailable" the day this shipped. An
     * unavailable bucket must DROP its row — rendering it as 0% would assert "none used", which is
     * the opposite of "we don't know".
     */
    @Test
    fun unavailablePercentageDropsItsRowRatherThanReadingZero() {
        val entities = live() + ("sensor.week" to st("sensor.week", "unavailable"))
        val card = claudeUsageCard(cfg, entities, ms("2026-07-26T18:00:00+00:00"), ny, false)!!
        assertEquals(listOf("Session"), card.bars.map { it.label })
    }

    @Test
    fun unknownAndNonNumericStatesAreAlsoDropped() {
        for (dead in listOf("unknown", "none", "", "  ", "N/A")) {
            val entities = live() + ("sensor.session" to st("sensor.session", dead))
            val card = claudeUsageCard(cfg, entities, ms("2026-07-26T18:00:00+00:00"), ny, false)!!
            assertEquals("state '$dead' should drop the row", listOf("Week"), card.bars.map { it.label })
        }
    }

    @Test
    fun cardIsHiddenWhenNoPercentageIsReadable() {
        assertNull(claudeUsageCard(cfg, emptyMap(), ms("2026-07-26T18:00:00+00:00"), ny, false))
    }

    @Test
    fun unconfiguredSlotsSimplyOmitTheirPieces() {
        val onlySession = ClaudeUsageConfig(session = "sensor.session")
        val card = claudeUsageCard(onlySession, live(), ms("2026-07-26T18:00:00+00:00"), ny, false)!!
        assertEquals(listOf("Session"), card.bars.map { it.label })
        assertNull("no reset entity configured", card.bars[0].resetLabel)
        assertNull("no pace entity configured", card.paceText)
    }

    @Test
    fun unparseableResetTimestampDropsOnlyTheSuffix() {
        val entities = live() + ("sensor.session_reset" to st("sensor.session_reset", "soon"))
        val card = claudeUsageCard(cfg, entities, ms("2026-07-26T18:00:00+00:00"), ny, false)!!
        assertEquals(9, card.bars[0].percent)
        assertNull(card.bars[0].resetLabel)
    }

    @Test
    fun percentagesAreClampedIntoRange() {
        val entities = live() + mapOf(
            "sensor.session" to st("sensor.session", "-4"),
            "sensor.week" to st("sensor.week", "137.9"),
        )
        val card = claudeUsageCard(cfg, entities, ms("2026-07-26T18:00:00+00:00"), ny, false)!!
        assertEquals(listOf(0, 100), card.bars.map { it.percent })
    }

    @Test
    fun midnightResetRendersAsTwelveNotZero() {
        // 04:00Z is 00:00 New York on the 27th; "now" is 01:00 local, so the reset is an hour
        // PAST — still inside the near window, and the 12-hour label must read "12:00a", not
        // "0:00a". Guards the hour%12 mapping, which is where a naive format prints 0.
        val entities = live() + ("sensor.session_reset" to
            st("sensor.session_reset", "2026-07-27T04:00:00+00:00"))
        val card = claudeUsageCard(cfg, entities, ms("2026-07-27T05:00:00+00:00"), ny, false)!!
        assertEquals("12:00a", card.bars[0].resetLabel)
    }
}
