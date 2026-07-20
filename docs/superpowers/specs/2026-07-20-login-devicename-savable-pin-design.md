# Web-Config Login: Device Name + Browser-Savable PIN + Custom PIN — Design

**Status:** Approved 2026-07-20

**Goal:** Make the web-config login friendlier: (A) show the device name on the login page, (B) let the browser save/autofill the PIN, and (C) let the user set a custom (memorable) PIN or reset to a random one.

**Scope:** app-side only (web-config assets + `ConfigServer` + `App.kt`). No HA-side work.

## Background (current state)

- The config server (`com.rar.hearth.web.ConfigServer`, NanoHTTPD, port 8080, plain HTTP on a LAN IP) serves static assets from `app/src/main/assets/config/` (`index.html`, `app.js`, `style.css`).
- Login is a `<form id="login-form">` inside `index.html` with `<input id="pin" inputmode="numeric" autocomplete="off" maxlength="6" placeholder="••••••">`. `app.js` POSTs `{pin}` to `POST /api/login` (pre-auth); on success the server sets `session=<token>; Path=/; HttpOnly`.
- The auth gate in `ConfigServer.route()`: `/api/login`, `/api/notify`, `/api/notify/clear` are pre-auth; everything else under `/api/` requires `authed(session)`.
- `deviceName()` is available in `ConfigServer` and returned by `GET /api/status` — but `/api/status` is **gated**, so the login page can't read the name yet. There is no GET name endpoint (`/api/name` is PUT + gated).
- PIN: `generatePin()` (`Pin.kt`) = random 6-digit zero-padded. Persisted in `settings.configPin` (SharedPreferences key `config_pin`). `App.ensuredPin` = `settings.configPin ?: generatePin().also { settings.configPin = it }` (a `by lazy`), and the server's `pin: () -> String` lambda is `{ configPin() }` which returns that cached lazy value. Login lockout already exists (`LoginResult.LockedOut` with `retryAfterSeconds`).

## Decisions (confirmed)

- Custom PIN format: **4–8 digits, numeric** (`^\d{4,8}$`). The auto-generated default stays 6 digits.
- Login PIN field: **`type="password"` (masked) with a show/hide eye toggle**, `autocomplete="current-password"`, paired with a readonly device-name **username** field so browsers save one credential per device.

## Part A — Device name on the login page (pre-auth)

- Add pre-auth endpoint **`GET /api/hello`** → `{"name": <deviceName()>, "configured": <configured()>}`. Register it in `route()` above the auth gate, next to `/api/login`. Unauthenticated is acceptable: the device name is already displayed on the physical device screen and is not a secret.
- `index.html`: add a device-name element in the login card (e.g. a line rendered as "Configuring: <name>").
- `app.js`: when the login overlay is shown, `fetch('/api/hello')`, render the name into that element, and set the username field value (Part B). Fail soft — if `/api/hello` errors, show the login without the name (never block login).

## Part B — Browser-savable PIN (login form semantics)

`index.html` login form changes:
- Add a readonly username field carrying the device name: `<input id="login-device" name="username" autocomplete="username" readonly>` (styled to read as the "Configuring: <name>" heading; must be a real form field so password managers key the saved credential to the device). Its value is set by `app.js` from `/api/hello`.
- Change the PIN input to: `type="password"`, `inputmode="numeric"`, `autocomplete="current-password"`, `maxlength="8"` (was 6), **remove** `autocomplete="off"`. Keep `id="pin"`.
- Add an **eye toggle** button that flips the PIN input between `type="password"` and `type="text"` (reveal/hide). Toggle has an accessible label (`aria-label`) and does not submit the form (`type="button"`).
- Keep everything inside `<form id="login-form">` so the browser recognizes a username+password login and offers to save on success.

`app.js` changes:
- On login-overlay show: populate `#login-device` value from `/api/hello`.
- Login submit: read `#pin`, POST to `/api/login` as today. Do **not** clear the PIN field before the browser has a chance to detect the successful submission (so the save prompt fires).
- Eye toggle handler.

Caveat (documented, not fixable here): plain-HTTP LAN origin means browsers may show a "not secure" note when saving; autofill still works same-origin. No TLS on a LAN IP.

## Part C — Custom / override PIN

