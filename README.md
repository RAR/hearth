# Echo Dashboard

A native Android kiosk dashboard for an Amazon Echo Show 5 running LineageOS. Logs into Home Assistant via OAuth2 (HA's own login page) and shows a multi-view dashboard: a right-side icon rail switches between a photo-backed Home clock view and five panels — Lights, Climate, Media, Weather, and Solar. Everything (entity assignment, panel order/visibility, home-screen settings, per-panel options, and the photo source) is configured from an on-device **web config page** served on the LAN — no HA labels, no on-device pickers. Config is one versioned `config.json`, applied live. Bundled Nunito font; auto-returns to Home after the configured idle timeout. Speaks the [VACA](https://github.com/msp1974/ViewAssist_Companion_App) device protocol, so the VACA HACS integration gives HA full control of the device — screen, brightness, screensaver, toasts, TTS announcements, and a media player — with native rendering instead of VACA's WebView.

Built with Kotlin + Jetpack Compose, minSdk 28 / targetSdk 34. 148 plain-JVM unit tests.

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
3. **Configure the dashboard** — long-press the Home view, choose **Configure**, and open the shown URL (`http://<device-ip>:8080`) in any browser on the same network. Enter the PIN shown on the device, then assign entities, order/hide panels, and set home-screen and photo options. Existing installs are migrated automatically: on first launch the app seeds the config from any current `echo-*` labels, after which labels are ignored.
4. **Dashboard** — tap the right-side rail to switch views; long-press the Home view for the menu (Android settings / Log out).

For boot-to-dashboard, set the app as the default launcher: *Settings → Apps → Default apps → Home*.

## HA-side controls (VACA)

Once the VACA integration connects, the device exposes in HA: screen on/off, brightness + auto-brightness (ambient light sensor), always-on, screen timeout, screensaver, dark mode, wake/refresh buttons, toast messages (`action: toast-message`), a media player (URLs/radio via ExoPlayer), and TTS announcements (`assist_satellite.announce`). Voice-pipeline entities (wake word, mic gain, pipeline select, mute) exist but are inert — this device is display-only for now. `assist_satellite.start_conversation` is unsupported (no microphone); plain `announce` works.

## Web configuration

The dashboard is configured from a small web page the device serves on your LAN (NanoHTTPD, port 8080). Long-press the Home view → **Configure** shows the URL (`http://<device-ip>:8080`) and a 6-digit PIN. Open the URL in a browser, enter the PIN (once per browser session), and configure:

- **Panels** — enable/disable and reorder Lights, Climate, Media, Weather, Solar (Home is always first).
- **Entities** — pick the temperature sensor, weather entity, thermostats, and solar sensors from searchable, domain-filtered lists; build named light groups with ordered members.
- **Home screen** — idle-return seconds (15–3600), clock format (auto/12h/24h), photo slideshow on/off, photo folder, and photo cache cap (5–500).
- **Panel options** — thermostat step (0.1–5.0) and forecast days (1–5).

Press **Save** to apply; the device updates within a couple of seconds. Out-of-range numbers are clamped on save. Config is stored at the app's `config.json` and survives reboots.

### Migration from labels

Earlier versions used HA labels (`echo-temp`, `echo-weather`, `echo-lights[-group]`, `echo-climate`, `echo-solar-*`). On first launch with no `config.json`, the app seeds the configuration from those labels once (bare `echo-lights` becomes a group named "Lights", suffixes become title-cased groups). After seeding, labels are never consulted again — all further changes happen on the web page.

### Security

Plain HTTP, LAN-only trust — the same grade as a default Home Assistant install. Access is gated by the on-device PIN (session cookie per browser; 5 wrong PINs lock the login for 60 s). There is no TLS, no multi-user accounts, and no remote access; keep the device on a trusted network.

## Photo slideshow (Home backdrop)

Drop images into a Home Assistant media folder (default `media/echo-frame/`, changeable on the config page). The device syncs that folder on connect and every 6 h, caches downsampled copies (bounded by the photo cache cap — large folders rotate through a random subset), and cycles them on the Home view every 5 minutes with a crossfade. With the slideshow off or no photos, the Home view falls back to the dusk-gradient background.

## Panels

- **Home** — clock, date, and a weather pill (condition + temperature); photo or gradient backdrop.
- **Lights** — grouped toggle tiles; tap to `homeassistant.toggle` (no optimistic UI).
- **Climate** — current temperature, +/- setpoint (configurable step, debounced 800 ms → `climate.set_temperature`), HVAC mode row (`climate.set_hvac_mode`).
- **Media** — the Echo's own VACA player only: play/pause/stop and a 0–100 volume slider.
- **Weather** — current conditions + a configurable-length forecast (up to 5 days, `weather.get_forecasts`, refreshed every 30 min).
- **Solar** — Solar → Home ↔ Grid power-flow with live watts and today's kWh.

## On-device verification checklist

MVP items verified 2026-07-11: setup → login → dashboard works on the Echo.

- [ ] Keyboard doesn't cover the URL field / HA login form (IME insets under immersive mode)
- [ ] Home pill tracks the configured temp/weather entities; toggle Wi-Fi → offline dot appears, last value stays
- [ ] Delete the device in HA while the dashboard is live → app returns to Setup promptly
- [ ] Edit an entity assignment in the web config → the matching panel updates live, no restart
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
