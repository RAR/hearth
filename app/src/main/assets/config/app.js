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

// ---------- tiny DOM helpers ----------
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}
function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
function setStatus(msg, ok) {
  const s = document.getElementById("status");
  s.textContent = msg;
  s.className = "status " + (ok ? "ok" : "err");
}

// ---------- auth + data ----------
async function api(method, path, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
  return fetch(path, opts);
}

async function tryLoad() {
  const r = await api("GET", "/api/config");
  if (r.status === 401) { showLogin(); return; }
  config = await r.json();
  const er = await api("GET", "/api/entities");
  entities = er.ok ? await er.json() : [];
  showApp();
  render();
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
  const r = await api("POST", "/api/login", { pin });
  if (r.ok) { await tryLoad(); return; }
  if (r.status === 429) {
    const b = await r.json().catch(() => ({}));
    errBox.textContent = "Too many attempts. Try again in " + (b.retryAfter || 60) + "s.";
  } else {
    errBox.textContent = "Wrong PIN.";
  }
}

async function save() {
  setStatus("Saving…", true);
  const r = await api("PUT", "/api/config", config);
  if (r.ok) {
    config = await r.json();  // adopt the server's clamped copy
    render();
    setStatus("Saved.", true);
  } else if (r.status === 401) {
    showLogin();
  } else {
    const b = await r.json().catch(() => ({}));
    setStatus("Error: " + (b.error || r.status), false);
  }
}

// ---------- reusable controls ----------
function entityPicker(domains, value, onChange) {
  const wrap = el("span", "picker");
  const input = el("input");
  const listId = "dl-" + (++dlSeq);
  input.setAttribute("list", listId);
  input.value = value || "";
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
  input.addEventListener("change", () => onChange(input.value.trim() || null));
  wrap.appendChild(input);
  wrap.appendChild(dl);
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
  const ordered = PANEL_KEYS.slice().sort((a, b) => config.panels[a].order - config.panels[b].order);
  ordered.forEach((key, idx) => {
    const p = config.panels[key];
    const row = el("div", "row");
    const cb = el("input"); cb.type = "checkbox"; cb.checked = p.enabled;
    cb.addEventListener("change", () => { p.enabled = cb.checked; });
    row.appendChild(cb);
    row.appendChild(el("label", null, PANEL_LABELS[key]));

    const up = el("button", "ghost small", "↑");
    up.setAttribute("aria-label", "Move up");
    up.disabled = idx === 0;
    up.addEventListener("click", () => { swapOrder(ordered, idx, idx - 1); renderPanels(); });
    const down = el("button", "ghost small", "↓");
    down.setAttribute("aria-label", "Move down");
    down.disabled = idx === ordered.length - 1;
    down.addEventListener("click", () => { swapOrder(ordered, idx, idx + 1); renderPanels(); });
    row.appendChild(up); row.appendChild(down);
    host.appendChild(row);
  });
  host.appendChild(el("div", "muted", "Home is always first and cannot be moved or hidden."));
}

function swapOrder(ordered, i, j) {
  const a = config.panels[ordered[i]], b = config.panels[ordered[j]];
  const t = a.order; a.order = b.order; b.order = t;
}

function renderEntities() {
  const host = document.getElementById("entities");
  clear(host);
  const e = config.entities;

  host.appendChild(labeledRow("Temperature sensor",
    entityPicker(["sensor"], e.tempSensor, v => e.tempSensor = v)));
  host.appendChild(labeledRow("Weather",
    entityPicker(["weather"], e.weather, v => e.weather = v)));

  // climate list
  host.appendChild(el("h3", null, "Thermostats"));
  e.climate.forEach((id, i) => {
    const row = el("div", "row");
    row.appendChild(entityPicker(["climate"], id, v => { if (v) e.climate[i] = v; else e.climate.splice(i, 1); renderEntities(); }));
    const del = el("button", "ghost small", "Remove");
    del.setAttribute("aria-label", "Remove thermostat");
    del.addEventListener("click", () => { e.climate.splice(i, 1); renderEntities(); });
    row.appendChild(del);
    host.appendChild(row);
  });
  const addClimate = el("button", "ghost small", "Add thermostat");
  addClimate.addEventListener("click", () => { e.climate.push(""); renderEntities(); });
  host.appendChild(addClimate);

  // solar slots
  host.appendChild(el("h3", null, "Solar"));
  const solarSlots = [["pv", "PV power"], ["load", "Home load"], ["grid", "Grid power"],
    ["pvToday", "PV today"], ["loadToday", "Load today"]];
  solarSlots.forEach(([k, lbl]) => {
    host.appendChild(labeledRow(lbl, entityPicker(["sensor"], e.solar[k], v => e.solar[k] = v)));
  });

  // light groups
  host.appendChild(el("h3", null, "Light groups"));
  e.lightGroups.forEach((g, gi) => host.appendChild(renderLightGroup(g, gi)));
  const addGroup = el("button", "ghost small", "Add group");
  addGroup.addEventListener("click", () => { e.lightGroups.push({ name: "New group", entities: [] }); renderEntities(); });
  host.appendChild(addGroup);
}

function renderLightGroup(g, gi) {
  const groups = config.entities.lightGroups;
  const box = el("div", "group");
  const head = el("div", "group-head");
  const name = el("input"); name.value = g.name;
  name.addEventListener("change", () => g.name = name.value.trim() || "Group");
  head.appendChild(name);
  const up = el("button", "ghost small", "↑");
  up.setAttribute("aria-label", "Move up");
  up.disabled = gi === 0;
  up.addEventListener("click", () => { const t = groups[gi]; groups[gi] = groups[gi - 1]; groups[gi - 1] = t; renderEntities(); });
  const down = el("button", "ghost small", "↓");
  down.setAttribute("aria-label", "Move down");
  down.disabled = gi === groups.length - 1;
  down.addEventListener("click", () => { const t = groups[gi]; groups[gi] = groups[gi + 1]; groups[gi + 1] = t; renderEntities(); });
  const del = el("button", "ghost small", "Delete");
  del.setAttribute("aria-label", "Delete group");
  del.addEventListener("click", () => { groups.splice(gi, 1); renderEntities(); });
  head.appendChild(up); head.appendChild(down); head.appendChild(del);
  box.appendChild(head);

  g.entities.forEach((id, ei) => {
    const row = el("div", "row");
    row.appendChild(entityPicker(["light", "switch", "fan"], id, v => { if (v) g.entities[ei] = v; else g.entities.splice(ei, 1); renderEntities(); }));
    const eup = el("button", "ghost small", "↑");
    eup.setAttribute("aria-label", "Move up");
    eup.disabled = ei === 0;
    eup.addEventListener("click", () => { const t = g.entities[ei]; g.entities[ei] = g.entities[ei - 1]; g.entities[ei - 1] = t; renderEntities(); });
    const edown = el("button", "ghost small", "↓");
    edown.setAttribute("aria-label", "Move down");
    edown.disabled = ei === g.entities.length - 1;
    edown.addEventListener("click", () => { const t = g.entities[ei]; g.entities[ei] = g.entities[ei + 1]; g.entities[ei + 1] = t; renderEntities(); });
    const erm = el("button", "ghost small", "Remove");
    erm.setAttribute("aria-label", "Remove entity");
    erm.addEventListener("click", () => { g.entities.splice(ei, 1); renderEntities(); });
    row.appendChild(eup); row.appendChild(edown); row.appendChild(erm);
    box.appendChild(row);
  });
  const addEnt = el("button", "ghost small", "Add entity");
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
