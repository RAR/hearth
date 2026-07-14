# Hearth HA Integration (VACA replacement, sub-project A) — Design

**Date:** 2026-07-14
**Status:** Approved (user: "looks good")
**Motivation:** Replace the VACA HACS fork with our own slim custom integration.
VACA serves someone else's WebView app; we are not its target user. Owning the
integration unblocks entities VACA structurally can't carry (view select, notify
platform — sub-project B). Voice/STT/TTS/wake/timers stay on HA core's Wyoming
integration (port 10600) and are NEVER touched by this work.

**User decisions:** v1 scope = VACA parity now, view-select + notify as
sub-project B; keep the existing VACA wire protocol (app unchanged day one,
coexistence during migration); distribute from this repo via HACS custom
repository.

## Decomposition

- **Sub-project A (this spec):** `custom_components/hearth/` at VACA parity +
  one tiny app change (advertise `_hearth._tcp`). Independently shippable and
  verifiable; VACA keeps running until the user cuts over per device.
- **Sub-project B (separate spec, later):** `select` entity for the current
  dashboard view + a real `notify` platform, plus the small app-side action
  handlers they need.

## Architecture

One config entry per device (unique_id = `host:port`). A per-entry
`HearthClient` owns the TCP connection and fans events out to entities. All
protocol code is HA-import-free and unit-tested with plain pytest against a
fake asyncio server; the HA entity layer is thin and verified live on the
user's HAOS.

**No pip requirements.** The Wyoming framing (JSONL header + optional binary
payload) is ~80 lines, hand-rolled in `codec.py`, mirroring the app's
`WyomingCodec`. Zero `requirements` in the manifest means zero version
conflicts with the `wyoming` lib that HA core pins for its own integration.

### Repo layout

```
hacs.json                              # {"name": "Hearth"} at repo root
custom_components/hearth/
  manifest.json                        # domain hearth, config_flow, zeroconf _hearth._tcp,
                                       # iot_class local_push, requirements []
  __init__.py                          # entry setup/teardown, HearthClient lifecycle
  const.py                             # DOMAIN, settings keys, action names
  codec.py                             # Wyoming JSONL read/write (HA-free)
  client.py                            # HearthClient: session, reconnect, dispatch (HA-free)
  config_flow.py                       # zeroconf + manual host/port
  media_player.py
  switch.py
  number.py
  button.py
  services.yaml                        # hearth.toast
tests/integration/                     # plain pytest for codec.py + client.py
```

## Wire protocol (clean-room, from our own `VacaServer` — the authoritative contract)

Framing: each event is one JSON line `{"type": ..., "data": {...},
"payload_length": N|absent}` followed by N raw bytes when present.

Integration → app:
- `describe` → app replies `info`; `data.satellite.name` is the (now
  configurable) device name, `data.satellite.version` the app version.
- `capabilities` → app replies `capabilities` (`app_version`, `has_battery:
  false`, `sensors` incl. `{type: 5}` when a light sensor exists, `audio.
  max_music_volume: 10`).
- `ping` `{text?}` → app replies `pong` `{text}`.
- `run-satellite` → this connection becomes THE active session (the app is
  newest-wins single-session); the app immediately sends a settings-feedback
  and a status snapshot.
- `custom-event` (HA→device is FLAT: keys sit beside `event_type`):
  - `{event_type: "settings", settings: {...}}` — partial updates OK.
  - `{event_type: "action", action: "...", payload: ...}`.
- `audio-start {rate,width,channels}` / `audio-chunk` (binary payload) /
  `audio-stop` — TTS announce PCM; the app replies `played` when playback
  finishes.

App → integration:
- `custom-event {event_type: "settings", data: {settings: {...}}}` — feedback
  of the full kiosk settings (NESTED under `data`, asymmetric with HA→device;
  documented quirk of the protocol).
- `custom-event {event_type: "status", data: {...}}` — e.g.
  `{media_player: {playing: bool}}`, `{sensors: {orientation, current_path}}`,
  light-sensor readings.
- `played` — announce finished.

Settings keys (device state, echoed in feedback): `screen_on` (bool),
`screen_brightness` (0–100), `screen_auto_brightness` (bool),
`screen_always_on` (bool), `screen_saver` (bool), `dark_mode` (bool),
`screen_timeout` (seconds), `music_volume` (0–10), `ducking_volume` (0–10).

Actions: `screen-wake`/`wake`, `screen-sleep`, `refresh`, `toast-message`
`{message}`, `play-media` `{url, volume?}`, `play`, `pause`, `stop`,
`set-volume` `{volume}`. **Scale quirk:** action `volume` is percent (0–100);
the `music_volume` SETTING is 0–10. Both are faithfully preserved.

## HearthClient (client.py, HA-free)

