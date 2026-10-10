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
    // „Minden héten” figyelés kódja: a régi (1.3-as) appok ezt nem ismerik fel, így nem veszik át
    // tévesen egyszerű, egy dátumos figyelésként
    private const val PREFIX2 = "REFI2:"
    private val shortDate: DateTimeFormatter get() = DateTimeFormatter.ofPattern(
        when (Lang.code) {
            Lang.HU_CODE -> "MMM d."
            Lang.DE_CODE -> "d. MMM"
            else -> "d MMM"
        },
        Lang.locale,
    )

    fun encode(w: Watch, currency: String): String {
        val json = JSONObject()
            .put("v", if (w.weeklyUntil != null) 2 else 1)
            .put("cur", currency)
            .put("w", searchOnly(w).toJson())
            // Az eredeti figyelés azonosítója: ha később újra elküldi (pl. módosított dátummal), a fogadónál
            // a meglévő figyelés frissül, nem lesz belőle még egy
            .put("src", w.sharedFrom ?: w.id)
        return (if (w.weeklyUntil != null) PREFIX2 else PREFIX) + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(json.toString().toByteArray(Charsets.UTF_8)))
    }

    /** Az elküldendő üzenet: olvasható összefoglaló + a kód külön sorban. */
    fun message(w: Watch, currency: String): String {
        val out = runCatching { LocalDate.parse(w.outboundDate).format(shortDate) }.getOrDefault(w.outboundDate)
        val ret = w.returnDate?.let { r -> runCatching { LocalDate.parse(r).format(shortDate) }.getOrDefault(r) }
        val until = w.weeklyUntil?.let { u -> runCatching { LocalDate.parse(u).format(shortDate) }.getOrDefault(u) }
        val dates = (if (ret != null) "$out – $ret" else out) + (until?.let { tr(", minden héten $it-ig", ", every week until $it", ", jede Woche bis $it") } ?: "")
        // Ha van friss ár, az is benne van (a családnak így elég az üzenetet elolvasni)
        val best = w.bestOffer?.takeIf { w.comparable(it) }
        val deal = best?.let { b ->
            val day = b.departure?.take(10)?.let { d -> runCatching { LocalDate.parse(d).format(shortDate) }.getOrNull() }
            tr("Most: ", "Now: ", "Jetzt: ") + formatPrice(b.price, currency) + listOfNotNull(b.airline, day).joinToString(", ").let { if (it.isBlank()) "" else " ($it)" } +
                (if (w.alertable(b)) tr(" – a célár alatt! 🎉", " – below the target price! 🎉", " – unter dem Zielpreis! 🎉") else "") + "\n" +
                (b.url?.takeIf { it.startsWith("https://") }?.let { tr("Foglalás: $it\n", "Book: $it\n", "Buchen: $it\n") } ?: "")
        } ?: ""
        return tr("✈ REFI figyelés: ${w.routeTitle}, $dates\n", "✈ REFI watch: ${w.routeTitle}, $dates\n", "✈ REFI-Beobachtung: ${w.routeTitle}, $dates\n") +
            deal +
            tr("Célár: ${formatPrice(w.targetPrice, currency)}\n", "Target price: ${formatPrice(w.targetPrice, currency)}\n", "Zielpreis: ${formatPrice(w.targetPrice, currency)}\n") +
            tr(
                "Átvétel: REFI → ⋮ menü → Kód beillesztése (vagy oszd meg ezt az üzenetet a REFI-vel).\n",
                "To add it: REFI → ⋮ menu → Paste code (or share this message with REFI).\n",
                "Zum Übernehmen: REFI → ⋮ Menü → Code einfügen (oder teile diese Nachricht mit REFI).\n",
            ) +
            encode(w, currency)
    }

    /** Megkeresi a kódot egy (akár hosszabb) szövegben, és figyeléssé alakítja. */
    /** A már meglévő figyelés, amelyből (vagy amellyel közös forrásból) ez a megosztott figyelés származik. */
    fun existingFor(w: Watch, list: List<Watch>): Watch? {
        val src = w.sharedFrom ?: return null
        return list.firstOrNull { it.id == src } ?: list.firstOrNull { it.sharedFrom == src }
    }

    fun decode(text: String): Pair<Watch, String>? {
        val (start, prefix) = listOf(PREFIX, PREFIX2).map { text.indexOf(it) to it }
            .filter { it.first >= 0 }.minByOrNull { it.first } ?: return null
        val code = text.substring(start + prefix.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (code.isEmpty() || code.length > 20_000) return null
        return runCatching {
            val bytes = inflate(Base64.getUrlDecoder().decode(code))
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.optInt("v", 1) <= 2) { "újabb REFI-verzió kódja" }
            val cur = json.optString("cur", "HUF").takeIf { c -> CURRENCIES.any { it.first == c } } ?: "HUF"
            val w = Watch.fromJson(json.getJSONObject("w")).sanitized() ?: error("érvénytelen figyelés")
            // Új azonosító: a saját figyeléseket sosem írja felül egy kapott kód
            val src = json.optString("src", "").takeIf { it.isNotBlank() && it.length <= 64 }
            searchOnly(w).copy(id = UUID.randomUUID().toString(), notify = true, sharedFrom = src) to cur
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
        flexDays = if (weeklyUntil != null) 0 else flexDays.coerceIn(0, 3),
        // A „minden héten” vége érvényes dátum legyen, az indulás után, legfeljebb MAX_WEEKS héten belül
        weeklyUntil = weeklyUntil?.let { u ->
            val until = runCatching { LocalDate.parse(u) }.getOrNull()
            val out = runCatching { LocalDate.parse(outboundDate) }.getOrNull()
            if (until == null || out == null || until.isBefore(out)) null
            else lastWeekly(out, until).toString()
        },
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

    fun fileName(today: LocalDate = LocalDate.now()) = tr("REFI-mentes", "REFI-backup", "REFI-Sicherung") + "-$today.json"

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
        // A Google árelőzménye csak a legelső dátumra szól: rugalmas figyelésnél csak akkor vetjük össze,
        // ha a legjobb ajánlat épp arra a napra esik
        val firstDay = w.datePairs(today).first().first
        if (w.isFlexible && best.departure?.take(10) != firstDay) return null
        // …és a Google-adat is erre a napra szóljon (nem egy korábbi, azóta elmúlt első napra)
        if (w.isFlexible && w.market?.forDate != firstDay) return null
        return marketVerdict(w, now, daysLeft, currency)
    }
    val lower = prices.count { it < now }
    val share = lower.toDouble() / prices.size
    // Az „eddigi legalacsonyabb” a kártyán látható minimummal egyezzen (az árgörbe csak az utolsó 120 mérést őrzi)
    val min = minOf(prices.min(), w.lowestPrice ?: Int.MAX_VALUE)
    val soon = daysLeft <= 14
    return when {
        now <= min -> Verdict(
            tr("Ez az eddigi legalacsonyabb ár – jó alkalom a foglalásra.", "This is the lowest price so far – a good time to book.", "Das ist der bisher niedrigste Preis – ein guter Zeitpunkt zum Buchen."),
            Verdict.Tone.GOOD,
        )
        share <= 0.25 -> Verdict(
            tr("Jó ár: az eddig mért árak legolcsóbb negyedében van.", "Good price: it's in the cheapest quarter of the prices seen so far.", "Guter Preis: Er liegt im günstigsten Viertel der bisherigen Preise."),
            Verdict.Tone.GOOD,
        )
        share >= 0.75 && !soon -> Verdict(
            tr(
                "Drágább, mint az eddigi árak többsége – ha nem sürgős, érdemes még várni.",
                "Pricier than most prices so far – if it's not urgent, it's worth waiting.",
                "Teurer als die meisten bisherigen Preise – wenn es nicht eilt, lohnt sich das Warten.",
            ),
            Verdict.Tone.WAIT,
        )
        share >= 0.75 -> Verdict(
            tr(
                "Drágább az eddigieknél, de két héten belül az árak ritkán esnek – ne várj sokat.",
                "Pricier than before, but prices rarely drop within two weeks of departure – don't wait long.",
                "Teurer als bisher, aber in den zwei Wochen vor dem Abflug fallen die Preise selten – warte nicht zu lange.",
            ),
            Verdict.Tone.NEUTRAL,
        )
        soon -> Verdict(
            tr("Átlagos ár; két héten belül az árak inkább emelkednek.", "Average price; within two weeks of departure prices tend to rise.", "Durchschnittlicher Preis; in den zwei Wochen vor dem Abflug steigen die Preise eher."),
            Verdict.Tone.NEUTRAL,
        )
        else -> Verdict(tr("Átlagos ár az eddigiekhez képest.", "Average price compared with the prices so far.", "Durchschnittlicher Preis im Vergleich zu den bisherigen Preisen."), Verdict.Tone.NEUTRAL)
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
    val band = currency?.let {
        tr(" (általában ${formatPrice(low, it)} – ${formatPrice(high, it)})", " (usually ${formatPrice(low, it)} – ${formatPrice(high, it)})", " (meist ${formatPrice(low, it)} – ${formatPrice(high, it)})")
    } ?: ""
    return when {
        now < low -> Verdict(tr("Olcsóbb a szokásosnál$band – jó alkalom a foglalásra.", "Cheaper than usual$band – a good time to book.", "Günstiger als üblich$band – ein guter Zeitpunkt zum Buchen."), Verdict.Tone.GOOD)
        now > high && daysLeft > 14 -> Verdict(
            tr("Drágább a szokásosnál$band – ha nem sürgős, érdemes várni.", "Pricier than usual$band – if it's not urgent, it's worth waiting.", "Teurer als üblich$band – wenn es nicht eilt, lohnt sich das Warten."),
            Verdict.Tone.WAIT,
        )
        now > high -> Verdict(
            tr("Drágább a szokásosnál$band, de két héten belül az árak ritkán esnek.", "Pricier than usual$band, but prices rarely drop within two weeks of departure.", "Teurer als üblich$band, aber in den zwei Wochen vor dem Abflug fallen die Preise selten."),
            Verdict.Tone.NEUTRAL,
        )
        else -> Verdict(tr("Szokásos ár ezen az úton$band.", "Usual price for this route$band.", "Üblicher Preis für diese Strecke$band."), Verdict.Tone.NEUTRAL)
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
    val ago = if (days < 1) tr("Ma", "Today", "Heute") else tr("$days napja", if (days == 1L) "1 day ago" else "$days days ago", if (days == 1L) "Vor 1 Tag" else "Vor $days Tagen")
    return tr(
        "$ago még ${formatPrice(first.price, currency)} volt – ennyit nyersz most: ${formatPrice(diff, currency)}",
        "$ago it was still ${formatPrice(first.price, currency)} – you save ${formatPrice(diff, currency)} now",
        "$ago lag der Preis noch bei ${formatPrice(first.price, currency)} – du sparst jetzt ${formatPrice(diff, currency)}",
    )
}
