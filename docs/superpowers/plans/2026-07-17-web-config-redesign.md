# Web Config Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the device's web config page from one long scroll of 13 cards into 9 hash-routed pages behind a sidebar (desktop) / scrollable pill row (phone), and re-theme it from dashboard blue to the warm ember palette — a pure static-asset change to three files.

**Architecture:** The page is a vanilla-JS single page served from three assets baked into the APK (`index.html`, `style.css`, `app.js`). `app.js` builds every control at runtime into fixed host `<div>`s by id; `render()` refills all hosts on load and after every save. The redesign (a) splits the two catch-all render functions (`renderEntities`, `renderOptions`) and one combined one (`renderNotifications`) into nine per-card render functions, (b) wraps the card sections into nine `<section class="page">` containers driven by a tiny hash-router nav module, and (c) swaps the CSS accent tokens and blue literals for the ember palette. No page container ever leaves the DOM — pages hide via the `[hidden]` CSS contract so id-polled elements keep working.

**Tech Stack:** Vanilla ES (no modules, no build), plain CSS (custom properties), inline SVG. Served by the device's embedded HTTP server; assets ship inside the Android APK (Gradle `assembleDebug`).

## Global Constraints
- Only these files change: `app/src/main/assets/config/index.html`, `app/src/main/assets/config/style.css`, `app/src/main/assets/config/app.js`. No Kotlin changes, no config-model changes.
- Vanilla JS/CSS only — no dependencies, no build step, no framework.
- All existing behavior and user-visible copy preserved verbatim: controls, clamps, defaults, muted help text, API calls. Content MOVES, it does not change (only exception: the spec's split of the old combined "Panel options" clamp note so each fragment sits beside its migrated row).
- Every element id referenced from app.js must exist in index.html after every task (`grep -o 'getElementById("[^"]*")' app.js` is the audit).
- Gate before EVERY commit: `node --check app/src/main/assets/config/app.js` then `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` (must be green — the assets ship inside the APK).
- Every commit message ends with the trailer line: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Work directly on master (user's standing instruction). One commit per task minimum.

### Cross-task naming contract (must match everywhere)
- **Page keys (order):** `device`, `screens`, `climate`, `lights`, `cameras`, `energy`, `calendars`, `media`, `alerts`.
- **Page container ids:** `page-device` … `page-alerts`. **Nav anchor ids:** `nav-device` … `nav-alerts`.
- **New host div ids** (filled by new render functions): `sensors`, `thermostats`, `lightgroups`, `quickbuttons`, `cameras`, `doorbells`, `solar`, `nws`, `push`.
- **New card-section wrapper ids:** `sensors-section`, `thermostats-section`, `lightgroups-section`, `quickbuttons-section`, `cameras-section`, `doorbells-section`, `solar-section`, `nws-section`, `push-section` (the last is toggled `hidden` by `renderPush`).
- **New render functions:** `renderSensors`, `renderThermostats`, `renderLightGroups`, `renderQuickButtons`, `renderCameras`, `renderDoorbells`, `renderSolar`, `renderNws`, `renderPush`.
- **New nav functions/const:** `PAGES`, `currentPage()`, `showPage(key)`.
- **Deleted:** functions `renderEntities`, `renderOptions`, `renderNotifications`; sections `entities-section` (host `entities`), `options-section` (host `options`), `notifications-section` (host `notifications`).

---

## Task 1 — Render-function split (page still one long scroll)

Dissolve `renderEntities()` and `renderOptions()` into seven per-section render functions, split `renderNotifications()` into `renderNws` + `renderPush`, migrate the four "Panel options" rows to their new owner cards, relocate the panel-bar note to the Panels card, and replace the three old card sections in `index.html` with the nine new card sections. **No nav yet** — after this task the page is still a single vertical scroll, just reorganized into the final card order. The gate must stay green.

### Files
- `app/src/main/assets/config/app.js` — rewrite `render()`; delete `renderEntities`, `renderOptions`, `renderNotifications`; add the nine new render functions; retarget the row-level re-render callbacks inside `renderLightGroup`, `renderCamera`, `renderDoorbell` to their new owning functions.
- `app/src/main/assets/config/index.html` — replace the entire `<div class="content"> … </div>` block with a flat list of card sections in final page order, dropping `entities-section` / `options-section` / `notifications-section` and adding the nine new sections.

### Interfaces (later tasks rely on these)
- New host div ids exist exactly once each: `sensors`, `thermostats`, `lightgroups`, `quickbuttons`, `cameras`, `doorbells`, `solar`, `nws`, `push`.
- `push-section` wrapper id exists (renderPush toggles its `hidden`).
- Render functions named exactly as in the naming contract. `render()` calls all of them.
- Card sections carry the same `class="card-section"` + `.card-head` (`.ic` tile + `.card-titles` h2 + p) pattern as today so Task 2 can wrap them unchanged.

### Preserve these quirks (a careless implementer will break them)
- **Defensive defaults stay put:** `renderNws` keeps the `if (!config.notifications) …` + the three `== null` fills; `renderQuickButtons`/`renderSolar` keep the `if (!Array.isArray(…)) … while(length<4) push({})` slot padding. `renderSensors`/`renderThermostats`/`renderDoorbells` read `config.panelOptions` directly with **no** defensive default (original `renderOptions` did the same — the model guarantees it).
- **Push card visibility:** the old code rendered the push block only when `lastStatus.notifyToken` was truthy. Now Push is its own card; `renderPush` must set `document.getElementById("push-section").hidden = !token` and `return` early when absent, so a build with no notify token hides the whole card (spec requirement). Drop the old inline `el("h3","subhead","Push from Home Assistant")` — the card-head supplies that title now.
- **Camera→doorbell coupling:** in the old code, adding/removing/reordering a camera called `renderEntities()`, which also rebuilt the doorbell rows (their `<select>` lists cameras by name). Preserve this: the camera add button and `renderCamera`'s reorder/delete handlers must call **`renderCameras(); renderDoorbells();`** so a new/removed camera immediately appears/disappears in the doorbell dropdowns. (Renaming a camera still does NOT re-render — matches today.)
- **Shared datalist / `labeledRow` id-wiring / `entityPicker` blank→null** all come from unchanged helpers; do not reimplement them.
- **The split clamp note** is the ONLY copy change: the old options note `"Step 0.1–5.0, forecast 1–5, doorbell popup 5–120 (clamped on save). The panel bar auto-hides; swipe in from the right edge to bring it back for 8 s."` splits into four fragments placed beside their migrated rows (Thermostats: `"Step 0.1–5.0 (clamped on save)."`; Sensors: `"Forecast 1–5 (clamped on save)."`; Doorbells: `"Doorbell popup 5–120 (clamped on save)."`) and the panel-bar sentence moves to the Panels card (Task 1 leaves it as a muted note appended by `renderPanels` — see step below).

### Steps

- [ ] **Rewrite `render()`.** Replace the whole existing function (currently lines ~300–314):

  Replace this exact block:
  ```js
  function render() {
    renderDevice();
    renderPanels();
    renderEntities();
    renderMedia();
    renderNotifications();
    renderHome();
    renderOptions();
    renderVoice();
    renderSendspin();
    renderNight();
    renderEv();
    renderCalendars();
    renderBackup();
  }
  ```
  with:
  ```js
  function render() {
    renderDevice();
    renderBackup();
    renderPanels();
    renderHome();
    renderNight();
    renderSensors();
    renderThermostats();
    renderLightGroups();
    renderQuickButtons();
    renderCameras();
    renderDoorbells();
    renderSolar();
    renderEv();
    renderCalendars();
    renderMedia();
    renderSendspin();
    renderVoice();
    renderNws();
    renderPush();
  }
  ```

- [ ] **Append the panel-bar note to `renderPanels()`.** The panel-bar sentence from the old options note now lives on the Panels card. At the very end of `renderPanels()` (immediately after the `ordered.forEach(...)` loop closes, i.e. after the existing final `});` and before the function's closing `}`), append one muted note. Insert this line as the last statement of `renderPanels`:
  ```js
    host.appendChild(el("div", "muted", "The panel bar auto-hides; swipe in from the right edge to bring it back for 8 s."));
  ```
  (Anchor: `renderPanels` currently ends with `    host.appendChild(row);\n  });\n}` — add the new `host.appendChild(...)` line after the `});` that closes the `forEach`, before the final `}`.)

- [ ] **Delete `renderEntities()` entirely** (currently lines ~400–520, from `function renderEntities() {` through its closing `}` after the `addDb` block that ends `host.appendChild(addDb);\n}`). Its contents are redistributed into the seven functions below.

- [ ] **Delete `renderOptions()` entirely** (currently lines ~731–741, `function renderOptions() { … }`). Its four rows move to Sensors/Thermostats/Doorbells and its note is split (above).

- [ ] **Delete `renderNotifications()` entirely** (currently lines ~537–614, `function renderNotifications() { … }`). It becomes `renderNws` + `renderPush`.

- [ ] **Add `renderSensors()`** (place where `renderEntities` was). Migrates the four sensor rows + rain note from the old Sensors subhead block, plus `forecastDays` and `sensorDecimals` from old options, plus the split forecast note:
  ```js
  function renderSensors() {
    const host = document.getElementById("sensors");
    clear(host);
    const e = config.entities;
    const o = config.panelOptions;

    host.appendChild(labeledRow("Temperature sensor",
      entityPicker(["sensor"], e.tempSensor, v => e.tempSensor = v)));
    host.appendChild(labeledRow("Weather",
      entityPicker(["weather"], e.weather, v => e.weather = v)));
    host.appendChild(labeledRow("Air quality (AQI)",
      entityPicker(["sensor"], e.aqiSensor, v => e.aqiSensor = v)));
    host.appendChild(labeledRow("Event rain sensor",
      entityPicker(["sensor"], e.rainEvent, v => e.rainEvent = v)));
    host.appendChild(el("div", "muted",
      "Event-rain total (resets to 0 when the rain event ends). While above 0, a rain pill shows the running total on the home screen."));

    host.appendChild(labeledRow("Forecast days", numberInput(o.forecastDays, v => o.forecastDays = Math.round(v))));
    host.appendChild(labeledRow("Sensor decimal places", numberInput(o.sensorDecimals, v => o.sensorDecimals = Math.round(v))));
    host.appendChild(el("div", "muted", "Forecast 1–5 (clamped on save)."));
  }
  ```

- [ ] **Add `renderThermostats()`.** Migrates the climate list + its add button from old entities, plus `thermostatStep` from old options, plus the split step note. Every internal `renderEntities()` becomes `renderThermostats()`:
  ```js
  function renderThermostats() {
    const host = document.getElementById("thermostats");
    clear(host);
    const e = config.entities;
    const o = config.panelOptions;

    e.climate.forEach((id, i) => {
      const row = el("div", "row");
      row.appendChild(entityPicker(["climate"], id, v => { if (v) e.climate[i] = v; else e.climate.splice(i, 1); renderThermostats(); }));
      const del = el("button", "ghost small danger", "Remove");
      del.type = "button";
      del.setAttribute("aria-label", "Remove thermostat");
      del.addEventListener("click", () => { e.climate.splice(i, 1); renderThermostats(); });
      row.appendChild(del);
      host.appendChild(row);
    });
    const addClimate = el("button", "add", "Add thermostat");
    addClimate.type = "button";
    addClimate.addEventListener("click", () => { e.climate.push(""); renderThermostats(); });
    host.appendChild(addClimate);

    host.appendChild(labeledRow("Thermostat step", numberInput(o.thermostatStep, v => o.thermostatStep = v)));
    host.appendChild(el("div", "muted", "Step 0.1–5.0 (clamped on save)."));
  }
  ```

- [ ] **Add `renderLightGroups()`.** Migrates the light-groups block; internal `renderEntities()` becomes `renderLightGroups()`:
  ```js
  function renderLightGroups() {
    const host = document.getElementById("lightgroups");
    clear(host);
    const e = config.entities;
    e.lightGroups.forEach((g, gi) => host.appendChild(renderLightGroup(g, gi)));
    const addGroup = el("button", "add", "Add group");
    addGroup.type = "button";
    addGroup.addEventListener("click", () => { e.lightGroups.push({ name: "New group", entities: [] }); renderLightGroups(); });
    host.appendChild(addGroup);
  }
  ```

- [ ] **Add `renderQuickButtons()`.** Migrates the quick-buttons block verbatim (it had no re-render callbacks — the name/entity change handlers just set values):
  ```js
  function renderQuickButtons() {
    const host = document.getElementById("quickbuttons");
    clear(host);
    const e = config.entities;
    if (!Array.isArray(e.quickButtons)) e.quickButtons = [];
    const quickButtons = e.quickButtons;
    while (quickButtons.length < 4) quickButtons.push({});
    quickButtons.slice(0, 4).forEach((slot, i) => {
      const box = el("div", "group");
      const head = el("div", "group-head");
      head.appendChild(el("span", "panel-name", "Button " + (i + 1)));
      box.appendChild(head);
      const name = el("input");
      name.value = slot.name || "";
      name.setAttribute("aria-label", "Button name");
      name.addEventListener("change", () => slot.name = name.value.trim());
      box.appendChild(labeledRow("Name", name));
      box.appendChild(labeledRow("Entity",
        entityPicker(["switch", "light", "input_boolean", "button", "script", "scene"],
          slot.entity, v => slot.entity = v)));
      host.appendChild(box);
    });
    host.appendChild(el("div", "muted",
      "Up to four tappable buttons on the home screen, below the EV and solar cards. Switches, lights, " +
      "and input booleans toggle and show live on/off; buttons, scripts, and scenes fire on tap. " +
      "Blank name uses the entity's name. Empty slots are dropped on save."));
  }
  ```

- [ ] **Add `renderCameras()`.** Migrates the cameras block; the add button re-renders cameras AND doorbells (preserve the coupling — see quirks):
  ```js
  function renderCameras() {
    const host = document.getElementById("cameras");
    clear(host);
    const e = config.entities;
    e.cameras.forEach((c, ci) => host.appendChild(renderCamera(c, ci)));
    const addCam = el("button", "add", "Add camera");
    addCam.type = "button";
    addCam.addEventListener("click", () => { e.cameras.push({ name: "New camera", entity: null, rtspUrl: null }); renderCameras(); renderDoorbells(); });
    host.appendChild(addCam);
    host.appendChild(el("div", "muted",
      "RTSP plays direct from Frigate/go2rtc (rtsp://host:8554/name) for sub-second latency; leave blank to stream through Home Assistant (HLS, ~5–10 s behind). Tip: prefer sub/fluent streams — the screen is 960×480."));
  }
  ```

- [ ] **Add `renderDoorbells()`.** Migrates the doorbells block, plus `doorbellPopupSeconds` from old options, plus the split popup note; internal `renderEntities()` becomes `renderDoorbells()`:
  ```js
  function renderDoorbells() {
    const host = document.getElementById("doorbells");
    clear(host);
    const e = config.entities;
    const o = config.panelOptions;
    e.doorbells.forEach((d, di) => host.appendChild(renderDoorbell(d, di)));
    const addDb = el("button", "add", "Add doorbell");
    addDb.type = "button";
    addDb.addEventListener("click", () => { e.doorbells.push({ trigger: null, camera: "" }); renderDoorbells(); });
    host.appendChild(addDb);
    host.appendChild(labeledRow("Doorbell popup (s)", numberInput(o.doorbellPopupSeconds, v => o.doorbellPopupSeconds = Math.round(v))));
    host.appendChild(el("div", "muted", "Doorbell popup 5–120 (clamped on save)."));
  }
  ```

- [ ] **Add `renderSolar()`.** Migrates the solar slots + arrays. Original had a single `subhead("solar","Solar")`; the card-head now supplies "Solar", so use two sub-heads to divide the two blocks (spec: "sensor slots + arrays A–D, with subheads"):
  ```js
  function renderSolar() {
    const host = document.getElementById("solar");
    clear(host);
    const e = config.entities;

    host.appendChild(subhead("solar", "Sensors"));
    const solarSlots = [["pv", "PV power"], ["load", "Home load"], ["grid", "Grid power"],
      ["pvToday", "PV today"], ["loadToday", "Load today"],
      ["gridImportToday", "Grid import today (kWh)"], ["gridExportToday", "Grid export today (kWh)"],
      ["battInToday", "Battery charged today (kWh)"], ["battOutToday", "Battery discharged today (kWh)"],
      ["battSoc", "Battery %"], ["battPower", "Battery power"]];
    solarSlots.forEach(([k, lbl]) => {
      host.appendChild(labeledRow(lbl, entityPicker(["sensor"], e.solar[k], v => e.solar[k] = v)));
    });
    host.appendChild(el("div", "muted",
      "Battery % and battery power add a solar card to the home screen (gauge shimmers green while charging, amber in reverse while discharging). " +
      "Battery power: negative = charging (evcc convention). Grid power: positive = importing."));

    host.appendChild(subhead("solar", "Arrays"));
    if (!Array.isArray(e.solar.arrays)) e.solar.arrays = [];
    const solarArrays = e.solar.arrays;
    while (solarArrays.length < 4) solarArrays.push({});
    solarArrays.slice(0, 4).forEach((slot, i) => {
      const box = el("div", "group");
      const head = el("div", "group-head");
      head.appendChild(el("span", "panel-name", "Array " + String.fromCharCode(65 + i)));
      box.appendChild(head);
      const name = el("input");
      name.value = slot.name || "";
      name.setAttribute("aria-label", "Array name");
      name.addEventListener("change", () => slot.name = name.value.trim());
      box.appendChild(labeledRow("Name", name));
      box.appendChild(labeledRow("PV power",
        entityPicker(["sensor"], slot.power, v => slot.power = v)));
      host.appendChild(box);
    });
    host.appendChild(el("div", "muted",
      "Per-array PV power (e.g. TigoMonitor sensor.solar_array_a–d) shows on the full-screen Solar panel only. " +
      "Blank name falls back to A–D. Empty slots are dropped on save."));
  }
  ```

- [ ] **Add `renderNws()`.** The NWS half of old `renderNotifications` (defensive defaults + four rows + note), verbatim:
  ```js
  function renderNws() {
    const host = document.getElementById("nws");
    clear(host);
    // Defensive defaults for configs saved before notifications existed (same pattern as Media/Night).
    if (!config.notifications) config.notifications = { nwsAlerts: null, nwsMinSeverity: "minor" };
    const n = config.notifications;
    if (n.nwsMinSeverity == null) n.nwsMinSeverity = "minor";
    if (n.autoDismiss == null) n.autoDismiss = "off";
    if (n.autoDismissSeconds == null) n.autoDismissSeconds = 300;

    // Same populated picker pattern as the AQI sensor: shared sensor datalist; blank -> null.
    host.appendChild(labeledRow("NWS alerts sensor",
      entityPicker(["sensor"], n.nwsAlerts, v => n.nwsAlerts = v)));

    const sev = el("select");
    SEVERITY_OPTIONS.forEach(([val, lbl]) => {
      const o = el("option", null, lbl); o.value = val;
      if (n.nwsMinSeverity === val) o.selected = true;
      sev.appendChild(o);
    });
    sev.addEventListener("change", () => n.nwsMinSeverity = sev.value);
    host.appendChild(labeledRow("Minimum severity", sev));

    const auto = el("select");
    AUTO_DISMISS_OPTIONS.forEach(([val, lbl]) => {
      const o = el("option", null, lbl); o.value = val;
      if (n.autoDismiss === val) o.selected = true;
      auto.appendChild(o);
    });
    auto.addEventListener("change", () => n.autoDismiss = auto.value);
    host.appendChild(labeledRow("Auto-dismiss", auto));

    const autoSecs = el("input"); autoSecs.type = "number"; autoSecs.min = 10; autoSecs.max = 7200;
    autoSecs.value = n.autoDismissSeconds;
    autoSecs.addEventListener("change", () => n.autoDismissSeconds = Math.round(parseFloat(autoSecs.value) || 300));
    host.appendChild(labeledRow("Auto-dismiss after (s)", autoSecs));

    host.appendChild(el("div", "muted",
      "Point this at the nws_alerts integration's sensor (e.g. sensor.nws_alerts_alerts) to show active " +
      "alerts under the weather; swipe left to dismiss. Only alerts at or above the minimum severity " +
      "appear (Minor = show all). Auto-dismiss removes rows at or below the chosen severity after the " +
      "set time; higher severities stay until swiped away."));
  }
  ```

- [ ] **Add `renderPush()`.** The token half of old `renderNotifications`, minus the inline subhead, plus the whole-card hide when no token:
  ```js
  function renderPush() {
    const host = document.getElementById("push");
    clear(host);
    // Rendered only when the app build exposes a notify token (older builds -> card hidden entirely).
    // lastStatus is populated in tryLoad() before render() runs, so the token is present on first paint.
    const token = lastStatus && lastStatus.notifyToken;
    document.getElementById("push-section").hidden = !token;
    if (!token) return;

    const tokenInput = el("input", "mono");
    tokenInput.readOnly = true;
    tokenInput.value = token;
    tokenInput.setAttribute("aria-label", "Notify token");
    tokenInput.addEventListener("focus", () => tokenInput.select());
    host.appendChild(labeledRow("Token", tokenInput));

    const yaml =
      'rest_command:\n' +
      '  echo_notify:\n' +
      '    url: "' + location.origin + '/api/notify"\n' +
      '    method: POST\n' +
      '    headers:\n' +
      '      authorization: "Bearer ' + token + '"\n' +
      '    content_type: "application/json"\n' +
      '    payload: >-\n' +
      '      {"title": {{ title | tojson }}, "message": {{ message | default(\'\') | tojson }},\n' +
      '       "severity": {{ severity | default(\'info\') | tojson }},\n' +
      '       "id": {{ id | default(\'\') | tojson }}, "timeout": {{ timeout | default(0) }}}';
    host.appendChild(el("pre", "yaml", yaml));

    host.appendChild(el("div", "muted",
      "Add this to configuration.yaml, then call rest_command.echo_notify from an automation " +
      "(title required; message/severity/id/timeout optional). Reusing an id updates that row; " +
      "timeout 0 or absent means it stays until dismissed. POST /api/notify/clear with " +
      "{\"id\":\"…\"} or {\"all\":true} removes rows."));
  }
  ```

- [ ] **Retarget `renderLightGroup(g, gi)`.** This shared helper is unchanged except every `renderEntities()` call becomes `renderLightGroups()` (there are seven: the two group-reorder handlers, the delete-group handler, the entity-change/remove handler, the two entity-reorder handlers, the entity-remove handler, and the add-entity handler). Replace the whole function with:
  ```js
  function renderLightGroup(g, gi) {
    const groups = config.entities.lightGroups;
    const box = el("div", "group");
    const head = el("div", "group-head");
    const name = el("input"); name.value = g.name; name.setAttribute("aria-label", "Group name");
    name.addEventListener("change", () => g.name = name.value.trim() || "Group");
    head.appendChild(name);
    head.appendChild(reorderButtons(
      gi !== 0, gi !== groups.length - 1,
      () => { const t = groups[gi]; groups[gi] = groups[gi - 1]; groups[gi - 1] = t; renderLightGroups(); },
      () => { const t = groups[gi]; groups[gi] = groups[gi + 1]; groups[gi + 1] = t; renderLightGroups(); },
    ));
    const del = el("button", "ghost small danger", "Delete");
    del.type = "button";
    del.setAttribute("aria-label", "Delete group");
    del.addEventListener("click", () => { groups.splice(gi, 1); renderLightGroups(); });
    head.appendChild(del);
    box.appendChild(head);

    g.entities.forEach((id, ei) => {
      const row = el("div", "row");
      row.appendChild(entityPicker(["light", "switch", "fan"], id, v => { if (v) g.entities[ei] = v; else g.entities.splice(ei, 1); renderLightGroups(); }));
      row.appendChild(reorderButtons(
        ei !== 0, ei !== g.entities.length - 1,
        () => { const t = g.entities[ei]; g.entities[ei] = g.entities[ei - 1]; g.entities[ei - 1] = t; renderLightGroups(); },
        () => { const t = g.entities[ei]; g.entities[ei] = g.entities[ei + 1]; g.entities[ei + 1] = t; renderLightGroups(); },
      ));
      const erm = el("button", "ghost small danger", "Remove");
      erm.type = "button";
      erm.setAttribute("aria-label", "Remove entity");
      erm.addEventListener("click", () => { g.entities.splice(ei, 1); renderLightGroups(); });
      row.appendChild(erm);
      box.appendChild(row);
    });
    const addEnt = el("button", "add", "Add entity");
    addEnt.type = "button";
    addEnt.addEventListener("click", () => { g.entities.push(""); renderLightGroups(); });
    box.appendChild(addEnt);
    return box;
  }
  ```

- [ ] **Retarget `renderCamera(c, ci)`.** Unchanged except the reorder handlers and the delete handler now re-render cameras AND doorbells (three `renderEntities()` → `renderCameras(); renderDoorbells();`). Replace the whole function with:
  ```js
  function renderCamera(c, ci) {
    const cams = config.entities.cameras;
    const box = el("div", "group");
    const head = el("div", "group-head");
    const name = el("input"); name.value = c.name; name.setAttribute("aria-label", "Camera name");
    name.addEventListener("change", () => c.name = name.value.trim());
    head.appendChild(name);
    head.appendChild(reorderButtons(
      ci !== 0, ci !== cams.length - 1,
      () => { const t = cams[ci]; cams[ci] = cams[ci - 1]; cams[ci - 1] = t; renderCameras(); renderDoorbells(); },
      () => { const t = cams[ci]; cams[ci] = cams[ci + 1]; cams[ci + 1] = t; renderCameras(); renderDoorbells(); },
    ));
    const del = el("button", "ghost small danger", "Delete");
    del.type = "button";
    del.setAttribute("aria-label", "Delete camera");
    del.addEventListener("click", () => { cams.splice(ci, 1); renderCameras(); renderDoorbells(); });
    head.appendChild(del);
    box.appendChild(head);

    box.appendChild(labeledRow("Camera entity", entityPicker(["camera"], c.entity, v => c.entity = v)));
    const rtsp = el("input"); rtsp.value = c.rtspUrl || ""; rtsp.placeholder = "rtsp://host:8554/name";
    rtsp.setAttribute("autocomplete", "off");
    rtsp.addEventListener("change", () => c.rtspUrl = rtsp.value.trim() || null);
    box.appendChild(labeledRow("RTSP URL", rtsp));
    return box;
  }
  ```
  > Note: `c.name` change stays without a re-render (matches today — the doorbell dropdown label only refreshes on add/remove/reorder, not on rename).

- [ ] **Retarget `renderDoorbell(d, di)`.** Unchanged except the single delete handler `renderEntities()` → `renderDoorbells()`. Replace the whole function with:
  ```js
  function renderDoorbell(d, di) {
    const dbs = config.entities.doorbells;
    const row = el("div", "row");
    row.appendChild(entityPicker(["binary_sensor", "event"], d.trigger, v => d.trigger = v));
    const sel = el("select");
    const none = el("option", null, "— camera —"); none.value = ""; sel.appendChild(none);
    config.entities.cameras.forEach(c => {
      const o = el("option", null, c.name); o.value = c.name;
      if (d.camera === c.name) o.selected = true;
      sel.appendChild(o);
    });
    sel.addEventListener("change", () => d.camera = sel.value);
    row.appendChild(sel);
    const del = el("button", "ghost small danger", "Remove");
    del.type = "button";
    del.setAttribute("aria-label", "Remove doorbell");
    del.addEventListener("click", () => { dbs.splice(di, 1); renderDoorbells(); });
    row.appendChild(del);
    return row;
  }
  ```

- [ ] **Replace the entire `<div class="content"> … </div>` block in `index.html`.** The current block spans from `<div class="content">` (currently line 66) through its matching `</div>` (currently line 294, immediately before `  </main>`). It contains, in order, the `setup-section`, `device-section`, `panels-section`, `entities-section`, `media-section`, `notifications-section`, `home-section`, `options-section`, `voice-section`, `sendspin-section`, `night-section`, `ev-section`, `calendars-section`, `backup-section` sections. Replace the whole block with the following flat list — the same `card-section`/`card-head` pattern throughout, existing sections copied verbatim, `entities`/`options`/`notifications` sections dropped, nine new sections added, laid out in final page order so Task 2 can wrap runs of them without reordering:

  ```html
    <div class="content">

      <section id="setup-section" class="card-section" hidden>
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3a9 9 0 1 0 9 9"/><path d="M12 3v6l4 2"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Connect to Home Assistant</h2>
            <p>Sign in through your Home Assistant to finish setup.</p>
          </div>
        </div>
        <form id="setup-form" class="setup">
          <div class="row">
            <label for="setup-url">Home Assistant URL</label>
            <span class="picker">
              <input id="setup-url" inputmode="url" autocomplete="off" placeholder="http://homeassistant.local:8123">
            </span>
          </div>
          <div class="setup-actions">
            <button id="setup-connect" type="submit" class="btn-primary">Connect</button>
          </div>
          <div id="setup-error" class="error" role="alert"></div>
        </form>
      </section>

      <section id="device-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0l-6.6-6.6A2 2 0 0 1 3.4 12.6V5a1.6 1.6 0 0 1 1.6-1.6h7.6a2 2 0 0 1 1.4.6l6.6 6.6a2 2 0 0 1 0 2.8Z"/>
              <circle cx="8" cy="8" r="1.4"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Device</h2>
            <p>How this device identifies itself to Home Assistant and on the network.</p>
          </div>
        </div>
        <div id="device"></div>
      </section>

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

      <section id="panels-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3.5" y="3.5" width="7" height="7" rx="1.8"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.8"/>
              <rect x="3.5" y="13.5" width="7" height="7" rx="1.8"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.8"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Panels</h2>
            <p>Choose which screens appear and the order they cycle in.</p>
          </div>
        </div>
        <div id="panels"></div>
      </section>

      <section id="home-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 11.5 12 4l8 7.5"/><path d="M6 10v10h12V10"/><path d="M10 20v-5h4v5"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Home screen</h2>
            <p>Idle behaviour, clock, and the photo slideshow.</p>
          </div>
        </div>
        <div id="home"></div>
      </section>

      <section id="night-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 0 1 9.5 4 7 7 0 1 0 20 14.5Z"/></svg>
          </span>
          <div class="card-titles">
            <h2>Night mode</h2>
            <p>Dim clock on a black screen when the room goes dark.</p>
          </div>
        </div>
        <div id="night"></div>
      </section>

      <section id="sensors-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M7 18a4 4 0 0 1-.4-8A5.5 5.5 0 0 1 17 9.2 3.6 3.6 0 0 1 16.8 18Z"/></svg>
          </span>
          <div class="card-titles">
            <h2>Sensors</h2>
            <p>Temperature, weather, air quality, and rain for the panels and home screen.</p>
          </div>
        </div>
        <div id="sensors"></div>
      </section>

      <section id="thermostats-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13.6V5a2 2 0 1 1 4 0v8.6a4 4 0 1 1-4 0Z"/><circle cx="12" cy="16" r="1.4" fill="currentColor" stroke="none"/></svg>
          </span>
          <div class="card-titles">
            <h2>Thermostats</h2>
            <p>Climate entities for the thermostat panel.</p>
          </div>
        </div>
        <div id="thermostats"></div>
      </section>

      <section id="lightgroups-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.8 10.6c.5.5.8 1 .8 1.6V16h6v-.8c0-.6.3-1.1.8-1.6A6 6 0 0 0 12 3Z"/></svg>
          </span>
          <div class="card-titles">
            <h2>Light groups</h2>
            <p>Grouped lights, switches, and fans for the lights panel.</p>
          </div>
        </div>
        <div id="lightgroups"></div>
      </section>

      <section id="quickbuttons-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.8 10.6c.5.5.8 1 .8 1.6V16h6v-.8c0-.6.3-1.1.8-1.6A6 6 0 0 0 12 3Z"/></svg>
          </span>
          <div class="card-titles">
            <h2>Quick buttons</h2>
            <p>Up to four tappable controls on the home screen.</p>
          </div>
        </div>
        <div id="quickbuttons"></div>
      </section>

      <section id="cameras-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="6.5" width="12" height="11" rx="2"/><path d="M15 10l6-3v10l-6-3Z"/></svg>
          </span>
          <div class="card-titles">
            <h2>Cameras</h2>
            <p>Live camera streams for the cameras panel.</p>
          </div>
        </div>
        <div id="cameras"></div>
      </section>

      <section id="doorbells-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="6.5" width="12" height="11" rx="2"/><path d="M15 10l6-3v10l-6-3Z"/></svg>
          </span>
          <div class="card-titles">
            <h2>Doorbells</h2>
            <p>Pop up a camera when a doorbell triggers.</p>
          </div>
        </div>
        <div id="doorbells"></div>
      </section>

      <section id="solar-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3.6"/><path d="M12 2.5v2.4M12 19.1v2.4M21.5 12h-2.4M4.9 12H2.5M18.4 5.6l-1.7 1.7M7.3 16.7l-1.7 1.7M18.4 18.4l-1.7-1.7M7.3 7.3 5.6 5.6"/></svg>
          </span>
          <div class="card-titles">
            <h2>Solar</h2>
            <p>Production, battery, and per-array sensors for the solar card and panel.</p>
          </div>
        </div>
        <div id="solar"></div>
      </section>

      <section id="ev-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 17v-4l2-4.5A2 2 0 0 1 7.8 7.3h6.4A2 2 0 0 1 16 8.5L18 13v4"/>
              <path d="M4 17h2M18 17h2"/><circle cx="7.5" cy="17" r="1.5"/><circle cx="16.5" cy="17" r="1.5"/>
              <path d="M12 8.5 11 11.5h2L11.8 14.5"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>EV charging</h2>
            <p>Home-screen card while a car charges — assign EVCC entities.</p>
          </div>
        </div>
        <div id="ev"></div>
      </section>

      <section id="calendars-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3.5" y="4.5" width="17" height="16" rx="2.5"/>
              <path d="M3.5 9h17"/><path d="M8 3v3"/><path d="M16 3v3"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Calendars</h2>
            <p>Home Assistant calendars for the home-screen card and agenda panel.</p>
          </div>
        </div>
        <div id="calendars"></div>
      </section>

      <section id="media-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M10.2 8.3 16 12l-5.8 3.7Z" fill="currentColor" stroke="none"/></svg>
          </span>
          <div class="card-titles">
            <h2>Media</h2>
            <p>Album art and track info for on-device playback.</p>
          </div>
        </div>
        <div id="media"></div>
      </section>

      <section id="sendspin-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20 11A8 8 0 0 0 6.3 6.3L4 8.6"/><path d="M4 4v4.6h4.6"/>
              <path d="M4 13a8 8 0 0 0 13.7 4.7L20 15.4"/><path d="M20 20v-4.6h-4.6"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Sendspin</h2>
            <p>Sample-accurate synced playback with other Music Assistant players.</p>
          </div>
        </div>
        <div id="sendspin"></div>
      </section>

      <section id="voice-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0"/><path d="M12 18v3"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Voice</h2>
            <p>Turn the dashboard into a Home Assistant voice satellite.</p>
          </div>
        </div>
        <div id="voice"></div>
      </section>

      <section id="nws-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Weather alerts</h2>
            <p>Active weather alerts shown under the weather.</p>
          </div>
        </div>
        <div id="nws"></div>
      </section>

      <section id="push-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Push from Home Assistant</h2>
            <p>Show notifications sent from a Home Assistant automation.</p>
          </div>
        </div>
        <div id="push"></div>
      </section>
    </div>
  ```
  > The nine new one-line card descriptions (`<p>`) are the only genuinely new copy this task introduces — required by the new-card pattern (these blocks were sub-heads inside catch-all cards before and had no descriptions). They are fixed verbatim above.

### Task 1 verification (run in `app/src/main/assets/config/`, all must pass before commit)

- [ ] `node --check app/src/main/assets/config/app.js` → no output (syntax OK).
- [ ] `grep -c 'renderEntities\|renderOptions\|renderNotifications' app/src/main/assets/config/app.js` → **`0`** (all three catch-alls gone, including internal calls).
- [ ] For each new host id, exactly one occurrence in index.html:
  `for id in sensors thermostats lightgroups quickbuttons cameras doorbells solar nws push; do echo -n "$id "; grep -c "id=\"$id\"" app/src/main/assets/config/index.html; done`
  → each prints `<id> 1` (note `cameras`, `sensors` etc. as `id="cameras"` match the host div, distinct from `id="cameras-section"`).
- [ ] Old ids gone: `grep -c 'id="entities"\|id="options"\|id="notifications"' app/src/main/assets/config/index.html` → **`0`**.
- [ ] **getElementById audit:** every literal id resolves to one element —
  `grep -o 'getElementById("[^"]*")' app/src/main/assets/config/app.js | sort -u` then confirm each id (except the dynamic `push-section`, which is a literal here too) has a matching `id="…"` in index.html. Expected new literals present: `sensors`, `thermostats`, `lightgroups`, `quickbuttons`, `cameras`, `doorbells`, `solar`, `nws`, `push`, `push-section`.
- [ ] `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] Commit: `git add -A && git commit` with message:
  ```
  refactor(web-config): split render fns into per-card sections

  Dissolve renderEntities/renderOptions into seven per-card render
  functions and split renderNotifications into renderNws + renderPush.
  Migrate the four Panel-options rows to their owner cards and move the
  panel-bar note to Panels. Page is still one long scroll — nav lands next.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

## Task 2 — Nav + pages (hash router, theme still blue)

Wrap the flat card sections into nine `<section class="page">` containers, add the `<nav id="nav">` with nine icon+label anchors, add the tiny nav module to `app.js`, and add the two-mode nav CSS (sticky sidebar ≥860px / sticky scrollable pill row below). Colors stay blue — Task 3 re-themes. The gate must stay green and the page must be fully navigable after this task.

### Files
- `app/src/main/assets/config/index.html` — replace the flat `<div class="content"> … </div>` (from Task 1) with a shell: `<nav id="nav">` + `<div class="pages">` holding nine `<section class="page" id="page-…" hidden>` wrappers, each holding its Task-1 card sections verbatim.
- `app/src/main/assets/config/app.js` — add `PAGES`, `currentPage()`, `showPage(key)`; add a `hashchange` listener at boot; add the `configured === false → #device` override + `showPage` call in `tryLoad()`; add navigation to the `completeSetup()` failure tail.
- `app/src/main/assets/config/style.css` — add `--topbar-h` token; change the `.content` rule; add the Navigation rule block (default = pill row) + the `@media (min-width: 860px)` sidebar block.

### Interfaces (Task 3 relies on these)
- CSS selectors that Task 3 re-colors: `.nav-item[aria-current="page"]` (phone + desktop), `.nav-item[aria-current="page"]::before` (desktop indicator bar). The two blue literals `rgba(58,110,165,.14)` live only in these nav rules after this task.
- `#page-<key>` and `#nav-<key>` ids exist for all nine keys; `showPage`/`currentPage`/`PAGES` exist.

### Preserve these quirks
- **Page containers never leave the DOM** — `showPage` toggles their `hidden` only. `#night-lux`, `#sendspin-status`, `#datalists` stay reachable by id while their page is hidden, so `updateNightLux`/`updateSendspinStatus`/the status poll keep working (spec's `[hidden]` contract).
- **Save must not change page or scroll.** `render()` refills host divs only; it must NOT call `showPage`. Page visibility persists across a save exactly because render never touches the `#page-*` `hidden` flags.
- **Setup/OAuth flow visibility:** `tryLoad`'s `completeSetup` branch `return`s before `render()`/`showPage`. On setup FAILURE, `completeSetup` re-renders but must also navigate to `#device` so the (now all-`hidden`) page containers reveal the Device page with the setup card. Add that navigation (step below). `renderSetup(show)` toggling `setup-section.hidden` is unchanged and independent of page visibility.

### Steps

- [ ] **index.html — replace the flat `.content` with the nav+pages shell.** Replace the entire `<div class="content"> … </div>` block (the Task-1 flat list) with the following. Every `<section id="…-section"> … </section>` inside a page is copied **verbatim** from Task 1 (shown here collapsed as reference comments to keep this block readable — paste the full section markup from the Task-1 `.content` in the same order). The wrapper markup (`nav`, `pages`, `page-*`) is literal:

  ```html
    <div class="content">
      <nav id="nav" aria-label="Configuration sections">
        <a id="nav-device" class="nav-item" href="#device">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0l-6.6-6.6A2 2 0 0 1 3.4 12.6V5a1.6 1.6 0 0 1 1.6-1.6h7.6a2 2 0 0 1 1.4.6l6.6 6.6a2 2 0 0 1 0 2.8Z"/><circle cx="8" cy="8" r="1.4"/></svg>
          <span class="nav-label">Device</span>
        </a>
        <a id="nav-screens" class="nav-item" href="#screens">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="3.5" width="7" height="7" rx="1.8"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.8"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.8"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.8"/></svg>
          <span class="nav-label">Screens</span>
        </a>
        <a id="nav-climate" class="nav-item" href="#climate">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13.6V5a2 2 0 1 1 4 0v8.6a4 4 0 1 1-4 0Z"/><circle cx="12" cy="16" r="1.4" fill="currentColor" stroke="none"/></svg>
          <span class="nav-label">Climate &amp; Weather</span>
        </a>
        <a id="nav-lights" class="nav-item" href="#lights">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.8 10.6c.5.5.8 1 .8 1.6V16h6v-.8c0-.6.3-1.1.8-1.6A6 6 0 0 0 12 3Z"/></svg>
          <span class="nav-label">Lights &amp; Buttons</span>
        </a>
        <a id="nav-cameras" class="nav-item" href="#cameras">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="6.5" width="12" height="11" rx="2"/><path d="M15 10l6-3v10l-6-3Z"/></svg>
          <span class="nav-label">Cameras</span>
        </a>
        <a id="nav-energy" class="nav-item" href="#energy">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3.6"/><path d="M12 2.5v2.4M12 19.1v2.4M21.5 12h-2.4M4.9 12H2.5M18.4 5.6l-1.7 1.7M7.3 16.7l-1.7 1.7M18.4 18.4l-1.7-1.7M7.3 7.3 5.6 5.6"/></svg>
          <span class="nav-label">Energy</span>
        </a>
        <a id="nav-calendars" class="nav-item" href="#calendars">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="4.5" width="17" height="16" rx="2.5"/><path d="M3.5 9h17"/><path d="M8 3v3"/><path d="M16 3v3"/></svg>
          <span class="nav-label">Calendars</span>
        </a>
        <a id="nav-media" class="nav-item" href="#media">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M10.2 8.3 16 12l-5.8 3.7Z" fill="currentColor" stroke="none"/></svg>
          <span class="nav-label">Media &amp; Voice</span>
        </a>
        <a id="nav-alerts" class="nav-item" href="#alerts">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/></svg>
          <span class="nav-label">Alerts</span>
        </a>
      </nav>

      <div class="pages">
        <section class="page" id="page-device" hidden>
          <!-- setup-section (verbatim from Task 1) -->
          <!-- device-section (verbatim from Task 1) -->
          <!-- backup-section (verbatim from Task 1) -->
        </section>
        <section class="page" id="page-screens" hidden>
          <!-- panels-section (verbatim from Task 1) -->
          <!-- home-section (verbatim from Task 1) -->
          <!-- night-section (verbatim from Task 1) -->
        </section>
        <section class="page" id="page-climate" hidden>
          <!-- sensors-section (verbatim from Task 1) -->
          <!-- thermostats-section (verbatim from Task 1) -->
        </section>
        <section class="page" id="page-lights" hidden>
          <!-- lightgroups-section (verbatim from Task 1) -->
          <!-- quickbuttons-section (verbatim from Task 1) -->
        </section>
        <section class="page" id="page-cameras" hidden>
          <!-- cameras-section (verbatim from Task 1) -->
          <!-- doorbells-section (verbatim from Task 1) -->
        </section>
        <section class="page" id="page-energy" hidden>
          <!-- solar-section (verbatim from Task 1) -->
          <!-- ev-section (verbatim from Task 1) -->
        </section>
        <section class="page" id="page-calendars" hidden>
          <!-- calendars-section (verbatim from Task 1) -->
        </section>
        <section class="page" id="page-media" hidden>
          <!-- media-section (verbatim from Task 1) -->
          <!-- sendspin-section (verbatim from Task 1) -->
          <!-- voice-section (verbatim from Task 1) -->
        </section>
        <section class="page" id="page-alerts" hidden>
          <!-- nws-section (verbatim from Task 1) -->
          <!-- push-section (verbatim from Task 1) -->
        </section>
      </div>
    </div>
  ```
  > The `<!-- …-section (verbatim from Task 1) -->` comments are **not** literal output — replace each with the full `<section id="…-section" class="card-section"> … </section>` block exactly as written in Task 1's `.content`, preserving its `hidden` attribute (only `setup-section` has one). Do not alter any card markup; only the surrounding `page` wrappers and the `nav` are new. Distinctive anchor to confirm you removed the right thing: the flat `.content` began `<div class="content">` immediately followed by `<section id="setup-section" class="card-section" hidden>`; it must now begin `<div class="content">` followed by `<nav id="nav" aria-label="Configuration sections">`.

- [ ] **app.js — add the nav module.** Insert this block immediately before the `// ---------- render ----------` comment (i.e. just above `function render()`):
  ```js
  // ---------- nav (hash-routed pages) ----------
  const PAGES = ["device", "screens", "climate", "lights", "cameras", "energy", "calendars", "media", "alerts"];

  function currentPage() {
    const key = (location.hash || "").replace(/^#/, "");
    return PAGES.includes(key) ? key : "device";
  }

  function showPage(key) {
    PAGES.forEach(k => {
      const page = document.getElementById("page-" + k);
      if (page) page.hidden = (k !== key);
      const item = document.getElementById("nav-" + k);
      if (item) {
        if (k === key) item.setAttribute("aria-current", "page");
        else item.removeAttribute("aria-current");
      }
    });
    const active = document.getElementById("nav-" + key);
    if (active && active.scrollIntoView) active.scrollIntoView({ inline: "nearest", block: "nearest" });
    window.scrollTo(0, 0);
  }
  ```

- [ ] **app.js — wire `tryLoad()` to navigate.** In `tryLoad`, replace this exact block:
  ```js
      renderSetup(status.configured === false);
      render();
      startStatusPoll();
      setStatus("Connected", "ok");
  ```
  with:
  ```js
      renderSetup(status.configured === false);
      render();
      if (status.configured === false) location.hash = "#device";
      showPage(currentPage());
      startStatusPoll();
      setStatus("Connected", "ok");
  ```

- [ ] **app.js — navigate on setup failure.** In `completeSetup`, replace this exact tail block:
  ```js
    // On failure the params stay in the URL; show the card so the user can retry Connect.
    renderSetup(true);
    render();
    setStatus("Setup failed", "err");
  ```
  with:
  ```js
    // On failure the params stay in the URL; show the card so the user can retry Connect.
    renderSetup(true);
    render();
    location.hash = "#device";
    showPage(currentPage());
    setStatus("Setup failed", "err");
  ```

- [ ] **app.js — add the `hashchange` listener at boot.** In the boot block at the end of the file, insert the listener before `tryLoad();`. Replace:
  ```js
  document.getElementById("login-form").addEventListener("submit", doLogin);
  document.getElementById("save").addEventListener("click", save);
  document.getElementById("setup-form").addEventListener("submit", beginSetup);
  tryLoad();
  ```
  with:
  ```js
  document.getElementById("login-form").addEventListener("submit", doLogin);
  document.getElementById("save").addEventListener("click", save);
  document.getElementById("setup-form").addEventListener("submit", beginSetup);
  window.addEventListener("hashchange", () => showPage(currentPage()));
  tryLoad();
  ```

- [ ] **style.css — add the `--topbar-h` token.** In `:root`, after the `--radius-sm: 12px;` line and before the closing `}`, add:
  ```css
  --topbar-h:  3.8rem;
  ```
  (Approximates the sticky topbar's rendered height; the nav sticks just below it. One value, adjustable here.)

- [ ] **style.css — change the `.content` rule.** Replace:
  ```css
  .content { max-width: 46rem; margin: 0 auto; padding: 1.1rem 1rem 4rem; }
  ```
  with:
  ```css
  .content { max-width: 46rem; margin: 0 auto; padding: 0 1rem 4rem; }
  ```

- [ ] **style.css — add the Navigation section.** Insert this block immediately after the `.content { … }` rule (before the `Cards` section comment):
  ```css
  /* =========================================================
     Navigation (scrollable pill row; sidebar ≥860px)
     ========================================================= */
  #nav {
    position: sticky; top: var(--topbar-h); z-index: 15;
    display: flex; flex-wrap: nowrap; gap: .4rem;
    margin: 0 -1rem; padding: .55rem 1rem;
    overflow-x: auto; overflow-y: hidden;
    background: rgba(15,20,32,.92);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    scrollbar-width: none; -ms-overflow-style: none;
  }
  #nav::-webkit-scrollbar { width: 0; height: 0; display: none; }

  .nav-item {
    flex: 0 0 auto;
    display: inline-flex; align-items: center; gap: .4rem;
    padding: .45rem .75rem; border-radius: 999px;
    text-decoration: none; white-space: nowrap;
    font-weight: 700; font-size: .85rem;
    color: var(--text-mid);
    background: var(--card-2);
    border: 1px solid var(--line-soft);
    transition: background .15s ease, color .15s ease, border-color .15s ease;
  }
  .nav-item svg { width: 1.05rem; height: 1.05rem; flex: none; }
  .nav-item:hover { color: var(--text); border-color: var(--line); }
  .nav-item[aria-current="page"] {
    color: var(--accent-hi);
    background: rgba(58,110,165,.14);
    border-color: var(--accent);
  }

  .pages { min-width: 0; }

  @media (min-width: 860px) {
    .content {
      display: flex; align-items: flex-start; gap: 1.4rem;
      max-width: 62rem;
    }
    #nav {
      position: sticky; top: calc(var(--topbar-h) + .2rem); z-index: 10;
      flex: 0 0 13rem; flex-direction: column; gap: .15rem;
      margin: 0; padding: .2rem 0 0;
      overflow: visible;
      background: none;
      backdrop-filter: none; -webkit-backdrop-filter: none;
    }
    .nav-item {
      position: relative; width: 100%;
      gap: .6rem; padding: .5rem .7rem; font-size: .92rem;
      background: none; border-color: transparent; border-radius: 10px;
    }
    .nav-item svg { width: 1.2rem; height: 1.2rem; }
    .nav-item:hover { background: var(--card-2); border-color: transparent; color: var(--text); }
    .nav-item[aria-current="page"] {
      background: rgba(58,110,165,.14);
      border-color: transparent;
    }
    .nav-item[aria-current="page"]::before {
      content: ""; position: absolute; left: 0; top: 50%;
      transform: translateY(-50%);
      width: 3px; height: 1.4rem; border-radius: 0 3px 3px 0;
      background: var(--accent);
    }
    .pages { flex: 1 1 auto; min-width: 0; max-width: 46rem; }
  }
  ```

### Task 2 verification (all must pass before commit)

- [ ] `node --check app/src/main/assets/config/app.js` → no output.
- [ ] All nine page containers present:
  `for k in device screens climate lights cameras energy calendars media alerts; do echo -n "$k "; grep -c "id=\"page-$k\"" app/src/main/assets/config/index.html; done` → each `<k> 1`.
- [ ] All nine nav anchors present:
  `for k in device screens climate lights cameras energy calendars media alerts; do echo -n "$k "; grep -c "id=\"nav-$k\"" app/src/main/assets/config/index.html; done` → each `<k> 1`.
- [ ] `grep -c 'class="page"' app/src/main/assets/config/index.html` → **`9`**.
- [ ] `grep -c 'showPage\|currentPage\|PAGES' app/src/main/assets/config/app.js` → ≥ **`8`** (const + 2 defs + tryLoad + completeSetup + hashchange + boot).
- [ ] getElementById audit still passes (every literal id present in index.html; `page-*`/`nav-*` covered by the two loops above).
- [ ] The two blue nav literals exist for Task 3: `grep -c 'rgba(58,110,165,.14)' app/src/main/assets/config/style.css` → **`2`**.
- [ ] `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] Commit:
  ```
  feat(web-config): hash-routed pages behind sidebar/pill nav

  Wrap the card sections into nine #page-* containers driven by a tiny
  hash router (PAGES/currentPage/showPage + hashchange). Nav renders as a
  sticky sidebar ≥860px and a scrollable pill row below. Unconfigured
  devices are forced to #device. Theme still blue.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

## Task 3 — Ember theme (style.css only)

Swap the two accent tokens, add `--ink`/`--flame`, and replace every blue literal the spec calls out with its warm equivalent. `index.html` and `app.js` are untouched. Ground dark navy and all structural navy (fields, borders, ghost/danger buttons, chips, `.status.info`, `.pinned`, placeholders) stay as-is — only accent-bearing surfaces go warm. The gate must stay green.

### Files
- `app/src/main/assets/config/style.css` — token + literal color swaps below. No other file changes.

### Interfaces
- None consumed downstream (final task). `--accent`/`--accent-hi` token values change, warming everything that already references them (`.card-head .ic` glyph, `.add` text, `.status.busy`, focus outlines, `.nav-item[aria-current]` text/border) with no per-rule edit.

### Steps (each is an exact find/replace in style.css)

- [ ] **Tokens.** Replace:
  ```css
    --accent:    #3a6ea5;
    --accent-hi: #7fb2ff;
  ```
  with:
  ```css
    --accent:    #EF6A17;
    --accent-hi: #F8B62D;
    --ink:       #221204;
    --flame:     linear-gradient(180deg, #F8B62D, #EF6A17);
  ```

- [ ] **Body ambient glow.** Replace:
  ```css
      radial-gradient(120% 80% at 50% -10%, rgba(58,110,165,.18), transparent 60%),
      radial-gradient(90% 60% at 100% 0%, rgba(127,178,255,.06), transparent 55%);
  ```
  with:
  ```css
      radial-gradient(120% 80% at 50% -10%, rgba(239,106,23,.13), transparent 60%),
      radial-gradient(90% 60% at 100% 0%, rgba(248,182,45,.05), transparent 55%);
  ```

- [ ] **Login overlay glow.** Replace:
  ```css
    background-image: radial-gradient(120% 90% at 50% -20%, rgba(58,110,165,.22), transparent 60%);
  ```
  with:
  ```css
    background-image: radial-gradient(120% 90% at 50% -20%, rgba(239,106,23,.13), transparent 60%);
  ```

- [ ] **Card icon tile (`.ic`).** Replace:
  ```css
    color: var(--accent-hi);
    background: linear-gradient(160deg, #21324f, #182234);
    border: 1px solid #2a3a58;
  ```
  with:
  ```css
    color: var(--accent-hi);
    background: linear-gradient(160deg, #3d2412, #241710);
    border: 1px solid #4d2f16;
  ```

- [ ] **Subhead color (warm-biased neutral).** In the `.subhead` rule, `letter-spacing: .1em;` is the tail of a longer line; anchor on that full line + the `color` line (unique to `.subhead`; the other `#8aa0c4` in `.status.info` stays). Replace:
  ```css
    font-size: .74rem; font-weight: 800; text-transform: uppercase; letter-spacing: .1em;
    color: #8aa0c4;
  ```
  with:
  ```css
    font-size: .74rem; font-weight: 800; text-transform: uppercase; letter-spacing: .1em;
    color: #c0a88b;
  ```

- [ ] **Select chevron stroke.** In the `select` rule's `background-image` data-URI, replace `stroke='%237fb2ff'` with `stroke='%23F8B62D'`. (Anchor the full line: `  background-image: url("data:image/svg+xml,…stroke='%237fb2ff'…");` — change only the `%237fb2ff` token.)

- [ ] **Input/select focus ring.** Replace:
  ```css
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(58,110,165,.28);
  ```
  with:
  ```css
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(239,106,23,.30);
  ```

- [ ] **Toggle checked track (flame gradient).** Replace:
  ```css
  input[type="checkbox"]:checked {
    background: linear-gradient(180deg, #4a83c0, var(--accent));
    border-color: var(--accent);
  }
  ```
  with:
  ```css
  input[type="checkbox"]:checked {
    background: var(--flame);
    border-color: var(--accent);
  }
  ```

- [ ] **Base button ink.** Replace:
  ```css
    padding: .5rem .9rem; font-size: .9rem; color: #fff;
    background: var(--accent);
  ```
  with:
  ```css
    padding: .5rem .9rem; font-size: .9rem; color: var(--ink);
    background: var(--accent);
  ```

- [ ] **Primary button (flame + warm shadow + warm hover).** Replace:
  ```css
  .btn-primary {
    background: linear-gradient(180deg, #4a83c0, var(--accent));
    box-shadow: 0 8px 18px -8px rgba(58,110,165,.8), inset 0 1px 0 rgba(255,255,255,.12);
    padding: .55rem 1.15rem;
  }
  .btn-primary:hover { background: linear-gradient(180deg, #5591cf, #3f76ad); }
  ```
  with:
  ```css
  .btn-primary {
    background: var(--flame);
    box-shadow: 0 8px 18px -8px rgba(239,106,23,.45), inset 0 1px 0 rgba(255,255,255,.12);
    padding: .55rem 1.15rem;
  }
  .btn-primary:hover { background: linear-gradient(180deg, #FFCE49, #EF6A17); }
  ```
  (`.btn-primary` text is inherited from the base `button` rule, now `var(--ink)` — dark ink on flame passes contrast where white failed.)

- [ ] **Add-row dashed button.** Replace the border line:
  ```css
    border: 1px dashed #35415a; border-radius: 11px;
  ```
  with:
  ```css
    border: 1px dashed #4a3220; border-radius: 11px;
  ```
  and replace the hover rule:
  ```css
  .add:hover { border-color: var(--accent); background: rgba(58,110,165,.12); color: #fff; }
  ```
  with:
  ```css
  .add:hover { border-color: var(--accent); background: rgba(239,106,23,.12); color: var(--accent-hi); }
  ```

- [ ] **Panel-row tile (`.ptile`).** Replace:
  ```css
    color: var(--accent-hi); background: #212a3d; border: 1px solid #2b3650;
  ```
  with:
  ```css
    color: var(--accent-hi); background: linear-gradient(160deg, #3d2412, #241710); border: 1px solid #4d2f16;
  ```

- [ ] **Nav active fill — phone.** Replace:
  ```css
  .nav-item[aria-current="page"] {
    color: var(--accent-hi);
    background: rgba(58,110,165,.14);
    border-color: var(--accent);
  }
  ```
  with:
  ```css
  .nav-item[aria-current="page"] {
    color: var(--accent-hi);
    background: rgba(239,106,23,.12);
    border-color: var(--accent);
  }
  ```

- [ ] **Nav active fill + indicator bar — desktop (inside `@media (min-width: 860px)`).** Replace:
  ```css
    .nav-item[aria-current="page"] {
      background: rgba(58,110,165,.14);
      border-color: transparent;
    }
    .nav-item[aria-current="page"]::before {
      content: ""; position: absolute; left: 0; top: 50%;
      transform: translateY(-50%);
      width: 3px; height: 1.4rem; border-radius: 0 3px 3px 0;
      background: var(--accent);
    }
  ```
  with:
  ```css
    .nav-item[aria-current="page"] {
      background: rgba(239,106,23,.12);
      border-color: transparent;
    }
    .nav-item[aria-current="page"]::before {
      content: ""; position: absolute; left: 0; top: 50%;
      transform: translateY(-50%);
      width: 3px; height: 1.4rem; border-radius: 0 3px 3px 0;
      background: linear-gradient(180deg, #F8B62D, #EF6A17, #BD2F0B);
    }
  ```

### Task 3 verification (all must pass before commit)

- [ ] `node --check app/src/main/assets/config/app.js` → no output (app.js unchanged, sanity only).
- [ ] No stray blue accent literals remain: `grep -nE 'rgba\(58,110,165|rgba\(127,178,255|7fb2ff|3a6ea5|4a83c0|5591cf|3f76ad|21324f|182234|2a3a58|2b3650|35415a' app/src/main/assets/config/style.css` → **no matches** (all warmed/removed). NB: `#212a3d` is intentionally NOT in this list — it also lives in the unchanged `.chip` rule (only `.ptile`'s copy was rewritten), so grepping it would false-fail.
- [ ] Warm tokens present: `grep -c 'EF6A17\|F8B62D\|--ink\|--flame' app/src/main/assets/config/style.css` → ≥ **`4`**.
- [ ] `.status.info` untouched: `grep -c 'status.info { color: #8aa0c4' app/src/main/assets/config/style.css` → **`1`** (semantic neutral preserved).
- [ ] `git diff --name-only` → only `app/src/main/assets/config/style.css`.
- [ ] `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] Commit:
  ```
  feat(web-config): ember theme (flame accent over dark navy)

  Swap the blue accent tokens for the logo flame palette (#EF6A17 /
  #F8B62D) and add --ink/--flame. Buttons, toggles, focus rings, icon
  tiles, nav active state, and ambient glow go warm; ground navy and
  semantic ok/err colors are unchanged.

  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

## Manual verification checklist (executing lead runs after Task 3 — not a task)

Flash the built APK to a device and open the config page in a desktop browser and a phone browser:

- [ ] Log in with the device PIN → app shell appears with the topbar (brand, status pill, Save) and the ember accent (orange Save/Unlock, warm glow).
- [ ] Desktop (≥860px): a sticky left sidebar lists all nine pages with icons; clicking each shows exactly that page's cards; the active item has the warm fill + the 3px flame indicator bar on its leading edge. Browser back/forward moves between visited pages.
- [ ] Phone (<860px, or narrow the window): the nav is a horizontally scrollable pill row under the topbar; the active pill scrolls into view; each pill shows icon + label; active pill has the warm fill + accent border.
- [ ] Each page renders its cards: Device (Device + Backup; setup card only if unconfigured) · Screens (Panels + Home + Night) · Climate & Weather (Sensors incl. Forecast days / Sensor decimals; Thermostats incl. Thermostat step) · Lights & Buttons (Light groups + Quick buttons) · Cameras (Cameras + Doorbells incl. Doorbell popup) · Energy (Solar with Sensors/Arrays sub-heads + EV) · Calendars · Media & Voice (Media + Sendspin + Voice) · Alerts (Weather alerts + Push — Push absent if the build has no notify token).
- [ ] Panels card shows the "panel bar auto-hides; swipe in from the right edge…" note.
- [ ] Toggles (Night clock, Voice, Sendspin, panel enables, slideshow) show the flame track when on; inputs/selects show the warm focus ring; the select chevron is amber.
- [ ] From a non-default page (e.g. Media & Voice), edit a field and press Save → "Saved"; the page does NOT jump or scroll and the current page stays selected. All cards (hidden pages included) reflect the server's clamped values.
- [ ] On the Cameras page: Add camera → the new camera immediately appears in each doorbell's camera dropdown; Delete camera → it disappears from the dropdowns.
- [ ] Night page shows the live "Current reading: … lux" line and it keeps updating (5s poll) even after navigating away and back; Sendspin page shows the live "Status: …" line.
- [ ] Unconfigured build only (skip if none available): loading any hash forces the Device page with the setup card front and center; completing OAuth returns and hides the setup card; a setup failure returns to the Device page with the card visible.

## Resolved ambiguities (spec gaps I closed while planning)

- **Split of the combined "Panel options" clamp note.** The spec says split it "so each fragment sits beside its migrated row" but gives no exact strings. Chosen fragments: Thermostats → `"Step 0.1–5.0 (clamped on save)."`; Sensors → `"Forecast 1–5 (clamped on save)."`; Doorbells → `"Doorbell popup 5–120 (clamped on save)."`; panel-bar sentence verbatim → Panels card. (`sensorDecimals` had no clamp in the original note, so it gets none — matches original behavior.)
- **Solar sub-heads.** Spec says "sensor slots + arrays A–D, with subheads" but not the labels. The card-head already says "Solar", so I used two `subhead("solar", …)` calls with labels **"Sensors"** and **"Arrays"** to divide the two blocks (the original had one "Solar" sub-head; keeping it would duplicate the card title).
- **New card one-line descriptions.** The new-card pattern needs a `<p>` description that none of these blocks had (they were sub-heads inside catch-all cards). Wrote nine short factual descriptions, fixed verbatim in Task 1.
- **New card-head icons.** Reused existing glyph art faithful to the original sub-head glyphs: Sensors=weather cloud, Thermostats=thermometer, Light groups & Quick buttons=bulb, Cameras & Doorbells=camera, Solar=sun, Weather alerts & Push=bell. (Nav icons follow the spec's explicit list.)
- **Camera→doorbell re-render coupling.** The spec's rule "row-level re-renders call the owning function" would drop the old behavior where changing cameras refreshed the doorbell camera dropdown (both were under `renderEntities`). Preserved it by having camera add/delete/reorder call `renderCameras(); renderDoorbells();` — behavior-preservation overrides the general rule.
- **Push card hidden when no token.** Implemented as `push-section.hidden = !token` inside `renderPush` (spec: "card hidden entirely when the build exposes no notify token").
- **`btn-primary` hover + nav sticky offset.** Spec fixes the resting flame/shadow but not the hover; used a brighter inner-flame `linear-gradient(180deg, #FFCE49, #EF6A17)`. Nav sticky offset uses a single `--topbar-h: 3.8rem` token approximating the topbar height (exact height varies with topbar wrap on very narrow screens; adjustable in one place).
- **`.status.info` (`#8aa0c4`) left unchanged.** It is a neutral info gray-blue not in the spec's enumerated warm list; only `.subhead` moves to `#c0a88b`.
