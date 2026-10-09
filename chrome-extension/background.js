'use strict';
// A Google-bejelentkezés a háttérben fut: a bővítmény kis ablaka (popup) bezárul, amikor a Google
// bejelentkező ablaka megnyílik, ezért ott a bejelentkezés eredménye elveszne.
importScripts('config.js');

const SCOPE = 'https://www.googleapis.com/auth/drive.appdata';
let generation = 0;   // kijelentkezéskor nő: egy közben futó bejelentkezés eredménye eldobandó
let pending = null;   // egyszerre csak egy csendes bejelentkezés fusson

function authUrl(interactive, hint, state) {
  const p = new URLSearchParams({
    client_id: self.REFI_CONFIG.clientId,
    response_type: 'token',
    redirect_uri: chrome.identity.getRedirectURL(),
    scope: SCOPE,
    include_granted_scopes: 'true',
    state,
    // Bejelentkezéskor fiókválasztó (a családban több fiók is lehet); csendes megújításnál nincs ablak
    prompt: interactive ? 'select_account' : 'none',
  });
  // Csendes megújításnál a korábban választott fiók (több bejelentkezett fióknál is a jót kapjuk)
  if (!interactive && hint) p.set('login_hint', hint);
  return 'https://accounts.google.com/o/oauth2/v2/auth?' + p;
}

async function accountEmail(token) {
  try {
    const r = await fetch('https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)', {
      headers: { Authorization: 'Bearer ' + token },
    });
    if (!r.ok) return null;
    const j = await r.json();
    return (j.user && j.user.emailAddress) || null;
  } catch { return null; }
}

async function signIn(interactive) {
  const gen = generation;
  const { account, signedOut } = await chrome.storage.local.get(['account', 'signedOut']);
  // Kijelentkezés után csendben nem lépünk vissza – csak ha a felhasználó újra bejelentkezik
  if (!interactive && signedOut) throw new Error('kijelentkezve');
  const state = crypto.randomUUID();
  const redirect = await chrome.identity.launchWebAuthFlow({ url: authUrl(interactive, account, state), interactive });
  const params = new URLSearchParams(new URL(redirect).hash.slice(1));
  if (params.get('state') !== state) throw new Error('érvénytelen válasz a Google-től – próbáld újra');
  const token = params.get('access_token');
  if (!token) throw new Error(params.get('error') || 'nincs hozzáférés');
  if (!(params.get('scope') || SCOPE).includes('drive.appdata')) {
    throw new Error('A bejelentkezéskor nem kaptunk engedélyt a REFI adataihoz – jelöld be a jelölőnégyzetet a Google ablakában.');
  }
  // Közben kijelentkezett: ezt a (csendes) eredményt eldobjuk
  if (!interactive && gen !== generation) throw new Error('kijelentkezve');
  const expires = Date.now() + (Number(params.get('expires_in') || 3600) - 60) * 1000;
  const email = await accountEmail(token);
  const prev = await chrome.storage.local.get(['account']);
  const update = { token, expires, account: email, signedOut: false, authError: null };
  // Más fiókkal lépett be: a korábbi fiók gyorsítótárazott figyelései nem látszhatnak
  if (!email || email !== prev.account) await chrome.storage.local.remove('data');
  await chrome.storage.local.set(update);
  return token;
}

/**
 * Kijelentkezés: csak itt, a böngészőben. A hozzáférést szándékosan NEM vonjuk vissza a Google-nél,
 * mert az a REFI többi eszközén (számítógép, telefon) is kijelentkeztetne.
 */
async function signOut() {
  generation++;
  await chrome.storage.local.remove(['token', 'expires', 'data', 'account', 'authError']);
  await chrome.storage.local.set({ signedOut: true });
}

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (!msg) return false;
  if (msg.type === 'signout') {
    signOut().then(() => reply({ ok: true }), () => reply({ ok: true }));
    return true;
  }
  if (msg.type !== 'signin') return false;
  const interactive = !!msg.interactive;
  const run = !interactive && pending ? pending : signIn(interactive);
  if (!interactive) pending = run;
  run
    .then((token) => reply({ token }))
    .catch(async (e) => {
      const error = String((e && e.message) || e);
      if (interactive) await chrome.storage.local.set({ authError: error });
      reply({ error });
    })
    .finally(() => { if (pending === run) pending = null; });
  return true; // válasz később
});

// ------------------------------------------------------------ frissítés
// A bővítmény egy mappából fut: ha oda új verzió kerül, magától újratöltődik (nem kell ↻-t nyomni).
// Csak ha ugyanazt az új verziót legalább egy perce látjuk (a másolás biztosan befejeződött), és
// épp nem fut bejelentkezés.
async function checkForNewFiles() {
  try {
    const r = await fetch(chrome.runtime.getURL('manifest.json') + '?t=' + Date.now(), { cache: 'no-store' });
    const onDisk = (await r.json()).version;
    const running = chrome.runtime.getManifest().version;
    if (!onDisk || onDisk === running) {
      await chrome.storage.local.remove('seenVersion');
      return;
    }
    const { seenVersion } = await chrome.storage.local.get('seenVersion');
    if (!seenVersion || seenVersion.v !== onDisk) {
      await chrome.storage.local.set({ seenVersion: { v: onDisk, at: Date.now() } });
      return;
    }
    if (Date.now() - seenVersion.at >= 50000 && !pending) chrome.runtime.reload();
  } catch { /* a fájl épp íródik – a következő körben újra */ }
}

chrome.alarms.get('refi-update-check').then((a) => {
  if (!a) chrome.alarms.create('refi-update-check', { periodInMinutes: 1 });
});
chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'refi-update-check') checkForNewFiles(); });
chrome.runtime.onStartup.addListener(checkForNewFiles);
