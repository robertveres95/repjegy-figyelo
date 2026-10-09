package hu.repjegy.figyelo

import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test

/**
 * Felderítő próba (csak -Drefi.live=true): benne van-e a Google Flights válaszában az útvonal
 * árelőzménye (időpont–ár párok). A nyers oldalt is elmenti, hogy elemezni lehessen.
 */
class HistoryProbeTest {
    @Test
    fun probe() {
        if (System.getProperty("refi.live") != "true") return
        Platform.current = DesktopPlatform
        val dir = File("build/diag").apply { mkdirs() }
        val out = StringBuilder()
        val d = LocalDate.now().plusDays(35)
        val cases = listOf(
            "bud-bcn-rt" to w("BUD", "BCN", d, d.plusDays(7)),
            "bud-stn-ow" to w("BUD", "STN", d, null),
            "bud-cdg-rt" to w("BUD", "CDG", d, d.plusDays(5)),
        )
        Http.setCookie("www.google.com", ".google.com", "SOCS", "CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
        Http.setCookie("www.google.com", ".google.com", "CONSENT", "YES+")
        for ((name, watch) in cases) {
            val url = GoogleFlights.searchUrl(watch, "HUF")
            val res = runCatching { Http.request(url, headers = mapOf("Accept" to "text/html,application/xhtml+xml")) }
            val body = res.getOrNull()?.body.orEmpty()
            out.appendLine("=== $name  HTTP ${res.getOrNull()?.code} ${body.length} bájt  $url")
            File(dir, "probe-$name.html").writeText(body)
            // Időbélyeg–ár párok: [1696176000000,25210] vagy [1696176000,25210]
            val pair = Regex("""\[(1[6-8]\d{8}(?:\d{3})?),(\d{3,7})\]""")
            val hits = pair.findAll(body).toList()
            out.appendLine("  időbélyeg–ár párok: ${hits.size}")
            hits.take(40).forEach { m ->
                val t = m.groupValues[1].toLong().let { if (it > 9_999_999_999L) it / 1000 else it }
                out.appendLine("    ${Instant.ofEpochSecond(t)}  ${m.groupValues[2]}   …${body.substring(maxOf(0, m.range.first - 60), m.range.first).replace("\n", " ")}")
            }
            listOf("price_history", "priceHistory", "typical", "Typical", "árelőzm", "Price history", "lowest", "low for").forEach { k ->
                val i = body.indexOf(k)
                if (i >= 0) out.appendLine("  kulcsszó '$k' @${i}: …${body.substring(maxOf(0, i - 80), minOf(body.length, i + 200)).replace("\n", " ")}")
            }
            val cbs = Regex("""AF_initDataCallback\(\{key: '([^']+)'[^}]*?data:""").findAll(body).map { it.groupValues[1] }.toList()
            out.appendLine("  AF_initDataCallback kulcsok: $cbs")
            out.appendLine()
            Thread.sleep(1500)
        }
        File(dir, "history-probe.txt").writeText(out.toString())
    }

    private fun w(from: String, to: String, out: LocalDate, ret: LocalDate?) = Watch(
        id = "p", from = from, to = to, outboundDate = out.toString(), returnDate = ret?.toString(), travelClass = 1,
        adults = 1, children = 0, infantsInSeat = 0, infantsOnLap = 0, bags = 0, stops = 0, targetPrice = 1, notify = false,
    )
}
