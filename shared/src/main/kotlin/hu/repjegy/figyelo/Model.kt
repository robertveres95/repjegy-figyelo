package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class PricePoint(val time: Long, val price: Int)

/**
 * A Google Flights saját árelőzménye az útvonalra (nem mi mértük): napi legolcsóbb ár kb.
 * 2 hónapra visszamenőleg, és az ott „szokásosnak” tartott ársáv. A választott pénznemben.
 */
data class MarketInsight(
    val points: List<PricePoint>,
    val typicalLow: Int?,
    val typicalHigh: Int?,
    val fetchedAt: Long,
    val forDate: String? = null,   // melyik indulási napra szól (rugalmas figyelésnél fontos)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("points", JSONArray().apply { points.forEach { put(JSONArray().put(it.time).put(it.price)) } })
        putOpt("typicalLow", typicalLow)
        putOpt("typicalHigh", typicalHigh)
        put("fetchedAt", fetchedAt)
        putOpt("forDate", forDate)
    }

    companion object {
        fun fromJson(o: JSONObject): MarketInsight? {
            val arr = o.optJSONArray("points") ?: return null
            val pts = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONArray(i)?.let { p -> runCatching { PricePoint(p.getLong(0), p.getInt(1)) }.getOrNull() }
            }.filter { it.price > 0 }
            if (pts.isEmpty()) return null
            return MarketInsight(
                pts.sortedBy { it.time }.takeLast(90),
                o.optInt("typicalLow", 0).takeIf { it > 0 },
                o.optInt("typicalHigh", 0).takeIf { it > 0 },
                o.optLong("fetchedAt", 0L),
                o.optString("forDate", "").takeIf { it.isNotBlank() },
            )
        }
    }
}

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
    val partial: Boolean = false,          // az ár hiányos (pl. ölben utazó csecsemő díja nélkül)
    val noteL: L10n? = null,               // a megjegyzés minden nyelven (a felület nyelvén látszik)
) {
    /** A megjegyzés a felület nyelvén. */
    val noteText: String? get() = noteL?.takeIf { it.has(note) }?.text ?: note

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
        noteL?.takeIf { it.has(note) }?.let { put("noteL", it.toJson()) }
        if (partial) put("partial", true)
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
            partial = o.optBoolean("partial", false),
            noteL = L10n.fromJson(o.optJSONObject("noteL")),
        )
    }
}

