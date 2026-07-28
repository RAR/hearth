"use strict";

let config = null;      // the live DashConfig model (source of truth)
let entities = [];      // [{id, name, domain, state}]
let lastStatus = null;  // most recent /api/status body (carries the live lux reading)
let statusPollStarted = false;
let dlSeq = 0;
let idSeq = 0;
let pollGeneration = 0; // generation counter to cancel stale polls on re-render
function nextId(prefix) { return prefix + "-" + (++idSeq); }

// OAuth callback params captured once at load, before any history.replaceState.
const _sp = new URLSearchParams(location.search);
const setupCode = _sp.get("code");
const setupState = _sp.get("state");
let setupAttempted = false;

const PANEL_KEYS = ["lights", "climate", "media", "weather", "solar", "cameras", "calendar"];
const PANEL_LABELS = {
  lights: "Lights", climate: "Climate", media: "Media", weather: "Weather", solar: "Solar", cameras: "Cameras",
  calendar: "Calendar",
};

const HOME_CARD_KEYS = ["nowPlaying", "ev1", "ev2", "solar", "quickButtons"];
const HOME_CARD_LABELS = {
  nowPlaying: "Now playing",
  ev1: "EV 1",
  ev2: "EV 2",
  solar: "Solar",
  quickButtons: "Quick buttons",
};

const TONE_OPTIONS = [
  ["argon", "Argon"],
  ["oxygen", "Oxygen"],
  ["krypton", "Krypton"],
  ["timer", "Timer (Android)"],
  ["beep", "Alarm beep"],
  ["helium", "Helium"],
  ["cyan", "Cyan alarm"],
  ["twotone", "Two-tone"],
  ["beeps", "Beeps"],
  ["chime", "Chime"],
  ["trill", "Trill"],
];

const WAKE_WORD_OPTIONS = [
  ["okay_nabu", "Okay Nabu"],
  ["hey_jarvis", "Hey Jarvis"],
  ["alexa", "Alexa"],
  ["ok_ember", "Ok Ember"],
];

const SEVERITY_OPTIONS = [
  ["minor", "Minor"],
  ["moderate", "Moderate"],
  ["severe", "Severe"],
];

const AUTO_DISMISS_OPTIONS = [
  ["off", "Off"],
  ["info", "Info only"],
  ["warning", "Info + warning"],
  ["critical", "All severities"],
];

const CALENDAR_COLORS = ["blue", "green", "amber", "red", "purple", "teal", "orange", "pink"];

// ---------- inline SVG glyphs (currentColor) ----------
const ICONS = {
  lights: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.8 10.6c.5.5.8 1 .8 1.6V16h6v-.8c0-.6.3-1.1.8-1.6A6 6 0 0 0 12 3Z"/></svg>',
  climate: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13.6V5a2 2 0 1 1 4 0v8.6a4 4 0 1 1-4 0Z"/><circle cx="12" cy="16" r="1.4" fill="currentColor" stroke="none"/></svg>',
  media: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M10.2 8.3 16 12l-5.8 3.7Z" fill="currentColor" stroke="none"/></svg>',
  weather: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M7 18a4 4 0 0 1-.4-8A5.5 5.5 0 0 1 17 9.2 3.6 3.6 0 0 1 16.8 18Z"/></svg>',
  solar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3.6"/><path d="M12 2.5v2.4M12 19.1v2.4M21.5 12h-2.4M4.9 12H2.5M18.4 5.6l-1.7 1.7M7.3 16.7l-1.7 1.7M18.4 18.4l-1.7-1.7M7.3 7.3 5.6 5.6"/></svg>',
  cameras: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="6.5" width="12" height="11" rx="2"/><path d="M15 10l6-3v10l-6-3Z"/></svg>',
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 11.5 12 4l8 7.5"/><path d="M6 10v10h12V10"/><path d="M10 20v-5h4v5"/></svg>',
  calendar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 9.5h16"/><path d="M8 3v4M16 3v4"/></svg>',
  ev: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 17v-4l2-4.5A2 2 0 0 1 7.8 7.3h6.4A2 2 0 0 1 16 8.5L18 13v4"/><path d="M4 17h2M18 17h2"/><circle cx="7.5" cy="17" r="1.5"/><circle cx="16.5" cy="17" r="1.5"/><path d="M12 8.5 11 11.5h2L11.8 14.5"/></svg>',
};

// Eye glyphs for the login PIN reveal toggle (currentColor, same stroke style as ICONS).
const EYE_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>';
const EYE_OFF_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10.6 6.2A9.7 9.7 0 0 1 12 6c6.5 0 10 6 10 6a17 17 0 0 1-3.2 3.7M6.2 6.2A17 17 0 0 0 2 12s3.5 6 10 6a9.6 9.6 0 0 0 4-.9"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/><path d="M3 3l18 18"/></svg>';

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
    if (status.configured === false) location.hash = "#device";
    showPage(currentPage());
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
  loadHello();
}

