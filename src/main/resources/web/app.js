"use strict";

// The upload page of a photo code. The picture is cropped, scaled and cut into 128x128 parts here; the server only
// receives exactly what ends up on the maps and dithers it into map colours itself.

const TILE = 128;
// photos are scaled down to this size once, the biggest picture (10x10 maps) is 1280 pixels wide
const WORK_SIZE = 2048;

const TEXTS = {
  en: {
    loading: "Loading...",
    pickTitle: "Choose a picture",
    pickLead: "It will be split into maps that you can hang up in your world.",
    camera: "Take a photo",
    selfie: "Selfie",
    gallery: "From gallery",
    dropHint: "You can also drop or paste a picture here.",
    editHint: "Drag to move, pinch to zoom. Tap a square to leave it out.",
    width: "Width: {n}",
    height: "Height: {n}",
    zoom: "Zoom: {n}%",
    rotate: "Rotate",
    fit: "Whole picture: {state}",
    preview: "Map colours: {state}",
    on: "ON",
    off: "OFF",
    other: "Other picture",
    send: "Send to Minecraft",
    sending: "Painting your maps...",
    doneTitle: "Picture arrived!",
    doneOne: "In your inventory: 1 map",
    doneMany: "In your inventory: {n} maps",
    doneText: "Put one part into an empty item frame and the others hang themselves next to it.",
    more: "Another picture",
    pictureFor: "Picture for <b>{name}</b>",
    summary: "Maps: <b>{selected}</b>/{total} · {size} px",
    summaryMaps: "Empty maps: <b>{maps}</b>",
    summaryNeed: "Empty maps needed: {selected}, you have: {maps}. Leave squares out or get more maps.",
    noneSelected: "Tap at least one square.",
    expiredTitle: "Code expired",
    expiredText: "Right-click the photo code in Minecraft to get a new one.",
    noTokenTitle: "Scan a photo code",
    noTokenText: "Craft a photo code in Minecraft, right-click it and scan the code with your phone.",
    offlineTitle: "Connection lost",
    offlineText: "You are not on the server. Join again, then try once more.",
    mapsError: "Empty maps needed: {needed}, you have: {available}.",
    busyError: "Slow down! Try again in a moment.",
    invalidError: "The picture could not be read.",
    serverError: "Something went wrong on the server. Try again.",
    networkError: "No connection to the server. Are you in the same network?",
    imageError: "This picture format cannot be opened. Try a JPEG or PNG.",
  },
  de: {
    loading: "Lade...",
    pickTitle: "Wähle ein Bild",
    pickLead: "Es wird in Karten aufgeteilt, die du in deiner Welt aufhängen kannst.",
    camera: "Foto aufnehmen",
    selfie: "Selfie",
    gallery: "Aus der Galerie",
    dropHint: "Du kannst ein Bild auch hierher ziehen oder einfügen.",
    editHint: "Ziehen zum Verschieben, mit zwei Fingern zoomen. Tippe ein Feld an, um es wegzulassen.",
    width: "Breite: {n}",
    height: "Höhe: {n}",
    zoom: "Zoom: {n}%",
    rotate: "Drehen",
    fit: "Ganzes Bild: {state}",
    preview: "Kartenfarben: {state}",
    on: "AN",
    off: "AUS",
    other: "Anderes Bild",
    send: "An Minecraft senden",
    sending: "Deine Karten werden gemalt...",
    doneTitle: "Bild angekommen!",
    doneOne: "Im Inventar: 1 Karte",
    doneMany: "Im Inventar: {n} Karten",
    doneText: "Steck einen Teil in einen leeren Rahmen, die anderen hängen sich automatisch daneben.",
    more: "Noch ein Bild",
    pictureFor: "Bild für <b>{name}</b>",
    summary: "Karten: <b>{selected}</b>/{total} · {size} px",
    summaryMaps: "Leere Karten: <b>{maps}</b>",
    summaryNeed: "Leere Karten nötig: {selected}, du hast: {maps}. Lass Felder weg oder hol mehr Karten.",
    noneSelected: "Wähle mindestens ein Feld.",
    expiredTitle: "Code abgelaufen",
    expiredText: "Rechtsklicke den Foto-Code in Minecraft, um einen neuen zu bekommen.",
    noTokenTitle: "Scanne einen Foto-Code",
    noTokenText: "Crafte in Minecraft einen Foto-Code, rechtsklicke ihn und scanne den Code mit dem Handy.",
    offlineTitle: "Verbindung verloren",
    offlineText: "Du bist nicht auf dem Server. Betritt ihn wieder und versuch es dann noch einmal.",
    mapsError: "Leere Karten nötig: {needed}, du hast: {available}.",
    busyError: "Nicht so schnell! Versuch es gleich noch einmal.",
    invalidError: "Das Bild konnte nicht gelesen werden.",
    serverError: "Auf dem Server ist etwas schiefgelaufen. Versuch es noch einmal.",
    networkError: "Keine Verbindung zum Server. Bist du im selben Netzwerk?",
    imageError: "Dieses Bildformat kann nicht geöffnet werden. Versuch ein JPEG oder PNG.",
  },
};

