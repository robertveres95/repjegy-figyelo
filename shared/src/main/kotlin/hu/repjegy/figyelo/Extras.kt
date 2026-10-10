package hu.repjegy.figyelo

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

// ---------------------------------------------------------------- Megosztás kóddal

/**
 * Egy figyelés átküldése szövegként (pl. Messengeren a néninek). A kód csak a keresési
 * beállításokat tartalmazza (az árakat nem), tömörítve és base64url-kódolva:
 * "REFI1:<kód>". A fogadó az appban beilleszti, vagy Androidon a megosztás menüből a REFI-t választja.
 */
object ShareCode {
    private const val PREFIX = "REFI1:"
    private val shortDate = DateTimeFormatter.ofPattern("MMM d.", HU)

    fun encode(w: Watch, currency: String): String {
        val json = JSONObject()
            .put("v", 1)
            .put("cur", currency)
            .put("w", searchOnly(w).toJson())
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(json.toString().toByteArray(Charsets.UTF_8)))
    }

    /** Az elküldendő üzenet: olvasható összefoglaló + a kód külön sorban. */
    fun message(w: Watch, currency: String): String {
        val out = runCatching { LocalDate.parse(w.outboundDate).format(shortDate) }.getOrDefault(w.outboundDate)
        val ret = w.returnDate?.let { r -> runCatching { LocalDate.parse(r).format(shortDate) }.getOrDefault(r) }
        val dates = if (ret != null) "$out – $ret" else out
        return "✈ REFI figyelés: ${w.routeTitle}, $dates\n" +
            "Célár: ${formatPrice(w.targetPrice, currency)}\n" +
            "Átvétel: REFI → ⋮ menü → Kód beillesztése (vagy oszd meg ezt az üzenetet a REFI-vel).\n" +
            encode(w, currency)
    }

    /** Megkeresi a kódot egy (akár hosszabb) szövegben, és figyeléssé alakítja. */
    fun decode(text: String): Pair<Watch, String>? {
        val start = text.indexOf(PREFIX)
        if (start < 0) return null
        val code = text.substring(start + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (code.isEmpty() || code.length > 20_000) return null
        return runCatching {
            val bytes = inflate(Base64.getUrlDecoder().decode(code))
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val cur = json.optString("cur", "HUF").takeIf { c -> CURRENCIES.any { it.first == c } } ?: "HUF"
            val w = Watch.fromJson(json.getJSONObject("w")).sanitized() ?: error("érvénytelen figyelés")
            // Új azonosító: a saját figyeléseket sosem írja felül egy kapott kód
            searchOnly(w).copy(id = UUID.randomUUID().toString(), notify = true) to cur
        }.getOrNull()
    }

    private fun searchOnly(w: Watch) = w.clearResults().copy(lastError = null)

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val i = Inflater(true)
        i.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        var idle = 0
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary() || ++idle > 10)) break
            out.write(buf, 0, n)
            require(out.size() < 200_000) { "túl nagy" }
        }
        i.end()
        return out.toByteArray()
    }
}

/**
 * Kívülről érkező (megosztott vagy mentett) figyelés ellenőrzése: csak értelmes értékek
 * maradhatnak (pl. 1–9 felnőtt, létező kódformátum, rövid szövegek). Null, ha használhatatlan.
 */
internal fun Watch.sanitized(): Watch? {
    val code = Regex("[A-Z]{3}")
    val fromCodes = from.split(',').map { it.trim() }
    val toCodes = to.split(',').map { it.trim() }
    if (fromCodes.isEmpty() || toCodes.isEmpty() || fromCodes.size > 8 || toCodes.size > 8) return null
    if (!(fromCodes + toCodes).all { it.matches(code) }) return null
    if (fromCodes.any { it in toCodes }) return null
    val out = runCatching { LocalDate.parse(outboundDate) }.getOrNull() ?: return null
    val ret = returnDate?.let { r -> runCatching { LocalDate.parse(r) }.getOrNull() ?: return null }
    if (ret != null && ret.isBefore(out)) return null
    if (adults !in 1..9 || targetPrice <= 0 || id.isBlank() || id.length > 64) return null
    val ch = children.coerceIn(0, 8)
    val seat = infantsInSeat.coerceIn(0, 4)
    val lap = infantsOnLap.coerceIn(0, adults)
    if (adults + ch + seat + lap > 9) return null
    return copy(
        from = fromCodes.joinToString(","),
        to = toCodes.joinToString(","),
        fromLabel = fromLabel?.take(60),
        toLabel = toLabel?.take(60),
        travelClass = travelClass.coerceIn(1, 4),
        children = ch,
        infantsInSeat = seat,
        infantsOnLap = lap,
        bags = bags.coerceIn(0, adults + ch + seat),
        stops = stops.coerceIn(0, 3),
        flexDays = flexDays.coerceIn(0, 3),
        depFrom = depFrom?.coerceIn(0, 23),
        depTo = depTo?.coerceIn(1, 24),
        airlines = airlines.take(80),
        lastError = lastError?.take(300),
    )
}

