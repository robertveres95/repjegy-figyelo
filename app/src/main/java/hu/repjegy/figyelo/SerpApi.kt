package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Flights-árak lekérdezése a SerpApi-n keresztül.
 * Dokumentáció: https://serpapi.com/google-flights-api
 */
object SerpApi {

    class Result(val price: Int, val airline: String?, val flightsUrl: String?)

    fun search(w: Watch, apiKey: String, currency: String): Result {
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
            "bags" to w.bags.toString(),
            "stops" to w.stops.toString(),
            "sort_by" to "2",
            "currency" to currency,
            "hl" to "hu",
            "api_key" to apiKey,
        )
        w.returnDate?.let { params["return_date"] = it }

        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL("https://serpapi.com/search.json?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 90_000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IOException("Hibás válasz a szervertől (HTTP $code)")
            if (json.has("error")) throw IOException(translateError(json.getString("error")))

            var best: JSONObject? = null
            for (key in listOf("best_flights", "other_flights")) {
                val arr = json.optJSONArray(key) ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val option = arr.optJSONObject(i) ?: continue
                    val price = option.optInt("price", -1)
                    if (price > 0 && (best == null || price < best.optInt("price"))) best = option
                }
            }

            val flightsUrl = json.optJSONObject("search_metadata")
                ?.optString("google_flights_url")?.takeIf { it.isNotBlank() }

            if (best != null) {
                return Result(best.getInt("price"), airlinesOf(best), flightsUrl)
            }
            val lowest = json.optJSONObject("price_insights")?.optInt("lowest_price", -1) ?: -1
            if (lowest > 0) return Result(lowest, null, flightsUrl)
            throw IOException("Nincs találat ezekkel a beállításokkal")
        } finally {
            conn.disconnect()
        }
    }

    private fun airlinesOf(option: JSONObject): String? {
        val flights = option.optJSONArray("flights") ?: return null
        val names = (0 until flights.length())
            .mapNotNull { flights.optJSONObject(it)?.optString("airline")?.takeIf(String::isNotBlank) }
            .distinct()
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun translateError(message: String): String = when {
        message.contains("Invalid API key", ignoreCase = true) -> "Érvénytelen SerpApi-kulcs"
        message.contains("run out of searches", ignoreCase = true) -> "Elfogyott a havi keresési keret"
        message.contains("hasn't returned any results", ignoreCase = true) ->
            "Nincs találat ezekkel a beállításokkal"
        else -> message
    }
}
