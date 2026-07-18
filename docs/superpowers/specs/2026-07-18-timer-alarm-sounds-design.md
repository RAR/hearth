# Timer Alarm System Sounds

**Date:** 2026-07-18
**Status:** Approved (user: "Use Argon, package them all tho - we can have them as options")

## Goal

Replace the synthesized default timer alarm with real alarm audio: bundle 7 AOSP system alarm
sounds (auditioned by the user off the Echo's `/product/media/audio/alarms/`) as app assets,
selectable through the existing `timerTone` setting. New default: **Argon**. The 4 synthesized
tones remain as options.

## Assets — `app/src/main/assets/sounds/`

Copied from the session scratchpad (`sysalarms/`, pulled from the Echo Show 5; identical files
ship on the Show 8). All are AOSP/LineageOS sounds, Apache-2.0 — include `ATTRIBUTION.txt`
noting origin and license.

| tone key | asset file        | source file       | length |
|----------|-------------------|-------------------|--------|
| argon    | alarm_argon.ogg   | Argon.ogg         | 19.4 s |
| oxygen   | alarm_oxygen.ogg  | Oxygen.ogg        | 10.7 s |
| krypton  | alarm_krypton.ogg | Krypton.ogg       | 7.2 s  |
| timer    | alarm_timer.ogg   | Timer.ogg         | 3.6 s  |
| beep     | alarm_beep.ogg    | Alarm_Beep_03.ogg | 2.4 s  |
| helium   | alarm_helium.ogg  | Helium.ogg        | 2.1 s  |
| cyan     | alarm_cyan.ogg    | CyanAlarm.ogg     | 1.9 s  |

Ogg is on aapt's default no-compress list, so `assets.openFd()` works (assets stay STORED).

## Pure model — `voice/TimerSounds.kt` (plain-JVM)

```kotlin
object TimerSounds {
    /** tone key -> asset path under assets/, null for synthesized tones. */
    fun assetPath(tone: String): String?  // "argon" -> "sounds/alarm_argon.ogg"
}
```

## Config — `DashConfig.kt` `VoiceSettings`

- `TONES` grows to 11 keys (7 asset + 4 synthesized).
- Default `timerTone = "argon"`; `clamped()` unknown/blank fallback also becomes `"argon"`.
- Volume semantics unchanged (0..100).

## Playback — `TimerChime.kt`

Constructor gains `private val assetFd: (String) -> AssetFileDescriptor?` (App.kt passes
`{ runCatching { context.assets.openFd(it) }.getOrNull() }`; tests pass `{ null }`).

- `start(tone, volume)` / `playOnce(tone, volume)`: when `TimerSounds.assetPath(tone) != null`,
  play via `MediaPlayer` — `AudioAttributes(USAGE_ALARM, CONTENT_TYPE_SONIFICATION)`,
  `setVolume(volume/100f, volume/100f)`, `isLooping = true` for `start` (continuous loop, the
  way DeskClock loops these files — proven working on this device 2026-07-18), `false` for
  `playOnce` (release on completion). `volume <= 0` -> no-op.
- Any failure on the asset path (missing fd, MediaPlayer error) falls back to the synthesized
  `"twotone"` AudioTrack loop at the same volume — an alarm must never fail silent.
- `stop()` stops/releases whichever path is active; idempotency semantics unchanged
  (second `start` while playing is a no-op).
- Synthesized-tone path (AudioTrack, prime-before-play HAL recipe) untouched.

## Web config — `assets/config/app.js`

- `TONE_OPTIONS` reordered: 7 system sounds first (labels: Argon, Oxygen, Krypton,
  Timer (Android), Alarm beep, Helium, Cyan alarm), then the 4 synthesized.
- Null default at load becomes `"argon"` (was `"twotone"`).
- Preview button/endpoint unchanged — `/api/voice/preview-chime` already passes the tone
  through `clamped()`.

## Tests (plain-JVM JUnit4)

- `TimerSounds`: each of the 7 keys maps to its asset path; synthesized keys and unknowns -> null.
- `VoiceSettings.clamped()`: new keys accepted; unknown/blank -> "argon"; default is "argon".
- Update any existing tests asserting the "twotone" default/fallback.

## Verification

- Gate per commit: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  plus `node --check app/src/main/assets/config/app.js`.
- Flash both Echos; POST `/api/voice/preview-chime` with `{"tone":"argon"}` for an audible check;
  set `voice.timerTone = "argon"` on both device configs via `/api/config` (stored configs still
  say "twotone" and stored values win over the new default).
- Wyoming-inject a short timer on the Show 8 and let it finish: logcat should show the
  MediaPlayer path, alarm loops until the finished-overlay dismiss calls `stop()`.
