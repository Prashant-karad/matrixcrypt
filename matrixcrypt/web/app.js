'use strict';

/* =====================================================================
   MatrixCrypt front-end
   All heavy computation happens in Java on the server. The browser:
   - starts runs and polls their progress
   - replays the recorded timeline (which row finished when, by which thread)
   - shows the pixel inspector, the race and the benchmark charts
   ===================================================================== */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const sleep = ms => new Promise(r => setTimeout(r, ms));
const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

const METHODS = {
  single: { label: 'Single-threaded', short: 'Single', color: '--m-single' },
  row:    { label: 'Thread per row',  short: 'Per row', color: '--m-row' },
  pool:   { label: 'Thread pool',     short: 'Pool', color: '--m-pool' },
  cell:   { label: 'Thread per cell', short: 'Per cell', color: '--m-cell' },
};
const ORDER = ['single', 'row', 'pool', 'cell'];

const app = { n: 512, cores: 2, busy: false, encSeed: null, filters: [], benchResults: [] };

/* ---------------------------------------------------------------- formatting */

const fmtInt = v => Math.round(v).toLocaleString('en-US');
function fmtMs(v) {
  if (v == null || v < 0) return '–';
  if (v < 1) return v.toFixed(3) + ' ms';
  if (v < 100) return v.toFixed(2) + ' ms';
  if (v < 1000) return v.toFixed(0) + ' ms';
  return (v / 1000).toFixed(2) + ' s';
}
const methodColor = m => css(METHODS[m].color);
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function threadsFor(method, n) {
  return { single: 1, row: n, cell: n * n, pool: Math.min(app.cores, n) }[method];
}

/* ---------------------------------------------------------------- server calls */

async function api(path, opts = {}) {
  const r = await fetch(path, opts);
  let j;
  try { j = await r.json(); } catch { throw new Error('Server returned an unexpected response (' + r.status + ')'); }
  if (!r.ok || j.error) throw new Error(j.error || r.statusText);
  return j;
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Could not load ' + url));
    img.src = url + '?t=' + Date.now();
  });
}

/** Starts a run on the server and polls until it finishes. onProgress gets the job status. */
async function runOnServer(query, onProgress) {
  await api('/api/run?' + query, { method: 'POST' });
  for (;;) {
    await sleep(90);
    const j = await api('/api/job');
    onProgress(j);
    if (j.finished) {
      if (j.error) throw new Error(j.error);
      return j;
    }
  }
}

function setBusy(b) {
  app.busy = b;
  $$('[data-action]').forEach(el => { el.disabled = b || el.dataset.locked === '1'; });
  $('#fileInput').disabled = b;
}

/* ---------------------------------------------------------------- image panel */

const confettiCache = {};
function confetti(n) {
  if (confettiCache[n]) return confettiCache[n];
  const c = document.createElement('canvas');
  c.width = c.height = n;
  const ctx = c.getContext('2d');
  const data = ctx.createImageData(n, n);
  for (let p = 0; p < n * n; p++) {
    const h = Math.random() * 6, x = 1 - Math.abs((h % 2) - 1);
    const [r, g, b] = h < 1 ? [1, x, 0] : h < 2 ? [x, 1, 0] : h < 3 ? [0, 1, x] : h < 4 ? [0, x, 1] : h < 5 ? [x, 0, 1] : [1, 0, x];
    data.data[p * 4] = 40 + r * 215; data.data[p * 4 + 1] = 40 + g * 215; data.data[p * 4 + 2] = 40 + b * 215; data.data[p * 4 + 3] = 255;
  }
  ctx.putImageData(data, 0, 0);
  return (confettiCache[n] = c);
}

function threadColor(method, t) {
  if (method === 'single') return methodColor('single');
  if (method === 'pool') return `hsl(${(t * 360 / Math.max(app.cores, 2) + 150) % 360} 85% 55%)`;
  return `hsl(${(t * 137.508) % 360} 80% 58%)`;
}

class Panel {
  constructor(el) {
    this.el = typeof el === 'string' ? $(el) : el;
    this.stage = $('.stage', this.el);
    this.base = $('.base', this.el);
    this.map = $('.map', this.el);
    this.hl = $('.hl', this.el);
    this.empty = $('.empty', this.el);
    this.n = 0;
    this.tl = null;
    this.img = null;
    this.ready = false;
    this.hlCanvasNeeded = !!this.hl;
  }
  canvases() { return [this.base, this.map, this.hl].filter(Boolean); }
  ctx(c) { const x = c.getContext('2d'); x.imageSmoothingEnabled = false; return x; }
  setSize(n) {
    if (this.n !== n) { this.canvases().forEach(c => { c.width = c.height = n; }); this.n = n; }
    this.stage.classList.toggle('pixelated', n <= 256);
  }
  clear(msg = '') {
    this.canvases().forEach(c => this.ctx(c).clearRect(0, 0, this.n, this.n));
    if (this.empty) this.empty.textContent = msg;
    this.tl = null; this.img = null; this.setReady(false);
  }
  setReady(r) { this.ready = r; this.stage.classList.toggle('ready', r); }
  draw(img) {
    const c = this.ctx(this.base);
    c.clearRect(0, 0, this.n, this.n);
    c.drawImage(img, 0, 0, this.n, this.n);
    if (this.empty) this.empty.textContent = '';
    this.img = img;
  }
  clearHl() { if (this.hl) this.ctx(this.hl).clearRect(0, 0, this.n, this.n); }
  mapRow(r, t, method) {
    const c = this.ctx(this.map);
    if (t === -1) c.drawImage(confetti(this.n), 0, r, this.n, 1, 0, r, this.n, 1);
    else { c.fillStyle = threadColor(method, t); c.fillRect(0, r, this.n, 1); }
  }
  drawMap(show) {
    this.ctx(this.map).clearRect(0, 0, this.n, this.n);
    if (!show || !this.tl) return;
    for (let r = 0; r < this.tl.n; r++) this.mapRow(r, this.tl.rowThread[r], this.tl.method);
  }
}

