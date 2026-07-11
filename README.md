# Echo Dashboard

A native Android kiosk dashboard for an Amazon Echo Show 5 running LineageOS. Logs into Home Assistant via OAuth2 (HA's own login page), registers itself as a `mobile_app` device, and shows a fullscreen dashboard: dusk-gradient background, minute clock, and a live temperature over a reconnecting WebSocket.

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
2. The app registers as a device — check HA under *Settings → Devices & Services → Mobile App* for "Echo Dashboard".
3. **Pick a sensor** — temperature sensors are listed, `sensor.outside_temperature` first.
4. **Dashboard** — long-press for the menu (Change sensor / Android settings / Log out).

For boot-to-dashboard, set the app as the default launcher: *Settings → Apps → Default apps → Home*.

## On-device verification checklist (not yet run)

- [ ] Setup → login → device appears in HA's device registry
- [ ] Keyboard doesn't cover the URL field / HA login form (IME insets under immersive mode)
- [ ] Temperature tracks the selected sensor; toggle Wi-Fi → offline dot appears, last value stays
- [ ] Delete the device in HA while the dashboard is live → app returns to Setup promptly
- [ ] Log out and back in twice → check HA for duplicate "Echo Dashboard" device entries
- [ ] Change sensor → watch for a brief stale value from the old sensor
- [ ] Set as default launcher, reboot → dashboard comes back on its own
- [ ] Confirm device clock is NTP-synced before judging any token/auth misbehavior

## Design docs

- Spec: `docs/superpowers/specs/2026-07-10-echo-ha-dashboard-design.md`
- Implementation plan: `docs/superpowers/plans/2026-07-10-echo-ha-dashboard.md`

## Known post-MVP cleanups (triaged, non-blocking)

- Hoist `AppDeps` to an Application singleton and release the socket/scope in `onDestroy`
- Session-scoped pending-request map in `HaWebSocket`
- `normalizeBaseUrl` hardening (case-insensitive scheme, degenerate `http://` input)
- Surface OAuth `error_description` and WebView HTTP errors on the Setup screen
- Re-register lazily when `webhookId` is missing instead of on every login
- Map non-numeric sensor states (`unavailable`) to `--` on the dashboard
