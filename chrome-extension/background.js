'use strict';
// A Google-bejelentkezés a háttérben fut: a bővítmény kis ablaka (popup) bezárul, amikor a Google
// bejelentkező ablaka megnyílik, ezért ott a bejelentkezés eredménye elveszne.
importScripts('config.js');

const SCOPE = 'https://www.googleapis.com/auth/drive.appdata';

function authUrl(interactive, hint) {
  const p = new URLSearchParams({
    client_id: self.REFI_CONFIG.clientId,
    response_type: 'token',
    redirect_uri: chrome.identity.getRedirectURL(),
    scope: SCOPE,
    include_granted_scopes: 'true',
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
  const { account, signedOut } = await chrome.storage.local.get(['account', 'signedOut']);
  // Kijelentkezés után csendben nem lépünk vissza – csak ha a felhasználó újra bejelentkezik
  if (!interactive && signedOut) throw new Error('kijelentkezve');
  const redirect = await chrome.identity.launchWebAuthFlow({ url: authUrl(interactive, account), interactive });
  const params = new URLSearchParams(new URL(redirect).hash.slice(1));
  const token = params.get('access_token');
  if (!token) throw new Error(params.get('error') || 'nincs hozzáférés');
  if (!(params.get('scope') || SCOPE).includes('drive.appdata')) {
    throw new Error('A bejelentkezéskor nem kaptunk engedélyt a REFI adataihoz – jelöld be a jelölőnégyzetet a Google ablakában.');
  }
  const expires = Date.now() + (Number(params.get('expires_in') || 3600) - 60) * 1000;
  const email = (await accountEmail(token)) || account || null;
  await chrome.storage.local.set({ token, expires, account: email, signedOut: false, authError: null });
  return token;
}

/** Kijelentkezés: a hozzáférés visszavonása a Google-nél, és a helyi adatok törlése. */
async function signOut() {
  const { token } = await chrome.storage.local.get('token');
  if (token) {
    try {
      await fetch('https://oauth2.googleapis.com/revoke?token=' + encodeURIComponent(token), {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      });
    } catch { /* nem baj, ha most nem érhető el */ }
  }
  await chrome.storage.local.remove(['token', 'expires', 'data', 'account', 'authError']);
  await chrome.storage.local.set({ signedOut: true });
}

let pending = null; // egyszerre csak egy bejelentkezés fusson

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (!msg) return false;
  if (msg.type === 'signout') {
    signOut().then(() => reply({ ok: true }), () => reply({ ok: true }));
    return true;
  }
  if (msg.type !== 'signin') return false;
  const run = pending && !msg.interactive ? pending : signIn(!!msg.interactive);
  pending = run;
  run
    .then((token) => reply({ token }))
    .catch(async (e) => {
      const error = String((e && e.message) || e);
      if (msg.interactive) await chrome.storage.local.set({ authError: error });
      reply({ error });
    })
    .finally(() => { if (pending === run) pending = null; });
  return true; // válasz később
});

// ------------------------------------------------------------ frissítés
// A bővítmény egy mappából fut: ha oda új verzió kerül, magától újratöltődik (nem kell ↻-t nyomni).
async function checkForNewFiles() {
  try {
    const r = await fetch(chrome.runtime.getURL('manifest.json') + '?t=' + Date.now(), { cache: 'no-store' });
    const onDisk = (await r.json()).version;
    if (onDisk && onDisk !== chrome.runtime.getManifest().version) chrome.runtime.reload();
  } catch { /* a fájl épp íródik – a következő körben újra */ }
}

chrome.alarms.create('refi-update-check', { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'refi-update-check') checkForNewFiles(); });
chrome.runtime.onStartup.addListener(checkForNewFiles);
