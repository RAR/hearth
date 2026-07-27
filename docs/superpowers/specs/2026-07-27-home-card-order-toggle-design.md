# Home Card Ordering and Enable/Disable — Design

**Date:** 2026-07-27
**Status:** Approved for planning

## Goal

Let the user order the home screen's right-hand card column, and hide individual
cards from the web config, without having to clear a card's entity IDs to make it
go away.

## Motivation

Today the column renders a hardcoded sequence — now-playing, then EV cards, then
solar, then quick buttons. The only way to hide one is to delete its entity IDs
from the config, which destroys the configuration you would need to put it back.
The view panels (lights / climate / media / …) already solved exactly this with
`PanelConfig(enabled, order)` and a checkbox-plus-arrows row in the web UI. This
extends that established pattern to the home card column.

## Scope

**In scope:** the right-hand card column only — now-playing, EV 1, EV 2, solar,
quick buttons.

**Out of scope:**

- The Claude usage card (bottom of the notification column) and the next-event
  card (bottom right). Neither is in this column.
- Drag-and-drop reordering. Up/down arrows match the existing panel UI.
- Any per-card setting beyond enable and order.

## Decisions

Each of these was chosen deliberately; the rejected alternative is recorded so a
later reader does not re-litigate it.

### Each EV is its own row

Five rows: `Now playing`, `EV 1`, `EV 2`, `Solar`, `Quick buttons`. The two EV
slots are separately orderable and separately disableable, so one car can sit
above solar and the other below, and either can be hidden while keeping its
entities.

Rejected: treating "EVs" as one block. That would leave the user back at deleting
entities to hide a single car — the exact problem this feature exists to solve.

Consequence: row identity is **positional** (`ev1` = `entities.evs[0]`), not tied
to the car's name. Swapping which car occupies which slot would leave the ordering
attached to the slot. Both slots are permanent and rarely changed, so this is
accepted.

### Now-playing is fully orderable; the protection follows position

The now-playing card is orderable and disableable like any other row. The
"always renders even if it alone overflows" guarantee attaches to whichever card
is **first in the user's order**, not to now-playing specifically.

This requires no logic change. `visibleCardCount()` already ends
`fit.coerceAtLeast(1)` — it guards position, and only its doc comment claims it
guards the now-playing re-entry. The comment gets corrected to describe what the
code does.

Accepted risk: on the Show 5, roughly one card fits. Placing now-playing last
puts it behind the "+N more" chip, so the route back into a dismissed takeover
becomes a chip tap rather than a visible card. This is the user's explicit choice.
It is mitigated, not hidden: the first enabled row carries a "Shown first" chip in
the web UI so the positional rule is stated rather than implicit.

Precisely: the guarantee applies to the first card that actually **renders**, not
the first enabled row. Cards are conditional on content — an enabled now-playing
row shows nothing when no music is playing — so with now-playing first and idle,
the protection falls to the next card with content. The chip marks the head of the
order, which is the part the user controls; it does not claim the card is on screen.

Rejected: pinning now-playing to the top (less control), and making protection
identity-based (would not match the code that already exists).

### Disabled means "configured but hidden"

Disabling a card hides it. It does **not** change the column's width reserve.
`homeOverlayCaps(reserveCardColumn = …)` stays driven by config presence, so
disabling every card in the column leaves the notification stack at its current
width and the right side simply sits empty.

Rejected: treating disabled as unconfigured so the notification stack reclaims the
width. Consistent with the existing principle that the reserve tracks configuration
rather than momentary visibility — which is why a card fading in and out never
makes the notification stack jump.

`AdaptiveGeometry` therefore needs no change at all.

### Disabled cards keep their entity subscriptions

`referencedEntityIds()` is unchanged: a disabled card's entities stay subscribed.
The cost is negligible, and it means re-enabling a card shows real data at once
instead of a card full of blanks awaiting the next state push.

## Architecture

### Config

A new top-level `homeCards` block, deliberately mirroring `panels` in both shape
and position so both can share one UI idiom and one swap helper:

```kotlin
@Serializable
data class HomeCardConfig(val enabled: Boolean = true, val order: Int = 0)

@Serializable
data class HomeCards(
    val nowPlaying:   HomeCardConfig = HomeCardConfig(true, 1),
    val ev1:          HomeCardConfig = HomeCardConfig(true, 2),
    val ev2:          HomeCardConfig = HomeCardConfig(true, 3),
    val solar:        HomeCardConfig = HomeCardConfig(true, 4),
    val quickButtons: HomeCardConfig = HomeCardConfig(true, 5),
)
```

Added to `DashConfig` as `val homeCards: HomeCards = HomeCards()`.

Top-level rather than nested inside `HomeSettings`, which stays a flat bag of
scalars.

**The defaults are exactly today's rendering order, all enabled.** A config saved
before this feature deserializes to today's layout, so existing devices are
pixel-identical after the update. This is the golden rule for this change and the
first thing the tests assert.

`clamped()` normalises the block: `order` is rewritten to the dense sequence
1..5 following the current sort. This is idempotent and prevents drift from
hand-edited configs or an interrupted save.