// ---------------------------------------------------------------- Mentés és visszaállítás

/**
 * Az összes figyelés egy fájlban (pl. telefonváltáskor, vagy a telefon és a Windows-gép
 * között). Az API-kulcsokat szándékosan nem menti: a fájl így nyugodtan továbbküldhető.
 */
object Backup {
    const val FORMAT = "refi-backup"

    fun fileName(today: LocalDate = LocalDate.now()) = "REFI-mentes-$today.json"

    fun export(watches: List<Watch>, settings: Settings): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("app", Updater.currentVersion)
        .put("exportedAt", System.currentTimeMillis())
        .put("currency", settings.currency)
        .put("settings", JSONObject()
            .put("intervalHours", settings.intervalHours)
            .put("themeMode", settings.themeMode)
            .put("textScale", settings.textScale)
            .put("quietOn", settings.quietOn)
            .put("quietFrom", settings.quietFrom)
            .put("quietTo", settings.quietTo))
        .put("watches", JSONArray().apply { watches.forEach { put(it.toJson()) } })
        .toString(2)

    class Parsed(val watches: List<Watch>, val currency: String, val skipped: Int, val settings: JSONObject? = null)

    /** Egy mentésfájl beolvasása; null, ha nem REFI-mentés. A hibás figyeléseket kihagyja. */
    fun parse(text: String): Parsed? {
        val json = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        if (json.optString("format") != FORMAT) return null
        val arr = json.optJSONArray("watches") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { Watch.fromJson(o).sanitized() }.getOrNull() }
        }
        val cur = json.optString("currency", "HUF").takeIf { c -> CURRENCIES.any { it.first == c } } ?: "HUF"
        return Parsed(list, cur, arr.length() - list.size, json.optJSONObject("settings"))
    }

    /** A mentett beállítások (téma, betűméret, csendes órák, gyakoriság) alkalmazása; a kulcsok és a pénznem maradnak. */
    fun applySettings(saved: JSONObject?, current: Settings): Settings {
        saved ?: return current
        return current.copy(
            intervalHours = saved.optInt("intervalHours", current.intervalHours).takeIf { h -> INTERVALS.any { it.first == h } } ?: current.intervalHours,
            themeMode = saved.optString("themeMode", current.themeMode).takeIf { t -> THEMES.any { it.first == t } } ?: current.themeMode,
            textScale = saved.optInt("textScale", current.textScale).takeIf { t -> TEXT_SCALES.any { it.first == t } } ?: current.textScale,
            quietOn = saved.optBoolean("quietOn", current.quietOn),
            quietFrom = saved.optInt("quietFrom", current.quietFrom).coerceIn(0, 23),
            quietTo = saved.optInt("quietTo", current.quietTo).coerceIn(0, 23),
        )
    }
}

// ---------------------------------------------------------------- „Most vegyem vagy várjak?”

/** Egy rövid, őszinte értékelés az eddigi árak alapján (nem jóslat). */
data class Verdict(val text: String, val tone: Tone) {
    enum class Tone { GOOD, NEUTRAL, WAIT }
}

/**
 * Az aktuális legjobb ár helye az eddig mért árak között, és hogy mennyi idő van még az
 * indulásig. Legalább 4 mérés kell hozzá, különben nincs mihez viszonyítani.
 */
