'use strict';

// REFI Chrome-bővítmény: a figyelések gyors áttekintése. Csak olvas: a REFI app által a Google Drive
// rejtett „alkalmazásadat” területére mentett refi-sync.json fájlt tölti le, és megjeleníti.

const SCOPE = 'https://www.googleapis.com/auth/drive.appdata';
const FILE_NAME = 'refi-sync.json';
const hasChrome = typeof chrome !== 'undefined' && !!chrome.storage;
const demo = location.search.includes('demo');

const $ = (id) => document.getElementById(id);
const show = (id, on = true) => { $(id).hidden = !on; };

// ------------------------------------------------------------ tárolás (token és utolsó adat)

const store = {
  async get(key) {
    if (!hasChrome) return null;
    const r = await chrome.storage.local.get(key);
    return r[key] ?? null;
  },
  async set(obj) { if (hasChrome) await chrome.storage.local.set(obj); },
  async remove(keys) { if (hasChrome) await chrome.storage.local.remove(keys); },
};

// ------------------------------------------------------------ Google-bejelentkezés

// A bejelentkezést a háttér-szkript végzi (background.js), mert ez az ablak bezárulhat közben
async function signIn(interactive) {
  const r = await chrome.runtime.sendMessage({ type: 'signin', interactive });
  if (!r || !r.token) throw new Error(friendlyAuthError((r && r.error) || 'nincs hozzáférés'));
  return r.token;
}

/** A Chrome/Google angol hibaüzenetei helyett érthető szöveg. */
function friendlyAuthError(msg) {
  if (/did not approve|canceled|cancelled|closed/i.test(msg)) return 'A bejelentkezés megszakadt (bezárult a Google ablaka). Próbáld újra.';
  if (/redirect_uri_mismatch/i.test(msg)) return 'A Google még nem ismeri fel a bővítményt (beállítás alatt) – próbáld újra néhány perc múlva.';
  if (/access_denied/i.test(msg)) return 'A Google-fiók nem engedte a hozzáférést.';
  return msg;
}

async function validToken() {
  const token = await store.get('token');
  const expires = await store.get('expires');
  if (token && expires && Date.now() < expires) return token;
  try { return await signIn(false); } catch { return null; }
}

// ------------------------------------------------------------ Drive

async function drive(url, token) {
  const r = await fetch(url, { headers: { Authorization: 'Bearer ' + token } });
  if (r.status === 401) { await store.remove(['token', 'expires']); throw Object.assign(new Error('auth'), { auth: true }); }
  if (!r.ok) throw new Error('Google Drive HTTP ' + r.status);
  return r;
}

async function loadFile(token) {
  const q = encodeURIComponent(`name='${FILE_NAME}'`);
  const list = await (await drive(
    `https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=${q}&fields=files(id)&orderBy=createdTime&pageSize=1`, token,
  )).json();
  const id = list.files && list.files[0] && list.files[0].id;
  if (!id) return { watches: [], currency: 'HUF', updatedAt: null };
  const data = await (await drive(`https://www.googleapis.com/drive/v3/files/${id}?alt=media`, token)).json();
  if (data.format !== 'refi-sync' || (data.version || 1) > 1) throw new Error('Ismeretlen adatformátum – frissítsd a bővítményt.');
  // A kulcsokat (SerpApi, Ignav) nem tároljuk el és nem mutatjuk
  return { watches: data.watches || [], currency: data.currency || 'HUF', updatedAt: data.updatedAt || null };
}

// ------------------------------------------------------------ megjelenítés

