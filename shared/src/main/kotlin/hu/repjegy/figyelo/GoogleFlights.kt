package hu.repjegy.figyelo

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

/**
 * Google Flights kulcs nélkül: ugyanazt az oldalt kéri le, amit a böngésző,
 * és a beágyazott adatból olvassa ki a járatokat.
 * A lekérdezés (tfs) és a feldolgozás a nyílt forrású fast-flights könyvtár alapján készült.
 */
object GoogleFlights {
    const val NAME = "Google Flights"

    /** A lekérdezés címe ([hl]: az oldal nyelve; a feldolgozás az angol oldalra épül). */
    fun searchUrl(w: Watch, currency: String, hl: String = "en"): String =
        "https://www.google.com/travel/flights?tfs=" +
            URLEncoder.encode(tfs(w), "UTF-8") + "&hl=$hl&curr=$currency" +
            // „Minden járat és ár” nézet (a fast-flights is ezt kéri), különben kimaradhat a legolcsóbb
            "&tfu=EgQIABABIgA"

    /** Ugyanez a keresés a felhasználónak megnyitva: a felület nyelvén. */
    fun userUrl(w: Watch, currency: String): String = searchUrl(w, currency, Lang.code)

    /** Diagnosztikához: az utolsó lekérés nyers jellemzői (hány elem, hiba-jelzés). */
    @Volatile
    var lastDebug: String = ""

    private class Fetched(val offers: List<Offer>, val seen: Int, val errorStatus: Boolean)

    /**
     * [storeInsight]: az útvonal árelőzményét csak a figyelés „fő” keresésénél tesszük félre (a rugalmas
     * dátumok és a repterenkénti tartalék kérései nem kellenek, és csak foglalnák a tárolót).
     */
    fun search(w: Watch, currency: String, allowFallback: Boolean = true, storeInsight: Boolean = false): List<Offer> {
        // Élő próbák: a Google néha hibajelzést („errorHasStatus”) ad, főleg több repteres
        // oda-vissza keresésre. Ilyenkor egyszer újrapróbáljuk, majd repterenként kérdezünk.
        val first = fetch(w, currency, storeInsight)
        if (!first.errorStatus) return first.offers
        Thread.sleep(1500)
        val again = fetch(w, currency, storeInsight)
        if (!again.errorStatus) return again.offers
        val pairs = pairsOf(w)
        if (pairs.size > 1 && allowFallback) {
            val pairWatches = pairs.take(6).map { (o, d) -> w.copy(from = o, to = d) }
            val results = pairWatches.map { pw ->
                Thread.sleep(700)
                // Korlátozásnál (429) nem bombázzuk tovább a Google-t
                try { fetch(pw, currency, storeInsight) } catch (e: FatalSourceException) { throw e } catch (e: Exception) { null }
            }
            // Több repteres keresésnél a Google néha csak repterenként válaszol: az árelőzmény ilyenkor az első
            // (fapados-bázissal kezdődő) reptérpáré – így a görbén akkor is ott a szürke előzmény
            if (storeInsight) {
                val mainKey = searchUrl(w, currency)
                if (!insights.containsKey(mainKey)) {
                    pairWatches.firstNotNullOfOrNull { pw -> insights[searchUrl(pw, currency)] }?.let { insights[mainKey] = it }
                }
                pairWatches.forEach { pw -> searchUrl(pw, currency).takeIf { it != mainKey }?.let { insights.remove(it) } }
            }
            if (results.any { it != null && !it.errorStatus }) {
                val offers = results.filterNotNull().flatMap { it.offers }
                val failed = results.count { it == null || it.errorStatus }
                if (failed > 0) throw PartialSourceException(offers, failed, results.size, null)
                return offers
            }
        }
        // Nem „nincs járat”: a forrás hibázott, így a korábbi ár megmarad
        throw IOException(trs("A Google erre a keresésre most hibát jelzett", "Google reported an error for this search", "Google hat für diese Suche einen Fehler gemeldet"))
    }

    private val insights = java.util.concurrent.ConcurrentHashMap<String, MarketInsight>()

    /** Az adott keresés (pontos dátumokkal) legutóbb kapott árelőzménye; kiveszi a tárolóból. */
    fun takeInsight(w: Watch, currency: String): MarketInsight? =
        insights.remove(searchUrl(w.exact(), currency))

