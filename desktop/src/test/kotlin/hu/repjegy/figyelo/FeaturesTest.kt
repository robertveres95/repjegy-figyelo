package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Az új funkciók tesztjei (hálózat nélkül). */
class FeaturesTest {
    @BeforeTest
    fun setUp() {
        Platform.current = DesktopPlatform
    }

    private val today = LocalDate.of(2026, 10, 8)

    private fun watch(out: String = "2026-11-12", ret: String? = "2026-11-17", flex: Int = 0) = Watch(
        id = "w1", from = "BUD", to = "LHR,LGW,STN,LTN,LCY,SEN", fromLabel = "Budapest", toLabel = "London",
        outboundDate = out, returnDate = ret, travelClass = 1, adults = 2, children = 1, infantsInSeat = 0,
        infantsOnLap = 1, bags = 2, checkedBag = true, stops = 2, targetPrice = 85000, notify = true,
        flexDays = flex, depFrom = 7, depTo = 20, airlines = "Wizz, Ryanair",
    )

    // ---------------- Rugalmas dátum
    @Test fun flexDatePairsKeepTripLength() {
        val pairs = watch(flex = 2).datePairs(today)
        assertEquals(5, pairs.size)
        assertEquals("2026-11-12" to "2026-11-17", pairs.first(), "a pontos dátum legyen az első")
        assertTrue(pairs.all { (o, r) -> LocalDate.parse(r).toEpochDay() - LocalDate.parse(o).toEpochDay() == 5L })
        assertEquals(setOf("2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13", "2026-11-14"), pairs.map { it.first }.toSet())
    }

    @Test fun flexSkipsPastDates() {
        val pairs = watch(out = "2026-10-09", ret = null, flex = 3).datePairs(today)
        assertEquals(listOf("2026-10-09", "2026-10-08", "2026-10-10", "2026-10-11", "2026-10-12").sorted(), pairs.map { it.first }.sorted())
        assertTrue(pairs.all { it.second == null })
    }

    @Test fun flexExpiry() {
        val w = watch(out = "2026-10-07", ret = null, flex = 1)
        assertFalse(w.isExpired(today), "a tartomány utolsó napja (10.08.) még ma van")
        assertTrue(w.isExpired(today.plusDays(1)))
    }

    @Test fun flexSearchMergesAndToleratesPartialFailure() {
        var calls = 0
        // Egy nap hibázott: a többi nap ajánlatai megmaradnak, de a forrás „részleges” (nem teljes válasz)
        val partial = runCatching { kotlinx.coroutines.runBlocking { PriceChecker.flexSearch(watch(out = LocalDate.now().plusDays(20).toString(), ret = null, flex = 1)) { w ->
            calls++
            if (calls == 2) throw java.io.IOException("egy nap hibázott")
            listOf(Offer(1000 + calls, "x", departure = w.outboundDate + "T10:00"))
        } } }.exceptionOrNull()
        assertEquals(3, calls)
        assertTrue(partial is PartialSourceException)
        assertEquals(2, (partial as PartialSourceException).offers.size)
        assertEquals(1, partial.failed)
    }

    @Test fun whatsNewVersionsAndOrder() {
        assertTrue(WhatsNew.compare("1.3.10", "1.3.9") > 0)
        assertEquals(0, WhatsNew.compare("1.3", "1.3.0"))
        // A legújabb verzió legyen elöl, és mindegyiknek legyen szövege
        val versions = WhatsNew.notes.map { it.first }
        assertEquals(versions.sortedWith { a, b -> WhatsNew.compare(b, a) }, versions)
        assertTrue(WhatsNew.notes.all { it.second.isNotEmpty() })
    }

    @Test fun googleInsightParsing() {
        // A Google válaszának valós szerkezete (rövidítve): [szint, [null, ár], …, [[ms, ár], …]]
        val snippet = JSONArray("""[[null,[1,2]],["x",[2,[null,29160],[null,34094],[null,4934],[null,25500],[null,51000],1,null,null,null,
            [[[1786226400000,30380],[1786312800000,34180],[1786399200000,32001]]],[[1,2,3]],"Barcelona"]]]""")
        val m = assertNotNull(GoogleFlights.findInsight(snippet))
        assertEquals(3, m.points.size)
        assertEquals(30380, m.points.first().price)
        assertEquals(25500, m.typicalLow)
        assertEquals(51000, m.typicalHigh)
        // Mentés és visszaolvasás a figyeléssel együtt
        val w = watch().copy(market = m)
        assertEquals(m.points, Watch.fromJson(w.toJson()).market?.points)
        // Más keresésnél (pl. új dátum) törlődik
        assertNull(w.clearResults().market)
        assertNull(GoogleFlights.findInsight(JSONArray("[[1,2,3],[null,5]]")))
    }

