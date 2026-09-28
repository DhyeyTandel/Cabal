"use strict";

/**
 * Cabal demo site. Two modes, one shared renderer:
 *  - "Demo plans": a read-only view over the plans API, picking a pre-computed plan.
 *  - "Try it": a public sandbox where a visitor drops riders on the map and the real
 *    engine plans them server-side, saving nothing (POST /api/sandbox/plan).
 * Both modes normalise their API response into the same plan/office shape (see
 * normalizePlan/normalizeOffice) and feed it to the same renderSummary/renderMap/renderCabList
 * functions, so there is one rendering path, not two.
 *
 * No framework, no build step; plain fetch against this origin. Every piece of API data is
 * written with textContent / DOM APIs, never innerHTML, and every Leaflet popup is built from
 * DOM nodes rather than an HTML string.
 */

const modePillsEl = document.getElementById("mode-pills");
const modeDemoPillEl = document.getElementById("mode-demo-pill");
const modeSandboxPillEl = document.getElementById("mode-sandbox-pill");
const demoModeEl = document.getElementById("demo-mode");
const sandboxModeEl = document.getElementById("sandbox-mode");

const pillsEl = document.getElementById("plan-pills");
const stateEl = document.getElementById("state-message");
const notSavedEl = document.getElementById("sandbox-not-saved");
const detailEl = document.getElementById("plan-detail");
const summaryEl = document.getElementById("summary-bar");
const cabListEl = document.getElementById("cab-list");

const shiftPillsEl = document.getElementById("shift-pills");
const fleetPillsEl = document.getElementById("fleet-pills");
const randomRidersBtn = document.getElementById("random-riders-btn");
const clearRidersBtn = document.getElementById("clear-riders-btn");
const riderCounterEl = document.getElementById("rider-counter");
const planItBtn = document.getElementById("plan-it-btn");

const SANDBOX_MAX_RIDERS = 40;
const SANDBOX_RANDOM_RADIUS_KM = 15;
const SANDBOX_RANDOM_WOMAN_SHARE = 1 / 3;
/** Fixed office the sandbox always plans against; matches SandboxService on the server. */
const SANDBOX_OFFICE_NAME = "Manyata Tech Park";

let map = null;
let routeLayers = new Map(); // cabNumber -> polyline
let markerLayer = null;
let selectedPlanId = null;
let selectedCabNumber = null;

let mode = "demo"; // "demo" | "sandbox"
let sandboxRiders = []; // { lat, lng, woman }
let sandboxPinLayer = null; // input-pin markers, shown whenever there is no computed plan yet
let sandboxHasPlan = false;
let sandboxShift = { time: "07:30", direction: "PICKUP" };
let sandboxFleet = "SEDANS";

init();
wireModePills();
wireSandboxControls();

async function init() {
  try {
    const plans = await fetchJson("/api/plans");
    if (plans.length === 0) {
      showState("No plans yet. The demo seeds itself on deploy.", false);
      return;
    }
    renderPills(plans);
    await selectPlan(plans[0].id);
  } catch (err) {
    showState("Couldn't load plans. Try refreshing the page.", true);
  }
}

function showState(message, isError) {
  stateEl.textContent = message;
  stateEl.hidden = false;
  stateEl.classList.toggle("state-message--error", Boolean(isError));
  detailEl.hidden = true;
}

function hideState() {
  stateEl.hidden = true;
  stateEl.textContent = "";
  stateEl.classList.remove("state-message--error");
}

function renderPills(plans) {
  pillsEl.textContent = "";
  for (const plan of plans) {
    const pill = document.createElement("button");
    pill.type = "button";
    pill.className = "plan-pill";
    pill.setAttribute("aria-pressed", "false");
    pill.textContent = planLabel(plan);
    pill.addEventListener("click", () => selectPlan(plan.id));
    pill.dataset.planId = String(plan.id);
    pillsEl.appendChild(pill);
  }
}

