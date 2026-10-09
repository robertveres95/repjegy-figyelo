(function () {
  const now = Date.now(), H = 3600000, D = 24 * H;
  const day = (n) => new Date(now + n * D).toISOString().slice(0, 10);
  window.REFI_DEMO = {
    currency: 'HUF', updatedAt: now - 20 * 60000,
    watches: [
      { fromLabel: 'Budapest', toLabel: 'Barcelona', from: 'BUD', to: 'BCN', outboundDate: day(35), returnDate: day(42),
        adults: 2, children: 0, infantsInSeat: 0, infantsOnLap: 0, bags: 0, targetPrice: 70000, flexDays: 1,
        lastPrice: 61200, lastChecked: now - 2 * H,
        offers: [{ price: 61200, source: 'Google Flights', url: 'https://www.google.com/travel/flights', bagsIncluded: true }],
        history: [72000, 70500, 69900, 66100, 64800, 63300, 61200].map((v, i) => [now - (6 - i) * 8 * H, v]),
        market: { points: [78100, 76400, 77900, 75200, 74800, 76900, 73100, 74500, 72800, 73900, 71800, 72600, 71200, 72400].map((v, i) => [now - 2 * D - (14 - i) * D, v]) } },
      { fromLabel: 'Budapest', toLabel: 'London', from: 'BUD', to: 'LTN,STN', outboundDate: day(60), returnDate: day(64),
        adults: 1, children: 0, infantsInSeat: 0, infantsOnLap: 0, bags: 1, targetPrice: 30000,
        lastPrice: 34900, lastChecked: now - 5 * H,
        offers: [{ price: 34900, source: 'Wizz Air', url: 'https://wizzair.com', bagsIncluded: true }],
        history: [36100, 35800, 36900, 35200, 34900].map((v, i) => [now - (4 - i) * 6 * H, v]) },
      { fromLabel: 'Budapest', toLabel: 'Milánó', from: 'BUD', to: 'BGY', outboundDate: day(12), returnDate: null,
        adults: 1, children: 0, infantsInSeat: 0, infantsOnLap: 0, bags: 0, targetPrice: 15000,
        lastPrice: null, lastChecked: null, offers: [], history: [] },
    ],
  };
})();