fun verdictFor(w: Watch, today: LocalDate = LocalDate.now(), currency: String? = null): Verdict? {
    val best = w.bestOffer ?: return null
    if (!w.comparable(best)) return null
    val now = best.price
    // Rugalmas dátumnál a legjobb ajánlat napja számít
    val depDay = best.departure?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: runCatching { LocalDate.parse(w.outboundDate) }.getOrNull()
    val daysLeft = depDay?.let { ChronoUnit.DAYS.between(today, it) } ?: 60L
    val prices = w.history.map { it.price }
    // Legalább 4 mérés, és ne legyen mind ugyanaz (pl. pár perc alatti kézi ellenőrzések);
    // amíg ennyi nincs, a Google szokásos ársávja alapján mondunk véleményt (ha van)
    val span = if (w.history.isEmpty()) 0L else w.history.maxOf { it.time } - w.history.minOf { it.time }
    if (prices.size < 4 || prices.distinct().size < 2 || span in 1 until 12 * 3_600_000L) {
        return marketVerdict(w, now, daysLeft, currency)
    }
    val lower = prices.count { it < now }
    val share = lower.toDouble() / prices.size
    // Az „eddigi legalacsonyabb” a kártyán látható minimummal egyezzen (az árgörbe csak az utolsó 120 mérést őrzi)
    val min = minOf(prices.min(), w.lowestPrice ?: Int.MAX_VALUE)
    val soon = daysLeft <= 14
    return when {
        now <= min -> Verdict("Ez az eddigi legalacsonyabb ár – jó alkalom a foglalásra.", Verdict.Tone.GOOD)
        share <= 0.25 -> Verdict("Jó ár: az eddig mért árak legolcsóbb negyedében van.", Verdict.Tone.GOOD)
        share >= 0.75 && !soon -> Verdict(
            "Drágább, mint az eddigi árak többsége – ha nem sürgős, érdemes még várni.", Verdict.Tone.WAIT,
        )
        share >= 0.75 -> Verdict(
            "Drágább az eddigieknél, de két héten belül az árak ritkán esnek – ne várj sokat.", Verdict.Tone.NEUTRAL,
        )
        soon -> Verdict("Átlagos ár; két héten belül az árak inkább emelkednek.", Verdict.Tone.NEUTRAL)
        else -> Verdict("Átlagos ár az eddigiekhez képest.", Verdict.Tone.NEUTRAL)
    }
}

/**
 * Vélemény a Google szokásos ársávja alapján (amíg nincs elég saját mérés). Csak poggyász nélküli
 * utaknál: a Google ára poggyász nélküli, a fapados csomagdíjjal növelt árunkkal nem összevethető.
 */
internal fun marketVerdict(w: Watch, now: Int, daysLeft: Long, currency: String?): Verdict? {
    if (w.wantsBags) return null
    val m = w.market ?: return null
    val low = m.typicalLow ?: return null
    val high = m.typicalHigh ?: return null
    if (low <= 0 || high < low) return null
    val band = currency?.let { " (általában ${formatPrice(low, it)} – ${formatPrice(high, it)})" } ?: ""
    return when {
        now < low -> Verdict("Olcsóbb a szokásosnál$band – jó alkalom a foglalásra.", Verdict.Tone.GOOD)
        now > high && daysLeft > 14 -> Verdict("Drágább a szokásosnál$band – ha nem sürgős, érdemes várni.", Verdict.Tone.WAIT)
        now > high -> Verdict("Drágább a szokásosnál$band, de két héten belül az árak ritkán esnek.", Verdict.Tone.NEUTRAL)
        else -> Verdict("Szokásos ár ezen az úton$band.", Verdict.Tone.NEUTRAL)
    }
}

/**
 * „Ennyit nyertél a figyeléssel”: mennyivel olcsóbb most a legjobb ár, mint a (megőrzött) első
 * mérésnél. Csak érdemi (legalább 5%-os) különbségnél, és csak összevethető árnál.
 */
fun savingsLine(w: Watch, currency: String, nowMs: Long = System.currentTimeMillis()): String? {
    val best = w.bestOffer ?: return null
    if (!w.comparable(best)) return null
    val first = w.history.firstOrNull() ?: return null
    val diff = first.price - best.price
    if (diff <= 0 || diff * 20 < first.price) return null
    val days = ((nowMs - first.time) / 86_400_000L).coerceAtLeast(0)
    val ago = if (days < 1) "Ma" else "$days napja"
    return "▼ $ago még ${formatPrice(first.price, currency)} volt – ennyit nyersz most: ${formatPrice(diff, currency)}"
}
