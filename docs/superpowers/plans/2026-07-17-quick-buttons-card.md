# Quick-Buttons Home Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A home-screen quick-access card holding up to 4 HA entities the user can tap — switch/light/input_boolean toggle and show live on/off state; button/script/scene fire and flash — joining the right-hand overlay column below the EV and solar cards on every device tier, opt-in via config.

**Architecture:** A pure model (`ui/model/QuickButtonsModel.kt`) derives an immutable `List<QuickButton>` (kind, icon, label, on-state, availability) from the configured slots via domain rules, plus a pure `quickButtonService()` dispatch mapping — both plain-JVM unit-tested. A single Compose card (`HomeView.QuickButtonsCardView`) renders that list of chips; `DashboardShell` computes the model in the HOME branch (mirroring `solarGraph`) and threads an `onQuickButton` callback that `App.kt` binds to `EntityHub.callService` via `quickButtonService()`. Config lives on `Entities.quickButtons`, cleaned by `DashConfig.clamped()` and watched via `referencedEntityIds()`. The web config page gains a fixed-four-slot "Quick buttons" card mirroring the solar Array A–D rows.

**Tech Stack:** Kotlin (JVM target 17), Jetpack Compose (Compose BOM, material-icons-extended 1.7.6 + material-icons-core), kotlinx.serialization, JUnit4 (plain JVM). Package `com.rar.echodash`. No new dependencies.

## Global Constraints

- **The gate for every task** is `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` — must pass (BUILD SUCCESSFUL) before every commit.
- **NO new dependencies.** Everything uses already-present artifacts.
- **SDK pins:** `compileSdk`/`targetSdk` stay at **34** (never bump); `minSdk = 28` (never bump).
- **Tests are plain-JVM JUnit4 only** — no instrumented tests, no Robolectric. Files under `ui/model` must have **zero android/androidx imports** (kotlin stdlib + `config.*`/`ha.EntityState` only). `QuickButtonsModel.kt`'s only imports are `com.rar.echodash.config.QuickButtonConfig` and `com.rar.echodash.ha.EntityState`.
- **UI composables have no unit tests** (repo convention) — the UI task (Task 3) and the wiring task (Task 4) gate on **compilation + the existing suite staying green**. Only the pure model functions (Tasks 1, 2) get new tests. `app.js` (Task 5) has no automated test; its gate is `assembleDebug` (packages the asset) plus a `node --check` syntax pass.
- **Material icons** (`androidx.compose.material:material-icons-extended` is already a dependency): `Icons.Outlined.Lightbulb` (LIGHT), `Icons.Outlined.Power` (SWITCH), `Icons.Outlined.PlayArrow` (RUN), `Icons.Outlined.Palette` (SCENE). All four confirmed present in the bundled libraries (Lightbulb/Power/Palette in `material-icons-extended-1.7.6`, PlayArrow in `material-icons-core`).
- **Comments explain *why*, not *what*.** Match surrounding style.
- **Work directly on `master`.** Keep commits small and focused.
- **Every commit message ends with the trailer line** `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`.
- **Out of scope (do NOT build):** custom icon pickers, confirmation dialogs, >4 slots, drag-reorder (config order IS display order), optimistic toggle state, per-button colors, tier-specific layouts.

---

## Task 1 — Config: `QuickButtonConfig` + `Entities.quickButtons` + clamp + watch list

Adds the `QuickButtonConfig` data class, the `Entities.quickButtons` field, the `referencedEntityIds()` watch-list hook, and the `clamped()` clean. Serialization round-trips automatically (kotlinx `encodeDefaults=true`, `ignoreUnknownKeys=true`); old configs decode to an empty list. This task must run **first** — Task 2's model and its tests reference `QuickButtonConfig`.