function markSelectedPill(planId) {
  for (const pill of pillsEl.children) {
    pill.setAttribute("aria-pressed", String(Number(pill.dataset.planId) === planId));
  }
}

function planLabel(plan) {
  return `${formatTime(plan.shiftTime)} ${plan.direction.toLowerCase()}`;
}

function formatTime(isoLocalDateTime) {
  // Backend LocalDateTime serialises as "yyyy-MM-ddTHH:mm:ss"; no timezone maths needed.
  return isoLocalDateTime.slice(11, 16);
}

function formatNumber(value) {
  return Number(value).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

async function fetchJson(url) {
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) {
    throw new Error(`request to ${url} failed with ${res.status}`);
  }
  return res.json();
}

/* ---------------------------------------------------------------------------------------
 * Mode switching
 * ------------------------------------------------------------------------------------- */

function wireModePills() {
  modeDemoPillEl.addEventListener("click", () => setMode("demo"));
  modeSandboxPillEl.addEventListener("click", () => setMode("sandbox"));
}

function setMode(newMode) {
  if (mode === newMode) {
    return;
  }
  mode = newMode;
  modeDemoPillEl.setAttribute("aria-pressed", String(newMode === "demo"));
  modeSandboxPillEl.setAttribute("aria-pressed", String(newMode === "sandbox"));
  demoModeEl.hidden = newMode !== "demo";
  sandboxModeEl.hidden = newMode !== "sandbox";
  hideState();
  notSavedEl.hidden = true;

  if (newMode === "demo") {
    if (selectedPlanId !== null) {
      selectPlan(selectedPlanId);
    } else {
      detailEl.hidden = true;
    }
    return;
  }

  summaryEl.hidden = true;
  summaryEl.textContent = "";
  cabListEl.textContent = "";
  detailEl.hidden = false;
  sandboxHasPlan = false;
  updateRiderCounter();
  renderSandboxPins();
}

/* ---------------------------------------------------------------------------------------
 * Demo mode: pick a pre-computed plan
 * ------------------------------------------------------------------------------------- */

async function selectPlan(planId) {
  selectedPlanId = planId;
  selectedCabNumber = null;
  markSelectedPill(planId);
  try {
    const rawPlan = await fetchJson(`/api/plans/${planId}`);
    const rawOffice = await fetchJson(`/api/offices/${rawPlan.officeId}`);
    if (selectedPlanId !== planId) {
      return; // a newer selection has already started
    }
    hideState();
    notSavedEl.hidden = true;
    detailEl.hidden = false;
    summaryEl.hidden = false;

    const plan = normalizePlan(rawPlan, "demo");
    const office = normalizeOffice(rawOffice, "demo");
    renderSummary(plan);
    renderMap(plan, office);
    renderCabList(plan);
    if (plan.cabs.length > 0) {
      setSelectedCab(plan.cabs[0].cabNumber);
    }
  } catch (err) {
    showState("Couldn't load this plan. Try another one.", true);
  }
}

/* ---------------------------------------------------------------------------------------
 * Sandbox mode: drop riders, plan them, nothing saved
 * ------------------------------------------------------------------------------------- */

function wireSandboxControls() {
  for (const pill of shiftPillsEl.children) {
    pill.addEventListener("click", () => {
      for (const p of shiftPillsEl.children) {
        p.setAttribute("aria-pressed", String(p === pill));
      }
      sandboxShift = { time: pill.dataset.shiftTime, direction: pill.dataset.direction };
    });
  }
  for (const pill of fleetPillsEl.children) {
    pill.addEventListener("click", () => {
      for (const p of fleetPillsEl.children) {
        p.setAttribute("aria-pressed", String(p === pill));
      }
      sandboxFleet = pill.dataset.fleet;
    });
  }
  randomRidersBtn.addEventListener("click", addRandomSandboxRiders);
  clearRidersBtn.addEventListener("click", clearSandboxRiders);
  planItBtn.addEventListener("click", runSandboxPlan);
}

