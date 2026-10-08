"use strict";

/**
 * Cabal demo site, "Line Sheet": every cab is a line on one shared shift clock. Two modes,
 * one renderer: "Demo plans" (GET /api/plans) and "Try it live" (POST /api/sandbox/plan,
 * saves nothing). Both responses are normalised into one plan/office shape for renderAll.
 * API data is only written with textContent / DOM APIs, and styling from JS goes through
 * the CSSOM, so the strict CSP holds.
 */

const shell = document.getElementById("shell");
const mapWrapEl = document.getElementById("map-wrap");
const mapEl = document.getElementById("map");
const mapHintEl = document.getElementById("map-hint");
const panelScrollEl = document.getElementById("panel-scroll");
const sheetHandleEl = document.getElementById("sheet-handle");
const panelEl = document.getElementById("panel");
const panelHeadEl = document.getElementById("panel-head");

const modeDemoPillEl = document.getElementById("mode-demo-pill");
const modeSandboxPillEl = document.getElementById("mode-sandbox-pill");
const demoModeEl = document.getElementById("demo-mode");
const sandboxModeEl = document.getElementById("sandbox-mode");
const pillsEl = document.getElementById("plan-pills");

const boardEl = document.getElementById("board");
const boardTitleEl = document.getElementById("board-title");
const summaryEl = document.getElementById("summary-bar");
const notSavedEl = document.getElementById("sandbox-not-saved");
const stateEl = document.getElementById("state-message");
const detailEl = document.getElementById("plan-detail");
const rulerTicksEl = document.getElementById("ruler-ticks");
const scrubRangeEl = document.getElementById("scrub-range");
const playBtn = document.getElementById("play-btn");
const readoutEl = document.getElementById("readout");
const readoutTimeEl = document.getElementById("readout-time");
const readoutMetaEl = document.getElementById("readout-meta");
const captionEl = document.getElementById("lines-caption");
const cabListEl = document.getElementById("cab-list");

const shiftPillsEl = document.getElementById("shift-pills");
const fleetPillsEl = document.getElementById("fleet-pills");
const randomRidersBtn = document.getElementById("random-riders-btn");
const womenPillEl = document.getElementById("women-pill");
const clearRidersBtn = document.getElementById("clear-riders-btn");
const riderCounterEl = document.getElementById("rider-counter");
const trialHintEl = document.getElementById("trial-hint");
const planItBtn = document.getElementById("plan-it-btn");

const SANDBOX_MAX_RIDERS = 40;
const SANDBOX_RANDOM_RADIUS_KM = 15;
const SANDBOX_RANDOM_WOMAN_SHARE = 1 / 3;
/** Fixed sandbox office; matches SandboxService on the server. */
const SANDBOX_OFFICE_NAME = "Manyata Tech Park";
/** routing.default-max-ride-minutes; SandboxPlanResponse does not carry the cap. */
const SANDBOX_RIDE_CAP_MINUTES = 90;

const SHEETS = ["peek", "half", "full"];
const SHEET_PEEK_PX = 216;
const SHEET_TOP_GAP_PX = 64;
const SHEET_DRAG_TAP_PX = 6;
const SHEET_FLING_PX_PER_MS = 0.5;
const SHEET_VELOCITY_WINDOW_MS = 80;

/** Playback: 80 ms per shift minute, clamped, so a 75 minute shift plays in 6 s. */
const PLAY_MS_PER_MINUTE = 80;
const PLAY_MIN_MS = 5000;
const PLAY_MAX_MS = 9000;
const PLAY_HOLD_MS = 600;
const DISC_FADE_MS = 200;
const AUTOPLAY_DELAY_MS = 1500;

const mobileMq = window.matchMedia("(max-width: 767px)");
const reducedMq = window.matchMedia("(prefers-reduced-motion: reduce)");
let reducedMotion = reducedMq.matches;

let map = null;
let routeLayers = new Map(); // cabNumber -> polyline
let routePts = new Map(); // cabNumber -> [[lat, lng], ...]
let stopMarkers = new Map(); // cabNumber -> [{ marker, stop }]
let drawing = new Map(); // cabNumber -> polyline whose draw-in is still running
let markerLayer = null;
let casingLayer = null;
let casingFor = null;
let selectedPlanId = null;
let selectedCabNumber = null;
let hoverCabNumber = null;
let timeline = null;
let currentPlan = null; // { direction, riderCount } of the plan on screen
let lineRefs = new Map(); // cabNumber -> per-row elements the clock updates
let cabMarkers = new Map(); // cabNumber -> { marker, node } map disc, on the map only while moving
let cabLayer = null;
let clockT = null; // minutes on the shift clock, null while the clock is off
let lastMinute = null; // integer minute last pushed to the DOM
let movingNow = []; // lineRefs of the cabs whose discs are on the map
let playing = false;
let rafId = 0;
let playStartWall = 0;
let playStartT = 0;
let playIconShown = null;
let endTimer = null;
let autoplayTimer = null;
let autoplayUsed = false;
let userInteracted = false;
let sheetDrag = null;
let suppressClick = false;
let busySecondsEl = null;
let initializing = false;
let sheetState = "half";
let resizeTimer = null;

let mode = "demo"; // "demo" | "sandbox"
let sandboxRiders = []; // { lat, lng, woman, fresh }
let sandboxPinLayer = null; // input-pin markers, shown whenever there is no computed plan yet
let sandboxHasPlan = false;
let sandboxShift = { time: "07:30", direction: "PICKUP" };
let sandboxFleet = "SEDANS";
let womenNext = false;
let planning = false;
let sandboxRunId = 0; // bumped whenever an in-flight plan result must be ignored
let cooldownLeft = 0;
let busyTimer = null;

initSheet();
wireModePills();
wireSandboxControls();
wireClock();
window.addEventListener("resize", () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(renderTicks, 150);
});
reducedMq.addEventListener("change", (e) => {
  reducedMotion = e.matches;
  if (reducedMotion) {
    cancelAutoplay();
  }
});
window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", styleRoutes);
init();

/* Small helpers */

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

function pad2(n) {
  return String(n).padStart(2, "0");
}

function setState(state) {
  shell.dataset.state = state;
}

async function fetchJson(url) {
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) {
    throw new Error(`request to ${url} failed with ${res.status}`);
  }
  return res.json();
}

function formatTime(isoLocalDateTime) {
  // Backend LocalDateTime serialises as "yyyy-MM-ddTHH:mm:ss"; no timezone maths needed.
  return isoLocalDateTime.slice(11, 16);
}

function formatHHmm(localTime) {
  return localTime.slice(0, 5);
}

/** "SEDAN" -> "Sedan"; short names such as "SUV" stay as they are. */
function vehicleLabel(name) {
  return name.length <= 3 ? name : name.charAt(0).toUpperCase() + name.slice(1).toLowerCase();
}

function windowLabel(stop, direction) {
  if (direction === "PICKUP" && stop.earliestPickup) {
    return `after ${formatHHmm(stop.earliestPickup)}`;
  }
  if (direction === "DROP" && stop.latestDrop) {
    return `by ${formatHHmm(stop.latestDrop)}`;
  }
  return null;
}

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function sentence(text) {
  const t = text.trim();
  const s = t.charAt(0).toUpperCase() + t.slice(1);
  return /[.!?]$/.test(s) ? s : `${s}.`;
}

/* Time math: ETAs live on one absolute minute axis, so midnight needs no special case. */

/** "2026-10-05T07:41:00" -> minutes since epoch, timezone free (UTC maths on local fields). */
function toMin(iso) {
  const [d, t] = iso.split("T");
  const [y, mo, da] = d.split("-").map(Number);
  const [h, mi, s = 0] = t.split(":").map(Number);
  return Date.UTC(y, mo - 1, da, h, mi, s) / 60000;
}

function minToHHmm(m) {
  const day = ((Math.round(m) % 1440) + 1440) % 1440;
  return pad2(Math.floor(day / 60)) + ":" + pad2(day % 60);
}

/** A LocalTime window ("06:15" or "06:15:00") placed on the same day as the ETA, nearest wins. */
function windowMin(lt, etaMin) {
  const [h, mi] = lt.split(":").map(Number);
  let w = Math.floor(etaMin / 1440) * 1440 + h * 60 + mi;
  if (w - etaMin > 720) {
    w -= 1440;
  }
  if (etaMin - w > 720) {
    w += 1440;
  }
  return w;
}

/**
 * One shared axis for every cab. tlCab = { events: [{ t, lat, lng, stopIndex }], start, end };
 * stopIndex is -1 for the office. Returns null when no cab has a stop.
 */