**Files**
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
- Test: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`

**Interfaces**
- Produces:
  - `data class QuickButtonConfig(val name: String = "", val entity: String? = null)` (`@Serializable`)
  - `Entities` gains `val quickButtons: List<QuickButtonConfig> = emptyList()`
  - `DashConfig.referencedEntityIds()` includes each `quickButtons[].entity`
  - `DashConfig.clamped()` trims/drops/caps `quickButtons` to at most 4 entity-bearing slots

### Steps

- [ ] **Step 1: Write the failing tests.** Append these three methods inside the existing `class DashConfigTest { ... }` in `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` (the test is in package `com.rar.echodash.config`, so `QuickButtonConfig`/`Entities`/`DashConfig` need no import; existing imports already include `assertEquals`):

  ```kotlin
      @Test
      fun quickButtonsRoundTripAndDefault() {
          val cfg = DashConfig(
              entities = Entities(
                  quickButtons = listOf(
                      QuickButtonConfig(name = "Desk", entity = "light.desk"),
                      QuickButtonConfig(entity = "script.movie_night"),
                  ),
              ),
          )
          val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
          assertEquals(cfg, decodeConfig(text))
          // old configs (no key) decode to an empty list
          val old = decodeConfig("""{"version":1}""")
          assertEquals(emptyList<QuickButtonConfig>(), old.entities.quickButtons)
      }

      @Test
      fun clampedQuickButtonsTrimDropEntitylessAndCap() {
          val cleaned = DashConfig(
              entities = Entities(
                  quickButtons = listOf(
                      QuickButtonConfig(name = "  Desk  ", entity = "  light.desk  "),
                      QuickButtonConfig(name = "  ", entity = "switch.fan"),      // blank name kept, entity present
                      QuickButtonConfig(name = "Nameless", entity = "  "),        // blank entity -> dropped
                      QuickButtonConfig(name = "  ", entity = null),              // no entity -> dropped
                      QuickButtonConfig(name = "Two", entity = "input_boolean.a"),
                      QuickButtonConfig(name = "Three", entity = "button.b"),
                      QuickButtonConfig(name = "Fifth", entity = "scene.c"),      // 5th valid -> capped out
                  ),
              ),
          ).clamped().entities.quickButtons
          assertEquals(4, cleaned.size)
          assertEquals(QuickButtonConfig("Desk", "light.desk"), cleaned[0])
          assertEquals(QuickButtonConfig("", "switch.fan"), cleaned[1])
          assertEquals(QuickButtonConfig("Two", "input_boolean.a"), cleaned[2])
          assertEquals(QuickButtonConfig("Three", "button.b"), cleaned[3])
      }

      @Test
      fun referencedEntityIdsIncludeQuickButtons() {
          val cfg = DashConfig(
              entities = Entities(
                  quickButtons = listOf(
                      QuickButtonConfig(entity = "light.desk"),
                      QuickButtonConfig(name = "name-only"),   // no entity -> contributes nothing
                      QuickButtonConfig(entity = "scene.movie"),
                  ),
              ),
          )
          assertEquals(listOf("light.desk", "scene.movie"), cfg.referencedEntityIds())
      }
  ```

- [ ] **Step 2: Run tests to verify they fail.**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`
  Expected: **compile failure** — `QuickButtonConfig` unresolved and `Entities` has no `quickButtons` parameter.

- [ ] **Step 3: Add the `QuickButtonConfig` data class.** Insert it into `DashConfig.kt` immediately after the `CalendarConfig` class (after its closing `}` on line ~96, before `@Serializable data class Entities(`):

  ```kotlin
  /** One home-screen quick button. [name] blank falls back to the entity's friendly_name (else the
   *  entity-id tail, resolved in the model). [entity] is a switch/light/input_boolean (toggles) or a
   *  button/script/scene (fires). */
  @Serializable
  data class QuickButtonConfig(
      val name: String = "",
      val entity: String? = null,
  )
  ```

- [ ] **Step 4: Add the `Entities.quickButtons` field.** In `DashConfig.kt`, extend the `Entities` data class. Old block (lines ~109-111):

  ```kotlin
      val evs: List<EvConfig> = emptyList(),
      val calendars: List<CalendarConfig> = emptyList(),
  )
  ```

  New:

  ```kotlin
      val evs: List<EvConfig> = emptyList(),
      val calendars: List<CalendarConfig> = emptyList(),
      val quickButtons: List<QuickButtonConfig> = emptyList(),
  )
  ```

- [ ] **Step 5: Add the watch-list hook.** In `referencedEntityIds()`, add the quick-button entities right after the EV loop. Old block (lines ~251-252):

  ```kotlin
          entities.evs.forEach { addAll(it.ids()) }
          media.companionEntity?.let { add(it) }
  ```

  New:

  ```kotlin
          entities.evs.forEach { addAll(it.ids()) }
          entities.quickButtons.forEach { qb -> qb.entity?.let { add(it) } }
          media.companionEntity?.let { add(it) }
  ```

- [ ] **Step 6: Add the `clamped()` clean.** In `clamped()`'s `entities.copy(...)`, add the `quickButtons` clean after `calendars = cleanedCalendars,`. Old block (line ~330):

  ```kotlin
                  calendars = cleanedCalendars,
              ),
  ```

  New:

  ```kotlin
                  calendars = cleanedCalendars,
                  // Trim both fields, drop slots with no entity (a name alone is useless), cap at 4.
                  quickButtons = entities.quickButtons
                      .map { it.copy(name = it.name.trim(), entity = it.entity?.trim()?.ifBlank { null }) }
                      .filter { it.entity != null }
                      .take(4),
              ),
  ```

