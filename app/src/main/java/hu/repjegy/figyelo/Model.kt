package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class PricePoint(val time: Long, val price: Int)

/** Egy konkrét ajánlat egy forrásból, a választott pénznemben. */
data class Offer(
    val price: Int,
    val source: String,
    val airline: String? = null,
    val fromCode: String? = null,
    val toCode: String? = null,
    val departure: String? = null,         // helyi idő, "yyyy-MM-ddTHH:mm"
    val arrival: String? = null,
    val stops: Int? = null,
    val returnDeparture: String? = null,
    val returnArrival: String? = null,
    val returnStops: Int? = null,
    val url: String? = null,
    val bagsIncluded: Boolean = true,      // a kért poggyász díja benne van-e
    val note: String? = null,              // pl. "becsült ár"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("price", price)
        put("source", source)
        putOpt("airline", airline)
        putOpt("fromCode", fromCode)
        putOpt("toCode", toCode)
        putOpt("departure", departure)
        putOpt("arrival", arrival)
        putOpt("stops", stops)
        putOpt("returnDeparture", returnDeparture)
        putOpt("returnArrival", returnArrival)
        putOpt("returnStops", returnStops)
        putOpt("url", url)
        put("bagsIncluded", bagsIncluded)
        putOpt("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject) = Offer(
            price = o.getInt("price"),
            source = o.optString("source", "?"),
            airline = o.stringOrNull("airline"),
            fromCode = o.stringOrNull("fromCode"),
            toCode = o.stringOrNull("toCode"),
            departure = o.stringOrNull("departure"),
            arrival = o.stringOrNull("arrival"),
            stops = o.intOrNull("stops"),
            returnDeparture = o.stringOrNull("returnDeparture"),
            returnArrival = o.stringOrNull("returnArrival"),
            returnStops = o.intOrNull("returnStops"),
            url = o.stringOrNull("url"),
            bagsIncluded = o.optBoolean("bagsIncluded", true),
            note = o.stringOrNull("note"),
        )
    }
}

/** Egy forrás eredménye az utolsó ellenőrzéskor. */
data class SourceStatus(val source: String, val ok: Boolean, val text: String)

/** Egy figyelt út a keresési beállításokkal és az utolsó eredményekkel. */
data class Watch(
    val id: String,
    // Keresési beállítások
    val from: String,
    val to: String,
    val fromLabel: String? = null,     // pl. "Budapest"
    val toLabel: String? = null,       // pl. "London"
    val outboundDate: String,          // YYYY-MM-DD
    val returnDate: String?,           // null = csak oda
    val travelClass: Int,              // 1 turista, 2 prémium turista, 3 business, 4 első
    val adults: Int,
    val children: Int,
    val infantsInSeat: Int,
    val infantsOnLap: Int,
    val bags: Int,                     // kézipoggyászok száma összesen
    val checkedBag: Boolean = false,   // feladott poggyász
    val stops: Int,                    // 0 mindegy, 1 közvetlen, 2 max 1, 3 max 2 átszállás
    val targetPrice: Int,
    val notify: Boolean,
    // Eredmények
    val lastPrice: Int? = null,
    val lowestPrice: Int? = null,
    val lastChecked: Long? = null,
    val lastError: String? = null,
    val lastNotifiedPrice: Int? = null,
    val offers: List<Offer> = emptyList(),          // ár szerint rendezve, az első a legjobb
    val sourceStatus: List<SourceStatus> = emptyList(),
    val history: List<PricePoint> = emptyList(),
) {
    val isRoundTrip: Boolean get() = returnDate != null

    val wantsBags: Boolean get() = bags > 0 || checkedBag

    val seatedPassengers: Int get() = adults + children + infantsInSeat

    /** Kártyán és értesítésben használt útvonalnév, pl. "Budapest → London". */
    val routeTitle: String get() = "${fromLabel ?: from} → ${toLabel ?: to}"

    val bestOffer: Offer? get() = offers.firstOrNull()

    fun isExpired(today: LocalDate = LocalDate.now()): Boolean =
        runCatching { LocalDate.parse(outboundDate).isBefore(today) }.getOrDefault(false)

    /** Ha ez változik, a korábbi árak már nem összehasonlíthatók. */
    fun searchKey(): String = listOf(
        from, to, outboundDate, returnDate, travelClass, adults, children,
        infantsInSeat, infantsOnLap, bags, stops, checkedBag,
    ).joinToString("|")

    fun clearResults(): Watch = copy(
        lastPrice = null, lowestPrice = null, lastChecked = null, lastError = null,
        lastNotifiedPrice = null, offers = emptyList(), sourceStatus = emptyList(), history = emptyList(),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("from", from)
        put("to", to)
        putOpt("fromLabel", fromLabel)
        putOpt("toLabel", toLabel)
        put("outboundDate", outboundDate)
        putOpt("returnDate", returnDate)
        put("travelClass", travelClass)
        put("adults", adults)
        put("children", children)
        put("infantsInSeat", infantsInSeat)
        put("infantsOnLap", infantsOnLap)
        put("bags", bags)
        put("checkedBag", checkedBag)
        put("stops", stops)
        put("targetPrice", targetPrice)
        put("notify", notify)
        putOpt("lastPrice", lastPrice)
        putOpt("lowestPrice", lowestPrice)
        putOpt("lastChecked", lastChecked)
        putOpt("lastError", lastError)
        putOpt("lastNotifiedPrice", lastNotifiedPrice)
        put("offers", JSONArray().apply { offers.forEach { put(it.toJson()) } })
        put("sourceStatus", JSONArray().apply {
            sourceStatus.forEach {
                put(JSONObject().put("source", it.source).put("ok", it.ok).put("text", it.text))
            }
        })
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
            val offersArr = o.optJSONArray("offers") ?: JSONArray()
            val offers = (0 until offersArr.length()).mapNotNull { i ->
                offersArr.optJSONObject(i)?.let { runCatching { Offer.fromJson(it) }.getOrNull() }
            }
            val statusArr = o.optJSONArray("sourceStatus") ?: JSONArray()
            val status = (0 until statusArr.length()).mapNotNull { i ->
                statusArr.optJSONObject(i)?.let {
                    SourceStatus(it.optString("source"), it.optBoolean("ok"), it.optString("text"))
                }
            }
            return Watch(
                id = o.getString("id"),
                from = o.getString("from"),
                to = o.getString("to"),
                fromLabel = o.stringOrNull("fromLabel"),
                toLabel = o.stringOrNull("toLabel"),
                outboundDate = o.getString("outboundDate"),
                returnDate = o.stringOrNull("returnDate"),
                travelClass = o.optInt("travelClass", 1),
                adults = o.optInt("adults", 1),
                children = o.optInt("children", 0),
                infantsInSeat = o.optInt("infantsInSeat", 0),
                infantsOnLap = o.optInt("infantsOnLap", 0),
                bags = o.optInt("bags", 0),
                checkedBag = o.optBoolean("checkedBag", false),
                stops = o.optInt("stops", 0),
                targetPrice = o.optInt("targetPrice", 0),
                notify = o.optBoolean("notify", true),
                lastPrice = o.intOrNull("lastPrice"),
                lowestPrice = o.intOrNull("lowestPrice"),
                lastChecked = o.longOrNull("lastChecked"),
                lastError = o.stringOrNull("lastError"),
                lastNotifiedPrice = o.intOrNull("lastNotifiedPrice"),
                offers = offers,
                sourceStatus = status,
                history = history,
            )
        }
    }
}

