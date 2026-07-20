# Package Rename echodash → hearth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the app's internal code identity from `com.rar.echodash` to `com.rar.hearth` (Kotlin package, symbols, XML theme, Gradle project name) while keeping `applicationId = com.rar.echodash`, so both live devices update in place and retain HA auth/config/PIN.

**Architecture:** A behavior-preserving refactor in three independently-green steps: (1) move the package tree + change `namespace`; (2) rename the app-level symbols/theme/project name; (3) fold `vaca` → `device` with `Vaca*` → `Hearth*`. No new behavior, therefore **no new tests** — the Kotlin compiler and the existing ~1055-test suite are the safety net; anything missed fails to build or fails a test.

**Tech Stack:** Android / Kotlin / Jetpack Compose, Gradle (AGP), plain-JVM JUnit4 unit tests.

## Global Constraints

- `applicationId` **stays** `"com.rar.echodash"` — never change it. Only `namespace` moves to `com.rar.hearth`. (In-place device update depends on this.)
- **Landmine — `"Vacation"`:** the string `"Vacation"` in `app/src/test/java/com/rar/echodash/ui/model/CalendarModelTest.kt:45` is real test data. NEVER run a bare `s/Vaca/Hearth/` substring replace — it corrupts `Vacation`. All `Vaca*` renames are symbol-targeted (`VacaServer`, `VacaParser`, `VacaOutgoing`, `startVaca`) only.
- Do **not** rename the runtime `_hearth._tcp.` mDNS service type or the value of `HearthServer.DEFAULT_PORT` — those are on-the-wire identifiers; only Kotlin identifiers change.
- **Gate before every commit** (RC captured immediately, output redirected to a scratchpad log, never piped to `tail`/`head`):
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; echo "RC=$?"
  ```
  Require `RC=0`. No `node --check` (no web-config / `app.js` is touched).
- Every commit ends with the trailer: `Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL`
- No new dependencies. Test count must stay ~1055 (a rename adds/removes none — a changed count signals a lost or duplicated file).

---

### Task 1: Move package tree `echodash` → `hearth` + `namespace`

Move both source roots, rewrite every `package`/`import`/FQN reference to `com.rar.echodash` (this single sweep also fixes the 3 explicit `R`/`BuildConfig` refs), and point `namespace` at the new package. Symbols keep their `EchoDash*`/`Vaca*` names for now; `applicationId` is untouched.

**Files:**
- Move: `app/src/main/java/com/rar/echodash/` → `app/src/main/java/com/rar/hearth/` (whole tree)
- Move: `app/src/test/java/com/rar/echodash/` → `app/src/test/java/com/rar/hearth/` (whole tree)
- Modify: every `.kt` under those trees (`package`/`import`/FQN lines) — includes `ui/SplashScreen.kt` + `ui/theme/Type.kt` (`import com.rar.echodash.R`) and `sendspin/sendspin/SendSpin.kt` (`com.rar.echodash.BuildConfig.VERSION_NAME`)
- Modify: `app/build.gradle.kts:9` (`namespace` line only)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: all app code now lives in package `com.rar.hearth`; generated `R`/`BuildConfig` are `com.rar.hearth.R` / `com.rar.hearth.BuildConfig`. Symbols still named `EchoDashApplication`, `EchoTheme`, `EchoTypography`, `Theme.EchoDash`, `VacaServer`, `VacaParser`, `VacaOutgoing`, `startVaca`. `applicationId` still `com.rar.echodash`.

- [ ] **Step 1: Move the two package directories with git**

```bash
cd /home/rar/android_simpla_ha_dash
git mv app/src/main/java/com/rar/echodash app/src/main/java/com/rar/hearth
git mv app/src/test/java/com/rar/echodash app/src/test/java/com/rar/hearth
```

- [ ] **Step 2: Rewrite every `com.rar.echodash` reference in Kotlin sources to `com.rar.hearth`**

Only `.kt` files. There are NO string-literal `"com.rar.echodash"` occurrences in `.kt` (verified: the only quoted occurrences live in `build.gradle.kts`), so every `.kt` occurrence is a `package`, `import`, or fully-qualified reference — all of which must move. This one sweep also fixes the 3 explicit `R`/`BuildConfig` refs.

```bash
cd /home/rar/android_simpla_ha_dash
grep -rIl "com\.rar\.echodash" --include=*.kt app/src | xargs sed -i 's/com\.rar\.echodash/com.rar.hearth/g'
```

- [ ] **Step 3: Change `namespace` (NOT `applicationId`) in build.gradle.kts**

Edit `app/build.gradle.kts` line 9 only:

```kotlin
    namespace = "com.rar.hearth"
