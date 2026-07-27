# Home Card Ordering and Enable/Disable — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user reorder the home screen's right-hand card column and hide individual cards from the web config, without clearing a card's entity IDs to make it disappear.

**Architecture:** A new top-level `homeCards` config block mirrors the existing `panels` block (`HomeCardConfig(enabled, order)` per card). A pure `ui/model/HomeCardOrder.kt` turns that block into an ordered list of `HomeCardKind`, which `HomeView` loops over to dispatch to the composables it already has. The web UI reuses the panel row markup, checkbox, and up/down arrows.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, JUnit4 (plain JVM), vanilla JS/HTML for the on-device web config.

**Spec:** `docs/superpowers/specs/2026-07-27-home-card-order-toggle-design.md`

## Global Constraints

- `minSdk` 27, `targetSdk` 34, `applicationId` `com.rar.echodash` — never change these.
- No new app dependencies.
- Files under `app/src/main/java/com/rar/hearth/ui/model/` are pure Kotlin: NO Android or Compose imports. They are unit-tested on the plain JVM.
- Tests are plain-JVM JUnit4 only. No instrumented tests, no Robolectric.
- **Golden rule: a config saved before this feature must render identically after it.** The `HomeCards` defaults are exactly today's order, all enabled.
- **Gate before EVERY commit:** `./gradlew testDebugUnitTest assembleDebug`, with the return code checked. Do not pipe the output to `tail`/`head` in a way that swallows the exit status. For any task touching `app.js`, also run `node --check app/src/main/assets/config/app.js`.
- Do not reformat or restructure code you are not changing.

---

### Task 1: Config block — `HomeCardConfig`, `HomeCards`, and order normalisation

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/config/DashConfig.kt`
- Test: `app/src/test/java/com/rar/hearth/config/DashConfigTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `HomeCardConfig(enabled: Boolean = true, order: Int = 0)`; `HomeCards` with fields `nowPlaying`, `ev1`, `ev2`, `solar`, `quickButtons` (all `HomeCardConfig`); `HomeCards.slots(): List<HomeCardConfig>` in declaration order; `HomeCards.clamped(): HomeCards`; `DashConfig.homeCards: HomeCards`.

- [ ] **Step 1: Write the failing tests**

Append these to `DashConfigTest.kt` (inside the existing test class):