// Pre-auth device name for the login card + password-manager username field. Fails soft:
// on any error the login shows without a name and never blocks sign-in.
async function loadHello() {
  try {
    const r = await api("GET", "/api/hello");
    if (!r.ok) return;
    const b = await r.json();
    const name = (b && b.name) || "";
    const row = document.getElementById("login-device-row");
    const field = document.getElementById("login-device");
    if (name) { field.value = name; row.hidden = false; }
    else { row.hidden = true; }
  } catch (e) { /* fail soft: show login without the device name */ }
}

// Reveal/hide the PIN. type=button so it never submits the login form.
function togglePinVisibility() {
  const pin = document.getElementById("pin");
  const btn = document.getElementById("pin-toggle");
  const reveal = pin.type === "password";
  pin.type = reveal ? "text" : "password";
  btn.setAttribute("aria-label", reveal ? "Hide PIN" : "Show PIN");
  btn.setAttribute("aria-pressed", reveal ? "true" : "false");
  btn.innerHTML = reveal ? EYE_OFF_ICON : EYE_ICON;
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

function renderSetup(show) {
  document.getElementById("setup-section").hidden = !show;
  // After a disconnect baseUrl is retained (clearAuth keeps it); prefill the empty field so
  // reconnecting is Connect -> HA login -> done.
  if (show) {
    const urlEl = document.getElementById("setup-url");
    if (urlEl && !urlEl.value && lastStatus && lastStatus.haUrl) urlEl.value = lastStatus.haUrl;
  }
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
  location.hash = "#device";
  showPage(currentPage());
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

// ---------- render ----------
function render() {
  renderHa();
  renderDevice();
  renderPin();
  renderBackup();
  renderDiag();
  renderPanels();
  renderHome();
  renderHomeCards();
  renderNight();
  renderClaudeUsage();
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
}

function renderHa() {
  // Complementary to the setup card: shown only when configured. Visibility is owned here
  // (render()/tryLoad()), never by the poll.
  document.getElementById("ha-section").hidden = !(lastStatus && lastStatus.configured);
  const host = document.getElementById("ha");
  clear(host);

  const pill = el("span", "status info", "Unknown");
  pill.id = "ha-conn";
  host.appendChild(labeledRow("Status", pill));
  updateHaConn(lastStatus);   // paint the real state now; the 5s poll refreshes it after

  const urlInput = el("input");
  urlInput.readOnly = true;
  urlInput.value = (lastStatus && lastStatus.haUrl) || "";
  urlInput.setAttribute("aria-label", "Home Assistant server address");
  urlInput.addEventListener("focus", () => urlInput.select());   // select-on-focus, read-only
  host.appendChild(labeledRow("Server", urlInput));

  const row = el("div", "row");
  const disc = el("button", "ghost danger", "Disconnect…");
  disc.type = "button";
  disc.addEventListener("click", disconnectHa);
  row.appendChild(disc);
  host.appendChild(row);

  host.appendChild(el("div", "muted",
    "Signs this device out of Home Assistant and returns it to setup. Panels, entities, and " +
    "all other settings are kept; the server address stays filled in for reconnecting."));
}

// Live status pill; called after render and from the 5s poll. Sets text + class ONLY — never
// toggles card visibility (that is render()/tryLoad()'s job).
function updateHaConn(status) {
  const node = document.getElementById("ha-conn");
  if (!node) return;                       // card not rendered
  const map = {
    CONNECTED: ["ok", "Connected"],
    CONNECTING: ["busy", "Connecting…"],
    OFFLINE: ["err", "Offline"],
    AUTH_FAILED: ["err", "Authentication failed"],
  };
  const [kind, label] = map[status && status.connState] || ["info", "Unknown"];
  node.className = "status " + kind;
  node.textContent = label;
}

async function disconnectHa() {
  if (!confirm("Disconnect from Home Assistant? The dashboard stops until you reconnect.")) return;
  setStatus("Disconnecting…", "busy");
  try {
    const r = await api("POST", "/api/disconnect");
    if (r.status === 401) { showLogin(); return; }
    // On ok the device has cleared auth; tryLoad() re-pulls status (now configured:false),
    // which hides this card, shows setup, and forces the #device page.
    if (r.ok) { await tryLoad(); return; }
    setStatus("Can't reach the device — not disconnected.", "err");
  } catch (e) {
    setStatus("Can't reach the device — not disconnected.", "err");
  }
}

function renderDevice() {
  const host = document.getElementById("device");
  clear(host);

  const nameInput = el("input");
  nameInput.type = "text";
  nameInput.maxLength = 40;
  nameInput.value = (lastStatus && lastStatus.deviceName) || "";
  nameInput.setAttribute("aria-label", "Device name");
  host.appendChild(labeledRow("Device name", nameInput));

  const row = el("div", "row");
  const renameBtn = el("button", "ghost", "Rename");
  renameBtn.type = "button";
  renameBtn.addEventListener("click", () => renameDevice(nameInput));
  row.appendChild(renameBtn);
  host.appendChild(row);

  host.appendChild(el("div", "muted",
    "This name appears in Home Assistant (voice satellite and VACA) and on the network (mDNS). " +
    "It is stored only on this device and is never included in config export/import. Leave the " +
    "field empty and rename to restore the default. Home Assistant may keep showing the old name " +
    "on entries it already knows until you rename or re-add the device there."));
}

async function renameDevice(input) {
  setStatus("Renaming…", "busy");
  try {
    const r = await api("PUT", "/api/name", { name: input.value });
    if (r.ok) {
      const b = await r.json();
      input.value = b.name || "";              // adopt the server's effective (clamped/default) name
      if (lastStatus) lastStatus.deviceName = b.name;
      setStatus("Renamed", "ok");
    } else if (r.status === 401) {
      showLogin();
    } else {
      const b = await r.json().catch(() => ({}));
      setStatus("Error: " + (b.error || r.status), "err");
    }
  } catch (e) {
    setStatus("Can't reach the device — not renamed.", "err");
  }
}

function renderPin() {
  const host = document.getElementById("pin-card");
  clear(host);

  const cur = el("input");
  cur.readOnly = true;
  cur.value = (lastStatus && lastStatus.pin) || "";
  cur.setAttribute("aria-label", "Current config PIN");
  cur.addEventListener("focus", () => cur.select());   // select-on-focus, read-only
  host.appendChild(labeledRow("Current PIN", cur));

  const next = el("input");
  next.type = "text";
  next.inputMode = "numeric";
  next.maxLength = 8;
  next.placeholder = "4–8 digits";
  next.setAttribute("autocomplete", "off");
  next.setAttribute("aria-label", "New PIN");
  host.appendChild(labeledRow("Change PIN", next));

  const err = el("div", "error");

  const row = el("div", "row");
  const saveBtn = el("button", "ghost", "Save PIN");
  saveBtn.type = "button";
  saveBtn.addEventListener("click", () => changePin(next, cur, err));
  row.appendChild(saveBtn);

  const resetBtn = el("button", "ghost", "Reset to random");
  resetBtn.type = "button";
  resetBtn.addEventListener("click", () => resetPinToRandom(cur, next, err));
  row.appendChild(resetBtn);
  host.appendChild(row);

  host.appendChild(err);

  host.appendChild(el("div", "muted",
    "This PIN protects the configuration page and is shown on the device screen. Choose 4 to 8 " +
    "digits, or reset to a new random 6-digit PIN. Changes take effect immediately — you stay " +
    "signed in on this browser, but the new PIN is required next time."));
}

async function changePin(input, currentEl, err) {
  err.textContent = "";
  const pin = input.value.trim();
  if (!/^\d{4,8}$/.test(pin)) {
    err.textContent = "PIN must be 4 to 8 digits.";
    return;
  }
  setStatus("Saving PIN…", "busy");
  try {
    const r = await api("PUT", "/api/pin", { pin });
    if (r.status === 401) { showLogin(); return; }
    const b = await r.json().catch(() => ({}));
    if (r.ok && b.pin) {
      currentEl.value = b.pin;
      if (lastStatus) lastStatus.pin = b.pin;
      input.value = "";
      setStatus("PIN changed", "ok");
    } else {
      err.textContent = b.error === "invalid pin"
        ? "PIN must be 4 to 8 digits."
        : ("Error: " + (b.error || r.status));
      setStatus("PIN not changed", "err");
    }
  } catch (e) {
    setStatus("Can't reach the device — PIN not changed.", "err");
  }
}

async function resetPinToRandom(currentEl, changeEl, err) {
  err.textContent = "";
  if (!confirm("Reset the config PIN to a new random 6-digit PIN?")) return;
  setStatus("Resetting PIN…", "busy");
  try {
    const r = await api("POST", "/api/pin/reset");
    if (r.status === 401) { showLogin(); return; }
    const b = await r.json().catch(() => ({}));
    if (r.ok && b.pin) {
      currentEl.value = b.pin;
      if (lastStatus) lastStatus.pin = b.pin;
      changeEl.value = "";
      setStatus("PIN reset", "ok");
    } else {
      setStatus("PIN not reset", "err");
    }
  } catch (e) {
    setStatus("Can't reach the device — PIN not reset.", "err");
  }
}

function renderPanels() {
  const host = document.getElementById("panels");
  clear(host);
  // Defensive default for configs saved before the Calendar panel existed.
  if (!config.panels.calendar) config.panels.calendar = { enabled: true, order: 7 };

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
      () => { swapOrder(config.panels, ordered, idx, idx - 1); renderPanels(); },
      () => { swapOrder(config.panels, ordered, idx, idx + 1); renderPanels(); },
    ));
    host.appendChild(row);
  });
  host.appendChild(el("div", "muted", "The panel bar auto-hides; swipe in from the right edge to bring it back for 8 s."));
}