/**
 * Replays a recorded run: rows appear in the order (and at the relative times) they
 * were really finished on the server. scale = playback ms per real ms.
 */
function replay(panel, img, tl, { scale, showMap, onTick }) {
  return new Promise(resolve => {
    const n = tl.n;
    const order = [...Array(n).keys()].sort((a, b) => tl.rowDoneMs[a] - tl.rowDoneMs[b]);
    const total = Math.max(tl.totalMs, tl.rowDoneMs[order[n - 1]] || 0, 1e-3);
    const base = panel.ctx(panel.base);
    base.clearRect(0, 0, n, n);
    panel.ctx(panel.map).clearRect(0, 0, n, n);
    if (panel.empty) panel.empty.textContent = '';
    panel.img = img; panel.tl = tl;
    let i = 0;
    const t0 = performance.now();
    function frame(now) {
      const real = (now - t0) / scale;
      while (i < n && tl.rowDoneMs[order[i]] <= real) {
        const r = order[i++];
        base.drawImage(img, 0, r, n, 1, 0, r, n, 1);
        if (showMap) panel.mapRow(r, tl.rowThread[r], tl.method);
      }
      const done = i >= n && real >= total;
      if (onTick) onTick(Math.min(real, total), i / n, total);
      if (!done) requestAnimationFrame(frame);
      else resolve();
    }
    requestAnimationFrame(frame);
  });
}

/* ---------------------------------------------------------------- shared UI bits */

function statBoxes(items) {
  return items.map(([k, v, big]) => `<div class="stat"><div class="k">${k}</div><div class="v${big ? ' big' : ''}">${v}</div></div>`).join('');
}

function legendHtml(method, n) {
  if (!method) return '';
  if (method === 'single') return `<span class="sw" style="background:${methodColor('single')}"></span> All rows by 1 thread`;
  if (method === 'cell') return `<span class="sw confetti"></span> Every pixel had its own thread (${fmtInt(n * n)} threads)`;
  if (method === 'pool') {
    let h = '';
    for (let t = 0; t < Math.min(app.cores, n); t++) h += `<span class="sw" style="background:${threadColor('pool', t)}"></span> Worker ${t + 1} `;
    return h + `<span class="muted">· rows are shared dynamically</span>`;
  }
  let h = '';
  for (let t = 0; t < 6; t++) h += `<span class="sw" style="background:${threadColor('row', t)}"></span>`;
  return h + ` Each row is its own thread (${fmtInt(n)} threads)`;
}

function methodHint(method, n) {
  const t = threadsFor(method, n);
  const base = `${METHODS[method].label} will create ${fmtInt(t)} thread${t === 1 ? '' : 's'} for ${n} × ${n}.`;
  if (method === 'cell' && n >= 512) return { text: base + ' This can take 30 seconds or more — that slowness is the lesson.', warn: true };
  if (method === 'cell' && n >= 256) return { text: base + ' Expect several seconds.', warn: true };
  return { text: base, warn: false };
}

function statusFromJob(j, label) {
  if (j.phase === 'key') return `Generating the ${j.n} × ${j.n} key matrix K (first time for this seed)…`;
  if (j.phase === 'warmup') return 'Warming up the Java JIT compiler so the race is fair…';
  if (j.phase === 'key-cached' || j.phase === 'starting') return 'Starting…';
  return `${label || METHODS[j.method].label} · rows ${fmtInt(j.rowsDone)}/${fmtInt(j.n)} · ${fmtMs(j.elapsedMs)} · ${fmtInt(j.threads)} threads`;
}

/* ================================================================ LAB (encrypt/decrypt) */

const pOriginal = new Panel('#pOriginal');
const pEncrypted = new Panel('#pEncrypted');
const pDecrypted = new Panel('#pDecrypted');
const pFOriginal = new Panel('#pFOriginal');
const pFiltered = new Panel('#pFiltered');

const labStatus = t => { $('#labStatus').textContent = t; };
const labFill = f => { $('#labFill').style.width = (f * 100).toFixed(1) + '%'; };
const replaySeconds = id => +$(id).value;

function updateMethodHint() {
  const h = methodHint($('#methodSel').value, app.n);
  const el = $('#methodHint');
  el.textContent = h.text;
  el.classList.toggle('warn', h.warn);
}

function lockDecrypt(locked) {
  ['#btnDecrypt', '#btnWrong'].forEach(s => { const b = $(s); b.dataset.locked = locked ? '1' : '0'; b.disabled = locked || app.busy; });
}

async function refreshOriginal() {
  const img = await loadImage('/api/img/original.png');
  app.origImg = img;
  [pOriginal, pEncrypted, pDecrypted, pFOriginal, pFiltered].forEach(p => p.setSize(app.n));
  pOriginal.draw(img);
  pFOriginal.draw(img);
  pEncrypted.clear('Press Encrypt');
  pDecrypted.clear('Encrypt first, then Decrypt');
  pFiltered.clear('Pick a filter above');
  $('#metaOriginal').innerHTML = `<span>${app.n} × ${app.n} · 3 channels</span><span>${fmtInt(app.n * app.n)} pixels</span>`;
  $('#metaEncrypted').innerHTML = '&nbsp;';
  $('#metaDecrypted').innerHTML = '&nbsp;';
  $('#metaFiltered').innerHTML = '&nbsp;';
  $('#chipSize').innerHTML = `Matrix: <b>${app.n} × ${app.n}</b>`;
  app.encSeed = null;
  lockDecrypt(true);
  updateMethodHint();
  resetRace();
}

async function setImage(query, body) {
  if (app.busy) return;
  setBusy(true);
  labStatus('Loading image…');
  try {
    const r = await api('/api/image?' + query, { method: 'POST', body: body || '' });
    app.n = r.n;
    await refreshOriginal();
    labStatus('Image ready');
    labFill(0);
  } catch (e) {
    labStatus('Error: ' + e.message);
  } finally {
    setBusy(false);
  }
}