    @Test fun chartShowsTwoWeeksBeforeOwnMeasurements() {
        val day = 86_400_000L
        val start = 1_800_000_000_000L
        val market = (0 until 60).map { PricePoint(start - (60 - it) * day, 1000 + it) }
        val own = listOf(PricePoint(start, 900), PricePoint(start + day / 4, 950))
        val d = chartDataFor(watch().copy(bags = 0, checkedBag = false, history = own, market = MarketInsight(market, null, null, start)), now = start + day)
        assertTrue(d.market.size in 13..14, "${d.market.size}")
        assertTrue(d.market.all { it.time < start && it.time >= start - 14 * day })
        assertEquals(start, d.boundary)
        // Saját mérés nélkül: a mai napig visszamenő 2 hét
        val fresh = chartDataFor(watch().copy(bags = 0, checkedBag = false, market = MarketInsight(market, null, null, start)), now = start)
        assertTrue(fresh.drawable)
        assertTrue(fresh.own.isEmpty())
        // Poggyászos figyelésnél a (poggyász nélküli) Google-előzmény nem jelenik meg
        val withBags = chartDataFor(watch().copy(bags = 1, history = own, market = MarketInsight(market, null, null, start)), now = start + day)
        assertTrue(withBags.market.isEmpty())
    }

    @Test fun collectOffersOutcomes() {
        // Minden sikerül → sima lista
        assertEquals(2, collectOffers(listOf(1, 2)) { listOf(Offer(it, "x")) }.size)
        // Mind hibázik → az első hiba
        val allFail = runCatching { collectOffers(listOf(1, 2)) { throw java.io.IOException("hiba $it") } }.exceptionOrNull()
        assertEquals("hiba 1", allFail?.message)
        // Letiltás az első után → részleges, a maradék is hibásnak számít
        val fatal = runCatching {
            collectOffers(listOf(1, 2, 3)) { if (it == 1) listOf(Offer(1, "x")) else throw FatalSourceException("tiltás") }
        }.exceptionOrNull()
        assertTrue(fatal is PartialSourceException)
        assertEquals(2, (fatal as PartialSourceException).failed)
        // Letiltás rögtön → maga a letiltás
        assertTrue(runCatching { collectOffers(listOf(1, 2)) { throw FatalSourceException("tiltás") } }.exceptionOrNull() is FatalSourceException)
    }

    // ---------------- Szűrők
    @Test fun timeWindowAndAirlineFilter() {
        val w = watch()
        assertTrue(w.matchesFilters(Offer(1, "x", "Wizz Air", departure = "2026-11-12T07:00")))
        assertTrue(w.matchesFilters(Offer(1, "x", "Ryanair", departure = "2026-11-12T20:00")))
        assertFalse(w.matchesFilters(Offer(1, "x", "Wizz Air", departure = "2026-11-12T06:59")))
        assertFalse(w.matchesFilters(Offer(1, "x", "Wizz Air", departure = "2026-11-12T20:01")))
        assertFalse(w.matchesFilters(Offer(1, "x", "Lufthansa", departure = "2026-11-12T10:00")))
        assertTrue(w.matchesFilters(Offer(1, "x", null, departure = null)), "ismeretlen adat miatt nem dobjuk el")
        val ranked = PriceChecker.rank(w, listOf(
            Offer(100, "x", "Lufthansa", "BUD", "LHR", "2026-11-12T10:00"),
            Offer(200, "x", "Wizz Air", "BUD", "LTN", "2026-11-12T10:00"),
        ), java.time.LocalDateTime.of(2026, 1, 1, 0, 0))
        assertEquals(listOf(200), ranked.map { it.price })
    }