// `bag` is the object holding the {enabled, order} entries (config.panels, config.homeCards);
// `ordered` is its keys already sorted by order.
function swapOrder(bag, ordered, i, j) {
  const a = bag[ordered[i]], b = bag[ordered[j]];
  const t = a.order; a.order = b.order; b.order = t;
}

function renderHomeCards() {
  const host = document.getElementById("homecards");
  clear(host);
  // Defensive default for configs saved before this block existed: the pre-feature order.
  if (!config.homeCards) {
    config.homeCards = {
      nowPlaying: { enabled: true, order: 1 },
      ev1: { enabled: true, order: 2 },
      ev2: { enabled: true, order: 3 },
      solar: { enabled: true, order: 4 },
      quickButtons: { enabled: true, order: 5 },
    };
  }
  const cards = config.homeCards;
  HOME_CARD_KEYS.forEach((key, i) => {
    if (!cards[key]) cards[key] = { enabled: true, order: i + 1 };
  });

  // EV rows carry the user's own car names when set -- "Rivian R1T" beats "EV 1".
  const evs = (config.entities && config.entities.evs) || [];
  const label = (key) => {
    if (key === "ev1" && evs[0] && evs[0].name) return evs[0].name;
    if (key === "ev2" && evs[1] && evs[1].name) return evs[1].name;
    return HOME_CARD_LABELS[key];
  };

  const ordered = HOME_CARD_KEYS.slice().sort((a, b) => cards[a].order - cards[b].order);
  let firstEnabledSeen = false;
  ordered.forEach((key, idx) => {
    const c = cards[key];
    const row = el("div", "panel-row" + (c.enabled ? "" : " off"));
    row.appendChild(el("span", "ptile"));
    row.appendChild(el("span", "panel-name", label(key)));

    // The head of the order is guaranteed a slot even when it alone would overflow the column.
    // It marks position, not presence -- a card with nothing to show still renders nothing.
    if (c.enabled && !firstEnabledSeen) {
      firstEnabledSeen = true;
      row.appendChild(el("span", "chip", "Shown first"));
    }

    const cb = el("input"); cb.type = "checkbox"; cb.checked = c.enabled;
    cb.setAttribute("aria-label", label(key) + " enabled");
    cb.addEventListener("change", () => { c.enabled = cb.checked; renderHomeCards(); });
    row.appendChild(cb);

    row.appendChild(reorderButtons(
      idx !== 0, idx !== ordered.length - 1,
      () => { swapOrder(cards, ordered, idx, idx - 1); renderHomeCards(); },
      () => { swapOrder(cards, ordered, idx, idx + 1); renderHomeCards(); },
    ));
    host.appendChild(row);
  });

  host.appendChild(el("div", "muted",
    "These are the cards down the right-hand side of the home screen. A card only appears when it " +
    "has something to show — a car that’s plugged in, music that’s playing — so turning one on " +
    "does not force it on screen. Hiding a card keeps its entities, so you can bring it back " +
    "without setting it up again. On a small screen only the first card or two fit; the rest move " +
    "behind the “+N more” chip."));
}

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