async function encrypt() {
  if (app.busy) return;
  const method = $('#methodSel').value;
  const seed = $('#seedInput').value || '2026';
  setBusy(true);
  pDecrypted.clear('Encrypt first, then Decrypt');
  $('#metaDecrypted').innerHTML = '&nbsp;';
  pEncrypted.clear('');
  try {
    labFill(0);
    await runOnServer(`op=encrypt&method=${method}&seed=${encodeURIComponent(seed)}`, j => {
      labStatus(statusFromJob(j));
      labFill(j.rowsDone / j.n);
    });
    const [tl, img] = await Promise.all([api('/api/timeline'), loadImage('/api/img/encrypted.png')]);
    labStatus(`Encrypted in ${fmtMs(tl.totalMs)} · replaying the recorded timeline…`);
    const scale = replaySeconds('#replaySlider') * 1000 / Math.max(tl.totalMs, 1e-3);
    await replay(pEncrypted, img, tl, { scale, showMap: $('#mapToggle').checked, onTick: (t, f) => labFill(f) });
    pEncrypted.setReady(true);
    app.encSeed = seed;
    $('#metaEncrypted').innerHTML = `<span>${METHODS[method].short} · ${fmtMs(tl.totalMs)}</span><span>seed ${esc(seed)}</span>`;
    showStats('Encrypt', tl, seed);
    labStatus(`Encrypted in ${fmtMs(tl.totalMs)} with ${METHODS[method].label.toLowerCase()} · hover the image to inspect a pixel`);
    lockDecrypt(false);
  } catch (e) {
    labStatus('Error: ' + e.message);
    pEncrypted.clear('Something went wrong');
  } finally {
    setBusy(false);
  }
}

async function decrypt(wrongKey) {
  if (app.busy || app.encSeed == null) return;
  const method = $('#methodSel').value;
  let seed = $('#seedInput').value || '2026';
  if (wrongKey) seed = String(BigInt(app.encSeed) + 1n);
  setBusy(true);
  pDecrypted.clear('');
  $('#metaDecrypted').innerHTML = '&nbsp;';
  try {
    labFill(0);
    await runOnServer(`op=decrypt&method=${method}&seed=${encodeURIComponent(seed)}`, j => {
      labStatus(statusFromJob(j));
      labFill(j.rowsDone / j.n);
    });
    const [tl, img] = await Promise.all([api('/api/timeline'), loadImage('/api/img/decrypted.png')]);
    const scale = replaySeconds('#replaySlider') * 1000 / Math.max(tl.totalMs, 1e-3);
    await replay(pDecrypted, img, tl, { scale, showMap: $('#mapToggle').checked, onTick: (t, f) => labFill(f) });
    pDecrypted.setReady(true);
    const v = await api('/api/verify');
    const pct = v.differences * 100 / v.total;
    $('#metaDecrypted').innerHTML = v.differences === 0
      ? `<span class="badge ok">✓ Pixel-perfect: 0 of ${fmtInt(v.total)} pixels differ</span><span>seed ${esc(seed)}</span>`
      : `<span class="badge bad">✗ Wrong key: ${pct.toFixed(1)}% of pixels wrong</span><span>seed ${esc(seed)} ≠ ${esc(app.encSeed)}</span>`;
    showStats(wrongKey ? 'Decrypt (wrong key)' : 'Decrypt', tl, seed);
    labStatus(v.differences === 0
      ? `Decrypted in ${fmtMs(tl.totalMs)} · original recovered exactly`
      : `Decrypted with the wrong key (seed ${seed}) · the image stays unreadable`);
  } catch (e) {
    labStatus('Error: ' + e.message);
    pDecrypted.clear('Something went wrong');
  } finally {
    setBusy(false);
  }
}

function showStats(opName, tl, seed) {
  const n = tl.n;
  const ops = 3 * n * n * n;
  $('#stats').innerHTML = statBoxes([
    ['Time', fmtMs(tl.totalMs), true],
    ['Method', METHODS[tl.method].label],
    ['Operation', opName],
    ['Threads created', fmtInt(tl.threads)],
    ['Matrix size', `${n} × ${n}`],
    ['Multiply-adds', fmtInt(ops) + ' <span class="muted small">(3·n³)</span>'],
    ['Throughput', (ops / Math.max(tl.totalMs, 1e-3) / 1e6).toFixed(2) + ' G/s'],
    ['Key seed', esc(seed)],
  ]);
  updateLegend();
}

function updateLegend() {
  const tl = pDecrypted.tl || pEncrypted.tl;
  const leg = $('#mapLegend');
  leg.hidden = !$('#mapToggle').checked || !tl;
  if (tl) leg.innerHTML = legendHtml(tl.method, tl.n);
}

/* ---------------------------------------------------------------- pixel inspector */

const insp = { pending: null, busy: false };

function requestInspect(kind, i, j, render) {
  insp.pending = { kind, i, j, render };
  if (!insp.busy) pumpInspect();
}

async function pumpInspect() {
  insp.busy = true;
  while (insp.pending) {
    const q = insp.pending;
    insp.pending = null;
    try {
      const d = await api(`/api/inspect?img=${q.kind}&i=${q.i}&j=${q.j}`);
      q.render(d);
    } catch { /* image changed mid-hover; ignore */ }
  }
  insp.busy = false;
}

function pixelFromEvent(panel, e) {
  const r = panel.stage.getBoundingClientRect();
  const j = Math.floor((e.clientX - r.left) / r.width * panel.n);
  const i = Math.floor((e.clientY - r.top) / r.height * panel.n);
  if (i < 0 || j < 0 || i >= panel.n || j >= panel.n) return null;
  return { i, j };
}

function drawCrosshair(panel, i, j) {
  panel.clearHl();
  const c = panel.ctx(panel.hl), n = panel.n;
  const s = Math.max(3, Math.round(n / 48)), lw = Math.max(1, n / 300);
  c.fillStyle = 'rgba(255,255,255,0.12)';
  c.fillRect(0, i, n, 1); c.fillRect(j, 0, 1, n);
  c.strokeStyle = '#fff'; c.lineWidth = lw;
  c.strokeRect(j - s / 2, i - s / 2, s, s);
}

