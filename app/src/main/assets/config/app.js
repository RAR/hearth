"use strict";

let config = null;      // the live DashConfig model (source of truth)
let entities = [];      // [{id, name, domain, state}]
let lastStatus = null;  // most recent /api/status body (carries the live lux reading)
let statusPollStarted = false;
let dlSeq = 0;
let idSeq = 0;
function nextId(prefix) { return prefix + "-" + (++idSeq); }

// OAuth callback params captured once at load, before any history.replaceState.
const _sp = new URLSearchParams(location.search);
const setupCode = _sp.get("code");
const setupState = _sp.get("state");
let setupAttempted = false;

const PANEL_KEYS = ["lights", "climate", "media", "weather", "solar", "cameras"];
const PANEL_LABELS = {
  lights: "Lights", climate: "Climate", media: "Media", weather: "Weather", solar: "Solar", cameras: "Cameras",
};

const TONE_OPTIONS = [
  ["twotone", "Two-tone"],
  ["beeps", "Beeps"],
  ["chime", "Chime"],
  ["trill", "Trill"],
];

// ---------- inline SVG glyphs (currentColor) ----------
const ICONS = {
  lights: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.8 10.6c.5.5.8 1 .8 1.6V16h6v-.8c0-.6.3-1.1.8-1.6A6 6 0 0 0 12 3Z"/></svg>',
  climate: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13.6V5a2 2 0 1 1 4 0v8.6a4 4 0 1 1-4 0Z"/><circle cx="12" cy="16" r="1.4" fill="currentColor" stroke="none"/></svg>',
  media: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M10.2 8.3 16 12l-5.8 3.7Z" fill="currentColor" stroke="none"/></svg>',
  weather: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M7 18a4 4 0 0 1-.4-8A5.5 5.5 0 0 1 17 9.2 3.6 3.6 0 0 1 16.8 18Z"/></svg>',
  solar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3.6"/><path d="M12 2.5v2.4M12 19.1v2.4M21.5 12h-2.4M4.9 12H2.5M18.4 5.6l-1.7 1.7M7.3 16.7l-1.7 1.7M18.4 18.4l-1.7-1.7M7.3 7.3 5.6 5.6"/></svg>',
  cameras: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="6.5" width="12" height="11" rx="2"/><path d="M15 10l6-3v10l-6-3Z"/></svg>',
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 11.5 12 4l8 7.5"/><path d="M6 10v10h12V10"/><path d="M10 20v-5h4v5"/></svg>',
  ev: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 17v-4l2-4.5A2 2 0 0 1 7.8 7.3h6.4A2 2 0 0 1 16 8.5L18 13v4"/><path d="M4 17h2M18 17h2"/><circle cx="7.5" cy="17" r="1.5"/><circle cx="16.5" cy="17" r="1.5"/><path d="M12 8.5 11 11.5h2L11.8 14.5"/></svg>',
};

// ---------- tiny DOM helpers ----------
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}
function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
function glyph(name, cls) {
  const s = el("span", cls);
  s.innerHTML = ICONS[name] || "";
  s.setAttribute("aria-hidden", "true");
  return s;
}
function subhead(name, text) {
  const h = el("h3", "subhead");
  h.appendChild(glyph(name));
  h.appendChild(document.createTextNode(text));
  return h;
}
function setStatus(msg, kind) {
  const s = document.getElementById("status");
  s.textContent = msg || "";
  s.className = "status " + (kind || "info");
}

// ---------- auth + data ----------
async function api(method, path, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
  return fetch(path, opts);
}

async function tryLoad() {
  try {
    const r = await api("GET", "/api/config");
    if (r.status === 401) { showLogin(); return; }
    config = await r.json();
    const er = await api("GET", "/api/entities");
    entities = er.ok ? await er.json() : [];
    const sr = await api("GET", "/api/status");
    const status = sr.ok ? await sr.json() : { configured: true };
    lastStatus = status;
    showApp();
    // If HA just redirected back with a code and setup isn't done yet, finish it once.
    if (setupCode && setupState && status.configured === false && !setupAttempted) {
      setupAttempted = true;
      await completeSetup();
      return;
    }
    renderSetup(status.configured === false);
    render();
    startStatusPoll();
    setStatus("Connected", "ok");
  } catch (e) {
    showLogin();
    document.getElementById("login-error").textContent = "Can't reach the device — is it on and connected?";
  }
}