function renderClaudeUsage() {
  const host = document.getElementById("claudeusage");
  clear(host);
  const e = config.entities;
  if (!e.claudeUsage) e.claudeUsage = {};
  const c = e.claudeUsage;
  const slots = [
    ["session", "Session usage %"],
    ["sessionReset", "Session reset time"],
    ["week", "Week usage %"],
    ["weekReset", "Week reset time"],
    ["pace", "Week pace %"],
  ];
  slots.forEach(([key, label]) => {
    host.appendChild(labeledRow(label, entityPicker(["sensor"], c[key], v => c[key] = v)));
  });
  host.appendChild(el("div", "muted",
    "Claude subscription usage, shown on the home screen between the notifications and the clock. " +
    "Sensors come from the hass_claude_usage integration. Set at least one of the two usage " +
    "percentages; reset and pace are optional. A sensor that reads unavailable drops its row " +
    "rather than showing 0%."));
}

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

function renderVoice() {
  const host = document.getElementById("voice");
  clear(host);
  if (!config.voice) config.voice = { enabled: false };
  const v = config.voice;
  if (v.timerTone == null) v.timerTone = "argon";
  if (v.timerVolume == null) v.timerVolume = 80;
  if (v.wakeSoundVolume == null) v.wakeSoundVolume = 80;
  if (v.wakeWord == null) v.wakeWord = "okay_nabu";
  if (v.wakeThreshold == null) v.wakeThreshold = 50;
  if (v.followUpEnabled == null) v.followUpEnabled = false;
  if (v.captureOnWake == null) v.captureOnWake = false;
  if (v.captureThreshold == null) v.captureThreshold = 40;

  const toggle = el("input"); toggle.type = "checkbox"; toggle.checked = !!v.enabled;
  toggle.setAttribute("aria-label", "Voice satellite enabled");
  toggle.addEventListener("change", () => v.enabled = toggle.checked);
  host.appendChild(labeledRow("Voice satellite (Wyoming)", toggle));

  const wakeSel = el("select");
  WAKE_WORD_OPTIONS.forEach(([val, lbl]) => {
    const o = el("option", null, lbl); o.value = val;
    if (v.wakeWord === val) o.selected = true;
    wakeSel.appendChild(o);
  });
  wakeSel.addEventListener("change", () => v.wakeWord = wakeSel.value);
  host.appendChild(labeledRow("Wake word", wakeSel));

  const sens = el("input"); sens.type = "number"; sens.min = 10; sens.max = 95; sens.value = v.wakeThreshold;
  sens.addEventListener("change", () => v.wakeThreshold = Math.round(parseFloat(sens.value) || 50));
  host.appendChild(labeledRow("Wake sensitivity", sens));

  const followUp = el("input"); followUp.type = "checkbox"; followUp.checked = !!v.followUpEnabled;
  followUp.setAttribute("aria-label", "Continue conversation enabled");
  followUp.addEventListener("change", () => v.followUpEnabled = followUp.checked);
  host.appendChild(labeledRow("Continue conversation", followUp));

  const capture = el("input"); capture.type = "checkbox"; capture.checked = !!v.captureOnWake;
  capture.setAttribute("aria-label", "Capture audio on wake");
  capture.addEventListener("change", () => v.captureOnWake = capture.checked);
  host.appendChild(labeledRow("Capture audio on wake", capture));

  const capThresh = el("input"); capThresh.type = "number"; capThresh.min = 10; capThresh.max = 95;
  capThresh.value = v.captureThreshold;
  capThresh.addEventListener("change", () => v.captureThreshold = Math.round(parseFloat(capThresh.value) || 40));
  host.appendChild(labeledRow("Capture score floor", capThresh));

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

  const wakeVol = el("input"); wakeVol.type = "number"; wakeVol.min = 0; wakeVol.max = 100; wakeVol.value = v.wakeSoundVolume;
  wakeVol.addEventListener("change", () => v.wakeSoundVolume = Math.round(parseFloat(wakeVol.value) || 0));
  host.appendChild(labeledRow("Wake sound volume", wakeVol));

  const wakePreview = el("button", "ghost small", "Preview");
  wakePreview.type = "button";
  wakePreview.addEventListener("click", async () => {
    // Audition the CURRENT (possibly unsaved) volume. Best-effort; ignore failures.
    wakePreview.disabled = true;
    try {
      await api("POST", "/api/voice/preview-wake", { volume: v.wakeSoundVolume });
    } catch (e) { /* device may be unreachable; nothing to persist */ }
    finally { wakePreview.disabled = false; }
  });
  host.appendChild(wakePreview);

  host.appendChild(el("div", "muted",
    "Home Assistant should auto-discover the satellite; otherwise add the Wyoming Protocol integration at <this-device-ip>:10600. " +
    "The wake word is now detected on-device — pick it above (sensitivity 10–95, higher = stricter, clamped on save). " +
    "Capture audio on wake saves the ~10 s before each detection to the device for false-positive tuning; it stays on the device, and is meant to be left off. " +
    "Its score floor is separate from and lower than the wake sensitivity, so near-misses are collected without triggering a wake. "  +
    "HA's own streaming wake-word setting is no longer used, and mic audio only reaches HA after the wake word is heard. " +
    "Wake sound: chirps when the wake word is heard and when it stops listening; volume 0 disables it. " +
    "Continue conversation: when the assistant's reply is a question, the mic reopens for your answer without a new wake word (up to 3 rounds; applies immediately, no restart). " +
    "If the on-device models fail to load, the satellite silently falls back to HA-side wake."));
}