- Connect → `describe` (grab name/version) → `capabilities` → `run-satellite`
  → event loop. Ping every 4 s; missing pong or read error → disconnect.
- Reconnect with exponential backoff (1 s doubling to a 60 s cap, reset on
  success). Entities show `available: False` while disconnected.
- Public surface: `async_start()`, `async_stop()`,
  `async_send_settings(dict)`, `async_send_action(name, payload)`,
  `async_announce(pcm_stream, rate, width, channels)` (sends audio-start/
  chunks/audio-stop, resolves when `played` arrives or the connection drops),
  `add_listener(cb)` for settings/status dicts, and properties
  `device_name`, `app_version`, `connected`.
- Local echo: after sending a settings change, the app's feedback event is the
  source of truth (entities update from feedback, not optimistically).

## Entities (all state from feedback/status events; device info shared:
manufacturer "Hearth", name from `info`, sw_version = app version)

- **media_player** — state PLAYING when `media_player.playing` else IDLE;
  `volume_level` from `music_volume`/10; supports PLAY_MEDIA / PLAY / PAUSE /
  STOP / VOLUME_SET / ANNOUNCE. `play_media`: resolve `media_source://` URLs
  via `async_process_play_media_url` (so Music Assistant / radio / TTS media
  ids work), then action `play-media {url}`. `announce=true`: fetch the
  resolved URL, transcode to 22050 Hz 16-bit mono PCM with ffmpeg (HA's
  bundled ffmpeg binary via `homeassistant.components.ffmpeg`), stream over
  audio-start/chunk/stop, await `played` — music ducks (app-side) instead of
  stopping. `volume_set` → action `set-volume {volume: pct}`.
- **switch** ×5 — `screen` (`screen_on`), `auto_brightness`, `always_on`,
  `screensaver`, `dark_mode`. Toggle → settings custom-event with that one key.
- **number** ×3 — `brightness` (0–100, `screen_brightness`), `screen_timeout`
  (0–3600 s), `ducking_volume` (0–10).
- **button** — `refresh` → action `refresh`.
- **service `hearth.toast`** — `{message, device/entity target}` → action
  `toast-message {message}`.

Entity ids derive from the device name HA-side as usual; no per-entity naming
config.

## Config flow & discovery

- **Zeroconf** on `_hearth._tcp.local.` (new advertisement, see app change).
  Discovery confirm shows the advertised name; unique_id `host:port` aborts
  duplicates.
- **Manual** fallback: host + port (default 10700). The flow probes with
  `describe` and uses the returned name as the entry title; connection failure
  → `cannot_connect` error in the form.
- Coexistence rule (documented in the config-flow description): the app serves
  ONE integration session at a time (newest-wins) — migrate per device by
  deleting the VACA entry, then adding Hearth. The tablet (never had VACA) is
  the natural first install.

## App change (the only one in sub-project A)

`startVaca()` registers a third `NsdAdvertiser(appContext, VacaServer.
DEFAULT_PORT, "_hearth._tcp.", name = { deviceName() })` alongside the existing
`_vaca._tcp` one (which stays until VACA is retired — follow-up, out of scope).
The rename bounce in `applyDeviceName()` re-registers it like the `_vaca` one.

## Testing

- **pytest** (new dev-only venv at `.venv-integration/`, not committed):
  `codec.py` round-trip framing (with/without payload, split reads);
  `client.py` against a fake asyncio server speaking the app's exact contract —
  handshake order, newest-wins re-run, settings/status dispatch (incl. the
  nested-`data` feedback quirk), ping keepalive, backoff reconnect, announce →
  `played` completion, action/settings scale quirks. No HA imports anywhere in
  `codec.py`/`client.py`.
- **Gate:** `python3 -m pytest tests/integration -q` exit 0, plus
  `python3 -m py_compile` over every file in `custom_components/hearth/`
  (entity modules import `homeassistant.*` so they compile-check but don't
  unit-run without HA).
- **Kotlin gate** unchanged for the app change: full gradle test + assemble.
- **Live verification on the user's HAOS:** push to GitHub → user adds this
  repo as a HACS custom repository and installs → restart HA → tablet
  discovered via `_hearth._tcp` → add it → verify: entity states track the
  app, brightness/screen switches work, `play-media` with a radio URL plays,
  TTS announce ducks music and completes, toast shows. Echo migration (delete
  VACA entry → add Hearth) after the tablet proves parity.

## Out of scope (YAGNI — most land in sub-project B or later cleanup)

- View select entity, notify platform, occupancy (sub-project B).
- Removing `_vaca._tcp` advertising or uninstalling VACA.
- Any change to HA core Wyoming voice (port 10600).
- Config options flow (nothing tunable yet), reauth (no auth on the LAN
  protocol), HACS default-store submission.
- Media browsing UI (`async_browse_media`) beyond media-source URL resolution.