const lang = (navigator.languages || [navigator.language || "en"])
  .map((l) => l.slice(0, 2).toLowerCase())
  .find((l) => l in TEXTS) || "en";
document.documentElement.lang = lang;

function t(key, values = {}) {
  return TEXTS[lang][key].replace(/\{(\w+)\}/g, (_, name) => escapeHtml(String(values[name] ?? "")));
}

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

const $ = (selector) => document.querySelector(selector);
const token = (location.pathname.match(/^\/s\/([A-Za-z0-9_-]{16})/) || [])[1];

const state = {
  session: null,
  deadline: 0,
  work: null, // the picture, scaled down to at most WORK_SIZE
  rotation: 0, // quarter turns
  columns: 1,
  rows: 1,
  contain: false,
  zoom: 1,
  panX: 0, // offset of the picture's centre, in output pixels
  panY: 0,
  selected: new Set(),
  preview: false,
  dithered: null,
};

// ---------------------------------------------------------------------------------------------------------- steps

function show(step) {
  for (const section of document.querySelectorAll(".step")) {
    section.hidden = section.id !== `step-${step}`;
  }
  if (step === "edit") {
    requestAnimationFrame(layoutStage);
  }
}

function fail(title, text) {
  $("#error-title").textContent = title;
  $("#error-text").textContent = text;
  $("#timer").hidden = true;
  show("error");
}

for (const element of document.querySelectorAll("[data-i18n]")) {
  element.textContent = t(element.dataset.i18n);
}

/** The toggles and sliders show their value in their label, like Minecraft's option buttons. */
function updateLabels() {
  const onOff = (on) => t(on ? "on" : "off");
  $("#fit-label").textContent = t("fit", { state: onOff(state.contain) });
  $("#preview-label").textContent = t("preview", { state: onOff(state.preview) });
  $("#columns-label").textContent = t("width", { n: state.columns });
  $("#rows-label").textContent = t("height", { n: state.rows });
  $("#zoom-label").textContent = t("zoom", { n: Math.round(state.zoom * 100) });
}

// -------------------------------------------------------------------------------------------------------- session

async function loadSession() {
  const response = await fetch(`/api/session/${token}`, { cache: "no-store" });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw Object.assign(new Error(body.error || "server"), { code: body.error || "server" });
  }
  return body;
}

function applySession(session) {
  state.session = session;
  state.deadline = Date.now() + session.expiresIn;
  $("#who").innerHTML = t("pictureFor", { name: session.player });
  $("#columns").max = session.maxColumns;
  $("#rows").max = session.maxRows;
  updateSummary();
}

function sessionError(code) {
  if (code === "expired") {
    fail(t("expiredTitle"), t("expiredText"));
  } else if (code === "offline") {
    fail(t("offlineTitle"), t("offlineText"));
  } else {
    fail(t("serverError"), "");
  }
}