function renderSendspin() {
  const host = document.getElementById("sendspin");
  clear(host);
  // Defensive default for configs saved before Sendspin existed (same pattern as Media/Night).
  if (!config.sendspin) config.sendspin = { enabled: false, syncDelayMs: 0, serverAddress: "" };
  const s = config.sendspin;
  if (typeof s.syncDelayMs !== "number") s.syncDelayMs = 0;
  if (s.serverAddress == null) s.serverAddress = "";
  if (s.maToken == null) s.maToken = "";
  if (s.maUser == null) s.maUser = "";

  const toggle = el("input"); toggle.type = "checkbox"; toggle.checked = !!s.enabled;
  toggle.setAttribute("aria-label", "Sendspin sync playback enabled");
  toggle.addEventListener("change", () => s.enabled = toggle.checked);
  host.appendChild(labeledRow("Synced playback (Sendspin)", toggle));

  const delay = el("input"); delay.type = "number"; delay.min = -2000; delay.max = 2000;
  delay.value = s.syncDelayMs;
  delay.addEventListener("change", () => s.syncDelayMs = Math.round(parseFloat(delay.value) || 0));
  host.appendChild(labeledRow("Sync delay (ms)", delay));

  const addr = el("input"); addr.value = s.serverAddress; addr.placeholder = "auto (mDNS)";
  addr.setAttribute("autocomplete", "off");
  addr.addEventListener("change", () => s.serverAddress = addr.value.trim());
  host.appendChild(labeledRow("Server address", addr));

  const status = el("div", "muted"); status.id = "sendspin-status";
  host.appendChild(status);
  updateSendspinStatus(lastStatus);

  host.appendChild(el("div", "muted",
    "Joins a Music Assistant Sendspin group for sample-accurate multi-room synced playback. Leave the " +
    "server address blank to auto-discover the Music Assistant server via mDNS. Sync delay offsets this " +
    "device's playback by up to ±2000 ms to compensate for its own audio-pipeline latency (positive = " +
    "this device plays later; applies live, clamped on save)."));

  // Music Assistant account: sign-in enables the on-panel library browser in the media view.
  // The device exchanges the password for a token and persists token + display name itself;
  // the password is sent once and never stored, so signed-in state is read back from config.
  if (!s.maToken) {
    const user = el("input"); user.placeholder = "username";
    user.setAttribute("autocomplete", "off");
    host.appendChild(labeledRow("MA username", user));
    const pass = el("input"); pass.type = "password"; pass.placeholder = "password";
    pass.setAttribute("autocomplete", "off");
    host.appendChild(labeledRow("MA password", pass));
    const err = el("div", "muted"); err.id = "sendspin-ma-error";
    const signIn = el("button", "ghost small", "Sign in");
    signIn.type = "button";
    signIn.addEventListener("click", async () => {
      err.textContent = "";
      signIn.disabled = true;
      try {
        const r = await api("POST", "/api/sendspin/login",
          { username: user.value.trim(), password: pass.value });
        if (r.status === 401) { showLogin(); return; }
        const b = await r.json().catch(() => ({}));
        if (r.ok && b.ok) { await tryLoad(); return; } // re-pull config: card re-renders signed in
        err.textContent = b.error || ("Sign-in failed (" + r.status + ")");
      } catch (e) {
        err.textContent = "Can't reach the device — is it on and connected?";
      } finally {
        signIn.disabled = false;
      }
    });
    host.appendChild(signIn);
    host.appendChild(err);
    if (!s.enabled) {
      host.appendChild(el("div", "muted", "Enable SendSpin and let it connect before signing in."));
    }
  } else {
    host.appendChild(el("div", "muted", "Signed in as " + (s.maUser || "Music Assistant user") + "."));
    const signOut = el("button", "ghost small", "Sign out");
    signOut.type = "button";
    signOut.addEventListener("click", async () => {
      signOut.disabled = true;
      try {
        const r = await api("POST", "/api/sendspin/logout");
        if (r.status === 401) { showLogin(); return; }
      } catch (e) { /* best-effort; the reload below shows the truth */ }
      await tryLoad();
    });
    host.appendChild(signOut);
  }
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
    box.appendChild(labeledRow("Charge limit %",
      entityPicker(["sensor", "number", "input_number"], slot.limit, v => slot.limit = v)));
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
    "Empty slots are dropped on save. A charge-limit entity draws a tick on the battery bar."));
}

