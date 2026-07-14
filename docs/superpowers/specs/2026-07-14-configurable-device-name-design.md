# Configurable Device Name — Design

**Date:** 2026-07-14
**Status:** Approved (user: "have at it")
**Motivation:** The device's identity is hardcoded "Echo Dashboard" in four places
(NsdAdvertiser.kt:22, VacaMessages.kt:72/74, SatelliteSession.kt SATELLITE_NAME).
With a second Hearth device (Lenovo Tab M9), enabling voice/VACA on it would create
two identically-named satellites in HA. This is the prerequisite for tablet voice,
and the same identity seam the planned own-HA-integration will use.

## Decisions (user-chosen)

- **Storage: device-local** (`SettingsStore`), NOT `DashConfig` — a config
  export/import clones the dashboard config between devices and must never clone
  identity. Same principle as the PIN and notify token.
- **Default name: `"Hearth (<MODEL> <ID4>)"`** where `<MODEL>` is
  `android.os.Build.MODEL` and `<ID4>` is the last 4 hex chars of
  `Settings.Secure.ANDROID_ID` — unique out of the box even for two identical
  devices. (MAC address is unavailable: API 30+ returns a fake
  `02:00:00:00:00:00` and `NetworkInterface.getHardwareAddress()` returns null.)
  ANDROID_ID is stable across reboots and `install -r`; changes only on factory
  reset. The suffix exists only in the computed default — a user-set name is used
  verbatim.

## Storage

`SettingsStore` gains one property, following the existing pattern exactly:

```kotlin
var deviceName: String?   // null = unset -> computed default
```

- `InMemorySettingsStore`: plain `null`-initialized var.
- `PrefsSettingsStore`: `get() = string("device_name"); set(v) = put("device_name", v)`.
- NOT touched by `clearAuth()` — the name survives an HA disconnect.

The effective name is computed in `App.kt` (the only Android-aware layer):

```kotlin
fun deviceName(): String =
    settings.deviceName ?: "Hearth (${Build.MODEL} ${androidIdSuffix})"
```

where `androidIdSuffix` is the last 4 chars of
`Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)`
(lowercase hex as returned; if ANDROID_ID is null/blank — never seen in practice —
fall back to `"0000"`). `App.deviceName()` is the single source of truth; every
consumer receives it as a `() -> String` lambda so live components always read the
current value.

## Consumers (all four hardcoded sites)

1. **`NsdAdvertiser`** — new constructor param `name: () -> String`, read inside
   `register()` when building `NsdServiceInfo`. Both App.kt instances (`nsd` for
   `_vaca._tcp.`, `voiceNsd` for `_wyoming._tcp.`) pass `{ deviceName() }`.
2. **`VacaOutgoing.info(appVersion)`** → `info(appVersion: String, name: String)`;
   both `put("name", ...)` sites (satellite name + attribution name) use it. Call
   site is App.kt:208's `infoEvent` lambda — becomes
   `VacaOutgoing.info(BuildConfig.VERSION_NAME, deviceName())`, so each info
   response reads the current name.
3. **`SatelliteSession`** — delete `const val SATELLITE_NAME`; new constructor
   param `name: () -> String`. All three uses (satellite name, attribution name,
   wake-section attribution) call it. `SatelliteServer` gains the same param and
   passes it to every `SatelliteSession(...)` it constructs; App.kt passes
   `{ deviceName() }`.

The attribution `url` fields stay as-is.

## Rename semantics

New PIN-gated endpoint on `ConfigServer`:

- **`PUT /api/name`**, body `{"name": "..."}`.
- Clamp: trim; strip ASCII control chars (`< 0x20` and `0x7F`); collapse runs of
  whitespace to single spaces; truncate to 40 chars (then trim again).
- Empty after clamping (or `name` missing/null) → **reset to default**: store
  `null`.
- Response `200`: `{"name": "<effective name>"}` — the stored custom name or the
  computed default. Malformed JSON body → 400 `{"error": ...}`.

`ConfigServer` stays Android-free and testable: two new constructor params
`deviceName: () -> String` (effective name) and
`setDeviceName: (String?) -> Unit` (clamped-or-null setter callback). App.kt wires
`setDeviceName` to: store the value in `settings.deviceName`, then apply the
rename —

- re-register the `_vaca._tcp.` advertiser if it's currently registered
  (`unregister()` + `register()`),
- restart the VACA server and voice satellite so HA reconnects and re-reads the
  identity (the voice side's existing reactive teardown/rebuild is the model; the
  plan picks the exact mechanism — what matters is both live sessions re-announce
  with the new name without an app restart).

Renames are rare; a brief HA reconnect blip is acceptable.

**HA-side caveat (documented in UI copy, not worked around):** HA's device
registry keeps the name it captured at first setup. Renaming here updates mDNS
and the Wyoming/VACA `info` immediately, but an already-registered HA entry may
keep showing its old name until renamed or re-added in HA. New setups (the
tablet) get the right name from the start.

## Web UI

- **`GET /api/status`** gains `"deviceName": deviceName()` (effective name).
- New **Device** card on the config page, placed first after the setup section
  (before Panels): matching card markup (`card-section`, `card-head`, inline SVG
  icon in the stroke-1.7 style — a tag/label motif), title **Device**, subtitle
  "How this device identifies itself to Home Assistant and on the network."
- Card body (new `renderDevice()` wired into `render()`): one text input
  pre-filled with the current name (from `/api/status`), a **Rename** button
  (`ghost` class), and muted copy: the name appears in HA (voice satellite,
  VACA) and in mDNS; it is per-device and never included in config
  export/import; leave the field empty to restore the default; Home Assistant
  may keep showing the old name on entries it already knows until renamed there.
- Rename click → `PUT /api/name` → on 200, write the returned effective name
  back into the input and `setStatus("Renamed", "ok")`; 401 → `showLogin()`;
  other errors → `setStatus(..., "err")`.

## Testing

- Plain-JVM `ConfigServer` tests (existing test style, injected fakes): PUT
  clamps (control chars, whitespace collapse, 40-char truncation), empty/missing
  name resets to default (setter receives `null`, response returns default),
  requires session auth, `/api/status` includes `deviceName`.
- `node --check app/src/main/assets/config/app.js` after JS edits.
- Live verify on the tablet: rename via the config page, confirm the input
  adopts the effective name, and `avahi-browse`/HA discovery shows the new mDNS
  name; confirm default shows "Hearth (TB310FU xxxx)" shape.

## Out of scope (YAGNI)

- Per-protocol names (one name feeds mDNS, VACA, and Wyoming alike).
- Automating the HA-side device rename for existing entries.
- Migrating/renaming the Echo's existing HA registration.
- Exporting the name in config backup (deliberately excluded).
