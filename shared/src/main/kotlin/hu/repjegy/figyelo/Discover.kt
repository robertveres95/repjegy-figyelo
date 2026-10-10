package hu.repjegy.figyelo

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * „Bárhová” keresés: hova lehet a legolcsóbban eljutni egy reptérről egy időszakban.
 * A Ryanair kulcs nélküli árkeresője célállomás nélkül is működik (a legolcsóbb járat
 * célállomásonként), így ez most csak a Ryanair járatait mutatja.
 */
object Discover {

    /** Az utolsó keresés (a képernyő elhagyása után visszatérve is látszik). */
    internal object Memory {
        var period = 0
        var tripType = 1
        var adults = 1
        var maxPrice = ""
        var fromCodes: String? = null
        var currency: String? = null
        var results: List<Result>? = null
        /** A futó keresés: a képernyő elhagyása után visszatérve is látszik, és egy új keresés leállítja. */
        var job: kotlinx.coroutines.Job? = null
        val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
        val lastResults = kotlinx.coroutines.flow.MutableStateFlow<List<Result>?>(null)
        val lastError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }

    /** Út típusa: csak oda, hosszú hétvége (2–4 éj), egy hét (5–9 éj). */
    val TRIP_TYPES: List<Pair<Int, String>> get() = listOf(
        0 to trs("Csak oda", "One way", "Nur Hinflug"),
        1 to trs("Hosszú hétvége (2–4 éjszaka)", "Long weekend (2–4 nights)", "Langes Wochenende (2–4 Nächte)"),
        2 to trs("Egy hét (5–9 éjszaka)", "A week (5–9 nights)", "Eine Woche (5–9 Nächte)"),
    )

    fun nightsFor(tripType: Int): IntRange? = when (tripType) {
        1 -> 2..4
        2 -> 5..9
        else -> null
    }

    private val monthFormat: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern(if (Lang.hu) "yyyy. LLLL" else "LLLL yyyy", Lang.locale)

    /** Időszakok: a következő 30 nap, majd a következő 12 hónap (egy évre előre). */
    fun periods(today: LocalDate = LocalDate.now()): List<Pair<Int, String>> =
        listOf(0 to trs("A következő 30 nap", "The next 30 days", "Die nächsten 30 Tage")) + (0..11).mapNotNull { i ->
            // A hónap utolsó napján a folyó hónapból már nem maradt keresendő nap
            val (start, end) = periodRange(i + 1, today)
            if (start.isAfter(end)) return@mapNotNull null
            val m = YearMonth.from(today).plusMonths(i.toLong())
            (i + 1) to m.format(monthFormat).replaceFirstChar { it.uppercase() }
        }

