package hu.repjegy.figyelo

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test

/**
 * Éles próba a valódi árforrásokkal (csak -Drefi.live=true esetén fut). Nem bukik el egy
 * forrás hibáján (azok külső szolgáltatások), hanem részletes jelentést ír:
 * build/diag/live.txt – ebből látszik, hogy minden forrás valóban ad-e értelmes adatot.
 */
class LiveSourcesTest {

    private val out = StringBuilder()

    @Test
    fun live() {
        if (System.getProperty("refi.live") != "true") return
        Platform.current = DesktopPlatform
        val d = LocalDate.now().plusDays(35)
        val cases = listOf(
            "BUD→STN csak oda, 1 felnőtt" to w("BUD", "STN", d, null),
            "BUD→London (minden reptér) oda-vissza" to w("BUD", "LHR,LGW,STN,LTN,LCY,SEN", d, d.plusDays(5)),
            "BUD→BCN oda-vissza, 2 felnőtt + 1 gyerek, kézipoggyász" to w("BUD", "BCN", d, d.plusDays(7), adults = 2, children = 1, bags = 3),
            "BUD→Milánó csak oda, feladott poggyász" to w("BUD", "MXP,LIN,BGY", d, null, checked = true),
            "BUD→JFK business, max 1 átszállás" to w("BUD", "JFK", d, d.plusDays(10), cls = 3, stops = 2),
            "Nem létező útvonal: DEB→LIS" to w("DEB", "LIS", d, null),
            "Wizz-próba: létező reptér, nem létező Wizz-útvonal BUD→JFK" to w("BUD", "JFK", d, null),
            "Wizz-próba: 13 hónap múlva BUD→LTN" to w("BUD", "LTN", LocalDate.now().plusMonths(13), null),
        )
        for ((name, watch) in cases) {
            out.appendLine("=== $name  (${watch.outboundDate}${watch.returnDate?.let { " – $it" } ?: ""})")
            run("Google Flights", watch) { GoogleFlights.search(watch, "HUF") }
            GoogleFlights.takeInsight(watch, "HUF").let { m ->
                out.appendLine("  [Google árelőzmény] " + (m?.let { "${it.points.size} nap, szokásos: ${it.typicalLow}–${it.typicalHigh}, utolsó: ${it.points.last().price}" } ?: "nincs"))
            }
            run("Ryanair", watch) { Ryanair.search(watch, "HUF") }
            WizzAir.lastRejection = ""
            run("Wizz Air", watch) { WizzAir.search(watch, "HUF") }
            if (WizzAir.lastRejection.isNotEmpty()) out.appendLine("  [Wizz elutasítás] ${WizzAir.lastRejection}")
            val all = runCatching { GoogleFlights.search(watch, "HUF") }.getOrDefault(emptyList()) +
                runCatching { Ryanair.search(watch, "HUF") }.getOrDefault(emptyList()) +
                runCatching { WizzAir.search(watch, "HUF") }.getOrDefault(emptyList())
            val ranked = PriceChecker.rank(watch, all)
            out.appendLine("  → rangsor első 3: " + ranked.take(3).joinToString(" | ") { "${it.price} Ft ${it.source}/${it.airline} ${it.departure} bags=${it.bagsIncluded}" })
            out.appendLine()
        }
        // Poggyász-kísérlet: beleszámolja-e a Google a poggyászdíjat? Ugyanazon járatok ára
        // poggyász nélkül, kézipoggyásszal és feladott poggyásszal.
        out.appendLine("=== POGGYÁSZ-KÍSÉRLET (Google, BUD→BGY és BUD→LTN, csak oda)")
        for (dest in listOf("BGY", "LTN")) {
            val variants = listOf(
                "nincs" to w("BUD", dest, d, null),
                "1 kézi" to w("BUD", dest, d, null, bags = 1),
                "feladott" to w("BUD", dest, d, null, checked = true),
            )
            val table = variants.map { (label, vw) ->
                val offers = runCatching { GoogleFlights.search(vw, "HUF") }.getOrElse { emptyList() }
                label to offers.associate { "${it.departure?.takeLast(5)} ${it.airline}" to it.price }
            }
            val keys = table.flatMap { it.second.keys }.distinct().sorted()
            out.appendLine("  $dest: " + table.joinToString(" | ") { it.first })
            keys.take(6).forEach { k -> out.appendLine("    $k: " + table.joinToString(" | ") { (it.second[k] ?: "-").toString() }) }
        }
        out.appendLine()
        out.appendLine("=== Google több repteres oda-vissza, 3 próbálkozás (hibajelzés + repterenkénti tartalék)")
        repeat(3) { n ->
            val lw = w("BUD", "LHR,LGW,STN,LTN,LCY,SEN", d.plusDays(n.toLong()), d.plusDays(5L + n))
            val t0 = System.currentTimeMillis()
            val r = runCatching { GoogleFlights.search(lw, "HUF") }
            out.appendLine("  #${n + 1}: " + r.fold({ "${it.size} ajánlat, legolcsóbb ${it.minOfOrNull { o -> o.price }}" }, { "HIBA: ${it.message}" }) +
                " (${System.currentTimeMillis() - t0} ms, utolsó: ${GoogleFlights.lastDebug})")
        }
        out.appendLine()
        out.appendLine("=== Árfolyam: 1 EUR = ${runCatching { Rates.convert(1.0, "EUR", "HUF") }.getOrElse { "HIBA: $it" }} HUF")
        out.appendLine("=== Frissítésfigyelő: ${runCatching { Updater.check()?.toString() ?: "nincs újabb (vagy nincs .msi)" }.getOrElse { "HIBA: $it" }}")
        File("build/diag").mkdirs()
        File("build/diag/live.txt").writeText(out.toString())
        println(out)
    }