function drawColumn(panel, j) {
  panel.clearHl();
  const c = panel.ctx(panel.hl), n = panel.n;
  const w = Math.max(1, Math.round(n / 256));
  c.fillStyle = 'rgba(79,140,255,0.85)';
  c.fillRect(j - Math.floor(w / 2), 0, w, n);
  c.fillStyle = 'rgba(79,140,255,0.18)';
  c.fillRect(j - 3 * w, 0, 7 * w, n);
}

function renderCipherInspect(d) {
  const dec = d.img === 'decrypted';
  const A = dec ? 'K⁻¹' : 'K', B = dec ? 'C' : 'P', R = dec ? 'P′' : 'C';
  const ok = d.channels.every(c => c.value === c.stored);
  const rgb = d.channels.map(c => c.stored).join(',');
  let h = `<div class="insp-head"><span class="sw" style="background:rgb(${rgb})"></span>
    <div><b class="mono">${R}[${d.i}][${d.j}]</b> <span class="muted">row ${d.i}, column ${d.j} · rgb(${rgb})</span></div>
    <span class="badge ${ok ? 'ok' : 'bad'}">${ok ? '✓ matches the image' : '✗ mismatch'}</span></div>
    <p class="insp-note">Each channel is row ${d.i} of the key <b>${A}</b> multiplied with column ${d.j} of <b>${B}</b>: ${fmtInt(d.n)} multiplications, then mod 256. The blue line on the ${dec ? 'encrypted' : 'original'} image marks that column — every one of its pixels affects this pixel.</p>`;
  for (const c of d.channels) {
    const terms = c.terms.map(([a, b]) => `${a}·${b}`).join(' + ');
    h += `<div class="chan"><span class="cn ${c.name}">${c.name}</span>
      <div class="line"><span class="muted">Σ ${A}[${d.i}][k]·${B}[k][${d.j}] =</span> ${terms} + … <span class="muted">(${fmtInt(d.n)} terms)</span></div>
      <div class="line res">= ${fmtInt(c.sum)} → ${fmtInt(c.sum)} mod 256 = <b>${c.value}</b></div></div>`;
  }
  $('#inspBody').innerHTML = h;
}

function attachCipherInspector(panel, kind, source) {
  panel.stage.addEventListener('mousemove', e => {
    if (!panel.ready) return;
    const p = pixelFromEvent(panel, e);
    if (!p) return;
    drawCrosshair(panel, p.i, p.j);
    drawColumn(source, p.j);
    requestInspect(kind, p.i, p.j, renderCipherInspect);
  });
  panel.stage.addEventListener('mouseleave', () => { panel.clearHl(); source.clearHl(); });
}

/* ================================================================ PHOTO FILTER STUDIO */

const fStatus = t => { $('#fStatus').textContent = t; };
const fFill = f => { $('#fFill').style.width = (f * 100).toFixed(1) + '%'; };
let liveTimer = null;

async function loadFilters() {
  const d = await api('/api/filters');
  app.filters = d.filters;
  const box = $('#presets');
  box.innerHTML = '<span class="sep" style="margin-left:0">Photo</span>';
  d.filters.forEach((f, idx) => {
    if (f.id === 'protanopia') box.insertAdjacentHTML('beforeend', '<span class="sep">Colour-blindness simulation</span>');
    const b = document.createElement('button');
    b.className = 'preset';
    b.textContent = f.name;
    b.dataset.id = f.id;
    b.dataset.action = '1';
    b.onclick = () => choosePreset(f, true);
    box.appendChild(b);
  });
  buildMatrixEditor();
  setMatrix(d.filters[1].m, d.filters[1]);   // start with sepia values shown
  $$('.preset').forEach(b => b.classList.remove('active'));
  fStatus('Pick a filter, or edit the matrix');
}

function buildMatrixEditor() {
  const ed = $('#matrixEditor');
  ['R′', 'G′', 'B′'].forEach((lab, r) => {
    ed.insertAdjacentHTML('beforeend', `<span class="mlabel rowl cn ${'RGB'[r]}">${lab}</span>`);
    for (let c = 0; c < 4; c++) {
      const inp = document.createElement('input');
      inp.type = 'number'; inp.step = c === 3 ? '1' : '0.01';
      inp.dataset.k = r * 4 + c;
      if (c === 3) inp.classList.add('off');
      inp.addEventListener('input', onMatrixEdit);
      ed.appendChild(inp);
    }
  });
}

function setMatrix(m, f) {
  $$('#matrixEditor input').forEach(inp => { inp.value = +(+m[inp.dataset.k]).toFixed(4); });
  $('#filterName').textContent = f ? '· ' + f.name : '· Custom';
  $('#filterAbout').textContent = f ? f.about : 'Your own filter. Each output channel is a weighted sum of the input R, G and B, plus an offset.';
}

function readMatrix() {
  return $$('#matrixEditor input').map(inp => { const v = parseFloat(inp.value); return Number.isFinite(v) ? v : 0; });
}

function onMatrixEdit() {
  $$('.preset').forEach(b => b.classList.remove('active'));
  $('#filterName').textContent = '· Custom';
  $('#filterAbout').textContent = 'Your own filter. Each output channel is a weighted sum of the input R, G and B, plus an offset.';
  if (!$('#fLive').checked) return;
  clearTimeout(liveTimer);
  liveTimer = setTimeout(() => applyFilter({ animate: false, method: 'pool' }), 220);
}

function choosePreset(f, run) {
  $$('.preset').forEach(b => b.classList.toggle('active', b.dataset.id === f.id));
  setMatrix(f.m, f);
  if (run) applyFilter({ animate: true });
}