function showLogin() {
  document.getElementById("login").hidden = false;
  document.getElementById("app").hidden = true;
}
function showApp() {
  document.getElementById("login").hidden = true;
  document.getElementById("app").hidden = false;
}

async function doLogin(ev) {
  ev.preventDefault();
  const pin = document.getElementById("pin").value.trim();
  const errBox = document.getElementById("login-error");
  errBox.textContent = "";
  try {
    const r = await api("POST", "/api/login", { pin });
    if (r.ok) { await tryLoad(); return; }
    if (r.status === 429) {
      const b = await r.json().catch(() => ({}));
      errBox.textContent = "Too many attempts. Try again in " + (b.retryAfter || 60) + "s.";
    } else {
      errBox.textContent = "Wrong PIN.";
    }
  } catch (e) {
    errBox.textContent = "Can't reach the device — is it on and connected?";
  }
}

async function save() {
  setStatus("Saving…", "busy");
  try {
    const r = await api("PUT", "/api/config", config);
    if (r.ok) {
      config = await r.json();  // adopt the server's clamped copy
      render();
      setStatus("Saved", "ok");
    } else if (r.status === 401) {
      showLogin();
    } else {
      const b = await r.json().catch(() => ({}));
      setStatus("Error: " + (b.error || r.status), "err");
    }
  } catch (e) {
    setStatus("Can't reach the device — changes not saved.", "err");
  }
}

function renderSetup(show) {
  document.getElementById("setup-section").hidden = !show;
}

function showSetupError(msg) {
  document.getElementById("setup-error").textContent = msg || "";
}

async function beginSetup(ev) {
  ev.preventDefault();
  const haUrl = document.getElementById("setup-url").value.trim();
  showSetupError("");
  if (!haUrl) { showSetupError("Enter your Home Assistant URL."); return; }
  const btn = document.getElementById("setup-connect");
  btn.disabled = true;
  try {
    const r = await api("POST", "/api/setup/begin", { haUrl, clientId: location.origin + "/" });
    if (r.ok) {
      const b = await r.json();
      location.assign(b.authorizeUrl);   // hand off to Home Assistant's login
      return;
    }
    if (r.status === 401) { showLogin(); return; }
    const b = await r.json().catch(() => ({}));
    showSetupError(b.error || ("Couldn't start setup (" + r.status + ")"));
  } catch (e) {
    showSetupError("Can't reach the device — is it on and connected?");
  } finally {
    // Always re-enable, even on the 401→login path — otherwise the card comes back
    // after re-login with Connect stuck disabled. Harmless on the navigate-away path.
    btn.disabled = false;
  }
}

async function completeSetup() {
  setStatus("Finishing setup…", "busy");
  try {
    const r = await api("POST", "/api/setup/complete", { code: setupCode, state: setupState });
    if (r.ok) {
      history.replaceState(null, "", location.pathname); // strip ?code&state AFTER success only
      await tryLoad();                                   // status now configured:true → card hides
      return;
    }
    if (r.status === 401) {
      // Session died between the status check and this POST. Re-arm the one-shot guard so the
      // post-login tryLoad re-attempts the exchange (params are still in the URL), then prompt.
      setupAttempted = false;
      showLogin();
      return;
    }
    const b = await r.json().catch(() => ({}));
    showSetupError(b.error || ("Setup failed (" + r.status + ")"));
  } catch (e) {
    showSetupError("Can't reach the device — setup not completed.");
  }
  // On failure the params stay in the URL; show the card so the user can retry Connect.
  renderSetup(true);
  render();
  setStatus("Setup failed", "err");
}

// ---------- reusable controls ----------
// One <datalist> is built per unique domain-set and shared across every picker that needs it.
// With 11,000+ entities this avoids duplicating thousands of <option> nodes across pickers.
const datalistCache = {};
function sharedDatalistId(domains) {
  const key = domains.slice().sort().join(",");
  if (datalistCache[key]) return datalistCache[key];
  const listId = "dl-" + (++dlSeq);
  const dl = el("datalist");
  dl.id = listId;
  entities.filter(e => domains.includes(e.domain))
    .sort((a, b) => a.name.localeCompare(b.name))
    .forEach(e => {
      const o = el("option");
      o.value = e.id;
      o.textContent = e.name + " (" + e.id + ")";
      dl.appendChild(o);
    });
  document.getElementById("datalists").appendChild(dl);
  datalistCache[key] = listId;
  return listId;
}

