# Quick-Buttons Home Card — Design

**Date:** 2026-07-17
**Status:** Approved (user: "yup 1" / "looks right, go ahead")

## Goal

A home-screen quick-access card holding up to 4 HA entities the user can tap: toggleables
(switch / light / input_boolean) toggle and show live on/off state; stateless actions
(button / script / scene) fire and flash. The card joins the right-hand overlay column below the
EV and solar cards, on every device tier (opt-in via config, so the Show 5 golden rule is
untouched — an unconfigured device renders nothing new).

## Config (`config/DashConfig.kt`)

```kotlin
/** One quick-access button. [name] blank falls back to the entity's friendly_name. */
@Serializable
data class QuickButtonConfig(
    val name: String = "",
    val entity: String? = null,
)
```

- `Entities` gains `val quickButtons: List<QuickButtonConfig> = emptyList()`.
- Watch list: the aggregation block that already does `addAll(entities.solar.ids())` adds
  `entities.quickButtons.forEach { it.entity?.let(::add) }` so button entities are live-subscribed.
- `DashConfig.clamped()` cleans the list like its neighbors: trim `name` and `entity`
  (blank entity → null), **drop slots whose entity is null** (a name alone is useless),
  `take(4)`.

## Model (`ui/model/QuickButtonsModel.kt` — pure Kotlin, zero android imports)

```kotlin
enum class QuickButtonKind { TOGGLE, PRESS }
enum class QuickButtonIcon { LIGHT, SWITCH, RUN, SCENE }

data class QuickButton(
    val entityId: String,
    val label: String,
    val icon: QuickButtonIcon,
    val kind: QuickButtonKind,
    val isOn: Boolean?,      // TOGGLE: state == "on"; PRESS: always null
    val available: Boolean,  // false when entity missing or state unavailable/unknown
)

fun quickButtons(cfg: List<QuickButtonConfig>, entities: Map<String, EntityState>): List<QuickButton>
```

Derivation rules (config order = display order):

| Rule | Value |
|---|---|
| kind | domain `button`/`script`/`scene` → PRESS; everything else → TOGGLE (`homeassistant.toggle` is domain-agnostic, so unknown domains degrade safely) |
| icon | `light` → LIGHT; `script`/`button` → RUN; `scene` → SCENE; everything else (incl. `switch`, `input_boolean`) → SWITCH |
| label | `name` (trimmed) if non-blank, else the entity's `friendly_name` attribute, else the entity-id tail after the `.` (calendar-name convention) |
| isOn | TOGGLE: `state == "on"`; PRESS: `null` |
| available | `false` when the entity is absent from the map or its state is `"unavailable"`/`"unknown"`; otherwise `true` |

Configured slots always produce a `QuickButton` (unavailable ones render dimmed — the card's
layout stays stable when a device drops off). Empty cfg list → empty result → card hidden.

## UI (`ui/HomeView.kt` — `QuickButtonsCardView`)

- Renders in the right column directly below the solar card:
  `evs.forEach { EvCardView } → Solar(Flow)CardView → QuickButtonsCardView`, only when the
  model list is non-empty. The column's `AnimatedVisibility(visible = ...)` gains
  `|| quickButtons.isNotEmpty()`.
- Chrome identical to neighbors: `width(cardWidth)`, `Color.Black.copy(alpha = 0.35f)`,
  `RoundedCornerShape(20.dp)`, padding 16×10.
- One `Row`: each button an equal-`weight(1f)` cell — a **44dp circular chip** (icon 22dp,
  centered) above an **11sp single-line label** (`TextOverflow.Ellipsis`,
  `Color.White.copy(alpha = 0.85f)`), cell contents centered, 6dp vertical gap.
- Chip colors (lights-panel palette): on → fill `Color(0xFF3A6EA5)`; off / PRESS idle → fill
  `Color(0xFF232733)`; icon tint white. Unavailable → whole cell `alpha(0.4f)`, not tappable.
- PRESS feedback: on tap the chip fill animates to `0xFF3A6EA5` and back over ~250ms
  (stateless actions need visible acknowledgment); TOGGLE chips just follow `isOn` from the
  subscription — no optimistic flip.
- Tap → `onTap(QuickButton)` hoisted callback. Night mode unchanged (its overlay eats taps by
  design).

## Dispatch (`ui/DashboardShell.kt`)

Where the shell already builds models and owns the `EntityHub`, compute
`quickButtons(config.entities.quickButtons, entities)` for the HOME branch and pass an
`onQuickButton` lambda:

| kind / domain | call |
|---|---|
| PRESS + `button.*` | `hub.callService("button", "press", entityId = id)` |
| PRESS + `script.*` | `hub.callService("script", "turn_on", entityId = id)` |
| PRESS + `scene.*` | `hub.callService("scene", "turn_on", entityId = id)` |
| TOGGLE (all) | `hub.callService("homeassistant", "toggle", entityId = id)` |

## Web config (`assets/config/app.js`)

New "Quick buttons" card after the solar card, four fixed slots ("Button 1".."Button 4"),
each: name text input + `entityPicker(["switch", "light", "input_boolean", "button", "script",
"scene"], slot.entity, ...)`. Same slot markup pattern as the solar Array A–D rows.

## Tests (plain-JVM JUnit4, matching suite conventions)

- `QuickButtonsModelTest`: kind mapping per domain (incl. unknown-domain → TOGGLE), icon
  mapping, label fallback chain (name → friendly_name → id tail), isOn for on/off,
  available=false for missing and for `unavailable`/`unknown`, PRESS isOn null, order
  preserved, empty cfg → empty.
- `DashConfigTest`: clamp drops entity-less slots, trims, caps at 4; watch list includes
  button entities; round-trip serialization of `quickButtons`.

## Out of scope (deliberate)

Custom icon pickers, confirmation dialogs, >4 slots, drag-reorder (config order is display
order), optimistic toggle state, per-button colors, tier-specific layouts.
