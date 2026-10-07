package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.ceil

/** Egy forrás által talált legjobb ár, már a választott pénznemben. */
class FareResult(
    val price: Int,
    val airline: String?,
    val flightsUrl: String?,
    val source: String,
)

/** Olyan hiba, aminél nincs értelme a többi repülőtér-párral próbálkozni (pl. rossz kulcs). */
class FatalSourceException(message: String) : IOException(message)

/**
 * Árak az Ignav API-ból. Dokumentáció: https://ignav.com/docs
 * Egy kérés egy indulási és egy érkezési repülőteret kezel, ezért a
 * „minden repülőtér” választásnál több kérést küldünk (legfeljebb [MAX_PAIRS]).
 */
object Ignav {
    const val MAX_PAIRS = 6
    private const val MARKET = "HU"

    fun search(w: Watch, apiKey: String, currency: String): FareResult {
        val pairs = w.from.split(',').flatMap { o -> w.to.split(',').map { d -> o to d } }
            .take(MAX_PAIRS)
        var best: FareResult? = null
        var lastError: Exception? = null
        for ((origin, destination) in pairs) {
            try {
                val r = searchPair(w, origin, destination, apiKey, currency)
                if (r != null && (best == null || r.price < best.price)) best = r
            } catch (e: FatalSourceException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        return best ?: throw (lastError ?: IOException("Nincs találat ezekkel a beállításokkal"))
    }

    private fun searchPair(
        w: Watch,
        origin: String,
        destination: String,
        apiKey: String,
        currency: String,
    ): FareResult? {
        val body = JSONObject().apply {
            put("origin", origin)
            put("destination", destination)
            put("departure_date", w.outboundDate)
            w.returnDate?.let { put("return_date", it) }
            put("adults", w.adults)
            put("children", w.children)
            put("infants_in_seat", w.infantsInSeat)
            put("infants_on_lap", w.infantsOnLap)
            put(
                "cabin_class",
                when (w.travelClass) {
                    2 -> "premium_economy"
                    3 -> "business"
                    4 -> "first"
                    else -> "economy"
                },
            )
            when (w.stops) {
                1 -> put("max_stops", 0)
                2 -> put("max_stops", 1)
                3 -> put("max_stops", 2)
            }
            if (w.bags > 0) put("min_carry_on_bags", 1)
            if (w.checkedBag) put("min_checked_bags", 1)
            put("market", MARKET)
        }

        val path = if (w.isRoundTrip) "round-trip" else "one-way"
        val conn = URL("https://ignav.com/api/fares/$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 90_000
        conn.doOutput = true
        conn.setRequestProperty("X-Api-Key", apiKey)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()

            if (code !in 200..299) {
                val message = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                throw errorFor(code, message)
            }
            if (json == null) throw IOException("Hibás válasz az Ignavtól")

            val itineraries = json.optJSONArray("itineraries") ?: JSONArray()
            var bestAmount = Double.MAX_VALUE
            var bestCurrency = ""
            var bestItinerary: JSONObject? = null
            for (i in 0 until itineraries.length()) {
                val itin = itineraries.optJSONObject(i) ?: continue
                val price = itin.optJSONObject("price") ?: continue
                val amount = price.optDouble("amount", Double.NaN)
                if (amount.isNaN() || amount <= 0) continue
                val cur = price.optString("currency", currency)
                val inTarget = Rates.convert(amount, cur, currency)
                if (inTarget < bestAmount) {
                    bestAmount = inTarget
                    bestCurrency = cur
                    bestItinerary = itin
                }
            }
            val itinerary = bestItinerary ?: return null
            return FareResult(
                price = ceil(bestAmount).toInt(),
                airline = carriersOf(itinerary),
                flightsUrl = googleFlightsUrl(w, origin, destination),
                source = if (bestCurrency.isNotBlank() && bestCurrency != currency) "Ignav ($bestCurrency-ből átváltva)" else "Ignav",
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun carriersOf(itinerary: JSONObject): String? {
        val names = listOf("outbound", "inbound").mapNotNull { leg ->
            itinerary.optJSONObject(leg)?.optString("carrier")?.takeIf { it.isNotBlank() && it != "null" }
        }.distinct()
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun errorFor(code: Int, message: String?): IOException = when (code) {
        401, 403 -> FatalSourceException("Érvénytelen Ignav-kulcs")
        402 -> FatalSourceException("Elfogyott az Ignav-keret")
        429 -> FatalSourceException("Túl sok Ignav-kérés, később újrapróbálom")
        else -> IOException(message ?: "Ignav-hiba (HTTP $code)")
    }

    /** Google Flights-keresés linkje, hogy a találatot meg lehessen nyitni. */
    fun googleFlightsUrl(w: Watch, origin: String, destination: String): String {
        val q = buildString {
            append("Flights from $origin to $destination on ${w.outboundDate}")
            w.returnDate?.let { append(" through $it") }
        }
        return "https://www.google.com/travel/flights?hl=hu&q=" + URLEncoder.encode(q, "UTF-8")
    }
}

/** Árfolyamok az Európai Központi Bank adataiból (frankfurter.dev, kulcs nélkül). */
object Rates {
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    private var cache: Map<String, Double>? = null
    private var fetchedAt = 0L

    @Synchronized
    fun convert(amount: Double, from: String, to: String): Double {
        if (from.equals(to, ignoreCase = true)) return amount
        val rates = rates()
        val fromRate = rates[from.uppercase()] ?: throw IOException("Ismeretlen pénznem: $from")
        val toRate = rates[to.uppercase()] ?: throw IOException("Ismeretlen pénznem: $to")
        return amount / fromRate * toRate
    }

    private fun rates(): Map<String, Double> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - fetchedAt < MAX_AGE_MS) return it }
        val conn = URL("https://api.frankfurter.dev/v1/latest?base=EUR").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) {
                cache?.let { return it }
                throw IOException("Nem sikerült lekérni az árfolyamot")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val obj = json.getJSONObject("rates")
            val map = mutableMapOf("EUR" to 1.0)
            obj.keys().forEach { k -> map[k] = obj.getDouble(k) }
            cache = map
            fetchedAt = now
            return map
        } finally {
            conn.disconnect()
        }
    }
}
