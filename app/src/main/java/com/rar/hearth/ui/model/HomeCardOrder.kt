package com.rar.hearth.ui.model

import com.rar.hearth.config.HomeCardConfig
import com.rar.hearth.config.HomeCards

/**
 * Ordering for the right-hand home card column. Pure Kotlin -- NO Android/Compose imports -- so it
 * is plain-JVM unit-testable like its ui/model siblings.
 *
 * This module decides ORDER and nothing else. Whether a card has anything to show stays where it
 * already lives (miniPlayerVisible, evCards, solarCard, quickButtons): enabling a card gates it,
 * it does not force it on screen.
 */

/** The five members of the card column. Declaration order is the tie-break for equal `order`s. */
enum class HomeCardKind { NOW_PLAYING, EV1, EV2, SOLAR, QUICK_BUTTONS }

/** This card's placement config. */
fun HomeCards.configFor(kind: HomeCardKind): HomeCardConfig = when (kind) {
    HomeCardKind.NOW_PLAYING -> nowPlaying
    HomeCardKind.EV1 -> ev1
    HomeCardKind.EV2 -> ev2
    HomeCardKind.SOLAR -> solar
    HomeCardKind.QUICK_BUTTONS -> quickButtons
}

/**
 * The enabled cards, top to bottom.
 *
 * Ties on `order` break by enum declaration order rather than resolving arbitrarily: a config with
 * duplicate values (hand-edited, or half-written by an interrupted save) must still produce ONE
 * stable sequence, or the column reshuffles between recompositions and reads as flicker.
 */
fun orderedHomeCards(cards: HomeCards): List<HomeCardKind> =
    HomeCardKind.entries
        .filter { cards.configFor(it).enabled }
        .sortedWith(compareBy({ cards.configFor(it).order }, { it.ordinal }))