**Endpoints (both gated):**
- **`PUT /api/pin`** body `{"pin": "1234"}` → validate `isValidCustomPin` (`^\d{4,8}$`); on success persist and return `{"pin": <newPin>}`; on invalid return `400` with `{"error": "invalid pin"}`. Does **not** clear the current session cookie.
- **`POST /api/pin/reset`** → generate a fresh random 6-digit PIN via `generatePin()`, persist, return `{"pin": <newPin>}`.

**Live PIN source fix (App.kt):**
- The server's `pin` lambda must reflect an override without an app restart. Change the pin source so it reads `settings.configPin` live, still ensuring one exists on first use — e.g. `fun configPin(): String = settings.configPin ?: ensuredPin` (keep `ensuredPin` for the generate-once-on-first-boot behavior; once `settings.configPin` is set, reads return it).
- Add `setPin: (String) -> Unit` (writes `settings.configPin`) and `resetPin: () -> String` (writes a new `generatePin()` and returns it) callbacks, wired from `App.kt` into `ConfigServer`, mirroring the existing `setDeviceName` pattern.
- The on-device PIN display must read the same live source so a changed PIN shows on the device screen immediately. Verify the display path (SetupCoordinator / UI) reads `configPin()` freshly rather than a cached copy; adjust if it caches.

**Config UI (`app.js` + `index.html` Device page):**
- Add a "PIN" card on the existing Device page: shows the current PIN, a **Change PIN** numeric input (4–8 digits) + Save → `PUT /api/pin`, and a **Reset to random** button → `POST /api/pin/reset`. On success, update the displayed current PIN and show a confirmation.
- Client-side validate 4–8 digits before sending; show inline error on 400.
- Source of the displayed current PIN: add a `pin` field to the gated `GET /api/status` response (already session-gated; the PIN is shown on the device screen anyway) for the initial render, and use the `{"pin": …}` returned by the change/reset endpoints to update it after a change.

## Files

- `app/src/main/assets/config/index.html` — login card (device-name line, username field, password-type PIN input + eye toggle, maxlength 8); Device-page PIN card.
- `app/src/main/assets/config/app.js` — `/api/hello` fetch + name/username render; eye toggle; login submit unchanged in transport; PIN change/reset handlers + validation.
- `app/src/main/assets/config/style.css` — eye toggle, device-name line, PIN card styling (ember theme, matches existing cards).
- `app/src/main/java/com/rar/hearth/web/ConfigServer.kt` — pre-auth `GET /api/hello`; gated `PUT /api/pin` + `POST /api/pin/reset`; add `pin` to `/api/status`; `setPin`/`resetPin` plumbing.
- `app/src/main/java/com/rar/hearth/web/Pin.kt` — `fun isValidCustomPin(s: String): Boolean` (`^\d{4,8}$`).
- `app/src/main/java/com/rar/hearth/App.kt` — live PIN source (`configPin()` reads `settings.configPin` fresh); `setPin`/`resetPin` callbacks wired to `ConfigServer`.
- `app/src/test/java/com/rar/hearth/web/PinTest.kt` — cases for `isValidCustomPin` (accept 4/6/8 digits, reject 3/9 digits, non-numeric, empty).

## Testing & gate

- **No new behavior in Compose** — the login/config are HTML/JS, not Compose; not unit-tested beyond `node --check`.
- Add JVM JUnit4 tests for `isValidCustomPin` (pure fn) following the existing `web` test patterns.
- **Gate before every commit:** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` (RC captured, redirected to a scratchpad log, never piped to `tail`/`head`) **plus `node --check app/src/main/assets/config/app.js`** (this feature touches the web assets). Require RC=0 and node check clean.
- Manual/curl verification post-flash: `GET /api/hello` returns the name pre-auth; login saves in a browser; `PUT /api/pin`/`reset` change the PIN and the device screen updates; a changed PIN authenticates without restart.

## Security notes

- `GET /api/hello` (device name, pre-auth): acceptable — name is on the device screen, not a secret.
- PIN endpoints are session-gated. A 4-digit custom PIN is weaker (10k combos) but the existing login lockout throttles brute force; keep the lockout in the path.
- Plain-HTTP LAN origin: browser "not secure" note on save is expected and unavoidable without LAN TLS.

## Out of scope (v1)

- PIN complexity requirements beyond length (no forbidden-sequence checks).
- TLS / HTTPS for the config server.
- Multi-user / per-user PINs.
