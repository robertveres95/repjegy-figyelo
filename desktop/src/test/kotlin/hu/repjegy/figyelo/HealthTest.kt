package hu.repjegy.figyelo

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.fail

/**
 * Heti egészségellenőrzés (csak -Drefi.health=true esetén): biztosan létező, napi járatokkal
 * ellenőrzi, hogy minden árforrás ad-e értelmes adatot. Ha valami elromlott (pl. megváltozott
 * egy oldal formátuma), a teszt elbukik, és a workflow hibajegyet nyit a GitHubon.
 */
class HealthTest {
    @Test
    fun sources() {
        if (System.getProperty("refi.health") != "true") return
        Platform.current = DesktopPlatform
        val report = StringBuilder()
        val problems = mutableListOf<String>()
        val d = LocalDate.now().plusDays(21)

        fun w(from: String, to: String) = Watch(
            id = "health", from = from, to = to, outboundDate = d.toString(), returnDate = null,
            travelClass = 1, adults = 1, children = 0, infantsInSeat = 0, infantsOnLap = 0,
            bags = 0, stops = 0, targetPrice = 1, notify = false,
        )

        // Egy forrás akkor egészséges, ha 3 napi próbából legalább egy ad járatot (egy-egy
        // napon tényleg lehet, hogy nincs járat, vagy épp lassú a szolgáltatás)
        fun check(name: String, block: (Watch) -> List<Offer>, from: String, to: String) {
            var lastMsg = ""
            for (k in 0..2) {
                val watch = w(from, to).copy(outboundDate = d.plusDays(k.toLong()).toString())
                val r = runCatching { block(watch) }
                val offers = r.getOrNull()
                if (offers != null && offers.isNotEmpty() && offers.all { it.price > 0 }) {
                    report.appendLine("OK   $name $from→$to: ${offers.size} ajánlat, legolcsóbb ${offers.minOf { it.price }} Ft")
                    return
                }
                lastMsg = r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "0 ajánlat"
                Thread.sleep(2000)
            }
            problems += "$name $from→$to: $lastMsg"
            report.appendLine("FAIL $name $from→$to: $lastMsg")
        }

        check("Google Flights", { GoogleFlights.search(it, "HUF") }, "BUD", "LHR")
        check("Ryanair", { Ryanair.search(it, "HUF") }, "BUD", "STN")
        check("Wizz Air", { WizzAir.search(it, "HUF") }, "BUD", "LTN")

        val disc = runCatching { Discover.search("BUD", 0, 0, null, "HUF") }
        if ((disc.getOrNull()?.size ?: 0) >= 10) report.appendLine("OK   Felfedezés: ${disc.getOrNull()!!.size} célállomás")
        else {
            val msg = "Felfedezés (Ryanair bárhová): ${disc.exceptionOrNull()?.message ?: "csak ${disc.getOrNull()?.size} célállomás"}"
            problems += msg
            report.appendLine("FAIL $msg")
        }

        val rate = runCatching { Rates.convert(1.0, "EUR", "HUF") }
        if (rate.getOrNull()?.let { it in 250.0..600.0 } == true) report.appendLine("OK   Árfolyam: 1 EUR = ${rate.getOrNull()} HUF")
        else {
            problems += "Árfolyam: ${rate.exceptionOrNull()?.message ?: rate.getOrNull()}"
            report.appendLine("FAIL Árfolyam")
        }
        val wide = runCatching { Rates.convert(100.0, "ALL", "HUF") }
        if (wide.isSuccess) report.appendLine("OK   Tartalék árfolyam (ALL)") else {
            problems += "Tartalék árfolyam (ALL): ${wide.exceptionOrNull()?.message}"
            report.appendLine("FAIL Tartalék árfolyam")
        }

        File("build/diag").mkdirs()
        File("build/diag/health.txt").writeText(report.toString())
        println(report)
        if (problems.isNotEmpty()) fail("Hibás források:\n" + problems.joinToString("\n"))
    }
}