    @Test fun airlineFilterNormalisation() {
        val w = watch().copy(airlines = "wizzair, ryan air")
        assertTrue(w.airlineMatches("Wizz Air"))
        assertTrue(w.airlineMatches("Ryanair"))
        assertTrue(w.airlineMatches("Lufthansa, Ryanair"))
        assertFalse(w.airlineMatches("Lufthansa"))
        assertTrue(watch().copy(airlines = "").airlineMatches("Bármi"))
    }

    @Test fun sanitizeRejectsNonsense() {
        val ok = watch()
        assertNotNull(ok.sanitized())
        assertNull(ok.copy(adults = 0).sanitized())
        assertNull(ok.copy(from = "BUD;DROP").sanitized())
        assertNull(ok.copy(to = "BUD").sanitized(), "honnan = hova")
        assertNull(ok.copy(returnDate = "2026-11-01").sanitized(), "visszaút az indulás előtt")
        val clamped = ok.copy(flexDays = 99, stops = 9, bags = 50, toLabel = "x".repeat(500)).sanitized()!!
        assertEquals(3, clamped.flexDays)
        assertEquals(3, clamped.stops)
        assertTrue(clamped.bags <= 3)
        assertEquals(60, clamped.toLabel!!.length)
    }

    @Test fun backupRestoresSettingsButNotKeys() {
        val text = Backup.export(listOf(watch()), Settings(themeMode = THEME_DAY, textScale = 130, quietOn = true, quietFrom = 23, apiKey = "K"))
        val parsed = Backup.parse(text)!!
        val restored = Backup.applySettings(parsed.settings, Settings(apiKey = "SAJAT", currency = "EUR"))
        assertEquals(THEME_DAY, restored.themeMode)
        assertEquals(130, restored.textScale)
        assertTrue(restored.quietOn)
        assertEquals(23, restored.quietFrom)
        assertEquals("SAJAT", restored.apiKey)
        assertEquals("EUR", restored.currency)
    }

    @Test fun verdictNeedsVariedHistory() {
        val w = Watch(
            id = "v", from = "BUD", to = "STN", outboundDate = "2026-12-20", returnDate = null, travelClass = 1, adults = 1,
            children = 0, infantsInSeat = 0, infantsOnLap = 0, bags = 0, stops = 0, targetPrice = 1, notify = true,
            offers = listOf(Offer(100, "x")), history = List(5) { PricePoint(it * 1000L, 100) },
        )
        assertNull(verdictFor(w, today), "mind ugyanaz → nincs „legalacsonyabb ár” állítás")
    }

    @Test fun discoverLastDayOfMonth() {
        val lastDay = LocalDate.of(2026, 10, 31)
        val periods = Discover.periods(lastDay)
        assertFalse(periods.any { it.second.contains("október", ignoreCase = true) }, periods.toString())
        assertEquals(12, periods.size)
    }

    @Test fun searchKeyIncludesNewFields() {
        val w = watch()
        assertTrue(w.searchKey() != w.copy(flexDays = 1).searchKey())
        assertTrue(w.searchKey() != w.copy(depFrom = null).searchKey())
        assertTrue(w.searchKey() != w.copy(airlines = "").searchKey())
        assertEquals(w.searchKey(), w.copy(airlines = " wizz, ryanair ").searchKey(), "kis-nagybetű és szóköz nem számít")
    }

    @Test fun newFieldsSurviveJson() {
        val w = watch(flex = 3)
        assertEquals(w, Watch.fromJson(JSONObject(w.toJson().toString())))
        val old = Watch.fromJson(JSONObject("""{"id":"a","from":"BUD","to":"LTN","outboundDate":"2026-12-01","targetPrice":1}"""))
        assertEquals(0, old.flexDays)
        assertNull(old.depFrom)
        assertEquals("", old.airlines)
    }

