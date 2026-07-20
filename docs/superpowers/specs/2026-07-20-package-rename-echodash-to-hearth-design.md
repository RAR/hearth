# Package Rename: `echodash` → `hearth` — Design

**Status:** Approved 2026-07-20

**Goal:** Rename the app's internal code identity from `com.rar.echodash` to `com.rar.hearth` (Kotlin package, symbols, XML theme, Gradle project name) while **keeping `applicationId = com.rar.echodash`** so both live devices update in place and retain their HA auth, config, and PIN. Also fold in the deferred `vaca` subpackage cleanup.

## The one critical invariant

`applicationId` **stays** `com.rar.echodash`.

`filesDir` (`/data/data/com.rar.echodash/files/config.json` + the auth/token/PIN store) is keyed on `applicationId`. Keeping it constant makes the flash an **in-place update**: both devices (desk Echo Show 5 `10.75.1.98`, Echo Show 8 "crown" `10.75.1.139`) keep their HA connection, config, and PIN with zero reconfiguration. Changing `applicationId` would be a fresh install = full reconfigure on every device — explicitly out of scope and must not happen.

The `applicationId` line in `app/build.gradle.kts` sits directly below the `namespace` line that this rename **does** change. The change touches `namespace` only; `applicationId` is left exactly as-is.

## Scope of the rename (all confirmed with the user)

Full identity rename **including** the `vaca` subpackage.

### 1. Gradle / build

- `settings.gradle.kts`: `rootProject.name = "EchoDash"` → `rootProject.name = "Hearth"`
- `app/build.gradle.kts`:
  - `namespace = "com.rar.echodash"` → `namespace = "com.rar.hearth"` — this drives the package of the generated `R` and `BuildConfig` classes and the expansion of relative manifest component names.
  - `applicationId = "com.rar.echodash"` → **UNCHANGED.**

### 2. Package tree move

- `app/src/main/java/com/rar/echodash/**` → `app/src/main/java/com/rar/hearth/**`
- `app/src/test/java/com/rar/echodash/**` → `app/src/test/java/com/rar/hearth/**`
- Every `package com.rar.echodash…` → `package com.rar.hearth…`
- Every `import com.rar.echodash…` → `import com.rar.hearth…`
- The 3 explicit fully-qualified `R`/`BuildConfig` references (namespace-driven, so they must point at the new package):
  - `app/src/main/java/com/rar/echodash/ui/SplashScreen.kt:14` — `import com.rar.echodash.R` → `com.rar.hearth.R`
  - `app/src/main/java/com/rar/echodash/ui/theme/Type.kt:11` — `import com.rar.echodash.R` → `com.rar.hearth.R`
  - `app/src/main/java/com/rar/echodash/sendspin/sendspin/SendSpin.kt:312` — `com.rar.echodash.BuildConfig.VERSION_NAME` → `com.rar.hearth.BuildConfig.VERSION_NAME`

### 3. App symbols (full identity)

- `EchoDashApplication` → `HearthApplication`
  - class in `EchoDashApplication.kt` → file renamed `HearthApplication.kt`
  - manifest `android:name=".EchoDashApplication"` → `".HearthApplication"`
  - caller `EchoDashApplication.kt:12` `deps.startVaca()` (see §5 for `startVaca`)
- `EchoDashApp` → `HearthApp` — the app's root `@Composable` (`fun EchoDashApp(deps: AppDeps)` in `App.kt`, called from `MainActivity.kt`). NOTE: `EchoDashApp` is a prefix of `EchoDashApplication`, so rename the Application class first.
- `EchoTheme` → `HearthTheme` — `fun` in `ui/theme/Theme.kt` (3 refs: decl + call sites)
- `EchoTypography` → `HearthTypography` — `val` in `ui/theme/Type.kt` (2 refs: decl + use in `Theme.kt`)

### 4. XML theme

- `res/values/themes.xml` — `<style name="Theme.EchoDash" …>` → `<style name="Theme.Hearth" …>`
- `res/values-v31/themes.xml` — same rename
- manifest `android:theme="@style/Theme.EchoDash"` → `"@style/Theme.Hearth"`

### 5. `vaca` subpackage → `com.rar.hearth.device`

- Folder move (main + test): `com/rar/hearth/vaca/**` → `com/rar/hearth/device/**` (after step 1 has already moved `echodash`→`hearth`)
- `package`/`import …vaca` → `…device`
- Symbol renames (target the **symbols**, never the substring — see Landmines):
  - `VacaServer` → `HearthServer` (incl. `VacaServer.Listener`, `VacaServer.DEFAULT_PORT` which follow automatically); file `VacaServer.kt` → `HearthServer.kt`, test `VacaServerTest.kt` → `HearthServerTest.kt`
  - `VacaParser` → `HearthParser`; `VacaOutgoing` → `HearthOutgoing`; both live in `VacaMessages.kt` → `HearthMessages.kt`, test `VacaMessagesTest.kt` → `HearthMessagesTest.kt`
  - `startVaca()` → `startHearth()` (decl in `App.kt`, call in `HearthApplication.kt`)
  - field `val vaca: VacaServer` in `App.kt` → `val hearth: HearthServer`