function tickTimer() {
  if (!state.session || $("#step-error").hidden === false) {
    return;
  }
  const left = Math.max(0, state.deadline - Date.now());
  const timer = $("#timer");
  timer.hidden = false;
  const minutes = Math.floor(left / 60000);
  const seconds = Math.floor((left % 60000) / 1000);
  timer.textContent = `${minutes}:${String(seconds).padStart(2, "0")}`;
  timer.classList.toggle("low", left < 60000);
  if (left === 0 && $("#step-done").hidden) {
    sessionError("expired");
  }
}

async function refreshSession() {
  if (!state.session || document.hidden || !$("#step-error").hidden) {
    return;
  }
  try {
    applySession(await loadSession());
  } catch (error) {
    if (error.code) {
      sessionError(error.code);
    }
  }
}

// ---------------------------------------------------------------------------------------------- loading a picture

async function openFile(file) {
  if (!file || !file.type.startsWith("image/")) {
    return;
  }
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.decoding = "async";
    image.src = url;
    await image.decode();
    state.work = downscale(image, image.naturalWidth, image.naturalHeight);
  } catch {
    alertInline(t("imageError"));
    return;
  } finally {
    URL.revokeObjectURL(url);
  }
  state.rotation = 0;
  state.zoom = 1;
  state.panX = state.panY = 0;
  state.contain = false;
  $("#zoom").value = 1;
  $("#fit").setAttribute("aria-pressed", "false");
  updateLabels();
  suggestGrid();
  show("edit");
  changed();
}

/** Halves the picture until it fits WORK_SIZE: one big step looks grainy, halving keeps it smooth. */
function downscale(source, width, height) {
  let canvas = source;
  while (Math.max(width, height) > WORK_SIZE) {
    const factor = Math.max(0.5, WORK_SIZE / Math.max(width, height));
    width = Math.round(width * factor);
    height = Math.round(height * factor);
    const next = document.createElement("canvas");
    next.width = width;
    next.height = height;
    const ctx = next.getContext("2d");
    ctx.imageSmoothingQuality = "high";
    ctx.drawImage(canvas, 0, 0, width, height);
    canvas = next;
  }
  if (canvas === source) {
    const copy = document.createElement("canvas");
    copy.width = width;
    copy.height = height;
    copy.getContext("2d").drawImage(source, 0, 0);
    canvas = copy;
  }
  return canvas;
}

function alertInline(text) {
  const hint = $(".drop-hint");
  hint.textContent = text;
  hint.classList.add("error");
}

/** Picks the grid closest to the picture's shape, with about four maps (or fewer if the player has fewer). */
function suggestGrid() {
  const { width, height } = rotatedSize();
  const aspect = width / height;
  const { maxColumns, maxRows, maps } = state.session;
  const budget = Math.max(1, Math.min(4, maps >= 0 ? maps : 4));
  let best = { columns: 1, rows: 1, score: Infinity };
  for (let columns = 1; columns <= maxColumns; columns++) {
    for (let rows = 1; rows <= maxRows; rows++) {
      if (columns * rows > budget) {
        continue;
      }
      // prefer the closest shape, and more maps when shapes are equally close
      const score = Math.abs(Math.log(columns / rows / aspect)) - columns * rows * 0.001;
      if (score < best.score) {
        best = { columns, rows, score };
      }
    }
  }
  setGrid(best.columns, best.rows);
}

function setGrid(columns, rows) {
  state.columns = columns;
  state.rows = rows;
  state.selected = new Set(Array.from({ length: columns * rows }, (_, i) => i));
  $("#columns").value = columns;
  $("#rows").value = rows;
  updateLabels();
  clampPan();
  layoutStage();
  changed();
}

// -------------------------------------------------------------------------------------------------------- geometry

function rotatedSize() {
  const turned = state.rotation % 2 === 1;
  return {
    width: turned ? state.work.height : state.work.width,
    height: turned ? state.work.width : state.work.height,
  };
}

function outputSize() {
  return { width: state.columns * TILE, height: state.rows * TILE };
}

function scale() {
  const { width, height } = rotatedSize();
  const out = outputSize();
  const fit = state.contain ? Math.min(out.width / width, out.height / height) : Math.max(out.width / width, out.height / height);
  return fit * state.zoom;
}