function buildTimeline(plan, office) {
  const pickup = plan.direction === "PICKUP";
  const cabs = new Map();
  let rawStart = Infinity;
  let rawEnd = -Infinity;
  for (const cab of plan.cabs) {
    if (cab.stops.length === 0) {
      continue;
    }
    const stops = cab.stops.map((s, i) => ({ t: toMin(s.eta), lat: s.latitude, lng: s.longitude, stopIndex: i }));
    const officeT = cab.officeTime ? toMin(cab.officeTime) : pickup ? stops[stops.length - 1].t : stops[0].t;
    const officeEv = { t: officeT, lat: office.latitude, lng: office.longitude, stopIndex: -1 };
    const events = pickup ? [...stops, officeEv] : [officeEv, ...stops];
    for (let i = 1; i < events.length; i++) {
      events[i].t = Math.max(events[i].t, events[i - 1].t);
    }
    const tlCab = { events, start: events[0].t, end: events[events.length - 1].t, legs: cab.roadLegs ? measureLegs(cab.roadLegs) : null };
    rawStart = Math.min(rawStart, tlCab.start);
    rawEnd = Math.max(rawEnd, tlCab.end);
    cabs.set(cab.cabNumber, tlCab);
  }
  if (cabs.size === 0) {
    return null;
  }
  const start = Math.floor(rawStart / 15) * 15;
  let end = Math.ceil(rawEnd / 15) * 15;
  if (end - start < 30) {
    end = start + 30;
  }
  return { start, end, span: end - start, cabs };
}

/** Cumulative distance along each leg, computed once; flat-earth units are fine at city scale. */
function measureLegs(roadLegs) {
  return roadLegs.map((pts) => {
    const k = Math.cos((pts[0][0] * Math.PI) / 180);
    const cum = [0];
    for (let i = 1; i < pts.length; i++) {
      cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], (pts[i][1] - pts[i - 1][1]) * k));
    }
    return { pts, cum };
  });
}

/** The point a fraction f (0..1) of the way along a measured leg, by distance. */
function legPoint(leg, f) {
  const { pts, cum } = leg;
  const total = cum[cum.length - 1];
  if (f <= 0) {
    return { lat: pts[0][0], lng: pts[0][1] };
  }
  if (f >= 1 || total === 0) {
    const end = pts[pts.length - 1];
    return { lat: end[0], lng: end[1] };
  }
  const target = f * total;
  let lo = 1;
  let hi = cum.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (cum[mid] < target) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  const span = cum[lo] - cum[lo - 1];
  const g = span > 0 ? (target - cum[lo - 1]) / span : 0;
  return { lat: pts[lo - 1][0] + (pts[lo][0] - pts[lo - 1][0]) * g, lng: pts[lo - 1][1] + (pts[lo][1] - pts[lo - 1][1]) * g };
}

function pct(tl, t) {
  return (t - tl.start) / tl.span;
}

/**
 * Cab position at minute t, clamped at both ends. Between events it follows the road leg by
 * distance when the cab has legs, and interpolates the straight line between events otherwise.
 */
function positionAt(tlCab, t) {
  const ev = tlCab.events;
  const legs = tlCab.legs;
  if (t <= ev[0].t) {
    return legs ? legPoint(legs[0], 0) : { lat: ev[0].lat, lng: ev[0].lng };
  }
  const last = ev[ev.length - 1];
  if (t >= last.t) {
    return legs ? legPoint(legs[legs.length - 1], 1) : { lat: last.lat, lng: last.lng };
  }
  let i = 0;
  while (i < ev.length - 2 && ev[i + 1].t <= t) {
    i++;
  }
  const f = (t - ev[i].t) / Math.max(1e-6, ev[i + 1].t - ev[i].t);
  if (legs) {
    return legPoint(legs[i], f);
  }
  return { lat: ev[i].lat + (ev[i + 1].lat - ev[i].lat) * f, lng: ev[i].lng + (ev[i + 1].lng - ev[i].lng) * f };
}

function playDuration(span) {
  return Math.min(PLAY_MAX_MS, Math.max(PLAY_MIN_MS, span * PLAY_MS_PER_MINUTE));
}

/** How many of the cab's stops have been reached by minute t. */
function stopsDoneAt(tlCab, t) {
  let n = 0;
  for (const ev of tlCab.events) {
    if (ev.stopIndex !== -1 && ev.t <= t) {
      n++;
    }
  }
  return n;
}

/** Riders in the cab at minute t: pickups fill up as stops are reached, drops empty out. */
function aboardAt(tlCab, t, pickup, seatsUsed) {
  if (t < tlCab.start || t >= tlCab.end) {
    return 0;
  }
  const done = stopsDoneAt(tlCab, t);
  return pickup ? done : seatsUsed - done;
}

/** A disc is on the map while its cab is between its first and last event; the selected cab also at the ends. */
function isMoving(tlCab, t, selected) {
  return selected ? t >= tlCab.start && t <= tlCab.end : t > tlCab.start && t < tlCab.end;
}

/* Boot and demo mode */

async function init() {
  initializing = true;
  setState("loading");
  hideNotice();
  detailEl.hidden = false;
  boardEl.hidden = false;
  renderBoardLoading();
  renderGhostRows();
  try {
    const plans = await fetchJson("/api/plans");
    if (plans.length === 0) {
      setState("empty");
      detailEl.hidden = true;
      boardEl.hidden = true;
      showNotice("info", "No plans yet. The demo seeds itself on deploy.");
      return;
    }
    renderShiftTabs(plans);
    await selectPlan(plans[0].id, { firstLoad: true });
  } catch (err) {
    setState("error");
    detailEl.hidden = true;
    boardEl.hidden = true;
    showNotice("error", "I couldn't load the plans. Try refreshing the page.", { retry: init });
  } finally {
    initializing = false;
  }
}

function showNotice(kind, message, opts = {}) {
  busySecondsEl = null;
  stateEl.textContent = "";
  stateEl.dataset.kind = kind;
  stateEl.appendChild(typeof message === "string" ? el("span", "", message) : message);
  if (opts.retry) {
    const retry = el("button", "ghost-button notice__retry", "Try again");
    retry.type = "button";
    retry.addEventListener("click", opts.retry);
    stateEl.appendChild(retry);
  }
  stateEl.hidden = false;
}

function hideNotice() {
  busySecondsEl = null;
  stateEl.hidden = true;
  stateEl.textContent = "";
  delete stateEl.dataset.kind;
}

function renderShiftTabs(plans) {
  pillsEl.textContent = "";
  for (const plan of plans) {
    const tab = el("button");
    tab.type = "button";
    tab.setAttribute("aria-pressed", "false");
    tab.dataset.planId = String(plan.id);
    const dir = plan.direction === "PICKUP" ? "pickup, into office" : "drop, out of office";
    tab.append(el("span", "shift-tab__time", formatTime(plan.shiftTime)), el("span", "shift-tab__dir", dir));
    tab.addEventListener("click", () => selectPlan(plan.id));
    pillsEl.appendChild(tab);
  }
}

function markSelectedPill(planId) {
  for (const pill of pillsEl.children) {
    pill.setAttribute("aria-pressed", String(Number(pill.dataset.planId) === planId));
  }
}

async function selectPlan(planId, opts = {}) {
  selectedPlanId = planId;
  markSelectedPill(planId);
  setClock(null);
  hideNotice();
  setState("loading");
  detailEl.hidden = false;
  boardEl.hidden = false;
  notSavedEl.hidden = true;
  mapWrapEl.classList.add("is-loading");
  renderBoardLoading();
  renderGhostRows();
  try {
    const rawPlan = await fetchJson(`/api/plans/${planId}`);
    const rawOffice = await fetchJson(`/api/offices/${rawPlan.officeId}`);
    if (selectedPlanId !== planId || mode !== "demo") {
      return; // a newer selection (or the other mode) has taken over
    }
    const plan = normalizePlan(rawPlan, "demo");
    renderAll(plan, normalizeOffice(rawOffice, "demo"), { firstLoad: Boolean(opts.firstLoad) });
  } catch (err) {
    if (selectedPlanId !== planId || mode !== "demo") {
      return;
    }
    setState("error");
    clearLines();
    showNotice("error", "I couldn't load this plan. Try another shift.", { retry: () => selectPlan(planId) });
  } finally {
    if (selectedPlanId === planId) {
      mapWrapEl.classList.remove("is-loading");
    }
  }
}

/* Mode switching */

function wireModePills() {
  modeDemoPillEl.addEventListener("click", () => setMode("demo"));
  modeSandboxPillEl.addEventListener("click", () => setMode("sandbox"));
}

