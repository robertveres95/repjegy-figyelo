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