async function applyFilter({ animate, method } = {}) {
  if (app.busy) return;
  method = method || $('#fMethodSel').value;
  const m = readMatrix();
  setBusy(true);
  try {
    fFill(0);
    await runOnServer(`op=filter&method=${method}&matrix=${m.join(',')}`, j => {
      fStatus(statusFromJob(j));
      fFill(j.rowsDone / j.n);
    });
    const [tl, img] = await Promise.all([api('/api/timeline'), loadImage('/api/img/filtered.png')]);
    if (animate) {
      const scale = replaySeconds('#fReplaySlider') * 1000 / Math.max(tl.totalMs, 1e-3);
      await replay(pFiltered, img, tl, { scale, showMap: $('#fMapToggle').checked, onTick: (t, f) => fFill(f) });
    } else {
      pFiltered.draw(img);
      pFiltered.tl = tl;
      pFiltered.drawMap($('#fMapToggle').checked);
      fFill(1);
    }
    pFiltered.setReady(true);
    $('#metaFiltered').innerHTML = `<span>${METHODS[method].short} · ${fmtMs(tl.totalMs)}</span><span>${fmtInt(tl.threads)} threads</span>`;
    const n = tl.n, ops = 9 * n * n;
    $('#fStats').innerHTML = statBoxes([
      ['Time', fmtMs(tl.totalMs), true],
      ['Method', METHODS[method].label],
      ['Threads created', fmtInt(tl.threads)],
      ['Pixels', fmtInt(n * n)],
      ['Multiply-adds', fmtInt(ops) + ' <span class="muted small">(9·n²)</span>'],
      ['Per pixel', fmtMs(tl.totalMs / (n * n))],
    ]);
    fStatus(`Filtered in ${fmtMs(tl.totalMs)} with ${METHODS[method].label.toLowerCase()}${animate ? '' : ' (live preview)'} · hover the image to inspect a pixel`);
  } catch (e) {
    fStatus('Error: ' + e.message);
  } finally {
    setBusy(false);
  }
}

function renderFilterInspect(d) {
  const [R, G, B] = d.in, out = d.out, m = d.m;
  const f = v => (Math.abs(v - Math.round(v)) < 1e-9 ? String(Math.round(v)) : (+v.toFixed(3)).toString());
  let h = `<div class="insp-head"><span class="sw" style="background:rgb(${R},${G},${B})"></span><span class="op">→</span>
    <span class="sw" style="background:rgb(${out.join(',')})"></span>
    <div><b class="mono">pixel [${d.i}][${d.j}]</b> <span class="muted">row ${d.i}, column ${d.j}</span></div></div>
    <div class="mv">
      <div class="mat c3">${[0, 1, 2].map(r => [0, 1, 2].map(c => `<span>${f(m[r * 4 + c])}</span>`).join('')).join('')}</div>
      <span class="op">×</span>
      <div class="mat c1"><span>${R}</span><span>${G}</span><span>${B}</span></div>
      <span class="op">+</span>
      <div class="mat c1">${[0, 1, 2].map(r => `<span>${f(m[r * 4 + 3])}</span>`).join('')}</div>
      <span class="op">=</span>
      <div class="mat c1">${out.map(v => `<span><b>${v}</b></span>`).join('')}</div>
    </div>`;
  ['R', 'G', 'B'].forEach((name, r) => {
    const raw = m[r * 4] * R + m[r * 4 + 1] * G + m[r * 4 + 2] * B + m[r * 4 + 3];
    const clamped = raw < 0 || raw > 255;
    h += `<div class="chan"><span class="cn ${name}">${name}′</span>
      <div class="line">${f(m[r * 4])}·${R} + ${f(m[r * 4 + 1])}·${G} + ${f(m[r * 4 + 2])}·${B} + ${f(m[r * 4 + 3])}</div>
      <div class="line res">= ${raw.toFixed(2)} → <b>${out[r]}</b>${clamped ? ' <span class="muted">(clamped to 0–255)</span>' : ''}</div></div>`;
  });
  $('#fInsp').innerHTML = h;
}

function attachFilterInspector() {
  pFiltered.stage.addEventListener('mousemove', e => {
    if (!pFiltered.ready) return;
    const p = pixelFromEvent(pFiltered, e);
    if (!p) return;
    drawCrosshair(pFiltered, p.i, p.j);
    drawCrosshair(pFOriginal, p.i, p.j);
    requestInspect('filtered', p.i, p.j, renderFilterInspect);
  });
  pFiltered.stage.addEventListener('mouseleave', () => { pFiltered.clearHl(); pFOriginal.clearHl(); });
}

/* ================================================================ RACE */

const lanes = {};

function buildLanes() {
  const box = $('#lanes');
  box.innerHTML = '';
  for (const m of ORDER) {
    const el = document.createElement('div');
    el.className = 'lane';
    el.style.setProperty('--lane-color', `var(${METHODS[m].color})`);
    el.innerHTML = `<div class="lane-head"><b><i class="dot ${m}"></i>${METHODS[m].label}</b></div>
      <span class="place"></span>
      <div class="stage"><canvas class="base"></canvas><canvas class="map"></canvas><div class="empty"></div></div>
      <div class="clock">0.00 ms</div>
      <div class="bar"><div></div></div>
      <div class="sub"></div>`;
    box.appendChild(el);
    const p = new Panel(el);
    lanes[m] = { el, panel: p };
  }
}

function resetRace() {
  $('#raceCell').checked = app.n <= 256;
  for (const m of ORDER) {
    const L = lanes[m];
    if (!L) continue;
    L.panel.setSize(app.n);
    L.panel.clear('Waiting for start');
    L.el.classList.remove('done');
    $('.clock', L.el).textContent = '0.00 ms';
    $('.bar div', L.el).style.width = '0';
    $('.sub', L.el).textContent = `${fmtInt(threadsFor(m, app.n))} threads`;
    L.el.style.opacity = '';
  }
  $('#raceSummary').hidden = true;
  raceHint();
}

function raceHint() {
  const el = $('#raceHint');
  if ($('#raceCell').checked && app.n >= 512) {
    el.innerHTML = `Thread per cell at ${app.n} × ${app.n} creates <b>${fmtInt(app.n * app.n)}</b> threads and can take 30 s or more. Untick it for a quick race, or choose 256 in the first tab.`;
    el.classList.add('warn');
  } else {
    el.innerHTML = 'Encrypts the current image with every method, one after another, for real on the server. Then all lanes are replayed side by side on the <b>same time scale</b>, so their speeds can be compared fairly.';
    el.classList.remove('warn');
  }
}