function renderCalendars() {
  const host = document.getElementById("calendars");
  clear(host);
  const e = config.entities;
  // Defensive: old configs and server responses may omit the array entirely.
  if (!Array.isArray(e.calendars)) e.calendars = [];
  e.calendars.forEach((c, ci) => host.appendChild(renderCalendar(c, ci)));
  const add = el("button", "add", "Add calendar");
  add.type = "button";
  add.addEventListener("click", () => {
    if (e.calendars.length >= 6) return; // cap 6 (matches DashConfig.clamped)
    e.calendars.push({ entity: "", name: "", color: "blue" });
    renderCalendars();
  });
  host.appendChild(add);
  host.appendChild(el("div", "muted",
    "Up to 6 Home Assistant calendars, color-coded on the home-screen next-event card and the " +
    "3-day agenda panel. Display name defaults to the entity id when blank. Blank rows are dropped on save."));
}

function renderCalendar(c, ci) {
  const cals = config.entities.calendars;
  const box = el("div", "group");
  const head = el("div", "group-head");
  head.appendChild(el("span", "panel-name", "Calendar " + (ci + 1)));
  head.appendChild(reorderButtons(
    ci !== 0, ci !== cals.length - 1,
    () => { const t = cals[ci]; cals[ci] = cals[ci - 1]; cals[ci - 1] = t; renderCalendars(); },
    () => { const t = cals[ci]; cals[ci] = cals[ci + 1]; cals[ci + 1] = t; renderCalendars(); },
  ));
  const del = el("button", "ghost small danger", "Delete");
  del.type = "button";
  del.setAttribute("aria-label", "Delete calendar");
  del.addEventListener("click", () => { cals.splice(ci, 1); renderCalendars(); });
  head.appendChild(del);
  box.appendChild(head);

  box.appendChild(labeledRow("Calendar entity",
    entityPicker(["calendar"], c.entity, v => c.entity = v || "")));

  const name = el("input");
  name.value = c.name || "";
  name.setAttribute("autocomplete", "off");
  name.setAttribute("aria-label", "Calendar name");
  name.addEventListener("change", () => c.name = name.value.trim());
  box.appendChild(labeledRow("Display name", name));

  const color = el("select");
  CALENDAR_COLORS.forEach(key => {
    const o = el("option", null, key.charAt(0).toUpperCase() + key.slice(1));
    o.value = key;
    if ((c.color || "blue") === key) o.selected = true;
    color.appendChild(o);
  });
  color.addEventListener("change", () => c.color = color.value);
  box.appendChild(labeledRow("Color", color));
  return box;
}

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

// ---------- diagnostics ----------

function formatBytes(n) {
  if (!n) return "empty";
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
  return (n / (1024 * 1024)).toFixed(1) + " MB";
}

