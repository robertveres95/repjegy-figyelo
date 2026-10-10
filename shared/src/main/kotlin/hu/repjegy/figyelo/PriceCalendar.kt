package hu.repjegy.figyelo

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

/**
 * Havi árnaptár: egy útvonal napi legolcsóbb ára egy hónapra (egy főre, csak oda, poggyász nélkül) a
 * fapadosok saját árnaptárából (Ryanair, Wizz Air). Így egy pillantással látszik, melyik napon érdemes indulni.
 */
object PriceCalendar {
    data class Day(val date: LocalDate, val pricePerPerson: Int, val source: String)

    /** Legfeljebb ennyi reptérpárt kérdezünk (több repteres városoknál). */
    private const val MAX_PAIRS_CAL = 4

    /**
     * Oda-vissza árnaptár: minden indulási napra az odaút + a [nights] éjszakával későbbi visszaút legolcsóbb
     * ára (egy főre). A visszaút a következő hónapba is átnyúlhat.
     */
    fun roundTrip(from: String, to: String, ym: YearMonth, nights: Int, currency: String, today: LocalDate = LocalDate.now()): Map<LocalDate, Day> {
        val out = month(from, to, ym, currency, today)
        if (out.isEmpty()) return emptyMap()
        val back = month(to, from, ym, currency, today).toMutableMap()
        val next = ym.plusMonths(1)
        if (!next.atDay(1).isAfter(maxTravelDate(today))) {
            runCatching { month(to, from, next, currency, today) }.getOrNull()?.let { back.putAll(it) }
        }
        return out.mapNotNull { (d, o) ->
            val r = back[d.plusDays(nights.toLong())] ?: return@mapNotNull null
            d to Day(d, o.pricePerPerson + r.pricePerPerson, if (o.source == r.source) o.source else "${o.source} + ${r.source}")
        }.toMap()
    }

    fun month(from: String, to: String, ym: YearMonth, currency: String, today: LocalDate = LocalDate.now()): Map<LocalDate, Day> {
        val settings = Store.settings.value
        val start = maxOf(ym.atDay(1), today)
        // Egy évnél későbbre nem lehet figyelést felvenni – a naptár se mutasson oda árat
        val end = minOf(ym.atEndOfMonth(), maxTravelDate(today))
        if (start.isAfter(end)) return emptyMap()
        val probe = Watch(
            id = "cal", from = from, to = to, outboundDate = start.toString(), returnDate = null, travelClass = 1,
            adults = 1, children = 0, infantsInSeat = 0, infantsOnLap = 0, bags = 0, stops = 0, targetPrice = 1, notify = false,
        )
        val pairs = pairsOf(probe, MAX_PAIRS_CAL)
        val best = mutableMapOf<LocalDate, Day>()
        fun offer(d: LocalDate, perPerson: Double, source: String) {
            if (d.isBefore(start) || d.isAfter(end) || !perPerson.isFinite() || perPerson <= 0) return
            val p = ceil(perPerson).toInt()
            if ((best[d]?.pricePerPerson ?: Int.MAX_VALUE) > p) best[d] = Day(d, p, source)
        }
        var errors = 0
        var tries = 0
        for ((o, dst) in pairs) {
            if (settings.ryanairOn) {
                tries++
                runCatching { ryanair(o, dst, ym, currency).forEach { (d, v) -> offer(d, v, Ryanair.NAME) } }.onFailure { errors++ }
            }
            if (settings.wizzOn) {
                tries++
                runCatching {
                    WizzAir.dayFares(o, dst, start, end).forEach { f ->
                        val d = runCatching { LocalDate.parse(f.departure) }.getOrNull() ?: return@forEach
                        // Egy hibás árfolyam (pl. ismeretlen pénznem) ne vigye el a többi napot
                        val v = runCatching { Rates.convert(f.amount, f.currency, currency) }.getOrNull() ?: return@forEach
                        offer(d, v, WizzAir.NAME)
                    }
                }.onFailure { errors++ }
            }
        }
        if (tries == 0) throw IOException(trs("Az árnaptárhoz a Ryanair vagy a Wizz Air forrás kell (Beállítások).",
            "The price calendar needs the Ryanair or Wizz Air source (Settings).",
            "Für den Preiskalender wird Ryanair oder Wizz Air als Quelle benötigt (Einstellungen)."))
        if (best.isEmpty() && errors == tries) throw IOException(trs("Most nem sikerült lekérni az árakat – próbáld újra később.",
            "Couldn't get the prices right now – try again later.", "Die Preise konnten gerade nicht abgerufen werden – versuche es später erneut."))
        return best
    }

    /** Ryanair „cheapestPerDay”: a hónap minden napjára a legolcsóbb egyirányú ár (egy főre). */
    private fun ryanair(origin: String, destination: String, ym: YearMonth, currency: String): List<Pair<LocalDate, Double>> {
        val q = "outboundMonthOfDate=${ym.atDay(1)}&currency=${URLEncoder.encode(currency, "UTF-8")}"
        val res = Http.request(
            "https://services-api.ryanair.com/farfnd/v4/oneWayFares/$origin/$destination/cheapestPerDay?$q",
            headers = mapOf("Accept" to "application/json"),
        )
        if (res.code == 404) return emptyList()
        if (res.code !in 200..299) throw IOException("Ryanair HTTP ${res.code}")
        val fares = JSONObject(res.body).optJSONObject("outbound")?.optJSONArray("fares") ?: return emptyList()
        return (0 until fares.length()).mapNotNull { i ->
            val f = fares.optJSONObject(i) ?: return@mapNotNull null
            if (f.optBoolean("unavailable") || f.optBoolean("soldOut")) return@mapNotNull null
            val price = f.optJSONObject("price") ?: return@mapNotNull null
            val v = price.optDouble("value", Double.NaN)
            val cur = price.optString("currencyCode", currency)
            val d = runCatching { LocalDate.parse(f.optString("day").take(10)) }.getOrNull() ?: return@mapNotNull null
            val conv = runCatching { Rates.convert(v, cur, currency) }.getOrNull() ?: return@mapNotNull null
            d to conv
        }
    }

    /** Ár-sávok a színezéshez: olcsó (alsó harmad), közepes, drága. */
    fun tiers(days: Collection<Day>): Pair<Int, Int>? {
        if (days.size < 3) return null
        val sorted = days.map { it.pricePerPerson }.sorted()
        return sorted[sorted.size / 3] to sorted[sorted.size * 2 / 3]
    }
}