async function startRace() {
  if (app.busy) return;
  const withCell = $('#raceCell').checked;
  const methods = ORDER.filter(m => withCell || m !== 'cell');
  const seed = $('#seedInput').value || '2026';
  resetRace();
  $('#raceCell').checked = withCell;
  raceHint();
  if (!withCell) { lanes.cell.panel.clear('Not included'); lanes.cell.el.style.opacity = '.45'; }
  setBusy(true);
  const status = t => { $('#raceStatus').textContent = t; };
  const fill = f => { $('#raceFill').style.width = (f * 100).toFixed(1) + '%'; };
  try {
    await runOnServer(`op=race&seed=${encodeURIComponent(seed)}&cell=${withCell ? 1 : 0}`, j => {
      const idx = Math.max(1, j.raceIndex);
      status(j.phase === 'running'
        ? `Running for real: ${METHODS[j.method].label} (${idx}/${methods.length}) · rows ${fmtInt(j.rowsDone)}/${fmtInt(j.n)} · ${fmtMs(j.elapsedMs)}`
        : statusFromJob(j));
      fill(j.phase === 'running' ? ((idx - 1) + j.rowsDone / j.n) / methods.length : 0);
    });
    const [data, img] = await Promise.all([api('/api/race'), loadImage('/api/img/encrypted.png')]);
    const maxTotal = Math.max(...data.lanes.map(l => l.totalMs));
    const scale = (+$('#raceSlider').value) * 1000 / maxTotal;
    const showMap = $('#raceMap').checked;
    const ranking = [...data.lanes].sort((a, b) => a.totalMs - b.totalMs).map(l => l.method);
    status(`Replaying all lanes on one time scale (slowest = ${fmtMs(maxTotal)} real time)…`);
    await Promise.all(data.lanes.map(tl => {
      const L = lanes[tl.method];
      return replay(L.panel, img, tl, {
        scale, showMap,
        onTick: (t, f, total) => {
          $('.clock', L.el).textContent = fmtMs(t);
          $('.bar div', L.el).style.width = (f * 100) + '%';
          if (f >= 1 && t >= total && !L.el.classList.contains('done')) {
            const place = ranking.indexOf(tl.method) + 1;
            $('.place', L.el).textContent = ['🥇 1st', '🥈 2nd', '🥉 3rd', '4th'][place - 1];
            L.el.classList.add('done');
          }
        },
      });
    }));
    fill(1);
    status('Race finished');
    raceSummary(data.lanes);
    // The lab tab now shows this encryption too
    pEncrypted.draw(img);
    pEncrypted.tl = data.lanes[data.lanes.length - 1];
    pEncrypted.setReady(true);
    pDecrypted.clear('Encrypt first, then Decrypt');
    app.encSeed = seed;
    lockDecrypt(false);
  } catch (e) {
    status('Error: ' + e.message);
  } finally {
    setBusy(false);
  }
}

function raceSummary(results) {
  const single = results.find(r => r.method === 'single');
  const rows = [...results].sort((a, b) => a.totalMs - b.totalMs).map((r, i) => {
    const sp = single ? single.totalMs / r.totalMs : null;
    const spTxt = sp == null ? '–' : r.method === 'single' ? 'baseline'
      : sp >= 1 ? `<span class="faster">${sp.toFixed(2)}× faster</span>` : `<span class="slower">${(1 / sp).toFixed(1)}× slower</span>`;
    return `<tr><td>${i + 1}</td><td><i class="dot ${r.method}"></i>${METHODS[r.method].label}</td><td>${fmtMs(r.totalMs)}</td><td>${fmtInt(r.threads)}</td><td>${spTxt}</td></tr>`;
  }).join('');
  const box = $('#raceSummary');
  box.hidden = false;
  box.innerHTML = `<h3>Result · ${results[0].n} × ${results[0].n} image · ${app.cores} CPU cores</h3>
    <table class="tbl"><thead><tr><th>#</th><th>Method</th><th>Time</th><th>Threads</th><th>vs single</th></tr></thead><tbody>${rows}</tbody></table>`;
}

/* ================================================================ BENCHMARK */

async function startBench() {
  if (app.busy) return;
  const sizes = $$('#benchSizes input:checked').map(i => i.value);
  const methods = $$('#benchMethods input:checked').map(i => i.value);
  if (!sizes.length || !methods.length) { $('#benchStatus').textContent = 'Pick at least one size and one method.'; return; }
  setBusy(true);
  $('#benchProg').classList.add('on');
  app.benchResults = [];
  renderBench();
  try {
    await api(`/api/bench?sizes=${sizes.join(',')}&methods=${methods.join(',')}`, { method: 'POST' });
    for (;;) {
      await sleep(400);
      const b = await api('/api/bench');
      app.benchResults = b.results;
      $('#benchStatus').textContent = b.status;
      renderBench();
      if (b.finished) {
        if (b.error) throw new Error(b.error);
        break;
      }
    }
    $('#benchStatus').textContent = `Done · ${app.benchResults.length} measurements`;
    $('#btnCsv').dataset.locked = '0';
  } catch (e) {
    $('#benchStatus').textContent = 'Error: ' + e.message;
  } finally {
    $('#benchProg').classList.remove('on');
    setBusy(false);
  }
}