data class Settings(
    val googleOn: Boolean = true,
    val ryanairOn: Boolean = true,
    val wizzOn: Boolean = true,
    val serpOn: Boolean = false,
    val ignavOn: Boolean = false,
    val apiKey: String = "",           // SerpApi
    val ignavKey: String = "",
    val currency: String = "HUF",
    val intervalHours: Int = 6,
) {
    val useSerpApi: Boolean get() = serpOn && apiKey.isNotBlank()
    val useIgnav: Boolean get() = ignavOn && ignavKey.isNotBlank()

    /** Van-e legalább egy működőképes forrás. */
    val isReady: Boolean get() = googleOn || ryanairOn || wizzOn || useSerpApi || useIgnav
}

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

internal fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

internal fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

internal fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

// ---------------------------------------------------------------- Időpontok kiírása

private val legDateFormat = java.time.format.DateTimeFormatter.ofPattern("MMM d., EEE", HU)

/**
 * Egy út szöveges leírása, pl. "nov. 5., cs 06:25 → 08:10 (BUD → STN), közvetlen".
 * Ha az érkezés másnapra esik, "+1 nap" jelzést kap.
 */
fun describeLeg(departure: String?, arrival: String?, stops: Int?, from: String?, to: String?): String? {
    val dep = departure?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() } ?: return null
    val arr = arrival?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
    return buildString {
        append(dep.format(legDateFormat))
        append(" ")
        append("%02d:%02d".format(dep.hour, dep.minute))
        if (arr != null) {
            append(" → ")
            append("%02d:%02d".format(arr.hour, arr.minute))
            val days = java.time.temporal.ChronoUnit.DAYS.between(dep.toLocalDate(), arr.toLocalDate())
            if (days > 0) append(" (+$days nap)")
        }
        if (from != null && to != null) append(" · $from → $to")
        when (stops) {
            null -> Unit
            0 -> append(" · közvetlen")
            else -> append(" · $stops átszállás")
        }
    }
}

fun Offer.outboundText(): String? = describeLeg(departure, arrival, stops, fromCode, toCode)

fun Offer.returnText(): String? = describeLeg(returnDeparture, returnArrival, returnStops, toCode, fromCode)