    private fun run(source: String, watch: Watch, block: () -> List<Offer>) {
        val t0 = System.currentTimeMillis()
        val r = runCatching(block)
        val ms = System.currentTimeMillis() - t0
        r.onFailure { out.appendLine("  [$source] HIBA (${ms} ms): ${it.javaClass.simpleName}: ${it.message}") }
        r.onSuccess { offers ->
            out.appendLine("  [$source] ${offers.size} ajánlat (${ms} ms)" + if (source == "Google Flights") " · ${GoogleFlights.lastDebug}" else "")
            val problems = mutableListOf<String>()
            offers.forEach { o ->
                if (o.price <= 0) problems += "nem pozitív ár: $o"
                if (o.price > 20_000_000) problems += "irreális ár: $o"
                val dep = o.departure?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                if (o.departure != null && dep == null) problems += "olvashatatlan indulás: ${o.departure}"
                if (dep != null && dep.toLocalDate().toString() != watch.outboundDate) problems += "más napi indulás: ${o.departure} (kért: ${watch.outboundDate})"
                if (watch.returnDate != null && o.returnDeparture != null &&
                    !o.returnDeparture.startsWith(watch.returnDate)) problems += "más napi visszaút: ${o.returnDeparture}"
                val arr = o.arrival?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                if (dep != null && arr != null && arr.isBefore(dep.minusHours(14))) problems += "érkezés az indulás előtt: $o"
                if (o.url.isNullOrBlank()) problems += "nincs link: ${o.source}"
                else if (!o.url.startsWith("https://")) problems += "nem https link: ${o.url}"
                val codes = (watch.from + "," + watch.to).split(',')
                if (o.fromCode != null && o.fromCode !in codes) problems += "váratlan reptér: ${o.fromCode}"
            }
            offers.take(3).forEach { o ->
                out.appendLine("     ${o.price} Ft · ${o.airline} · ${o.fromCode}→${o.toCode} · ${o.departure}→${o.arrival} · átsz:${o.stops}" +
                    (o.returnDeparture?.let { " · vissza ${it}→${o.returnArrival}" } ?: "") +
                    " · bags=${o.bagsIncluded} · ${o.note ?: ""} · ${o.url?.take(90)}")
            }
            problems.distinct().take(8).forEach { out.appendLine("     !! $it") }
        }
    }

    private fun w(
        from: String, to: String, out: LocalDate, ret: LocalDate?,
        adults: Int = 1, children: Int = 0, bags: Int = 0, checked: Boolean = false, cls: Int = 1, stops: Int = 0,
    ) = Watch(
        id = "live", from = from, to = to, outboundDate = out.toString(), returnDate = ret?.toString(),
        travelClass = cls, adults = adults, children = children, infantsInSeat = 0, infantsOnLap = 0,
        bags = bags, checkedBag = checked, stops = stops, targetPrice = 1, notify = false,
    )
}