function renderBench() {
  const R = app.benchResults;
  if (!R.length) {
    $('#benchTable').innerHTML = '<p class="muted">No results yet.</p>';
    $('#chart').innerHTML = '<p class="muted">Results will appear here.</p>';
    $('#observations').innerHTML = '<p class="muted">Written automatically from your measured results.</p>';
    return;
  }
  // table
  let lastN = null;
  const rows = R.map(r => {
    const single = R.find(x => x.n === r.n && x.method === 'single');
    let vs = '–';
    if (r.skipped) vs = '<span class="muted">skipped</span>';
    else if (r.method === 'single') vs = 'baseline';
    else if (single) {
      const sp = single.ms / r.ms;
      vs = sp >= 1 ? `<span class="faster">${sp.toFixed(2)}× faster</span>` : `<span class="slower">${(1 / sp).toFixed(1)}× slower</span>`;
    }
    const cls = lastN !== null && lastN !== r.n ? ' class="group-start"' : '';
    lastN = r.n;
    return `<tr${cls}><td>${r.n}</td><td><i class="dot ${r.method}"></i>${METHODS[r.method].short}</td>
      <td>${r.skipped ? '–' : fmtMs(r.ms)}</td><td>${fmtInt(r.threads)}</td><td>${vs}</td>
      <td>${r.skipped ? '' : r.correct ? '<span class="ok-text">✓</span>' : '<span class="bad-text">✗</span>'}</td></tr>`;
  }).join('');
  $('#benchTable').innerHTML = `<table class="tbl"><thead><tr><th>n</th><th>Method</th><th>Time</th><th>Threads</th><th>vs single</th><th>OK</th></tr></thead><tbody>${rows}</tbody></table>`;
  renderChart();
  renderObservations();
}

function renderChart() {
  const R = app.benchResults.filter(r => !r.skipped && r.ms > 0);
  if (!R.length) return;
  const log = $('#logToggle').checked;
  const sizes = [...new Set(app.benchResults.map(r => r.n))].sort((a, b) => a - b);
  const W = 680, H = 380, ml = 70, mr = 20, mt = 30, mb = 46;
  const pw = W - ml - mr, ph = H - mt - mb;
  const x = n => ml + (sizes.length === 1 ? pw / 2 : sizes.indexOf(n) / (sizes.length - 1) * pw);
  const vals = R.map(r => r.ms);
  let y, ticks;
  if (log) {
    const lo = Math.pow(10, Math.floor(Math.log10(Math.min(...vals))));
    const hi = Math.pow(10, Math.ceil(Math.log10(Math.max(...vals))));
    const a = Math.log10(lo), b = Math.log10(Math.max(hi, lo * 10));
    y = v => mt + ph - (Math.log10(v) - a) / (b - a) * ph;
    ticks = [];
    for (let e = a; e <= b; e++) ticks.push(Math.pow(10, e));
  } else {
    const max = Math.max(...vals);
    const step = Math.pow(10, Math.floor(Math.log10(max))) * (max / Math.pow(10, Math.floor(Math.log10(max))) > 5 ? 2 : max / Math.pow(10, Math.floor(Math.log10(max))) > 2 ? 1 : 0.5);
    const top = Math.ceil(max / step) * step;
    y = v => mt + ph - v / top * ph;
    ticks = [];
    for (let t = 0; t <= top + 1e-9; t += step) ticks.push(t);
  }
  let s = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Time versus matrix size">`;
  for (const t of ticks) {
    s += `<line class="grid" x1="${ml}" x2="${W - mr}" y1="${y(t)}" y2="${y(t)}"/>`;
    s += `<text x="${ml - 8}" y="${y(t) + 4}" text-anchor="end">${fmtMs(t).replace(' ', '')}</text>`;
  }
  s += `<line class="axis" x1="${ml}" x2="${W - mr}" y1="${mt + ph}" y2="${mt + ph}"/>`;
  for (const n of sizes) s += `<text x="${x(n)}" y="${mt + ph + 18}" text-anchor="middle">${n}</text>`;
  s += `<text x="${ml + pw / 2}" y="${H - 6}" text-anchor="middle">matrix size n (n × n)</text>`;
  s += `<text x="16" y="${mt + ph / 2}" text-anchor="middle" transform="rotate(-90 16 ${mt + ph / 2})">time${log ? ' (log scale)' : ''}</text>`;
  let lx = ml + 6;
  for (const m of ORDER) {
    const pts = R.filter(r => r.method === m).sort((a, b) => a.n - b.n);
    if (!pts.length) continue;
    const col = methodColor(m);
    s += `<polyline fill="none" stroke="${col}" stroke-width="2.5" stroke-linejoin="round" points="${pts.map(p => `${x(p.n)},${y(p.ms)}`).join(' ')}"/>`;
    for (const p of pts) s += `<circle cx="${x(p.n)}" cy="${y(p.ms)}" r="4.5" fill="${col}"><title>${METHODS[m].label} · n=${p.n} · ${fmtMs(p.ms)}</title></circle>`;
    s += `<circle cx="${lx + 5}" cy="${mt - 14}" r="5" fill="${col}"/><text class="lbl" x="${lx + 14}" y="${mt - 10}">${METHODS[m].short}</text>`;
    lx += 26 + METHODS[m].short.length * 7.2;
  }
  $('#chart').innerHTML = s + '</svg>';
}