function updateRiderCounter() {
  riderCounterEl.textContent = `${sandboxRiders.length} / ${SANDBOX_MAX_RIDERS} riders`;
  planItBtn.disabled = sandboxRiders.length === 0;
}

function onSandboxRidersChanged() {
  sandboxHasPlan = false;
  notSavedEl.hidden = true;
  summaryEl.hidden = true;
  summaryEl.textContent = "";
  cabListEl.textContent = "";
  hideState();
  updateRiderCounter();
  renderSandboxPins();
}

function addSandboxRider(lat, lng, woman) {
  if (sandboxRiders.length >= SANDBOX_MAX_RIDERS) {
    return;
  }
  sandboxRiders.push({ lat, lng, woman: Boolean(woman) });
}

function removeSandboxRider(rider) {
  sandboxRiders = sandboxRiders.filter((r) => r !== rider);
  onSandboxRidersChanged();
}

function clearSandboxRiders() {
  sandboxRiders = [];
  onSandboxRidersChanged();
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
  onSandboxRidersChanged();
}

function sandboxOffice() {
  return { name: SANDBOX_OFFICE_NAME, latitude: 13.0475, longitude: 77.6206 };
}

function onSandboxMapClick(e) {
  if (mode !== "sandbox" || sandboxRiders.length >= SANDBOX_MAX_RIDERS) {
    return;
  }
  addSandboxRider(e.latlng.lat, e.latlng.lng, false);
  onSandboxRidersChanged();
}

/** The raw input pins: office plus one marker per rider, click to remove, no routes yet. */
function renderSandboxPins() {
  const office = sandboxOffice();
  ensureMap(office);
  clearRouteAndMarkerLayers();
  if (sandboxPinLayer) {
    map.removeLayer(sandboxPinLayer);
  }
  sandboxPinLayer = L.layerGroup().addTo(map);

  const officeMarker = L.marker([office.latitude, office.longitude], { icon: officeIcon() });
  officeMarker.bindPopup(buildOfficePopup(office));
  officeMarker.addTo(sandboxPinLayer);

  const allPoints = [[office.latitude, office.longitude]];
  for (const rider of sandboxRiders) {
    const marker = L.marker([rider.lat, rider.lng], { icon: stopIcon() });
    marker.on("click", () => removeSandboxRider(rider));
    marker.addTo(sandboxPinLayer);
    allPoints.push([rider.lat, rider.lng]);
  }
  fitMapToPoints(allPoints);
}

async function runSandboxPlan() {
  if (sandboxRiders.length === 0) {
    return;
  }
  hideState();
  planItBtn.disabled = true;
  planItBtn.textContent = "Planning...";
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
    if (!res.ok) {
      const problem = await res.json().catch(() => null);
      const detail = problem && problem.detail ? problem.detail : `the sandbox failed with ${res.status}`;
      showState(detail, res.status !== 429);
      detailEl.hidden = false; // showState hides it; the map and pins should stay visible
      return;
    }

    const rawPlan = await res.json();
    sandboxHasPlan = true;
    const plan = normalizePlan(rawPlan, "sandbox");
    const office = normalizeOffice(rawPlan, "sandbox");
    summaryEl.hidden = false;
    renderSummary(plan);
    renderMap(plan, office, { onStopClick: (stop) => removeSandboxRider(sandboxRiders[stop.id - 1]) });
    renderCabList(plan);
    if (plan.cabs.length > 0) {
      setSelectedCab(plan.cabs[0].cabNumber);
    }
    notSavedEl.hidden = false;
  } catch (err) {
    showState("Couldn't reach the sandbox. Try again.", true);
    detailEl.hidden = false;
  } finally {
    planItBtn.textContent = "Plan it";
    planItBtn.disabled = sandboxRiders.length === 0;
  }
}

/* ---------------------------------------------------------------------------------------
 * Normalisation: demo plans (PlanResponse) and sandbox plans (SandboxPlanResponse) map to
 * one common shape so the rendering functions below only need to know one field naming.
 * ------------------------------------------------------------------------------------- */

