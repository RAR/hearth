# Echo Mic Mixer Self-Heal — MixerGuard

**Date:** 2026-07-17
**Status:** Approved ("just fine on the design")

## Problem

The Echo Show 5/8 codec boots with the mic PGA starved (`ADC_A MICPGA Volume Ctrl` = 40 →
speech at ~2-5% healthy amplitude; wake detection crippled). We fixed it live via
`tinymix 'ADC_A MICPGA Volume Ctrl' 64 64`, but ALSA mixer state resets on reboot. The fix
must reapply itself with no human in the loop.

## Design

Two halves: a one-time per-device unlock (done over adb root by the operator, not the app),
and an app-side guard that applies the value whenever the voice stack starts.

### Device unlock (one-time, per Echo, operator-applied — documented, not code)

`/system/etc/permissions/platform.xml` gains inside `<permissions>`:
```xml
<permission name="android.permission.MODIFY_AUDIO_SETTINGS" >
    <group gid="audio" />
</permission>
```
Applied via `adb root` + remount; survives reboots and app updates. Effect: any app holding
MODIFY_AUDIO_SETTINGS gets the `audio` GID → rw on `/dev/snd/controlC0` (SELinux is
permissive on these builds). The M9 is untouched (no unlock, and the guard no-ops there).

### App side

- `AndroidManifest.xml` gains `<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />`.
- New `voice/MixerGuard.kt`:
  - Pure decision core `mixerCommands(tinymixExists: Boolean, hardware: String): List<List<String>>`
    (plain-JVM testable): returns the command list ONLY when `tinymixExists` and `hardware`
    contains `"mt81"` (MTK Echo boards; M9/other devices → empty list). Commands:
    `["/system/bin/tinymix", "ADC_A MICPGA Volume Ctrl", "64", "64"]`.
  - `fun apply()` (Android side): checks `File("/system/bin/tinymix").exists()`, reads
    `android.os.Build.HARDWARE`, runs each command via `ProcessBuilder` with a 3s wait,
    logs one line: `MixerGuard: applied (exit=0)` / `MixerGuard: skipped (not this hardware)`
    / `MixerGuard: failed (exit=N)` — failures are soft, voice continues at HAL gain.
- Call site: wherever the voice stack starts (the same App.kt path that rebuilds
  detector/satellite on `voice.enabled`) — guard runs on every voice-stack (re)start, so a
  rebooted or drifted HAL heals on next app launch or voice toggle.

## Constants

`MICPGA_TARGET = 64` — the tuned value (40 default = starved, 80 = analog clipping).
Not user-configurable (YAGNI); lives as a constant in MixerGuard with a comment pointing at
the HAL-quirks investigation.

## Tests (plain-JVM JUnit4)

- `mixerCommands(true, "mt8163")` → the tinymix command with "64" "64".
- `mixerCommands(true, "qcom")` → empty; `mixerCommands(false, "mt8163")` → empty.

## Verification

- Gate: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- Live on desk Echo: set mixer to 40 manually, restart app, confirm log line `MixerGuard: applied`
  and `tinymix 'ADC_A MICPGA Volume Ctrl'` reads 64. Show 8: same unlock + spot-check.
  M9 (when reachable): confirm `MixerGuard: skipped`.

## Out of scope

- Making the PGA value configurable; boost/digital-volume controls (inert/unneeded).
- Magisk boot scripts (the app owns the heal).
- The VOICE_RECOGNITION→MIC source change (already shipped separately).
