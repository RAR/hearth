package com.rar.hearth.ui.model

import kotlin.math.floor

/**
 * Per-screen adaptive geometry: the sizing math for the four growable dashboard regions. Pure
 * Kotlin — NO Android/Compose imports — so it is plain-JVM unit-testable like its ui/model siblings.
 * The composables measure their local space with BoxWithConstraints, pass the dp values in as Float,
 * and apply the whole-dp Int results with `.dp`.
 *
 * Design rule (see docs/superpowers/specs/2026-07-16-adaptive-sizing-design.md): element sizes stay
 * fixed dp everywhere; only the growable regions absorb the extra room. Free space is therefore
 * `screen − fixed reserves` (a subtraction, not a percentage), because the fixed neighbours
 * (paddings, card column, clock block) do not grow with the screen. Every constant is chosen so the
 * Echo Show 5's 787×394dp canvas reproduces today's shipped layout exactly (the golden rule).
 *
 * Outputs are floor()ed then clamped so a fractional dp never rounds a region past its neighbour.
 */

data class HomeOverlayCaps(
    val notifMaxWidthDp: Int,
    val notifMaxHeightDp: Int,
    val nextEventMaxWidthDp: Int,
    val cardColumnMaxHeightDp: Int,
)

data class TakeoverLayout(
    val artSizeDp: Int,
    val metaMaxWidthDp: Int,
)

// ---- reserves, each named for the physical thing it holds ----

// Home overlays draw at a 28dp start pad and a 28dp end pad; 56 is both edges.
private const val EDGE_PADS = 56
// Gap between the notification stack's right edge and the EV/solar card column.
private const val CARD_GAP = 23
// The pills row plus its top pad — the notification stack's top offset today (padding top = 70dp).
private const val TOP_ROW = 70
// Bottom-left clock block height: the big time line + the date line + its 20dp bottom pad.
private const val CLOCK_BLOCK_H = 110
// Breathing room kept between the bottom of the notification stack and the top of the clock block.
private const val NOTIF_CLOCK_GAP = 14
// The Claude usage card sitting between the notification stack and the clock: two bars + the pace
// line + card padding, plus the 10dp gap above it. Reserved out of the notification stack's height
// ONLY when the card is configured, so an unconfigured device keeps today's layout exactly.
private const val USAGE_CARD_BLOCK = 84 + 10
// Home overlay end pad (the next-event card is right-aligned at 28dp).
private const val END_PAD = 28
// Worst-case width of the bottom-left clock's date line (a long weekday + full date).
private const val CLOCK_BLOCK_W = 230
// Horizontal clearance kept between the next-event card and the clock block; the value that
// reproduces today's 420dp cap on the Show 5 (787 − 28 − 230 − 109 = 420).
private const val CLOCK_CLEAR = 109
// Card column draws at a 20dp top pad (Alignment.TopEnd).
private const val CARD_COLUMN_TOP = 20
// Bottom-right next-event pill (single row ~44dp) + its 20dp bottom pad + clearance kept above it.
private const val NEXT_EVENT_BLOCK = 44 + 20 + 14

// Takeover: vertical margins around the art card (today's 2×17 around the 360dp card).
private const val ART_VMARGINS = 34
// Art width as a fraction of the screen — just above 360/787 so the Show 5 stays HEIGHT-limited (its
// art is capped by height, not width) while wider screens grow the art. The art-vs-metadata split.
private const val ART_FRACTION = 0.46f
// Metadata left-margin budget: 48 start pad + 48 art end pad + 32 art↔meta clearance = today's
// padding(end = 440) on the Show 5 (= 360 art + 48 + 32).
private const val META_MARGINS = 128

// Agenda: target per-column width; floor(contentW / this) keeps columns in the 219–249dp band
// around the Show 5's current 238dp.
private const val AGENDA_COL_TARGET = 228

/** EV/solar card width tier: <900 → 248 (today's), <1200 → 300, else 320. Discrete because the
 *  cards are fixed-size elements by design — they do not scale, wider screens just fit more of them. */
fun homeCardWidthDp(screenWidthDp: Float): Int = when {
    screenWidthDp < 900f -> 248
    screenWidthDp < 1200f -> 300
    else -> 320
}

/** True below a 300dp card: the solar stats row keeps the 12sp/14dp/3dp squeeze from 2beecaf; at
 *  300dp+ the row relaxes to 14sp/16dp/4dp. */
fun solarStatsCompact(cardWidthDp: Int): Boolean = cardWidthDp < 300

/** True at the 300dp+ card tiers: the home solar card shows the animated flow diagram instead of
 *  the compact pill. False at the Show 5's 248dp tier by construction — the golden rule (the pill
 *  is untouched there). Discrete like homeCardWidthDp: cards are fixed-size, not scaled. */
fun solarFlowCard(cardWidthDp: Int): Boolean = cardWidthDp >= 300

/**
 * Home overlay caps for a [screenWidthDp]×[screenHeightDp] canvas. When [reserveCardColumn] the
 * notification width subtracts the EV/solar card column so a row never slides under the cards; pass
 * CONFIG presence (not current card visibility) so a card fading in/out never jumps the width. The
 * next-event, notification-height, and card-column-height caps are independent of the card column.
 * The card-column height reserves the top pad and the bottom-right next-event block so the column
 * never runs off-screen or collides with the next-event card.
 */
