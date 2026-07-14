# Config Export/Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Backup card to the Hearth web config page that exports the device's `DashConfig` to a JSON file and imports a JSON file back as a full-replace configuration.

**Architecture:** Web-UI-only change to the NanoHTTPD-served config page. Export does a fresh `GET /api/config` and triggers a Blob download; import reads a picked file, JSON-parses it, confirms, then reuses the existing `save()` path (which PUTs to `/api/config`, adopts the server's clamped copy, and re-renders). The `PUT /api/config` handler already clamps/sanitizes every section, so robustness against old/hand-edited files comes for free from the device side.

**Tech Stack:** Vanilla JS (ES2020, `"use strict"`), plain HTML/CSS, no framework. Verification via `node --check` only (no JS test harness).

## Global Constraints

- No Kotlin changes. Only `app/src/main/assets/config/index.html` and `app/src/main/assets/config/app.js` may be modified.
- No new HTTP endpoints — reuse the existing `GET`/`PUT /api/config`.
- No new dependencies, no build-tool changes, no external libraries (strict same-origin, plain `fetch`/Blob).
- `node --check app/src/main/assets/config/app.js` MUST pass after every JS change.
- Match existing `app.js` style: `"use strict"`, semicolons, `el(tag, cls, text)` / `clear(node)` DOM helpers, `api(method, path, body)` fetch wrapper, template literals where already used, `addEventListener` for wiring.
- Card UI copy is verbatim from the spec — do not paraphrase.
- Every commit message MUST end with this trailer line:
  ```
  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

### Task 1: Backup card — export/import

**Files:**
- Modify: `app/src/main/assets/config/index.html` — insert a new `<section id="backup-section">` after the calendars section (currently ends at the `</section>` on line 231), before the `.content` closing `</div>` (line 232).
- Modify: `app/src/main/assets/config/app.js` — make `save()` return a boolean; add `renderBackup()`, `exportConfig()`, `importConfig()`; wire `renderBackup()` into `render()`.
- Test: none (no JS test harness). Gate is `node --check` plus manual browser verification (final steps).

**Interfaces:**
- Consumes (already in `app.js`): `api(method, path, body)` → `fetch` Response; `save()` (PUTs global `config`, adopts clamped copy, calls `render()`, sets status); `setStatus(msg, kind)` with `kind` in `"ok"|"err"|"busy"|"info"`; `render()`; `showLogin()`; `el(tag, cls, text)`; `clear(node)`; global `config`.
- Produces: `save()` now returns `true` when the PUT succeeded (HTTP ok), `false` otherwise. New functions `renderBackup()`, `exportConfig()`, `importConfig(ev)`. New DOM ids: `backup-section`, `backup`.

---

- [ ] **Step 1: Add the Backup `<section>` to `index.html`**

In `app/src/main/assets/config/index.html`, insert this block immediately after the calendars section's closing `</section>` (line 231) and before the `</div>` that closes `.content` (line 232). It is the LAST card. The inline SVG is a stroke-1.7 tray with a down arrow (export) and an up arrow (import), matching the other card icons.

```html
      <section id="backup-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>
              <path d="M8.5 4v7"/><path d="M6 8.5 8.5 11 11 8.5"/>
              <path d="M15.5 11V4"/><path d="M13 6.5 15.5 4 18 6.5"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Backup</h2>
            <p>Export this device's configuration to a file, or restore one — handy for cloning a setup onto a new device.</p>
          </div>
        </div>
        <div id="backup"></div>
      </section>
```

- [ ] **Step 2: Make `save()` return a success boolean**

`save()` currently returns nothing, so the import code has no way to know whether the PUT succeeded. Make the minimal change: return `true` on the ok path, `false` on every other exit. In `app/src/main/assets/config/app.js`, replace the whole `save()` function:

```js
async function save() {
  setStatus("Saving…", "busy");
  try {
    const r = await api("PUT", "/api/config", config);
    if (r.ok) {
      config = await r.json();  // adopt the server's clamped copy
      render();
      setStatus("Saved", "ok");
      return true;
    } else if (r.status === 401) {
      showLogin();
    } else {
      const b = await r.json().catch(() => ({}));
      setStatus("Error: " + (b.error || r.status), "err");
    }
  } catch (e) {
    setStatus("Can't reach the device — changes not saved.", "err");
  }
  return false;
}
```

This is the only change to `save()` — its signature (no args), status messages, and adopt-clamped-copy behavior are all unchanged. No other caller inspects the return value, so adding it is safe.

- [ ] **Step 3: Add `renderBackup()`, `exportConfig()`, and `importConfig()`**

In `app/src/main/assets/config/app.js`, add these three functions. Place them just after `renderCalendar(...)` ends (line 855) and before `updateNightLux(...)` (line 857), keeping the render functions grouped together.

```js
function renderBackup() {
  const host = document.getElementById("backup");
  clear(host);

  // Hidden file input the visible "Import" button proxies to.
  const fileInput = el("input");
  fileInput.type = "file";
  fileInput.accept = ".json,application/json";
  fileInput.hidden = true;
  fileInput.addEventListener("change", importConfig);

  const row = el("div", "row");

  const exportBtn = el("button", "ghost", "Export config");
  exportBtn.type = "button";
  exportBtn.addEventListener("click", exportConfig);
  row.appendChild(exportBtn);

  const importBtn = el("button", "ghost", "Import config…");
  importBtn.type = "button";
  importBtn.addEventListener("click", () => fileInput.click());
  row.appendChild(importBtn);

  row.appendChild(fileInput);
  host.appendChild(row);

  host.appendChild(el("div", "muted",
    "The file holds only this dashboard's configuration — not the Home Assistant " +
    "connection or the notify token, which stay on each device. A freshly imported " +
    "device still needs its own HA setup. Importing replaces this device's entire configuration."));
}

async function exportConfig() {
  // Fetch a FRESH copy from the device, not the in-memory `config` (which may hold unsaved edits).
  setStatus("Exporting…", "busy");
  try {
    const r = await api("GET", "/api/config");
    if (r.status === 401) { showLogin(); return; }
    if (!r.ok) { setStatus("Export failed (" + r.status + ")", "err"); return; }
    const cfg = await r.json();
    const text = JSON.stringify(cfg, null, 2);
    const d = new Date();
    const stamp = d.getFullYear() + "-" +
      String(d.getMonth() + 1).padStart(2, "0") + "-" +
      String(d.getDate()).padStart(2, "0");
    const blob = new Blob([text], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = el("a");
    a.href = url;
    a.download = "hearth-config-" + stamp + ".json";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    setStatus("Exported", "ok");
  } catch (e) {
    setStatus("Can't reach the device — export failed.", "err");
  }
}

async function importConfig(ev) {
  const input = ev.target;
  const file = input.files && input.files[0];
  if (!file) return;

  let parsed;
  try {
    const raw = await file.text();
    parsed = JSON.parse(raw);
  } catch (e) {
    setStatus("Import failed: not a valid config file", "err");
    input.value = "";   // reset so re-picking the same file fires `change` again
    return;
  }

  // Must be a plain object — arrays, strings, numbers, null are the same rejection.
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    setStatus("Import failed: not a valid config file", "err");
    input.value = "";
    return;
  }

  if (!confirm("Replace this device's entire configuration with " + file.name + "?")) {
    input.value = "";
    return;
  }

  // Full replace: adopt the parsed object and PUT it. save() reports its own
  // "Saved"/error status and re-renders every card (including this one); only
  // announce the import when the PUT actually succeeded.
  config = parsed;
  if (await save()) setStatus("Imported " + file.name, "ok");
  input.value = "";
}
```

- [ ] **Step 4: Wire `renderBackup()` into `render()`**

In `app/src/main/assets/config/app.js`, add the Backup card to the render pass. It renders LAST, matching its position in the page. Change the `render()` function (lines 298–309):

```js
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderNotifications();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
  renderEv();
  renderCalendars();
  renderBackup();
}
```

- [ ] **Step 5: Run the syntax gate**

Run: `node --check app/src/main/assets/config/app.js`
Expected: no output, exit code 0 (any syntax error fails the task — fix and re-run).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/config/index.html app/src/main/assets/config/app.js
git commit -m "feat: config export/import on the web config page

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

- [ ] **Step 7: Manual verification (browser)**

No automated harness exists; verify by hand against a running device (Echo at 10.75.1.98, tablet at 10.75.0.183):

1. Open the config page, unlock with the PIN. Scroll to the bottom — the **Backup** card appears last, after Calendars, with the tray icon and both buttons.
2. Click **Export config**. A file named `hearth-config-YYYY-MM-DD.json` (today's date) downloads; open it and confirm it is pretty-printed `DashConfig` JSON (version, panels, entities, home, panelOptions, voice, media, night, notifications) and contains NO HA auth, PIN, or notify token.
3. Click **Import config…**, pick a non-JSON file (e.g. any `.txt` renamed to `.json`, or an empty file). Status shows `Import failed: not a valid config file`; nothing changes.
4. Pick a JSON file that is a top-level array (e.g. `[]`). Same rejection: `Import failed: not a valid config file`.
5. Click **Import config…**, pick the exported file. The confirm dialog reads `Replace this device's entire configuration with hearth-config-YYYY-MM-DD.json?`. Click **Cancel** → no change, no status update.
6. Repeat and click **OK** → status goes `Saving…` then `Saved`, every card re-renders, and finally status reads `Imported hearth-config-YYYY-MM-DD.json`.
7. Pick the SAME file a second time — the `change` event fires again (the input was reset), confirming re-import works.
8. Live round trip: export from the Echo, import the file on the tablet, confirm the tablet's Panels/Entities/Calendars re-render with the Echo's values. The tablet's Notifications card still shows the tablet's own notify token (device-specific, not imported).