    /**
     * Az „árbetekintés” blokk megkeresése a válaszban:
     * [szint, [null, mostani], [null, …], [null, eltérés], [null, szokásos alsó], [null, szokásos felső], …, [[[időbélyeg ms, ár], …]], …]
     * (a ds:1 adat 5. eleme; a helye változhat, ezért keressük)
     */
    internal fun findInsight(node: Any?, depth: Int = 0): MarketInsight? {
        if (node !is JSONArray || depth > 14) return null
        if (node.length() >= 11) {
            // A pontlista egy további tömbbe csomagolva jön: [[[ms, ár], …]] (a régebbi alakot is elfogadjuk)
            val raw = node.optJSONArray(10)
            val pts = raw?.optJSONArray(0)?.takeIf { it.optJSONArray(0) != null } ?: raw
            fun money(i: Int) = node.optJSONArray(i)?.takeIf { it.length() == 2 && it.isNull(0) }?.optInt(1, 0)?.takeIf { it > 0 }
            if (pts != null && pts.length() >= 2 && money(1) != null) {
                val parsed = (0 until pts.length()).mapNotNull { i ->
                    val p = pts.optJSONArray(i) ?: return@mapNotNull null
                    val t = p.optLong(0, 0L)
                    val v = p.optInt(1, 0)
                    if (t > 1_500_000_000_000L && v > 0) PricePoint(t, v) else null
                }
                if (parsed.size >= 2 && parsed.size == pts.length()) {
                    return MarketInsight(parsed.sortedBy { it.time }, money(4), money(5), System.currentTimeMillis())
                }
            }
        }
        for (i in 0 until node.length()) {
            findInsight(node.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun fetch(w: Watch, currency: String, storeInsight: Boolean = false): Fetched {
        // Az EU-s beleegyezési oldal átugrása
        Http.setCookie("www.google.com", ".google.com", "SOCS", "CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
        Http.setCookie("www.google.com", ".google.com", "CONSENT", "YES+")

        val url = searchUrl(w, currency)
        val res = Http.request(url, headers = mapOf("Accept" to "text/html,application/xhtml+xml"))
        if (res.code == 429) throw FatalSourceException(trs("A Google ideiglenesen korlátozta a lekérdezést", "Google is temporarily limiting searches", "Google begrenzt die Suchen vorübergehend"))
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")

        val script = Regex("""<script class="ds:1"[^>]*>([\s\S]*?)</script>""").find(res.body)?.groupValues?.get(1)
            ?: if (res.body.contains("consent.google")) {
                throw IOException(trs("A Google beleegyezési oldalt adott vissza", "Google returned a consent page", "Google hat eine Einwilligungsseite zurückgegeben"))
            } else {
                throw IOException(trs("Nem található járatadat az oldalon (változhatott a formátum)", "No flight data found on the page (the format may have changed)", "Keine Flugdaten auf der Seite gefunden (das Format hat sich evtl. geändert)"))
            }

        val data = script.substringAfter("data:").substringBeforeLast(",").trim()
        if (data.endsWith("errorHasStatus: true")) {
            lastDebug = "errorHasStatus"
            return Fetched(emptyList(), 0, errorStatus = true)
        }
        val payload = JSONArray(data)
        // Az útvonal árelőzménye (ha a Google ad hozzá): a keresés címével tároljuk, az ellenőrző innen veszi
        if (storeInsight) {
            if (insights.size > 30) insights.clear() // el nem vitt régiek (pl. közben törölt figyelés)
            runCatching { findInsight(payload) }.getOrNull()?.let { insights[url] = it }
        }

        val offers = mutableListOf<Offer>()
        val openUrl = userUrl(w, currency)
        var seen = 0
        var failed = 0
        for (groupIndex in listOf(2, 3)) {
            val group = payload.optJSONArray(groupIndex)?.optJSONArray(0) ?: continue
            for (i in 0 until group.length()) {
                val item = group.optJSONArray(i) ?: continue
                seen++
                val r = runCatching { parseItem(item, openUrl, w, currency) }
                if (r.isFailure) failed++
                r.getOrNull()?.let(offers::add)
            }
        }
        // Ha voltak járatok, de egyiket sem tudtuk értelmezni, az formátumváltozás –
        // ezt hibaként jelezzük, ne „nincs járat”-ként
        if (seen > 0 && offers.isEmpty() && failed > 0) {
            throw IOException(trs("A Google válaszát nem sikerült értelmezni (változhatott a formátum)", "Couldn't read Google's response (the format may have changed)", "Die Antwort von Google konnte nicht gelesen werden (das Format hat sich evtl. geändert)"))
        }
        lastDebug = "payload=${payload.length()} elem=$seen hibás=$failed"
        return Fetched(offers, seen, errorStatus = false)
    }

    private fun parseItem(item: JSONArray, url: String, w: Watch, currency: String): Offer? {
        val flight = item.getJSONArray(0)
        val raw = (item.optJSONArray(1)?.optJSONArray(0)?.opt(1) as? Number)?.toDouble() ?: return null
        if (!raw.isFinite() || raw <= 0 || raw > 1e9) return null
        val price = kotlin.math.ceil(raw).toInt()
        val airlines = flight.optJSONArray(1)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.opt(it) as? String }.distinct().joinToString(", ")
        }?.takeIf { it.isNotBlank() }
        val segments = flight.getJSONArray(2)
        if (segments.length() == 0) return null
        val first = segments.getJSONArray(0)
        val last = segments.getJSONArray(segments.length() - 1)
        return Fees.apply(Offer(
            price = price,
            source = NAME,
            airline = airlines,
            fromCode = first.opt(3) as? String,
            toCode = last.opt(6) as? String,
            departure = dateTime(first.optJSONArray(20), first.optJSONArray(8)),
            arrival = dateTime(last.optJSONArray(21), last.optJSONArray(10)),
            stops = segments.length() - 1,
            url = url,
        ), w, currency, includeInfants = false)
    }

    /** [2026, 11, 5] + [6, 25] → "2026-11-05T06:25". A Google a nulla értékeket elhagyja. */
    private fun dateTime(date: JSONArray?, time: JSONArray?): String? {
        if (date == null || date.length() < 3) return null
        val y = (date.opt(0) as? Number)?.toInt() ?: return null
        val mo = (date.opt(1) as? Number)?.toInt() ?: return null
        val d = (date.opt(2) as? Number)?.toInt() ?: return null
        val h = (time?.opt(0) as? Number)?.toInt() ?: 0
        val mi = (time?.opt(1) as? Number)?.toInt() ?: 0
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d", y, mo, d, h, mi)
    }

    // ------------------------------------------------------------ tfs (protobuf) összeállítása

    private fun tfs(w: Watch): String {
        val maxStops = when (w.stops) {
            1 -> 0
            2 -> 1
            3 -> 2
            else -> null
        }
        val legs = buildList {
            add(Triple(w.outboundDate, w.from.split(','), w.to.split(',')))
            w.returnDate?.let { add(Triple(it, w.to.split(','), w.from.split(','))) }
        }
        val passengers = Pb().apply {
            repeat(w.adults) { varint(1) }
            repeat(w.children) { varint(2) }
            repeat(w.infantsInSeat) { varint(3) }
            repeat(w.infantsOnLap) { varint(4) }
        }.bytes()

        val info = Pb().apply {
            for ((legIndex, leg) in legs.withIndex()) {
                val (date, origins, destinations) = leg
                message(3) {
                    string(2, date)
                    if (maxStops != null) int(5, maxStops)
                    // Odaút időablaka (fast-flights: earliest/latest_departure_hour = 8/9)
                    if (legIndex == 0) {
                        w.depFrom?.let { int(8, it) }
                        w.depTo?.takeIf { it < 24 }?.let { int(9, it) }
                    }
                    origins.forEach { code -> message(13) { string(2, code) } }
                    destinations.forEach { code -> message(14) { string(2, code) } }
                }
            }
            bytesField(8, passengers)
            int(9, w.travelClass.coerceIn(1, 4))
            // Poggyászt itt NEM kérünk: a Google csak részben számolja bele a díjat
            // (élő próba: Ryanair-kézipoggyász igen, feladott és Wizz nem) – lásd Fees
            int(19, if (w.isRoundTrip) 1 else 2)
        }.bytes()
        return Base64.getEncoder().encodeToString(info)
    }

    /** Minimális protobuf-író. */
    private class Pb {
        private val out = ByteArrayOutputStream()

        fun bytes(): ByteArray = out.toByteArray()

        fun varint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun varint(value: Int) = varint(value.toLong())

        private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

        fun int(field: Int, value: Int) {
            tag(field, 0)
            varint(value.toLong())
        }

        fun bytesField(field: Int, data: ByteArray) {
            tag(field, 2)
            varint(data.size.toLong())
            out.write(data)
        }

        fun string(field: Int, value: String) = bytesField(field, value.toByteArray(Charsets.UTF_8))

        fun message(field: Int, block: Pb.() -> Unit) = bytesField(field, Pb().apply(block).bytes())
    }
}