function renderObservations() {
  const R = app.benchResults.filter(r => !r.skipped);
  const get = (n, m) => R.find(r => r.n === n && r.method === m);
  const sizes = [...new Set(R.map(r => r.n))].sort((a, b) => a - b);
  const obs = [];
  const cores = app.cores;
  const big = sizes[sizes.length - 1];

  // O(n^3) growth
  for (let i = sizes.length - 1; i > 0; i--) {
    const a = get(sizes[i - 1], 'single'), b = get(sizes[i], 'single');
    if (a && b && sizes[i] === sizes[i - 1] * 2 && a.ms > 0.5) {
      obs.push(`Doubling n from ${sizes[i - 1]} to ${sizes[i]} made single-threaded <b>${(b.ms / a.ms).toFixed(1)}×</b> slower. For O(n³) we expect 2³ = 8× (cache effects make it vary a little).`);
      break;
    }
  }
  // best at the largest size
  const atBig = ORDER.map(m => get(big, m)).filter(Boolean).sort((a, b) => a.ms - b.ms);
  const sBig = get(big, 'single');
  if (atBig.length > 1) {
    const w = atBig[0];
    obs.push(`At n = ${big}, the fastest was <b>${METHODS[w.method].label}</b> (${fmtMs(w.ms)})${sBig && w.method !== 'single' ? `, ${(sBig.ms / w.ms).toFixed(2)}× faster than single-threaded` : ''}.`);
  }
  // pool
  const poolBest = sizes.map(n => [n, get(n, 'pool'), get(n, 'single')]).filter(([, p, s]) => p && s)
    .map(([n, p, s]) => ({ n, sp: s.ms / p.ms })).sort((a, b) => b.sp - a.sp)[0];
  if (poolBest) obs.push(`The thread pool reached a best speed-up of <b>${poolBest.sp.toFixed(2)}×</b> (n = ${poolBest.n}). The theoretical maximum on this machine is ${cores}× because there are ${cores} CPU cores.`);
  // row
  const rowWins = sizes.filter(n => get(n, 'row') && get(n, 'single') && get(n, 'row').ms < get(n, 'single').ms);
  if (sizes.some(n => get(n, 'row') && get(n, 'single'))) {
    if (rowWins.length) obs.push(`Thread per row first beat single-threaded at n = <b>${rowWins[0]}</b>. Below that, the time to create n threads was larger than the time saved by running rows in parallel.`);
    else obs.push(`Thread per row was <b>never faster</b> than single-threaded here (up to n = ${big}). With ${cores} cores, creating n threads cost more than the parallel speed-up saved. Each row needs more work (bigger n) or the machine needs more cores for it to pay off.`);
  }
  // cell
  const cells = R.filter(r => r.method === 'cell').sort((a, b) => b.n - a.n);
  if (cells.length) {
    const c = cells[0], s = get(c.n, 'single');
    obs.push(`Thread per cell created <b>${fmtInt(c.threads)}</b> threads at n = ${c.n} and took ${fmtMs(c.ms)}${s ? ` — <b>${(c.ms / s.ms).toFixed(0)}× slower</b> than single-threaded` : ''}. Each thread does only n = ${c.n} multiplications, so nearly all the time goes into creating and scheduling threads.`);
  }
  if (app.benchResults.some(r => r.skipped)) obs.push('Thread per cell was skipped above 512 because it would need more than a million threads.');
  if (R.length) obs.push(R.every(r => r.correct) ? 'All methods produced <b>exactly the same</b> result matrix, so the parallel versions are correct.' : '<span class="bad-text">Some methods produced different results — check the code.</span>');
  $('#observations').innerHTML = `<ul>${obs.map(o => `<li>${o}</li>`).join('')}</ul>`;
}

function downloadCsv() {
  const lines = ['n,method,time_ms,threads,correct'];
  for (const r of app.benchResults) lines.push([r.n, r.method, r.skipped ? '' : r.ms, r.threads, r.correct].join(','));
  const blob = new Blob([lines.join('\n') + '\n'], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'matrixcrypt-benchmark.csv';
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

/* ================================================================ wiring */

function wire() {
  $$('.tab-btn').forEach(b => b.addEventListener('click', () => {
    $$('.tab-btn').forEach(x => x.classList.toggle('active', x === b));
    $$('.tab').forEach(t => t.classList.toggle('active', t.id === 'tab-' + b.dataset.tab));
  }));

  // buttons that must be disabled while something runs
  ['#btnSample', '#btnEncrypt', '#btnDecrypt', '#btnWrong', '#sizeSel', '#btnFilter', '#btnRace', '#btnBench']
    .forEach(s => { $(s).dataset.action = '1'; });
  $('#btnCsv').dataset.action = '1';
  $('#btnCsv').dataset.locked = '1';

  $('#fileInput').addEventListener('change', e => {
    const f = e.target.files[0];
    if (f) setImage(`n=${$('#sizeSel').value}`, f);
    e.target.value = '';
  });
  $('#btnSample').onclick = () => setImage(`n=${$('#sizeSel').value}&sample=1`);
  $('#sizeSel').onchange = () => setImage(`n=${$('#sizeSel').value}&reuse=1`);
  $('#methodSel').onchange = updateMethodHint;
  $('#btnEncrypt').onclick = encrypt;
  $('#btnDecrypt').onclick = () => decrypt(false);
  $('#btnWrong').onclick = () => decrypt(true);
  $('#mapToggle').onchange = () => {
    pEncrypted.drawMap($('#mapToggle').checked);
    pDecrypted.drawMap($('#mapToggle').checked);
    updateLegend();
  };
  const sl = (s, l) => { const f = () => { $(l).textContent = $(s).value + ' s'; }; $(s).oninput = f; f(); };
  sl('#replaySlider', '#replayLabel');
  sl('#fReplaySlider', '#fReplayLabel');
  sl('#raceSlider', '#raceLabel');

  attachCipherInspector(pEncrypted, 'encrypted', pOriginal);
  attachCipherInspector(pDecrypted, 'decrypted', pEncrypted);
  attachFilterInspector();

  $('#btnFilter').onclick = () => applyFilter({ animate: true });
  $('#fMethodSel').onchange = () => { const h = methodHint($('#fMethodSel').value, app.n); fStatus(h.text); };
  $('#fMapToggle').onchange = () => pFiltered.drawMap($('#fMapToggle').checked);

  $('#btnRace').onclick = startRace;
  $('#raceCell').onchange = raceHint;

  $('#btnBench').onclick = startBench;
  $('#btnCsv').onclick = downloadCsv;
  $('#logToggle').onchange = renderChart;
}

async function init() {
  wire();
  buildLanes();
  try {
    const s = await api('/api/state');
    app.n = s.n;
    app.cores = s.cores;
    app.sample = s.sample;
    $('#chipCores').innerHTML = `CPU cores: <b>${s.cores}</b>`;
    $('#chipJava').innerHTML = `Java <b>${esc(s.java)}</b>`;
    $('#sizeSel').value = String(s.n);
    await refreshOriginal();
    await loadFilters();
    labStatus('Ready · press Encrypt');
  } catch (e) {
    labStatus('Cannot reach the Java server: ' + e.message + ' — is ./run.sh running?');
  }
}

init();
