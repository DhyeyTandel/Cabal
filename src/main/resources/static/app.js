"use strict";

/**
 * Cabal live demo. A read-only view over the plans API: pick a plan, see its cabs on a map,
 * open a cab to see its stops. No framework, no build step; plain fetch against this origin.
 * Every piece of API data is written with textContent / DOM APIs, never innerHTML, and every
 * Leaflet popup is built from DOM nodes rather than an HTML string.
 */

const pillsEl = document.getElementById("plan-pills");
const stateEl = document.getElementById("state-message");
const detailEl = document.getElementById("plan-detail");
const summaryEl = document.getElementById("summary-bar");
const cabListEl = document.getElementById("cab-list");

let map = null;
let routeLayers = new Map(); // cabNumber -> polyline
let markerLayer = null;
let selectedPlanId = null;
let selectedCabNumber = null;

init();

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

async function selectPlan(planId) {
  selectedPlanId = planId;
  selectedCabNumber = null;
  markSelectedPill(planId);
  try {
    const plan = await fetchJson(`/api/plans/${planId}`);
    const office = await fetchJson(`/api/offices/${plan.officeId}`);
    if (selectedPlanId !== planId) {
      return; // a newer selection has already started
    }
    hideState();
    detailEl.hidden = false;
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

function renderSummary(plan) {
  summaryEl.textContent = "";
  const metrics = [
    ["Cabs", String(plan.cabCount)],
    ["Riders", String(plan.employeeCount)],
    ["Distance", `${formatNumber(plan.totalDistanceKm)} km`],
    ["Cost", formatNumber(plan.totalCost)],
  ];
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
  name.textContent = stop.employeeName;
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

function renderMap(plan, office) {
  if (!map) {
    map = L.map("map");
    // OpenStreetMap's own tiles: no key, attribution required. Light use like a demo
    // page is within the OSM tile usage policy.
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19,
    }).addTo(map);
  }

  for (const layer of routeLayers.values()) {
    map.removeLayer(layer);
  }
  routeLayers.clear();
  if (markerLayer) {
    map.removeLayer(markerLayer);
  }
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
    const polyline = L.polyline(points, { color: mutedSoft, weight: 3 }).addTo(map);
    routeLayers.set(cab.cabNumber, polyline);

    for (const stop of cab.stops) {
      const marker = L.marker([stop.latitude, stop.longitude], { icon: stopIcon() });
      marker.bindPopup(buildStopPopup(stop));
      marker.addTo(markerLayer);
    }
  }

  if (allPoints.length > 0) {
    map.fitBounds(allPoints, { padding: [32, 32] });
  }
}

function highlightRoute(cabNumber) {
  const mutedSoft = cssVar("--muted-soft");
  const accent = cssVar("--accent");
  for (const [number, layer] of routeLayers) {
    if (number === cabNumber) {
      layer.setStyle({ color: accent, weight: 5 });
      layer.bringToFront();
    } else {
      layer.setStyle({ color: mutedSoft, weight: 3 });
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
    cabListEl.appendChild(buildCabCard(cab));
  }
}

function buildCabCard(cab) {
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
    name.textContent = stop.employeeName;
    const eta = document.createElement("span");
    eta.className = "cab-card__stop-eta";
    eta.textContent = formatTime(stop.eta);
    item.appendChild(name);
    item.appendChild(eta);
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
