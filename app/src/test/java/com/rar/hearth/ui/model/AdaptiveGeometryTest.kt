package com.rar.hearth.ui.model

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
        assertTrue(solarFlowCard(300))   // Show 8 / Tab M9 tier
        assertTrue(solarFlowCard(320))   // >=1200dp-wide tier
    }

    // ---- homeOverlayCaps: all five golden rows ----

    @Test
    fun homeOverlayCapsGoldenTable() {
        // Show 5 787×394, cards configured — today's shipped caps (golden rule). Card column
        // 394 − 20 − 78 = 296.
        assertEquals(HomeOverlayCaps(460, 200, 420, 296), homeOverlayCaps(787f, 394f, reserveCardColumn = true))
        // Show 8 961×601, cards configured. Card column 601 − 98 = 503.
        assertEquals(HomeOverlayCaps(582, 407, 594, 503), homeOverlayCaps(961f, 601f, reserveCardColumn = true))
        // Tab M9 1340×800, cards configured — notif width & next-event hit their caps (700 / 640).
        // Card column 800 − 98 = 702.
        assertEquals(HomeOverlayCaps(700, 606, 640, 702), homeOverlayCaps(1340f, 800f, reserveCardColumn = true))
        // Show 5, no cards configured — notif width reclaims the card column (731 → capped 700).
        assertEquals(HomeOverlayCaps(700, 200, 420, 296), homeOverlayCaps(787f, 394f, reserveCardColumn = false))
        // Tiny 500×300 — every field floors to its minimum. Card column 300 − 98 = 202.
        assertEquals(HomeOverlayCaps(300, 120, 240, 202), homeOverlayCaps(500f, 300f, reserveCardColumn = true))
    }

    /** The golden rule: an unconfigured usage card must not move a single dp. */
    @Test
    fun usageCardReserveIsOffByDefaultAndMatchesTheGoldenRow() {
        assertEquals(
            homeOverlayCaps(787f, 394f, reserveCardColumn = true),
            homeOverlayCaps(787f, 394f, reserveCardColumn = true, reserveUsageCard = false),
        )
    }

    @Test
    fun usageCardReserveTakesItsWidthOutOfTheNextEventPill() {
        // Show 8: 594 − (335 + 20) = 239. Only nextEventMaxWidthDp moves; the others are untouched.
        assertEquals(
            HomeOverlayCaps(582, 407, 239, 503),
            homeOverlayCaps(961f, 601f, reserveCardColumn = true, reserveUsageCard = true),
        )
        // Tab M9: 1340 − 28 − 230 − 109 − (360 + 20) = 593.
        assertEquals(
            HomeOverlayCaps(700, 606, 593, 702),
            homeOverlayCaps(1340f, 800f, reserveCardColumn = true, reserveUsageCard = true),
        )
    }

    /**
     * The card takes the strip's leftover rather than a fixed width — a constant sized for the
     * Show 5's card column left it stubby on every larger screen.
     */
    @Test
    fun usageCardWidthGrowsWithTheStripAndCapsOut() {
        assertEquals("Show 5: card column binds at 511", 213, usageCardWidthDp(787f))
        assertEquals("Show 8: card column binds at 633", 335, usageCardWidthDp(961f))
        assertEquals("Tab M9: hits the 360 ceiling", 360, usageCardWidthDp(1340f))
        // A screen narrow enough that the leftover goes negative still floors to something usable.
        assertEquals(200, usageCardWidthDp(500f))
    }

    /**
     * The bottom strip is the tight one on the Show 5. Its three occupants — clock block, usage
     * card, next-event pill — plus their gaps must fit inside 787dp, which is why the pill's floor
     * drops to 200 while the card is on. Guards the actual no-overlap arithmetic, not just the cap.
     */
    @Test
    fun bottomStripOccupantsDoNotOverlapOnTheShow5() {
        val caps = homeOverlayCaps(787f, 394f, reserveCardColumn = true, reserveUsageCard = true)
        assertEquals(200, caps.nextEventMaxWidthDp)          // floored, not the raw 160
        val cardRightEdge = usageCardStartDp() + usageCardWidthDp(787f) // 278 + 213 = 491
        val pillLeftEdge = 787 - 28 - caps.nextEventMaxWidthDp // right-aligned at the 28dp end pad
        assertTrue(
            "usage card ($cardRightEdge) must end before the next-event pill ($pillLeftEdge)",
            cardRightEdge < pillLeftEdge,
        )
    }

    @Test
    fun usageCardStartsClearOfTheClockBlock() {
        // 28dp start pad + the clock's 230dp worst-case date line + a 20dp gap.
        assertEquals(278, usageCardStartDp())
    }

    /**
     * The usage card's other neighbour. The EV/solar column is right-aligned and can hang low
     * enough to reach the bottom strip, so the card must end before the column starts on every
     * screen. The Show 5 is the one that binds — a 240dp card overlapped it by 7dp.
     */
    @Test
    fun usageCardClearsTheEvSolarColumnOnEveryScreen() {
        for ((w, label) in listOf(787f to "Show 5", 961f to "Show 8", 1340f to "Tab M9")) {
            val columnLeftEdge = w.toInt() - 28 - homeCardWidthDp(w)
            assertTrue(
                "$label: usage card ends at ${usageCardStartDp() + usageCardWidthDp(w)}, " +
                    "card column starts at $columnLeftEdge",
                usageCardStartDp() + usageCardWidthDp(w) < columnLeftEdge,
            )
        }
    }

    // ---- visibleCardCount: fit math + chip reservation ----

    @Test
    fun visibleCardCountFitAndOverflow() {
        // Empty list -> nothing to show, no chip.
        assertEquals(0, visibleCardCount(emptyList(), 300, 10, 30))
        // All fit with room to spare -> both, no chip reserved (100 + 10 + 100 = 210 <= 300).
        assertEquals(2, visibleCardCount(listOf(100, 100), 300, 10, 30))
        // A single card that fits -> 1, no chip.
        assertEquals(1, visibleCardCount(listOf(100), 300, 10, 30))
        // Overflow reserves chip space: three 100s in 250 with gap 10. Pass 1 fits two (210) but not
        // three, so pass 2 reserves the chip; two cards + gap + chip = 100+10+100+10+30 = 250 <= 250.
        assertEquals(2, visibleCardCount(listOf(100, 100, 100), 250, 10, 30))
        // Exact boundary is INCLUDED (<=): one 100 card + gap + 30 chip = 140 == max, so 1 card fits
        // beside the chip; the second card would push cards+gap+chip to 240 > 140, dropped.
        assertEquals(1, visibleCardCount(listOf(100, 100), 140, 10, 30))
        // Pathological floor: even the top card alone (100) overflows a 100 budget once the chip is
        // reserved, but the protected top card still shows -> 1.
        assertEquals(1, visibleCardCount(listOf(100, 100), 100, 10, 30))
    }

    // ---- pageCardCount: page window + later-page chip reservation ----

    @Test
    fun pageCardCountPagingAndChipReservation() {
        // Empty list -> nothing to show, regardless of page.
        assertEquals(0, pageCardCount(emptyList(), 0, 300, 10, 30))
        // pageStart == 0 delegates to visibleCardCount: all-fit and overflow cases match exactly.
        assertEquals(
            visibleCardCount(listOf(100, 100), 300, 10, 30),
            pageCardCount(listOf(100, 100), 0, 300, 10, 30),
        )
        assertEquals(
            visibleCardCount(listOf(100, 100, 100), 250, 10, 30),
            pageCardCount(listOf(100, 100, 100), 0, 250, 10, 30),
        )
        // Later page ALWAYS reserves chip space (a wrap-to-top chip shows once paged forward). The
        // two remaining 100s from index 2 just fit beside the chip: 100+10+100 +gap+chip = 250 <= 250.
        assertEquals(2, pageCardCount(listOf(100, 100, 100, 100), 2, 250, 10, 30))
        // One px tighter and the reserved chip pushes the second card off: 250 > 249 -> only 1.
        assertEquals(1, pageCardCount(listOf(100, 100, 100, 100), 2, 249, 10, 30))
        // Out-of-range pageStart is treated as 0 (delegates to visibleCardCount): >= size and negative.
        assertEquals(2, pageCardCount(listOf(100, 100), 5, 300, 10, 30))
        assertEquals(2, pageCardCount(listOf(100, 100), -1, 300, 10, 30))
        // Later-page floor: the card at the start alone can't also fit the chip, but still shows -> 1.
        assertEquals(1, pageCardCount(listOf(100, 100), 1, 100, 10, 30))
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
