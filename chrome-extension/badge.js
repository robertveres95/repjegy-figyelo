'use strict';
// Jelvény a bővítmény ikonján: hány figyelés ára van most a célár alatt (a REFI app szabálya
// szerint: poggyásszal csak a poggyászdíjat is tartalmazó, nem hiányos ár számít).

function refiBelowTargetCount(data) {
  if (!data || !Array.isArray(data.watches)) return 0;
  const now = new Date();
  return data.watches.filter((w) => {
    const best = (w.offers || [])[0];
    if (!best || !Number.isFinite(best.price) || !Number.isFinite(w.targetPrice)) return false;
    const bags = (w.bags || 0) > 0 || !!w.checkedBag;
    if ((bags && best.bagsIncluded === false) || best.partial) return false;
    const last = new Date(w.outboundDate + 'T00:00:00');
    last.setDate(last.getDate() + (w.flexDays || 0) + 1);
    if (!(last > now)) return false; // lejárt vagy hibás dátum
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
      title: n > 0 ? `REFI – ${n} figyelés célár alatt!` : 'REFI – figyelt repjegyek',
    });
  } catch { /* a jelvény nem létfontosságú */ }
}