function normalizePlan(raw, kind) {
  const riderCount = kind === "demo" ? raw.employeeCount : raw.riderCount;
  return {
    direction: raw.direction,
    cabCount: raw.cabCount,
    riderCount,
    totalDistanceKm: raw.totalDistanceKm,
    totalCost: raw.totalCost,
    windowsMissed: raw.windowsMissed,
    cabs: raw.cabs.map((c) => normalizeCab(c, kind)),
  };
}

function normalizeCab(c, kind) {
  return {
    cabNumber: c.cabNumber,
    vehicleType: c.vehicleType,
    seats: c.seats,
    seatsUsed: c.seatsUsed,
    distanceKm: c.distanceKm,
    cost: c.cost,
    maxRideMinutes: c.maxRideMinutes,
    escortRequired: c.escortRequired,
    stops: c.stops.map((s) => normalizeStop(s, kind)),
  };
}

function normalizeStop(s, kind) {
  return {
    sequence: s.sequence,
    id: kind === "demo" ? s.employeeId : s.riderNumber,
    name: kind === "demo" ? s.employeeName : s.name,
    latitude: s.latitude,
    longitude: s.longitude,
    eta: s.eta,
    rideMinutes: s.rideMinutes,
    earliestPickup: s.earliestPickup,
    latestDrop: s.latestDrop,
    windowMissed: s.windowMissed,
  };
}

function normalizeOffice(raw, kind) {
  if (kind === "demo") {
    return { name: raw.name, latitude: raw.latitude, longitude: raw.longitude };
  }
  return { name: SANDBOX_OFFICE_NAME, latitude: raw.officeLatitude, longitude: raw.officeLongitude };
}

/* ---------------------------------------------------------------------------------------
 * Shared rendering: summary metrics, map, cab list. Both modes normalise into the same
 * plan/office shape above and call these directly.
 * ------------------------------------------------------------------------------------- */

function renderSummary(plan) {
  summaryEl.hidden = false;
  summaryEl.textContent = "";
  const metrics = [
    ["Cabs", String(plan.cabCount)],
    ["Riders", String(plan.riderCount)],
    ["Distance", `${formatNumber(plan.totalDistanceKm)} km`],
    ["Cost", formatNumber(plan.totalCost)],
  ];
  if (plan.windowsMissed > 0) {
    metrics.push(["Windows missed", String(plan.windowsMissed)]);
  }
  for (const [label, value] of metrics) {
    const wrap = document.createElement("div");
    const dt = document.createElement("dt");
    dt.className = "summary-label";
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.className = "summary-value";
    dd.style.margin = "0";
    dd.textContent = value;
    wrap.appendChild(dt);
    wrap.appendChild(dd);
    summaryEl.appendChild(wrap);
  }
}

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function routePoints(plan, office, cab) {
  const stops = [...cab.stops].sort((a, b) => a.sequence - b.sequence);
  const stopPoints = stops.map((s) => [s.latitude, s.longitude]);
  const officePoint = [office.latitude, office.longitude];
  return plan.direction === "PICKUP" ? [...stopPoints, officePoint] : [officePoint, ...stopPoints];
}

function officeIcon() {
  return L.divIcon({ className: "", html: '<div class="marker-office"></div>', iconSize: [14, 14] });
}

function stopIcon() {
  return L.divIcon({ className: "", html: '<div class="marker-stop"></div>', iconSize: [9, 9] });
}

function buildStopPopup(stop) {
  const wrap = document.createElement("div");
  wrap.className = "map-popup";
  const name = document.createElement("strong");
  name.textContent = stop.name;
  const eta = document.createElement("div");
  eta.className = "map-popup__eta";
  eta.textContent = `ETA ${formatTime(stop.eta)}`;
  wrap.appendChild(name);
  wrap.appendChild(eta);
  return wrap;
}

function buildOfficePopup(office) {
  const wrap = document.createElement("div");
  wrap.className = "map-popup";
  const name = document.createElement("strong");
  name.textContent = office.name;
  wrap.appendChild(name);
  return wrap;
}

