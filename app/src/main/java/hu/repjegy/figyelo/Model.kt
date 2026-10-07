package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class PricePoint(val time: Long, val price: Int)

/** Egy figyelt út a keresési beállításokkal és az utolsó eredményekkel. */
data class Watch(
    val id: String,
    // Keresési beállítások
    val from: String,
    val to: String,
    val outboundDate: String,          // YYYY-MM-DD
    val returnDate: String?,           // null = csak oda
    val travelClass: Int,              // 1 turista, 2 prémium turista, 3 business, 4 első
    val adults: Int,
    val children: Int,
    val infantsInSeat: Int,
    val infantsOnLap: Int,
    val bags: Int,                     // kézipoggyászok száma összesen
    val stops: Int,                    // 0 mindegy, 1 közvetlen, 2 max 1, 3 max 2 átszállás
    val targetPrice: Int,
    val notify: Boolean,
    // Eredmények
    val lastPrice: Int? = null,
    val lowestPrice: Int? = null,
    val lastChecked: Long? = null,
    val lastError: String? = null,
    val lastNotifiedPrice: Int? = null,
    val bestAirline: String? = null,
    val flightsUrl: String? = null,
    val history: List<PricePoint> = emptyList(),
) {
    val isRoundTrip: Boolean get() = returnDate != null

    fun isExpired(today: LocalDate = LocalDate.now()): Boolean =
        runCatching { LocalDate.parse(outboundDate).isBefore(today) }.getOrDefault(false)

    /** Ha ez változik, a korábbi árak már nem összehasonlíthatók. */
    fun searchKey(): String = listOf(
        from, to, outboundDate, returnDate, travelClass, adults, children,
        infantsInSeat, infantsOnLap, bags, stops,
    ).joinToString("|")

    fun clearResults(): Watch = copy(
        lastPrice = null, lowestPrice = null, lastChecked = null, lastError = null,
        lastNotifiedPrice = null, bestAirline = null, flightsUrl = null, history = emptyList(),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("from", from)
        put("to", to)
        put("outboundDate", outboundDate)
        putOpt("returnDate", returnDate)
        put("travelClass", travelClass)
        put("adults", adults)
        put("children", children)
        put("infantsInSeat", infantsInSeat)
        put("infantsOnLap", infantsOnLap)
        put("bags", bags)
        put("stops", stops)
        put("targetPrice", targetPrice)
        put("notify", notify)
        putOpt("lastPrice", lastPrice)
        putOpt("lowestPrice", lowestPrice)
        putOpt("lastChecked", lastChecked)
        putOpt("lastError", lastError)
        putOpt("lastNotifiedPrice", lastNotifiedPrice)
        putOpt("bestAirline", bestAirline)
        putOpt("flightsUrl", flightsUrl)
        val h = JSONArray()
        history.forEach { h.put(JSONArray().put(it.time).put(it.price)) }
        put("history", h)
    }

    companion object {
        fun fromJson(o: JSONObject): Watch {
            val h = o.optJSONArray("history") ?: JSONArray()
            val history = (0 until h.length()).mapNotNull { i ->
                h.optJSONArray(i)?.let { PricePoint(it.getLong(0), it.getInt(1)) }
            }
            return Watch(
                id = o.getString("id"),
                from = o.getString("from"),
                to = o.getString("to"),
                outboundDate = o.getString("outboundDate"),
                returnDate = o.stringOrNull("returnDate"),
                travelClass = o.optInt("travelClass", 1),
                adults = o.optInt("adults", 1),
                children = o.optInt("children", 0),
                infantsInSeat = o.optInt("infantsInSeat", 0),
                infantsOnLap = o.optInt("infantsOnLap", 0),
                bags = o.optInt("bags", 0),
                stops = o.optInt("stops", 0),
                targetPrice = o.optInt("targetPrice", 0),
                notify = o.optBoolean("notify", true),
                lastPrice = o.intOrNull("lastPrice"),
                lowestPrice = o.intOrNull("lowestPrice"),
                lastChecked = o.longOrNull("lastChecked"),
                lastError = o.stringOrNull("lastError"),
                lastNotifiedPrice = o.intOrNull("lastNotifiedPrice"),
                bestAirline = o.stringOrNull("bestAirline"),
                flightsUrl = o.stringOrNull("flightsUrl"),
                history = history,
            )
        }
    }
}

data class Settings(
    val apiKey: String = "",
    val currency: String = "HUF",
    val intervalHours: Int = 6,
)

val TRAVEL_CLASSES = listOf(
    1 to "Turista",
    2 to "Prémium turista",
    3 to "Business",
    4 to "Első osztály",
)

val STOP_OPTIONS = listOf(
    0 to "Mindegy",
    1 to "Csak közvetlen járat",
    2 to "Legfeljebb 1 átszállás",
    3 to "Legfeljebb 2 átszállás",
)

val CURRENCIES = listOf(
    "HUF" to "Forint (Ft)",
    "EUR" to "Euró (€)",
    "USD" to "Dollár ($)",
    "GBP" to "Font (£)",
)

val INTERVALS = listOf(
    3 to "3 óránként",
    6 to "6 óránként",
    12 to "12 óránként",
    24 to "Naponta egyszer",
)

val HU: Locale = Locale.forLanguageTag("hu-HU")

fun currencySymbol(currency: String): String = when (currency) {
    "HUF" -> "Ft"
    "EUR" -> "€"
    "USD" -> "$"
    "GBP" -> "£"
    else -> currency
}

fun formatPrice(price: Int, currency: String): String {
    val digits = String.format(Locale.ROOT, "%,d", price).replace(',', ' ')
    return "$digits ${currencySymbol(currency)}"
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null