fun homeOverlayCaps(
    screenWidthDp: Float,
    screenHeightDp: Float,
    reserveCardColumn: Boolean,
    reserveUsageCard: Boolean = false,
): HomeOverlayCaps {
    val reserve = if (reserveCardColumn) homeCardWidthDp(screenWidthDp) + CARD_GAP else 0
    val usage = if (reserveUsageCard) USAGE_CARD_BLOCK else 0
    val notifW = floorDp(screenWidthDp - EDGE_PADS - reserve).coerceIn(300, 700)
    // The notification floor drops when the usage card is on. On the Show 5 the pills-to-clock
    // budget is only 200dp; holding the 120dp floor AND adding a 94dp card would total 214 and eat
    // the clock gap. Yielding ~1 notification row is the better trade, and it costs nothing on the
    // taller screens (they clear the 120 floor with the card reserved anyway).
    val notifH = floorDp(screenHeightDp - TOP_ROW - CLOCK_BLOCK_H - NOTIF_CLOCK_GAP - usage)
        .coerceAtLeast(if (reserveUsageCard) 96 else 120)
    val nextEventW = floorDp(screenWidthDp - END_PAD - CLOCK_BLOCK_W - CLOCK_CLEAR).coerceIn(240, 640)
    val cardColH = floorDp(screenHeightDp - CARD_COLUMN_TOP - NEXT_EVENT_BLOCK).coerceAtLeast(120)
    return HomeOverlayCaps(notifW, notifH, nextEventW, cardColH)
}

/**
 * Now-playing takeover split: a square art card sized by the smaller of the height budget and a
 * width fraction, and the metadata column filling the width the art leaves. These two growable
 * regions dividing the width are the one genuine proportional split in the design.
 */
fun takeoverLayout(screenWidthDp: Float, screenHeightDp: Float): TakeoverLayout {
    val art = floorDp(minOf(screenHeightDp - ART_VMARGINS, screenWidthDp * ART_FRACTION)).coerceAtLeast(200)
    // metaMax subtracts the FLOORED art (Int) so the clearance the art actually occupies is what's
    // reserved — matches the golden table (Show 8: 961 − 442 − 128 = 391, not 961 − 442.06 − 128).
    val metaMax = floorDp(screenWidthDp - art - META_MARGINS).coerceAtLeast(240)
    return TakeoverLayout(art, metaMax)
}

/** Agenda columns from panel CONTENT width (inside PanelSurface's 24dp pads): floor(w / 228),
 *  clamped 3..5 so the Show 5 keeps 3 and no screen shows a lonely 1–2 or over-thin 6+ columns. */
fun agendaDayCount(panelContentWidthDp: Float): Int =
    floorDp(panelContentWidthDp / AGENDA_COL_TARGET).coerceIn(3, 5)

/**
 * How many leading cards (top-priority first, [cardHeights] in px) fit within [maxHeightPx],
 * counting [gapPx] between adjacent cards. If not all fit, room for the "+N more" chip
 * ([overflowChipHeightPx] + one gap) is reserved at the bottom, so the returned count shrinks to
 * leave space for it. Always returns at least 1 when there is a card (the top card is the protected
 * now-playing re-entry -- it shows even if it alone would overflow). Returns 0 for an empty list.
 * The caller shows the chip iff the returned count is less than cardHeights.size.
 */
fun visibleCardCount(
    cardHeights: List<Int>,
    maxHeightPx: Int,
    gapPx: Int,
    overflowChipHeightPx: Int,
): Int {
    if (cardHeights.isEmpty()) return 0
    // Pass 1: how many fit with no chip.
    var fitNoChip = 0
    var acc = 0
    for (h in cardHeights) {
        val next = if (fitNoChip == 0) h else acc + gapPx + h
        if (next <= maxHeightPx) { acc = next; fitNoChip++ } else break
    }
    if (fitNoChip == cardHeights.size) return fitNoChip
    // Pass 2: overflow -> reserve chip space (chip sits below the cards with its own gap).
    var fitWithChip = 0
    acc = 0
    for (h in cardHeights) {
        val cards = if (fitWithChip == 0) h else acc + gapPx + h
        if (cards + gapPx + overflowChipHeightPx <= maxHeightPx) { acc = cards; fitWithChip++ } else break
    }
    return fitWithChip.coerceAtLeast(1)
}

/**
 * Cards that fit on the page starting at [pageStart] (0-based index into [cardHeights],
 * top-priority first). On the FIRST page (pageStart == 0) the "+N more" chip is reserved only if
 * some cards overflow -- identical to [visibleCardCount]. On a LATER page a chip is always shown
 * (either "+N more" or a wrap-to-top affordance), so chip space is always reserved. Returns at
 * least 1 when a card exists at/after the (clamped) start; 0 for an empty list. An out-of-range
 * [pageStart] is treated as 0.
 */
fun pageCardCount(
    cardHeights: List<Int>,
    pageStart: Int,
    maxHeightPx: Int,
    gapPx: Int,
    overflowChipHeightPx: Int,
): Int {
    if (cardHeights.isEmpty()) return 0
    val start = if (pageStart in cardHeights.indices) pageStart else 0
    if (start == 0) return visibleCardCount(cardHeights, maxHeightPx, gapPx, overflowChipHeightPx)
    // Later page: reserve chip space unconditionally (a chip always shows once you've paged forward).
    var fit = 0
    var acc = 0
    for (i in start until cardHeights.size) {
        val h = cardHeights[i]
        val cards = if (fit == 0) h else acc + gapPx + h
        if (cards + gapPx + overflowChipHeightPx <= maxHeightPx) { acc = cards; fit++ } else break
    }
    return fit.coerceAtLeast(1)
}

// floor then Int: outputs never round a region past its fixed neighbour. toDouble() keeps floor
// exact for the Float inputs (a Float multiply like 961 × 0.46f lands at 442.06, flooring to 442).
private fun floorDp(value: Float): Int = floor(value.toDouble()).toInt()
