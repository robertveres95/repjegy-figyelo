package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.ceil

internal const val MAX_PAIRS = 12

/** Rugalmas dátumnál dátumonként ennyi reptérpárt kérdezünk (különben túl sok kérés lenne). */
internal const val MAX_PAIRS_FLEX = 4

/**
 * Több repteres városoknál (pl. London: LHR, LGW, STN, LTN…) a párokat „átlósan” vesszük
 * sorra, hogy a korlát minden reptérből adjon párt – a fapadosok bázisai (STN, LTN)
 * gyakran a lista végén vannak, és a sima sorrendnél kimaradnának.
 */
/** Fapados-bázisok a több repteres városokban: ezek kerüljenek előre, ha kevés párt kérdezünk. */
private val LOW_COST_BASES = setOf("STN", "LTN", "SEN", "LGW", "BGY", "MXP", "CIA", "TSF", "CRL", "BVA", "WMI", "SAW", "DWC", "DMK")

internal fun pairsOf(w: Watch, max: Int = MAX_PAIRS): List<Pair<String, String>> {
    val from = w.from.split(',').sortedBy { if (it in LOW_COST_BASES) 0 else 1 }
    val to = w.to.split(',').sortedBy { if (it in LOW_COST_BASES) 0 else 1 }
    return from.indices.flatMap { i -> to.indices.map { j -> Triple(i, j, from[i] to to[j]) } }
        .sortedWith(compareBy({ maxOf(it.first, it.second) }, { it.first + it.second }))
        .map { it.third }
        .take(max)
}

/** "2026-11-05T06:25:00.000" → "2026-11-05T06:25" */
private fun trimTime(raw: String?): String? =
    raw?.takeIf { it.length >= 16 && it[10] == 'T' }?.substring(0, 16)

/**
 * A fapadosok egy főre adják az árat. Az összárat az ülőhelyes utasok számával becsüljük;
 * az ölben utazó csecsemő díját és a poggyászt nem tartalmazza.
 */
