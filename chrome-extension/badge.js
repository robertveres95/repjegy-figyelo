'use strict';
// Nyelv: magyar böngészőben magyar, német böngészőben német, máshol angol (a popup, a demó és a háttér is ezt használja)
const REFI_LANG = (() => {
  const l = (typeof navigator !== 'undefined' && navigator.language || '').toLowerCase();
  return l.startsWith('hu') ? 'hu' : l.startsWith('de') ? 'de' : 'en';
})();
/** Szöveg a böngésző nyelvén (német hiányában angolul). */
function t(hu, en, de) { return REFI_LANG === 'hu' ? hu : REFI_LANG === 'de' ? (de ?? en) : en; }

// Jelvény a bővítmény ikonján: hány figyelés ára van most a célár alatt (a REFI app szabálya
// szerint: poggyásszal csak a poggyászdíjat is tartalmazó, nem hiányos ár számít).

/** Az utolsó lehetséges indulás napjának vége (Date) – mint az appban: ±napok vagy „minden héten”. */
function refiLastDay(w) {
  const first = new Date(w.outboundDate + 'T00:00:00');
  const last = new Date(first);
  if (w.weeklyUntil) {
    // Az első indulással azonos hétköznapra eső, a határnál nem későbbi nap (legfeljebb 9 hét)
    const until = new Date(w.weeklyUntil + 'T00:00:00');
    const weeks = Math.min(8, Math.max(0, Math.floor(Math.round((until - first) / 86400000) / 7)));
    last.setDate(last.getDate() + weeks * 7);
  } else {
    last.setDate(last.getDate() + (w.flexDays || 0));
  }
  last.setDate(last.getDate() + 1);
  return last;
}

function refiBelowTargetCount(data) {
  if (!data || !Array.isArray(data.watches)) return 0;
  const now = new Date();
  return data.watches.filter((w) => {
    const best = (w.offers || [])[0];
    if (!best || !Number.isFinite(best.price) || !Number.isFinite(w.targetPrice)) return false;
    const bags = (w.bags || 0) > 0 || !!w.checkedBag;
    if ((bags && best.bagsIncluded === false) || best.partial) return false;
    if (!(refiLastDay(w) > now)) return false; // lejárt vagy hibás dátum
    return best.price <= w.targetPrice;
  }).length;
}

async function refiSetBadge(data) {
  if (typeof chrome === 'undefined' || !chrome.action) return;
  const n = refiBelowTargetCount(data);
  try {
    await chrome.action.setBadgeBackgroundColor({ color: '#4CC9B8' });
    if (chrome.action.setBadgeTextColor) await chrome.action.setBadgeTextColor({ color: '#06101C' });
    await chrome.action.setBadgeText({ text: n > 0 ? String(n) : '' });
    await chrome.action.setTitle({
      title: n > 0
        ? t(`REFI – ${n} figyelés célár alatt!`, n === 1 ? 'REFI – 1 watch below target price!' : `REFI – ${n} watches below target price!`,
          n === 1 ? 'REFI – 1 Beobachtung unter dem Zielpreis!' : `REFI – ${n} Beobachtungen unter dem Zielpreis!`)
        : t('REFI – figyelt repjegyek', 'REFI – watched flights', 'REFI – beobachtete Flüge'),
    });
  } catch { /* a jelvény nem létfontosságú */ }
}
