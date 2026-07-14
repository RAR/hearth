# Config Export/Import — Design

**Date:** 2026-07-14
**Status:** Approved (user: "lgtm")
**Motivation:** A second Hearth device (Lenovo Tab M9 wall tablet) needs the Echo's
dashboard config without re-entering everything by hand. `GET`/`PUT /api/config`
already exist and PUT already clamps/sanitizes, so this is web-UI-only work.

## Scope

A new **Backup** card on the config page (`index.html` + `app.js`) with two actions.
No Kotlin changes. No new endpoints.

- **Import mode: full replace** (user-chosen). No section picker.
- **Export format: JSON file download** (user-chosen). No copy/paste textarea.

## Behavior

### Export config
1. Fetch a fresh copy from the device: `GET /api/config` (NOT the page's in-memory
   `config`, which may hold unsaved edits).
2. Pretty-print (`JSON.stringify(cfg, null, 2)`) and download as
   `hearth-config-YYYY-MM-DD.json` via a Blob + temporary `<a download>` element.
3. On fetch failure (401 → `showLogin()`, network error → `setStatus(..., "err")`),
   nothing downloads.

The file contains only `DashConfig` content (version, panels, entities, home,
panelOptions, voice, media, night, notifications). It never contains HA auth,
the PIN, or the notify token — those live outside `DashConfig`.

### Import config…
1. Hidden `<input type="file" accept=".json,application/json">`, triggered by the
   visible button.
2. Read the file's text; `JSON.parse` it. Parse failure →
   `setStatus("Import failed: not a valid config file", "err")`, config untouched.
   The parsed value must be a plain object (not an array/string/number) — anything
   else is the same parse failure.
3. `confirm("Replace this device's entire configuration with <filename>?")` —
   cancel aborts with no change.
4. On confirm: `config = parsed; await save();` — the existing `save()` PUTs,
   adopts the server's clamped copy, re-renders every card, and reports
   status ("Saved" / error). No page reload needed. After a successful save,
   `setStatus("Imported <filename>", "ok")`.
5. Reset the file input's value after each pick so re-importing the same file fires
   `change` again.

Robustness comes free from the device side: `ConfigJson` uses `ignoreUnknownKeys`
and every section is `clamped()` on PUT, so files from older/newer builds or
hand-edited files degrade gracefully (bad values fall back to defaults) instead of
erroring. A file that isn't JSON at all is the only client-rejected case.

## UI

New `<section id="backup-section" class="card-section">` placed **last** (after the
calendars card), matching the existing card markup: `card-head` with an inline SVG
icon (stroke 1.7 style — up/down arrows over a tray, e.g. download/upload motif),
`card-titles` with:

- **h2:** Backup
- **p:** Export this device's configuration to a file, or restore one — handy for
  cloning a setup onto a new device.

Body `<div id="backup">` rendered by a new `renderBackup()` in `app.js` (wired into
`render()`): two buttons styled like the page's existing buttons ("Export config",
"Import config…") plus the hidden file input. Note the connection to HA and the
notify token are NOT in the file — a freshly imported device still needs its own
HA setup, and the Notifications card still shows that device's own token.

## Testing

- `node --check app/src/main/assets/config/app.js` must pass.
- No JS test harness exists; verification is a live round trip: export from the
  Echo (10.75.1.98), import to the tablet (10.75.0.183), confirm the tablet's
  cards re-render with the Echo's panels/entities/calendars.
- Also verify the abort paths by hand: bad file → error status, cancel on the
  confirm → no change.

## Out of scope (YAGNI)

- Section-by-section import picker.
- Export/import of HA auth or notify token (device-specific by design).
- Automatic config sync between devices.
- Version-migration logic beyond the existing clamping.
