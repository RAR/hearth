# Timer Takeover Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A full-screen "kitchen timer" takeover that appears whenever ≥1 Assist timer is running, shows every running timer with a big live countdown and its voice name, is dismissable back to the dashboard, re-appears only for NEW timers, and lets you tap a name to rename it locally.

**Architecture:** A plain-JVM model (`TimerTakeoverModel`) owns all visibility / dismiss / rename / label logic and maps live `TimerChip`s to render rows; a thin Compose view (`TimersTakeoverView`) draws them; `App.kt` wires the model into the existing overlay stack next to `TimerChips` / `TimerFinishedOverlay`. The 500 ms timer tick already re-emits `TimersUiState`; the takeover recomputes on every emission. No new timer plumbing — `SatelliteSession` → `AppDeps.timersUi` StateFlow stays exactly as-is.

**Tech Stack:** Kotlin, Jetpack Compose (Material3, compose-bom 2024.12.01), plain-JVM JUnit4 (no Robolectric, no Compose UI tests), Gradle.

## Global Constraints

- Work directly on `master`. Commit per task. **Every** commit message ends with this trailer line, verbatim:
  `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- **Gate before EVERY commit** (must print `BUILD SUCCESSFUL`, all tests green):
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
- **NO new dependencies.** Plain-JVM JUnit4 only — no Robolectric, no Compose UI tests. Compose is verified live on-device.
- Pure logic (visibility / dismiss / rename / label / format rules) lives in the model file so it is plain-JVM testable; Compose stays thin.
- Match existing code style. No narrating comments. KDoc on public types/functions like the `ui/model` siblings; nothing else.
- **The spec's model API is normative — keep these names exactly:** `data class TakeoverTimer(id, label, remainingSec, active)`; `class TimerTakeoverModel` with `fun update(timers): List<TakeoverTimer>`, `fun dismiss()`, `fun rename(id, label)`, `val visible`.
- The spec's "TimerInfo" **is** the existing `com.rar.echodash.voice.TimerChip(id, name, remainingSec, active)`. Do not invent a new input type.

---

## Resolved ambiguities (read before starting)

These are design calls made while writing this plan. Do not re-litigate them mid-implementation; if one is wrong the lead will say so in review.

1. **Fallback label source.** The spec says the unnamed-timer fallback is the "formatted original duration (`10 min timer`)", but `TimerChip` carries only `remainingSec` (already resolved against the clock), never the original duration. Resolution: derive the fallback from `remainingSec` via `defaultTimerLabel(...)`. For a just-created unnamed timer `remainingSec` ≈ the spoken duration, so it reads naturally; as it counts down an unnamed timer's fallback label tracks the remaining time (named timers are unaffected). This is the only honest option given the available data.
2. **tabular-nums.** Nunito is a single variable font (see `ui/theme/Type.kt`); its `tnum` OpenType figures are not guaranteed. The instruction permits falling back to monospace. Resolution: the giant / grid countdown digits use `fontFamily = FontFamily.Monospace` (guaranteed non-wobbling digits, zero font-feature uncertainty). One mechanism, chosen concretely.
3. **Recomposition safety.** The model stays plain-JVM (mutable `HashMap`/`HashSet`, no Compose imports). `App.kt` holds `remember { TimerTakeoverModel() }` plus an `Int` revision `var … by remember { mutableStateOf(0) }` that is incremented on `dismiss()`/`rename()` to force a recompute; the render list is computed in `remember(timersState, rev) { model.update(...) }`, and `model.visible` is read right after. Recomposition is driven by the `timersUi` StateFlow (new emissions) and the revision state (dismiss/rename). Chose this "revision counter" over a StateFlow-holder: lighter, reuses the already-imported `mutableStateOf`, no new import.
4. **`formatTimer` split.** `formatTimer` is created and unit-tested in Task 1 (so the spec's formatter test group lives with the model tests). Task 2 performs the *extraction* the spec asks for — repointing `VoiceOverlay.kt` at the shared `ui/model` copy and deleting its private duplicate. Between Task 1 and Task 2 two identical `formatTimer`s coexist harmlessly (one `private` in `ui`, one public in `ui.model`); Task 2 removes the duplicate.
5. **Screen-wake poke.** Per the spec ("pokes the screen-wake/idle timer the same way" as the voice overlay), the takeover uses a one-shot `LaunchedEffect(timerTakeoverVisible)` mirroring the existing `LaunchedEffect(voiceOverlayState.phase)` poke — NOT a continuous re-arm loop. Note for the lead: a one-shot poke will not hold the screen past `screen_timeout` for a long-running countdown; the existing `timersState.chips.any { it.active }` night-suppression still keeps brightness up while a timer runs, and the finish alert already has its own re-arm loop. If a continuous hold is wanted for the running-timer takeover, that is a deliberate expansion beyond this spec.

---

## File Structure

- **Create** `app/src/main/java/com/rar/echodash/ui/model/TimerTakeover.kt` — pure model: `TakeoverTimer`, `TimerTakeoverModel`, `defaultTimerLabel`, `formatTimer`. Plain Kotlin, no Android imports (mirrors `AdaptiveGeometry.kt` / `QuickButtonsModel.kt`).
- **Create** `app/src/test/java/com/rar/echodash/ui/model/TimerTakeoverModelTest.kt` — plain-JVM JUnit4 tests (all 6 spec groups).
- **Create** `app/src/main/java/com/rar/echodash/ui/TimersTakeoverView.kt` — the Compose takeover view (single/grid layout, ✕, paused dim, rename dialog).
- **Modify** `app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt` — delete the private `formatTimer`, import the shared one.
- **Modify** `app/src/main/java/com/rar/echodash/App.kt` — instantiate the model, compute visibility, hide `TimerChips` while the takeover shows, join night-mode suppression + screen-wake, draw the view in the overlay stack.

---

## Task 1: Pure model + tests (`TimerTakeover.kt`)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/TimerTakeover.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/TimerTakeoverModelTest.kt`