function ensureMap(office) {
  if (map) {
    return;
  }
  // scrollWheelZoom starts off: a map that captures the mouse wheel makes the page
  // itself un-scrollable the moment the cursor passes over it while scrolling past.
  // It turns on only after a click inside the map (a deliberate "I want to use this
  // map now" signal), and off again the moment the cursor leaves it, so scrolling
  // past the map normally is never hijacked, but a visitor who clicks in can still
  // zoom with the wheel as expected.
  map = L.map("map", { scrollWheelZoom: false }).setView([office.latitude, office.longitude], 11);
  // OpenStreetMap's own tiles: no key, attribution required. Light use like a demo
  // page is within the OSM tile usage policy.
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19,
  }).addTo(map);
  map.on("click", onSandboxMapClick);
  map.on("click", () => map.scrollWheelZoom.enable());
  document.querySelector(".map-frame").addEventListener("mouseleave", () => map.scrollWheelZoom.disable());
}

/**
 * After the plan detail section is visible, refreshes the map's cached size and fits it to
 * `points`. Deferred to the next animation frame so layout has settled in case the container
 * was just un-hidden; running invalidateSize/fitBounds against a hidden or zero-sized
 * container is what used to leave the map at the tile layer's max zoom.
 */
function fitMapToPoints(points) {
  requestAnimationFrame(() => {
    map.invalidateSize();
    map.fitBounds(points, { padding: [24, 24], maxZoom: 13 });
  });
}

function clearRouteAndMarkerLayers() {
  for (const layer of routeLayers.values()) {
    map.removeLayer(layer);
  }
  routeLayers.clear();
  if (markerLayer) {
    map.removeLayer(markerLayer);
    markerLayer = null;
  }
}

/**
 * Draws every cab's route and stops. In sandbox mode {@code opts.onStopClick(stop)} is
 * called instead of opening a popup, so a rider pin stays click-to-remove after planning.
 */
function renderMap(plan, office, opts = {}) {
  ensureMap(office);
  if (sandboxPinLayer) {
    map.removeLayer(sandboxPinLayer);
    sandboxPinLayer = null;
  }

  clearRouteAndMarkerLayers();
  markerLayer = L.layerGroup().addTo(map);

  const officeMarker = L.marker([office.latitude, office.longitude], { icon: officeIcon() });
  officeMarker.bindPopup(buildOfficePopup(office));
  officeMarker.addTo(markerLayer);

  const allPoints = [[office.latitude, office.longitude]];
  const mutedSoft = cssVar("--muted-soft");

  for (const cab of plan.cabs) {
    const points = routePoints(plan, office, cab);
    for (const point of points) {
      allPoints.push(point);
    }
    const polyline = L.polyline(points, { color: mutedSoft, weight: 4, opacity: 0.85 }).addTo(map);
    routeLayers.set(cab.cabNumber, polyline);

    for (const stop of cab.stops) {
      const marker = L.marker([stop.latitude, stop.longitude], { icon: stopIcon() });
      if (opts.onStopClick) {
        marker.on("click", () => opts.onStopClick(stop));
      } else {
        marker.bindPopup(buildStopPopup(stop));
      }
      marker.addTo(markerLayer);
    }
  }

  if (allPoints.length > 0) {
    fitMapToPoints(allPoints);
  }
}

function highlightRoute(cabNumber) {
  const mutedSoft = cssVar("--muted-soft");
  const accent = cssVar("--accent");
  for (const [number, layer] of routeLayers) {
    if (number === cabNumber) {
      layer.setStyle({ color: accent, weight: 6, opacity: 1 });
      layer.bringToFront();
    } else {
      layer.setStyle({ color: mutedSoft, weight: 4, opacity: 0.85 });
    }
  }
}

function setSelectedCab(cabNumber) {
  selectedCabNumber = cabNumber;
  highlightRoute(cabNumber);
  for (const card of cabListEl.children) {
    const isSelected = Number(card.dataset.cabNumber) === cabNumber;
    card.setAttribute("aria-expanded", String(isSelected));
    const stopsList = card.querySelector(".cab-card__stops");
    if (stopsList) {
      stopsList.hidden = !isSelected;
    }
  }
}