    // ---------------- Megosztás
    @Test fun shareCodeRoundTrip() {
        val w = watch(flex = 1).copy(lastPrice = 99999, history = listOf(PricePoint(1, 2)))
        val msg = ShareCode.message(w, "HUF")
        assertTrue(msg.contains("London") && msg.contains("REFI1:"))
        val (back, cur) = assertNotNull(ShareCode.decode("Szia! Nézd ezt:\n$msg\nPuszi"))
        assertEquals("HUF", cur)
        assertTrue(back.id != w.id, "új azonosítót kap")
        assertEquals(w.searchKey(), back.searchKey())
        assertEquals(w.targetPrice, back.targetPrice)
        assertNull(back.lastPrice, "az árak nem utaznak a kóddal")
        assertTrue(back.history.isEmpty())
        assertTrue(ShareCode.encode(w, "HUF").length < 600, "rövid maradjon: ${ShareCode.encode(w, "HUF").length}")
        // Közös figyelés: az eredeti azonosító utazik; újbóli átvételkor a meglévő frissül
        assertEquals(w.id, back.sharedFrom)
        assertEquals(w, ShareCode.existingFor(back, listOf(w)), "a saját figyelésem")
        val mine = back.copy(id = "nalam")
        val again = ShareCode.decode(ShareCode.message(w.copy(outboundDate = "2026-11-14"), "HUF"))!!.first
        assertEquals(mine, ShareCode.existingFor(again, listOf(watch().copy(id = "mas"), mine)))
        // Továbbküldve is az eredeti forrás marad
        assertEquals(w.id, ShareCode.decode(ShareCode.message(mine, "HUF"))!!.first.sharedFrom)
        assertNull(ShareCode.existingFor(watch().copy(id = "x"), listOf(w)))
        // Friss ár az üzenetben (csak https link)
        val deal = ShareCode.message(w.copy(targetPrice = 100000, offers = listOf(
            Offer(80000, "Google Flights", "Wizz Air", url = "https://example.com/x", bagsIncluded = true))), "HUF")
        assertTrue(deal.contains("Most: 80") && deal.contains("célár alatt") && deal.contains("Foglalás: https://example.com/x"), deal)
        assertFalse(ShareCode.message(w.copy(offers = listOf(Offer(1, "x", url = "javascript:alert(1)"))), "HUF").contains("javascript"))
    }

    @Test fun shareCodeRejectsGarbage() {
        assertNull(ShareCode.decode("semmi"))
        assertNull(ShareCode.decode("REFI1:"))
        assertNull(ShareCode.decode("REFI1:!!!!"))
        assertNull(ShareCode.decode("REFI1:AAAAAAAAAAAAAAAA"))
        // Bomba elleni védelem: hatalmas kicsomagolt méret
        val huge = java.io.ByteArrayOutputStream()
        val d = java.util.zip.Deflater(9, true)
        d.setInput(ByteArray(5_000_000) { 'a'.code.toByte() }); d.finish()
        val buf = ByteArray(4096)
        while (!d.finished()) huge.write(buf, 0, d.deflate(buf))
        val code = "REFI1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(huge.toByteArray())
        assertNull(ShareCode.decode(code))
    }

    // ---------------- Mentés
    @Test fun backupRoundTrip() {
        val ws = listOf(watch(), watch().copy(id = "w2", to = "BCN", toLabel = "Barcelona"))
        val s = Settings(currency = "EUR", apiKey = "TITKOS-KULCS", quietOn = true)
        val text = Backup.export(ws, s)
        assertFalse(text.contains("TITKOS-KULCS"), "API-kulcs nem kerülhet a mentésbe")
        val parsed = assertNotNull(Backup.parse(text))
        assertEquals("EUR", parsed.currency)
        assertEquals(ws, parsed.watches)
        assertEquals(0, parsed.skipped)
        assertNull(Backup.parse("""{"hello":1}"""))
        assertNull(Backup.parse("nem json"))
        val broken = JSONObject(text).apply { getJSONArray("watches").put(JSONObject().put("x", 1)) }.toString()
        assertEquals(1, Backup.parse(broken)!!.skipped)
    }

