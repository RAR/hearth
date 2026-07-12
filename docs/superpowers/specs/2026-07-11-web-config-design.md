# On-Device Web Configuration — Design

**Date:** 2026-07-11
**Target device:** Amazon Echo Show 5, LineageOS 18.1 (Android 11), 960×480 landscape
**Goal:** Replace HA-label-driven entity configuration with an on-device web config UI: the app runs a small embedded HTTP server on the LAN; a browser on any machine configures entities, panels, home-screen settings, and the photo source. Config becomes the single source of truth, applied live.

## Background & decisions

- Labels (`echo-*`) worked but are limited: no ordering, no per-panel options, and HA's label slugification already caused one field bug. The user chose **web replaces labels** — one config system.
- Config scope (user-chosen): entity assignment, panel enable/order, home-screen settings, per-panel options, photo source.
- Photo source stays HA-mediated (folder path configurable; a NAS is reached by mounting it into HA's media dirs — no SMB client in the app). Large folders handled by a **rotating cached subset**.
- Auth: **PIN shown on device**, entered once per browser session.
- Server: **NanoHTTPD** (`org.nanohttpd:nanohttpd:2.3.1`) — single dependency-free Java jar; runs on Android 11 and inside plain-JVM unit tests. Ktor rejected (Kotlin-metadata pinning, transitive kotlinx bumps, dex weight). This is the only new dependency.

## Architecture

```
Browser ⇄ ConfigServer (NanoHTTPD, port 8080)
              ⇅
          ConfigStore — config.json in app filesDir, exposed as StateFlow<DashConfig>
              ⇅
   EntityHub · PhotoStore · DashboardShell/panels (react live, no restart)
```

- `EntityHub` keeps fetching the entity registry (names + full entity list feed the web pickers), but the watched entity set is now derived from `DashConfig`, not label matching. A config change re-subscribes exactly like a registry change does today.
- VACA protocol code and kiosk control are untouched.

## Config model

`config/DashConfig.kt` — kotlinx-serialization data classes, one versioned document (`version: 1` field for future migration).

- **Panels:** for each of Lights/Climate/Media/Weather/Solar: `enabled: Boolean`, `order: Int` (rail position; Home is always first and not configurable).
- **Entities:**
  - `tempSensor: String?` — home-pill temperature sensor.
  - `weather: String?` — weather entity for pill + weather panel.
  - `climate: List<String>` — thermostat entities.
  - `solar: pv/load/grid/pvToday/loadToday`, each `String?` (grid and today slots optional, same panel semantics as now).
  - `lightGroups: List<LightGroup>` — each `{name: String, entities: List<String>}`, list order = on-screen order. Explicit groups replace label-suffix derivation; entity order within a group is display order.
  - Media panel has no entity config (this device only).
- **Home:** `idleReturnSeconds: Int` (default 60), `clockFormat: AUTO|H12|H24` (default AUTO = follow system), `slideshowEnabled: Boolean` (default true), `photoFolder: String` (default `echo-frame`, relative to HA `media/`), `photoCacheCap: Int` (default 50).
- **Per-panel:** `thermostatStep: Double` (default 0.5), `forecastDays: Int` (default 5, max 5).

### ConfigStore

`config/ConfigStore.kt` — loads/saves `config.json` in the app's files dir; exposes `StateFlow<DashConfig>`. Writes are atomic (temp file + rename). Validation on save: unknown fields ignored (`ignoreUnknownKeys`), out-of-range numbers clamped to sane bounds (idle 15–3600 s, cap 5–500, step 0.1–5.0, forecast 1–5).

### Migration from labels

On first start with no `config.json`, once the registry is available: seed the config from current `echo-*` labels using the existing label-resolution code (temp/weather/climate/solar slots; `echo-lights[-<group>]` becomes explicit named groups, bare label → group "Lights" first, suffixes title-cased, alphabetical). Persist the seed, then never consult labels again. A corrupt `config.json` is renamed to `config.json.bad` and re-seeded (labels if present, else defaults). Label-matching code is retained solely for seeding.

## ConfigServer

`web/ConfigServer.kt` — NanoHTTPD subclass bound to port **8080** on all interfaces, started with the dashboard once the user is logged in. Routes:

| Route | Behavior |
|---|---|
| `GET /` (+ static assets) | Serves the config page from app assets |
| `POST /api/login` | Body `{pin}`; correct → sets a session cookie (random token, valid until app restart); wrong → 401. 5 consecutive failures → 60 s lockout (429) |
| `GET /api/config` | Current `DashConfig` JSON (auth required) |
| `PUT /api/config` | Validate + save via ConfigStore; 200 with the stored config, or 400 with a reason string; config untouched on 400 (auth required) |
| `GET /api/entities` | Live picker feed from EntityHub's registry + states: `[{id, name, domain, state}]`, all registry entities (auth required) |

Port already bound → log the failure; retry at next app start (no crash loop). Server stops on logout.

## Web UI

`assets/config/` — one self-contained page (`index.html` + inline or sibling CSS/JS), vanilla JS, no framework, no build step, no external resources. Sections mirror the config model:

- **Panels:** checkbox + up/down reorder per panel.
- **Entities:** each slot is a searchable picker (text filter, pre-filtered to sensible domains — e.g. `climate.*` for thermostats, `weather.*` for weather) fed from `/api/entities`. Light groups: add/rename/delete groups, add/remove/reorder entities within each.
- **Home** and **per-panel** settings as plain inputs with the documented ranges.
- Explicit **Save** button → `PUT /api/config`; success/failure feedback inline. The device applies changes within a couple of seconds.
- PIN prompt overlays everything until `POST /api/login` succeeds.

## Security

- 6-digit PIN generated on first start, persisted in app prefs.
- The Home view's long-press menu gains a **"Configure"** entry showing `http://<device-ip>:8080` and the PIN.
- Session cookie per browser session; all `/api/*` routes except login require it. Failed-PIN lockout as above.
- Plain HTTP, LAN-only trust model — same grade as a default HA install; documented in README.

## App-side rewiring

- **EntityHub:** watched set = all entity ids referenced anywhere in `DashConfig`; collect the config StateFlow and re-subscribe on change (reusing the registry-change resync path). Registry list/`entity_registry_updated` handling remains for names + picker feed.
- **Panels/shell:** rail builds from panel enable/order; LightsModel consumes explicit groups; ClimatePanel uses `thermostatStep`; WeatherPanel caps at `forecastDays`; IdleReturnTimer uses `idleReturnSeconds`; clock honors `clockFormat`.
- **PhotoStore:** folder + cap + enabled from config; folder or cap change triggers a resync. Slideshow disabled → dusk gradient.

## Photos — rotating subset

- Listing ≤ cap → today's sync-all behavior (download new, delete removed).
- Listing > cap → maintain a random subset of `cap` photos: each sync evicts ~20% of the cached set (random picks) and refills to the cap with random not-yet-cached items from the listing. Files that left the folder are always evicted first. Over successive syncs a large archive rotates through fully; storage stays bounded (cap × ≤~400 KB downsampled).
- Selection logic is a pure function (listing + currently-cached + cap → downloads/deletions) for testability; randomness injected.

## Error handling

| Condition | Behavior |
|---|---|
| Invalid `PUT /api/config` | 400 + reason; stored config unchanged |
| Config references entity absent from HA | Panel dims/hints exactly like today's unavailable handling |
| Corrupt config.json at load | Rename to `.bad`, reseed from labels/defaults, log |
| Port 8080 in use | Log, dashboard runs without config server, retry next app start |
| Wrong PIN ×5 | 60 s lockout on login route |
| HA disconnected | Config page still loads and saves; `/api/entities` serves last-known registry (empty if never fetched) |

## Testing

Plain-JVM JUnit4, as established:

- `DashConfig` serialization round-trip; unknown-field tolerance; clamping.
- Label→config seeding (each slot, lights suffix grouping, empty-label default).
- Corrupt-file recovery (`.bad` rename + reseed).
- Rotating-subset selection: under-cap passthrough, over-cap eviction/refill counts, removed-files-first, injected randomness.
- EntityHub: watched set derived from config, re-subscribe on config change.
- Auth: PIN check, session token, lockout after 5 failures, lockout expiry.
- `ConfigServer` end-to-end in JVM tests: start on an ephemeral port with fake ConfigStore/EntityHub, drive with OkHttp (login → get → put → entities, 401 without cookie, 400 on bad body).
- Web page JS stays thin and untested; manual verification in a real browser.

Manual verification: long-press → Configure shows URL + PIN; page loads on phone/PC; login; reassign an entity and watch the panel update live; reorder/disable a panel; change photo folder to a big NAS-mounted folder and confirm subset rotation across syncs; reboot → config persists; wrong PIN ×5 locks out.

## Out of scope

HTTPS/TLS, multi-user accounts, config export/import, on-device (touchscreen) config UI, SMB/Immich/direct-URL photo sources, editing HA entities themselves from the page, mDNS advertisement of the config URL.

## References

- Prior specs: `2026-07-10-echo-ha-dashboard-design.md` (MVP), `2026-07-11-vaca-protocol-support-design.md` (VACA), `2026-07-11-dashboard-shell-design.md` (shell — its "Label scheme & EntityHub" section is superseded by this spec).
