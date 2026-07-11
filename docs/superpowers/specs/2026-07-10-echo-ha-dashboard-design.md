# Echo HA Dashboard — MVP Design

**Date:** 2026-07-10
**Target device:** Amazon Echo Show 5 running LineageOS 18.1 (Android 11), 960×480 landscape touchscreen
**Goal:** A native Android kiosk dashboard that logs into Home Assistant, registers itself as a device, and displays a background image, the current time, and a temperature from HA.

## Decisions made during brainstorming

- **Auth:** HA OAuth2 code flow (HA's own login page in a WebView) + `mobile_app` device registration. No long-lived tokens.
- **Temperature source:** configurable entity picker, defaulting to `sensor.outside_temperature`; reachable later via long-press on the dashboard.
- **Kiosk:** immersive fullscreen, screen always on, auto-start on boot, registers as HOME launcher (user opts in via Android settings).
- **Stack:** single-module Kotlin app, Jetpack Compose, OkHttp WebSocket for live updates. minSdk 28, targetSdk 34.
- **Out of scope for MVP:** weather forecasts, additional cards, reporting sensors back to HA, night dimming, notifications.

## Architecture

Three logical layers in one module:

### `ha/` — Home Assistant client

- **`AuthManager`** — OAuth2 authorization-code flow:
  - Authorize URL: `{haUrl}/auth/authorize?client_id={clientId}&redirect_uri={redirect}`.
    `client_id` is a URL the HA instance can resolve per HA convention; redirect is a custom scheme intercepted by the WebView.
  - Exchanges code at `POST {haUrl}/auth/token`; stores access token (≈30 min lifetime) and refresh token.
  - `refreshAccessToken()` used before WebSocket connect and on auth failure. A rejected refresh token (device deleted in HA) clears credentials and returns the app to Setup.
- **`RegistrationClient`** — one `POST {haUrl}/api/mobile_app/registrations` with device metadata (name "Echo Dashboard", model, OS version, app id/version). Stores the returned `webhook_id`. Device then appears in HA under Settings → Devices & Services.
- **`HaWebSocket`** — OkHttp WebSocket to `{haUrl}/api/websocket`:
  - Handshake: receive `auth_required` → send `auth` with access token → expect `auth_ok`.
  - `subscribe_entities` scoped to the chosen temperature entity for live state pushes.
  - Also used once by the entity picker: `get_states` filtered to `sensor.*` with `device_class: temperature`.
  - Auto-reconnect with exponential backoff, 2 s doubling to a 60 s cap; resubscribes after reconnect.
  - Exposes a connection-state flow (connected / connecting / offline) to the UI.

### `data/` — settings

`SharedPreferences`-backed store; tokens in `EncryptedSharedPreferences`. Keys: HA base URL, access token + expiry, refresh token, webhook id, selected temperature entity id.

### `ui/` — three Compose screens

1. **Setup** — text field for HA URL (validates scheme/reachability), then a WebView showing HA's login page; on redirect capture, exchanges the code and runs device registration. Inline error messages with retry for bad URL / unreachable host / auth failure.
2. **Entity picker** — lists temperature sensors fetched over the WebSocket, pre-selecting `sensor.outside_temperature` if present. Reachable from the dashboard via long-press menu.
3. **Dashboard** — full-bleed bundled background image (calm landscape placeholder), large clock (Compose ticker aligned to minute boundaries), temperature with unit below it, small offline indicator dot when the WebSocket is down. Long-press opens a small menu: change sensor, open Android settings (launcher escape hatch), log out.

## First-run flow

Launch → no refresh token stored → Setup → OAuth login → registration → entity picker → dashboard. Subsequent launches: straight to dashboard, connect WebSocket, refresh token as needed.

## Kiosk behavior

- Immersive fullscreen (hide status/nav bars) and `FLAG_KEEP_SCREEN_ON` while the dashboard is showing.
- `RECEIVE_BOOT_COMPLETED` receiver starts the activity on boot.
- Main activity declares `HOME` + `DEFAULT` intent categories so it can be chosen as the default launcher; the long-press menu's "Android settings" item prevents lock-in.

## Error handling

| Condition | Behavior |
|---|---|
| Access token expired | Refresh via refresh token before connect / on auth failure |
| Refresh token revoked | Clear credentials, return to Setup |
| Network loss / HA restart | Backoff reconnect; keep last value visible with offline dot |
| No entity update >15 min while connected | Dim the temperature value (stale guard) |
| Setup input errors | Inline message + retry on Setup screen |

## Testing

- **Unit (JVM/JUnit):** token exchange and refresh logic, WebSocket handshake and entity-update message parsing, settings store round-trip. HTTP/WS faked at the OkHttp boundary.
- **Manual:** adb install to the Echo over Wi-Fi; verify device registration appears in HA and temperature tracks the selected sensor; pull power to verify boot auto-start.

## Build & tooling

- Gradle with Android Gradle Plugin; command-line SDK (cmdline-tools) installed locally — no Android Studio required.
- Deliverable: debug APK sideloaded via `adb install`.
- No Play services, no release signing for MVP.
