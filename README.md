<p align="center">
  <img src="docs/logo.png" width="132" alt="Hearth logo" />
</p>

<h1 align="center">Hearth</h1>

A native Android wall-dashboard for Home Assistant, plus its own HA integration.

- **The app** (`app/`): a Kotlin + Jetpack Compose kiosk that turns an Android device into an always-on HA dashboard and voice satellite. Born on a LineageOS Echo Show 5, now happily multi-device. Everything is configured from a web page the device serves on your LAN — no YAML.
- **The integration** (`custom_components/hearth/`): a slim custom integration that gives HA full control of each device — media player, screen, brightness, toasts, TTS announcements.

## What's on screen

- **Home** — photo slideshow backdrop (synced from an HA media folder), clock, weather + AQI + rain pills, next-calendar-event card, EV-charging cards while a car is plugged in, a notification area (HA push + NWS weather alerts), and a full now-playing takeover with album art while music plays.
- **Panels** (right-side rail, swipe-back, auto-return to Home after a configurable idle timeout): Lights, Climate, Media, Weather (current + forecast), Solar power flow, Cameras (RTSP/HLS), and a 3-day Calendar agenda.
- **Voice** — a Wyoming satellite with **on-device wake word** (openWakeWord TFLite: Okay Nabu / Hey Jarvis / Alexa). Mic audio only leaves the device after a local wake detection; HA runs STT/intent/TTS. Assist timers live on the device with countdown chips and a chime, and survive HA restarts.
- **Extras** — doorbell camera popups, ambient-light night clock (huge dim clock in a dark room), dark mode, screensaver, ambient auto-brightness.
- **Multi-room synced audio** — acts as a Music Assistant SendSpin player for sample-accurate multi-room playback (auto-discovered via mDNS; enable on the config page).

## The web config page

Long-press the Home view → **Configure** shows a URL (`http://<device-ip>:8080`) and a 6-digit PIN. From any browser on the LAN you can: run first-time HA OAuth setup, name the device, pick entities from searchable lists, enable/reorder panels, build light groups, configure cameras/doorbells/calendars/EVs, tune voice (wake word, chime, volumes with live preview), set night mode with a live lux readout, manage notifications (token + ready-to-paste `rest_command` YAML), and **export/import the whole config as a JSON file** — handy for cloning a setup onto a new device. Device identity (name, HA auth, PIN, notify token) stays per-device and is never exported.

Plain HTTP, LAN-only trust: PIN-gated session cookies, 5-strike lockout, no TLS — same grade as a default HA install. Keep it on a trusted network.

## The Hearth integration (HA side)

Install via HACS: **HACS → Integrations → ⋮ → Custom repositories → `https://github.com/RAR/hearth`** (type: Integration), install *Hearth*, restart HA. Devices are auto-discovered via mDNS (`_hearth._tcp`, port 10700); manual host/port also works.

Each device gets: a **media player** (URLs/radio/Music Assistant via ExoPlayer, plus `announce` — TTS ducks the music instead of stopping it), **switches** for screen / auto-brightness / always-on / screensaver / dark mode, **numbers** for brightness / screen timeout / ducking volume, a **refresh button**, a **View** select that mirrors and drives the on-screen dashboard view, a **notify** entity, and the **`hearth.toast`**, **`hearth.notify`** (title / message / severity / timeout / id), and **`hearth.notify_clear`** services.

Voice is deliberately separate: the satellite speaks to HA core's own Wyoming integration (port 10600) and keeps working with or without the Hearth integration.

### SendSpin bring-up (manual)

After building/flashing a version with SendSpin, verify it on real devices with Music Assistant:

1. Flash the app to each device; on the config page enable "Synced playback (Sendspin)".
2. Confirm the config page's SendSpin status line moves Disconnected → Connected when Music Assistant is reachable, and the device appears in Music Assistant's player list (pair/add if MA prompts).
3. Group it with another speaker and start playback → audio plays; the home-screen now-playing takeover shows title/artist/artwork.
4. Trigger a TTS/announce → SendSpin audio ducks then restores.
5. Start a URL on the existing media_player → SendSpin stops (mutual exclusion; note it won't auto-rejoin until you re-toggle SendSpin — expected in this version).
6. Note the sync offset vs. the other speaker; the "Sync delay (ms)" field tunes it (tuning deferred).

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto  # any JDK 17+
./gradlew test assembleDebug                            # app: build + 454 unit tests
python3 -m pytest tests/integration -q                  # integration protocol tests (stdlib + pytest only)
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Android SDK location comes from `local.properties` (`sdk.dir=...`).

## Install on a device

```bash
adb connect <device-ip>   # or USB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First run: the device shows a pointer card — open `http://<device-ip>:8080` in a browser, enter the PIN, and complete HA login there (OAuth on HA's own page). For boot-to-dashboard, set Hearth as the default launcher (*Settings → Apps → Default apps → Home*); this is also what auto-starts it after a reboot on Android 10+.
