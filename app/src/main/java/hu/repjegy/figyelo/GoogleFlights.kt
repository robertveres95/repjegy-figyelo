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

    fun searchUrl(w: Watch, currency: String): String =
        "https://www.google.com/travel/flights?tfs=" +
            URLEncoder.encode(tfs(w), "UTF-8") + "&hl=en&curr=$currency"

    fun search(w: Watch, currency: String): List<Offer> {
        // Az EU-s beleegyezési oldal átugrása
        Http.setCookie("www.google.com", ".google.com", "SOCS", "CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
        Http.setCookie("www.google.com", ".google.com", "CONSENT", "YES+")

        val url = searchUrl(w, currency)
        val res = Http.request(url, headers = mapOf("Accept" to "text/html,application/xhtml+xml"))
        if (res.code == 429) throw FatalSourceException("A Google ideiglenesen korlátozta a lekérdezést")
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")

        val script = Regex("""<script class="ds:1"[^>]*>([\s\S]*?)</script>""").find(res.body)?.groupValues?.get(1)
            ?: if (res.body.contains("consent.google")) {
                throw IOException("A Google beleegyezési oldalt adott vissza")
            } else {
                throw IOException("Nem található járatadat az oldalon (változhatott a formátum)")
            }

        val data = script.substringAfter("data:").substringBeforeLast(",").trim()
        if (data.endsWith("errorHasStatus: true")) return emptyList()
        val payload = JSONArray(data)

        val offers = mutableListOf<Offer>()
        for (groupIndex in listOf(2, 3)) {
            val group = payload.optJSONArray(groupIndex)?.optJSONArray(0) ?: continue
            for (i in 0 until group.length()) {
                val item = group.optJSONArray(i) ?: continue
                runCatching { parseItem(item, url) }.getOrNull()?.let(offers::add)
            }
        }
        return offers
    }

    private fun parseItem(item: JSONArray, url: String): Offer? {
        val flight = item.getJSONArray(0)
        val price = (item.optJSONArray(1)?.optJSONArray(0)?.opt(1) as? Number)?.toInt() ?: return null
        if (price <= 0) return null
        val airlines = flight.optJSONArray(1)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.opt(it) as? String }.distinct().joinToString(", ")
        }?.takeIf { it.isNotBlank() }
        val segments = flight.getJSONArray(2)
        if (segments.length() == 0) return null
        val first = segments.getJSONArray(0)
        val last = segments.getJSONArray(segments.length() - 1)
        return Offer(
            price = price,
            source = NAME,
            airline = airlines,
            fromCode = first.opt(3) as? String,
            toCode = last.opt(6) as? String,
            departure = dateTime(first.optJSONArray(20), first.optJSONArray(8)),
            arrival = dateTime(last.optJSONArray(21), last.optJSONArray(10)),
            stops = segments.length() - 1,
            url = url,
            bagsIncluded = true, // a kért poggyász becsült díját a Google beleszámolja
        )
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
            for ((date, origins, destinations) in legs) {
                message(3) {
                    string(2, date)
                    if (maxStops != null) int(5, maxStops)
                    origins.forEach { code -> message(13) { string(2, code) } }
                    destinations.forEach { code -> message(14) { string(2, code) } }
                }
            }
            bytesField(8, passengers)
            int(9, w.travelClass.coerceIn(1, 4))
            if (w.bags > 0 || w.checkedBag) {
                message(13) {
                    if (w.bags > 0) int(2, 1)
                    if (w.checkedBag) int(3, 1)
                }
            }
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