// Tags are exactly v<major>.<minor>.<versionCode> -- the last part is the versionCode.
// Anything else is "unknown", never 0: a 0 would read as older than everything and
// wrongly enable the button.
function parseTagVersionCode(tag) {
  const m = /^v\d+\.\d+\.(\d+)$/.exec(tag || "");
  return m ? parseInt(m[1], 10) : null;
}

async function checkForUpdate(latestEl, btn, msgEl, busyRef) {
  let release;
  try {
    const r = await fetch("https://api.github.com/repos/RAR/hearth/releases/latest");
    if (!r.ok) throw new Error("http " + r.status);
    release = await r.json();
  } catch (e) {
    // A display with no internet is a valid configuration, not an error state.
    latestEl.textContent = "unavailable";
    return;
  }
  const latestCode = parseTagVersionCode(release.tag_name);
  if (latestCode == null) { latestEl.textContent = "unavailable"; return; }
  latestEl.textContent = release.tag_name;

  // The device already has an update in flight (seeded by seedUpdateState): the poll loop it
  // resumed owns the button and message from here, so a since-arrived GitHub answer must not
  // re-enable the button or attach a second startUpdate handler underneath it.
  if (busyRef && busyRef.value) return;

  const currentCode = lastStatus && lastStatus.appVersionCode;
  const dirty = !!(lastStatus && lastStatus.appVersion &&
                   lastStatus.appVersion.endsWith(".dirty"));
  if (typeof currentCode !== "number") {
    msgEl.textContent = "device version unknown";
    return;
  }

  // A .dirty build at the release's own code is not that release -- offer it anyway.
  const available = latestCode > currentCode || (latestCode === currentCode && dirty);
  const asset = (release.assets || []).find(a => (a.name || "").endsWith(".apk"));
  if (!available) { btn.textContent = "Up to date"; return; }
  if (!asset) { msgEl.textContent = "release has no APK attached"; return; }

  btn.disabled = false;
  btn.addEventListener("click", () => startUpdate(asset.browser_download_url, btn, msgEl));
}

async function startUpdate(url, btn, msgEl) {
  const gen = pollGeneration;
  btn.disabled = true;
  msgEl.textContent = "starting…";
  const r = await api("POST", "/api/update", { url: url });
  if (!r.ok) {
    if (r.status === 409) {
      // Someone else's update already owns the slot (another tab, or this one raced a reload) --
      // it is genuinely in flight, so watch it rather than just reporting the rejection.
      msgEl.textContent = "an update is already in progress";
      pollUpdate(btn, msgEl, gen);
      return;
    }
    msgEl.textContent = r.status === 400
      ? "update rejected: URL not allowed"
      : "could not start (" + r.status + ")";
    btn.disabled = false;
    return;
  }
  pollUpdate(btn, msgEl, gen);
}

// Seeds button/message state from the device's own /api/update on page load or reload, so a
// browser refresh mid-download does not present an enabled button and a blank status line (which
// a click on would then 409). Sets busyRef.value so a concurrently-resolving checkForUpdate can
// tell not to override what this seed (or the poll it resumes) is showing.
async function seedUpdateState(btn, msgEl, gen, busyRef) {
  try {
    const r = await api("GET", "/api/update");
    if (gen !== pollGeneration) return;  // superseded by a re-render before this returned
    if (!r.ok) return;
    const s = await r.json();
    if (s.state === "downloading" || s.state === "verifying" || s.state === "awaiting_confirmation") {
      busyRef.value = true;
      btn.disabled = true;
      pollUpdate(btn, msgEl, gen);
    } else if (s.state === "failed") {
      msgEl.textContent = "failed: " + (s.error || "unknown");
      btn.disabled = false;
    }
    // idle: nothing to seed -- checkForUpdate's GitHub comparison governs the button.
  } catch (e) { /* fail soft: falls back to checkForUpdate's GitHub-only view */ }
}

async function pollUpdate(btn, msgEl, gen) {
  if (gen !== pollGeneration) return;  // this poll was superseded by a re-render
  const r = await api("GET", "/api/update");
  if (!r.ok) { msgEl.textContent = "lost contact with the device"; return; }
  const s = await r.json();
  if (s.state === "downloading") {
    msgEl.textContent = "downloading " + (s.progressPct || 0) + "%";
  } else if (s.state === "verifying") {
    msgEl.textContent = "verifying…";
  } else if (s.state === "awaiting_confirmation") {
    // The one message that matters: the dialog is on the DEVICE, not in this browser.
    msgEl.textContent = "Confirm the install on the device's screen.";
    return;
  } else if (s.state === "failed") {
    msgEl.textContent = "failed: " + (s.error || "unknown");
    btn.disabled = false;
    return;
  } else if (s.state === "idle") {
    // Idle is a normal resting state (nothing started yet, or the last attempt already finished
    // and the receiver reset it) -- not an anomaly worth alarming the user about.
    msgEl.textContent = "";
    btn.disabled = false;
    return;
  } else {
    msgEl.textContent = "unexpected state: " + (s.state || "unknown");
    btn.disabled = false;
    return;
  }
  setTimeout(() => pollUpdate(btn, msgEl, gen), 1000);
}