### Ordering — a pure module

`app/src/main/java/com/rar/hearth/ui/model/HomeCardOrder.kt`, plain Kotlin with no
Compose imports, unit-testable like its `ui/model/` siblings:

```kotlin
enum class HomeCardKind { NOW_PLAYING, EV1, EV2, SOLAR, QUICK_BUTTONS }

/** Enabled cards in user order. Ties broken by enum declaration order. */
fun orderedHomeCards(cards: HomeCards): List<HomeCardKind>
```

Sorted by `order`, disabled entries dropped. **Ties break by enum declaration
order**, so two rows sharing an `order` value produce a stable sequence rather
than one that can flip between frames.

The function decides order and nothing else. Whether a card has content to show
remains where it already lives — `miniPlayerVisible()`, `evCards()`,
`solarCard()`, `quickButtons` — so this module has one responsibility and no
knowledge of entity state.

### `EvCard` gains a slot index

`evCards()` uses `mapNotNull`, so the returned list is compacted: with EV 1
unplugged and EV 2 charging, the result is `[EV2]` at index 0. Slot identity is
lost, and the `ev1` / `ev2` rows cannot be matched positionally.

`EvCard` therefore gains `val slot: Int` (0-based, the config slot it came from),
set from the source index during derivation. `HomeView` selects by slot rather
than by list position. Contained change to a pure module that already has tests.

### `HomeView`

The hardcoded sequence is replaced by a loop over `orderedHomeCards(config.homeCards)`
that dispatches each `HomeCardKind` to the composable it already has. Card
content, styling, and the `CardColumn` / `pageCardCount` paging are untouched.

`cardPage`'s reset `LaunchedEffect` gains the ordering config as a key, so
reordering from the web UI returns the column to its top page rather than leaving
the user mid-column on a page that may no longer exist.

### Web UI

A "Home cards" section on the Screens page, beside the existing Panels section,
reusing `panel-row`, the enable checkbox, and `reorderButtons()`.

`swapOrder(ordered, i, j)` is currently hardcoded to `config.panels`; it is
generalised to take the object it swaps within, and `renderPanels()` updated to
pass `config.panels`. This is the only change to existing JS behaviour.

Row labels use the user's EV names when set (`"Rivian R1T"`), falling back to
`"EV 1"` / `"EV 2"`. The first enabled row carries a "Shown first" chip.

## Data flow

```
config.json ──► DashConfig.homeCards ──► orderedHomeCards() ──► List<HomeCardKind>
                                                                      │
entities (HA websocket) ──► evCards()/solarCard()/quickButtons ───────┤
                            miniPlayerVisible()                       ▼
                                                            HomeView dispatch loop
                                                                      │
                                                            CardColumn (paging)
```

Ordering derives from config alone; content derives from entity state alone. They
meet only in the dispatch loop.

## Error handling and edge cases

| Case | Behaviour |
|---|---|
| Config predates `homeCards` | kotlinx defaults fill in today's order, all enabled — layout unchanged |
| Two rows share an `order` | Stable tie-break by enum declaration order; `clamped()` redensifies on next save |
| All five disabled | Column empty; width reserve retained (see decision above) |
| Card enabled but has no content | Nothing renders, exactly as today — enable gates, it does not force |
| EV 2 enabled, EV 1 disabled, only EV 1 plugged | Nothing renders for EVs; slot matching prevents the old compaction bug from showing EV 1's card in EV 2's place |
| Reorder while the column is paged forward | `cardPage` resets to 0 |

## Testing

Plain-JVM JUnit4 only, consistent with the rest of the app.

**`HomeCardOrderTest`** — defaults reproduce today's order exactly (the golden
rule); disabled rows are dropped; all-disabled yields an empty list; duplicate
`order` values produce a stable, declaration-ordered result; a hand-written
scrambled order sorts correctly.

**`EvModelTest`** — a new case asserting `slot` survives compaction: with slot 0
unplugged and slot 1 charging, the single returned card reports `slot == 1`.

**`DashConfigTest`** — `homeCards` round-trips through serialization; a JSON
document with no `homeCards` key deserializes to the defaults; `clamped()`
redensifies duplicate and sparse `order` values and is idempotent.

Not unit-tested, consistent with existing practice: the Compose dispatch loop and
the web UI beyond `node --check`.

**Gate before every commit:** `./gradlew testDebugUnitTest assembleDebug` with the
return code checked, plus `node --check app.js` for the JS change.

## Verification

Live-verify on the Show 8 (300 dp tier, both EV cards present, the most cards
visible at once): reorder solar above the EVs, disable one car, confirm the other
still renders and the disabled car's entities survive a round trip through save
and reload.

The Show 5 is the tight case — roughly one card fits — and is where the
positional-protection tradeoff is visible. Worth an eyeball, noting that this
device has not yet been checked for the Claude usage card either.

## Global constraints

- `minSdk` 27, `targetSdk` 34, `applicationId` `com.rar.echodash` — all unchanged.
- No new app dependencies.
- Pure `ui/model/` modules take no Compose or Android imports.
- Existing configs must render identically after the update.