const fmtMonth = new Intl.DateTimeFormat('hu-HU', { month: 'short', day: 'numeric' });
const fmtTime = new Intl.DateTimeFormat('hu-HU', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

function money(v, cur) {
  try {
    return new Intl.NumberFormat('hu-HU', { style: 'currency', currency: cur, maximumFractionDigits: 0 }).format(v);
  } catch { return `${v} ${cur}`; }
}

function day(iso) {
  const d = new Date(iso + 'T12:00:00');
  return isNaN(d) ? iso : fmtMonth.format(d);
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function wantsBags(w) { return (w.bags || 0) > 0 || !!w.checkedBag; }
function comparable(w, o) { return !(wantsBags(w) && o.bagsIncluded === false) && !o.partial; }

function expired(w) {
  const last = new Date(w.outboundDate + 'T00:00:00');
  last.setDate(last.getDate() + (w.flexDays || 0) + 1);
  return last < new Date();
}

/** [időbélyeg, ár] párok → érvényes, időrendbe rendezett pontok (hibás elemek nélkül). */
function points(arr) {
  return (Array.isArray(arr) ? arr : [])
    .map((p) => ({ t: Number(p && p[0]), v: Number(p && p[1]) }))
    .filter((p) => Number.isFinite(p.t) && Number.isFinite(p.v) && p.v > 0)
    .sort((a, b) => a.t - b.t);
}

function sparkline(w) {
  const pts = points(w.history);
  // Poggyász nélküli figyelésnél a Google 2 hetes árelőzménye is (halványan), mint az appban
  const first = pts.length ? pts[0].t : Date.now();
  const market = points(!wantsBags(w) && w.market ? w.market.points : [])
    .filter((p) => p.t < first - 43200000 && p.t >= first - 14 * 86400000);
  const all = market.concat(pts);
  if (all.length < 2) return null;
  const W = 340, H = 34, pad = 3;
  const vs = all.map((p) => p.v).concat(Number.isFinite(w.targetPrice) ? [w.targetPrice] : []);
  const lo = Math.min(...vs), hi = Math.max(...vs), span = (hi - lo) || 1;
  const t0 = all[0].t, t1 = all[all.length - 1].t || t0 + 1;
  const x = (t) => ((t - t0) / ((t1 - t0) || 1)) * W;
  const y = (v) => pad + (1 - (v - lo) / span) * (H - 2 * pad);
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('preserveAspectRatio', 'none');
  svg.classList.add('spark');
  const line = (points, color, width, dash) => {
    if (points.length < 2) return;
    const p = document.createElementNS(ns, 'polyline');
    p.setAttribute('points', points.map((q) => `${x(q.t).toFixed(1)},${y(q.v).toFixed(1)}`).join(' '));
    p.setAttribute('fill', 'none');
    p.setAttribute('stroke', color);
    p.setAttribute('stroke-width', width);
    p.setAttribute('vector-effect', 'non-scaling-stroke');
    p.setAttribute('stroke-linejoin', 'round');
    if (dash) p.setAttribute('stroke-dasharray', dash);
    svg.appendChild(p);
  };
  const ty = y(w.targetPrice);
  const t = document.createElementNS(ns, 'line');
  Object.entries({ x1: 0, x2: W, y1: ty, y2: ty, stroke: '#F2788A', 'stroke-opacity': '.7', 'stroke-width': 1, 'stroke-dasharray': '5 4', 'vector-effect': 'non-scaling-stroke' })
    .forEach(([k, v]) => t.setAttribute(k, v));
  svg.appendChild(t);
  line(market.concat(pts.slice(0, 1)), '#8D9DB0', 1.5);
  line(pts, '#5EA8F2', 2);
  return svg;
}

function card(w, cur) {
  const li = el('li', 'card');
  const best = (w.offers || [])[0];
  const price = w.lastPrice ?? (best && best.price);
  const ok = best && comparable(w, best) && best.price <= w.targetPrice;
  const off = expired(w);
  if (ok && !off) li.classList.add('good');
  if (off) li.classList.add('off');

  const top = el('div', 'row');
  top.append(
    el('span', 'route', `${w.fromLabel || w.from} → ${w.toLabel || w.to}`),
    el('span', 'price', price != null ? money(price, cur) : '—'),
  );
  li.append(top);

  const pax = (w.adults || 1) + (w.children || 0) + (w.infantsInSeat || 0) + (w.infantsOnLap || 0);
  const dates = w.returnDate ? `${day(w.outboundDate)} – ${day(w.returnDate)}` : `${day(w.outboundDate)} · csak oda`;
  const second = el('div', 'row');
  second.append(
    el('span', 'meta', `${dates}${w.flexDays ? ` (±${w.flexDays} nap)` : ''} · ${pax} fő`),
    el('span', 'target', `célár: ${money(w.targetPrice, cur)}`),
  );
  li.append(second);

  if (off) li.append(el('div', 'badge warn', 'AZ INDULÁS ELMÚLT'));
  else if (ok) li.append(el('div', 'badge', '▼ CÉLÁR ALATT'));
  else if (w.lastError && price == null) li.append(el('div', 'badge warn', w.lastError.slice(0, 80)));

  const s = sparkline(w);
  if (s) li.append(s);

  const bottom = el('div', 'bottom');
  bottom.append(el('span', 'meta', w.lastChecked ? `ellenőrizve: ${fmtTime.format(new Date(w.lastChecked))}` : 'még nem volt ellenőrzés'));
  if (best && typeof best.url === 'string' && best.url.startsWith('https://')) {
    const a = el('a', null, 'Megnyitás ›');
    a.href = best.url;
    a.target = '_blank';
    a.rel = 'noopener';
    bottom.append(a);
  }
  li.append(bottom);
  return li;
}

function render(data) {
  const list = $('list');
  list.replaceChildren();
  const watches = [...data.watches].sort((a, b) => expired(a) - expired(b) || String(a.outboundDate).localeCompare(String(b.outboundDate)));
  show('empty', watches.length === 0);
  watches.forEach((w) => list.append(card(w, data.currency)));
  const f = $('footer');
  f.textContent = data.updatedAt
    ? `Utolsó szinkron: ${fmtTime.format(new Date(data.updatedAt))} · az árakat a REFI app ellenőrzi`
    : 'Az árakat a REFI app ellenőrzi (telefonon vagy számítógépen).';
  show('footer');
}

// ------------------------------------------------------------ indulás

let running = null;

/** Betöltés (egyszerre csak egy fut; ha közben újat kérnek, a futót várjuk meg). */
let stickyError = false;
let epoch = 0; // kijelentkezéskor nő: a közben befejeződő betöltés eredményét eldobjuk

function refresh(interactive) {
  if (running && !interactive) return running;
  const p = doRefresh(interactive).finally(() => { if (running === p) running = null; });
  running = p;
  return p;
}

async function doRefresh(interactive) {
  const myEpoch = epoch;
  // A bejelentkezési hiba üzenete addig marad, amíg újra meg nem próbálja
  if (interactive) stickyError = false;
  if (!stickyError) show('error', false);
  const btn = $('refresh');
  btn.classList.add('spin');
  try {
    let token;
    if (interactive) {
      // Bejelentkezés közben az ablak bezárulhat; az eredményt a háttér elmenti
      $('signin-btn').disabled = true;
      $('signin-btn').textContent = 'Bejelentkezés folyamatban…';
      try { token = await signIn(true); } finally {
        $('signin-btn').disabled = false;
        $('signin-btn').textContent = 'Bejelentkezés Google-fiókkal';
      }
    } else {
      token = await validToken();
    }
    if (!token) {
      show('loading', false);
      // Nincs (érvényes) bejelentkezés: a régi, esetleg más fiókhoz tartozó adatot nem mutatjuk
      $('list').replaceChildren();
      ['refresh', 'signout', 'footer', 'empty'].forEach((id) => show(id, false));
      show('signin');
      return;
    }
    show('signin', false);
    let data;
    try {
      data = await loadFile(token);
    } catch (e) {
      if (!e.auth) throw e;
      token = await validToken();
      if (!token) {
        $('list').replaceChildren();
        ['refresh', 'signout', 'footer', 'empty'].forEach((id) => show(id, false));
        show('signin');
        return;
      }
      data = await loadFile(token);
    }
    if (myEpoch !== epoch) return; // közben kijelentkezett
    await store.set({ data });
    show('loading', false);
    show('refresh'); show('signout');
    render(data);
  } catch (e) {
    show('loading', false);
    $('error').textContent = interactive ? e.message : `Nem sikerült betölteni: ${e.message}`;
    show('error');
  } finally {
    btn.classList.remove('spin');
  }
}

async function start() {
  // Ha a bejelentkezés a háttérben befejeződik, amíg ez az ablak nyitva van, frissítünk
  if (hasChrome) {
    chrome.storage.onChanged.addListener((changes) => {
      if (changes.token && changes.token.newValue && !running) refresh(false);
      if (changes.authError && changes.authError.newValue) {
        $('error').textContent = `A bejelentkezés nem sikerült: ${changes.authError.newValue}`;
        show('error');
      }
    });
  }
  $('signin-btn').addEventListener('click', () => refresh(true));
  $('refresh').addEventListener('click', () => refresh(false));
  $('signout').addEventListener('click', async () => {
    // A hozzáférést a Google-nél is visszavonjuk, különben a következő megnyitáskor csendben visszalépne
    epoch++;
    await chrome.runtime.sendMessage({ type: 'signout' });
    $('list').replaceChildren();
    ['refresh', 'signout', 'footer', 'empty', 'error'].forEach((id) => show(id, false));
    show('signin');
  });

  // Ha a mappába újabb verzió került, mint ami most fut, újratöltjük a bővítményt
  if (hasChrome && !demo) {
    try {
      const onDisk = (await (await fetch('manifest.json?t=' + Date.now(), { cache: 'no-store' })).json()).version;
      if (onDisk && onDisk !== chrome.runtime.getManifest().version) { chrome.runtime.reload(); return; }
    } catch { /* nem baj */ }
  }

  if (demo) {
    show('refresh'); show('signout');
    render(window.REFI_DEMO);
    return;
  }
  // Egy korábbi (bezárt ablak alatti) bejelentkezési hiba üzenete
  const authError = await store.get('authError');
  if (authError && authError !== 'kijelentkezve') {
    $('error').textContent = friendlyAuthError(authError);
    show('error');
    stickyError = true;
    await store.remove(['authError']);
  }
  // Az utolsó ismert állapot azonnal látszik, közben frissítünk
  const cached = await store.get('data');
  if (cached) { render(cached); show('refresh'); show('signout'); } else { show('loading'); }
  refresh(false);
}

start();