/** Keeps the picture covering the whole frame (or centred, while it is smaller than the frame). */
function clampPan() {
  if (!state.work) {
    return;
  }
  const { width, height } = rotatedSize();
  const out = outputSize();
  const s = scale();
  const maxX = Math.max(0, (width * s - out.width) / 2);
  const maxY = Math.max(0, (height * s - out.height) / 2);
  state.panX = Math.max(-maxX, Math.min(maxX, state.panX));
  state.panY = Math.max(-maxY, Math.min(maxY, state.panY));
}

/** Draws the picture as it will be cut, onto a context of any size with the output's shape. */
function drawPicture(ctx, width, height) {
  const out = outputSize();
  const k = width / out.width;
  ctx.save();
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, width, height);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = "high";
  ctx.translate(width / 2 + state.panX * k, height / 2 + state.panY * k);
  ctx.rotate((state.rotation * Math.PI) / 2);
  const s = scale() * k;
  ctx.scale(s, s);
  ctx.drawImage(state.work, -state.work.width / 2, -state.work.height / 2);
  ctx.restore();
}

function renderOutput() {
  const { width, height } = outputSize();
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  drawPicture(canvas.getContext("2d"), width, height);
  return canvas;
}

// ---------------------------------------------------------------------------------------------------------- stage

const stage = $("#stage");
const stageCtx = stage.getContext("2d");

function layoutStage() {
  if (!state.work || $("#step-edit").hidden) {
    return;
  }
  const available = $("#stage-wrap").clientWidth;
  const maxHeight = Math.max(220, window.innerHeight * 0.52);
  const ratio = state.columns / state.rows;
  const cssWidth = Math.floor(Math.min(available, maxHeight * ratio));
  const cssHeight = Math.floor(cssWidth / ratio);
  const dpr = Math.min(3, window.devicePixelRatio || 1);
  stage.style.width = `${cssWidth}px`;
  stage.style.height = `${cssHeight}px`;
  stage.width = Math.round(cssWidth * dpr);
  stage.height = Math.round(cssHeight * dpr);
  draw();
}

function draw() {
  if (!state.work) {
    return;
  }
  const { width, height } = stage;
  if (state.preview && state.dithered) {
    stageCtx.imageSmoothingEnabled = false;
    stageCtx.drawImage(state.dithered, 0, 0, width, height);
  } else {
    drawPicture(stageCtx, width, height);
  }

  const cellW = width / state.columns;
  const cellH = height / state.rows;
  const dpr = width / parseFloat(stage.style.width);
  // left-out squares
  for (let i = 0; i < state.columns * state.rows; i++) {
    if (state.selected.has(i)) {
      continue;
    }
    const x = (i % state.columns) * cellW;
    const y = Math.floor(i / state.columns) * cellH;
    stageCtx.fillStyle = "rgba(10, 12, 16, 0.72)";
    stageCtx.fillRect(x, y, cellW, cellH);
    const r = Math.min(cellW, cellH) * 0.12;
    stageCtx.strokeStyle = "rgba(255, 255, 255, 0.75)";
    stageCtx.lineWidth = 2 * dpr;
    stageCtx.lineCap = "round";
    stageCtx.beginPath();
    stageCtx.moveTo(x + cellW / 2 - r, y + cellH / 2 - r);
    stageCtx.lineTo(x + cellW / 2 + r, y + cellH / 2 + r);
    stageCtx.moveTo(x + cellW / 2 + r, y + cellH / 2 - r);
    stageCtx.lineTo(x + cellW / 2 - r, y + cellH / 2 + r);
    stageCtx.stroke();
  }
  // seams between the maps: a dark line under a light one, visible on any picture
  if (state.columns * state.rows > 1) {
    for (const [colour, lineWidth] of [["rgba(0,0,0,0.45)", 3], ["rgba(255,255,255,0.85)", 1]]) {
      stageCtx.strokeStyle = colour;
      stageCtx.lineWidth = lineWidth * dpr;
      stageCtx.setLineDash([6 * dpr, 4 * dpr]);
      stageCtx.beginPath();
      for (let c = 1; c < state.columns; c++) {
        stageCtx.moveTo(Math.round(c * cellW), 0);
        stageCtx.lineTo(Math.round(c * cellW), height);
      }
      for (let r = 1; r < state.rows; r++) {
        stageCtx.moveTo(0, Math.round(r * cellH));
        stageCtx.lineTo(width, Math.round(r * cellH));
      }
      stageCtx.stroke();
    }
    stageCtx.setLineDash([]);
  }
}