```

Leave line 12 exactly as `applicationId = "com.rar.echodash"`. (Do this as a targeted edit of the `namespace = "com.rar.echodash"` line — do not sed the whole file, which would also change `applicationId`.)

- [ ] **Step 4: Verify no stray references remain and applicationId is intact**

```bash
cd /home/rar/android_simpla_ha_dash
echo "src refs to old pkg (expect 0):"; grep -rI "com\.rar\.echodash" app/src | wc -l
echo "gradle occurrences (expect exactly the applicationId line):"; grep -n "com\.rar\.echodash" app/build.gradle.kts
echo "namespace line (expect com.rar.hearth):"; grep -n "namespace" app/build.gradle.kts
```
Expected: src refs = `0`; gradle shows only `applicationId = "com.rar.echodash"`; namespace = `com.rar.hearth`.

- [ ] **Step 5: Run the gate**

```bash
cd /home/rar/android_simpla_ha_dash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-t1.log 2>&1; echo "RC=$?"
grep -iE "BUILD (SUCCESS|FAIL)|tests? (completed|failed)|FAILED" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-t1.log | tail -20
```
Expected: `RC=0`, `BUILD SUCCESSFUL`, tests pass (~1055, unchanged). Manifest still references `.EchoDashApplication` / `@style/Theme.EchoDash`, which resolve fine against the new namespace and the still-named symbols — so it compiles green.

- [ ] **Step 6: Commit**

```bash
cd /home/rar/android_simpla_ha_dash
git add -A
git commit -m "$(cat <<'EOF'
refactor(rename): move package com.rar.echodash -> com.rar.hearth

Move main+test source trees and rewrite all package/import/FQN refs
(incl. the 3 explicit R/BuildConfig refs); point namespace at
com.rar.hearth. applicationId stays com.rar.echodash for in-place
device updates. Symbols/theme/project name renamed in later steps.

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL
EOF
)"
```

---

### Task 2: Rename app symbols, XML theme, and project name

Rename the four app-level identities to `Hearth*` and set the Gradle project name. Each is a unique symbol, so word/token-targeted replaces are safe.

**Files:**
- Move: `app/src/main/java/com/rar/hearth/EchoDashApplication.kt` → `HearthApplication.kt`
- Modify: the moved `HearthApplication.kt` (class name), and every `.kt` referencing `EchoDashApplication`
- Modify: `app/src/main/java/com/rar/hearth/App.kt` (root composable `fun EchoDashApp(deps: AppDeps)` at line ~636) + `app/src/main/java/com/rar/hearth/MainActivity.kt` (call `setContent { EchoDashApp(deps) }` at line ~67)
- Modify: `app/src/main/java/com/rar/hearth/ui/theme/Theme.kt` (`EchoTheme`, `EchoTypography` use), `.../ui/theme/Type.kt` (`EchoTypography` decl), and any callers of `EchoTheme`
- Modify: `app/src/main/AndroidManifest.xml` (`android:name=".EchoDashApplication"`, `android:theme="@style/Theme.EchoDash"`)
- Modify: `app/src/main/res/values/themes.xml` + `app/src/main/res/values-v31/themes.xml` (`<style name="Theme.EchoDash">`)
- Modify: `settings.gradle.kts` (`rootProject.name`)

**Interfaces:**
- Consumes: package `com.rar.hearth` from Task 1.
- Produces: `HearthApplication`, root composable `HearthApp(deps: AppDeps)`, `HearthTheme`, `HearthTypography`, `<style name="Theme.Hearth">`, `rootProject.name = "Hearth"`. Manifest points at `.HearthApplication` + `@style/Theme.Hearth`.

- [ ] **Step 1: Rename the Application class file + symbol**

```bash
cd /home/rar/android_simpla_ha_dash
git mv app/src/main/java/com/rar/hearth/EchoDashApplication.kt app/src/main/java/com/rar/hearth/HearthApplication.kt
grep -rIl "EchoDashApplication" --include=*.kt app/src | xargs sed -i 's/EchoDashApplication/HearthApplication/g'
sed -i 's/\.EchoDashApplication/.HearthApplication/' app/src/main/AndroidManifest.xml
```

- [ ] **Step 2: Rename the Compose theme + typography symbols**

Both are unique identifiers; replace across all `.kt`:

```bash
cd /home/rar/android_simpla_ha_dash
grep -rIl -E "EchoTheme|EchoTypography" --include=*.kt app/src | xargs sed -i -e 's/EchoTypography/HearthTypography/g' -e 's/EchoTheme/HearthTheme/g'
```

- [ ] **Step 3: Rename the XML theme (both qualifiers) + manifest reference**

```bash
cd /home/rar/android_simpla_ha_dash
sed -i 's/Theme\.EchoDash/Theme.Hearth/g' app/src/main/res/values/themes.xml app/src/main/res/values-v31/themes.xml
sed -i 's#@style/Theme\.EchoDash#@style/Theme.Hearth#' app/src/main/AndroidManifest.xml
```

- [ ] **Step 4: Rename the root composable `EchoDashApp` → `HearthApp` and set the Gradle project name**

`EchoDashApp` is the app's root `@Composable` (declared in `App.kt`, called in `MainActivity.kt`). It is safe to sweep now because Step 1 already removed every `EchoDashApplication` (of which `EchoDashApp` is a prefix), so nothing else with that prefix remains:

```bash
cd /home/rar/android_simpla_ha_dash
grep -rIl "EchoDashApp" --include=*.kt app/src | xargs sed -i 's/EchoDashApp/HearthApp/g'
```

Edit `settings.gradle.kts`: `rootProject.name = "Hearth"`.

Then confirm no app-name identifiers remain (the only surviving `Echo` should be the hardware reference in the `values-v31/themes.xml` comment — leave that):

```bash
cd /home/rar/android_simpla_ha_dash
grep -rIn "EchoDash\|EchoTheme\|EchoTypography" app/src settings.gradle.kts
```
Expected: no output (no `EchoDash*`/`EchoTheme`/`EchoTypography` identifiers remain).

- [ ] **Step 5: Run the gate**

```bash
cd /home/rar/android_simpla_ha_dash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-t2.log 2>&1; echo "RC=$?"
grep -iE "BUILD (SUCCESS|FAIL)|tests? (completed|failed)|FAILED" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-t2.log | tail -20
```
Expected: `RC=0`, `BUILD SUCCESSFUL`, tests pass (~1055).

- [ ] **Step 6: Commit**

```bash
cd /home/rar/android_simpla_ha_dash
git add -A
git commit -m "$(cat <<'EOF'
refactor(rename): EchoDash* app symbols/theme/project -> Hearth*

