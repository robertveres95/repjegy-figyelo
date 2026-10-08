package hu.repjegy.figyelo

import org.json.JSONObject
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Automata tesztek a közös logikára (hálózat nélkül). */
class LogicTest {

    @BeforeTest
    fun setUp() {
        Platform.current = DesktopPlatform
    }

    private fun watch(
        from: String = "BUD", to: String = "STN", ret: String? = null,
        bags: Int = 0, checked: Boolean = false, adults: Int = 1,
    ) = Watch(
        id = "t", from = from, to = to, outboundDate = LocalDate.now().plusDays(30).toString(),
        returnDate = ret, travelClass = 1, adults = adults, children = 0, infantsInSeat = 0,
        infantsOnLap = 0, bags = bags, checkedBag = checked, stops = 0, targetPrice = 30000, notify = true,
    )

    @Test fun typedDates() {
        assertEquals(LocalDate.of(2026, 10, 16), parseTypedDate("2026.10.16"))
        assertEquals(LocalDate.of(2026, 10, 16), parseTypedDate("2026-10-16"))
        assertEquals(LocalDate.of(2026, 10, 16), parseTypedDate("2026. 10. 16."))
        assertNull(parseTypedDate("2026.02.30"))
        assertNull(parseTypedDate("26.10.16"))
        assertNull(parseTypedDate("2026.10"))
        assertNull(parseTypedDate(""))
    }

    @Test fun lowCostPairsCoverEveryAirport() {
        val w = watch(from = "LHR,LGW,STN,LTN,LCY,SEN", to = "MXP,LIN,BGY")
        val pairs = pairsOf(w)
        assertEquals(12, pairs.size)
        assertEquals(pairs.size, pairs.toSet().size, "ismétlődő pár")
        assertTrue(pairs.any { it.first == "STN" && it.second == "BGY" }, "STN→BGY hiányzik: $pairs")
        assertEquals(listOf("BUD" to "STN"), pairsOf(watch()))
    }

    @Test fun watchJsonRoundTrip() {
        val w = watch(ret = LocalDate.now().plusDays(37).toString(), bags = 2, checked = true).copy(
            fromLabel = "Budapest", toLabel = "London", lastPrice = 25000, lowestPrice = 20000,
            lastChecked = 1_700_000_000_000, lastError = "x", lastNotifiedPrice = 21000,
            offers = listOf(Offer(25000, "Ryanair", "Ryanair", "BUD", "STN", "2026-11-05T06:25", "2026-11-05T08:10", 0,
                "2026-11-12T09:00", "2026-11-12T12:40", 1, "https://x", false, "becsült")),
            sourceStatus = listOf(SourceStatus("Ryanair", true, "1 ajánlat")),
            history = listOf(PricePoint(1L, 30000), PricePoint(2L, 25000)),
        )
        val back = Watch.fromJson(JSONObject(w.toJson().toString()))
        assertEquals(w, back)
    }

    @Test fun oldWatchFormatStillLoads() {
        val old = JSONObject("""{"id":"a","from":"BUD","to":"LTN","outboundDate":"2026-12-01","targetPrice":20000}""")
        val w = Watch.fromJson(old)
        assertEquals(1, w.adults)
        assertNull(w.returnDate)
        assertTrue(w.notify)
    }

    @Test fun corruptHistoryEntryDoesNotDropWatch() {
        val j = JSONObject("""{"id":"a","from":"BUD","to":"LTN","outboundDate":"2026-12-01","targetPrice":1,
            "history":[[1,100],["x"],[2]]}""")
        val w = runCatching { Watch.fromJson(j) }
        assertTrue(w.isSuccess, "egy hibás árpont miatt az egész figyelés elveszett: ${w.exceptionOrNull()}")
    }

    @Test fun rankPutsBaglessLastWhenBagsWanted() {
        val w = watch(bags = 1)
        val ranked = PriceChecker.rank(w, listOf(
            Offer(10000, "Ryanair", bagsIncluded = false, departure = "2026-11-05T06:25", fromCode = "BUD", toCode = "STN", airline = "Ryanair"),
            Offer(20000, "Google Flights", bagsIncluded = true, departure = "2026-11-05T09:00", fromCode = "BUD", toCode = "LTN", airline = "Wizz Air"),
        ))
        assertEquals(20000, ranked.first().price)
    }

    @Test fun rankDeduplicatesSameFlight() {
        val w = watch()
        val a = Offer(15000, "Google Flights", "Ryanair", "BUD", "STN", "2026-11-05T06:25")
        val b = Offer(14000, "Ryanair", "Ryanair", "BUD", "STN", "2026-11-05T06:25")
        val ranked = PriceChecker.rank(w, listOf(a, b))
        assertEquals(1, ranked.size)
        assertEquals(14000, ranked.first().price)
    }

    @Test fun versionCompare() {
        val cur = Updater.currentVersion
        val parts = cur.split('.').map { it.toInt() }
        val next = "${parts[0]}.${parts[1]}.${parts[2] + 1}"
        assertTrue(Updater.isNewer(Updater.Release(next, 1, "u", "p")))
        assertFalse(Updater.isNewer(Updater.Release("0.9.9", 99999, "u", "p")))
        assertTrue(Updater.isNewer(Updater.Release(cur, Updater.currentBuild + 1, "u", "p")))
        assertFalse(Updater.isNewer(Updater.Release(cur, Updater.currentBuild, "u", "p")))
        assertTrue(Updater.isNewer(Updater.Release("${parts[0]}.${parts[1] + 1}.0", 1, "u", "p")))
    }

    @Test fun legTextOvernight() {
        val p = assertNotNull(legParts("2026-11-05T23:10", "2026-11-06T01:05", 0, "BUD", "STN"))
        assertTrue(p.times.contains("+1"), p.times)
        assertTrue(p.route!!.contains("közvetlen"))
        assertNull(legParts(null, null, null, null, null))
        assertNull(legParts("garbage", null, null, null, null))
    }

    @Test fun prices() {
        val huf = formatPrice(1234567, "HUF")
        assertTrue(huf.filter(Char::isDigit) == "1234567", huf)
        assertTrue(formatPrice(99, "EUR").contains("99"))
    }

    @Test fun airportSearch() {
        fun codes(q: String) = Airports.search(q).map { it.codes }
        assertTrue(codes("budapest").any { "BUD" in it }, codes("budapest").toString())
        assertTrue(codes("bécs").any { "VIE" in it })
        assertTrue(codes("becs").any { "VIE" in it }, "ékezet nélkül is találjon")
        assertTrue(codes("london").first().contains(","), "a több repteres város legyen elöl: ${codes("london")}")
        assertTrue(codes("LTN").any { it == "LTN" })
        assertTrue(Airports.search("").size <= 50)
        assertTrue(Airports.search("zzzzqqq").isEmpty())
        val place = assertNotNull(Airports.placeFor("LHR,LGW,STN,LTN,LCY,SEN", null))
        assertEquals("London", place.city)
    }

    @Test fun searchKeyIgnoresResultFields() {
        val w = watch()
        assertEquals(w.searchKey(), w.copy(lastPrice = 1, targetPrice = 5, notify = false).searchKey())
        assertTrue(w.searchKey() != w.copy(adults = 2).searchKey())
    }
}