// moving, zooming and picking squares
const pointers = new Map();
let gesture = null;

stage.addEventListener("pointerdown", (event) => {
  stage.setPointerCapture(event.pointerId);
  pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
  gesture = { moved: false, startX: event.clientX, startY: event.clientY, pinch: pointers.size > 1 };
});

stage.addEventListener("pointermove", (event) => {
  const previous = pointers.get(event.pointerId);
  if (!previous || !gesture) {
    return;
  }
  const perCss = outputSize().width / parseFloat(stage.style.width);
  if (pointers.size === 1) {
    if (Math.hypot(event.clientX - gesture.startX, event.clientY - gesture.startY) > 6) {
      gesture.moved = true;
    }
    if (gesture.moved) {
      state.panX += (event.clientX - previous.x) * perCss;
      state.panY += (event.clientY - previous.y) * perCss;
    }
  } else if (pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    const before = Math.hypot(a.x - b.x, a.y - b.y);
    const next = new Map(pointers).set(event.pointerId, { x: event.clientX, y: event.clientY });
    const [c, d] = [...next.values()];
    const after = Math.hypot(c.x - d.x, c.y - d.y);
    if (before > 0) {
      setZoom(state.zoom * (after / before));
    }
    // moving both fingers moves the picture
    state.panX += ((event.clientX - previous.x) / 2) * perCss;
    state.panY += ((event.clientY - previous.y) / 2) * perCss;
    gesture.moved = true;
  }
  pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
  clampPan();
  changed();
});

function endPointer(event) {
  if (!pointers.has(event.pointerId)) {
    return;
  }
  pointers.delete(event.pointerId);
  if (gesture && !gesture.moved && !gesture.pinch && pointers.size === 0 && event.type === "pointerup") {
    toggleSquare(event);
  }
  if (pointers.size === 0) {
    gesture = null;
  }
}

stage.addEventListener("pointerup", endPointer);
stage.addEventListener("pointercancel", endPointer);

stage.addEventListener(
  "wheel",
  (event) => {
    event.preventDefault();
    setZoom(state.zoom * Math.exp(-event.deltaY * 0.0015));
    clampPan();
    changed();
  },
  { passive: false },
);

function toggleSquare(event) {
  const total = state.columns * state.rows;
  if (total < 2) {
    return;
  }
  const rect = stage.getBoundingClientRect();
  const column = Math.min(state.columns - 1, Math.floor(((event.clientX - rect.left) / rect.width) * state.columns));
  const row = Math.min(state.rows - 1, Math.floor(((event.clientY - rect.top) / rect.height) * state.rows));
  const index = row * state.columns + column;
  if (state.selected.has(index)) {
    state.selected.delete(index);
  } else {
    state.selected.add(index);
  }
  updateSummary();
  draw();
}

function setZoom(zoom) {
  state.zoom = Math.max(1, Math.min(5, zoom));
  $("#zoom").value = state.zoom;
  updateLabels();
}

// --------------------------------------------------------------------------------------------------- map colours

let paletteCache = null;

/** The same conversion as the server (Palette.java): nearest colour by "redmean" distance, Floyd-Steinberg. */
function palette() {
  if (!paletteCache) {
    const colours = state.session.palette;
    const entries = [];
    colours.forEach((rgb, index) => {
      if (rgb >= 0) {
        entries.push([index, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255]);
      }
    });
    paletteCache = { entries, nearest: new Int16Array(1 << 18).fill(-1) };
  }
  return paletteCache;
}