    // ---------------- Most vegyem?
    @Test fun verdicts() {
        fun w(now: Int, hist: List<Int>, out: String = "2026-12-20") = Watch(
            id = "v", from = "BUD", to = "STN", outboundDate = out, returnDate = null, travelClass = 1, adults = 1,
            children = 0, infantsInSeat = 0, infantsOnLap = 0, bags = 0, stops = 0, targetPrice = 1, notify = true,
            offers = listOf(Offer(now, "x")), history = hist.mapIndexed { i, p -> PricePoint(i * 21_600_000L, p) },
        )
        assertNull(verdictFor(w(100, listOf(100, 120)), today), "kevés mérésnél nincs vélemény")
        assertEquals(Verdict.Tone.GOOD, verdictFor(w(90, listOf(120, 110, 100, 90)), today)!!.tone)
        assertEquals(Verdict.Tone.WAIT, verdictFor(w(150, listOf(100, 110, 120, 130, 150)), today)!!.tone)
        assertEquals(Verdict.Tone.NEUTRAL, verdictFor(w(150, listOf(100, 110, 120, 130, 150), out = "2026-10-15"), today)!!.tone)
        assertEquals(Verdict.Tone.NEUTRAL, verdictFor(w(115, listOf(100, 110, 120, 130)), today)!!.tone)
        // Kevés saját mérésnél a Google szokásos ársávja dönt (csak poggyász nélkül)
        val m = MarketInsight(emptyList(), 100, 200, 0L)
        assertEquals(Verdict.Tone.GOOD, verdictFor(w(90, listOf(95)).copy(market = m), today)!!.tone)
        assertEquals(Verdict.Tone.WAIT, verdictFor(w(250, emptyList()).copy(market = m), today)!!.tone)
        assertEquals(Verdict.Tone.NEUTRAL, verdictFor(w(250, emptyList(), out = "2026-10-15").copy(market = m), today)!!.tone)
        assertTrue(verdictFor(w(150, emptyList()).copy(market = m), today, "HUF")!!.text.contains("általában"))
        assertNull(verdictFor(w(90, emptyList()).copy(market = m, bags = 1), today), "poggyásszal nem összevethető")
        // Megtakarítás: legalább 5% kell
        val now0 = 5 * 86_400_000L
        assertEquals(null, savingsLine(w(98, listOf(100, 99)), "HUF", now0))
        assertTrue(savingsLine(w(80, listOf(100, 90)), "HUF", now0)!!.contains("5 napja"))
        assertNull(savingsLine(w(120, listOf(100)), "HUF", now0))
    }

    // ---------------- Minden héten (pl. bármelyik hétvége)
    @Test fun weeklyMode() {
        // 2026-11-13 péntek – 11-15 vasárnap, minden héten dec. 4-ig → 4 hétvége
        val w = watch(out = "2026-11-13", ret = "2026-11-15").copy(weeklyUntil = "2026-12-04")
        val pairs = w.datePairs(today)
        assertEquals(listOf("2026-11-13" to "2026-11-15", "2026-11-20" to "2026-11-22",
            "2026-11-27" to "2026-11-29", "2026-12-04" to "2026-12-06"), pairs)
        assertTrue(w.isFlexible)
        // A múltbeli hetek kimaradnak, a lejárat az utolsó héthez igazodik
        assertEquals("2026-11-27", w.datePairs(LocalDate.of(2026, 11, 25)).first().first)
        assertFalse(w.isExpired(LocalDate.of(2026, 12, 4)))
        assertTrue(w.isExpired(LocalDate.of(2026, 12, 5)))
        // Legfeljebb MAX_WEEKS hét; a hibás vég eldobódik
        val long = w.copy(weeklyUntil = "2027-06-01").sanitized()!!
        assertEquals(MAX_WEEKS, long.datePairs(today).size)
        assertNull(w.copy(weeklyUntil = "2026-11-01").sanitized()!!.weeklyUntil)
        assertEquals(0, w.copy(flexDays = 2).sanitized()!!.flexDays, "a két rugalmasság nem keveredik")
        // Más keresés (a régi árak nem hasonlíthatók), JSON-oda-vissza, a pontos példány
        assertTrue(w.searchKey() != w.copy(weeklyUntil = null).searchKey())
        assertEquals(w, Watch.fromJson(w.toJson()))
        assertNull(w.exact("2026-11-20", "2026-11-22").weeklyUntil)
        // A szinkronfájl csak ilyenkor 2-es verziójú (a régi appok így nem írják felül)
        assertEquals(2, JSONObject(Sync.serialize(listOf(w), emptyMap(), "HUF")).getInt("version"))
        assertEquals(1, JSONObject(Sync.serialize(listOf(watch()), emptyMap(), "HUF")).getInt("version"))
        assertEquals("2026-12-04", Sync.parse(Sync.serialize(listOf(w), emptyMap(), "HUF"))!!.watches.single().weeklyUntil)
        // Megosztott kód
        val (back, _) = assertNotNull(ShareCode.decode(ShareCode.message(w, "HUF")))
        assertEquals("2026-12-04", back.weeklyUntil)
        assertEquals(4, weeklyCount(LocalDate.parse("2026-11-13"), LocalDate.parse("2026-12-04")))
        assertTrue(ShareCode.message(w, "HUF").contains("REFI2:"), "a régi appok ne vegyék át egy dátumosként")
        assertTrue(ShareCode.message(watch(), "HUF").contains("REFI1:"))
        // Nem azonos hétköznapra eső határ: az utolsó valódi indulás számít (lejárat, mentés)
        val odd = w.copy(weeklyUntil = "2026-12-09")   // szerda → az utolsó péntek dec. 4.
        assertEquals(LocalDate.parse("2026-12-04"), odd.lastDeparture())
        assertTrue(odd.isExpired(LocalDate.of(2026, 12, 5)))
        assertEquals("2026-12-04", odd.sanitized()!!.weeklyUntil)
        assertEquals(LocalDate.parse("2026-12-04"), lastWeekly(LocalDate.parse("2026-11-13"), LocalDate.parse("2026-12-09")))
    }