/** Egy forrás eredménye az utolsó ellenőrzéskor. */
data class SourceStatus(val source: String, val ok: Boolean, val text: String, val textL: L10n? = null) {
    /** Az állapot szövege a felület nyelvén. */
    val shown: String get() = textL?.takeIf { it.has(text) }?.text ?: text
}

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
    val flexDays: Int = 0,             // rugalmas dátum: ±ennyi nap (0 = pontos dátum)
    val depFrom: Int? = null,          // odaút indulása legkorábban (óra, 0–23)
    val depTo: Int? = null,            // odaút indulása legkésőbb (óra, 1–24; az óra vége)
    val airlines: String = "",         // csak ezek a légitársaságok (vesszővel), üres = bármelyik
    val weeklyUntil: String? = null,   // „minden héten”: ugyanezek a napok hetente, legkésőbb ezen a napon indulva
    val sharedFrom: String? = null,    // megosztott figyelésnél az eredeti figyelés azonosítója (újbóli átvételkor frissül)
    val editedAt: Long = 0,            // utolsó felhasználói módosítás (szinkronizáláshoz)
    // Eredmények
    val lastPrice: Int? = null,
    val lowestPrice: Int? = null,
    val lastChecked: Long? = null,
    val lastError: String? = null,
    val lastErrorL: L10n? = null,      // a hibaüzenet minden nyelven
    val lastNotifiedPrice: Int? = null,
    val offers: List<Offer> = emptyList(),          // ár szerint rendezve, az első a legjobb
    val sourceStatus: List<SourceStatus> = emptyList(),
    val history: List<PricePoint> = emptyList(),
    val market: MarketInsight? = null,               // a Google árelőzménye (nem a mi mérésünk)
) {
    val isRoundTrip: Boolean get() = returnDate != null

    val wantsBags: Boolean get() = bags > 0 || checkedBag

    val seatedPassengers: Int get() = adults + children + infantsInSeat

    /** Kártyán és értesítésben használt útvonalnév, pl. "Budapest → London". */
    val routeTitle: String
        get() = "${Airports.cityName(from) ?: fromLabel ?: from} → ${Airports.cityName(to) ?: toLabel ?: to}"

    val bestOffer: Offer? get() = offers.firstOrNull()

    /** A hibaüzenet a felület nyelvén. */
    val errorText: String? get() = lastErrorL?.takeIf { it.has(lastError) }?.text ?: lastError

    /**
     * A tárolt szövegek (hiba, forrásállapot, ajánlat-megjegyzés) minden nyelvű változatának kitöltése a
     * [Texts]-ből: így nyelvváltás után is a felület nyelvén látszanak. A már nem illő változatot eldobja.
     */
    fun withL10n(): Watch = copy(
        lastErrorL = lastErrorL?.takeIf { it.has(lastError) } ?: Texts.find(lastError),
        sourceStatus = sourceStatus.map { s -> if (s.textL?.has(s.text) == true) s else s.copy(textL = Texts.find(s.text)) },
        offers = offers.map { o -> if (o.note == null || o.noteL?.has(o.note) == true) o else o.copy(noteL = Texts.find(o.note)) },
    )

    /**
     * Összevethető-e az ajánlat a célárral: a kért poggyász díja benne van, és az ár nem
     * hiányos (pl. a fapadosok ölben utazó csecsemőre számolt díja nélkül). A riasztás,
     * az árgörbe és a kártya „célár alatt” jelzése is ezt használja, hogy egyezzenek.
     */
    fun comparable(o: Offer): Boolean = !(wantsBags && !o.bagsIncluded) && !o.partial

    fun alertable(o: Offer): Boolean = comparable(o) && o.price <= targetPrice

    /** Több dátumot is keres (±napok vagy minden héten). */
    val isFlexible: Boolean get() = flexDays > 0 || weeklyUntil != null

    /** Az utolsó lehetséges indulási nap. */
    fun lastDeparture(): LocalDate? = runCatching {
        val out = LocalDate.parse(outboundDate)
        weeklyUntil?.let { lastWeekly(out, LocalDate.parse(it)) } ?: out.plusDays(flexDays.toLong())
    }.getOrNull()

    /** Lejárt, ha már a rugalmas tartomány utolsó napja is elmúlt. */
    fun isExpired(today: LocalDate = LocalDate.now()): Boolean = lastDeparture()?.isBefore(today) ?: false

    /** Egyetlen, pontos dátumpárra szűkített példány (a forrásoknak ilyet adunk át). */
    fun exact(out: String = outboundDate, ret: String? = returnDate): Watch =
        copy(outboundDate = out, returnDate = ret, flexDays = 0, weeklyUntil = null)

    /**
     * A keresendő dátumpárok rugalmas dátumnál: az út hossza marad, és csak a mai vagy
     * későbbi indulások számítanak (pl. ±2 nap → 5 dátumpár). Pontos dátumnál egy elem.
     */
    fun datePairs(today: LocalDate = LocalDate.now()): List<Pair<String, String?>> {
        val out = runCatching { LocalDate.parse(outboundDate) }.getOrNull() ?: return listOf(outboundDate to returnDate)
        val ret = returnDate?.let { r -> runCatching { LocalDate.parse(r) }.getOrNull() }
        // Minden héten: ugyanezek a napok hetente (pl. péntek–vasárnap), időrendben, legfeljebb MAX_WEEKS hét
        val until = weeklyUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (until != null) {
            return (0 until MAX_WEEKS)
                .map { k -> out.plusWeeks(k.toLong()) to ret?.plusWeeks(k.toLong()) }
                .filter { !it.first.isAfter(until) && !it.first.isBefore(today) }
                .map { (o, r) -> o.toString() to r?.toString() }
                .ifEmpty { listOf(outboundDate to returnDate) }
        }
        return (-flexDays..flexDays)
            .map { k -> out.plusDays(k.toLong()) to ret?.plusDays(k.toLong()) }
            .filter { !it.first.isBefore(today) }
            .sortedBy { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(out, it.first)) }
            .map { (o, r) -> o.toString() to r?.toString() }
            .ifEmpty { listOf(outboundDate to returnDate) }
    }

    /** A légitársaság-szűrő elemei kisbetűvel, szóközök nélkül (pl. ["wizz", "ryanair"]). */
    val airlineTokens: List<String>
        get() = airlines.split(',', ';').map { normalizeAirline(it) }.filter { it.length >= 2 }

    /**
     * Átengedi-e a légitársaság-szűrő ezt a légitársaságot (vagy egy több légitársaságos
     * ajánlat bármelyik tagját). Szóköz és kis-nagybetű nem számít: „wizzair” = „Wizz Air”.
     */
    fun airlineMatches(airline: String?): Boolean {
        val tokens = airlineTokens
        if (tokens.isEmpty() || airline == null) return true
        val name = normalizeAirline(airline)
        return tokens.any { name.contains(it) || (it.length >= 4 && it.contains(name)) }
    }

    /**
     * Megfelel-e az ajánlat az időablaknak és a légitársaság-szűrőnek. Ha a forrás nem adta
     * meg az indulási időt vagy a légitársaságot, nem dobjuk el (nem tudjuk, hogy rossz).
     */
    fun matchesFilters(o: Offer): Boolean {
        if (depFrom != null || depTo != null) {
            val dep = o.departure?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
            if (dep != null) {
                val minutes = dep.hour * 60 + dep.minute
                if (depFrom != null && minutes < depFrom * 60) return false
                if (depTo != null && minutes > depTo * 60) return false
            }
        }
        return airlineMatches(o.airline)
    }

    /** Ha ez változik, a korábbi árak már nem összehasonlíthatók. */
    fun searchKey(): String = listOf(
        from, to, outboundDate, returnDate, travelClass, adults, children,
        infantsInSeat, infantsOnLap, bags, stops, checkedBag, flexDays, depFrom, depTo, airlines.trim().lowercase(),
    ).joinToString("|") + (weeklyUntil?.let { "|w$it" } ?: "")

    fun clearResults(): Watch = copy(
        lastPrice = null, lowestPrice = null, lastChecked = null, lastError = null, lastErrorL = null,
        lastNotifiedPrice = null, offers = emptyList(), sourceStatus = emptyList(), history = emptyList(),
        market = null,
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
        if (flexDays != 0) put("flexDays", flexDays)
        putOpt("depFrom", depFrom)
        putOpt("depTo", depTo)
        if (airlines.isNotBlank()) put("airlines", airlines)
        putOpt("weeklyUntil", weeklyUntil)
        putOpt("sharedFrom", sharedFrom)
        if (editedAt != 0L) put("editedAt", editedAt)
        putOpt("lastPrice", lastPrice)
        putOpt("lowestPrice", lowestPrice)
        putOpt("lastChecked", lastChecked)
        putOpt("lastError", lastError)
        lastErrorL?.takeIf { it.has(lastError) }?.let { put("lastErrorL", it.toJson()) }
        putOpt("lastNotifiedPrice", lastNotifiedPrice)
        put("offers", JSONArray().apply { offers.forEach { put(it.toJson()) } })
        put("sourceStatus", JSONArray().apply {
            sourceStatus.forEach {
                put(JSONObject().put("source", it.source).put("ok", it.ok).put("text", it.text).apply {
                    it.textL?.takeIf { l -> l.has(it.text) }?.let { l -> put("textL", l.toJson()) }
                })
            }
        })
        val h = JSONArray()
        history.forEach { h.put(JSONArray().put(it.time).put(it.price)) }
        put("history", h)
        market?.let { put("market", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject): Watch {
            val h = o.optJSONArray("history") ?: JSONArray()
            val history = (0 until h.length()).mapNotNull { i ->
                h.optJSONArray(i)?.let { p -> runCatching { PricePoint(p.getLong(0), p.getInt(1)) }.getOrNull() }
            }
            val offersArr = o.optJSONArray("offers") ?: JSONArray()
            val offers = (0 until offersArr.length()).mapNotNull { i ->
                offersArr.optJSONObject(i)?.let { runCatching { Offer.fromJson(it) }.getOrNull() }
            }
            val statusArr = o.optJSONArray("sourceStatus") ?: JSONArray()
            val status = (0 until statusArr.length()).mapNotNull { i ->
                statusArr.optJSONObject(i)?.let {
                    SourceStatus(it.optString("source"), it.optBoolean("ok"), it.optString("text"), L10n.fromJson(it.optJSONObject("textL")))
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
                flexDays = o.optInt("flexDays", 0).coerceIn(0, 3),
                depFrom = o.intOrNull("depFrom")?.coerceIn(0, 23),
                depTo = o.intOrNull("depTo")?.coerceIn(1, 24),
                airlines = o.optString("airlines", ""),
                weeklyUntil = o.stringOrNull("weeklyUntil"),
                sharedFrom = o.stringOrNull("sharedFrom")?.take(64),
                editedAt = o.optLong("editedAt", 0L),
                lastPrice = o.intOrNull("lastPrice"),
                lowestPrice = o.intOrNull("lowestPrice"),
                lastChecked = o.longOrNull("lastChecked"),
                lastError = o.stringOrNull("lastError"),
                lastErrorL = L10n.fromJson(o.optJSONObject("lastErrorL")),
                lastNotifiedPrice = o.intOrNull("lastNotifiedPrice"),
                offers = offers,
                sourceStatus = status,
                history = history,
                market = o.optJSONObject("market")?.let { runCatching { MarketInsight.fromJson(it) }.getOrNull() },
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
    val themeMode: String = THEME_AUTO,
    val textScale: Int = 100,          // betűméret százalékban
    val quietOn: Boolean = false,      // csendes órák: éjszaka nem szól/rezeg
    val quietFrom: Int = 22,
    val quietTo: Int = 7,
) {
    /** Most csendes óra van-e (az éjfélen átnyúló tartományt is kezeli, pl. 22–7). */
    fun isQuiet(now: java.time.LocalTime = java.time.LocalTime.now()): Boolean {
        if (!quietOn || quietFrom == quietTo) return false
        val h = now.hour
        return if (quietFrom < quietTo) h in quietFrom until quietTo else h >= quietFrom || h < quietTo
    }

    val useSerpApi: Boolean get() = serpOn && apiKey.isNotBlank()
    val useIgnav: Boolean get() = ignavOn && ignavKey.isNotBlank()

    /** Van-e legalább egy működőképes forrás. */
    val isReady: Boolean get() = googleOn || ryanairOn || wizzOn || useSerpApi || useIgnav
}

val TRAVEL_CLASSES: List<Pair<Int, String>> get() = listOf(
    1 to tr("Turista", "Economy"),
    2 to tr("Prémium turista", "Premium economy"),
    3 to "Business",
    4 to tr("Első osztály", "First class"),
)

val STOP_OPTIONS: List<Pair<Int, String>> get() = listOf(
    0 to tr("Mindegy", "Any"),
    1 to tr("Csak közvetlen járat", "Direct flights only"),
    2 to tr("Legfeljebb 1 átszállás", "At most 1 stop"),
    3 to tr("Legfeljebb 2 átszállás", "At most 2 stops"),
)

val CURRENCIES: List<Pair<String, String>> get() = listOf(
    "HUF" to tr("Forint (Ft)", "Hungarian forint (Ft)"),
    "EUR" to tr("Euró (€)", "Euro (€)"),
    "USD" to tr("Dollár ($)", "US dollar ($)"),
    "GBP" to tr("Font (£)", "British pound (£)"),
)

val TEXT_SCALES: List<Pair<Int, String>> get() = listOf(
    100 to tr("Normál", "Normal"),
    115 to tr("Nagy", "Large"),
    130 to tr("Extra nagy", "Extra large"),
)

val INTERVALS: List<Pair<Int, String>> get() = listOf(
    0 to tr("Ki (csak kézi ellenőrzés)", "Off (manual checks only)"),
    3 to tr("3 óránként", "Every 3 hours"),
    6 to tr("6 óránként", "Every 6 hours"),
    12 to tr("12 óránként", "Every 12 hours"),
    24 to tr("Naponta egyszer", "Once a day"),
)

internal fun normalizeAirline(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

val FLEX_OPTIONS: List<Pair<Int, String>> get() = listOf(
    0 to tr("Pontos dátum", "Exact date"),
    1 to tr("±1 nap", "±1 day"),
    2 to tr("±2 nap", "±2 days"),
    3 to tr("±3 nap", "±3 days"),
)

/** „Minden héten” mód: legfeljebb ennyi hetet nézünk (a kérések száma ne nőjön túl). */
const val MAX_WEEKS = 9

/**
 * A „minden héten” mód tényleges utolsó indulása: az első indulás napjával azonos hétköznapra eső,
 * [until]-nál nem későbbi nap (pl. péntek → november végéig: az utolsó novemberi péntek), legfeljebb MAX_WEEKS hét.
 */
fun lastWeekly(first: LocalDate, until: LocalDate): LocalDate {
    val days = java.time.temporal.ChronoUnit.DAYS.between(first, until).coerceAtLeast(0)
    return first.plusWeeks((days / 7).coerceAtMost(MAX_WEEKS - 1L))
}


val HOUR_FROM_OPTIONS: List<Pair<Int?, String>>
    get() = listOf<Pair<Int?, String>>(null to tr("Bármikor", "Any time")) +
        listOf(5, 6, 7, 8, 9, 10, 12, 14, 16, 18).map { it to tr("%02d:00-tól".format(it), "From %02d:00".format(it)) }

val HOUR_TO_OPTIONS: List<Pair<Int?, String>>
    get() = listOf<Pair<Int?, String>>(null to tr("Bármikor", "Any time")) +
        listOf(9, 10, 12, 14, 16, 18, 20, 22).map { it to tr("%02d:00-ig".format(it), "Until %02d:00".format(it)) }

val QUIET_HOURS = (0..23).map { it to "%02d:00".format(it) }

val HU: Locale = Locale.forLanguageTag("hu-HU")

/**
 * Csak sima https-weboldal nyitható meg. A linkek külső forrásokból is jönnek (pl. SerpApi),
 * és egy file:, intent: vagy javascript: cím programot indíthatna vagy összeomlaszthatná az appot.
 */
fun isSafeWebUrl(url: String?): Boolean {
    if (url.isNullOrBlank() || url.length > 4000) return false
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}

fun currencySymbol(currency: String): String = when (currency) {
    "HUF" -> "Ft"
    "EUR" -> "€"
    "USD" -> "$"
    "GBP" -> "£"
    else -> currency
}

fun formatPrice(price: Int, currency: String): String {
    // Nem törő szóközök: nagy betűméretnél se törjön el a „485 000 Ft” két sorba
    val digits = String.format(Locale.ROOT, "%,d", price).replace(',', '\u00A0')
    return "$digits\u00A0${currencySymbol(currency)}"
}

internal fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

internal fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

internal fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

// ---------------------------------------------------------------- Időpontok kiírása

private val legDateFormat: java.time.format.DateTimeFormatter
    get() = java.time.format.DateTimeFormatter.ofPattern(if (Lang.en) "d MMM, EEE" else "MMM d., EEE", Lang.locale)

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
            if (days != 0L) append(" (${if (days > 0) "+" else ""}$days ${tr("nap", if (kotlin.math.abs(days) == 1L) "day" else "days")})")
        }
        if (from != null && to != null) append(" · $from → $to")
        when (stops) {
            null -> Unit
            0 -> append(" · " + tr("közvetlen", "direct"))
            else -> append(" · " + tr("$stops átszállás", if (stops == 1) "1 stop" else "$stops stops"))
        }
    }
}

fun Offer.outboundText(): String? = describeLeg(departure, arrival, stops, fromCode, toCode)

fun Offer.returnText(): String? = describeLeg(returnDeparture, returnArrival, returnStops, toCode, fromCode)

/** Egy út darabokra bontva a jól olvasható, nagybetűs megjelenítéshez. */
data class LegParts(val day: String, val times: String, val route: String?)

fun legParts(departure: String?, arrival: String?, stops: Int?, from: String?, to: String?): LegParts? {
    val dep = departure?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() } ?: return null
    val arr = arrival?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
    val times = buildString {
        append("%02d:%02d".format(dep.hour, dep.minute))
        if (arr != null) {
            append(" → ")
            append("%02d:%02d".format(arr.hour, arr.minute))
            val days = java.time.temporal.ChronoUnit.DAYS.between(dep.toLocalDate(), arr.toLocalDate())
            if (days != 0L) append(" (${if (days > 0) "+" else ""}$days ${tr("nap", if (kotlin.math.abs(days) == 1L) "day" else "days")})")
        }
    }
    val route = listOfNotNull(
        if (from != null && to != null) "$from → $to" else null,
        when (stops) {
            null -> null
            0 -> tr("közvetlen", "direct")
            else -> tr("$stops átszállás", if (stops == 1) "1 stop" else "$stops stops")
        },
    ).joinToString(" · ").ifBlank { null }
    return LegParts(dep.format(legDateFormat), times, route)
}

fun Offer.outboundParts(): LegParts? = legParts(departure, arrival, stops, fromCode, toCode)

fun Offer.returnParts(): LegParts? = legParts(returnDeparture, returnArrival, returnStops, toCode, fromCode)
