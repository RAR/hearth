package com.rar.echodash.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the design spec's golden value table verbatim. The Show 5 (787×394) rows ARE today's shipped
 * layout — the golden rule: they must never change. Show 8 / Tab M9 rows document the growth; the
 * tiny 500×300 row documents floor/clamp behaviour only (no such device exists in the fleet).
 */
class AdaptiveGeometryTest {

    // ---- homeCardWidthDp: tier table + boundaries ----

    @Test
    fun homeCardWidthTierTableAndBoundaries() {
        // Golden column: Show 5 → 248, Show 8 → 300, Tab M9 → 320, tiny 500 → 248.
        assertEquals(248, homeCardWidthDp(787f))
        assertEquals(300, homeCardWidthDp(961f))
        assertEquals(320, homeCardWidthDp(1340f))
        assertEquals(248, homeCardWidthDp(500f))
        // Tier boundaries are strict: <900, <1200, else.
        assertEquals(248, homeCardWidthDp(899f))
        assertEquals(300, homeCardWidthDp(900f))
        assertEquals(300, homeCardWidthDp(1199f))
        assertEquals(320, homeCardWidthDp(1200f))
    }

    // ---- solarStatsCompact ----

    @Test
    fun solarStatsCompactThreshold() {
        assertTrue(solarStatsCompact(248))   // today's 248 card keeps the 12sp squeeze
        assertFalse(solarStatsCompact(300))  // 300dp+ relaxes to 14sp
    }

    // ---- solarFlowCard ----

    @Test
    fun solarFlowCardTierPins() {
        assertFalse(solarFlowCard(248))  // Show 5 tier: unchanged pill by construction (golden rule)
        assertTrue(solarFlowCard(300))   // Show 8 tier
        assertTrue(solarFlowCard(320))   // Tab M9 tier
    }

    // ---- homeOverlayCaps: all five golden rows ----

    @Test
    fun homeOverlayCapsGoldenTable() {
        // Show 5 787×394, cards configured — today's shipped caps (golden rule).
        assertEquals(HomeOverlayCaps(460, 200, 420), homeOverlayCaps(787f, 394f, reserveCardColumn = true))
        // Show 8 961×601, cards configured.
        assertEquals(HomeOverlayCaps(582, 407, 594), homeOverlayCaps(961f, 601f, reserveCardColumn = true))
        // Tab M9 1340×800, cards configured — notif width & next-event hit their caps (700 / 640).
        assertEquals(HomeOverlayCaps(700, 606, 640), homeOverlayCaps(1340f, 800f, reserveCardColumn = true))
        // Show 5, no cards configured — notif width reclaims the card column (731 → capped 700).
        assertEquals(HomeOverlayCaps(700, 200, 420), homeOverlayCaps(787f, 394f, reserveCardColumn = false))
        // Tiny 500×300 — every field floors to its minimum.
        assertEquals(HomeOverlayCaps(300, 120, 240), homeOverlayCaps(500f, 300f, reserveCardColumn = true))
    }

    // ---- takeoverLayout: golden rows (the no-cards row has no takeover values) ----

    @Test
    fun takeoverLayoutGoldenTable() {
        // Show 5 — height-limited art (360) and today's effective 299dp metadata width.
        assertEquals(TakeoverLayout(360, 299), takeoverLayout(787f, 394f))
        // Show 8 — width-limited art (442), wider metadata (391). metaMax uses the floored art Int.
        assertEquals(TakeoverLayout(442, 391), takeoverLayout(961f, 601f))
        // Tab M9.
        assertEquals(TakeoverLayout(616, 596), takeoverLayout(1340f, 800f))
        // Tiny — art floors via the width fraction (230), metadata clamps up to 240.
        assertEquals(TakeoverLayout(230, 240), takeoverLayout(500f, 300f))
    }

    // ---- agendaDayCount ----

    @Test
    fun agendaDayCountGoldenTable() {
        // Panel CONTENT widths (screen − 2×24 PanelSurface pads): Show 5 739 → 3, Show 8 913 → 4,
        // Tab M9 1292 → 5; the tiny 452 clamps up to the 3 floor.
        assertEquals(3, agendaDayCount(739f))
        assertEquals(4, agendaDayCount(913f))
        assertEquals(5, agendaDayCount(1292f))
        assertEquals(3, agendaDayCount(452f))
    }
}
