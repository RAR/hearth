# Echo Dashboard

A native Android kiosk dashboard for an Amazon Echo Show 5 running LineageOS. Logs into Home Assistant via OAuth2 (HA's own login page) and shows a fullscreen dashboard: dusk-gradient background, minute clock, and a live temperature over a reconnecting WebSocket. Speaks the [VACA](https://github.com/msp1974/ViewAssist_Companion_App) device protocol, so the VACA HACS integration gives HA full control of the device — screen, brightness, screensaver, toasts, TTS announcements, and a media player — with native rendering instead of VACA's WebView.

Built with Kotlin + Jetpack Compose, minSdk 28 / targetSdk 34. 25 plain-JVM unit tests.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto  # or any JDK 17+
./gradlew test assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Android SDK location is read from `local.properties` (`sdk.dir=/home/rar/android-sdk`).

## Install on the Echo

```bash
adb connect <echo-ip>   # or USB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-run flow

1. **Setup** — enter the HA URL (e.g. `http://homeassistant.local:8123`), log in on HA's own page.
2. **Add the device in HA** — install the [VACA integration](https://github.com/msp1974/ViewAssist_Companion_App) via HACS; the Echo is auto-discovered via mDNS (`_vaca._tcp.`, port 10700) under *Settings → Devices & Services*. Manual fallback: add a VACA device with the Echo's IP and port 10700.
3. **Pick a sensor** — temperature sensors are listed, `sensor.outside_temperature` first.
4. **Dashboard** — long-press for the menu (Change sensor / Android settings / Log out).

For boot-to-dashboard, set the app as the default launcher: *Settings → Apps → Default apps → Home*.

## HA-side controls (VACA)

Once the VACA integration connects, the device exposes in HA: screen on/off, brightness + auto-brightness (ambient light sensor), always-on, screen timeout, screensaver, dark mode, wake/refresh buttons, toast messages (`action: toast-message`), a media player (URLs/radio via ExoPlayer), and TTS announcements (`assist_satellite.announce`). Voice-pipeline entities (wake word, mic gain, pipeline select, mute) exist but are inert — this device is display-only for now. `assist_satellite.start_conversation` is unsupported (no microphone); plain `announce` works.

## On-device verification checklist

MVP items verified 2026-07-11: setup → login → dashboard works on the Echo.

- [ ] Keyboard doesn't cover the URL field / HA login form (IME insets under immersive mode)
- [ ] Temperature tracks the selected sensor; toggle Wi-Fi → offline dot appears, last value stays
- [ ] Delete the device in HA while the dashboard is live → app returns to Setup promptly
- [ ] Change sensor → watch for a brief stale value from the old sensor
- [ ] Set as default launcher, reboot → dashboard comes back on its own
- [ ] VACA: integration auto-discovers the Echo; device + entities appear
- [ ] VACA: screen switch, brightness, screensaver, dark mode respond from HA
- [ ] VACA: screen timeout sleeps the screen; touch wakes it and HA's screen switch follows
- [ ] VACA: `assist_satellite.announce` plays through the Echo speaker (media ducks and resumes)
- [ ] VACA: media player plays a radio URL; play/pause/stop/volume track in HA
- [ ] VACA: light sensor entity follows room lighting; auto-brightness adjusts the panel
- [ ] VACA: reboot the Echo → settings restored, HA reconnects within ~10 s
- [ ] Remove the old mobile_app "Echo Dashboard" device entry in HA (superseded by VACA)

## Design docs

- Spec: `docs/superpowers/specs/2026-07-10-echo-ha-dashboard-design.md`
- Implementation plan: `docs/superpowers/plans/2026-07-10-echo-ha-dashboard.md`

## Known post-MVP cleanups (triaged, non-blocking)

- Session-scoped pending-request map in `HaWebSocket`
- `normalizeBaseUrl` hardening (case-insensitive scheme, degenerate `http://` input)
- Surface OAuth `error_description` and WebView HTTP errors on the Setup screen
- Map non-numeric sensor states (`unavailable`) to `--` on the dashboard