    // ---------------- Előrejelzés, csoportos költség, naptár, bárhová-riasztás
    @Test fun forecastGroupCalendarDeals() {
        val now = LocalDate.of(2026, 10, 8).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun w(prices: List<Int>, out: String = "2026-12-20") = watch(out = out, ret = null).copy(
            bags = 0, checkedBag = false, offers = listOf(Offer(prices.last(), "x")),
            history = prices.mapIndexed { i, p -> PricePoint(now - (prices.size - 1 - i) * 86_400_000L, p) },
        )
        assertEquals(Verdict.Tone.WAIT, forecastFor(w(listOf(100, 97, 95, 92, 90, 88)), today, now)!!.tone)
        assertEquals(Verdict.Tone.GOOD, forecastFor(w(listOf(80, 84, 86, 90, 93, 95)), today, now)!!.tone)
        assertNull(forecastFor(w(listOf(90, 90, 91, 90, 90, 90)), today, now), "stabil árnál nincs mondanivaló")
        assertNull(forecastFor(w(listOf(100, 90)), today, now), "kevés mérés")
        assertTrue(forecastFor(w(listOf(100, 90), out = "2026-10-20"), today, now)!!.text.contains("12 nap"))
        assertEquals(-2.0, trendPercentPerDay(listOf(PricePoint(0, 101), PricePoint(86_400_000L, 99)))!!, 0.01)

        // Fejenként: 3 ülő utas (2 felnőtt + 1 gyerek)
        val g = watch()
        val line = groupCostLine(g, Offer(90000, "x"), "HUF", 30000)!!
        assertTrue(line.contains("40") && line.contains("3 fő") && line.contains("transzfer"), line)
        assertNull(groupCostLine(watch().copy(adults = 1, children = 0, infantsOnLap = 0), Offer(1000, "x"), "HUF", null))

        // Naptár: oda és vissza, UTC-re váltva a reptér zónájából (télen Budapest = UTC+1)
        val o = Offer(50000, "Google Flights", "Wizz Air", "BUD", "LTN", "2026-11-13T12:50", "2026-11-13T14:35", 0,
            "2026-11-17T20:00", "2026-11-17T23:30", 0, "https://example.com/b")
        val evs = RefiCalendar.eventsFor(watch(), o)
        assertEquals(2, evs.size)
        val ics = RefiCalendar.ics(evs, 0L)
        assertTrue(ics.contains("DTSTART:20261113T115000Z"), ics)
        assertTrue(ics.contains("BEGIN:VCALENDAR") && ics.contains("SUMMARY:✈ LTN → BUD"))
        assertTrue(ics.lines().all { it.toByteArray(Charsets.UTF_8).size <= 75 }, "75 bájtos sorok")
        // Ismeretlen zónájú érkezés (pl. JFK): nem keverünk zónát, 2 órás bejegyzés UTC-ben
        val jfk = RefiCalendar.eventsFor(watch(), o.copy(toCode = "JFK", returnDeparture = null, returnArrival = null)).single()
        assertNull(jfk.end)
        assertTrue(RefiCalendar.ics(listOf(jfk), 0L).contains("DTEND:20261113T135000Z"))
        assertEquals("2026. decemberben", DealAlerts.periodLabel(DealAlert("x", "BUD", "Budapest", "2026-12", 1, 1, "HUF", 0L)))
        assertEquals("2026. májusban", DealAlerts.periodLabel(DealAlert("x", "BUD", "Budapest", "2027-05", 1, 1, "HUF", 0L)).replace("2027", "2026"))

        // Bárhová-riasztás: időszak-index, lejárat, újonnan jelzendők, JSON
        val a = DealAlert("d1", "BUD", "Budapest", "2026-12", 1, 20000, "HUF", 0L, notified = mapOf("BCN" to 15000))
        assertEquals(3, a.periodIndex(today))
        assertTrue(a.copy(month = "2026-09").isExpired(today))
        assertEquals(0, a.copy(month = null).periodIndex(today))
        fun r(code: String, p: Int) = Discover.Result(code, code, "", p, null, null, "BUD")
        assertEquals(listOf("STN", "BCN"), DealAlerts.fresh(a, listOf(r("STN", 19000), r("BCN", 14000), r("MAD", 25000))).map { it.code })
        assertTrue(DealAlerts.fresh(a, listOf(r("BCN", 14800))).isEmpty(), "5%-nál kisebb esésről nem szólunk újra")
        assertEquals(a.copy(latest = listOf(r("STN", 19000))), DealAlert.fromJson(a.copy(latest = listOf(r("STN", 19000))).toJson()))
    }