```kotlin
    @Test
    fun homeCardDefaultsReproduceTodaysColumnOrder() {
        // The golden rule: a config with no homeCards block renders exactly as it does today --
        // now-playing, then EV 1, EV 2, then solar, then quick buttons, with nothing hidden.
        val cfg = decodeConfig("""{"version":1}""")
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            cfg.homeCards.slots().map { it.order },
        )
        assertTrue(cfg.homeCards.slots().all { it.enabled })
    }

    @Test
    fun homeCardsRoundTripThroughSerialization() {
        val cfg = DashConfig().copy(
            homeCards = HomeCards(
                nowPlaying = HomeCardConfig(enabled = false, order = 5),
                ev1 = HomeCardConfig(enabled = true, order = 1),
                ev2 = HomeCardConfig(enabled = false, order = 2),
                solar = HomeCardConfig(enabled = true, order = 3),
                quickButtons = HomeCardConfig(enabled = true, order = 4),
            ),
        )
        val back = decodeConfig(ConfigJson.json.encodeToString(DashConfig.serializer(), cfg))
        assertEquals(cfg.homeCards, back.homeCards)
    }

    @Test
    fun clampedRedensifiesSparseOrdersKeepingTheSequence() {
        // Hand-edited or partially-saved configs can hold sparse orders. Redensifying to 1..5
        // keeps the same visual sequence while making later swaps predictable.
        val cards = HomeCards(
            nowPlaying = HomeCardConfig(true, 40),
            ev1 = HomeCardConfig(true, 10),
            ev2 = HomeCardConfig(true, 30),
            solar = HomeCardConfig(true, 20),
            quickButtons = HomeCardConfig(true, 50),
        ).clamped()
        assertEquals(4, cards.nowPlaying.order)
        assertEquals(1, cards.ev1.order)
        assertEquals(3, cards.ev2.order)
        assertEquals(2, cards.solar.order)
        assertEquals(5, cards.quickButtons.order)
    }

    @Test
    fun clampedBreaksOrderTiesByDeclarationOrder() {
        // Every card claiming order 0 must produce the declaration sequence, not an arbitrary one.
        val cards = HomeCards(
            nowPlaying = HomeCardConfig(true, 0),
            ev1 = HomeCardConfig(true, 0),
            ev2 = HomeCardConfig(true, 0),
            solar = HomeCardConfig(true, 0),
            quickButtons = HomeCardConfig(true, 0),
        ).clamped()
        assertEquals(listOf(1, 2, 3, 4, 5), cards.slots().map { it.order })
    }

    @Test
    fun clampedIsIdempotent() {
        val once = HomeCards(
            nowPlaying = HomeCardConfig(true, 40),
            ev1 = HomeCardConfig(false, 10),
            ev2 = HomeCardConfig(true, 30),
            solar = HomeCardConfig(true, 20),
            quickButtons = HomeCardConfig(true, 50),
        ).clamped()
        assertEquals(once, once.clamped())
    }

    @Test
    fun clampedPreservesEnabledFlags() {
        // Reordering must never silently re-enable a card the user turned off.
        val cards = HomeCards(
            nowPlaying = HomeCardConfig(false, 3),
            ev1 = HomeCardConfig(true, 1),
            ev2 = HomeCardConfig(false, 2),
            solar = HomeCardConfig(true, 4),
            quickButtons = HomeCardConfig(false, 5),
        ).clamped()
        assertEquals(
            listOf(false, true, false, true, false),
            cards.slots().map { it.enabled },
        )
    }

    @Test
    fun dashConfigClampedNormalisesTheHomeCardsBlock() {
        val cfg = DashConfig().copy(
            homeCards = HomeCards(
                nowPlaying = HomeCardConfig(true, 90),
                ev1 = HomeCardConfig(true, 80),
                ev2 = HomeCardConfig(true, 70),
                solar = HomeCardConfig(true, 60),
                quickButtons = HomeCardConfig(true, 50),
            ),
        ).clamped()
        assertEquals(listOf(5, 4, 3, 2, 1), cfg.homeCards.slots().map { it.order })
    }
```

If `assertTrue` is not already imported in this file, add `import org.junit.Assert.assertTrue`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests '*DashConfigTest*'`
Expected: FAIL to compile — `Unresolved reference: HomeCards`.

- [ ] **Step 3: Add the config types**

In `DashConfig.kt`, immediately after the existing `Panels` data class (which ends around line 21), insert:

```kotlin
/** One home-screen card's placement: whether it renders, and where in the column it sits. */
@Serializable
data class HomeCardConfig(val enabled: Boolean = true, val order: Int = 0)

/**
 * The right-hand home card column, in user-controlled order.
 *
 * Defaults are exactly the sequence the column rendered before this block existed -- now-playing,
 * EV 1, EV 2, solar, quick buttons, nothing hidden -- so a config saved by an older build
 * deserializes to an identical layout.
 *
 * EV rows are POSITIONAL: `ev1` is `entities.evs[0]`, not a particular car. Swapping which car
 * occupies which slot leaves the ordering attached to the slot.
 */
@Serializable
data class HomeCards(
    val nowPlaying: HomeCardConfig = HomeCardConfig(true, 1),
    val ev1: HomeCardConfig = HomeCardConfig(true, 2),
    val ev2: HomeCardConfig = HomeCardConfig(true, 3),
    val solar: HomeCardConfig = HomeCardConfig(true, 4),
    val quickButtons: HomeCardConfig = HomeCardConfig(true, 5),
) {
    /** The five cards in DECLARATION order -- which is also the tie-break for equal `order`s. */
    fun slots(): List<HomeCardConfig> = listOf(nowPlaying, ev1, ev2, solar, quickButtons)

    /**
     * Rewrite `order` to a dense 1..5 following the current sort, breaking ties by declaration
     * order. Idempotent. Keeps hand-edited or half-saved configs from accumulating sparse or
     * duplicated values, which would make the web UI's swap arrows behave unpredictably.
     * `enabled` is never touched.
     */
    fun clamped(): HomeCards {
        val ranked = slots().withIndex().sortedWith(compareBy({ it.value.order }, { it.index }))
        val orders = IntArray(slots().size)
        ranked.forEachIndexed { rank, iv -> orders[iv.index] = rank + 1 }
        return HomeCards(
            nowPlaying = nowPlaying.copy(order = orders[0]),
            ev1 = ev1.copy(order = orders[1]),
            ev2 = ev2.copy(order = orders[2]),
            solar = solar.copy(order = orders[3]),
            quickButtons = quickButtons.copy(order = orders[4]),
        )
    }
}
```

- [ ] **Step 4: Wire it into `DashConfig`**

In the `DashConfig` data class (around line 297), add the field immediately after `panels`:

```kotlin
    val panels: Panels = Panels(),
    val homeCards: HomeCards = HomeCards(),