function setMode(newMode) {
  if (mode === newMode) {
    return;
  }
  mode = newMode;
  shell.dataset.mode = newMode;
  modeDemoPillEl.setAttribute("aria-pressed", String(newMode === "demo"));
  modeSandboxPillEl.setAttribute("aria-pressed", String(newMode === "sandbox"));
  demoModeEl.hidden = newMode !== "demo";
  sandboxModeEl.hidden = newMode !== "sandbox";
  hideNotice();
  notSavedEl.hidden = true;
  setClock(null);
  syncKeyboardCue();
  if (mobileMq.matches) {
    setSheet(newMode === "sandbox" ? "peek" : "half");
  }

  if (newMode === "demo") {
    sandboxRunId++;
    planning = false;
    mapHintEl.hidden = true;
    if (selectedPlanId !== null) {
      selectPlan(selectedPlanId);
    } else if (!initializing) {
      init();
    }
    return;
  }

  mapWrapEl.classList.remove("is-loading");
  if (cooldownLeft > 0) {
    showBusyNotice();
  }
  onSandboxRidersChanged({ fit: true });
}

/* Sandbox mode: drop riders, plan them, nothing saved */

function wireSandboxControls() {
  for (const pill of shiftPillsEl.children) {
    pill.addEventListener("click", () => {
      for (const p of shiftPillsEl.children) {
        p.setAttribute("aria-pressed", String(p === pill));
      }
      sandboxShift = { time: pill.dataset.shiftTime, direction: pill.dataset.direction };
      if (!sandboxHasPlan) {
        renderPreBoard();
      }
    });
  }
  for (const pill of fleetPillsEl.children) {
    pill.addEventListener("click", () => {
      for (const p of fleetPillsEl.children) {
        p.setAttribute("aria-pressed", String(p === pill));
      }
      sandboxFleet = pill.dataset.fleet;
      if (!sandboxHasPlan) {
        renderPreBoard();
      }
    });
  }
  womenPillEl.addEventListener("click", () => {
    womenNext = !womenNext;
    womenPillEl.setAttribute("aria-pressed", String(womenNext));
  });
  randomRidersBtn.addEventListener("click", addRandomSandboxRiders);
  clearRidersBtn.addEventListener("click", clearSandboxRiders);
  planItBtn.addEventListener("click", runSandboxPlan);
}

/** Plan it button: one function decides label, disabled and style from the current state. */
function refreshPlanBtn() {
  const n = sandboxRiders.length;
  planItBtn.textContent = "";
  planItBtn.removeAttribute("aria-label");
  planItBtn.classList.remove("primary-pill--done");
  if (cooldownLeft > 0) {
    planItBtn.textContent = `Try again in ${cooldownLeft}s`;
    planItBtn.setAttribute("aria-label", "Plan it, unavailable while the demo is busy");
    planItBtn.disabled = true;
  } else if (planning) {
    planItBtn.textContent = `Routing ${n} rider${n === 1 ? "" : "s"} `;
    const dots = el("span", "dots");
    dots.setAttribute("aria-hidden", "true");
    for (let i = 0; i < 3; i++) {
      dots.appendChild(el("span"));
    }
    planItBtn.appendChild(dots);
    planItBtn.disabled = true;
  } else {
    planItBtn.appendChild(document.createTextNode(sandboxHasPlan ? "Plan again " : "Plan it "));
    const arrow = el("span", "", "→");
    arrow.setAttribute("aria-hidden", "true");
    planItBtn.appendChild(arrow);
    planItBtn.disabled = n === 0;
    planItBtn.classList.toggle("primary-pill--done", sandboxHasPlan && n > 0);
  }
}

function updateRiderCounter() {
  riderCounterEl.textContent = String(sandboxRiders.length);
  refreshPlanBtn();
}

function updateHints() {
  const full = sandboxRiders.length >= SANDBOX_MAX_RIDERS;
  const limit = "40 riders is the limit for the live demo";
  trialHintEl.textContent = full
    ? limit
    : mapWrapEl.classList.contains("is-kbd")
      ? "Press Enter to drop a rider at the crosshair."
      : "Tap the map to drop a rider. Tap a pin to remove it.";
  const showChip = mode === "sandbox" && !sandboxHasPlan && (sandboxRiders.length === 0 || full);
  mapHintEl.hidden = !showChip;
  mapHintEl.textContent = full ? limit : "Tap anywhere to drop a rider";
}

/** The crosshair for keyboard users: shown while the map has keyboard focus in Try it. */
function syncKeyboardCue() {
  mapWrapEl.classList.toggle("is-kbd", mode === "sandbox" && mapEl.matches(":focus-visible"));
  updateHints();
}

function onSandboxRidersChanged(opts = {}) {
  sandboxRunId++; // a plan still in flight no longer matches these riders
  planning = false;
  sandboxHasPlan = false;
  selectedCabNumber = null;
  notSavedEl.hidden = true;
  if (cooldownLeft === 0) {
    hideNotice();
  }
  setClock(null);
  setState("empty");
  detailEl.hidden = false;
  boardEl.hidden = false;
  renderPreBoard();
  if (sandboxRiders.length === 0) {
    renderLinesEmpty();
  } else {
    clearLines();
  }
  updateRiderCounter();
  updateHints();
  renderSandboxPins(opts);
}

function addSandboxRider(lat, lng, woman) {
  if (sandboxRiders.length >= SANDBOX_MAX_RIDERS) {
    return;
  }
  sandboxRiders.push({ lat, lng, woman: Boolean(woman), fresh: true });
}

function removeSandboxRider(rider) {
  sandboxRiders = sandboxRiders.filter((r) => r !== rider);
  onSandboxRidersChanged();
}

function clearSandboxRiders() {
  sandboxRiders = [];
  onSandboxRidersChanged({ fit: true });
}

function addRandomSandboxRiders() {
  const toAdd = Math.min(20, SANDBOX_MAX_RIDERS - sandboxRiders.length);
  for (let i = 0; i < toAdd; i++) {
    const radiusKm = SANDBOX_RANDOM_RADIUS_KM * Math.sqrt(Math.random());
    const angle = Math.random() * 2 * Math.PI;
    const officeLat = sandboxOffice().latitude;
    const officeLng = sandboxOffice().longitude;
    const dLat = (radiusKm * Math.cos(angle)) / 111;
    const dLng = (radiusKm * Math.sin(angle)) / (111 * Math.cos((officeLat * Math.PI) / 180));
    addSandboxRider(officeLat + dLat, officeLng + dLng, Math.random() < SANDBOX_RANDOM_WOMAN_SHARE);
  }
  onSandboxRidersChanged({ fit: true });
}

function sandboxOffice() {
  return { name: SANDBOX_OFFICE_NAME, latitude: 13.0475, longitude: 77.6206 };
}

function onSandboxMapClick(e) {
  if (mode !== "sandbox" || sandboxRiders.length >= SANDBOX_MAX_RIDERS) {
    return;
  }
  addSandboxRider(e.latlng.lat, e.latlng.lng, womenNext);
  onSandboxRidersChanged();
}

/** Enter on the focused map drops a rider at the crosshair (the map centre). */
function onMapKeydown(e) {
  if (e.key !== "Enter" || e.target !== mapEl || mode !== "sandbox" || sandboxRiders.length >= SANDBOX_MAX_RIDERS) {
    return;
  }
  e.preventDefault();
  const c = map.getCenter();
  addSandboxRider(c.lat, c.lng, womenNext);
  onSandboxRidersChanged();
}

/** The raw input pins: office plus one marker per rider, click to remove, no routes yet. */
function renderSandboxPins(opts = {}) {
  const office = sandboxOffice();
  ensureMap(office);
  removeSandboxPins();
  clearRouteAndMarkerLayers();
  sandboxPinLayer = L.layerGroup().addTo(map);
  addOfficeMarker(office, sandboxPinLayer);

  const allPoints = [[office.latitude, office.longitude]];
  let freshIndex = 0;
  sandboxRiders.forEach((rider, i) => {
    const marker = L.marker([rider.lat, rider.lng], {
      icon: pinIcon(rider, i + 1, rider.fresh ? freshIndex++ : -1),
      title: `Remove rider ${i + 1}`,
    });
    marker.on("click", () => removeSandboxRider(rider));
    marker.addTo(sandboxPinLayer);
    allPoints.push([rider.lat, rider.lng]);
  });
  for (const rider of sandboxRiders) {
    rider.fresh = false;
  }
  if (opts.fit) {
    fitMapToPoints(allPoints);
  }
}

function removeSandboxPins() {
  if (sandboxPinLayer) {
    map.removeLayer(sandboxPinLayer);
    sandboxPinLayer = null;
  }
}

/**
 * The status region announces this text once. The ticking seconds sit in an aria-hidden span
 * that is edited in place, so screen readers are not told again every second.
 */
function showBusyNotice() {
  const lead = "The demo is busy. I cap how many plans run at once, so try again ";
  const message = el("span");
  const spoken = el("span", "sr-only", `${lead}in a moment.`);
  const visual = el("span", "", `${lead}in `);
  visual.setAttribute("aria-hidden", "true");
  const seconds = el("span", "", String(cooldownLeft));
  visual.append(seconds, document.createTextNode(" seconds."));
  message.append(spoken, visual);
  showNotice("busy", message);
  busySecondsEl = seconds;
}