function entityPicker(domains, value, onChange) {
  const wrap = el("span", "picker");
  const input = el("input");
  input.setAttribute("list", sharedDatalistId(domains));
  input.setAttribute("autocomplete", "off");
  input.placeholder = "Search entities…";
  input.value = value || "";
  input.addEventListener("change", () => onChange(input.value.trim() || null));
  wrap.appendChild(input);
  return wrap;
}

function labeledRow(labelText, control) {
  const row = el("div", "row");
  const label = el("label", null, labelText);
  // control may be a bare input/select, or a wrapper (e.g. entityPicker's span) around one.
  const target = (control.matches && control.matches("input, select")) ? control : control.querySelector("input, select");
  if (target) {
    if (!target.id) target.id = nextId("field");
    label.setAttribute("for", target.id);
  }
  row.appendChild(label);
  row.appendChild(control);
  return row;
}

function reorderButtons(canUp, canDown, onUp, onDown) {
  const nav = el("div", "reorder");
  const up = el("button", "ghost small icon", "↑");
  up.type = "button";
  up.setAttribute("aria-label", "Move up");
  up.disabled = !canUp;
  up.addEventListener("click", onUp);
  const down = el("button", "ghost small icon", "↓");
  down.type = "button";
  down.setAttribute("aria-label", "Move down");
  down.disabled = !canDown;
  down.addEventListener("click", onDown);
  nav.appendChild(up); nav.appendChild(down);
  return nav;
}

// ---------- render ----------
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
  renderEv();
}

function renderPanels() {
  const host = document.getElementById("panels");
  clear(host);

  // Home is always first and cannot be moved or hidden — shown as a pinned, non-interactive row.
  const homeRow = el("div", "panel-row pinned");
  homeRow.appendChild(glyph("home", "ptile"));
  homeRow.appendChild(el("span", "panel-name", "Home"));
  homeRow.appendChild(el("span", "chip", "Always first"));
  host.appendChild(homeRow);

  const ordered = PANEL_KEYS.slice().sort((a, b) => config.panels[a].order - config.panels[b].order);
  ordered.forEach((key, idx) => {
    const p = config.panels[key];
    const row = el("div", "panel-row" + (p.enabled ? "" : " off"));
    row.appendChild(glyph(key, "ptile"));
    row.appendChild(el("span", "panel-name", PANEL_LABELS[key]));

    const cb = el("input"); cb.type = "checkbox"; cb.checked = p.enabled;
    cb.setAttribute("aria-label", PANEL_LABELS[key] + " enabled");
    cb.addEventListener("change", () => { p.enabled = cb.checked; row.classList.toggle("off", !cb.checked); });
    row.appendChild(cb);

    row.appendChild(reorderButtons(
      idx !== 0, idx !== ordered.length - 1,
      () => { swapOrder(ordered, idx, idx - 1); renderPanels(); },
      () => { swapOrder(ordered, idx, idx + 1); renderPanels(); },
    ));
    host.appendChild(row);
  });
}

function swapOrder(ordered, i, j) {
  const a = config.panels[ordered[i]], b = config.panels[ordered[j]];
  const t = a.order; a.order = b.order; b.order = t;
}

