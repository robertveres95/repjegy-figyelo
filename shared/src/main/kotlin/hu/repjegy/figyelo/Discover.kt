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
        var results: List<Result>? = null
    }

    /** Út típusa: csak oda, hosszú hétvége (2–4 éj), egy hét (5–9 éj). */
    val TRIP_TYPES = listOf(
        0 to "Csak oda",
        1 to "Hosszú hétvége (2–4 éjszaka)",
        2 to "Egy hét (5–9 éjszaka)",
    )

    fun nightsFor(tripType: Int): IntRange? = when (tripType) {
        1 -> 2..4
        2 -> 5..9
        else -> null
    }

    private val monthFormat = DateTimeFormatter.ofPattern("yyyy. LLLL", HU)

    /** Időszakok: a következő 30 nap, majd a következő 6 hónap. */
    fun periods(today: LocalDate = LocalDate.now()): List<Pair<Int, String>> =
        listOf(0 to "A következő 30 nap") + (0..5).mapNotNull { i ->
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
        if (origins.isEmpty()) throw IOException("Válassz indulási repteret")
        val all = mutableListOf<Result>()
        var lastError: Exception? = null
        for (origin in origins) {
            try {
                all += query(origin, start, end, nights, maxPrice, currency)
            } catch (e: Exception) {
                lastError = e
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
        if (res.code == 429 || res.code == 403) throw IOException("A Ryanair ideiglenesen blokkolta a lekérdezést")
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")
        return parse(res.body, origin, currency)
    }

    /** A Ryanair-válasz feldolgozása (külön, hogy tesztelhető legyen). */
    internal fun parse(body: String, origin: String, currency: String): List<Result> {
        val fares = JSONObject(body).optJSONArray("fares") ?: throw IOException("Váratlan Ryanair-válasz")
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
                // Ország magyarul (a Ryanair angolul adja); ha nincs kód, marad az eredeti
                country = arr.optJSONObject("city")?.optString("countryCode")?.takeIf { it.length == 2 }
                    ?.let { java.util.Locale("", it.uppercase()).getDisplayCountry(HU).takeIf { n -> n.isNotBlank() } }
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

    private val dayFormat = DateTimeFormatter.ofPattern("MMM d., EEE", HU)

    fun describeDates(r: Result): String {
        val o = r.departure?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val ret = r.returnDeparture?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val first = o?.let { "${it.format(dayFormat)} %02d:%02d".format(it.hour, it.minute) } ?: "?"
        return if (ret != null) "$first → ${ret.format(dayFormat)} (${ChronoUnit.DAYS.between(o?.toLocalDate() ?: ret.toLocalDate(), ret.toLocalDate())} éj)"
        else first
    }
}