```

Then in `DashConfig.clamped()`, in the `return copy(...)` block, add this line immediately after `version = 1,`:

```kotlin
            homeCards = homeCards.clamped(),
```

Do **not** touch `referencedEntityIds()`. A disabled card keeps its entity subscriptions by design (see the spec).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests '*DashConfigTest*'`
Expected: PASS.

- [ ] **Step 6: Run the full gate**

Run: `./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"`
Expected: `RC=0`, whole suite green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rar/hearth/config/DashConfig.kt \
        app/src/test/java/com/rar/hearth/config/DashConfigTest.kt
git commit -m "feat(config): add the homeCards order/enable block"
```

---

### Task 2: `HomeCardOrder` pure module

**Files:**
- Create: `app/src/main/java/com/rar/hearth/ui/model/HomeCardOrder.kt`
- Create: `app/src/test/java/com/rar/hearth/ui/model/HomeCardOrderTest.kt`

**Interfaces:**
- Consumes: `HomeCards`, `HomeCardConfig` from Task 1.
- Produces: `enum class HomeCardKind { NOW_PLAYING, EV1, EV2, SOLAR, QUICK_BUTTONS }`; `fun HomeCards.configFor(kind: HomeCardKind): HomeCardConfig`; `fun orderedHomeCards(cards: HomeCards): List<HomeCardKind>`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rar/hearth/ui/model/HomeCardOrderTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*HomeCardOrderTest*'`
Expected: FAIL to compile — `Unresolved reference: HomeCardKind`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/rar/hearth/ui/model/HomeCardOrder.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*HomeCardOrderTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the full gate**

Run: `./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"`
Expected: `RC=0`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/hearth/ui/model/HomeCardOrder.kt \
        app/src/test/java/com/rar/hearth/ui/model/HomeCardOrderTest.kt