function startBusyCountdown(seconds) {
  clearInterval(busyTimer);
  cooldownLeft = seconds;
  showBusyNotice();
  refreshPlanBtn();
  busyTimer = setInterval(() => {
    cooldownLeft--;
    if (cooldownLeft <= 0) {
      cooldownLeft = 0;
      clearInterval(busyTimer);
      if (stateEl.dataset.kind === "busy") {
        hideNotice();
      }
    } else if (busySecondsEl) {
      busySecondsEl.textContent = String(cooldownLeft);
    }
    refreshPlanBtn();
  }, 1000);
}

async function runSandboxPlan() {
  if (sandboxRiders.length === 0 || planning || cooldownLeft > 0) {
    return;
  }
  const runId = ++sandboxRunId;
  hideNotice();
  planning = true;
  refreshPlanBtn();
  try {
    const body = {
      direction: sandboxShift.direction,
      shiftTime: sandboxShift.time,
      fleet: sandboxFleet,
      riders: sandboxRiders.map((r) => ({ latitude: r.lat, longitude: r.lng, woman: r.woman })),
    };
    const res = await fetch("/api/sandbox/plan", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
    });
    if (runId !== sandboxRunId) {
      return;
    }
    if (!res.ok) {
      if (res.status === 429) {
        startBusyCountdown(Math.min(parseInt(res.headers.get("Retry-After"), 10) || 30, 120));
        return;
      }
      const problem = await res.json().catch(() => null);
      if (runId !== sandboxRunId) {
        return;
      }
      const known = res.status < 500 && problem && problem.detail;
      const message = known ? sentence(problem.detail) : "I couldn't plan these riders. Try again.";
      showNotice("error", message, { retry: runSandboxPlan });
      return;
    }

    const rawPlan = await res.json();
    if (runId !== sandboxRunId) {
      return;
    }
    const plan = normalizePlan(rawPlan, "sandbox");
    const office = normalizeOffice(rawPlan, "sandbox");
    sandboxHasPlan = true;
    if (mobileMq.matches) {
      setSheet("half");
    }
    renderAll(plan, office, { firstLoad: false, sandbox: true });
    notSavedEl.hidden = false;
    updateHints();
    if (mobileMq.matches) {
      panelScrollEl.scrollTop = detailEl.offsetTop;
    }
  } catch (err) {
    if (runId === sandboxRunId) {
      showNotice("error", "I couldn't reach the planner. Try again.", { retry: runSandboxPlan });
    }
  } finally {
    if (runId === sandboxRunId) {
      planning = false;
      refreshPlanBtn();
    }
  }
}

/* Normalisation: PlanResponse and SandboxPlanResponse map to one common shape. */

function normalizePlan(raw, kind) {
  const demo = kind === "demo";
  return {
    ...raw,
    riderCount: demo ? raw.employeeCount : raw.riderCount,
    rideCapMinutes: demo ? raw.maxRideMinutes : SANDBOX_RIDE_CAP_MINUTES,
    cabs: raw.cabs.map(normalizeCab(demo)),
  };
}

/** Adds roadLegs: one [[lat, lng], ...] per route segment, or null when the API gave none that fit. */
function normalizeCab(demo) {
  return (c) => {
    const stops = [...c.stops].sort((a, b) => a.sequence - b.sequence).map((s) => normalizeStop(s, demo));
    const roadLegs = Array.isArray(c.legs) && stops.length > 0 && c.legs.length === stops.length ? c.legs.map(decodePolyline6) : null;
    // One short or empty leg would leave a gap in the line, so any of those means straight segments.
    const usable = roadLegs && roadLegs.every((leg) => leg.length >= 2);
    return { ...c, stops, roadLegs: usable ? roadLegs : null };
  };
}

/** Google polyline algorithm at precision 6, as OSRM emits it. Returns [[lat, lng], ...]. */
function decodePolyline6(str) {
  const out = [];
  let i = 0;
  let lat = 0;
  let lng = 0;
  const next = () => {
    let b;
    let shift = 0;
    let r = 0;
    do {
      b = str.charCodeAt(i++) - 63;
      r |= (b & 31) << shift;
      shift += 5;
    } while (b >= 32 && i < str.length);
    return r & 1 ? ~(r >> 1) : r >> 1;
  };
  while (i < str.length) {
    lat += next();
    lng += next();
    out.push([lat / 1e6, lng / 1e6]);
  }
  return out;
}

function normalizeStop(s, demo) {
  return {
    ...s,
    id: demo ? s.employeeId : s.riderNumber,
    name: demo ? s.employeeName : s.name,
    woman: demo ? false : Boolean(s.woman),
  };
}

function normalizeOffice(raw, kind) {
  if (kind === "demo") {
    return { name: raw.name, latitude: raw.latitude, longitude: raw.longitude };
  }
  return { name: SANDBOX_OFFICE_NAME, latitude: raw.officeLatitude, longitude: raw.officeLongitude };
}

/* Shared rendering */

function renderAll(plan, office, opts = {}) {
  setClock(null);
  selectedCabNumber = null;
  hoverCabNumber = null;
  currentPlan = { direction: plan.direction, riderCount: plan.riderCount };
  detailEl.hidden = false;
  boardEl.hidden = false;
  hideNotice();
  renderBoard(plan, office);
  timeline = buildTimeline(plan, office);
  if (!timeline) {
    setState("empty");
    clearLines();
    showNotice("info", "The planner returned no cabs for these riders.");
    if (opts.sandbox) {
      renderSandboxPins();
    } else {
      renderMap(plan, office, opts);
    }
    return;
  }
  setState("ready");
  renderRuler();
  renderLines(plan, office);
  renderMap(plan, office, opts);
  setSelectedCab(plan.cabs[0].cabNumber, { fly: false });
  if (opts.firstLoad) {
    scheduleAutoplay();
  }
}

/* Board */

function setBoardTitle(before, emphasis, after) {
  boardTitleEl.textContent = "";
  boardTitleEl.appendChild(document.createTextNode(before));
  if (emphasis) {
    boardTitleEl.append(el("em", "", emphasis), document.createTextNode(after));
  }
}

/** cells: [label, value, missed?]. One flap tile per character, flipping in with --i. */
function renderCells(cells, still) {
  summaryEl.textContent = "";
  summaryEl.classList.toggle("is-still", Boolean(still));
  let i = 0;
  for (const [label, value, missed] of cells) {
    const cell = el("div", missed ? "board__cell board__cell--missed" : "board__cell");
    cell.appendChild(el("dt", "", label));
    const dd = el("dd");
    dd.appendChild(el("span", "sr-only", value));
    for (const ch of value) {
      const tile = el("span", "flap", ch);
      tile.setAttribute("aria-hidden", "true");
      tile.style.setProperty("--i", String(i++));
      dd.appendChild(tile);
    }
    cell.appendChild(dd);
    summaryEl.appendChild(cell);
  }
}

function renderBoard(plan, office) {
  const pickup = plan.direction === "PICKUP";
  setBoardTitle(`${formatTime(plan.shiftTime)} ${pickup ? "pickup" : "drop"} `, pickup ? "into" : "out of", ` ${office.name}`);
  renderCells([
    ["Cabs", pad2(plan.cabCount)],
    ["Riders", pad2(plan.riderCount)],
    ["Km", Number(plan.totalDistanceKm).toFixed(1)],
    ["Cost", Math.round(plan.totalCost).toLocaleString("en-IN")],
    ["Missed", String(plan.windowsMissed), plan.windowsMissed > 0],
  ]);
}

function renderBoardLoading() {
  setBoardTitle("Loading the plan");
  renderCells(["Cabs", "Riders", "Km", "Cost", "Missed"].map((label) => [label, "--"]));
}

/** Try it before a plan exists: the shift being set up. */
function renderPreBoard() {
  const pickup = sandboxShift.direction === "PICKUP";
  const fleet = sandboxFleet === "SEDANS" ? "4-seat cabs" : "sedans + 2 SUVs";
  setBoardTitle(`Your shift: ${sandboxShift.time} ${pickup ? "pickup" : "drop"}, ${fleet}`);
  renderCells(
    [["Cabs", "--"], ["Riders", pad2(sandboxRiders.length)], ["Km", "--"], ["Cost", "--"], ["Missed", "--"]],
    true
  );
}

/* Ruler */

function renderRuler() {
  scrubRangeEl.max = String(timeline.span);
  scrubRangeEl.value = "0";
  renderTicks();
}

/** Ticks on multiples of the step; labels thin to every second tick when cramped. */
function renderTicks() {
  rulerTicksEl.textContent = "";
  if (!timeline) {
    return;
  }
  const { start, end, span } = timeline;
  const step = span <= 60 ? 10 : span <= 150 ? 15 : span <= 300 ? 30 : 60;
  const width = rulerTicksEl.clientWidth || 260;
  const labelEvery = width / (span / step) < 44 ? 2 : 1;
  const first = Math.ceil(start / step) * step;
  for (let t = first, k = 0; t <= end; t += step, k++) {
    const tick = el("span", "tick");
    tick.style.setProperty("--p", String(pct(timeline, t)));
    if (k % labelEvery === 0) {
      tick.appendChild(el("span", "tick__label", minToHHmm(t)));
    }
    rulerTicksEl.appendChild(tick);
  }
}

