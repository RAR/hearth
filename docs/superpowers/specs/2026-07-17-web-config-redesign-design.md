# Web Config Redesign — Sidebar Navigation + Ember Theme

**Date:** 2026-07-17
**Status:** Approved (layout = sidebar + focused pages; theme = full ember)

## Goal

Replace the config page's single long scroll (13 stacked cards) with 9 focused pages behind
a sidebar (desktop) / scrollable pill row (phone), and re-theme the page from dashboard blue
to the warm ember palette of the Hearth logo. Pure asset change: `index.html`, `style.css`,
`app.js` only. No Kotlin changes, no config-model changes, no new dependencies, vanilla JS.

## Page map

Nine pages. Every existing card moves to exactly one page; the old **Entities** and
**Panel options** cards are dissolved and their contents redistributed by function.

| # | Hash | Nav label | Cards on the page |
|---|------|-----------|-------------------|
| 1 | `#device` | Device | Connect to Home Assistant (conditional setup card) · Device (name/rename) · Backup |
| 2 | `#screens` | Screens | Panels (enable + order) · Home screen (idle, clock, slideshow) · Night mode |
| 3 | `#climate` | Climate & Weather | Sensors (temp, weather, AQI, event rain, **+ forecast days, + sensor decimals**) · Thermostats (climate list, **+ thermostat step**) |
| 4 | `#lights` | Lights & Buttons | Light groups · Quick buttons |
| 5 | `#cameras` | Cameras | Cameras (list + RTSP note) · Doorbells (list, **+ doorbell popup seconds**) |
| 6 | `#energy` | Energy | Solar (sensor slots + arrays A–D, with subheads) · EV charging |
| 7 | `#calendars` | Calendars | Calendars |
| 8 | `#media` | Media & Voice | Media · Sendspin · Voice |
| 9 | `#alerts` | Alerts | Weather alerts (NWS sensor, severity, auto-dismiss) · Push from Home Assistant (token + YAML; card hidden entirely when the build exposes no notify token) |

Bold items are the four rows migrating out of the old "Panel options" card:
`thermostatStep` → Thermostats card, `forecastDays` + `sensorDecimals` → Sensors card,
`doorbellPopupSeconds` → Doorbells card. The old card's muted note about the auto-hiding
panel bar ("swipe in from the right edge…") moves to the Panels card on Screens.
The old Notifications card splits into the two Alerts cards.

## Navigation behavior

- **Routing:** `location.hash` is the source of truth. `#device` … `#alerts` select a page;
  unknown or empty hash falls back to `#device`. Nav items are `<a href="#…">` anchors, so
  browser back/forward and bookmarks work natively; a `hashchange` listener updates the
  active page. (Anchor targets never collide with element ids — page containers use
  `page-<key>` ids.)
- **Active state:** active nav item gets `aria-current="page"` and the visual active
  treatment; exactly one page container is visible, the rest carry `hidden`.
- **Unconfigured device:** when `/api/status` reports `configured: false`, the app navigates
  to `#device` (overriding any hash) so the setup card is front and center. The existing
  `renderSetup(show)` hidden-toggle on the setup card is unchanged.
- **Global chrome:** topbar (brand, status pill, Save) stays as-is and is visible on every
  page. Save always PUTs the whole config regardless of the current page. The full-render
  cycle after save re-renders all pages (hidden ones included), exactly like today.
- **Scroll:** switching pages scrolls the window to the top.

## Responsive layout

- **Desktop (min-width: 860px):** two-column shell. Left: a sticky sidebar (`position:
  sticky` under the topbar, `align-self: flex-start`), ~13rem wide, one row per page with
  a 1.2rem icon + label. Right: the content column, same `max-width: 46rem` cards as today.
- **Phone (below 860px):** the same `<nav>` renders as a sticky horizontally scrollable
  pill row directly under the topbar (`overflow-x: auto`, no wrap, thin/hidden scrollbar).
  Each pill is icon + label. The active pill scrolls into view on selection
  (`scrollIntoView({inline: "nearest"})`).
- One `<nav>` element serves both modes; only CSS changes between them.

## Nav icons

Static inline SVG per nav item in `index.html` (same 24×24 stroke style as the existing
card-head icons, `stroke-width` 1.7, `currentColor`). Reuse existing art where it exists:

- Device → the tag glyph (current Device card head)
- Screens → the 4-tile grid glyph (current Panels card head)
- Climate & Weather → the thermometer glyph (ICONS.climate art)
- Lights & Buttons → the bulb glyph (ICONS.lights art)
- Cameras → the camera glyph (ICONS.cameras art)
- Energy → the sun glyph (ICONS.solar art)
- Calendars → the calendar glyph (current Calendars card head)
- Media & Voice → the play-circle glyph (ICONS.media art)
- Alerts → the bell glyph (current Notifications card head)

## Ember theme (full)

