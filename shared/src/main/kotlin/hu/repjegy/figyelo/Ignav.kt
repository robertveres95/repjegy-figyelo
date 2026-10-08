package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil

/**
 * Árak az Ignav API-ból (opcionális, kulcsos tartalék). Dokumentáció: https://ignav.com/docs
 * Egy kérés egy indulási és egy érkezési repülőteret kezel.
 */
object Ignav {
    const val NAME = "Ignav"
    const val MAX_PAIRS = 6
    private const val MARKET = "HU"

    fun search(w: Watch, apiKey: String, currency: String): List<Offer> {
        val pairs = w.from.split(',').flatMap { o -> w.to.split(',').map { d -> o to d } }.take(MAX_PAIRS)
        val offers = mutableListOf<Offer>()
        var lastError: Exception? = null
        var anySuccess = false
        for ((origin, destination) in pairs) {
            try {
                offers += searchPair(w, origin, destination, apiKey, currency)
                anySuccess = true
            } catch (e: FatalSourceException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!anySuccess && lastError != null) throw lastError
        return offers
    }

    private fun searchPair(
        w: Watch,
        origin: String,
        destination: String,
        apiKey: String,
        currency: String,
    ): List<Offer> {
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
        val res = Http.request(
            "https://ignav.com/api/fares/$path",
            method = "POST",
            headers = mapOf(
                "X-Api-Key" to apiKey,
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = body.toString(),
            timeoutMs = 90_000,
        )
        val json = runCatching { JSONObject(res.body) }.getOrNull()
        if (res.code !in 200..299) {
            val message = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            throw when (res.code) {
                401, 403 -> FatalSourceException("érvénytelen kulcs")
                402 -> FatalSourceException("elfogyott a keret")
                429 -> FatalSourceException("túl sok kérés, később újrapróbálom")
                else -> IOException(message ?: "HTTP ${res.code}")
            }
        }
        if (json == null) throw IOException("hibás válasz")

        val itineraries = json.optJSONArray("itineraries") ?: JSONArray()
        val url = GoogleFlights.searchUrl(w.copy(from = origin, to = destination), currency)
        val offers = mutableListOf<Offer>()
        for (i in 0 until itineraries.length()) {
            val itin = itineraries.optJSONObject(i) ?: continue
            val price = itin.optJSONObject("price") ?: continue
            val amount = price.optDouble("amount", Double.NaN)
            if (amount.isNaN() || amount <= 0) continue
            val inTarget = runCatching { Rates.convert(amount, price.optString("currency", currency), currency) }.getOrNull() ?: continue
            val outbound = itin.optJSONObject("outbound")
            val inbound = itin.optJSONObject("inbound")
            val outSegs = outbound?.optJSONArray("segments")
            val inSegs = inbound?.optJSONArray("segments")
            val carriers = listOfNotNull(outbound, inbound)
                .mapNotNull { it.optString("carrier").takeIf { c -> c.isNotBlank() && c != "null" } }
                .distinct()
            offers += Offer(
                price = ceil(inTarget).toInt(),
                source = NAME,
                airline = carriers.takeIf { it.isNotEmpty() }?.joinToString(", "),
                fromCode = origin,
                toCode = destination,
                departure = segmentTime(outSegs?.optJSONObject(0), "depart"),
                arrival = segmentTime(outSegs?.let { it.optJSONObject(it.length() - 1) }, "arriv"),
                stops = outSegs?.let { it.length() - 1 },
                returnDeparture = segmentTime(inSegs?.optJSONObject(0), "depart"),
                returnArrival = segmentTime(inSegs?.let { it.optJSONObject(it.length() - 1) }, "arriv"),
                returnStops = inSegs?.let { it.length() - 1 },
                url = url,
                bagsIncluded = true,
            )
        }
        return offers
    }

    /**
     * Helyi indulási/érkezési idő kiolvasása egy szakaszból. A pontos mezőnevet nem
     * rögzítjük: az első olyan mezőt vesszük, aminek a neve tartalmazza a kulcsszót
     * (és lehetőleg a "local" szót), az értéke pedig dátum-idő.
     */
    private fun segmentTime(segment: JSONObject?, keyword: String): String? {
        segment ?: return null
        val candidates = mutableListOf<Pair<Int, String>>()
        fun scan(obj: JSONObject, prefix: String) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val full = "$prefix$k".lowercase()
                when (val v = obj.opt(k)) {
                    is JSONObject -> scan(v, "$full.")
                    is String -> if (full.contains(keyword) && Regex("""^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}""").containsMatchIn(v)) {
                        val score = when {
                            full.contains("local") -> 0
                            full.contains("utc") -> 2
                            else -> 1
                        }
                        candidates += score to v.replace(' ', 'T').substring(0, 16)
                    }
                }
            }
        }
        scan(segment, "")
        return candidates.minByOrNull { it.first }?.second
    }
}

/** Árfolyamok az Európai Központi Bank adataiból (frankfurter.dev, kulcs nélkül). */
object Rates {
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /** Elsődleges: EKB-árfolyamok (29 pénznem, köztük HUF). */
    private val ecb = Source("https://api.frankfurter.dev/v1/latest?base=EUR")

    /**
     * Tartalék a ritkább pénznemekhez: a Wizz Air az indulási állomás pénznemében áraz
     * (pl. Tirana ALL, Szkopje MKD, Kutaiszi GEL), ezek nincsenek az EKB-listában.
     */
    private val wide = Source("https://open.er-api.com/v6/latest/EUR")

    @Synchronized
    fun convert(amount: Double, from: String, to: String): Double {
        if (from.equals(to, ignoreCase = true)) return amount
        val f = from.uppercase()
        val t = to.uppercase()
        // Mindkét árfolyam ugyanabból a forrásból jöjjön, hogy egymáshoz illeszkedjenek
        val primary = runCatching { ecb.rates() }.getOrNull()
        if (primary != null && f in primary && t in primary) return amount / primary.getValue(f) * primary.getValue(t)
        val backup = runCatching { wide.rates() }.getOrNull()
        if (backup != null && f in backup && t in backup) return amount / backup.getValue(f) * backup.getValue(t)
        if (primary == null && backup == null) throw IOException("Nem sikerült lekérni az árfolyamot")
        throw IOException("Ismeretlen pénznem: ${if (primary?.containsKey(f) == true || backup?.containsKey(f) == true) t else f}")
    }

    private class Source(val url: String) {
        private var cache: Map<String, Double>? = null
        private var fetchedAt = 0L

        fun rates(): Map<String, Double> {
            val now = System.currentTimeMillis()
            cache?.let { if (now - fetchedAt < MAX_AGE_MS) return it }
            // Hálózati hiba esetén is jó a korábbi (kicsit régebbi) árfolyam
            val res = try {
                Http.request(url, timeoutMs = 20_000)
            } catch (e: IOException) {
                cache?.let { return it }
                throw e
            }
            if (res.code !in 200..299) {
                cache?.let { return it }
                throw IOException("Nem sikerült lekérni az árfolyamot (HTTP ${res.code})")
            }
            val obj = JSONObject(res.body).getJSONObject("rates")
            val map = mutableMapOf("EUR" to 1.0)
            obj.keys().forEach { k ->
                val v = obj.optDouble(k, Double.NaN)
                if (v.isFinite() && v > 0) map[k.uppercase()] = v
            }
            if (map.size < 2) {
                cache?.let { return it }
                throw IOException("Hibás árfolyam-válasz")
            }
            cache = map
            fetchedAt = now
            return map
        }
    }
}