    fun periodRange(period: Int, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> {
        val start = today.plusDays(1)
        if (period <= 0) return start to today.plusDays(30)
        val m = YearMonth.from(today).plusMonths((period - 1).toLong())
        val first = m.atDay(1)
        return maxOf(first, start) to m.atEndOfMonth()
    }

    data class Result(
        val code: String,
        val city: String,
        val country: String,
        val pricePerPerson: Int,
        val departure: String?,
        val returnDeparture: String?,
        val fromCode: String,
    ) {
        /** A város és az ország a felület nyelvén (nyelvváltás után is). */
        val shownCity: String get() = Airports.cityName(code) ?: city
        val shownCountry: String get() = Airports.countryName(code) ?: country
        val outDate: LocalDate? get() = departure?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val retDate: LocalDate? get() = returnDeparture?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    /**
     * Lekérdezés: [from] lehet több reptér is (pl. London), legfeljebb 3-at kérdezünk.
     * [maxPrice] egy főre, a választott pénznemben (null = nincs felső határ).
     */
    fun search(
        from: String,
        period: Int,
        tripType: Int,
        maxPrice: Int?,
        currency: String,
        today: LocalDate = LocalDate.now(),
    ): List<Result> {
        val (start, end) = periodRange(period, today)
        if (start.isAfter(end)) return emptyList()
        val nights = nightsFor(tripType)
        // Több repteres városnál (pl. London) mindegyiket megkérdezzük: a Ryanair-bázis gyakran a lista végén van
        val origins = from.split(',').map { it.trim() }.filter { it.length == 3 }.take(6)
        if (origins.isEmpty()) throw IOException(trs("Válassz indulási repteret", "Choose a departure airport", "Wähle einen Abflughafen"))
        val all = mutableListOf<Result>()
        var lastError: Exception? = null
        for (origin in origins) {
            try {
                all += query(origin, start, end, nights, maxPrice, currency)
            } catch (e: Exception) {
                lastError = e
            }
        }
        // Wizz Air „olcsó járatok” (csak egyirányú utaknál): további úti célok, amerre a Ryanair nem repül
        if (nights == null && Store.settings.value.wizzOn) {
            for (origin in origins.take(3)) {
                runCatching { all += wizz(origin, start, end, currency) }
            }
        }
        if (all.isEmpty() && lastError != null) throw lastError
        return all
            .filter { r -> nights == null || r.nights()?.let { it in nights } == true }
            .groupBy { it.code }
            .map { (_, list) -> list.minBy { it.pricePerPerson } }
            .filter { maxPrice == null || it.pricePerPerson <= maxPrice }
            .sortedBy { it.pricePerPerson }
            .take(60)
    }

    /** A Wizz Air ajánlói egy reptérről, a keresett időszakra szűrve. */
    private fun wizz(origin: String, start: LocalDate, end: LocalDate, currency: String): List<Result> =
        WizzAir.cheapFlights(origin).mapNotNull { f ->
            val day = f.departure?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            if (day.isBefore(start) || day.isAfter(end)) return@mapNotNull null
            val to = f.to ?: return@mapNotNull null
            val price = runCatching { Rates.convert(f.amount, f.currency, currency) }.getOrNull() ?: return@mapNotNull null
            Result(
                code = to,
                city = Airports.cityName(to) ?: to,
                country = Airports.countryName(to) ?: "",
                pricePerPerson = ceil(price).toInt(),
                departure = f.departure?.takeIf { it.length >= 16 } ?: "${day}T00:00",
                returnDeparture = null,
                fromCode = f.from ?: origin,
            )
        }

    internal fun Result.nights(): Int? {
        val o = outDate ?: return null
        val r = retDate ?: return null
        return ChronoUnit.DAYS.between(o, r).toInt()
    }

    private fun query(
        origin: String,
        start: LocalDate,
        end: LocalDate,
        nights: IntRange?,
        maxPrice: Int?,
        currency: String,
    ): List<Result> {
        val params = linkedMapOf(
            "departureAirportIataCode" to origin,
            "outboundDepartureDateFrom" to start.toString(),
            "outboundDepartureDateTo" to end.toString(),
            "currency" to currency,
            "language" to "en",
            "market" to "en-gb",
        )
        if (nights != null) {
            params["inboundDepartureDateFrom"] = start.plusDays(nights.first.toLong()).toString()
            params["inboundDepartureDateTo"] = end.plusDays(nights.last.toLong()).toString()
            params["durationFrom"] = nights.first.toString()
            params["durationTo"] = nights.last.toString()
        }
        if (maxPrice != null && nights == null) params["priceValueTo"] = maxPrice.toString()
        val endpoint = if (nights != null) "roundTripFares" else "oneWayFares"
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val res = Http.request(
            "https://services-api.ryanair.com/farfnd/v4/$endpoint?$query",
            headers = mapOf("Accept" to "application/json"),
        )
        if (res.code == 429 || res.code == 403) throw IOException(trs("A Ryanair ideiglenesen blokkolta a lekérdezést", "Ryanair has temporarily blocked the search", "Ryanair hat die Suche vorübergehend blockiert"))
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")
        return parse(res.body, origin, currency)
    }

    /** A Ryanair-válasz feldolgozása (külön, hogy tesztelhető legyen). */
    internal fun parse(body: String, origin: String, currency: String): List<Result> {
        val fares = JSONObject(body).optJSONArray("fares") ?: throw IOException(trs("Váratlan Ryanair-válasz", "Unexpected Ryanair response", "Unerwartete Ryanair-Antwort"))
        val out = mutableListOf<Result>()
        for (i in 0 until fares.length()) {
            val fare = fares.optJSONObject(i) ?: continue
            val ob = fare.optJSONObject("outbound") ?: continue
            val ib = fare.optJSONObject("inbound")
            val priceObj = fare.optJSONObject("summary")?.optJSONObject("price") ?: ob.optJSONObject("price") ?: continue
            val value = priceObj.optDouble("value", Double.NaN)
            if (!value.isFinite() || value <= 0) continue
            val cur = priceObj.optString("currencyCode", currency)
            val converted = runCatching { Rates.convert(value, cur, currency) }.getOrNull() ?: continue
            val arr = ob.optJSONObject("arrivalAirport") ?: continue
            val code = arr.optString("iataCode").takeIf { it.length == 3 } ?: continue
            val known = Airports.placeFor(code, null)
            val city = known.city.takeIf { it.isNotBlank() && it != code }
                ?: arr.optJSONObject("city")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: arr.optString("name", code)
            out += Result(
                code = code,
                city = city,
                // Ország a felület nyelvén (a Ryanair angolul adja); ha nincs kód, marad az eredeti
                country = arr.optJSONObject("city")?.optString("countryCode")?.takeIf { it.length == 2 }
                    ?.let { cc -> java.util.Locale("", cc.uppercase()).getDisplayCountry(Lang.locale).takeIf { n -> n.isNotBlank() && !n.equals(cc, true) } }
                    ?: arr.optString("countryName", ""),
                pricePerPerson = ceil(converted).toInt(),
                departure = ob.optString("departureDate").takeIf { it.length >= 16 }?.substring(0, 16),
                returnDeparture = ib?.optString("departureDate")?.takeIf { it.length >= 16 }?.substring(0, 16),
                fromCode = ob.optJSONObject("departureAirport")?.optString("iataCode")?.takeIf { it.length == 3 } ?: origin,
            )
        }
        return out
    }

    /** Új figyelés sablonja egy találatból (a célár a mostani ár, utasszámmal). */
    fun templateFor(r: Result, adults: Int, fromLabel: String?): Watch {
        val out = r.outDate ?: LocalDate.now().plusDays(30)
        return Watch(
            id = "",
            from = r.fromCode,
            to = r.code,
            fromLabel = fromLabel,
            toLabel = r.city,
            outboundDate = out.toString(),
            returnDate = r.retDate?.toString(),
            travelClass = 1,
            adults = adults,
            children = 0,
            infantsInSeat = 0,
            infantsOnLap = 0,
            bags = 0,
            stops = 0,
            targetPrice = r.pricePerPerson * adults,
            notify = true,
        )
    }

    private val dayFormat: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern(
            when (Lang.code) {
                Lang.HU_CODE -> "MMM d., EEE"
                Lang.DE_CODE -> "EEE, d. MMM"
                else -> "d MMM, EEE"
            },
            Lang.locale,
        )

    fun describeDates(r: Result): String {
        val o = r.departure?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val ret = r.returnDeparture?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val first = o?.let { "${it.format(dayFormat)} %02d:%02d".format(it.hour, it.minute) } ?: "?"
        if (ret == null) return first
        val n = ChronoUnit.DAYS.between(o?.toLocalDate() ?: ret.toLocalDate(), ret.toLocalDate())
        return "$first → ${ret.format(dayFormat)} (" + trs("$n éj", if (n == 1L) "1 night" else "$n nights", if (n == 1L) "1 Nacht" else "$n Nächte") + ")"
    }
}
