package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Google Flights-árak a SerpApi-n keresztül (opcionális, kulcsos tartalék).
 * Dokumentáció: https://serpapi.com/google-flights-api
 */
object SerpApi {
    const val NAME = "SerpApi"

    fun search(w: Watch, apiKey: String, currency: String): List<Offer> {
        val params = linkedMapOf(
            "engine" to "google_flights",
            "departure_id" to w.from,
            "arrival_id" to w.to,
            "outbound_date" to w.outboundDate,
            "type" to if (w.isRoundTrip) "1" else "2",
            "travel_class" to w.travelClass.toString(),
            "adults" to w.adults.toString(),
            "children" to w.children.toString(),
            "infants_in_seat" to w.infantsInSeat.toString(),
            "infants_on_lap" to w.infantsOnLap.toString(),
            // A poggyászdíjat a Google-alapú ár csak részben tartalmazza: becsléssel adjuk hozzá (Fees)
            "bags" to "0",
            "stops" to w.stops.toString(),
            "sort_by" to "2",
            "currency" to currency,
            "hl" to "hu",
            "gl" to "hu", // magyarországi árakat kérünk (különben amerikai „piacról” keres)
            "api_key" to apiKey,
        )
        w.returnDate?.let { params["return_date"] = it }

        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val res = Http.request("https://serpapi.com/search.json?$query", timeoutMs = 90_000)
        val json = runCatching { JSONObject(res.body) }.getOrNull()
            ?: throw IOException("Hibás válasz (HTTP ${res.code})")
        if (json.has("error")) {
            val message = json.getString("error")
            if (message.contains("hasn't returned any results", ignoreCase = true)) return emptyList()
            throw FatalSourceException(translateError(message))
        }

        val flightsUrl = json.optJSONObject("search_metadata")
            ?.optString("google_flights_url")?.takeIf { it.isNotBlank() }
            ?: GoogleFlights.searchUrl(w, currency)

        val offers = mutableListOf<Offer>()
        for (key in listOf("best_flights", "other_flights")) {
            val arr = json.optJSONArray(key) ?: JSONArray()
            for (i in 0 until arr.length()) {
                val option = arr.optJSONObject(i) ?: continue
                val rawPrice = option.optDouble("price", -1.0)
                if (!rawPrice.isFinite() || rawPrice <= 0) continue
                val price = kotlin.math.ceil(rawPrice).toInt()
                val flights = option.optJSONArray("flights") ?: JSONArray()
                val first = flights.optJSONObject(0)
                val last = flights.optJSONObject(flights.length() - 1)
                val airlines = (0 until flights.length())
                    .mapNotNull { flights.optJSONObject(it)?.optString("airline")?.takeIf(String::isNotBlank) }
                    .distinct()
                offers += Fees.apply(Offer(
                    price = price,
                    source = NAME,
                    airline = airlines.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    fromCode = first?.optJSONObject("departure_airport")?.optString("id"),
                    toCode = last?.optJSONObject("arrival_airport")?.optString("id"),
                    departure = toIso(first?.optJSONObject("departure_airport")?.optString("time")),
                    arrival = toIso(last?.optJSONObject("arrival_airport")?.optString("time")),
                    stops = if (flights.length() > 0) flights.length() - 1 else null,
                    url = flightsUrl,
                ), w, currency, includeInfants = false)
            }
        }
        return offers
    }

    /** "2026-11-05 06:25" → "2026-11-05T06:25" */
    private fun toIso(raw: String?): String? =
        raw?.takeIf { it.length >= 16 }?.replace(' ', 'T')?.substring(0, 16)

    private fun translateError(message: String): String = when {
        message.contains("Invalid API key", ignoreCase = true) -> "érvénytelen kulcs"
        message.contains("run out of searches", ignoreCase = true) -> "elfogyott a havi keret"
        else -> message
    }
}