function nearest(p, r, g, b) {
  const key = ((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2);
  let found = p.nearest[key];
  if (found >= 0) {
    return found;
  }
  r = (r & ~3) | 2;
  g = (g & ~3) | 2;
  b = (b & ~3) | 2;
  let best = 0;
  let bestDistance = Infinity;
  for (const [index, pr, pg, pb] of p.entries) {
    const mean = (r + pr) >> 1;
    const dr = r - pr;
    const dg = g - pg;
    const db = b - pb;
    const distance = (((512 + mean) * dr * dr) >> 8) + 4 * dg * dg + (((767 - mean) * db * db) >> 8);
    if (distance < bestDistance) {
      bestDistance = distance;
      best = index;
    }
  }
  p.nearest[key] = best;
  return best;
}

function dither(canvas) {
  const p = palette();
  const { width, height } = canvas;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  const image = ctx.getImageData(0, 0, width, height);
  const data = image.data;
  let current = new Float32Array((width + 2) * 3);
  let next = new Float32Array((width + 2) * 3);
  const colours = state.session.palette;
  const clamp = (v) => (v <= 0 ? 0 : v >= 255 ? 255 : Math.round(v));

  for (let y = 0; y < height; y++) {
    const reverse = (y & 1) === 1;
    const ahead = reverse ? -3 : 3;
    for (let step = 0; step < width; step++) {
      const x = reverse ? width - 1 - step : step;
      const i = (y * width + x) * 4;
      const e = (x + 1) * 3;
      const r = clamp(data[i] + current[e]);
      const g = clamp(data[i + 1] + current[e + 1]);
      const b = clamp(data[i + 2] + current[e + 2]);
      const chosen = colours[nearest(p, r, g, b)];
      const cr = (chosen >> 16) & 255;
      const cg = (chosen >> 8) & 255;
      const cb = chosen & 255;
      data[i] = cr;
      data[i + 1] = cg;
      data[i + 2] = cb;
      const er = r - cr;
      const eg = g - cg;
      const eb = b - cb;
      spread(current, e + ahead, er, eg, eb, 7 / 16);
      spread(next, e - ahead, er, eg, eb, 3 / 16);
      spread(next, e, er, eg, eb, 5 / 16);
      spread(next, e + ahead, er, eg, eb, 1 / 16);
    }
    [current, next] = [next, current];
    next.fill(0);
  }
  ctx.putImageData(image, 0, 0);
  return canvas;
}

function spread(errors, offset, r, g, b, weight) {
  errors[offset] += r * weight;
  errors[offset + 1] += g * weight;
  errors[offset + 2] += b * weight;
}

// ---------------------------------------------------------------------------------------------------- updates

let previewTimer = 0;

function changed() {
  state.dithered = null;
  updateSummary();
  draw();
  if (state.preview) {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(() => {
      state.dithered = dither(renderOutput());
      draw();
    }, 120);
  }
}

function updateSummary() {
  if (!state.session || !state.work) {
    return;
  }
  const total = state.columns * state.rows;
  const selected = state.selected.size;
  const { width, height } = outputSize();
  const maps = state.session.maps;
  const summary = $("#summary");
  let html = t("summary", { selected, total, size: `${width}×${height}` });
  let ok = selected > 0;
  if (selected === 0) {
    html = t("noneSelected");
  } else if (maps >= 0 && selected > maps) {
    html = t("summaryNeed", { selected, maps });
    ok = false;
  } else if (maps >= 0) {
    html += `<br>${t("summaryMaps", { maps })}`;
  }
  summary.innerHTML = html;
  summary.classList.toggle("warn", !ok);
  $("#send").disabled = !ok;
}

// ----------------------------------------------------------------------------------------------------- sending

async function send() {
  const parts = [...state.selected].sort((a, b) => a - b);
  const canvas = renderOutput();
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/png"));
  show("sending");
  try {
    const query = new URLSearchParams({ columns: state.columns, rows: state.rows, parts: parts.join(",") });
    const response = await fetch(`/api/upload/${token}?${query}`, {
      method: "POST",
      headers: { "Content-Type": "image/png" },
      body: blob,
    });
    const body = await response.json().catch(() => ({}));
    if (response.ok) {
      $("#done-title").textContent = body.count === 1 ? t("doneOne") : t("doneMany", { n: body.count });
      show("done");
      if (navigator.vibrate) {
        navigator.vibrate(30);
      }
      refreshSession();
      return;
    }
    if (body.error === "expired" || body.error === "offline") {
      sessionError(body.error);
      return;
    }
    const message =
      body.error === "maps"
        ? t("mapsError", { needed: body.needed, available: body.available })
        : body.error === "busy"
          ? t("busyError")
          : body.error === "invalid"
            ? t("invalidError")
            : t("serverError");
    show("edit");
    flash(message);
    refreshSession();
  } catch {
    show("edit");
    flash(t("networkError"));
  }
}

function flash(message) {
  const summary = $("#summary");
  summary.textContent = message;
  summary.classList.add("warn");
  setTimeout(updateSummary, 5000);
}

// ------------------------------------------------------------------------------------------------------ wiring

for (const input of document.querySelectorAll("[data-source]")) {
  input.addEventListener("change", () => {
    openFile(input.files[0]);
    input.value = "";
  });
}

for (const id of ["columns", "rows"]) {
  $(`#${id}`).addEventListener("input", (event) => {
    const value = Number(event.target.value);
    if (value !== state[id]) {
      click();
      setGrid(id === "columns" ? value : state.columns, id === "rows" ? value : state.rows);
    }
  });
}

$("#rotate").addEventListener("click", () => {
  state.rotation = (state.rotation + 1) % 4;
  state.panX = state.panY = 0;
  clampPan();
  changed();
});

$("#fit").addEventListener("click", () => {
  state.contain = !state.contain;
  $("#fit").setAttribute("aria-pressed", String(state.contain));
  updateLabels();
  clampPan();
  changed();
});

$("#preview").addEventListener("click", () => {
  state.preview = !state.preview;
  $("#preview").setAttribute("aria-pressed", String(state.preview));
  updateLabels();
  changed();
});

$("#zoom").addEventListener("input", (event) => {
  setZoom(Number(event.target.value));
  clampPan();
  changed();
});

$("#other").addEventListener("click", () => show("pick"));
$("#more").addEventListener("click", () => show("pick"));
$("#send").addEventListener("click", send);

window.addEventListener("resize", layoutStage);
document.addEventListener("visibilitychange", refreshSession);

// pasting and dropping, for computers
document.addEventListener("paste", (event) => {
  const file = [...(event.clipboardData?.files || [])].find((f) => f.type.startsWith("image/"));
  if (file && state.session) {
    openFile(file);
  }
});
document.addEventListener("dragover", (event) => {
  event.preventDefault();
  document.body.classList.add("dragging");
});
document.addEventListener("dragleave", (event) => {
  if (!event.relatedTarget) {
    document.body.classList.remove("dragging");
  }
});
document.addEventListener("drop", (event) => {
  event.preventDefault();
  document.body.classList.remove("dragging");
  if (state.session) {
    openFile(event.dataTransfer.files[0]);
  }
});

// a short click for every button, made on the fly (no sound file needed)
let audio = null;

function click() {
  try {
    audio = audio || new AudioContext();
    const length = Math.floor(audio.sampleRate * 0.035);
    const buffer = audio.createBuffer(1, length, audio.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < length; i++) {
      data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, 4);
    }
    const source = audio.createBufferSource();
    const filter = audio.createBiquadFilter();
    const gain = audio.createGain();
    filter.type = "bandpass";
    filter.frequency.value = 2400;
    gain.gain.value = 0.35;
    source.buffer = buffer;
    source.connect(filter).connect(gain).connect(audio.destination);
    source.start();
  } catch {
    // no sound, no problem
  }
}

document.addEventListener("pointerdown", (event) => {
  const button = event.target.closest(".mc-button");
  if (button && !button.disabled) {
    click();
  }
});

// ------------------------------------------------------------------------------------------------------- start

(async () => {
  updateLabels();
  if (!token) {
    fail(t("noTokenTitle"), t("noTokenText"));
    return;
  }
  try {
    applySession(await loadSession());
    show("pick");
    setInterval(tickTimer, 1000);
    setInterval(refreshSession, 15000);
    tickTimer();
  } catch (error) {
    if (error.code) {
      sessionError(error.code);
    } else {
      fail(t("networkError"), "");
    }
  }
})();