function renderDiag() {
  pollGeneration++;  // cancel any stale polls from a prior render
  const gen = pollGeneration;
  const host = document.getElementById("diag");
  clear(host);

  const version = el("input");
  version.readOnly = true;
  version.value = (lastStatus && lastStatus.appVersion) || "unknown";
  version.setAttribute("aria-label", "App version");
  version.addEventListener("focus", () => version.select());
  host.appendChild(labeledRow("App version", version));

  // Latest release. Asked of GitHub by the BROWSER, not the device: api.github.com is
  // CORS-clean, so this works even when the display itself has no route to the internet.
  const latest = el("span", "status info", "checking…");
  host.appendChild(labeledRow("Latest version", latest));

  const updateRow = el("div", "row");
  const updateBtn = el("button", "ghost", "Update");
  updateBtn.type = "button";
  updateBtn.disabled = true;
  updateRow.appendChild(updateBtn);
  const updateMsg = el("span", "status info", "");
  updateRow.appendChild(updateMsg);
  host.appendChild(updateRow);

  // Seeded from the device's own state first (it knows if an update is already running); the
  // GitHub check is independent and only takes over the button/message when the device is idle.
  const deviceBusy = { value: false };
  seedUpdateState(updateBtn, updateMsg, gen, deviceBusy);
  checkForUpdate(latest, updateBtn, updateMsg, deviceBusy);

  const size = el("span", "status info", "…");
  size.id = "diag-log-size";
  host.appendChild(labeledRow("Device log", size));
  updateDiagLogSize(lastStatus);   // paint now; the 5s poll refreshes it after

  const row = el("div", "row");

  const dl = el("button", "ghost", "Download log");
  dl.type = "button";
  dl.addEventListener("click", downloadLog);
  row.appendChild(dl);

  const clr = el("button", "ghost danger", "Clear log");
  clr.type = "button";
  clr.addEventListener("click", clearLog);
  row.appendChild(clr);

  host.appendChild(row);

  host.appendChild(el("div", "muted",
    "The device keeps its most recent log lines in a small rolling file, so a fault is still " +
    "readable after the reboot that cleared it. Include this file when reporting a problem — " +
    "it records crashes and the audio, voice, and connection subsystems."));
}

// Live size line; called after render and from the 5s poll. Sets text only.
function updateDiagLogSize(status) {
  const node = document.getElementById("diag-log-size");
  if (!node) return;
  node.textContent = status ? formatBytes(status.logBytes) : "…";
}

function downloadLog() {
  // A plain navigation, not fetch+blob: the endpoint already sends
  // Content-Disposition, and the session cookie rides along automatically.
  const a = el("a");
  a.href = "/api/log";
  a.download = "hearth-log.txt";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

async function clearLog() {
  if (!confirm("Clear the device log? Anything not yet downloaded is lost.")) return;
  setStatus("Clearing…", "busy");
  try {
    const r = await api("POST", "/api/log/clear", {});
    if (r.status === 401) { showLogin(); return; }
    if (!r.ok) { setStatus("Clear failed (" + r.status + ")", "err"); return; }
    if (lastStatus) lastStatus.logBytes = 0;
    updateDiagLogSize(lastStatus);
    setStatus("Log cleared", "ok");
  } catch (e) {
    setStatus("Can't reach the device — log not cleared.", "err");
  }
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

function updateNightLux(status) {
  const box = document.getElementById("night-lux");
  if (!box) return;
  if (status && typeof status.lux === "number") box.textContent = "Current reading: " + status.lux + " lux";
  else box.textContent = "Current reading: no sensor";
}

function updateSendspinStatus(st) {
  const node = document.getElementById("sendspin-status");
  if (!node) return;                       // card not rendered
  const s = st && st.sendspin;             // "Disconnected"|"Connecting"|"Connected"|"Playing"
  const label = { Playing: "Playing", Connected: "Connected", Connecting: "Connecting…",
                  Disconnected: "Disconnected" }[s] || "Disconnected";
  node.textContent = "Status: " + label;
}

// The base page fetches /api/status once at load; poll it here so the live lux reading refreshes.
function startStatusPoll() {
  if (statusPollStarted) return;
  statusPollStarted = true;
  setInterval(async () => {
    try {
      const r = await api("GET", "/api/status");
      if (r.ok) { lastStatus = await r.json(); updateNightLux(lastStatus); updateSendspinStatus(lastStatus); updateHaConn(lastStatus); updateDiagLogSize(lastStatus); }
    } catch (e) { /* device may be briefly unreachable; ignore */ }
  }, 5000);
}

// ---------- boot ----------
document.getElementById("login-form").addEventListener("submit", doLogin);
document.getElementById("pin-toggle").innerHTML = EYE_ICON;
document.getElementById("pin-toggle").addEventListener("click", togglePinVisibility);
document.getElementById("save").addEventListener("click", save);
document.getElementById("setup-form").addEventListener("submit", beginSetup);
window.addEventListener("hashchange", () => showPage(currentPage()));
tryLoad();