function renderCabList(plan) {
  cabListEl.textContent = "";
  for (const cab of plan.cabs) {
    cabListEl.appendChild(buildCabCard(cab, plan.direction));
  }
}

/** "HH:mm" from a "HH:mm" or "HH:mm:ss" LocalTime string. */
function formatHHmm(localTime) {
  return localTime.slice(0, 5);
}

/** The stop's own window, relevant to this plan's direction, or null if it has none. */
function windowLabel(stop, direction) {
  if (direction === "PICKUP" && stop.earliestPickup) {
    return `after ${formatHHmm(stop.earliestPickup)}`;
  }
  if (direction === "DROP" && stop.latestDrop) {
    return `by ${formatHHmm(stop.latestDrop)}`;
  }
  return null;
}

function buildCabCard(cab, direction) {
  const card = document.createElement("article");
  card.className = "cab-card";
  card.dataset.cabNumber = String(cab.cabNumber);
  card.setAttribute("role", "button");
  card.setAttribute("tabindex", "0");
  card.setAttribute("aria-expanded", "false");

  const head = document.createElement("div");
  head.className = "cab-card__head";
  const title = document.createElement("span");
  title.className = "cab-card__title";
  title.textContent = `Cab ${cab.cabNumber}`;
  const vehicle = document.createElement("span");
  vehicle.className = "cab-card__vehicle";
  vehicle.textContent = `${cab.vehicleType} ${cab.seatsUsed}/${cab.seats}`;
  head.appendChild(title);
  head.appendChild(vehicle);
  card.appendChild(head);

  const stats = document.createElement("div");
  stats.className = "cab-card__stats";
  stats.appendChild(statItem(`${formatNumber(cab.distanceKm)} km`));
  stats.appendChild(statItem(formatNumber(cab.cost)));
  stats.appendChild(statItem(`${formatNumber(cab.maxRideMinutes)} min longest ride`));
  card.appendChild(stats);

  if (cab.escortRequired) {
    const chip = document.createElement("span");
    chip.className = "escort-chip";
    chip.textContent = "Escort";
    card.appendChild(chip);
  }

  const stops = document.createElement("ol");
  stops.className = "cab-card__stops";
  stops.hidden = true;
  const sorted = [...cab.stops].sort((a, b) => a.sequence - b.sequence);
  for (const stop of sorted) {
    const item = document.createElement("li");
    item.className = "cab-card__stop";
    const name = document.createElement("span");
    name.className = "cab-card__stop-name";
    name.textContent = stop.name;

    const timeWrap = document.createElement("span");
    timeWrap.className = "cab-card__stop-time";
    const eta = document.createElement("span");
    eta.className = "cab-card__stop-eta";
    eta.textContent = formatTime(stop.eta);
    timeWrap.appendChild(eta);

    const windowText = windowLabel(stop, direction);
    if (windowText) {
      const windowEl = document.createElement("span");
      windowEl.className = "cab-card__stop-window";
      windowEl.textContent = windowText;
      if (stop.windowMissed) {
        windowEl.classList.add("cab-card__stop-window--missed");
        windowEl.appendChild(document.createTextNode(" "));
        const flag = document.createElement("span");
        flag.className = "cab-card__stop-window-flag";
        flag.textContent = "missed";
        windowEl.appendChild(flag);
      }
      timeWrap.appendChild(windowEl);
    }

    item.appendChild(name);
    item.appendChild(timeWrap);
    stops.appendChild(item);
  }
  card.appendChild(stops);

  const toggle = () => setSelectedCab(cab.cabNumber);
  card.addEventListener("click", toggle);
  card.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      toggle();
    }
  });

  return card;
}

function statItem(text) {
  const span = document.createElement("span");
  const strong = document.createElement("strong");
  strong.textContent = text;
  span.appendChild(strong);
  return span;
}
