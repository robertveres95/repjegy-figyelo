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
internal fun pairsOf(w: Watch, max: Int = MAX_PAIRS): List<Pair<String, String>> {
    val from = w.from.split(',')
    val to = w.to.split(',')
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
    if (w.seatedPassengers > 1) parts += "becsült: ${w.seatedPassengers} × egy fő ára"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun <T> searchPairs(w: Watch, maxPairs: Int, block: (String, String) -> List<T>): List<T> {
    val results = mutableListOf<T>()
    var lastError: Exception? = null
    var anySuccess = false
    for ((origin, destination) in pairsOf(w, maxPairs)) {
        try {
            results += block(origin, destination)
            anySuccess = true
        } catch (e: FatalSourceException) {
            throw e
        } catch (e: Exception) {
            lastError = e
        }
    }
    if (!anySuccess && lastError != null) throw lastError
    return results
}

// ---------------------------------------------------------------- Ryanair

/** Ryanair saját, kulcs nélküli árkereső felülete (services-api.ryanair.com/farfnd). */
object Ryanair {
    const val NAME = "Ryanair"

    fun search(w: Watch, currency: String, maxPairs: Int = MAX_PAIRS): List<Offer> {
        if (w.travelClass != 1) throw SkipSourceException("csak turista osztály")
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
        if (res.code == 429 || res.code == 403) throw FatalSourceException("A Ryanair ideiglenesen blokkolta a lekérdezést")
        if (res.code == 404) return emptyList()
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")

        val root = JSONObject(res.body)
        // Hiányzó „fares” mező formátumváltozást jelez – ne „nincs járat” legyen belőle
        val fares = root.optJSONArray("fares") ?: throw IOException("Váratlan Ryanair-válasz (változhatott a formátum)")
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
                fromCode = out.optJSONObject("departureAirport")?.optString("iataCode") ?: origin,
                toCode = out.optJSONObject("arrivalAirport")?.optString("iataCode") ?: destination,
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
    private var sessionAt = 0L

    fun search(w: Watch, currency: String, maxPairs: Int = MAX_PAIRS): List<Offer> {
        if (w.travelClass != 1) throw SkipSourceException("csak turista osztály")
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
        if (res.code == 429) throw FatalSourceException("a Wizz Air bot-védelme blokkolta")
        if (res.code == 400 || res.code == 401 || res.code == 403) {
            // Nincs ilyen útvonal: ezt nem érdemes új munkamenettel újrapróbálni
            if (res.code == 400 && res.body.contains("validationCodes")) return emptyList()
            if (retry) return searchPair(w, origin, destination, currency, retry = false)
            throw FatalSourceException("a Wizz Air elutasította a kérést (HTTP ${res.code})")
        }
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")

        val json = JSONObject(res.body)
        if (!json.has("outboundFlights") && !json.has("returnFlights")) {
            throw IOException("Váratlan Wizz Air-válasz (változhatott a formátum)")
        }
        val outbound = cheapest(json.optJSONArray("outboundFlights"), w.outboundDate, w.depFrom, w.depTo) ?: return emptyList()
        val inbound = w.returnDate?.let { cheapest(json.optJSONArray("returnFlights"), it, null, null) ?: return emptyList() }

        val perPerson = Rates.convert(outbound.amount, outbound.currency, currency) +
            (inbound?.let { Rates.convert(it.amount, it.currency, currency) } ?: 0.0)
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
                    ?.let { it.hour * 60 + it.minute } ?: continue
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
        return best
    }
}