- [ ] **Step 7: Run the tests to verify they pass.**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`
  Expected: **BUILD SUCCESSFUL**, all `DashConfigTest` methods green (existing ones unchanged).

- [ ] **Step 8: Gate.**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  Expected: **BUILD SUCCESSFUL**.

- [ ] **Step 9: Commit.**

  ```bash
  git add app/src/main/java/com/rar/echodash/config/DashConfig.kt app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
  git commit -m "feat(config): quick-button slots (QuickButtonConfig + Entities.quickButtons)

  Adds QuickButtonConfig, Entities.quickButtons, the referencedEntityIds()
  watch hook, and a clamped() clean (trim, drop entity-less slots, cap 4).
  Serialization/export-import round-trips for free. 3 new config tests.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
  ```

---

## Task 2 — Model: `QuickButtonsModel.kt` (derivation + dispatch)

A new pure-Kotlin model file: the `QuickButtonKind`/`QuickButtonIcon` enums, the `QuickButton` data class, the `quickButtons()` derivation, and the `quickButtonService()` dispatch mapping. Zero android imports. Fully unit-tested per the spec's derivation table and dispatch table.

**Files**
- Create: `app/src/main/java/com/rar/echodash/ui/model/QuickButtonsModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/QuickButtonsModelTest.kt`

**Interfaces**
- Consumes: `com.rar.echodash.config.QuickButtonConfig` (Task 1), `com.rar.echodash.ha.EntityState` (`.state: String`, `.attr("friendly_name"): String?`).
- Produces:
  - `enum class QuickButtonKind { TOGGLE, PRESS }`
  - `enum class QuickButtonIcon { LIGHT, SWITCH, RUN, SCENE }`
  - `data class QuickButton(val entityId: String, val label: String, val icon: QuickButtonIcon, val kind: QuickButtonKind, val isOn: Boolean?, val available: Boolean)`
  - `fun quickButtons(cfg: List<QuickButtonConfig>, entities: Map<String, EntityState>): List<QuickButton>`
  - `fun quickButtonService(entityId: String): Pair<String, String>` — returns `(domain, service)` for `EntityHub.callService`.

### Steps

- [ ] **Step 1: Write the failing tests.** Create `app/src/test/java/com/rar/echodash/ui/model/QuickButtonsModelTest.kt` with this complete content:

  ```kotlin
  package com.rar.echodash.ui.model

  import com.rar.echodash.config.QuickButtonConfig
  import com.rar.echodash.ha.EntityState
  import kotlinx.serialization.json.Json
  import kotlinx.serialization.json.JsonObject
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Test

  class QuickButtonsModelTest {
      private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject

      /** [friendly] present -> attributes carry a friendly_name; otherwise no attributes. */
      private fun st(id: String, state: String, friendly: String? = null): EntityState =
          EntityState(
              id, state,
              friendly?.let { attrs("""{"friendly_name":"$it"}""") } ?: JsonObject(emptyMap()),
              0L,
          )

      @Test
      fun emptyConfigYieldsEmptyList() {
          assertEquals(emptyList<QuickButton>(), quickButtons(emptyList(), emptyMap()))
      }

      @Test
      fun kindMappingByDomainIncludingUnknown() {
          val cfg = listOf(
              QuickButtonConfig(entity = "switch.a"),
              QuickButtonConfig(entity = "light.b"),
              QuickButtonConfig(entity = "input_boolean.c"),
              QuickButtonConfig(entity = "button.d"),
              QuickButtonConfig(entity = "script.e"),
              QuickButtonConfig(entity = "scene.f"),
              QuickButtonConfig(entity = "fan.g"), // unknown domain -> TOGGLE (homeassistant.toggle is domain-agnostic)
          )
          assertEquals(
              listOf(
                  QuickButtonKind.TOGGLE, QuickButtonKind.TOGGLE, QuickButtonKind.TOGGLE,
                  QuickButtonKind.PRESS, QuickButtonKind.PRESS, QuickButtonKind.PRESS,
                  QuickButtonKind.TOGGLE,
              ),
              quickButtons(cfg, emptyMap()).map { it.kind },
          )
      }

      @Test
      fun iconMappingByDomain() {
          val cfg = listOf(
              QuickButtonConfig(entity = "light.a"),
              QuickButtonConfig(entity = "switch.b"),
              QuickButtonConfig(entity = "input_boolean.c"),
              QuickButtonConfig(entity = "script.d"),
              QuickButtonConfig(entity = "button.e"),
              QuickButtonConfig(entity = "scene.f"),
          )
          assertEquals(
              listOf(
                  QuickButtonIcon.LIGHT, QuickButtonIcon.SWITCH, QuickButtonIcon.SWITCH,
                  QuickButtonIcon.RUN, QuickButtonIcon.RUN, QuickButtonIcon.SCENE,
              ),
              quickButtons(cfg, emptyMap()).map { it.icon },
          )
      }

      @Test
      fun labelFallbackChainNameThenFriendlyThenTail() {
          val cfg = listOf(
              QuickButtonConfig(name = "  Desk Lamp  ", entity = "light.desk"), // explicit name wins (trimmed)
              QuickButtonConfig(entity = "switch.porch"),                       // -> friendly_name
              QuickButtonConfig(entity = "scene.movie_night"),                  // -> id tail (no friendly_name)
          )
          val entities = mapOf(
              "light.desk" to st("light.desk", "on", friendly = "Desk (registry)"),
              "switch.porch" to st("switch.porch", "off", friendly = "Porch Light"),
              "scene.movie_night" to st("scene.movie_night", "unknown"),
          )
          assertEquals(
              listOf("Desk Lamp", "Porch Light", "movie_night"),
              quickButtons(cfg, entities).map { it.label },
          )
      }

      @Test
      fun isOnReflectsToggleStateAndPressIsNull() {
          val cfg = listOf(
              QuickButtonConfig(entity = "switch.on"),
              QuickButtonConfig(entity = "switch.off"),
              QuickButtonConfig(entity = "button.press"),
          )
          val entities = mapOf(
              "switch.on" to st("switch.on", "on"),
              "switch.off" to st("switch.off", "off"),
              "button.press" to st("button.press", "2026-07-17T00:00:00+00:00"),
          )
          val out = quickButtons(cfg, entities)
          assertEquals(true, out[0].isOn)
          assertEquals(false, out[1].isOn)
          assertNull(out[2].isOn) // PRESS always null
      }

      @Test
      fun availabilityFalseForMissingAndUnavailableOrUnknown() {
          val cfg = listOf(
              QuickButtonConfig(entity = "switch.here"),
              QuickButtonConfig(entity = "switch.gone"),    // absent from map
              QuickButtonConfig(entity = "switch.unavail"),
              QuickButtonConfig(entity = "switch.unknown"),
          )
          val entities = mapOf(
              "switch.here" to st("switch.here", "off"),
              "switch.unavail" to st("switch.unavail", "unavailable"),
              "switch.unknown" to st("switch.unknown", "unknown"),
          )
          assertEquals(
              listOf(true, false, false, false),
              quickButtons(cfg, entities).map { it.available },
          )
      }

      @Test
      fun orderPreservedAndNullEntitySlotsSkipped() {
          val cfg = listOf(
              QuickButtonConfig(name = "First", entity = "switch.a"),
              QuickButtonConfig(name = "NoEntity"),        // entity null -> skipped
              QuickButtonConfig(name = "Second", entity = "light.b"),
          )
          assertEquals(
              listOf("switch.a", "light.b"),
              quickButtons(cfg, emptyMap()).map { it.entityId },
          )
      }

      @Test
      fun quickButtonServiceMapsPerSpecTable() {
          assertEquals("button" to "press", quickButtonService("button.doorbell"))
          assertEquals("script" to "turn_on", quickButtonService("script.movie_night"))
          assertEquals("scene" to "turn_on", quickButtonService("scene.evening"))
          assertEquals("homeassistant" to "toggle", quickButtonService("switch.fan"))
          assertEquals("homeassistant" to "toggle", quickButtonService("light.desk"))
          assertEquals("homeassistant" to "toggle", quickButtonService("input_boolean.guest"))
          assertEquals("homeassistant" to "toggle", quickButtonService("fan.unknown_domain"))
      }
  }
  ```

- [ ] **Step 2: Run the tests to verify they fail.**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.QuickButtonsModelTest"`
  Expected: **compile failure** — `quickButtons`, `quickButtonService`, `QuickButton`, `QuickButtonKind`, `QuickButtonIcon` unresolved.