- Callers to update: `App.kt` (imports + `VacaOutgoing`/`VacaServer` uses + `startVaca` + `vaca` field), `HearthApplication.kt` (`startVaca()` call).
- The other files in the folder (`AndroidKioskDevice`, `AnnouncePlayer`, `DashActionParser`, `ExoPlayerEngine`, `KioskController`, `LightSensorReporter`, `MediaBridge`, `NsdAdvertiser`, `ResumePolicy`, `WyomingEvent`, `AndroidPcmSink`) move with the folder but keep their class names (they carry no `Vaca` prefix).

## Landmines (explicit)

1. **`"Vacation"` string in `CalendarModelTest.kt:45`** (`{"summary":"Vacation",…}`) — a blind `Vaca`→`Hearth` substring replace corrupts this to `"Heartntion"`. All `vaca` symbol renames MUST be word/symbol-targeted (`VacaServer`, `VacaParser`, `VacaOutgoing`, `startVaca`), never a bare `s/Vaca/Hearth/`. This is real test data (a calendar event summary) and must survive unchanged.
2. **`applicationId` line must not be touched** — change `namespace` only in `build.gradle.kts`.
3. **Do not rename the runtime `_hearth._tcp.` mDNS service type or `HearthServer.DEFAULT_PORT` value** — those are on-the-wire identifiers already named `hearth`; only the Kotlin class/field identifiers change.

## What stays unchanged

- `applicationId = "com.rar.echodash"`.
- On-device `filesDir` path `/data/data/com.rar.echodash/…` (follows applicationId).
- HA auth/config/PIN on both devices (consequence of the two above).
- The `"Vacation"` test string.
- `android:label="Hearth"` in the manifest (already correct).
- The `_hearth._tcp.` service type and wire protocol behavior.

## On-device consequence: Home launcher default

The app registers `category.HOME` + `category.LAUNCHER` on `MainActivity`. Android stores the Home default as a `ComponentName` = `applicationId` + class FQN = `com.rar.echodash/com.rar.echodash.MainActivity`. After the rename the class FQN becomes `com.rar.hearth.MainActivity`, so:

- The install updates in place (same `applicationId` package) — config survives.
- The Home **default** (a stale `ComponentName`) no longer resolves, so the device would fall back to the Home chooser on next home-press.

**Mitigation (post-flash, non-interactive):**
```
adb shell cmd package set-home-activity com.rar.echodash/com.rar.hearth.MainActivity
```
Fallback if `cmd package set-home-activity` is unavailable on the device's API level (Echo = API 30 / LOS 18.1; Tab M9 = Android 13): press Home once and pick Hearth in the chooser (Always). Verify with `adb shell cmd shortcut get-default-launcher` or `dumpsys package preferred`.

## Verification strategy

Because this is a pure rename with no behavior change, **the compiler and the existing unit-test suite (≈1055 tests) are the safety net** — any missed reference fails to compile or fails a test.

- **Gate before every commit** (standing rule): `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`, RC captured immediately, output redirected to a scratchpad log (never piped to `tail`/`head`). No `node --check` needed — no web-config (`app.js`) is touched.
- **No new tests** are written; the rename introduces no new behavior. Existing tests must remain green and unmodified in intent (only their `package`/`import`/symbol references change).
- **Post-flash live check:** app launches; home shows HA data (proves config/auth survived the in-place update); then reset the Home default via adb.

## Execution structure — three independently-green steps

Each step compiles and passes the full suite on its own, so each is a reviewable unit.

1. **Package move.** `echodash`→`hearth` directory move (main + test) + rewrite all `package`/`import` + the 3 explicit `R`/`BuildConfig` refs + `namespace` in `build.gradle.kts`. Symbols keep their `EchoDash*`/`Vaca*` names; `applicationId` unchanged. → gate green.
2. **App-symbol + theme + project rename.** `EchoDashApplication`→`HearthApplication` (+ file + manifest), `EchoTheme`→`HearthTheme`, `EchoTypography`→`HearthTypography`, XML `Theme.EchoDash`→`Theme.Hearth` (both `themes.xml` + manifest), `rootProject.name`→`"Hearth"`. → gate green.
3. **`vaca`→`device` + `Vaca*`→`Hearth*`.** Folder move + symbol/file renames + caller updates per §5, honoring the `"Vacation"` landmine. → gate green.

Commit after each step with the standard session trailer. Flash to desk + crown after step 3 (or after each step if convenient), reset Home over adb, live-verify config survived. Push only when the user says so. Freshy Show 5 #2 (`10.75.0.13`) is currently adb-unauthorized and will catch up later; Tab M9 catches up when reachable.
