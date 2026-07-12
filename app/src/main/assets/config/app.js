"use strict";

let config = null;      // the live DashConfig model (source of truth)
let entities = [];      // [{id, name, domain, state}]
let dlSeq = 0;
let idSeq = 0;
function nextId(prefix) { return prefix + "-" + (++idSeq); }

const PANEL_KEYS = ["lights", "climate", "media", "weather", "solar"];
const PANEL_LABELS = {
  lights: "Lights", climate: "Climate", media: "Media", weather: "Weather", solar: "Solar",
};

// ---------- inline SVG glyphs (currentColor) ----------
const ICONS = {
  lights: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.8 10.6c.5.5.8 1 .8 1.6V16h6v-.8c0-.6.3-1.1.8-1.6A6 6 0 0 0 12 3Z"/></svg>',
  climate: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13.6V5a2 2 0 1 1 4 0v8.6a4 4 0 1 1-4 0Z"/><circle cx="12" cy="16" r="1.4" fill="currentColor" stroke="none"/></svg>',
  media: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M10.2 8.3 16 12l-5.8 3.7Z" fill="currentColor" stroke="none"/></svg>',
  weather: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M7 18a4 4 0 0 1-.4-8A5.5 5.5 0 0 1 17 9.2 3.6 3.6 0 0 1 16.8 18Z"/></svg>',
  solar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3.6"/><path d="M12 2.5v2.4M12 19.1v2.4M21.5 12h-2.4M4.9 12H2.5M18.4 5.6l-1.7 1.7M7.3 16.7l-1.7 1.7M18.4 18.4l-1.7-1.7M7.3 7.3 5.6 5.6"/></svg>',
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 11.5 12 4l8 7.5"/><path d="M6 10v10h12V10"/><path d="M10 20v-5h4v5"/></svg>',
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
    showApp();
    render();
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
  renderHome();
  renderOptions();
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
  const folder = el("input"); folder.value = h.photoFolder;
  folder.addEventListener("change", () => h.photoFolder = folder.value.trim());
  host.appendChild(labeledRow("Photo folder", folder));
  host.appendChild(labeledRow("Photo cache cap", numberInput(h.photoCacheCap, v => h.photoCacheCap = Math.round(v))));
  host.appendChild(el("div", "muted", "Idle 15–3600 s, cap 5–500 (clamped on save)."));
}

function renderOptions() {
  const host = document.getElementById("options");
  clear(host);
  const o = config.panelOptions;
  host.appendChild(labeledRow("Thermostat step", numberInput(o.thermostatStep, v => o.thermostatStep = v)));
  host.appendChild(labeledRow("Forecast days", numberInput(o.forecastDays, v => o.forecastDays = Math.round(v))));
  host.appendChild(el("div", "muted", "Step 0.1–5.0, forecast 1–5 (clamped on save)."));
}

// ---------- boot ----------
document.getElementById("login-form").addEventListener("submit", doLogin);
document.getElementById("save").addEventListener("click", save);
tryLoad();