private fun lowCostNote(w: Watch): String? {
    val parts = mutableListOf<String>()
    if (w.seatedPassengers > 1) parts += trs("becsült: ${w.seatedPassengers} × egy fő ára", "estimated: ${w.seatedPassengers} × the price for one", "geschätzt: ${w.seatedPassengers} × Preis für eine Person")
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun searchPairs(w: Watch, maxPairs: Int, block: (String, String) -> List<Offer>): List<Offer> =
    collectOffers(pairsOf(w, maxPairs)) { (o, d) -> block(o, d) }

// ---------------------------------------------------------------- Ryanair

/** Ryanair saját, kulcs nélküli árkereső felülete (services-api.ryanair.com/farfnd). */
object Ryanair {
    const val NAME = "Ryanair"

    fun search(w: Watch, currency: String, maxPairs: Int = MAX_PAIRS): List<Offer> {
        if (w.travelClass != 1) throw SkipSourceException(trs("csak turista osztály", "economy class only", "nur Economy Class"))
        return searchPairs(w, maxPairs) { o, d -> searchPair(w, o, d, currency) }
    }

    private fun searchPair(w: Watch, origin: String, destination: String, currency: String): List<Offer> {
        val params = linkedMapOf(
            "departureAirportIataCode" to origin,
            "arrivalAirportIataCode" to destination,
            "outboundDepartureDateFrom" to w.outboundDate,
            "outboundDepartureDateTo" to w.outboundDate,
            "currency" to currency,
            "language" to "en",
            "market" to "en-gb",
        )
        w.returnDate?.let {
            params["inboundDepartureDateFrom"] = it
            params["inboundDepartureDateTo"] = it
        }
        // Időablak: a Ryanair a legolcsóbb járatot adja, ezért az ablakot neki is meg kell adni
        // (különben egy ablakon kívüli olcsó járat elrejtené a megfelelőt)
        w.depFrom?.let { params["outboundDepartureTimeFrom"] = "%02d:00".format(it) }
        w.depTo?.let { params["outboundDepartureTimeTo"] = if (it >= 24) "23:59" else "%02d:00".format(it) }
        val endpoint = if (w.isRoundTrip) "roundTripFares" else "oneWayFares"
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val res = Http.request(
            "https://services-api.ryanair.com/farfnd/v4/$endpoint?$query",
            headers = mapOf("Accept" to "application/json"),
        )
        if (res.code == 429 || res.code == 403) throw FatalSourceException(trs("A Ryanair ideiglenesen blokkolta a lekérdezést", "Ryanair has temporarily blocked the search", "Ryanair hat die Suche vorübergehend blockiert"))
        if (res.code == 404) return emptyList()
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")

        val root = JSONObject(res.body)
        // Hiányzó „fares” mező formátumváltozást jelez – ne „nincs járat” legyen belőle
        val fares = root.optJSONArray("fares") ?: throw IOException(trs("Váratlan Ryanair-válasz (változhatott a formátum)", "Unexpected Ryanair response (the format may have changed)", "Unerwartete Ryanair-Antwort (das Format hat sich evtl. geändert)"))
        val offers = mutableListOf<Offer>()
        for (i in 0 until fares.length()) {
            val fare = fares.optJSONObject(i) ?: continue
            val out = fare.optJSONObject("outbound") ?: continue
            val inb = fare.optJSONObject("inbound")
            val priceObj = fare.optJSONObject("summary")?.optJSONObject("price") ?: out.optJSONObject("price") ?: continue
            val perPerson = priceObj.optDouble("value", Double.NaN)
            if (perPerson.isNaN() || perPerson <= 0) continue
            val cur = priceObj.optString("currencyCode", currency)
            // Egy furcsa ajánlat (pl. ismeretlen pénznem) ne vigye el a többit
            val total = runCatching { Rates.convert(perPerson, cur, currency) * w.seatedPassengers }.getOrNull() ?: continue
            offers += Fees.apply(Offer(
                price = ceil(total).toInt(),
                source = NAME,
                airline = "Ryanair",
                // optString hiányzó mezőnél üres szöveget ad (nem nullt): csak érvényes kódot fogadunk el
                fromCode = out.optJSONObject("departureAirport")?.optString("iataCode")?.takeIf { it.length == 3 } ?: origin,
                toCode = out.optJSONObject("arrivalAirport")?.optString("iataCode")?.takeIf { it.length == 3 } ?: destination,
                departure = trimTime(out.optString("departureDate")),
                arrival = trimTime(out.optString("arrivalDate")),
                stops = 0,
                returnDeparture = trimTime(inb?.optString("departureDate")),
                returnArrival = trimTime(inb?.optString("arrivalDate")),
                returnStops = if (inb != null) 0 else null,
                url = bookingUrl(w, origin, destination),
                note = lowCostNote(w),
            ), w, currency, includeInfants = true)
        }
        return offers
    }

    private fun bookingUrl(w: Watch, origin: String, destination: String): String =
        "https://www.ryanair.com/hu/hu/trip/flights/select?adults=${w.adults}&teens=0" +
            "&children=${w.children + w.infantsInSeat}&infants=${w.infantsOnLap}" +
            "&dateOut=${w.outboundDate}&dateIn=${w.returnDate ?: ""}&isConnectedFlight=false" +
            "&isReturn=${w.isRoundTrip}&discount=0&originIata=$origin&destinationIata=$destination"
}

// ---------------------------------------------------------------- Wizz Air

/**
 * A Wizz Air weboldala mögötti menetrend-felület (be.wizzair.com …/search/timetableV2).
 * Kulcs nem kell, de munkamenet-süti igen; a verziószámot a nyitóoldalról olvassuk ki.
 * A felület működését a nyílt forrású flywizz könyvtár írja le.
 */
object WizzAir {
    const val NAME = "Wizz Air"
    private const val HOME = "https://www.wizzair.com/en-gb"
    private const val DEFAULT_VERSION = "29.14.0"
    private const val SESSION_MAX_AGE_MS = 20 * 60 * 1000L

    private var apiBase: String? = null

    /** Az utolsó elutasítás (HTTP 400) szövege – diagnosztikához. */
    @Volatile
    var lastRejection: String = ""
    private var sessionAt = 0L

    fun search(w: Watch, currency: String, maxPairs: Int = MAX_PAIRS): List<Offer> {
        if (w.travelClass != 1) throw SkipSourceException(trs("csak turista osztály", "economy class only", "nur Economy Class"))
        return searchPairs(w, maxPairs) { o, d -> searchPair(w, o, d, currency, retry = true) }
    }

    @Synchronized
    private fun base(forceNew: Boolean): String {
        val now = System.currentTimeMillis()
        apiBase?.let { if (!forceNew && now - sessionAt < SESSION_MAX_AGE_MS) return it }
        val version = runCatching {
            val home = Http.request(HOME, headers = mapOf("Accept" to "text/html"))
            Regex("""be\.wizzair\.com/(\d+\.\d+\.\d+)/Api""").find(home.body)?.groupValues?.get(1)
        }.getOrNull() ?: DEFAULT_VERSION
        val base = "https://be.wizzair.com/$version/Api"
        runCatching { Http.request("$base/userSession/new", headers = apiHeaders()) }
        apiBase = base
        sessionAt = now
        return base
    }

    private fun apiHeaders(): Map<String, String> = buildMap {
        put("Accept", "application/json, text/plain, */*")
        put("Content-Type", "application/json")
        put("Origin", "https://www.wizzair.com")
        put("Referer", HOME)
        Http.cookie("be.wizzair.com", "RequestVerificationToken")?.let { put("X-RequestVerificationToken", it) }
    }

    private fun searchPair(
        w: Watch,
        origin: String,
        destination: String,
        currency: String,
        retry: Boolean,
    ): List<Offer> {
        val flightList = JSONArray().put(
            JSONObject()
                .put("departureStation", origin)
                .put("arrivalStation", destination)
                .put("from", w.outboundDate)
                .put("to", w.outboundDate)
        )
        w.returnDate?.let {
            flightList.put(
                JSONObject()
                    .put("departureStation", destination)
                    .put("arrivalStation", origin)
                    .put("from", it)
                    .put("to", it)
            )
        }
        val body = JSONObject()
            .put("flightList", flightList)
            .put("priceType", "regular")
            .put("adultCount", w.adults)
            .put("childCount", w.children + w.infantsInSeat)
            .put("infantCount", w.infantsOnLap)

        val base = base(forceNew = !retry)
        val res = Http.request("$base/search/timetableV2", method = "POST", headers = apiHeaders(), body = body.toString())
        if (res.code == 429) throw FatalSourceException(trs("a Wizz Air bot-védelme blokkolta", "blocked by Wizz Air's bot protection", "vom Bot-Schutz von Wizz Air blockiert"))
        if (res.code == 400 || res.code == 401 || res.code == 403) {
            // Nincs ilyen útvonal: ezt nem érdemes új munkamenettel újrapróbálni
            if (res.code == 400) lastRejection = "$origin→$destination ${w.outboundDate}: ${res.body.take(400)}"
            if (res.code == 400 && res.body.contains("validationCodes")) {
                val codes = runCatching {
                    JSONObject(res.body).optJSONArray("validationCodes")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                }.getOrNull().orEmpty()
                // „InvalidMarket” = ezen az útvonalon a Wizz nem repül → valóban nincs járat.
                // Minden más elutasítás (pl. utasszám, dátum, megváltozott kérés) hiba, nem „nincs járat”.
                if (codes.isNotEmpty() && codes.all { it == "InvalidMarket" }) return emptyList()
                throw IOException(
                    trs("a Wizz Air elutasította a kérést", "Wizz Air rejected the request", "Wizz Air hat die Anfrage abgelehnt") +
                        " (${codes.joinToString().ifEmpty { trs("ismeretlen ok", "unknown reason", "unbekannter Grund") }.take(80)})",
                )
            }
            if (retry) return searchPair(w, origin, destination, currency, retry = false)
            // 401/403 = letiltás (a többi párt sem érdemes kérdezni); egy furcsa 400 csak ennél a párnál hiba
            if (res.code == 400) throw IOException(trs("a Wizz Air elutasította a kérést (HTTP 400)", "Wizz Air rejected the request (HTTP 400)", "Wizz Air hat die Anfrage abgelehnt (HTTP 400)"))
            throw FatalSourceException(trs("a Wizz Air elutasította a kérést (HTTP ${res.code})", "Wizz Air rejected the request (HTTP ${res.code})", "Wizz Air hat die Anfrage abgelehnt (HTTP ${res.code})"))
        }
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")

        val json = JSONObject(res.body)
        if (!json.has("outboundFlights") && !json.has("returnFlights")) {
            throw IOException(trs("Váratlan Wizz Air-válasz (változhatott a formátum)", "Unexpected Wizz Air response (the format may have changed)", "Unerwartete Wizz-Air-Antwort (das Format hat sich evtl. geändert)"))
        }
        val outbound = cheapest(json.optJSONArray("outboundFlights"), w.outboundDate, w.depFrom, w.depTo) ?: return emptyList()
        val inbound = w.returnDate?.let { cheapest(json.optJSONArray("returnFlights"), it, null, null) ?: return emptyList() }

        // Ismeretlen pénznem (pl. ALL, MKD) vagy elérhetetlen árfolyam: ez a pár hibás, a többi megmarad
        val perPerson = Rates.convert(outbound.amount, outbound.currency, currency) +
            (inbound?.let { Rates.convert(it.amount, it.currency, currency) } ?: 0.0)
        if (!perPerson.isFinite() || perPerson <= 0) throw IOException(trs("érvénytelen Wizz-ár", "invalid Wizz price", "ungültiger Wizz-Preis"))
        return listOf(
            Fees.apply(Offer(
                price = ceil(perPerson * w.seatedPassengers).toInt(),
                source = NAME,
                airline = "Wizz Air",
                // A Wizz a város másik repterére is adhat járatot (pl. LGW-re kérve LTN-t):
                // a válaszban szereplő valódi reptér kell, különben ugyanaz a járat többször látszik
                fromCode = outbound.from ?: origin,
                toCode = outbound.to ?: destination,
                departure = outbound.departure,
                stops = 0,
                returnDeparture = inbound?.departure,
                returnStops = if (inbound != null) 0 else null,
                url = "https://wizzair.com/hu-hu/booking/select-flight/${outbound.from ?: origin}/${outbound.to ?: destination}/" +
                    "${w.outboundDate}/${w.returnDate ?: "null"}/${w.adults}/" +
                    "${w.children + w.infantsInSeat}/${w.infantsOnLap}/null",
                note = lowCostNote(w),
            ), w, currency, includeInfants = true)
        )
    }

    /**
     * Napi legolcsóbb árak (egy főre, csak oda) egy időszakra – az árnaptárhoz. A Wizz egy kérésben
     * legfeljebb ~6 hetet ad vissza.
     */
    internal fun dayFares(origin: String, destination: String, from: java.time.LocalDate, to: java.time.LocalDate): List<DayFare> {
        val body = JSONObject()
            .put("flightList", JSONArray().put(
                JSONObject().put("departureStation", origin).put("arrivalStation", destination)
                    .put("from", from.toString()).put("to", to.toString())
            ))
            .put("priceType", "regular").put("adultCount", 1).put("childCount", 0).put("infantCount", 0)
        var res = Http.request("${base(forceNew = false)}/search/timetableV2", method = "POST", headers = apiHeaders(), body = body.toString())
        if (res.code == 401 || res.code == 403) {
            res = Http.request("${base(forceNew = true)}/search/timetableV2", method = "POST", headers = apiHeaders(), body = body.toString())
        }
        if (res.code == 400 && res.body.contains("InvalidMarket")) return emptyList()
        if (res.code !in 200..299) throw IOException("Wizz HTTP ${res.code}")
        val flights = JSONObject(res.body).optJSONArray("outboundFlights") ?: return emptyList()
        return (0 until flights.length()).mapNotNull { i ->
            val f = flights.optJSONObject(i) ?: return@mapNotNull null
            val price = f.optJSONObject("price") ?: return@mapNotNull null
            val amount = price.optDouble("amount", Double.NaN)
            if (amount.isNaN() || amount <= 0 || f.optString("priceType") == "soldOut") return@mapNotNull null
            DayFare(amount, price.optString("currencyCode", "EUR"), f.optString("departureDate").take(10),
                f.optString("departureStation").takeIf { it.length == 3 }, f.optString("arrivalStation").takeIf { it.length == 3 })
        }
    }

    /** A legutóbbi „olcsó járatok” válasz eleje – diagnosztikához. */
    @Volatile
    var lastCheapRaw: String = ""

    /**
     * „Olcsó járatok innen” (a Wizz Air oldalán a „Cheap flights” ajánló): egyirányú legolcsóbb árak
     * minden úti célra a következő hónapokban. Hiba vagy ismeretlen formátum esetén üres lista.
     */
    internal fun cheapFlights(origin: String, months: Int = 6): List<DayFare> {
        val body = JSONObject().put("departureStation", origin).put("months", months).put("discountedOnly", false)
        var res = Http.request("${base(forceNew = false)}/search/CheapFlights", method = "POST", headers = apiHeaders(), body = body.toString())
        if (res.code == 401 || res.code == 403) {
            res = Http.request("${base(forceNew = true)}/search/CheapFlights", method = "POST", headers = apiHeaders(), body = body.toString())
        }
        lastCheapRaw = "HTTP ${res.code}: ${res.body.take(600)}"
        if (res.code !in 200..299) throw IOException("Wizz HTTP ${res.code}")
        val root = JSONObject(res.body)
        val items = root.optJSONArray("items") ?: root.optJSONArray("flights") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val f = items.optJSONObject(i) ?: return@mapNotNull null
            val to = f.optString("arrivalStation").takeIf { it.length == 3 } ?: return@mapNotNull null
            val price = f.optJSONObject("price") ?: f.optJSONObject("regularPrice") ?: return@mapNotNull null
            val amount = price.optDouble("amount", Double.NaN)
            if (amount.isNaN() || amount <= 0) return@mapNotNull null
            val date = listOf("std", "departureDate", "date").map { f.optString(it) }.firstOrNull { it.length >= 10 }
            DayFare(amount, price.optString("currencyCode", "EUR"), date?.let { if (it.length >= 16) it.take(16) else it.take(10) },
                f.optString("departureStation").takeIf { it.length == 3 } ?: origin, to)
        }
    }

    internal class DayFare(
        val amount: Double,
        val currency: String,
        val departure: String?,
        val from: String? = null,
        val to: String? = null,
    )

    /**
     * A nap legolcsóbb járata. A Wizz a napi legolcsóbb árat adja, mellette a nap összes
     * indulási idejét; időablaknál csak akkor használható az ár, ha épp a legolcsóbb járat
     * esik az ablakba (a többi járat árát nem ismerjük).
     */
    internal fun cheapest(flights: JSONArray?, date: String, depFrom: Int?, depTo: Int?): DayFare? {
        flights ?: return null
        var best: DayFare? = null
        var unknownTime = false
        for (i in 0 until flights.length()) {
            val f = flights.optJSONObject(i) ?: continue
            if (!f.optString("departureDate").startsWith(date)) continue
            val price = f.optJSONObject("price") ?: continue
            val amount = price.optDouble("amount", Double.NaN)
            if (amount.isNaN() || amount <= 0) continue
            val dates = f.optJSONArray("departureDates") ?: JSONArray()
            var time: String? = null
            for (j in 0 until dates.length()) {
                val d = dates.optJSONObject(j) ?: continue
                if (time == null || d.optBoolean("isCheapestOfTheDay")) time = trimTime(d.optString("date"))
                if (d.optBoolean("isCheapestOfTheDay")) break
            }
            if (depFrom != null || depTo != null) {
                val minutes = time?.let { t -> runCatching { java.time.LocalDateTime.parse(t) }.getOrNull() }
                    ?.let { it.hour * 60 + it.minute }
                if (minutes == null) {
                    unknownTime = true
                    continue
                }
                if (depFrom != null && minutes < depFrom * 60) continue
                if (depTo != null && minutes > depTo * 60) continue
            }
            if (best == null || amount < best.amount) {
                best = DayFare(
                    amount, price.optString("currencyCode", "EUR"), time,
                    f.optString("departureStation").takeIf { it.length == 3 },
                    f.optString("arrivalStation").takeIf { it.length == 3 },
                )
            }
        }
        // Időablaknál ismeretlen indulási idő: ez nem „nincs járat”, hanem hiányzó adat
        if (best == null && unknownTime) throw IOException(trs("a Wizz Air nem adta meg az indulás idejét", "Wizz Air didn't give the departure time", "Wizz Air hat die Abflugzeit nicht angegeben"))
        return best
    }
}