git commit -m "feat(home): pure module for home card ordering"
```

---

### Task 3: `EvCard` carries its config slot

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/ui/model/EvModel.kt`
- Test: `app/src/test/java/com/rar/hearth/ui/model/EvModelTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `EvCard.slot: Int` — the 0-based index of the `EvConfig` the card came from. Task 4 matches `HomeCardKind.EV1` to `slot == 0` and `EV2` to `slot == 1`.

**Why:** `evCards()` uses `mapNotNull`, so the returned list is COMPACTED. With EV 1 unplugged and EV 2 charging the result is `[EV2]` at index 0, and a positional match would render EV 2's card in EV 1's row. No existing test constructs an `EvCard` directly, so adding a field is safe.

- [ ] **Step 1: Write the failing test**

Append to `EvModelTest.kt`:

```kotlin
    @Test
    fun cardCarriesItsConfigSlotThroughCompaction() {
        // Slot 0 is unplugged, slot 1 is charging: the single card returned must still report
        // slot 1, not the index 0 it happens to occupy in the compacted list.
        val cards = evCards(
            listOf(
                EvConfig(name = "First", charging = "binary_sensor.a"),
                EvConfig(name = "Second", charging = "binary_sensor.b"),
            ),
            mapOf(
                "binary_sensor.a" to st("binary_sensor.a", "off"),
                "binary_sensor.b" to st("binary_sensor.b", "on"),
            ),
            0L,
        )
        assertEquals(1, cards.size)
        assertEquals("Second", cards[0].name)
        assertEquals(1, cards[0].slot)
    }

    @Test
    fun slotsAreAssignedFromConfigPositionNotOutputPosition() {
        val cards = evCards(
            listOf(
                EvConfig(name = "First", charging = "binary_sensor.a"),
                EvConfig(name = "Second", charging = "binary_sensor.b"),
            ),
            mapOf(
                "binary_sensor.a" to st("binary_sensor.a", "on"),
                "binary_sensor.b" to st("binary_sensor.b", "on"),
            ),
            0L,
        )
        assertEquals(listOf(0, 1), cards.map { it.slot })
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*EvModelTest*'`
Expected: FAIL to compile — `Unresolved reference: slot`.

- [ ] **Step 3: Add the field and populate it**

In `EvModel.kt`, add `slot` as the FIRST field of `EvCard` (line 12 onward):

```kotlin
/** One EV's card. Fields are pre-formatted display strings; null = omit that line. */
data class EvCard(
    val slot: Int,           // 0-based config slot this card came from; survives list compaction
    val name: String,        // config name, or "EV" when blank
    val charging: Boolean,   // true while actually charging: animates the gauge + shows the charge/eta lines
    val socPct: Int?,        // 0..100 for the gauge + "%" text; null hides the gauge row
    val limitPct: Int?,      // 0..100 tick position on the gauge; null hides the tick
    val chargeLine: String?, // "7.2 kW · 4.3 kWh" (power · energy); null when idle or neither sensor readable
    val etaText: String?,    // "1h05" / "45m"; null when idle or unparseable
)
```

Change the derivation from `mapNotNull` to `mapIndexedNotNull` (line 34):

```kotlin
fun evCards(cfgs: List<EvConfig>, entities: Map<String, EntityState>, nowMs: Long): List<EvCard> =
    cfgs.mapIndexedNotNull { slot, cfg ->
```

and pass it in the `EvCard(...)` construction near line 56:

```kotlin
        EvCard(
            slot = slot,
            name = cfg.name.trim().ifBlank { "EV" },
```

Extend the existing KDoc above `evCards` with a sentence on why the slot exists:

```
 * The returned list is COMPACTED -- slots that are neither plugged nor charging are skipped -- so
 * each card carries its `slot` index. Consumers that key off the config slot (the home card
 * ordering) must use `slot`, never the list position.
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*EvModelTest*'`
Expected: PASS.

- [ ] **Step 5: Run the full gate**

Run: `./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"`
Expected: `RC=0`. The whole suite must be green — `EvCard` is a data class, so any other call site would fail to compile here.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/hearth/ui/model/EvModel.kt \
        app/src/test/java/com/rar/hearth/ui/model/EvModelTest.kt
git commit -m "fix(home): keep the config slot on each EV card"
```

---

### Task 4: `HomeView` renders the column in user order

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/ui/HomeView.kt`
- Modify: `app/src/main/java/com/rar/hearth/ui/DashboardShell.kt`
- Modify: `app/src/main/java/com/rar/hearth/ui/model/AdaptiveGeometry.kt` (comment only)

**Interfaces:**
- Consumes: `HomeCards` (Task 1), `orderedHomeCards()` / `HomeCardKind` (Task 2), `EvCard.slot` (Task 3).
- Produces: a new `HomeView` parameter `homeCards: HomeCards = HomeCards()`.

**No unit test.** Compose UI is not unit-tested in this project (plain-JVM JUnit4 only — see Global Constraints). Verification here is: the full gate compiles and stays green, plus the live check in Task 6. The ordering logic itself is already covered by `HomeCardOrderTest`, which is why it lives in a pure module.

- [ ] **Step 1: Add the parameter to `HomeView`**

In the `HomeView` signature (around line 190), add after `quickButtons` / `onQuickButton`:

```kotlin
    // User-controlled order and visibility for the right-hand card column. Defaults reproduce the
    // pre-feature sequence, so an old config renders identically.
    homeCards: HomeCards = HomeCards(),
```

Add the imports:

```kotlin
import com.rar.hearth.config.HomeCards
import com.rar.hearth.ui.model.HomeCardKind
import com.rar.hearth.ui.model.orderedHomeCards
```

- [ ] **Step 2: Replace the hardcoded card sequence with an ordered dispatch loop**

In `HomeView.kt` around lines 434-457, the body of the `CardColumn { ... }` lambda currently reads:

```kotlin
                        // Now-playing card sits above the EV/solar cards when a session is up.
                        if (showMiniPlayer) {
                            NowPlayingCardView(...)
                        }
                        evs.forEach { EvCardView(it, cardWidth) }
                        if (solarFlowCard(cardWidth.value.toInt()) && solarGraph != null) {
                            SolarFlowCardView(solarGraph, cardWidth)
                        } else if (solar != null) {
                            SolarCardView(solar, cardWidth)
                        }
                        if (quickButtons.isNotEmpty()) {
                            QuickButtonsCardView(quickButtons, cardWidth, onQuickButton)
                        }
```

Replace that whole block with:

```kotlin
                        // Order and visibility come from config; whether a card has anything to
                        // show stays with its own model. Enabling a card gates it, never forces it.
                        orderedHomeCards(homeCards).forEach { kind ->
                            when (kind) {
                                HomeCardKind.NOW_PLAYING ->
                                    if (showMiniPlayer) {
                                        NowPlayingCardView(
                                            title = nowPlaying.title?.takeIf { it.isNotBlank() }
                                                ?: "Now playing",
                                            artist = nowPlaying.artist,
                                            playing = nowPlaying.playing,
                                            cardWidth = cardWidth,
                                            onPlayPause = {
                                                if (nowPlaying.playing) onMediaPause() else onMediaPlay()
                                            },
                                            onNext = onMediaNext,
                                            onOpenTakeover = onTakeoverRestore,
                                        )
                                    }
                                // Match on the CONFIG SLOT, not the list position: evCards()
                                // compacts, so evs[0] can be the second car.
                                HomeCardKind.EV1 ->
                                    evs.firstOrNull { it.slot == 0 }?.let { EvCardView(it, cardWidth) }
                                HomeCardKind.EV2 ->
                                    evs.firstOrNull { it.slot == 1 }?.let { EvCardView(it, cardWidth) }
                                // The 300dp+ tiers (Show 8 / Tab M9) show the animated flow diagram;
                                // the Show 5 (248dp) fails solarFlowCard() and keeps the compact pill
                                // byte-for-byte.
                                HomeCardKind.SOLAR ->
                                    if (solarFlowCard(cardWidth.value.toInt()) && solarGraph != null) {
                                        SolarFlowCardView(solarGraph, cardWidth)
                                    } else if (solar != null) {
                                        SolarCardView(solar, cardWidth)
                                    }
                                HomeCardKind.QUICK_BUTTONS ->
                                    if (quickButtons.isNotEmpty()) {
                                        QuickButtonsCardView(quickButtons, cardWidth, onQuickButton)
                                    }
                            }
                        }
```

- [ ] **Step 3: Key the page reset on the ordering**

Around line 411 the page reset currently reads:

```kotlin
            LaunchedEffect(showMiniPlayer, evs.size, solar != null, quickButtons.isNotEmpty()) { cardPage = 0 }
```

Change it to include the ordering, so a reorder from the web config returns the column to its top page instead of stranding the user on a page that may no longer exist:

```kotlin
            LaunchedEffect(
                showMiniPlayer, evs.size, solar != null, quickButtons.isNotEmpty(), homeCards,
            ) { cardPage = 0 }
```

- [ ] **Step 4: Correct the stale protection comment**

In `AdaptiveGeometry.kt`, `visibleCardCount`'s KDoc says the protected card is the now-playing re-entry. The code guards POSITION, and now that position is user-controlled that comment is wrong. Change this sentence:

```
 * Always returns at least 1 when there is a card (the top card is the protected
 * now-playing re-entry -- it shows even if it alone would overflow).
```

to:

```
 * Always returns at least 1 when there is a card: whatever card the user ordered FIRST is
 * protected and shows even if it alone would overflow. The guarantee follows position, not
 * identity -- it defaulted to the now-playing re-entry only because that card used to be pinned
 * to the top.
```

Make no code change in this file.

- [ ] **Step 5: Pass the config through from `DashboardShell`**

In `DashboardShell.kt`, in the `HomeView(...)` call (near line 310), add:

```kotlin
                        homeCards = config.homeCards,
```

Leave `reserveCardColumn` exactly as it is. Per the spec, the width reserve tracks CONFIG PRESENCE, not enablement — disabling every card leaves the notification width unchanged.

- [ ] **Step 6: Run the full gate**

Run: `./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"`
Expected: `RC=0`, whole suite green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rar/hearth/ui/HomeView.kt \
        app/src/main/java/com/rar/hearth/ui/DashboardShell.kt \
        app/src/main/java/com/rar/hearth/ui/model/AdaptiveGeometry.kt
git commit -m "feat(home): render the card column in user-configured order"
```

---

### Task 5: Web config — the "Home cards" section

**Files:**
- Modify: `app/src/main/assets/config/index.html`
- Modify: `app/src/main/assets/config/app.js`

**Interfaces:**
- Consumes: the `homeCards` JSON block from Task 1 (`{nowPlaying, ev1, ev2, solar, quickButtons}`, each `{enabled, order}`).
- Produces: nothing consumed by later tasks.

**Existing helpers to reuse, do not reinvent:** `el(tag, cls, text)`, `clear(host)`, `glyph(key, cls)`, `reorderButtons(canUp, canDown, onUp, onDown)`, and the `panel-row` / `panel-name` / `chip` / `off` CSS classes used by `renderPanels()`.

- [ ] **Step 1: Add the section markup**

In `index.html`, insert this section immediately AFTER the `home-section` block (which closes around line 252) and BEFORE `claudeusage-section`:

```html
          <section id="homecards-section" class="card-section">
            <div class="card-head">
              <span class="ic" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="5" rx="1.5"/><rect x="4" y="11.5" width="16" height="5" rx="1.5"/><path d="M4 19.5h16"/></svg>
              </span>
              <div class="card-titles">
                <h2>Home cards</h2>
                <p>Order the cards down the right of the home screen, and hide any you don’t want.</p>
              </div>
            </div>
            <div id="homecards"></div>
          </section>
```

- [ ] **Step 2: Generalise `swapOrder`**

`swapOrder` is currently hardcoded to `config.panels`:

```javascript
function swapOrder(ordered, i, j) {
  const a = config.panels[ordered[i]], b = config.panels[ordered[j]];
  const t = a.order; a.order = b.order; b.order = t;
}
```

Change it to take the object it swaps within:

```javascript
// `bag` is the object holding the {enabled, order} entries (config.panels, config.homeCards);
// `ordered` is its keys already sorted by order.
function swapOrder(bag, ordered, i, j) {
  const a = bag[ordered[i]], b = bag[ordered[j]];
  const t = a.order; a.order = b.order; b.order = t;
}
```

Update the two existing call sites inside `renderPanels()` to pass `config.panels` as the first argument:

```javascript
    row.appendChild(reorderButtons(
      idx !== 0, idx !== ordered.length - 1,
      () => { swapOrder(config.panels, ordered, idx, idx - 1); renderPanels(); },
      () => { swapOrder(config.panels, ordered, idx, idx + 1); renderPanels(); },
    ));
```

- [ ] **Step 3: Add the key and label constants**

Beside the existing `PANEL_KEYS` / `PANEL_LABELS` (lines 17-18), add:

```javascript
const HOME_CARD_KEYS = ["nowPlaying", "ev1", "ev2", "solar", "quickButtons"];
const HOME_CARD_LABELS = {
  nowPlaying: "Now playing",
  ev1: "EV 1",
  ev2: "EV 2",
  solar: "Solar",
  quickButtons: "Quick buttons",
};
```

- [ ] **Step 4: Write `renderHomeCards()`**

Add this function immediately after `swapOrder()`:

```javascript
function renderHomeCards() {
  const host = document.getElementById("homecards");
  clear(host);
  // Defensive default for configs saved before this block existed: the pre-feature order.
  if (!config.homeCards) {
    config.homeCards = {
      nowPlaying: { enabled: true, order: 1 },
      ev1: { enabled: true, order: 2 },
      ev2: { enabled: true, order: 3 },
      solar: { enabled: true, order: 4 },
      quickButtons: { enabled: true, order: 5 },
    };
  }
  const cards = config.homeCards;
  HOME_CARD_KEYS.forEach((key, i) => {
    if (!cards[key]) cards[key] = { enabled: true, order: i + 1 };
  });

  // EV rows carry the user's own car names when set -- "Rivian R1T" beats "EV 1".
  const evs = (config.entities && config.entities.evs) || [];
  const label = (key) => {
    if (key === "ev1" && evs[0] && evs[0].name) return evs[0].name;
    if (key === "ev2" && evs[1] && evs[1].name) return evs[1].name;
    return HOME_CARD_LABELS[key];
  };

  const ordered = HOME_CARD_KEYS.slice().sort((a, b) => cards[a].order - cards[b].order);
  let firstEnabledSeen = false;
  ordered.forEach((key, idx) => {
    const c = cards[key];
    const row = el("div", "panel-row" + (c.enabled ? "" : " off"));
    row.appendChild(el("span", "panel-name", label(key)));

    // The head of the order is guaranteed a slot even when it alone would overflow the column.
    // It marks position, not presence -- a card with nothing to show still renders nothing.
    if (c.enabled && !firstEnabledSeen) {
      firstEnabledSeen = true;
      row.appendChild(el("span", "chip", "Shown first"));
    }

    const cb = el("input"); cb.type = "checkbox"; cb.checked = c.enabled;
    cb.setAttribute("aria-label", label(key) + " enabled");
    cb.addEventListener("change", () => { c.enabled = cb.checked; renderHomeCards(); });
    row.appendChild(cb);

    row.appendChild(reorderButtons(
      idx !== 0, idx !== ordered.length - 1,
      () => { swapOrder(cards, ordered, idx, idx - 1); renderHomeCards(); },
      () => { swapOrder(cards, ordered, idx, idx + 1); renderHomeCards(); },
    ));
    host.appendChild(row);
  });

  host.appendChild(el("div", "muted",
    "These are the cards down the right-hand side of the home screen. A card only appears when it " +
    "has something to show — a car that’s plugged in, music that’s playing — so turning one on " +
    "does not force it on screen. Hiding a card keeps its entities, so you can bring it back " +
    "without setting it up again. On a small screen only the first card or two fit; the rest move " +
    "behind the “+N more” chip."));
}
```

The checkbox handler calls `renderHomeCards()` rather than only toggling the class, because turning a card off can move the "Shown first" chip to a different row.

**Watch the row alignment.** `renderPanels()` appends a `glyph(key, "ptile")` as each row's first child, and there are no glyphs for these five keys, so `renderHomeCards()` starts with the name instead. If `.panel-row` turns out to depend on that leading tile for its spacing, the rows will sit flush left and look wrong next to the Panels section above them. Check this in Task 6 Step 2 while the page is open. If it is off, add a same-sized empty spacer (`el("span", "ptile")`) as the first child rather than changing the shared `.panel-row` CSS, which the Panels section also uses.

- [ ] **Step 5: Call it from the render dispatch**

In `render()` (line 372), add the call immediately after `renderHome();`:

```javascript
  renderHome();
  renderHomeCards();
```

- [ ] **Step 6: Syntax-check the JS**

Run: `node --check app/src/main/assets/config/app.js; echo "RC=$?"`
Expected: `RC=0`.

- [ ] **Step 7: Run the full gate**

Run: `./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"`
Expected: `RC=0`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/assets/config/index.html app/src/main/assets/config/app.js
git commit -m "feat(web): Home cards ordering and visibility section"
```

---

### Task 6: Live verification on the Show 8

**Files:** none — this task changes no code.

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: a verification record. If anything here fails, fix it in a follow-up commit and re-run the gate.

**Device:** Echo Show 8 "crown", `10.75.1.139:5555`, web config PIN `2016`. It is the 300 dp tier with both EV cards configured, so it shows the most cards at once.

**Do NOT flash the Kitchen Echo (`10.75.1.98`).** It is mid-run collecting wake-word captures and reinstalling restarts the run. See the `wake-false-positive-investigation` memory.

- [ ] **Step 1: Build and install**

```bash
./gradlew assembleDebug; echo "RC=$?"
adb -s 10.75.1.139:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `RC=0`, then `Success`.

- [ ] **Step 2: Confirm the default layout is unchanged (the golden rule)**

```bash
adb -s 10.75.1.139:5555 shell screencap -p > /tmp/homecards-before.png
```

Expected: the column reads top-to-bottom Rivian R1T, Tesla Model 3, Solar — exactly as it did before this feature, because the stored config has no `homeCards` block yet and the defaults reproduce it.

- [ ] **Step 3: Log in to the device web API**

The config page needs a browser, but the API behind it does not — drive it with curl so this task is executable without one. **The login endpoint requires `Content-Type: application/json`; without it you get a misleading 401.**

```bash
curl -s -X POST http://10.75.1.139:8080/api/login \
  -H 'Content-Type: application/json' -d '{"pin":"2016"}' -c /tmp/hc.jar
curl -s http://10.75.1.139:8080/api/config -b /tmp/hc.jar > /tmp/config-before.json
python3 -c "import json;print(json.load(open('/tmp/config-before.json')).get('homeCards'))"
```

Expected: the login returns without error, and `homeCards` prints either `None` (config predates the block) or the default order. Both are correct.

- [ ] **Step 4: Reorder solar above the EV cards**

```bash
python3 - <<'EOF'
import json
c = json.load(open('/tmp/config-before.json'))
c['homeCards'] = {
    "nowPlaying":   {"enabled": True,  "order": 1},
    "solar":        {"enabled": True,  "order": 2},
    "ev1":          {"enabled": True,  "order": 3},
    "ev2":          {"enabled": True,  "order": 4},
    "quickButtons": {"enabled": True,  "order": 5},
}
json.dump(c, open('/tmp/config-solar-first.json', 'w'))
EOF
curl -s -X PUT http://10.75.1.139:8080/api/config -b /tmp/hc.jar \
  -H 'Content-Type: application/json' --data-binary @/tmp/config-solar-first.json
sleep 3
adb -s 10.75.1.139:5555 shell screencap -p > /tmp/homecards-solar-first.png
```

Expected: the solar card now renders ABOVE both EV cards. Read the screencap to confirm — do not assume the PUT took effect.

- [ ] **Step 5: Disable EV 2 and confirm EV 1 survives in its own place**

```bash
python3 - <<'EOF'
import json
c = json.load(open('/tmp/config-solar-first.json'))
c['homeCards']['ev2']['enabled'] = False
json.dump(c, open('/tmp/config-ev2-off.json', 'w'))
EOF
curl -s -X PUT http://10.75.1.139:8080/api/config -b /tmp/hc.jar \
  -H 'Content-Type: application/json' --data-binary @/tmp/config-ev2-off.json
sleep 3
adb -s 10.75.1.139:5555 shell screencap -p > /tmp/homecards-ev2-off.png
```

Expected: the second car's card is gone and the first car's card still renders. **This is the specific regression Task 3 prevents** — with positional matching instead of `slot`, the surviving car would appear in the disabled car's row. Read the screencap and confirm the car that remains is the one in `entities.evs[0]`.

- [ ] **Step 6: Confirm the hidden card kept its entities**

```bash
curl -s http://10.75.1.139:8080/api/config -b /tmp/hc.jar \
  | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['entities']['evs'][1], indent=1))"
```

Expected: every entity ID for the disabled car is still populated. This is the point of the feature — hiding must not destroy configuration.

- [ ] **Step 7: Restore and confirm the round trip**

```bash
curl -s -X PUT http://10.75.1.139:8080/api/config -b /tmp/hc.jar \
  -H 'Content-Type: application/json' --data-binary @/tmp/config-before.json
sleep 3
adb -s 10.75.1.139:5555 shell screencap -p > /tmp/homecards-after.png
```

Expected: the column matches `/tmp/homecards-before.png` — same cards, same order.

- [ ] **Step 8: Eyeball the web page itself**

Open `http://10.75.1.139:8080` in a browser (PIN `2016`), go to Screens → Home cards. This is the one check curl cannot make: confirm the rows line up with the Panels section above them, the arrows disable correctly at the ends, the EV rows show the cars' real names, and the "Shown first" chip sits on the top enabled row. See the alignment note in Task 5 Step 4 if the rows look flush left.

- [ ] **Step 9: Record the result**

Append one line to `.superpowers/sdd/progress.md` naming the commits verified and what was checked. If any step failed, fix it, re-run the gate, and re-verify before recording.

---

## Notes for the implementer

**Show 5 caveat, not a task.** The Echo Show 5 (`10.75.1.98`) fits roughly one card, so ordering matters most there and the "first card is protected" rule is most visible. It is deliberately excluded from Task 6 because reinstalling would restart the wake-capture run. Do not flash it. A separate check on the other Show 5 (`10.75.0.13`, "freshy", PIN `2016`) is a reasonable substitute if the user wants small-screen confirmation.

**What is deliberately NOT built:** drag-and-drop reordering, per-card settings beyond enable/order, and any change to the Claude usage card or the next-event card. All three are out of scope per the spec.
