'use strict';
// A Google-bejelentkezés a háttérben fut: a bővítmény kis ablaka (popup) bezárul, amikor a Google
// bejelentkező ablaka megnyílik, ezért ott a bejelentkezés eredménye elveszne.
importScripts('config.js');

const SCOPE = 'https://www.googleapis.com/auth/drive.appdata';

function authUrl(interactive) {
  const p = new URLSearchParams({
    client_id: self.REFI_CONFIG.clientId,
    response_type: 'token',
    redirect_uri: chrome.identity.getRedirectURL(),
    scope: SCOPE,
    include_granted_scopes: 'true',
    // Bejelentkezéskor fiókválasztó (a családban több fiók is lehet); csendes megújításnál nincs ablak
    prompt: interactive ? 'select_account' : 'none',
  });
  return 'https://accounts.google.com/o/oauth2/v2/auth?' + p;
}

async function signIn(interactive) {
  const redirect = await chrome.identity.launchWebAuthFlow({ url: authUrl(interactive), interactive });
  const params = new URLSearchParams(new URL(redirect).hash.slice(1));
  const token = params.get('access_token');
  if (!token) throw new Error(params.get('error') || 'nincs hozzáférés');
  const expires = Date.now() + (Number(params.get('expires_in') || 3600) - 60) * 1000;
  await chrome.storage.local.set({ token, expires, authError: null });
  return token;
}

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (!msg || msg.type !== 'signin') return false;
  signIn(!!msg.interactive)
    .then((token) => reply({ token }))
    .catch(async (e) => {
      const error = String(e && e.message || e);
      if (msg.interactive) await chrome.storage.local.set({ authError: error });
      reply({ error });
    });
  return true; // válasz később
});