**Interfaces:**
- Consumes: `com.rar.echodash.voice.TimerChip(val id: String, val name: String, val remainingSec: Long, val active: Boolean)` (existing).
- Produces (later tasks rely on these EXACT signatures):
  - `data class TakeoverTimer(val id: String, val label: String, val remainingSec: Long, val active: Boolean)`
  - `class TimerTakeoverModel` with `fun update(timers: List<TimerChip>): List<TakeoverTimer>`, `fun dismiss()`, `fun rename(id: String, label: String)`, `val visible: Boolean`
  - `fun defaultTimerLabel(remainingSec: Long): String`
  - `fun formatTimer(sec: Long): String`

> Note: leave `VoiceOverlay.kt`'s existing `private fun formatTimer` untouched in this task — Task 2 consolidates it. The new public `formatTimer` lives in package `com.rar.echodash.ui.model`; no name clash.

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/com/rar/echodash/ui/model/TimerTakeoverModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.voice.TimerChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerTakeoverModelTest {
    private fun chip(id: String, name: String = "", remainingSec: Long = 60, active: Boolean = true) =
        TimerChip(id, name, remainingSec, active)

    // 1. empty -> hidden; adding one -> visible with the voice name as label.
    @Test
    fun emptyHiddenThenVisibleWithVoiceName() {
        val m = TimerTakeoverModel()
        assertEquals(emptyList<TakeoverTimer>(), m.update(emptyList()))
        assertFalse(m.visible)
        val out = m.update(listOf(chip("a", name = "Pasta", remainingSec = 120)))
        assertTrue(m.visible)
        assertEquals(listOf(TakeoverTimer("a", "Pasta", 120, true)), out)
    }

    // 2. blank name -> duration fallback; rename overrides; rename pruned when the id disappears.
    @Test
    fun fallbackLabelRenameOverrideAndPrune() {
        val m = TimerTakeoverModel()
        assertEquals("10 min timer", m.update(listOf(chip("a", remainingSec = 600))).single().label)
        m.rename("a", "Eggs")
        assertEquals("Eggs", m.update(listOf(chip("a", remainingSec = 600))).single().label)
        m.update(listOf(chip("b", remainingSec = 600)))  // "a" gone -> its rename is pruned
        assertEquals("10 min timer", m.update(listOf(chip("a", remainingSec = 600))).single().label)
    }

    // 3. dismiss hides; same timers stay hidden; a NEW id re-shows (old ids stay marked).
    @Test
    fun dismissHidesUntilNewId() {
        val m = TimerTakeoverModel()
        m.update(listOf(chip("a")))
        assertTrue(m.visible)
        m.dismiss()
        assertFalse(m.visible)
        m.update(listOf(chip("a")))                 // same timer still hidden
        assertFalse(m.visible)
        m.update(listOf(chip("a"), chip("b")))      // new id b re-shows
        assertTrue(m.visible)
    }

    // 4. all timers gone -> dismiss + rename state reset; the next timer shows fresh.
    @Test
    fun clearingResetsTransientState() {
        val m = TimerTakeoverModel()
        m.update(listOf(chip("a", name = "Pasta")))
        m.rename("a", "Eggs")
        m.dismiss()
        assertFalse(m.visible)
        m.update(emptyList())                        // all gone -> reset
        val out = m.update(listOf(chip("a", name = "Pasta")))
        assertTrue(m.visible)                        // not still dismissed
        assertEquals("Pasta", out.single().label)    // rename gone
    }

    // 5. paused timer keeps its remaining seconds and active=false passes through.
    @Test
    fun pausedPassesThrough() {
        val m = TimerTakeoverModel()
        val out = m.update(listOf(chip("a", name = "Tea", remainingSec = 45, active = false)))
        assertEquals(TakeoverTimer("a", "Tea", 45, false), out.single())
    }

    // 6a. countdown formatter matches TimerChips semantics.
    @Test
    fun formatTimerMatchesChipSemantics() {
        assertEquals("0:45", formatTimer(45))
        assertEquals("10:05", formatTimer(605))
        assertEquals("1:01:01", formatTimer(3661))
    }

    // 6b. duration fallback label buckets.
    @Test
    fun defaultTimerLabelBuckets() {
        assertEquals("10 min timer", defaultTimerLabel(600))
        assertEquals("45 sec timer", defaultTimerLabel(45))
        assertEquals("1 hr 1 min timer", defaultTimerLabel(3661))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.TimerTakeoverModelTest"`
Expected: FAIL — compilation error, `unresolved reference: TimerTakeoverModel` (and `TakeoverTimer`, `defaultTimerLabel`, `formatTimer`).

- [ ] **Step 3: Write the model**

Create `app/src/main/java/com/rar/echodash/ui/model/TimerTakeover.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.voice.TimerChip

/**
 * One timer ready to render in the takeover panel. [label] is already resolved (local rename >
 * voice name > duration fallback); [remainingSec] and [active] pass straight through from the chip.
 */
data class TakeoverTimer(
    val id: String,
    val label: String,
    val remainingSec: Long,
    val active: Boolean,
)

/**
 * Kitchen timer takeover state. Maps live [TimerChip]s to display rows and holds the transient local
 * state — per-id renames and the ids the user has dismissed. Plain Kotlin (no Android) so it is
 * JUnit-testable; the composable owns recomposition. Timers are ephemeral: once every timer is gone
 * the renames and dismissals reset for a fresh session.
 */
class TimerTakeoverModel {
    private val renames = HashMap<String, String>()
    private val dismissedIds = HashSet<String>()
    private var currentIds: List<String> = emptyList()

    /**
     * Map the live chips to display rows. Prunes rename/dismiss entries for ids no longer present;
     * when no timers remain, resets the transient state. Call on every timers emission.
     */
    fun update(timers: List<TimerChip>): List<TakeoverTimer> {
        if (timers.isEmpty()) {
            renames.clear()
            dismissedIds.clear()
            currentIds = emptyList()
            return emptyList()
        }
        val ids = timers.map { it.id }
        val idSet = ids.toSet()
        renames.keys.retainAll(idSet)
        dismissedIds.retainAll(idSet)
        currentIds = ids
        return timers.map { chip ->
            TakeoverTimer(chip.id, labelFor(chip), chip.remainingSec, chip.active)
        }
    }

    /** Record every currently-known id as dismissed; the takeover hides until a NEW id arrives. */
    fun dismiss() {
        dismissedIds.addAll(currentIds)
    }

    /** Set a local display label for [id]. A blank label clears the rename (back to the default). */
    fun rename(id: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) renames.remove(id) else renames[id] = trimmed
    }

    /** Visible while at least one known timer has not been dismissed (a new id re-shows it). */
    val visible: Boolean
        get() = currentIds.any { it !in dismissedIds }

    private fun labelFor(chip: TimerChip): String =
        renames[chip.id] ?: chip.name.trim().ifBlank { defaultTimerLabel(chip.remainingSec) }
}

/** Human fallback name for an unnamed timer, from its remaining time (e.g. 600 -> "10 min timer"). */
fun defaultTimerLabel(remainingSec: Long): String {
    val s = remainingSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 && m > 0 -> "$h hr $m min timer"
        h > 0 -> "$h hr timer"
        m > 0 -> "$m min timer"
        else -> "$sec sec timer"
    }
}

/** m:ss (or h:mm:ss past an hour) countdown — the shared TimerChips / takeover formatter. */
fun formatTimer(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.TimerTakeoverModelTest"`
Expected: PASS — 7 tests, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all unit tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/model/TimerTakeover.kt \
        app/src/test/java/com/rar/echodash/ui/model/TimerTakeoverModelTest.kt
git commit -m "$(cat <<'EOF'
feat(timers): TimerTakeoverModel + shared countdown formatter

Pure-JVM model for the timer takeover: maps TimerChips to render rows,
resolves labels (rename > voice name > duration fallback), tracks
dismiss/re-show and per-id renames. Adds shared defaultTimerLabel and
formatTimer (formatTimer to be consolidated out of VoiceOverlay next).

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 2: Takeover view + `formatTimer` extraction (`TimersTakeoverView.kt`, `VoiceOverlay.kt`)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/TimersTakeoverView.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt` (delete private `formatTimer`, import shared one)

**Interfaces:**
- Consumes (from Task 1): `com.rar.echodash.ui.model.TakeoverTimer`, `com.rar.echodash.ui.model.formatTimer`.
- Produces (App.kt relies on this EXACT signature):
  - `@Composable fun TimersTakeoverView(timers: List<TakeoverTimer>, onDismiss: () -> Unit, onRename: (id: String, label: String) -> Unit, modifier: Modifier = Modifier)`

No unit tests (Compose is verified live; Task 3 wires it in). This task's proof is a clean gate compile; the on-device behaviour is verified in Task 3.

- [ ] **Step 1: Create the takeover view**

Create `app/src/main/java/com/rar/echodash/ui/TimersTakeoverView.kt`:

```kotlin
package com.rar.echodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.TakeoverTimer
import com.rar.echodash.ui.model.formatTimer

private val TIMER_NAME_PRESETS = listOf("Pasta", "Eggs", "Tea", "Oven", "Laundry")

/**
 * Full-screen kitchen timer takeover: every running timer shown big with a live countdown. One
 * timer fills the screen; 2–4 tile into a 2-column grid; 5+ scroll. Dark surface in the NowPlaying
 * takeover family. ✕ dismisses (back to the dashboard); tapping a name opens the rename dialog.
 */
@Composable
fun TimersTakeoverView(
    timers: List<TakeoverTimer>,
    onDismiss: () -> Unit,
    onRename: (id: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Box(modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        if (timers.size == 1) {
            SingleTimer(
                timer = timers[0],
                onRename = { renamingId = timers[0].id; renameText = "" },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 72.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                timers.chunked(2).forEach { rowTimers ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        rowTimers.forEach { t ->
                            TimerCard(
                                timer = t,
                                onRename = { renamingId = t.id; renameText = "" },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowTimers.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Box(
            Modifier.align(Alignment.TopEnd).padding(16.dp).size(48.dp)
                .clip(CircleShape).background(Color(0x33FFFFFF)).clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Dismiss timers",
                tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }

    renamingId?.let { id ->
        RenameDialog(
            text = renameText,
            onText = { renameText = it },
            onPreset = { renameText = it },
            onSave = { onRename(id, renameText); renamingId = null },
            onCancel = { renamingId = null },
        )
    }
}

@Composable
private fun SingleTimer(timer: TakeoverTimer, onRename: () -> Unit, modifier: Modifier = Modifier) {
    val dim = if (timer.active) 1f else 0.5f
    Column(
        modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            timer.label,
            color = Color.White.copy(alpha = dim),
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onRename() },
        )
        Text(
            formatTimer(timer.remainingSec),
            color = Color.White.copy(alpha = dim),
            fontSize = 120.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (!timer.active) PausedTag()
    }
}

@Composable
private fun TimerCard(timer: TakeoverTimer, onRename: () -> Unit, modifier: Modifier = Modifier) {
    val dim = if (timer.active) 1f else 0.5f
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF11151F), modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                timer.label,
                color = Color.White.copy(alpha = dim),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onRename() },
            )
            Text(
                formatTimer(timer.remainingSec),
                color = Color.White.copy(alpha = dim),
                fontSize = 64.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (!timer.active) PausedTag()
        }
    }
}

@Composable
private fun PausedTag() {
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0x33FFFFFF)) {
        Text("paused", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun RenameDialog(
    text: String,
    onText: (String) -> Unit,
    onPreset: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Name this timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TIMER_NAME_PRESETS.forEach { preset ->
                        SuggestionChip(onClick = { onPreset(preset) }, label = { Text(preset) })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
```

- [ ] **Step 2: Consolidate `formatTimer` — edit `VoiceOverlay.kt`**

In `app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt`, add the shared import. Find:

```kotlin
import com.rar.echodash.voice.TimerAlert
```

and insert the new import directly above it:

```kotlin
import com.rar.echodash.ui.model.formatTimer
import com.rar.echodash.voice.TimerAlert
```

Then delete the now-duplicate private function. Remove this entire block:

```kotlin
private fun formatTimer(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}
```

`TimerChips` in the same file keeps calling `formatTimer(chip.remainingSec)` — it now resolves to the imported `com.rar.echodash.ui.model.formatTimer`, which is byte-identical logic.

- [ ] **Step 3: Run the gate (compile proves the view + extraction)**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all unit tests green (the Task-1 model tests still pass; `TimerChips` now uses the shared formatter).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/TimersTakeoverView.kt \
        app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt
git commit -m "$(cat <<'EOF'
feat(timers): timer takeover view + share formatTimer

Full-screen takeover: giant single countdown or 2-column grid (scrolls
past 4), paused timers dimmed with a tag, dismiss X, tap-to-rename dialog
with preset chips. Monospace digits so the countdown does not wobble.
VoiceOverlay's private formatTimer is dropped for the shared ui/model one.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 3: App.kt wiring

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/App.kt`

**Interfaces:**
- Consumes: `com.rar.echodash.ui.TimersTakeoverView(...)` (Task 2), `com.rar.echodash.ui.model.TimerTakeoverModel` (Task 1). Existing: `deps.timersUi: MutableStateFlow<TimersUiState>`, `timersState` (already collected at the anchor), `idleTimer`, `deps.kiosk.onUserInteraction()`, `deps.nightMode.onOverride(...)`.
- Produces: no new public API; wires the takeover into the running app.

No unit tests. Verified by the gate compile plus on-device live steps (Step 6).

- [ ] **Step 1: Add imports**

In `app/src/main/java/com/rar/echodash/App.kt`, add the two imports alongside the existing `ui` / `ui.model` import groups. Find:

```kotlin
import com.rar.echodash.ui.TimerFinishedOverlay
import com.rar.echodash.ui.VoiceOverlay
```

and change to:

```kotlin
import com.rar.echodash.ui.TimerFinishedOverlay
import com.rar.echodash.ui.TimersTakeoverView
import com.rar.echodash.ui.VoiceOverlay
```

Then find:

```kotlin
import com.rar.echodash.ui.model.CalendarEvent
```

and change to:

```kotlin
import com.rar.echodash.ui.model.CalendarEvent
import com.rar.echodash.ui.model.TimerTakeoverModel
```

(Import ordering is not build-critical — the gate is `testDebugUnitTest` + `assembleDebug`, no lint task. These placements just match the file's grouping.)

- [ ] **Step 2: Instantiate the model + compute visibility**

Find the pair of collectors (currently ~lines 925–926):

```kotlin
                    val voiceOverlayState by deps.voiceOverlay.collectAsStateWithLifecycle()
                    val timersState by deps.timersUi.collectAsStateWithLifecycle()
```

and insert the model wiring directly after them:

```kotlin
                    val voiceOverlayState by deps.voiceOverlay.collectAsStateWithLifecycle()
                    val timersState by deps.timersUi.collectAsStateWithLifecycle()
                    // Kitchen timer takeover: the model maps live chips to render rows and owns
                    // dismiss/re-show + rename. Recompute on every timers emission (and on rev bumps
                    // from dismiss/rename); model.visible is read right after update().
                    val timerTakeover = remember { TimerTakeoverModel() }
                    var timerTakeoverRev by remember { mutableStateOf(0) }
                    val takeoverTimers = remember(timersState, timerTakeoverRev) {
                        timerTakeover.update(timersState.chips)
                    }
                    val timerTakeoverVisible = timerTakeover.visible
```

- [ ] **Step 3: Join night-mode suppression**

Find the existing override effect (currently ~lines 929–938):

```kotlin
                    LaunchedEffect(takeoverVisible, doorbellPopup, voiceOverlayState, timersState) {
                        deps.nightMode.onOverride(
                            takeoverVisible ||
                                doorbellPopup != null ||
                                voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN ||
                                timersState.chips.any { it.active } ||
                                timersState.alert != null,
                            SystemClock.elapsedRealtime(),
                        )
                    }
```

and change it to add `timerTakeoverVisible` to both the keys and the OR condition:

```kotlin
                    LaunchedEffect(takeoverVisible, doorbellPopup, voiceOverlayState, timersState, timerTakeoverVisible) {
                        deps.nightMode.onOverride(
                            takeoverVisible ||
                                doorbellPopup != null ||
                                voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN ||
                                timersState.chips.any { it.active } ||
                                timersState.alert != null ||
                                timerTakeoverVisible,
                            SystemClock.elapsedRealtime(),
                        )
                    }
```

- [ ] **Step 4: Screen-wake poke (mirror the voice-overlay one-shot)**

Find the existing voice-overlay wake effect (currently ~lines 939–944):

```kotlin
                    LaunchedEffect(voiceOverlayState.phase) {
                        if (voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN) {
                            deps.kiosk.onUserInteraction()   // wakes screen + counts as activity
                            idleTimer.onInteraction()
                        }
                    }
```

and insert a sibling effect directly after it:

```kotlin
                    LaunchedEffect(voiceOverlayState.phase) {
                        if (voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN) {
                            deps.kiosk.onUserInteraction()   // wakes screen + counts as activity
                            idleTimer.onInteraction()
                        }
                    }
                    LaunchedEffect(timerTakeoverVisible) {
                        if (timerTakeoverVisible) {
                            deps.kiosk.onUserInteraction()   // wake the screen when a timer takes over
                            idleTimer.onInteraction()
                        }
                    }
```

- [ ] **Step 5: Draw the takeover + hide chips beneath it**

Find the `TimerChips` call (currently line 963), which sits at the top of the overlay stack, right after `DashboardShell(...)` and before `WakeGlow`/`VoiceOverlay`/the finished-alert/doorbell:

```kotlin
                    TimerChips(timersState)
```

and replace it with the mutually-exclusive pair. The takeover occupies the same z-slot the chips had — beneath `WakeGlow`/`VoiceOverlay`/`TimerFinishedOverlay`/doorbell (all drawn after), so the voice pill, wake glow, finish alarm, and doorbell stay on top; the chips are hidden while the takeover shows:

```kotlin
                    if (timerTakeoverVisible) {
                        TimersTakeoverView(
                            timers = takeoverTimers,
                            onDismiss = { timerTakeover.dismiss(); timerTakeoverRev++ },
                            onRename = { id, label -> timerTakeover.rename(id, label); timerTakeoverRev++ },
                        )
                    } else {
                        TimerChips(timersState)
                    }
```

- [ ] **Step 6: Run the gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all unit tests green.

- [ ] **Step 7: Live verification (desk Echo Show 5, per the spec)**

Install the debug build and drive the real flow (do NOT claim success from the build alone):

```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:installDebug
```

Then verify each behaviour on-device:
- "okay nabu, set a pasta timer for 2 minutes" → full-screen takeover with **pasta** above a giant live countdown.
- "okay nabu, set an egg timer for 3 minutes" → both timers now tile into a 2-column grid, each counting down.
- Tap ✕ (top-right) → returns to the dashboard; the top-center countdown chips are visible again.
- "okay nabu, set a tea timer for 5 minutes" → a NEW timer re-takes over (the dismissed ones do not, on their own, re-show).
- Tap a timer's name → rename dialog; tap a preset chip or type a name, Save → the name updates in place; Cancel leaves it unchanged.
- Let a timer finish → the existing "Timer done" alarm overlay appears on top of the takeover; dismiss it; when every timer has finished/cleared, the takeover disappears (no timers remain).
- Pause a timer by voice ("okay nabu, pause the pasta timer") → that timer's card dims and shows a "paused" tag while still displaying its remaining time.

Optional: screencap-verify the grid on Show 8 by injecting timer events if convenient.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rar/echodash/App.kt
git commit -m "$(cat <<'EOF'
feat(timers): wire timer takeover into the overlay stack

Instantiate TimerTakeoverModel in the dashboard scope, recompute on each
timers emission, and show TimersTakeoverView while visible (chips hidden
beneath it; voice pill / doorbell / finish alarm stay on top). Takeover
joins the night-dim suppression set and pokes the screen-wake like voice.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Self-Review

**1. Spec coverage.** Every spec section maps to a task:
- *Existing plumbing unchanged* — no timer plumbing touched; `timersUi` StateFlow, `TimerChips`, `TimerFinishedOverlay` all preserved (Task 3 keeps `TimerFinishedOverlay` as-is and only gates `TimerChips`). ✓
- *New pure model `ui/model/TimerTakeover.kt`* — Task 1: `TakeoverTimer`, `TimerTakeoverModel.update/dismiss/rename/visible`, exact API names. Label precedence (rename > non-blank voice name > duration fallback) in `labelFor`; `visible` = any known id not dismissed; empty list resets renames+dismissals; stale entries pruned on `update`. ✓
- *UI `ui/TimersTakeoverView.kt`* — Task 2: full-screen dark surface, single (~28sp name / ~120sp countdown) vs 2-column grid (~64sp) vs 5+ scroll, paused dim + tag, ✕ → dismiss, tap-name → rename dialog with the five preset chips + `OutlinedTextField` + Save/Cancel, countdown via shared `formatTimer`. ✓
- *App.kt wiring* — Task 3: `remember { TimerTakeoverModel() }`, recompute per emission, `if (timerTakeoverVisible)`, chips conditional, night-mode suppression join + screen-wake poke, draw order after `DashboardShell` and beneath `WakeGlow`/`VoiceOverlay`/finish-alert/doorbell. ✓
- *Tests (6 groups)* — Task 1 test file covers all six (groups 1–5 behaviours + 6a formatter + 6b fallback buckets). ✓
- *Verification* — per-commit gate in Global Constraints; live steps in Task 3 Step 7. ✓

**2. Placeholder scan.** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete code; every command shows expected output. ✓

**3. Type consistency.** `TakeoverTimer(id, label, remainingSec, active)`, `TimerTakeoverModel.update/dismiss/rename/visible`, `defaultTimerLabel(Long): String`, `formatTimer(Long): String`, and `TimersTakeoverView(timers, onDismiss, onRename, modifier)` are used identically across Tasks 1→2→3. `onRename: (id: String, label: String) -> Unit` matches `model.rename(id, label)`. `timerTakeoverRev` / `timerTakeoverVisible` / `takeoverTimers` names are consistent through Task 3. The `formatTimer` referenced by `TimerChips` (Task 2) and `TimersTakeoverView` (Task 2) is the one defined in Task 1. ✓