function renderEntities() {
  const host = document.getElementById("entities");
  clear(host);
  const e = config.entities;

  host.appendChild(subhead("climate", "Sensors"));
  host.appendChild(labeledRow("Temperature sensor",
    entityPicker(["sensor"], e.tempSensor, v => e.tempSensor = v)));
  host.appendChild(labeledRow("Weather",
    entityPicker(["weather"], e.weather, v => e.weather = v)));
  host.appendChild(labeledRow("Air quality (AQI)",
    entityPicker(["sensor"], e.aqiSensor, v => e.aqiSensor = v)));

  // climate list
  host.appendChild(subhead("climate", "Thermostats"));
  e.climate.forEach((id, i) => {
    const row = el("div", "row");
    row.appendChild(entityPicker(["climate"], id, v => { if (v) e.climate[i] = v; else e.climate.splice(i, 1); renderEntities(); }));
    const del = el("button", "ghost small danger", "Remove");
    del.type = "button";
    del.setAttribute("aria-label", "Remove thermostat");
    del.addEventListener("click", () => { e.climate.splice(i, 1); renderEntities(); });
    row.appendChild(del);
    host.appendChild(row);
  });
  const addClimate = el("button", "add", "Add thermostat");
  addClimate.type = "button";
  addClimate.addEventListener("click", () => { e.climate.push(""); renderEntities(); });
  host.appendChild(addClimate);

  // solar slots
  host.appendChild(subhead("solar", "Solar"));
  const solarSlots = [["pv", "PV power"], ["load", "Home load"], ["grid", "Grid power"],
    ["pvToday", "PV today"], ["loadToday", "Load today"]];
  solarSlots.forEach(([k, lbl]) => {
    host.appendChild(labeledRow(lbl, entityPicker(["sensor"], e.solar[k], v => e.solar[k] = v)));
  });

  // light groups
  host.appendChild(subhead("lights", "Light groups"));
  e.lightGroups.forEach((g, gi) => host.appendChild(renderLightGroup(g, gi)));
  const addGroup = el("button", "add", "Add group");
  addGroup.type = "button";
  addGroup.addEventListener("click", () => { e.lightGroups.push({ name: "New group", entities: [] }); renderEntities(); });
  host.appendChild(addGroup);

  // cameras
  host.appendChild(subhead("cameras", "Cameras"));
  e.cameras.forEach((c, ci) => host.appendChild(renderCamera(c, ci)));
  const addCam = el("button", "add", "Add camera");
  addCam.type = "button";
  addCam.addEventListener("click", () => { e.cameras.push({ name: "New camera", entity: null, rtspUrl: null }); renderEntities(); });
  host.appendChild(addCam);
  host.appendChild(el("div", "muted",
    "RTSP plays direct from Frigate/go2rtc (rtsp://host:8554/name) for sub-second latency; leave blank to stream through Home Assistant (HLS, ~5–10 s behind). Tip: prefer sub/fluent streams — the screen is 960×480."));

  // doorbells
  host.appendChild(subhead("cameras", "Doorbells"));
  e.doorbells.forEach((d, di) => host.appendChild(renderDoorbell(d, di)));
  const addDb = el("button", "add", "Add doorbell");
  addDb.type = "button";
  addDb.addEventListener("click", () => { e.doorbells.push({ trigger: null, camera: "" }); renderEntities(); });
  host.appendChild(addDb);
}

function renderMedia() {
  const host = document.getElementById("media");
  clear(host);
  if (!config.media) config.media = { companionEntity: null, pausedDismissSeconds: 60 };
  const m = config.media;
  if (typeof m.pausedDismissSeconds !== "number") m.pausedDismissSeconds = 60;
  // Same populated picker pattern as the AQI sensor: a shared media_player datalist; blank -> null.
  host.appendChild(labeledRow("Companion media player",
    entityPicker(["media_player"], m.companionEntity, v => m.companionEntity = v)));
  host.appendChild(labeledRow("Dismiss player after paused (s)",
    numberInput(m.pausedDismissSeconds, v => m.pausedDismissSeconds = Math.round(v || 0))));
  host.appendChild(el("div", "muted",
    "The HA media player entity that mirrors this device (pick your Music Assistant player for the Echo) — enables album art, track info, and next/previous."));
}