/* Shift clock: one time axis for every row, driven by play, the range input or autoplay. */

function wireClock() {
  playBtn.addEventListener("click", () => {
    if (playing) {
      pause();
    } else {
      play();
    }
  });
  scrubRangeEl.addEventListener("pointerdown", pause);
  scrubRangeEl.addEventListener("input", () => {
    if (!timeline) {
      return;
    }
    pause();
    setClock(timeline.start + Number(scrubRangeEl.value));
  });
  scrubRangeEl.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      setClock(null);
    }
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && playing) {
      pause();
    }
  });
  // Autoplay gives way as soon as the visitor does anything.
  for (const type of ["pointerdown", "keydown", "wheel"]) {
    document.addEventListener(type, noteInteraction, { capture: true, passive: true });
  }
  panelScrollEl.addEventListener("scroll", noteInteraction, { passive: true });
  syncPlayUi();
}

function noteInteraction() {
  userInteracted = true;
  cancelAutoplay();
}

function cancelAutoplay() {
  clearTimeout(autoplayTimer);
  autoplayTimer = null;
}

/** Autoplay: once per page, on the first demo plan, only for a visitor who has not touched anything. */
function scheduleAutoplay() {
  if (autoplayUsed) {
    return;
  }
  autoplayUsed = true;
  if (reducedMotion || userInteracted) {
    return;
  }
  autoplayTimer = setTimeout(() => {
    autoplayTimer = null;
    if (!userInteracted && !reducedMotion && mode === "demo" && timeline && clockT === null && !playing) {
      play();
    }
  }, AUTOPLAY_DELAY_MS);
}

function playIcon(isPlaying) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 16 16");
  svg.setAttribute("width", "16");
  svg.setAttribute("height", "16");
  svg.setAttribute("fill", "currentColor");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  if (isPlaying) {
    for (const x of [4, 9]) {
      const bar = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      bar.setAttribute("x", String(x));
      bar.setAttribute("y", "3");
      bar.setAttribute("width", "3");
      bar.setAttribute("height", "10");
      svg.appendChild(bar);
    }
  } else {
    const tri = document.createElementNS("http://www.w3.org/2000/svg", "path");
    tri.setAttribute("d", "M5 3 L13 8 L5 13 Z");
    svg.appendChild(tri);
  }
  return svg;
}

/** Button icon and label, and the readout's live region: announced while paused, silent while playing. */
function syncPlayUi() {
  playBtn.setAttribute("aria-label", playing ? "Pause" : "Play the shift");
  readoutEl.setAttribute("aria-live", playing ? "off" : "polite");
  if (playIconShown !== playing) {
    playIconShown = playing;
    playBtn.textContent = "";
    playBtn.appendChild(playIcon(playing));
  }
}

function play() {
  if (!timeline) {
    return;
  }
  cancelAutoplay();
  clearTimeout(endTimer);
  for (const disc of cabMarkers.values()) {
    disc.node.classList.remove("is-fading");
  }
  if (clockT === null || clockT >= timeline.end) {
    lastMinute = null;
    setClock(timeline.start);
  }
  playing = true;
  playStartWall = performance.now();
  playStartT = clockT;
  syncPlayUi();
  rafId = requestAnimationFrame(tick);
}

/** Stops the animation and leaves the clock where it is. */
function pause() {
  cancelAnimationFrame(rafId);
  rafId = 0;
  clearTimeout(endTimer);
  playing = false;
  syncPlayUi();
}

function tick(now) {
  const { end, span } = timeline;
  const elapsed = Math.max(0, now - playStartWall);
  const t = Math.min(end, playStartT + (elapsed / playDuration(span)) * span);
  setClock(t);
  if (t < end) {
    rafId = requestAnimationFrame(tick);
    return;
  }
  rafId = 0;
  playing = false;
  syncPlayUi();
  // Hold on the last frame, fade the discs, then return to the overview.
  endTimer = setTimeout(() => {
    for (const disc of cabMarkers.values()) {
      disc.node.classList.add("is-fading");
    }
    endTimer = setTimeout(() => setClock(null), reducedMotion ? 0 : DISC_FADE_MS);
  }, PLAY_HOLD_MS);
}

/**
 * Moves the clock to minute t, or turns it off with null. Every call is cheap (one custom
 * property and the disc positions); rows, seats, readout and range only change when the
 * integer minute does.
 */
function setClock(t) {
  if (t === null) {
    resetClock();
    return;
  }
  if (!timeline) {
    return;
  }
  clockT = t;
  shell.dataset.clock = "on";
  cabListEl.style.setProperty("--t", String(pct(timeline, t)));
  const minute = Math.floor(t);
  if (minute !== lastMinute) {
    lastMinute = minute;
    refreshMinute(minute);
  }
  for (const ref of movingNow) {
    const p = positionAt(ref.tl, t);
    ref.disc.marker.setLatLng([p.lat, p.lng]);
  }
}

function resetClock() {
  pause();
  cancelAutoplay();
  clockT = null;
  lastMinute = null;
  movingNow = [];
  shell.dataset.clock = "off";
  readoutEl.hidden = true;
  scrubRangeEl.value = "0";
  scrubRangeEl.removeAttribute("aria-valuetext");
  if (cabLayer) {
    cabLayer.clearLayers();
  }
  for (const ref of lineRefs.values()) {
    setSeats(ref, ref.cab.seatsUsed);
    setDone(ref, 0);
    ref.moving = false;
    ref.cabDot.classList.remove("is-on");
  }
}

function refreshMinute(minute) {
  const pickup = currentPlan.direction === "PICKUP";
  let total = 0;
  let lastStart = -Infinity;
  movingNow = [];
  for (const [n, ref] of lineRefs) {
    ref.disc = cabMarkers.get(n);
    const aboard = aboardAt(ref.tl, minute, pickup, ref.cab.seatsUsed);
    total += aboard;
    lastStart = Math.max(lastStart, ref.tl.start);
    setSeats(ref, aboard);
    setDone(ref, stopsDoneAt(ref.tl, minute));
    const selected = n === selectedCabNumber;
    const moving = isMoving(ref.tl, minute, selected);
    if (moving !== ref.moving) {
      ref.moving = moving;
      ref.cabDot.classList.toggle("is-on", moving);
      if (moving && cabLayer) {
        cabLayer.addLayer(ref.disc.marker);
      } else if (cabLayer) {
        cabLayer.removeLayer(ref.disc.marker);
      }
    }
    ref.disc.node.classList.toggle("is-selected", selected);
    if (moving) {
      movingNow.push(ref);
    }
  }

  const hhmm = minToHHmm(minute);
  const delivered = total === 0 && minute >= lastStart;
  readoutTimeEl.textContent = hhmm;
  readoutMetaEl.textContent = "";
  let spoken;
  if (delivered) {
    readoutMetaEl.appendChild(document.createTextNode("all delivered"));
    spoken = "all riders delivered";
  } else if (pickup) {
    readoutMetaEl.append(document.createTextNode(`${total} of ${currentPlan.riderCount} `), el("em", "", "on board"));
    spoken = `${total} of ${currentPlan.riderCount} riders on board`;
  } else {
    readoutMetaEl.append(document.createTextNode(`${total} `), el("em", "", "still riding"));
    spoken = `${total} riders still on board`;
  }
  readoutEl.hidden = false;
  scrubRangeEl.value = String(Math.min(timeline.span, minute - timeline.start));
  scrubRangeEl.setAttribute("aria-valuetext", `${hhmm}, ${spoken}`);
}

/** Seat dots: the first `aboard` are on, the rest of the assigned seats are rings, spare seats stay dashed. */
function setSeats(ref, aboard) {
  if (ref.aboard === aboard) {
    return;
  }
  ref.aboard = aboard;
  ref.seats.forEach((seat, i) => {
    seat.className = `seat ${i >= ref.cab.seatsUsed ? "seat--free" : i < aboard ? "seat--on" : "seat--due"}`;
  });
}

/** Marks the first n stops (strip dot and station row) as reached. */
function setDone(ref, n) {
  if (ref.done === n) {
    return;
  }
  const reached = n > ref.done;
  for (let i = Math.min(n, ref.done); i < Math.max(n, ref.done); i++) {
    ref.stops[i].classList.toggle("is-done", reached);
    ref.stations[i].classList.toggle("is-done", reached);
  }
  ref.done = n;
}

/* Lines */

function clearLines() {
  cabListEl.textContent = "";
  lineRefs.clear();
}