Ground stays dark navy (`--bg #0f1420`, cards `#151b28` — already matches the icon tile).
Everything currently blue-accented goes warm, drawn from the logo flame gradient
`#BD2F0B → #EF6A17 → #F8B62D` (+ inner-flame `#FFCE49`):

- **Tokens:** `--accent: #EF6A17`, `--accent-hi: #F8B62D`. New `--ink: #221204` (dark text
  for on-flame contrast) and `--flame: linear-gradient(180deg, #F8B62D, #EF6A17)`.
- **Primary buttons** (`.btn-primary`, incl. Save/Unlock/Connect): flame gradient
  background, `--ink` text (white-on-orange fails contrast; dark ink passes), warm shadow
  `rgba(239,106,23,.45)`. Base `button` rule likewise flame-solid `--accent` with `--ink`
  text. Ghost/danger buttons keep their neutral/red styling.
- **Toggles:** checked track = flame gradient, border `--accent`; knob stays white.
- **Focus:** inputs/selects focus border `--accent`, ring `rgba(239,106,23,.30)`; button
  and toggle focus outlines `--accent-hi`.
- **Nav active state:** warm fill `rgba(239,106,23,.12)`, text `--accent-hi`, plus a 3px
  vertical flame-gradient indicator bar (`#F8B62D → #EF6A17 → #BD2F0B` top-to-bottom) on
  the item's leading edge (sidebar mode); pill mode uses the same fill plus an
  `--accent`-tinted border instead of the bar.
- **Card icon tiles** (`.ic`) and panel-row tiles (`.ptile`): warm dark gradient
  (`#3d2412 → #241710`), border `#4d2f16`, glyph `--accent-hi`.
- **Ambient glow:** body/login radial gradients swap blue for warm —
  `rgba(239,106,23,.13)` main glow, `rgba(248,182,45,.05)` corner glow.
- **Details:** select chevron SVG stroke → `#F8B62D`; `.add` dashed button → warm border
  `#4a3220`, text `--accent-hi`, hover fill `rgba(239,106,23,.12)`; `.status.busy` →
  `--accent-hi`; subheads warm-biased neutral `#c0a88b`. Semantic colors unchanged:
  `--ok` green, `--err` red. `theme-color` meta stays `#0f1420`.

## app.js restructuring

- `renderEntities()` and `renderOptions()` are dissolved. New render functions, one per
  new host div: `renderSensors()` (#sensors), `renderThermostats()` (#thermostats),
  `renderLightGroups()` (#lightgroups), `renderQuickButtons()` (#quickbuttons),
  `renderCameras()` (#cameras), `renderDoorbells()` (#doorbells), `renderSolar()` (#solar),
  `renderNws()` (#nws), `renderPush()` (#push). Existing helpers (`renderLightGroup`,
  `renderCamera`, `renderDoorbell`, `renderCalendar`, `entityPicker`, `labeledRow`,
  `subhead`, shared datalists) are reused unchanged; row-level re-render calls inside them
  change from `renderEntities()` to the new per-section function that owns them.
- `render()` calls the full new list. Behavior (defensive defaults, clamp notes, muted
  copy) is preserved verbatim — copy moves, it doesn't change, except the four migrated
  rows keep their labels and the old combined clamp note is split to sit beside its rows.
- New tiny nav module: `PAGES` constant (the 9 hash keys), `currentPage()` (hash → valid
  key, default `device`), `showPage(key)` (toggle `hidden` on `#page-<key>` containers,
  set `aria-current`, scroll to top), a `hashchange` listener, and the
  `configured === false` → `location.hash = "#device"` override in `tryLoad()`.
- Elements polled by id while hidden (`#night-lux`, `#sendspin-status`, `#datalists`)
  keep working — pages hide via the `[hidden]` CSS contract, the DOM stays in place.

## index.html restructuring

`<div class="content">` becomes a shell: `<nav id="nav">` (9 anchors with inline SVG +
label) + `<div class="pages">` holding 9 `<section class="page" id="page-…" hidden>`
containers. Existing card sections move inside their page container, keeping their
current ids and card-head markup; new card sections (Sensors, Thermostats, Light groups,
Quick buttons, Cameras, Doorbells, Solar, Weather alerts, Push) get the same card-head
pattern (icon tile + h2 + one-line description) with their host div.

## Out of scope

- No search/filter, no per-field change tracking, no unsaved-changes warning (Save stays
  manual and global, as today).
- No Kotlin/server changes; `/api/*` contracts untouched.
- No light theme — the page stays dark like the device UI.
- No behavior changes to any control (clamps, defaults, tokens, YAML snippet all as-is).

## Verification

- Gate before every commit: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew
  :app:testDebugUnitTest :app:assembleDebug` (assets ship inside the APK).
- Live check on a flashed device from a browser: login → each of the 9 pages renders its
  cards → save round-trip works from a non-default page → unconfigured flow forced to
  Device (only checkable on an unconfigured build; skip live if none available).