function renderLightGroup(g, gi) {
  const groups = config.entities.lightGroups;
  const box = el("div", "group");
  const head = el("div", "group-head");
  const name = el("input"); name.value = g.name; name.setAttribute("aria-label", "Group name");
  name.addEventListener("change", () => g.name = name.value.trim() || "Group");
  head.appendChild(name);
  head.appendChild(reorderButtons(
    gi !== 0, gi !== groups.length - 1,
    () => { const t = groups[gi]; groups[gi] = groups[gi - 1]; groups[gi - 1] = t; renderEntities(); },
    () => { const t = groups[gi]; groups[gi] = groups[gi + 1]; groups[gi + 1] = t; renderEntities(); },
  ));
  const del = el("button", "ghost small danger", "Delete");
  del.type = "button";
  del.setAttribute("aria-label", "Delete group");
  del.addEventListener("click", () => { groups.splice(gi, 1); renderEntities(); });
  head.appendChild(del);
  box.appendChild(head);

  g.entities.forEach((id, ei) => {
    const row = el("div", "row");
    row.appendChild(entityPicker(["light", "switch", "fan"], id, v => { if (v) g.entities[ei] = v; else g.entities.splice(ei, 1); renderEntities(); }));
    row.appendChild(reorderButtons(
      ei !== 0, ei !== g.entities.length - 1,
      () => { const t = g.entities[ei]; g.entities[ei] = g.entities[ei - 1]; g.entities[ei - 1] = t; renderEntities(); },
      () => { const t = g.entities[ei]; g.entities[ei] = g.entities[ei + 1]; g.entities[ei + 1] = t; renderEntities(); },
    ));
    const erm = el("button", "ghost small danger", "Remove");
    erm.type = "button";
    erm.setAttribute("aria-label", "Remove entity");
    erm.addEventListener("click", () => { g.entities.splice(ei, 1); renderEntities(); });
    row.appendChild(erm);
    box.appendChild(row);
  });
  const addEnt = el("button", "add", "Add entity");
  addEnt.type = "button";
  addEnt.addEventListener("click", () => { g.entities.push(""); renderEntities(); });
  box.appendChild(addEnt);
  return box;
}

function renderCamera(c, ci) {
  const cams = config.entities.cameras;
  const box = el("div", "group");
  const head = el("div", "group-head");
  const name = el("input"); name.value = c.name; name.setAttribute("aria-label", "Camera name");
  name.addEventListener("change", () => c.name = name.value.trim());
  head.appendChild(name);
  head.appendChild(reorderButtons(
    ci !== 0, ci !== cams.length - 1,
    () => { const t = cams[ci]; cams[ci] = cams[ci - 1]; cams[ci - 1] = t; renderEntities(); },
    () => { const t = cams[ci]; cams[ci] = cams[ci + 1]; cams[ci + 1] = t; renderEntities(); },
  ));
  const del = el("button", "ghost small danger", "Delete");
  del.type = "button";
  del.setAttribute("aria-label", "Delete camera");
  del.addEventListener("click", () => { cams.splice(ci, 1); renderEntities(); });
  head.appendChild(del);
  box.appendChild(head);

  box.appendChild(labeledRow("Camera entity", entityPicker(["camera"], c.entity, v => c.entity = v)));
  const rtsp = el("input"); rtsp.value = c.rtspUrl || ""; rtsp.placeholder = "rtsp://host:8554/name";
  rtsp.setAttribute("autocomplete", "off");
  rtsp.addEventListener("change", () => c.rtspUrl = rtsp.value.trim() || null);
  box.appendChild(labeledRow("RTSP URL", rtsp));
  return box;
}

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
  del.addEventListener("click", () => { dbs.splice(di, 1); renderEntities(); });
  row.appendChild(del);
  return row;
}

function numberInput(value, onChange) {
  const n = el("input"); n.type = "number"; n.value = value;
  n.addEventListener("change", () => onChange(parseFloat(n.value)));
  return n;
}

function renderHome() {
  const host = document.getElementById("home");
  clear(host);
  const h = config.home;
  host.appendChild(labeledRow("Idle return (s)", numberInput(h.idleReturnSeconds, v => h.idleReturnSeconds = Math.round(v))));
  const clock = el("select");
  ["AUTO", "H12", "H24"].forEach(opt => { const o = el("option", null, opt); o.value = opt; if (h.clockFormat === opt) o.selected = true; clock.appendChild(o); });
  clock.addEventListener("change", () => h.clockFormat = clock.value);
  host.appendChild(labeledRow("Clock format", clock));
  const slide = el("input"); slide.type = "checkbox"; slide.checked = h.slideshowEnabled;
  slide.addEventListener("change", () => h.slideshowEnabled = slide.checked);
  host.appendChild(labeledRow("Photo slideshow", slide));
  host.appendChild(labeledRow("Photo interval (s)", numberInput(h.slideshowSeconds, v => h.slideshowSeconds = Math.round(v))));
  const folder = el("input"); folder.value = h.photoFolder;
  folder.addEventListener("change", () => h.photoFolder = folder.value.trim());
  host.appendChild(labeledRow("Photo folder", folder));
  host.appendChild(labeledRow("Photo cache cap", numberInput(h.photoCacheCap, v => h.photoCacheCap = Math.round(v))));
  host.appendChild(el("div", "muted", "Idle 15–3600 s, interval 10–3600 s, cap 5–500 (clamped on save)."));
}

