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
            val w = Watch.fromJson(json.getJSONObject("w"))
            require(w.from.isNotBlank() && w.to.isNotBlank()) { "üres útvonal" }
            require(w.from.split(',').all { it.matches(Regex("[A-Z]{3}")) } && w.to.split(',').all { it.matches(Regex("[A-Z]{3}")) })
            require(w.adults in 1..9 && w.targetPrice > 0)
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

    class Parsed(val watches: List<Watch>, val currency: String, val skipped: Int)

    /** Egy mentésfájl beolvasása; null, ha nem REFI-mentés. A hibás figyeléseket kihagyja. */
    fun parse(text: String): Parsed? {
        val json = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        if (json.optString("format") != FORMAT) return null
        val arr = json.optJSONArray("watches") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { Watch.fromJson(o) }.getOrNull() }
        }
        val cur = json.optString("currency", "HUF").takeIf { c -> CURRENCIES.any { it.first == c } } ?: "HUF"
        return Parsed(list, cur, arr.length() - list.size)
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
fun verdictFor(w: Watch, today: LocalDate = LocalDate.now()): Verdict? {
    val best = w.bestOffer ?: return null
    if (!w.comparable(best)) return null
    val prices = w.history.map { it.price }
    if (prices.size < 4) return null
    val now = best.price
    val daysLeft = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(w.outboundDate)) }.getOrDefault(60L)
    val lower = prices.count { it < now }
    val share = lower.toDouble() / prices.size
    val min = prices.min()
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