function renderGhostRows() {
  clearLines();
  for (let i = 0; i < 4; i++) {
    const row = el("li", "line line--ghost");
    row.setAttribute("aria-hidden", "true");
    row.append(el("span", "ghost-badge"), el("span", "ghost-rail"));
    cabListEl.appendChild(row);
  }
}

function renderLinesEmpty() {
  clearLines();
  const empty = el("li", "lines__empty");
  for (let i = 0; i < 3; i++) {
    empty.appendChild(el("span", "ghost-rail"));
  }
  empty.appendChild(el("p", "", "No riders yet. Tap the map to drop one, or add 20 at random."));
  cabListEl.appendChild(empty);
}

function renderLines(plan, office) {
  const pickups = plan.direction === "PICKUP";
  captionEl.textContent = `Each line is one cab on the shift clock. Dots are ${pickups ? "pickups" : "drops"}, the square is the office.`;
  clearLines();
  plan.cabs.forEach((cab, i) => {
    cabListEl.appendChild(buildLineRow(cab, timeline.cabs.get(cab.cabNumber), plan, office, i));
  });
}

function cabOfficeLabel(cab, tlCab) {
  if (cab.officeTime) {
    return formatTime(cab.officeTime);
  }
  return tlCab ? minToHHmm(tlCab.events.find((ev) => ev.stopIndex === -1).t) : "";
}

function buildLineRow(cab, tlCab, plan, office, index) {
  const pickup = plan.direction === "PICKUP";
  const missed = cab.stops.filter((s) => s.windowMissed).length;
  const officeLabel = cabOfficeLabel(cab, tlCab);

  const ref = tlCab
    ? {
        cab,
        tl: tlCab,
        seats: [],
        stops: [],
        stations: [],
        cabDot: null,
        disc: null,
        aboard: cab.seatsUsed,
        done: 0,
        moving: false,
      }
    : null;
  const li = el("li", missed > 0 ? "line has-missed" : "line");
  li.dataset.cab = String(cab.cabNumber);
  li.style.setProperty("--i", String(Math.min(index, 10)));

  const head = el("button", "line__head");
  head.type = "button";
  head.setAttribute("aria-expanded", "false");
  head.setAttribute("aria-controls", `cab-${cab.cabNumber}-detail`);
  head.setAttribute(
    "aria-label",
    `Cab ${cab.cabNumber}, ${vehicleLabel(cab.vehicleType)}, ${cab.seatsUsed} of ${cab.seats} seats, ` +
      `${pickup ? "arrives" : "leaves"} ${officeLabel}` +
      (cab.escortRequired ? ", guard on board" : "") +
      (missed > 0 ? `, ${missed} window${missed === 1 ? "" : "s"} missed` : "")
  );

  head.appendChild(el("span", "line__badge", pad2(cab.cabNumber)));

  const title = el("span", "line__title");
  title.append(el("span", "line__vehicle", vehicleLabel(cab.vehicleType)), buildSeats(cab, false, ref));
  if (cab.escortRequired) {
    title.appendChild(el("span", "chip chip--guard", "guard"));
  }
  head.appendChild(title);

  const when = el("span", "line__when", pickup ? `→ ${officeLabel}` : `${officeLabel} →`);
  if (missed > 0) {
    when.appendChild(el("span", "chip chip--missed", `${missed} missed`));
  }
  head.appendChild(when);

  if (tlCab) {
    head.appendChild(buildStrip(tlCab, cab, ref));
  }
  li.append(head, buildDetail(cab, plan, office, tlCab, ref));
  if (ref) {
    lineRefs.set(cab.cabNumber, ref);
  }

  head.addEventListener("click", () => setSelectedCab(cab.cabNumber));
  head.addEventListener("pointerenter", (e) => {
    if (e.pointerType === "mouse") {
      setHoverCab(cab.cabNumber);
    }
  });
  head.addEventListener("pointerleave", () => setHoverCab(null));
  return li;
}

/** One cab on the shared clock: a rail from first event to last, stops, office square. */
function buildStrip(tlCab, cab, ref) {
  const strip = el("span", "strip");
  strip.setAttribute("aria-hidden", "true");
  const rail = el("span", "strip__rail");
  rail.style.setProperty("--a", String(pct(timeline, tlCab.start)));
  rail.style.setProperty("--b", String(pct(timeline, tlCab.end)));
  strip.appendChild(rail);
  // Office first in the DOM so a stop that lands on the same minute is drawn above it.
  const officeEvents = tlCab.events.filter((ev) => ev.stopIndex === -1);
  const ordered = [...officeEvents, ...tlCab.events.filter((ev) => ev.stopIndex !== -1)];
  for (const ev of ordered) {
    const isOffice = ev.stopIndex === -1;
    const node = el("span", isOffice ? "strip__office" : "strip__stop");
    node.style.setProperty("--p", String(pct(timeline, ev.t)));
    if (!isOffice) {
      ref.stops[ev.stopIndex] = node;
      if (cab.stops[ev.stopIndex].windowMissed) {
        node.classList.add("is-missed");
      }
    }
    strip.appendChild(node);
  }
  ref.cabDot = el("span", "strip__cab");
  strip.append(el("span", "strip__now"), ref.cabDot);
  return strip;
}

/** With a ref, the dots are kept so the clock can restyle them. */
function buildSeats(cab, large, ref) {
  const seats = el("span", large ? "seats seats--lg" : "seats");
  seats.setAttribute("aria-hidden", "true");
  for (let i = 0; i < cab.seats; i++) {
    const seat = el("span", i < cab.seatsUsed ? "seat seat--on" : "seat seat--free");
    seats.appendChild(seat);
    if (ref) {
      ref.seats.push(seat);
    }
  }
  return seats;
}

function buildDetail(cab, plan, office, tlCab, ref) {
  const pickup = plan.direction === "PICKUP";
  const detail = el("div", "line__detail");
  detail.id = `cab-${cab.cabNumber}-detail`;
  detail.setAttribute("role", "region");
  detail.setAttribute("aria-label", `Cab ${cab.cabNumber} stops`);
  detail.hidden = true;

  const stations = el("ol", "stations");
  const rows = cab.stops.map((stop, index) => ({ stop, index }));
  const ordered = pickup ? [...rows, { office }] : [{ office }, ...rows];
  for (const row of ordered) {
    if (row.office) {
      stations.appendChild(buildOfficeStation(row.office, cab, tlCab, pickup));
      continue;
    }
    const station = buildStopStation(row.stop, plan);
    if (ref) {
      ref.stations[row.index] = station;
    }
    stations.appendChild(station);
  }
  detail.append(stations, buildLedger(cab, plan));
  return detail;
}

function buildStopStation(stop, plan) {
  const li = el("li", "station");
  li.appendChild(el("span", "station__eta", formatTime(stop.eta)));
  const mark = el("span", "station__mark");
  mark.appendChild(el("span", "station__dot"));
  li.appendChild(mark);

  const text = el("div", "station__text");
  const name = el("span", "station__name");
  if (stop.woman) {
    name.appendChild(el("span", "woman-dot"));
  }
  name.appendChild(document.createTextNode(stop.name));
  if (stop.woman) {
    name.appendChild(el("span", "sr-only", ", woman rider"));
  }
  text.appendChild(name);
  const windowText = windowLabel(stop, plan.direction);
  if (windowText) {
    text.appendChild(
      stop.windowMissed
        ? el("span", "chip chip--missed", `${windowText}, missed`)
        : el("span", "chip chip--window", windowText)
    );
  }
  li.append(text, el("span", "station__ride", `${Math.round(stop.rideMinutes)} min ride`));
  return li;
}

function buildOfficeStation(office, cab, tlCab, pickup) {
  const li = el("li", "station");
  li.appendChild(el("span", "station__eta", cabOfficeLabel(cab, tlCab)));
  const mark = el("span", "station__mark");
  mark.appendChild(el("span", "station__terminus"));
  li.appendChild(mark);
  const text = el("div", "station__text");
  text.appendChild(el("span", "station__name", office.name));
  li.append(text, el("span", "station__ride", pickup ? "arrives" : "leaves"));
  return li;
}