function renderOptions() {
  const host = document.getElementById("options");
  clear(host);
  const o = config.panelOptions;
  host.appendChild(labeledRow("Thermostat step", numberInput(o.thermostatStep, v => o.thermostatStep = v)));
  host.appendChild(labeledRow("Forecast days", numberInput(o.forecastDays, v => o.forecastDays = Math.round(v))));
  host.appendChild(labeledRow("Sensor decimal places", numberInput(o.sensorDecimals, v => o.sensorDecimals = Math.round(v))));
  host.appendChild(labeledRow("Doorbell popup (s)", numberInput(o.doorbellPopupSeconds, v => o.doorbellPopupSeconds = Math.round(v))));
  const autoHide = el("input"); autoHide.type = "checkbox"; autoHide.checked = !!o.autoHideRail;
  autoHide.setAttribute("aria-label", "Auto-hide panel bar");
  autoHide.addEventListener("change", () => o.autoHideRail = autoHide.checked);
  host.appendChild(labeledRow("Auto-hide panel bar", autoHide));
  host.appendChild(el("div", "muted", "Step 0.1–5.0, forecast 1–5, doorbell popup 5–120 (clamped on save). " +
    "Auto-hide slides the panel bar away; any touch brings it back for 8 s."));
}

function renderVoice() {
  const host = document.getElementById("voice");
  clear(host);
  if (!config.voice) config.voice = { enabled: false };
  const v = config.voice;
  if (v.timerTone == null) v.timerTone = "twotone";
  if (v.timerVolume == null) v.timerVolume = 80;

  const toggle = el("input"); toggle.type = "checkbox"; toggle.checked = !!v.enabled;
  toggle.setAttribute("aria-label", "Voice satellite enabled");
  toggle.addEventListener("change", () => v.enabled = toggle.checked);
  host.appendChild(labeledRow("Voice satellite (Wyoming)", toggle));

  const toneSel = el("select");
  TONE_OPTIONS.forEach(([val, lbl]) => {
    const o = el("option", null, lbl); o.value = val;
    if (v.timerTone === val) o.selected = true;
    toneSel.appendChild(o);
  });
  toneSel.addEventListener("change", () => v.timerTone = toneSel.value);
  host.appendChild(labeledRow("Timer alarm", toneSel));

  const vol = el("input"); vol.type = "number"; vol.min = 0; vol.max = 100; vol.value = v.timerVolume;
  vol.addEventListener("change", () => v.timerVolume = Math.round(parseFloat(vol.value) || 0));
  host.appendChild(labeledRow("Alarm volume", vol));

  const preview = el("button", "ghost small", "Preview");
  preview.type = "button";
  preview.addEventListener("click", async () => {
    // Audition the CURRENT (possibly unsaved) selections. Best-effort; ignore failures.
    preview.disabled = true;
    try {
      await api("POST", "/api/voice/preview-chime", { tone: v.timerTone, volume: v.timerVolume });
    } catch (e) { /* device may be unreachable; nothing to persist */ }
    finally { preview.disabled = false; }
  });
  host.appendChild(preview);

  host.appendChild(el("div", "muted",
    "Home Assistant should auto-discover the satellite; otherwise add the Wyoming Protocol integration at <this-device-ip>:10600. Pick the pipeline and wake word in HA's Assist satellite settings."));
}