HearthApplication (+file+manifest), HearthTheme, HearthTypography,
XML Theme.Hearth (both values/ and values-v31/ + manifest ref),
rootProject.name = "Hearth".

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL
EOF
)"
```

---

### Task 3: `vaca` → `device` folder + `Vaca*` → `Hearth*` symbols

Fold the legacy `vaca` subpackage into `com.rar.hearth.device` and rename the four `Vaca`-named symbols to `Hearth*`. Honor the `"Vacation"` landmine — symbol-targeted only.

**Files:**
- Move: `app/src/main/java/com/rar/hearth/vaca/` → `.../device/`; `app/src/test/java/com/rar/hearth/vaca/` → `.../device/`
- Move: `.../device/VacaServer.kt` → `HearthServer.kt`; `.../device/VacaMessages.kt` → `HearthMessages.kt`; test `VacaServerTest.kt` → `HearthServerTest.kt`; test `VacaMessagesTest.kt` → `HearthMessagesTest.kt`
- Modify: every `.kt` referencing `com.rar.hearth.vaca`, `VacaServer`, `VacaParser`, `VacaOutgoing`, `startVaca`, or the `vaca` field — chiefly `App.kt` and `HearthApplication.kt`

**Interfaces:**
- Consumes: `com.rar.hearth` package (Task 1) and `HearthApplication` (Task 2).
- Produces: `com.rar.hearth.device.HearthServer` (`.Listener`, `.DEFAULT_PORT`), `HearthParser`, `HearthOutgoing`, `App.startHearth()`, `App.hearth` field. No `Vaca*` identifier or `.vaca` package segment remains; `"Vacation"` test data intact.

- [ ] **Step 1: Move the folder (main + test)**

```bash
cd /home/rar/android_simpla_ha_dash
git mv app/src/main/java/com/rar/hearth/vaca app/src/main/java/com/rar/hearth/device
git mv app/src/test/java/com/rar/hearth/vaca app/src/test/java/com/rar/hearth/device
```

- [ ] **Step 2: Rewrite the package segment `.vaca` → `.device` (FQN-targeted, safe)**

Only the full package path is matched, so no bare-`vaca` substring risk:

```bash
cd /home/rar/android_simpla_ha_dash
grep -rIl "com\.rar\.hearth\.vaca" --include=*.kt app/src | xargs sed -i 's/com\.rar\.hearth\.vaca/com.rar.hearth.device/g'
```

- [ ] **Step 3: Rename the class/object files**

```bash
cd /home/rar/android_simpla_ha_dash
git mv app/src/main/java/com/rar/hearth/device/VacaServer.kt   app/src/main/java/com/rar/hearth/device/HearthServer.kt
git mv app/src/main/java/com/rar/hearth/device/VacaMessages.kt app/src/main/java/com/rar/hearth/device/HearthMessages.kt
git mv app/src/test/java/com/rar/hearth/device/VacaServerTest.kt   app/src/test/java/com/rar/hearth/device/HearthServerTest.kt
git mv app/src/test/java/com/rar/hearth/device/VacaMessagesTest.kt app/src/test/java/com/rar/hearth/device/HearthMessagesTest.kt
```

- [ ] **Step 4: Rename the `Vaca*` symbols (symbol-targeted — NEVER bare `Vaca`)**

`VacaServer`/`VacaParser`/`VacaOutgoing` are unique tokens; replacing them cannot touch `Vacation` (different token). Then handle `startVaca` and the `vaca` field explicitly.

```bash
cd /home/rar/android_simpla_ha_dash
grep -rIl -E "VacaServer|VacaParser|VacaOutgoing|startVaca" --include=*.kt app/src \
  | xargs sed -i -e 's/VacaServer/HearthServer/g' -e 's/VacaParser/HearthParser/g' -e 's/VacaOutgoing/HearthOutgoing/g' -e 's/startVaca/startHearth/g'