function buildLedger(cab, plan) {
  const ledger = el("dl", "ledger");
  const add = (label, ...content) => {
    ledger.appendChild(el("dt", "", label));
    const dd = el("dd");
    for (const node of content) {
      dd.appendChild(typeof node === "string" ? document.createTextNode(node) : node);
    }
    ledger.appendChild(dd);
  };

  add("Seats", buildSeats(cab, true), `${cab.seatsUsed} of ${cab.seats}`);

  const longest = Math.round(cab.maxRideMinutes);
  const f = plan.rideCapMinutes > 0 ? cab.maxRideMinutes / plan.rideCapMinutes : 0;
  const bar = el("span", f > 1 ? "ridebar ridebar--over" : "ridebar");
  const fill = el("span", "ridebar__fill");
  fill.style.setProperty("--f", String(Math.min(f, 1)));
  bar.appendChild(fill);
  add("Longest ride", bar, `${longest} of ${plan.rideCapMinutes} min`);

  const withWindow = cab.stops.filter((s) => windowLabel(s, plan.direction));
  const missed = withWindow.filter((s) => s.windowMissed).length;
  const met = withWindow.length - missed;
  if (withWindow.length === 0) {
    add("Windows", "none set");
  } else if (missed === 0) {
    add("Windows", `${met} met`);
  } else {
    add("Windows", `${met} met, `, el("span", "is-missed-text", `${missed} missed`));
  }

  if (cab.escortRequired) {
    add(
      "Night guard",
      el("span", "guard-mark"),
      "Guard on board. The route could not avoid leaving a woman alone in the cab at night."
    );
  } else {
    add("Night guard", "Not needed");
  }
  add("Distance", `${Number(cab.distanceKm).toFixed(1)} km`);
  add("Cost", Math.round(cab.cost).toLocaleString("en-IN"));
  return ledger;
}

/* Selection */

function setHoverCab(cabNumber) {
  hoverCabNumber = cabNumber;
  for (const li of cabListEl.children) {
    li.classList.toggle("is-hover", cabNumber !== null && Number(li.dataset.cab) === cabNumber);
  }
  styleRoutes();
}

function setSelectedCab(cabNumber, opts = {}) {
  const { fly = true, scroll = false } = opts;
  const previous = selectedCabNumber;
  selectedCabNumber = cabNumber;
  let selectedRow = null;
  for (const li of cabListEl.children) {
    if (!li.dataset.cab) {
      continue;
    }
    const isSelected = Number(li.dataset.cab) === cabNumber;
    li.classList.toggle("is-selected", isSelected);
    li.querySelector(".line__head").setAttribute("aria-expanded", String(isSelected));
    li.querySelector(".line__detail").hidden = !isSelected;
    if (isSelected) {
      selectedRow = li;
    }
  }
  if (previous !== cabNumber) {
    for (const { marker } of stopMarkers.get(previous) || []) {
      marker.setIcon(stopIcon());
    }
    (stopMarkers.get(cabNumber) || []).forEach(({ marker }, i) => marker.setIcon(stationIcon(i)));
  }
  styleRoutes();
  if (clockT !== null) {
    lastMinute = null; // the selected cab's disc and colours depend on the selection
    setClock(clockT);
  }
  if (scroll && selectedRow) {
    selectedRow.scrollIntoView({ block: "nearest" });
  }
  if (fly) {
    if (mobileMq.matches && sheetState === "peek") {
      setSheet("half");
    }
    flyToCab(cabNumber);
  }
}

function flyToCab(cabNumber) {
  const pts = routePts.get(cabNumber);
  if (!map || !pts) {
    return;
  }
  map.fitBounds(pts, {
    paddingTopLeft: [24, 24],
    paddingBottomRight: [24, sheetPad()],
    maxZoom: 14,
    animate: true,
    duration: 0.5,
  });
}

/* Map */

function routePoints(plan, office, cab) {
  if (cab.roadLegs) {
    // Each leg starts where the previous one ended, so its first point is dropped.
    return cab.roadLegs.flatMap((leg, i) => (i === 0 ? leg : leg.slice(1)));
  }
  const stopPoints = cab.stops.map((s) => [s.latitude, s.longitude]);
  const officePoint = [office.latitude, office.longitude];
  return plan.direction === "PICKUP" ? [...stopPoints, officePoint] : [officePoint, ...stopPoints];
}

/** All divIcons take a DOM element as html, so nothing from the API is ever parsed as markup. */
function stopIcon() {
  return L.divIcon({ className: "mk", html: el("div", "mk-stop"), iconSize: [8, 8] });
}

function stationIcon(order) {
  const dot = el("div", "mk-station");
  dot.style.setProperty("--i", String(order));
  return L.divIcon({ className: "mk", html: dot, iconSize: [12, 12] });
}

function officeIcon() {
  return L.divIcon({ className: "mk", html: el("div", "mk-office"), iconSize: [18, 18] });
}

/** Pre-plan rider pin. freshIndex >= 0 plays the drop-in, staggered; -1 is a settled pin. */
function pinIcon(rider, number, freshIndex) {
  const pin = el("div", "mk-pin");
  pin.classList.toggle("is-woman", rider.woman);
  if (freshIndex >= 0) {
    pin.classList.add("is-fresh");
    pin.style.setProperty("--i", String(freshIndex));
  }
  pin.appendChild(el("span", "mk-pin__n", String(number)));
  return L.divIcon({ className: "mk", html: pin, iconSize: [20, 20] });
}

/** Map disc for a moving cab; it joins the map only while the clock has the cab on the road. */
function buildCabDisc(cab, startPoint) {
  const node = el("div", cab.escortRequired ? "mk-cab is-escort" : "mk-cab", String(cab.cabNumber));
  const marker = L.marker(startPoint, {
    icon: L.divIcon({ className: "mk", html: node, iconSize: [22, 22] }),
    interactive: false,
    keyboard: false,
    zIndexOffset: 1000,
  });
  return { marker, node };
}

function addOfficeMarker(office, layer) {
  const marker = L.marker([office.latitude, office.longitude], {
    icon: officeIcon(),
    interactive: false,
    keyboard: false,
  });
  marker.bindTooltip(el("span", "mk-office__label", office.name), {
    permanent: true,
    direction: "right",
    offset: [12, 0],
    className: "mk-office__tip",
  });
  marker.addTo(layer);
}

function buildStopPopup(stop) {
  const wrap = el("div", "popup");
  wrap.append(el("strong", "", stop.name), el("div", "popup__eta", `ETA ${formatTime(stop.eta)}`));
  return wrap;
}

function buildSandboxPopup(stop) {
  const wrap = buildStopPopup(stop);
  const remove = el("button", "popup__remove", "Remove rider");
  remove.type = "button";
  remove.addEventListener("click", () => {
    const rider = sandboxRiders[stop.id - 1];
    if (rider) {
      removeSandboxRider(rider);
    }
  });
  wrap.appendChild(remove);
  return wrap;
}

function ensureMap(office) {
  if (map) {
    return;
  }
  // scrollWheelZoom is off until a click inside the map and off again when the cursor leaves,
  // so passing over the map never hijacks the wheel. Controls are added by hand so both sit
  // top-right, clear of the mobile sheet.
  map = L.map("map", { scrollWheelZoom: false, zoomControl: false, attributionControl: false }).setView(
    [office.latitude, office.longitude],
    11
  );
  L.control.zoom({ position: "topright" }).addTo(map);
  L.control.attribution({ position: "topright", prefix: false }).addTo(map);
  // OpenStreetMap's own tiles: no key, attribution required; light demo use is within policy.
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19,
  }).addTo(map);
  map.on("click", onSandboxMapClick);
  map.on("click", () => map.scrollWheelZoom.enable());
  mapWrapEl.addEventListener("mouseleave", () => map.scrollWheelZoom.disable());
  mapEl.addEventListener("keydown", onMapKeydown);
  mapEl.addEventListener("focus", syncKeyboardCue);
  mapEl.addEventListener("blur", syncKeyboardCue);
}

/** Fits the map to `points`, leaving room under the mobile sheet. The map is always laid out. */
function fitMapToPoints(points) {
  map.invalidateSize({ animate: false });
  map.fitBounds(points, {
    paddingTopLeft: [24, 24],
    paddingBottomRight: [24, sheetPad()],
    maxZoom: 13,
    animate: false,
  });
}

function clearRouteAndMarkerLayers() {
  for (const layer of routeLayers.values()) {
    map.removeLayer(layer);
  }
  routeLayers.clear();
  routePts.clear();
  stopMarkers.clear();
  cabMarkers.clear();
  drawing.clear();
  if (cabLayer) {
    map.removeLayer(cabLayer);
    cabLayer = null;
  }
  if (casingLayer) {
    map.removeLayer(casingLayer);
    casingLayer = null;
    casingFor = null;
  }
  if (markerLayer) {
    map.removeLayer(markerLayer);
    markerLayer = null;
  }
}