function renderNight() {
  const host = document.getElementById("night");
  clear(host);
  // Defensive defaults for configs saved before night mode existed (same pattern as the Media card).
  if (!config.night) config.night = { enabled: false, thresholdLux: 10, brightness: 0 };
  const n = config.night;
  if (typeof n.thresholdLux !== "number") n.thresholdLux = 10;
  if (typeof n.brightness !== "number") n.brightness = 0;

  const toggle = el("input"); toggle.type = "checkbox"; toggle.checked = !!n.enabled;
  toggle.setAttribute("aria-label", "Night clock enabled");
  toggle.addEventListener("change", () => n.enabled = toggle.checked);
  host.appendChild(labeledRow("Night clock", toggle));

  host.appendChild(labeledRow("Enter below (lux)",
    numberInput(n.thresholdLux, v => n.thresholdLux = Math.round(v || 0))));
  host.appendChild(labeledRow("Night brightness (%)",
    numberInput(n.brightness, v => n.brightness = Math.round(v || 0))));

  const lux = el("div", "muted"); lux.id = "night-lux";
  host.appendChild(lux);
  updateNightLux(lastStatus);

  host.appendChild(el("div", "muted",
    "When the room stays darker than the threshold for ~30 s the screen becomes a dim clock at the " +
    "night brightness. A touch or activity (music, doorbell, voice, timers) wakes it; it returns after " +
    "60 s if still dark. Threshold 1–1000 lux, brightness 0–100 % (0 = dimmest), clamped on save."));
}

function renderEv() {
  const host = document.getElementById("ev");
  clear(host);
  // Defensive: old configs and server responses may return 0/1/2 slots — always render exactly two.
  if (!Array.isArray(config.entities.evs)) config.entities.evs = [{}, {}];
  const evs = config.entities.evs;
  while (evs.length < 2) evs.push({});

  evs.slice(0, 2).forEach((slot, i) => {
    const box = el("div", "group");
    const head = el("div", "group-head");
    head.appendChild(el("span", "panel-name", "EV " + (i + 1)));
    box.appendChild(head);

    const name = el("input");
    name.value = slot.name || "";
    name.setAttribute("aria-label", "EV name");
    name.addEventListener("change", () => slot.name = name.value.trim());
    box.appendChild(labeledRow("Name", name));

    box.appendChild(labeledRow("Plugged in when on",
      entityPicker(["binary_sensor", "sensor", "switch"], slot.plugged, v => slot.plugged = v)));
    box.appendChild(labeledRow("Charging when on",
      entityPicker(["binary_sensor", "sensor", "switch"], slot.charging, v => slot.charging = v)));
    box.appendChild(labeledRow("Battery %",
      entityPicker(["sensor"], slot.soc, v => slot.soc = v)));
    box.appendChild(labeledRow("Charge power",
      entityPicker(["sensor"], slot.power, v => slot.power = v)));
    box.appendChild(labeledRow("Session energy",
      entityPicker(["sensor"], slot.energy, v => slot.energy = v)));
    box.appendChild(labeledRow("Time remaining",
      entityPicker(["sensor"], slot.eta, v => slot.eta = v)));

    host.appendChild(box);
  });

  host.appendChild(el("div", "muted",
    "A card shows on the home screen while a car is plugged in or charging. Set either trigger — " +
    "“Plugged in when on” shows the card once the cable is connected; “Charging when on” shows it " +
    "and animates the bar while power flows. Charge power (W or kW), session energy (Wh or kWh), " +
    "and time remaining (minutes, H:MM:SS, or a timestamp) only display while charging. Battery % " +
    "shows whenever the card is up. EVCC’s status sensor (A/B/C) can drive both trigger pickers. " +
    "Empty slots are dropped on save."));
}

function updateNightLux(status) {
  const box = document.getElementById("night-lux");
  if (!box) return;
  if (status && typeof status.lux === "number") box.textContent = "Current reading: " + status.lux + " lux";
  else box.textContent = "Current reading: no sensor";
}

// The base page fetches /api/status once at load; poll it here so the live lux reading refreshes.
function startStatusPoll() {
  if (statusPollStarted) return;
  statusPollStarted = true;
  setInterval(async () => {
    try {
      const r = await api("GET", "/api/status");
      if (r.ok) { lastStatus = await r.json(); updateNightLux(lastStatus); }
    } catch (e) { /* device may be briefly unreachable; ignore */ }
  }, 5000);
}

// ---------- boot ----------
document.getElementById("login-form").addEventListener("submit", doLogin);
document.getElementById("save").addEventListener("click", save);
document.getElementById("setup-form").addEventListener("submit", beginSetup);
tryLoad();