```

- [ ] **Step 5: Rename the `vaca` field in App.kt surgically**

In `app/src/main/java/com/rar/hearth/App.kt`, the field `val vaca: HearthServer = HearthServer(...)` (was `VacaServer`) and any bare `vaca` references to it must become `hearth`. Do NOT sed bare `vaca` globally. Inspect and edit App.kt directly:

```bash
cd /home/rar/android_simpla_ha_dash
grep -n "\bvaca\b" app/src/main/java/com/rar/hearth/App.kt
```
Rename the `val vaca` declaration to `val hearth` and update its in-file uses (and the stale comment mentioning it, if any). Confirm no other file references `.vaca` as a member.

- [ ] **Step 6: Verify — no `Vaca*` left, `"Vacation"` preserved, package clean**

```bash
cd /home/rar/android_simpla_ha_dash
echo "Vaca-prefixed symbols left (expect 0):"; grep -rIn "Vaca[A-Za-z]" app/src | grep -v "Vacation" | wc -l
echo "Vacation test data still present (expect 1):"; grep -rn "Vacation" app/src/test/java/com/rar/hearth/ui/model/CalendarModelTest.kt | wc -l
echo "old .vaca package segment (expect 0):"; grep -rI "com\.rar\.hearth\.vaca\|startVaca\|\bval vaca\b" app/src | wc -l
```
Expected: `0`, `1`, `0`.

- [ ] **Step 7: Run the gate**

```bash
cd /home/rar/android_simpla_ha_dash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-t3.log 2>&1; echo "RC=$?"
grep -iE "BUILD (SUCCESS|FAIL)|tests? (completed|failed)|FAILED" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-t3.log | tail -20
```
Expected: `RC=0`, `BUILD SUCCESSFUL`, tests pass (~1055).

- [ ] **Step 8: Commit**

```bash
cd /home/rar/android_simpla_ha_dash
git add -A
git commit -m "$(cat <<'EOF'
refactor(rename): vaca subpackage -> com.rar.hearth.device, Vaca* -> Hearth*

Move folder (main+test) and rename HearthServer/HearthParser/
HearthOutgoing/startHearth + App.hearth field. Wire protocol
(_hearth._tcp., DEFAULT_PORT value) unchanged. "Vacation" test data
preserved (symbol-targeted renames only).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL
EOF
)"
```

---

## Post-plan (controller, after all three tasks review clean)

Not tasks in the plan, but the operational close-out:

1. Flash desk (`10.75.1.98`) + crown (`10.75.1.139`); confirm each shows `Success` and relaunches.
2. Reset the Home default over adb on each: `adb -s <serial> shell cmd package set-home-activity com.rar.echodash/com.rar.hearth.MainActivity` (fallback: press Home once, pick Hearth → Always). Verify with `adb shell dumpsys package preferred | grep -i home` or a home-press.
3. Live-verify the in-place update kept config: app launches and shows HA data (proves `filesDir` / auth survived — the whole point of keeping `applicationId`).
4. Freshy Show 5 #2 (`10.75.0.13`, adb-unauthorized) and Tab M9 catch up later.
5. Update `.superpowers/sdd/progress.md` + memory. Push only when the user says so.