    // ---------------- Nyelvek: városnevek és tárolt szövegek
    @Test fun languagesCitiesAndStoredTexts() {
        try {
            Lang.set(Lang.EN_CODE)
            assertEquals("Milan", Airports.cityName("MXP"))
            assertEquals("Athens", Airports.cityName("ATH"))
            assertEquals("Brussels", Airports.cityName("BRU"))
            assertEquals("Budapest → London", watch().copy(fromLabel = "Budapest", toLabel = "London").routeTitle)
            assertEquals("Rome → Milan", watch().copy(from = "FCO", to = "MXP,LIN,BGY", fromLabel = "Róma", toLabel = "Milánó").routeTitle)
            Lang.set(Lang.DE_CODE)
            assertEquals("Mailand", Airports.cityName("MXP,LIN,BGY"))
            assertEquals("Wien", Airports.cityName("VIE"))
            assertEquals("Italien", Airports.countryName("FCO"))
            Lang.set(Lang.HU_CODE)
            assertEquals("Milánó", Airports.cityName("MXP"))
            assertEquals("Róma → Milánó", watch().copy(from = "FCO", to = "MXP,LIN,BGY").routeTitle)
            // Keresés bármelyik nyelvű névvel
            assertTrue(Airports.search("Mailand").any { it.codes.contains("MXP") })
            assertTrue(Airports.search("Milan").any { it.codes.contains("MXP") })

            // Tárolt szöveg: magyarul keletkezett, angolul angolul látszik
            val msg = trs("Nincs járat erre a napra", "No flights on this day", "Keine Flüge an diesem Tag")
            val w = watch().copy(lastError = msg, sourceStatus = listOf(SourceStatus("Ryanair", true, msg)),
                offers = listOf(Offer(1, "x", note = msg))).withL10n()
            val back = Watch.fromJson(w.toJson())
            Lang.set(Lang.EN_CODE)
            assertEquals("No flights on this day", back.errorText)
            assertEquals("No flights on this day", back.sourceStatus.single().shown)
            assertEquals("No flights on this day", back.offers.single().noteText)
            Lang.set(Lang.DE_CODE)
            assertEquals("Keine Flüge an diesem Tag", back.errorText)
            // Ismeretlen (pl. régi) szöveg úgy marad, ahogy volt
            assertEquals("valami régi", watch().copy(lastError = "valami régi").withL10n().errorText)
            // Összefűzött megjegyzések
            val a = trs("egy", "one", "eins"); val b = trs("kettő", "two", "zwei")
            Lang.set(Lang.HU_CODE)
            assertEquals(L10n("egy, kettő", "one, two", "eins, zwei"), Texts.find("$a, $b"))
            assertEquals("Ugyanaz", tr("Ugyanaz", "Same"))
        } finally {
            Lang.set(Lang.HU_CODE)
        }
    }