- [ ] **Step 3: Create the model.** Create `app/src/main/java/com/rar/echodash/ui/model/QuickButtonsModel.kt` with this complete content:

  ```kotlin
  package com.rar.echodash.ui.model

  import com.rar.echodash.config.QuickButtonConfig
  import com.rar.echodash.ha.EntityState

  /** Whether a quick button toggles a stateful entity or fires a stateless action. */
  enum class QuickButtonKind { TOGGLE, PRESS }

  /** Glyph family for a quick button, chosen from the entity domain. */
  enum class QuickButtonIcon { LIGHT, SWITCH, RUN, SCENE }

  /** One derived quick button ready to render. [isOn] is null for PRESS (stateless actions have no
   *  on/off state); [available] false dims the chip and disables its tap when the entity is missing
   *  or reports unavailable/unknown. */
  data class QuickButton(
      val entityId: String,
      val label: String,
      val icon: QuickButtonIcon,
      val kind: QuickButtonKind,
      val isOn: Boolean?,
      val available: Boolean,
  )

  /** Action domains fire (PRESS); everything else toggles. homeassistant.toggle is domain-agnostic,
   *  so unknown domains degrade safely to TOGGLE. */
  private val PRESS_DOMAINS = setOf("button", "script", "scene")

  /**
   * Derive the render list (config order = display order). Every configured slot with an entity
   * produces a QuickButton — unavailable ones render dimmed so the card's layout stays stable when a
   * device drops off. Slots with no entity are skipped; empty cfg -> empty list -> card hidden.
   */
  fun quickButtons(cfg: List<QuickButtonConfig>, entities: Map<String, EntityState>): List<QuickButton> =
      cfg.mapNotNull { slot ->
          val id = slot.entity ?: return@mapNotNull null
          val domain = id.substringBefore('.')
          val state = entities[id]
          val kind = if (domain in PRESS_DOMAINS) QuickButtonKind.PRESS else QuickButtonKind.TOGGLE
          QuickButton(
              entityId = id,
              // name (trimmed) -> friendly_name -> entity-id tail (the calendar-name convention).
              label = slot.name.trim().ifBlank {
                  state?.attr("friendly_name")?.takeIf { it.isNotBlank() } ?: id.substringAfter('.')
              },
              icon = when (domain) {
                  "light" -> QuickButtonIcon.LIGHT
                  "script", "button" -> QuickButtonIcon.RUN
                  "scene" -> QuickButtonIcon.SCENE
                  else -> QuickButtonIcon.SWITCH // incl. switch, input_boolean, unknown domains
              },
              kind = kind,
              isOn = if (kind == QuickButtonKind.TOGGLE) state?.state == "on" else null,
              available = state != null && state.state != "unavailable" && state.state != "unknown",
          )
      }

  /** The HA service call for a quick button, keyed on entity domain (the design's dispatch table):
   *  button.press / script|scene.turn_on fire the action; everything else routes to the
   *  domain-agnostic homeassistant.toggle. Returns (domain, service). */
  fun quickButtonService(entityId: String): Pair<String, String> =
      when (entityId.substringBefore('.')) {
          "button" -> "button" to "press"
          "script" -> "script" to "turn_on"
          "scene" -> "scene" to "turn_on"
          else -> "homeassistant" to "toggle"
      }
  ```

- [ ] **Step 4: Run the tests to verify they pass.**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.QuickButtonsModelTest"`
  Expected: **BUILD SUCCESSFUL**, all 8 `QuickButtonsModelTest` methods green.

- [ ] **Step 5: Gate.**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  Expected: **BUILD SUCCESSFUL**.