/** Draws every cab's route and stops. opts.sandbox swaps the stop popup for "Remove rider". */
function renderMap(plan, office, opts = {}) {
  ensureMap(office);
  removeSandboxPins();
  clearRouteAndMarkerLayers();
  markerLayer = L.layerGroup().addTo(map);
  cabLayer = L.layerGroup().addTo(map);
  addOfficeMarker(office, markerLayer);

  const allPoints = [[office.latitude, office.longitude]];
  for (const cab of plan.cabs) {
    const points = routePoints(plan, office, cab);
    routePts.set(cab.cabNumber, points);
    allPoints.push(...points);
  }
  fitMapToPoints(allPoints);

  // One casing line, moved to the selected route; added first so it starts underneath.
  casingLayer = L.polyline([], {
    interactive: false,
    color: cssVar("--route-casing"),
    weight: 9,
    opacity: 0,
  }).addTo(map);
  plan.cabs.forEach((cab, index) => {
    const n = cab.cabNumber;
    const layer = L.polyline(routePts.get(n), {
      color: cssVar("--route-idle"),
      weight: 3,
      opacity: 0.75,
      bubblingMouseEvents: false,
    }).addTo(map);
    layer.on("click", () => setSelectedCab(n, { scroll: true }));
    routeLayers.set(n, layer);
    if (!reducedMotion) {
      drawIn(layer, n, 300 + index * 80);
    }

    const list = [];
    for (const stop of cab.stops) {
      const marker = L.marker([stop.latitude, stop.longitude], { icon: stopIcon(), title: stop.name });
      marker.bindPopup(opts.sandbox ? buildSandboxPopup(stop) : buildStopPopup(stop));
      marker.addTo(markerLayer);
      list.push({ marker, stop });
    }
    stopMarkers.set(n, list);
    cabMarkers.set(n, buildCabDisc(cab, routePts.get(n)[0]));
  });
  styleRoutes();
}

/** Strokes a route on; the dash trick is cleared afterwards because zoom changes the length. */
function drawIn(layer, cabNumber, delayMs) {
  const path = layer.getElement();
  if (!path || typeof path.getTotalLength !== "function") {
    return;
  }
  const len = path.getTotalLength();
  if (!len) {
    return;
  }
  drawing.set(cabNumber, layer);
  path.style.strokeDasharray = String(len);
  path.style.strokeDashoffset = String(len);
  path.getBoundingClientRect(); // force layout so the transition starts from the hidden state
  path.style.transition = `stroke-dashoffset .7s cubic-bezier(.2,.7,.2,1) ${delayMs}ms`;
  path.style.strokeDashoffset = "0";

  let finished = false;
  const finish = () => {
    if (finished) {
      return;
    }
    finished = true;
    path.style.strokeDasharray = "";
    path.style.strokeDashoffset = "";
    path.style.transition = "";
    if (drawing.get(cabNumber) === layer) {
      drawing.delete(cabNumber);
      if (selectedCabNumber === cabNumber) {
        styleRoutes();
      }
    }
  };
  path.addEventListener("transitionend", (e) => {
    if (e.propertyName === "stroke-dashoffset") {
      finish();
    }
  });
  setTimeout(finish, delayMs + 1000);
}

/** Idle, hover and selected looks from the CSS tokens; z-order idle, hover, casing, selected. */
function styleRoutes() {
  if (!map) {
    return;
  }
  const idle = cssVar("--route-idle");
  for (const layer of routeLayers.values()) {
    layer.setStyle({ color: idle, weight: 3, opacity: 0.75 });
  }
  const hovering = hoverCabNumber !== null && hoverCabNumber !== selectedCabNumber;
  const hover = hovering ? routeLayers.get(hoverCabNumber) : null;
  if (hover) {
    hover.setStyle({ color: cssVar("--ink"), weight: 4, opacity: 1 });
    hover.bringToFront();
  }
  const selected = routeLayers.get(selectedCabNumber);
  if (casingLayer) {
    if (selected && !drawing.has(selectedCabNumber)) {
      if (casingFor !== selectedCabNumber) {
        casingLayer.setLatLngs(selected.getLatLngs());
        casingFor = selectedCabNumber;
      }
      casingLayer.setStyle({ color: cssVar("--route-casing"), weight: 9, opacity: 1 });
      casingLayer.bringToFront();
    } else {
      casingLayer.setStyle({ opacity: 0 });
    }
  }
  if (selected) {
    selected.setStyle({ color: cssVar("--accent"), weight: 5, opacity: 1 });
    selected.bringToFront();
  }
}

/* Sheet */

function initSheet() {
  for (const target of [sheetHandleEl, panelHeadEl]) {
    target.addEventListener("pointerdown", onSheetPointerDown);
    // A drag that started on a button must not also click it.
    target.addEventListener(
      "click",
      (e) => {
        if (suppressClick) {
          e.stopPropagation();
          e.preventDefault();
        }
      },
      true
    );
  }
  // Registered after the suppressing listener above, so a drag never also cycles the sheet.
  sheetHandleEl.addEventListener("click", () => {
    setSheet(SHEETS[(SHEETS.indexOf(sheetState) + 1) % SHEETS.length]);
    if (selectedCabNumber !== null) {
      flyToCab(selectedCabNumber);
    }
  });
  window.addEventListener("pointermove", onSheetPointerMove);
  window.addEventListener("pointerup", onSheetPointerUp);
  window.addEventListener("pointercancel", onSheetPointerUp);
  panelScrollEl.addEventListener(
    "scroll",
    () => panelScrollEl.classList.toggle("is-scrolled", panelScrollEl.scrollTop > 0),
    { passive: true }
  );
  mobileMq.addEventListener("change", endSheetDrag);
  setSheet(sheetState);
}

function setSheet(state) {
  sheetState = state;
  shell.dataset.sheet = state;
  sheetHandleEl.setAttribute("aria-label", `Panel: ${state} height. Tap to change.`);
}

/** Visible height of the sheet in `state`, in px. */
function sheetVisible(state) {
  const h = shell.clientHeight;
  return state === "peek" ? SHEET_PEEK_PX : state === "half" ? h * 0.56 : h - SHEET_TOP_GAP_PX;
}

/** Pixels of map the sheet covers in `state`, plus a margin; desktop never covers the map. */
function sheetPad(state = sheetState) {
  return mobileMq.matches ? sheetVisible(state) + 24 : 24;
}

function onSheetPointerDown(e) {
  if (!mobileMq.matches || !e.isPrimary || (e.button !== undefined && e.button > 0)) {
    return;
  }
  // From the panel head the sheet only drags while the list is at its top; otherwise it scrolls.
  if (e.currentTarget === panelHeadEl && panelScrollEl.scrollTop > 0) {
    return;
  }
  const matrix = new DOMMatrix(getComputedStyle(panelEl).transform);
  sheetDrag = {
    id: e.pointerId,
    target: e.target,
    y0: e.clientY,
    ty0: matrix.m42,
    ty: matrix.m42,
    active: false,
    samples: [{ y: e.clientY, t: e.timeStamp }],
  };
}

function onSheetPointerMove(e) {
  const d = sheetDrag;
  if (!d || e.pointerId !== d.id) {
    return;
  }
  const dy = e.clientY - d.y0;
  if (!d.active) {
    if (Math.abs(dy) < SHEET_DRAG_TAP_PX) {
      return;
    }
    d.active = true;
    panelEl.classList.add("is-dragging");
    try {
      d.target.setPointerCapture(d.id);
    } catch (err) {
      // Capture is only a nicety; window listeners still see the drag.
    }
  }
  const panelH = panelEl.offsetHeight;
  d.ty = Math.min(panelH - 120, Math.max(0, d.ty0 + dy));
  panelEl.style.transform = `translateY(${d.ty}px)`;
  d.samples.push({ y: e.clientY, t: e.timeStamp });
  while (d.samples.length > 2 && e.timeStamp - d.samples[0].t > SHEET_VELOCITY_WINDOW_MS) {
    d.samples.shift();
  }
}

function onSheetPointerUp(e) {
  const d = sheetDrag;
  if (!d || e.pointerId !== d.id) {
    return;
  }
  if (!d.active) {
    sheetDrag = null; // under 6px: a tap, left to the click handlers
    return;
  }
  const first = d.samples[0];
  const last = d.samples[d.samples.length - 1];
  const velocity = last.t > first.t ? (last.y - first.y) / (last.t - first.t) : 0;
  const visible = panelEl.offsetHeight - d.ty;
  const heights = SHEETS.map((state) => ({ state, h: sheetVisible(state) }));
  let target;
  if (Math.abs(velocity) > SHEET_FLING_PX_PER_MS) {
    // Fling: the next snap point in the direction of travel (up is negative y).
    const ahead = heights.filter((x) => (velocity < 0 ? x.h > visible : x.h < visible));
    ahead.sort((a, b) => (velocity < 0 ? a.h - b.h : b.h - a.h));
    target = ahead.length > 0 ? ahead[0].state : velocity < 0 ? "full" : "peek";
  } else {
    target = heights.reduce((best, x) => (Math.abs(x.h - visible) < Math.abs(best.h - visible) ? x : best)).state;
  }
  suppressClick = true;
  setTimeout(() => {
    suppressClick = false;
  }, 0);
  const changed = target !== sheetState;
  endSheetDrag();
  setSheet(target);
  if (changed && selectedCabNumber !== null) {
    flyToCab(selectedCabNumber);
  }
}

/** Hands the sheet back to the stylesheet: no inline transform, transition on again. */
function endSheetDrag() {
  sheetDrag = null;
  panelEl.classList.remove("is-dragging");
  panelEl.style.transform = "";
}