    // ---------------- Reptéri transzfer
    @Test fun transfers() {
        val stn = assertNotNull(Transfers.infoFor("stn"))
        assertNull(Transfers.infoFor("BUD"))
        val rt = watch().copy(returnDate = "2026-12-27", adults = 2)
        assertEquals(rt.seatedPassengers * 2, Transfers.trips(rt))
        val line = Transfers.line(rt, stn, "HUF") { it * 400.0 }
        assertTrue(line.contains("London") && line.contains("oda-vissza"), line)
        // Árfolyam nélkül euróban
        assertTrue(Transfers.line(rt, stn, "HUF") { null }.contains("€"))
    }

    // ---------------- Csendes órák
    @Test fun quietHours() {
        val s = Settings(quietOn = true, quietFrom = 22, quietTo = 7)
        assertTrue(s.isQuiet(LocalTime.of(23, 30)))
        assertTrue(s.isQuiet(LocalTime.of(3, 0)))
        assertFalse(s.isQuiet(LocalTime.of(7, 0)))
        assertFalse(s.isQuiet(LocalTime.of(12, 0)))
        assertTrue(s.copy(quietFrom = 1, quietTo = 5).isQuiet(LocalTime.of(2, 0)))
        assertFalse(s.copy(quietFrom = 1, quietTo = 5).isQuiet(LocalTime.of(23, 0)))
        assertFalse(s.copy(quietOn = false).isQuiet(LocalTime.of(23, 0)))
        assertFalse(s.copy(quietFrom = 5, quietTo = 5).isQuiet(LocalTime.of(5, 0)))
    }

    // ---------------- Wizz időablak
    @Test fun wizzCheapestRespectsWindow() {
        val flights = JSONArray("""[{"departureDate":"2026-11-12T00:00:00","price":{"amount":30,"currencyCode":"EUR"},
            "departureStation":"BUD","arrivalStation":"LTN",
            "departureDates":[{"date":"2026-11-12T06:00:00","isCheapestOfTheDay":true},{"date":"2026-11-12T17:30:00"}]}]""")
        assertNotNull(WizzAir.cheapest(flights, "2026-11-12", null, null))
        assertNull(WizzAir.cheapest(flights, "2026-11-12", 7, null), "a legolcsóbb 06:00-kor indul – az ára nem érvényes 7 után")
        assertEquals("LTN", WizzAir.cheapest(flights, "2026-11-12", 5, 9)!!.to)
    }

    // ---------------- Felfedezés
    @Test fun discoverParsing() {
        val body = """{"fares":[
          {"outbound":{"departureAirport":{"iataCode":"BUD"},"arrivalAirport":{"iataCode":"BGY","name":"Milan Bergamo","countryName":"Italy","city":{"name":"Milan","countryCode":"it"}},
           "departureDate":"2026-11-12T08:35:00","price":{"value":15.99,"currencyCode":"HUF"}}},
          {"outbound":{"departureAirport":{"iataCode":"BUD"},"arrivalAirport":{"iataCode":"ZZZ","name":"Nowhere","countryName":"X"},
           "departureDate":"2026-11-13T10:00:00","price":{"value":0,"currencyCode":"HUF"}}}
        ]}"""
        val list = Discover.parse(body, "BUD", "HUF")
        assertEquals(1, list.size)
        assertEquals("Milánó", list.first().city)
        assertEquals(16, list.first().pricePerPerson)
        assertEquals("Olaszország", list.first().country)
        val t = Discover.templateFor(list.first(), 2, "Budapest")
        assertEquals("BGY", t.to)
        assertEquals(32, t.targetPrice)
        assertEquals("2026-11-12", t.outboundDate)
        val (a, b) = Discover.periodRange(0, today)
        assertEquals(today.plusDays(1), a)
        assertEquals(today.plusDays(30), b)
        val (c, e) = Discover.periodRange(1, today)
        assertEquals(today.plusDays(1), c, "a folyó hónap a holnapi nappal kezdődik")
        assertEquals(LocalDate.of(2026, 10, 31), e)
        assertEquals(13, Discover.periods(today).size)
    }
}