- [ ] **Step 6: Commit.**

  ```bash
  git add app/src/main/java/com/rar/echodash/ui/model/QuickButtonsModel.kt app/src/test/java/com/rar/echodash/ui/model/QuickButtonsModelTest.kt
  git commit -m "feat(home): QuickButton derivation + dispatch model

  Pure ui/model: QuickButtonKind/QuickButtonIcon enums, QuickButton, the
  quickButtons() derivation (kind/icon/label-fallback/isOn/available per
  domain), and the quickButtonService() dispatch mapping. 8 new tests.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
  ```

---

## Task 3 — UI: `QuickButtonsCardView` in `HomeView.kt`

Adds the card composable, its two `HomeView` parameters, and the right-column insertion. **No unit test — Compose is untestable in this repo's plain-JVM harness, so the gate is that `:app:assembleDebug` compiles** and the existing suite stays green. `HomeView`'s new params default to empty/no-op, so this task compiles and gates green on its own; Task 4 supplies the real values.

**Files**
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt`

**Interfaces**
- Consumes: `QuickButton`, `QuickButtonIcon`, `QuickButtonKind` (from `ui.model`, Task 2); `Icons.Outlined.Lightbulb/Power/PlayArrow/Palette`; `androidx.compose.animation.animateColorAsState`.
- Produces: `HomeView` gains `quickButtons: List<QuickButton> = emptyList()` and `onQuickButton: (QuickButton) -> Unit = {}`; private `QuickButtonsCardView`, `QuickButtonCell`, `quickButtonIcon` composables.

### Steps

- [ ] **Step 1: Add imports.** In `HomeView.kt`, add these import lines (keep the file's existing grouping/order; the anchors below show where each fits). After `import androidx.compose.animation.AnimatedVisibility` (line ~14) — the color-animation API lives in `androidx.compose.animation`:

  ```kotlin
  import androidx.compose.animation.animateColorAsState
  ```

  After `import androidx.compose.foundation.background` (line ~19):

  ```kotlin
  import androidx.compose.foundation.clickable
  ```

  With the other `androidx.compose.material.icons.outlined.*` imports (lines ~42-47):

  ```kotlin
  import androidx.compose.material.icons.outlined.Lightbulb
  import androidx.compose.material.icons.outlined.Palette
  import androidx.compose.material.icons.outlined.PlayArrow
  import androidx.compose.material.icons.outlined.Power
  ```

  After `import androidx.compose.ui.draw.clipToBounds` (line ~64):

  ```kotlin
  import androidx.compose.ui.draw.alpha
  ```

  After `import androidx.compose.ui.graphics.asImageBitmap` (line ~68):

  ```kotlin
  import androidx.compose.ui.graphics.vector.ImageVector
  ```

  With the `com.rar.echodash.ui.model.*` imports (lines ~82-97):

  ```kotlin
  import com.rar.echodash.ui.model.QuickButton
  import com.rar.echodash.ui.model.QuickButtonIcon
  import com.rar.echodash.ui.model.QuickButtonKind
  ```

  (Already imported and reused: `Icons`, `Icon`, `Text`, `Column`, `Row`, `Box`, `Modifier`, `Arrangement`, `Alignment`, `Color`, `CircleShape`, `RoundedCornerShape`, `clip`, `background`, `padding`, `size`, `width`, `fillMaxWidth`, `dp`, `sp`, `TextAlign`, `TextOverflow`, `remember`, `mutableStateOf`, `getValue`, `setValue`, `LaunchedEffect`, `tween`, `kotlinx.coroutines.delay`, `Dp`.)

- [ ] **Step 2: Add the two `HomeView` parameters.** Old block (lines ~167-169):

  ```kotlin
      solar: SolarCard? = null,
      solarGraph: SolarFlowGraph? = null,
      notifications: List<NotificationItem> = emptyList(),
  ```

  New:

  ```kotlin
      solar: SolarCard? = null,
      solarGraph: SolarFlowGraph? = null,
      quickButtons: List<QuickButton> = emptyList(),
      onQuickButton: (QuickButton) -> Unit = {},
      notifications: List<NotificationItem> = emptyList(),
  ```

- [ ] **Step 3: Insert the card into the right column and widen the visibility gate.** Old block (lines ~332-348):

  ```kotlin
              AnimatedVisibility(
                  visible = evs.isNotEmpty() || solar != null,
                  enter = fadeIn(tween(600)),
                  exit = fadeOut(tween(600)),
                  modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
              ) {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                      evs.forEach { EvCardView(it, cardWidth) }
                      // The 300dp+ tiers (Show 8 / Tab M9) show the animated flow diagram; the Show 5
                      // (248dp) fails solarFlowCard() and keeps the compact pill byte-for-byte.
                      if (solarFlowCard(cardWidth.value.toInt()) && solarGraph != null) {
                          SolarFlowCardView(solarGraph, cardWidth)
                      } else if (solar != null) {
                          SolarCardView(solar, cardWidth)
                      }
                  }
              }
  ```

  New:

  ```kotlin
              AnimatedVisibility(
                  visible = evs.isNotEmpty() || solar != null || quickButtons.isNotEmpty(),
                  enter = fadeIn(tween(600)),
                  exit = fadeOut(tween(600)),
                  modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
              ) {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                      evs.forEach { EvCardView(it, cardWidth) }
                      // The 300dp+ tiers (Show 8 / Tab M9) show the animated flow diagram; the Show 5
                      // (248dp) fails solarFlowCard() and keeps the compact pill byte-for-byte.
                      if (solarFlowCard(cardWidth.value.toInt()) && solarGraph != null) {
                          SolarFlowCardView(solarGraph, cardWidth)
                      } else if (solar != null) {
                          SolarCardView(solar, cardWidth)
                      }
                      // Quick buttons sit below the EV/solar cards on every tier (opt-in via config).
                      if (quickButtons.isNotEmpty()) {
                          QuickButtonsCardView(quickButtons, cardWidth, onQuickButton)
                      }
                  }
              }
  ```

  (Night mode needs no change here: `DashboardShell`'s `NightClockOverlay` sits above `HomeView` and eats taps by design.)

- [ ] **Step 4: Append the card composables.** Add these at the end of `HomeView.kt`, after the closing `}` of `SolarCardView` (end of file, line ~707):

  ```kotlin
  /** Home quick-buttons card: up to four equal-width cells, each a 44dp circular chip (22dp icon)
   *  above an 11sp single-line label. Same black-0.35 / RoundedCornerShape(20) / 16×10 chrome as the
   *  EV and solar cards. TOGGLE chips follow live [QuickButton.isOn]; PRESS chips flash on tap.
   *  Unavailable cells dim to 0.4 and ignore taps. */
  @Composable
  private fun QuickButtonsCardView(
      buttons: List<QuickButton>,
      cardWidth: Dp,
      onTap: (QuickButton) -> Unit,
  ) {
      Row(
          Modifier
              .width(cardWidth)
              .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
              .padding(horizontal = 16.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
          buttons.forEach { button ->
              QuickButtonCell(button, onTap, Modifier.weight(1f))
          }
      }
  }

  @Composable
  private fun QuickButtonCell(
      button: QuickButton,
      onTap: (QuickButton) -> Unit,
      modifier: Modifier = Modifier,
  ) {
      // PRESS actions are stateless, so a tap gives no live feedback: flash the chip blue and let it
      // fade back (~250ms) as the acknowledgment. TOGGLE chips instead follow isOn from the
      // subscription — no optimistic flip.
      var flashing by remember(button.entityId) { mutableStateOf(false) }
      LaunchedEffect(flashing) {
          if (flashing) { delay(250); flashing = false }
      }
      val targetColor = when {
          button.kind == QuickButtonKind.PRESS -> if (flashing) QuickChipOn else QuickChipOff
          button.isOn == true -> QuickChipOn
          else -> QuickChipOff
      }
      val chipColor by animateColorAsState(targetColor, tween(250), label = "quickButtonChip")
      Column(
          modifier.alpha(if (button.available) 1f else 0.4f),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
          Box(
              Modifier
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(chipColor)
                  .clickable(enabled = button.available) {
                      if (button.kind == QuickButtonKind.PRESS) flashing = true
                      onTap(button)
                  },
              contentAlignment = Alignment.Center,
          ) {
              Icon(
                  quickButtonIcon(button.icon), contentDescription = button.label,
                  tint = Color.White, modifier = Modifier.size(22.dp),
              )
          }
          Text(
              button.label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
              maxLines = 1, overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
          )
      }
  }

  // Lights-panel palette: on / PRESS-flash blue, off / PRESS-idle dark.
  private val QuickChipOn = Color(0xFF3A6EA5)
  private val QuickChipOff = Color(0xFF232733)

  private fun quickButtonIcon(icon: QuickButtonIcon): ImageVector = when (icon) {
      QuickButtonIcon.LIGHT -> Icons.Outlined.Lightbulb
      QuickButtonIcon.SWITCH -> Icons.Outlined.Power
      QuickButtonIcon.RUN -> Icons.Outlined.PlayArrow
      QuickButtonIcon.SCENE -> Icons.Outlined.Palette
  }
  ```

- [ ] **Step 5: Gate (compile + existing suite).**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  Expected: **BUILD SUCCESSFUL** (HomeView compiles with the new composables; no test regressions).

- [ ] **Step 6: Commit.**

  ```bash
  git add app/src/main/java/com/rar/echodash/ui/HomeView.kt
  git commit -m "feat(home): quick-buttons card view

  QuickButtonsCardView in the right column below the EV/solar cards: a row
  of 44dp chips (22dp icon + 11sp label), TOGGLE chips follow isOn, PRESS
  chips flash ~250ms, unavailable dims + disables. HomeView gains
  quickButtons/onQuickButton params (default empty/no-op).

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
  ```

---

## Task 4 — Dispatch + wiring: `DashboardShell` model compute + `App.kt` callback

Computes the model in `DashboardShell`'s HOME branch (mirroring `solarGraph`), threads a new `onQuickButton` parameter down to `HomeView`, and binds it in `App.kt` to `EntityHub.callService` via `quickButtonService()`. Compile-gated (no new tests — the dispatch mapping itself is already tested in Task 2).

**Files**
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt`

**Interfaces**
- Consumes: `quickButtons(...)`, `quickButtonService(...)`, `QuickButton` (Task 2); `HomeView(quickButtons=, onQuickButton=)` (Task 3); `EntityHub.callService(domain, service, serviceData = JsonObject(emptyMap()), entityId = null)`.
- Produces: `DashboardShell` gains `onQuickButton: (QuickButton) -> Unit = {}`.

### Steps

- [ ] **Step 1: Add DashboardShell imports.** In `DashboardShell.kt`, with the `com.rar.echodash.ui.model.*` imports (lines ~36-51), add:

  ```kotlin
  import com.rar.echodash.ui.model.QuickButton
  import com.rar.echodash.ui.model.quickButtons
  ```

- [ ] **Step 2: Add the `onQuickButton` parameter to `DashboardShell`.** Old block (line ~82):

  ```kotlin
      onToggle: (String) -> Unit,
      onSetTemperature: (String, Double) -> Unit,
  ```

  New:

  ```kotlin
      onToggle: (String) -> Unit,
      onQuickButton: (QuickButton) -> Unit = {},
      onSetTemperature: (String, Double) -> Unit,
  ```

- [ ] **Step 3: Compute the model in the HOME branch.** Old block (lines ~215-217):

  ```kotlin
                      val solarGraph = remember(entities, config.entities.solar) {
                          solarFlowGraph(config.entities.solar, entities)
                      }
  ```

  New:

  ```kotlin
                      val solarGraph = remember(entities, config.entities.solar) {
                          solarFlowGraph(config.entities.solar, entities)
                      }
                      val quickBtns = remember(entities, config.entities.quickButtons) {
                          quickButtons(config.entities.quickButtons, entities)
                      }
  ```

- [ ] **Step 4: Pass them into `HomeView`.** Old block (lines ~225-227):

  ```kotlin
                          solar = solar,
                          solarGraph = solarGraph,
                          notifications = notifications,
  ```

  New:

  ```kotlin
                          solar = solar,
                          solarGraph = solarGraph,
                          quickButtons = quickBtns,
                          onQuickButton = onQuickButton,
                          notifications = notifications,
  ```

- [ ] **Step 5: Add the App.kt import.** In `App.kt`, after `import com.rar.echodash.ui.model.pushedNotificationItems` (line ~53):

  ```kotlin
  import com.rar.echodash.ui.model.quickButtonService
  ```

- [ ] **Step 6: Bind the callback in App.kt.** In the `DashboardShell(...)` call, add `onQuickButton` right after `onToggle`. Old block (line ~809):

  ```kotlin
                          onToggle = { id -> deps.entityHub.callService("homeassistant", "toggle", entityId = id) },
                          onSetTemperature = { id, temp ->
  ```

  New:

  ```kotlin
                          onToggle = { id -> deps.entityHub.callService("homeassistant", "toggle", entityId = id) },
                          onQuickButton = { qb ->
                              val (domain, service) = quickButtonService(qb.entityId)
                              deps.entityHub.callService(domain, service, entityId = qb.entityId)
                          },
                          onSetTemperature = { id, temp ->
  ```

- [ ] **Step 7: Gate (compile + existing suite).**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  Expected: **BUILD SUCCESSFUL**.

- [ ] **Step 8: Commit.**

  ```bash
  git add app/src/main/java/com/rar/echodash/ui/DashboardShell.kt app/src/main/java/com/rar/echodash/App.kt
  git commit -m "feat(home): wire quick buttons to EntityHub

  DashboardShell computes quickButtons() in the HOME branch and threads an
  onQuickButton callback to HomeView; App.kt binds it to
  EntityHub.callService via quickButtonService() (button.press /
  script|scene.turn_on / homeassistant.toggle).

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
  ```

---

## Task 5 — Web config: "Quick buttons" card in `app.js`

Adds a "Quick buttons" card to the config page's Entities tab, after the solar card, with four fixed slots ("Button 1".."Button 4"), each a name text input + an `entityPicker` over the six control domains. Mirrors the solar Array A–D slot markup exactly. No automated JS test — the gate is `assembleDebug` (packages the asset) plus `node --check` (syntax) and a manual load-and-save round-trip.

**Files**
- Modify: `app/src/main/assets/config/app.js`

**Interfaces**
- Consumes: existing `subhead(name, text)`, `el(tag, cls, text)`, `labeledRow(label, control)`, `entityPicker(domains, value, onChange)` helpers; `config.entities` (aliased `e` in `renderEntities()`). The `.group` / `.group-head` / `.panel-name` CSS classes already exist (`style.css`).
- Produces: renders/edits `e.quickButtons` (array of `{name, entity}`); slots persist via the existing save path and are cleaned by `DashConfig.clamped()` (Task 1) — empty slots dropped, capped at 4.

### Steps

- [ ] **Step 1: Insert the Quick buttons card.** In `renderEntities()` (`app.js`), insert this block immediately after the solar array section's closing muted `<div>` (after line ~468, `"Per-array PV power ... Empty slots are dropped on save."`), and before the `// light groups` comment (line ~470):

  ```javascript
    // quick buttons (home card; up to four toggle/action entities). Fixed four slots like the arrays.
    host.appendChild(subhead("lights", "Quick buttons"));
    if (!Array.isArray(e.quickButtons)) e.quickButtons = [];
    const quickButtons = e.quickButtons;
    while (quickButtons.length < 4) quickButtons.push({});
    quickButtons.slice(0, 4).forEach((slot, i) => {
      const box = el("div", "group");
      const head = el("div", "group-head");
      head.appendChild(el("span", "panel-name", "Button " + (i + 1)));
      box.appendChild(head);
      const name = el("input");
      name.value = slot.name || "";
      name.setAttribute("aria-label", "Button name");
      name.addEventListener("change", () => slot.name = name.value.trim());
      box.appendChild(labeledRow("Name", name));
      box.appendChild(labeledRow("Entity",
        entityPicker(["switch", "light", "input_boolean", "button", "script", "scene"],
          slot.entity, v => slot.entity = v)));
      host.appendChild(box);
    });
    host.appendChild(el("div", "muted",
      "Up to four tappable buttons on the home screen, below the EV and solar cards. Switches, lights, " +
      "and input booleans toggle and show live on/off; buttons, scripts, and scenes fire on tap. " +
      "Blank name uses the entity's name. Empty slots are dropped on save."));
  ```

- [ ] **Step 2: Syntax-check the asset.**

  Run: `node --check app/src/main/assets/config/app.js`
  Expected: **no output, exit 0** (valid syntax — this validates parsing only, not browser runtime).

- [ ] **Step 3: Gate (packages the asset).**

  Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  Expected: **BUILD SUCCESSFUL**.

- [ ] **Step 4: Manual verification note (no device automation in this task).**

  When the built APK is next flashed: open the device config web page → Entities tab, confirm a "Quick buttons" section with four "Button 1".."Button 4" slots (name input + entity picker limited to switch/light/input_boolean/button/script/scene) renders after the Solar arrays. Set one toggle entity and one script/scene, Save, reload the page, and confirm the two configured slots persist and the empty slots are gone (clamp on save). This is a manual check; there is no automated gate for it.

- [ ] **Step 5: Commit.**

  ```bash
  git add app/src/main/assets/config/app.js
  git commit -m "feat(config-web): Quick buttons card (4 fixed slots)

  Entities tab gains a Quick buttons card after Solar: four Button 1..4
  slots, each a name input + entityPicker over switch/light/input_boolean/
  button/script/scene. Mirrors the solar Array A-D slot markup; empty slots
  dropped on save by clamped().

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
  ```

---

## Self-Review

**1. Spec coverage** — every spec requirement maps to a task:

| Spec section | Requirement | Task |
|---|---|---|
| Config | `QuickButtonConfig(name="", entity=null)` `@Serializable` | 1 (Step 3) |
| Config | `Entities.quickButtons: List<QuickButtonConfig> = emptyList()` | 1 (Step 4) |
| Config | Watch list adds `quickButtons.forEach { it.entity?.let(::add) }` | 1 (Step 5) |
| Config | `clamped()`: trim name/entity, blank entity→null, drop entity-less, `take(4)` | 1 (Step 6) |
| Model | `QuickButtonKind{TOGGLE,PRESS}`, `QuickButtonIcon{LIGHT,SWITCH,RUN,SCENE}`, `QuickButton` | 2 (Step 3) |
| Model | `fun quickButtons(cfg, entities)` | 2 (Step 3) |
| Model | Derivation rules: kind / icon / label chain / isOn / available | 2 (Steps 1 tests + 3) |
| Model | Empty cfg → empty; slots always produce (unavailable dimmed) | 2 (`emptyConfigYieldsEmptyList`, `availability...`) |
| UI | Card below EV/solar; `AnimatedVisibility \|= quickButtons.isNotEmpty()` | 3 (Step 3) |
| UI | Chrome: `width(cardWidth)`, black 0.35, `RoundedCornerShape(20.dp)`, 16×10 | 3 (Step 4) |
| UI | Row, equal `weight(1f)`, 44dp chip, 22dp icon, 11sp label ellipsis white 0.85, centered, 6dp gap | 3 (Step 4) |
| UI | Chip on `0xFF3A6EA5`, off/PRESS-idle `0xFF232733`, icon white, unavailable `alpha(0.4)` + not tappable | 3 (Step 4) |
| UI | PRESS pulse ~250ms `animateColorAsState`; TOGGLE follows isOn, no optimistic flip | 3 (Step 4) |
| UI | Tap → hoisted `onTap(QuickButton)`; night unchanged | 3 (Step 4 + note) |
| Dispatch | PRESS button→button.press, script→script.turn_on, scene→scene.turn_on, TOGGLE→homeassistant.toggle | 2 (`quickButtonService` + test) + 4 (Step 6) |
| Web config | "Quick buttons" card after solar, 4 fixed slots, name + `entityPicker(6 domains)`, array-row markup | 5 (Step 1) |
| Tests | `QuickButtonsModelTest` full coverage | 2 (Step 1) |
| Tests | `DashConfigTest`: clamp/trim/cap, watch list, round-trip | 1 (Step 1) |

**2. Placeholder scan** — no TBD/TODO/"add error handling"/"similar to Task N"/"write tests for the above". Every code step shows complete code; every command shows expected output.

**3. Type consistency** — verified across tasks: `QuickButtonConfig(name, entity)`, `QuickButton(entityId, label, icon, kind, isOn, available)`, `QuickButtonKind{TOGGLE,PRESS}`, `QuickButtonIcon{LIGHT,SWITCH,RUN,SCENE}`, `quickButtons(cfg, entities): List<QuickButton>`, `quickButtonService(entityId): Pair<String,String>`, `HomeView(quickButtons=, onQuickButton=)`, `DashboardShell.onQuickButton: (QuickButton) -> Unit`, and `EntityHub.callService(domain, service, entityId=)` are used identically everywhere they appear.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-17-quick-buttons-card.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