---

## Self-Review

**1. Spec coverage:**
- Export: fresh `GET /api/config`, pretty-print, `hearth-config-YYYY-MM-DD.json` Blob + `<a download>` — Step 3 `exportConfig()`. ✓
- Export failure paths: 401 → `showLogin()`, network/non-ok → `setStatus(..., "err")`, nothing downloads — Step 3. ✓
- Import: hidden `<input accept=".json,application/json">` triggered by visible button — Step 3 `renderBackup()`. ✓
- Import parse failure and non-plain-object rejection → `setStatus("Import failed: not a valid config file", "err")` — Step 3 `importConfig()`. ✓
- `confirm("Replace this device's entire configuration with <filename>?")`, cancel aborts — Step 3. ✓
- On confirm `config = parsed; await save();` then `setStatus("Imported <filename>", "ok")` only on success — Step 3 (via `save()` returning a boolean, Step 2). ✓
- Reset `input.value` after each pick — Step 3 (all exit paths). ✓
- New `<section id="backup-section">` placed last, `card-head` + inline stroke-1.7 SVG (up/down over a tray) + `card-titles` h2 "Backup" / spec p text; body `<div id="backup">` rendered by `renderBackup()` wired into `render()` — Steps 1 & 4. ✓
- Testing: `node --check` gate + manual round trip and abort paths — Steps 5 & 7. ✓
- Out of scope (section picker, auth/token export, auto-sync, migration logic) — none added. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to". All code is complete and final. ✓

**3. Type/name consistency:** `save()` returns boolean (Step 2) and is awaited as one in Step 3. `renderBackup`/`exportConfig`/`importConfig` are defined in Step 3 and referenced only there and in `render()` (Step 4). DOM ids `backup-section`/`backup` match between Steps 1 and 3. Button class `ghost` and helpers `el`/`clear`/`setStatus`/`api`/`showLogin` all exist in the current `app.js`. ✓

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-14-config-export-import.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent for Task 1, review before commit, fast iteration.

**2. Inline Execution** — execute Task 1 in this session using executing-plans, with a checkpoint before the commit.

**Which approach?**
