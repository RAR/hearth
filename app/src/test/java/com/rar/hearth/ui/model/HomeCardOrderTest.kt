package com.rar.hearth.ui.model

import com.rar.hearth.config.HomeCardConfig
import com.rar.hearth.config.HomeCards
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCardOrderTest {

    /** The golden rule: untouched config renders the column exactly as it did before the feature. */
    @Test
    fun defaultsReproduceTodaysColumnOrder() {
        assertEquals(
            listOf(
                HomeCardKind.NOW_PLAYING,
                HomeCardKind.EV1,
                HomeCardKind.EV2,
                HomeCardKind.SOLAR,
                HomeCardKind.QUICK_BUTTONS,
            ),
            orderedHomeCards(HomeCards()),
        )
    }

    @Test
    fun disabledCardsAreDropped() {
        val cards = HomeCards(
            nowPlaying = HomeCardConfig(false, 1),
            ev1 = HomeCardConfig(true, 2),
            ev2 = HomeCardConfig(false, 3),
            solar = HomeCardConfig(true, 4),
            quickButtons = HomeCardConfig(false, 5),
        )
        assertEquals(listOf(HomeCardKind.EV1, HomeCardKind.SOLAR), orderedHomeCards(cards))
    }

    @Test
    fun allDisabledYieldsAnEmptyColumn() {
        val off = HomeCardConfig(enabled = false, order = 1)
        val cards = HomeCards(off, off, off, off, off)
        assertEquals(emptyList<HomeCardKind>(), orderedHomeCards(cards))
    }

    @Test
    fun cardsSortByUserOrderNotDeclarationOrder() {
        val cards = HomeCards(
            nowPlaying = HomeCardConfig(true, 5),
            ev1 = HomeCardConfig(true, 4),
            ev2 = HomeCardConfig(true, 3),
            solar = HomeCardConfig(true, 2),
            quickButtons = HomeCardConfig(true, 1),
        )
        assertEquals(
            listOf(
                HomeCardKind.QUICK_BUTTONS,
                HomeCardKind.SOLAR,
                HomeCardKind.EV2,
                HomeCardKind.EV1,
                HomeCardKind.NOW_PLAYING,
            ),
            orderedHomeCards(cards),
        )
    }

    /**
     * Duplicate `order` values must produce a STABLE sequence. If ties resolved arbitrarily the
     * column could reshuffle between recompositions, which reads as flicker.
     */
    @Test
    fun tiedOrdersBreakByDeclarationOrderAndAreStable() {
        val tied = HomeCards(
            nowPlaying = HomeCardConfig(true, 2),
            ev1 = HomeCardConfig(true, 2),
            ev2 = HomeCardConfig(true, 1),
            solar = HomeCardConfig(true, 2),
            quickButtons = HomeCardConfig(true, 1),
        )
        val expected = listOf(
            HomeCardKind.EV2,          // order 1, declared before quickButtons
            HomeCardKind.QUICK_BUTTONS, // order 1
            HomeCardKind.NOW_PLAYING,  // order 2, declared first among the 2s
            HomeCardKind.EV1,          // order 2
            HomeCardKind.SOLAR,        // order 2
        )
        assertEquals(expected, orderedHomeCards(tied))
        assertEquals("repeated calls must agree", expected, orderedHomeCards(tied))
    }

    @Test
    fun configForReturnsTheMatchingSlot() {
        val cards = HomeCards(
            nowPlaying = HomeCardConfig(false, 11),
            ev1 = HomeCardConfig(false, 12),
            ev2 = HomeCardConfig(false, 13),
            solar = HomeCardConfig(false, 14),
            quickButtons = HomeCardConfig(true, 15),
        )
        assertEquals(11, cards.configFor(HomeCardKind.NOW_PLAYING).order)
        assertEquals(12, cards.configFor(HomeCardKind.EV1).order)
        assertEquals(13, cards.configFor(HomeCardKind.EV2).order)
        assertEquals(14, cards.configFor(HomeCardKind.SOLAR).order)
        assertEquals(15, cards.configFor(HomeCardKind.QUICK_BUTTONS).order)
    }
}
