package hu.repjegy.figyelo

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Árelőrejelzés

/**
 * Egyszerű, őszinte becslés arról, merre tart az ár: az utóbbi (legfeljebb 14) nap méréseire illesztett
 * egyenes meredeksége, és az indulásig hátralévő idő (az utolsó 3 hétben az árak jellemzően emelkednek).
 * Nem jóslat: legalább 6 mérés és 2 nap kell hozzá, különben nem mondunk semmit.
 */
fun forecastFor(w: Watch, today: LocalDate = LocalDate.now(), nowMs: Long = System.currentTimeMillis()): Verdict? {
    val best = w.bestOffer ?: return null
    if (!w.comparable(best)) return null
    val depDay = best.departure?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: runCatching { LocalDate.parse(w.outboundDate) }.getOrNull() ?: return null
    val daysLeft = ChronoUnit.DAYS.between(today, depDay)
    if (daysLeft < 0) return null
    if (daysLeft <= 21) {
        return Verdict(
            tr(
                "Előrejelzés: $daysLeft nap van az indulásig – ilyenkor az árak inkább emelkednek, nagy esés ritka.",
                "Forecast: $daysLeft ${if (daysLeft == 1L) "day" else "days"} until departure – prices tend to rise now, big drops are rare.",
                "Prognose: noch $daysLeft ${if (daysLeft == 1L) "Tag" else "Tage"} bis zum Abflug – jetzt steigen die Preise eher, große Preisstürze sind selten.",
            ),
            Verdict.Tone.NEUTRAL,
        )
    }
    val recent = w.history.filter { nowMs - it.time <= 14 * 86_400_000L }
    if (recent.size < 6) return null
    val spanDays = (recent.maxOf { it.time } - recent.minOf { it.time }) / 86_400_000.0
    if (spanDays < 2.0) return null
    val slopePct = trendPercentPerDay(recent) ?: return null
    return when {
        slopePct <= -0.5 -> Verdict(
            tr(
                "Előrejelzés: csökkenő trend (naponta átlagosan kb. ${pctText(slopePct)}) – ha nem sürgős, érdemes még figyelni.",
                "Forecast: falling trend (about ${pctText(slopePct)} a day on average) – if it's not urgent, keep watching.",
                "Prognose: fallender Trend (im Schnitt etwa ${pctText(slopePct)} pro Tag) – wenn es nicht eilt, beobachte weiter.",
            ),
            Verdict.Tone.WAIT,
        )
        slopePct >= 0.5 -> Verdict(
            tr(
                "Előrejelzés: emelkedő trend (naponta átlagosan kb. +${pctText(slopePct)}) – ha jó az ár, ne várj sokáig.",
                "Forecast: rising trend (about +${pctText(slopePct)} a day on average) – if the price is good, don't wait long.",
                "Prognose: steigender Trend (im Schnitt etwa +${pctText(slopePct)} pro Tag) – wenn der Preis gut ist, warte nicht zu lange.",
            ),
            Verdict.Tone.GOOD,
        )
        else -> null // stabil: nincs mit mondani (a kártyát nem zsúfoljuk)
    }
}

/** Az ár napi változása a középár százalékában (legkisebb négyzetek), null ha nem értelmezhető. */
internal fun trendPercentPerDay(points: List<PricePoint>): Double? {
    if (points.size < 2) return null
    val t0 = points.minOf { it.time }
    val xs = points.map { (it.time - t0) / 86_400_000.0 }
    val ys = points.map { it.price.toDouble() }
    val mx = xs.average()
    val my = ys.average()
    val sxx = xs.sumOf { (it - mx) * (it - mx) }
    if (sxx <= 0.0 || my <= 0.0) return null
    val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
    return sxy / sxx / my * 100.0
}

private fun pctText(p: Double): String {
    val v = abs(p)
    return if (v < 10) "%.1f%%".format(java.util.Locale.ROOT, v).let { if (Lang.en) it else it.replace('.', ',') } else "${v.roundToInt()}%"
}

// ---------------------------------------------------------------- Csoportos költség

/**
 * A teljes becsült útiköltség fejenként: jegy (a becsült poggyászdíjjal) + a transzfer, elosztva az
 * ülőhelyes utasok között (az ölben utazó csecsemő díja is benne van a jegyárban). Egy főnél nincs mit osztani.
 */
fun groupCostLine(w: Watch, best: Offer, currency: String, transferTotal: Int?): String? {
    val people = w.seatedPassengers
    if (people < 2) return null
    val total = best.price + (transferTotal ?: 0)
    val per = total / people
    val parts = buildList {
        add(tr("jegy", "ticket", "Ticket"))
        if (w.wantsBags && best.bagsIncluded) add(tr("poggyász", "bags", "Gepäck"))
        if (transferTotal != null && transferTotal > 0) add(tr("transzfer", "transfer", "Transfer"))
    }.joinToString(" + ")
    return tr(
        "Fejenként kb. ${formatPrice(per, currency)} ($people fő, $parts: ${formatPrice(total, currency)})",
        "About ${formatPrice(per, currency)} per person ($people people, $parts: ${formatPrice(total, currency)})",
        "Etwa ${formatPrice(per, currency)} pro Person ($people Personen, $parts: ${formatPrice(total, currency)})",
    )
}

// ---------------------------------------------------------------- Naptár

/** Egy naptárbejegyzés (helyi idők; [zone]: ha ismert az időzóna, pontos időpontként kerül a naptárba). */
data class CalEvent(
    val title: String,
    val start: LocalDateTime,
    val startZone: ZoneId?,
    val end: LocalDateTime?,
    val endZone: ZoneId?,
    val location: String,
    val notes: String,
) {
    /** Kezdés epoch ms-ban (ismeretlen zónánál az eszköz időzónája szerint). */
    fun startMillis(): Long = start.atZone(startZone ?: ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun endMillis(): Long = (end?.atZone(endZone ?: startZone ?: ZoneId.systemDefault())?.toInstant()?.toEpochMilli())
        ?.takeIf { it > startMillis() } ?: (startMillis() + 2 * 3_600_000L)
}

object RefiCalendar {
    /**
     * A legjobb ajánlat útjai naptárbejegyzésként (oda, és ha van, vissza). A repülőjegyek ideje az
     * indulási / érkezési reptér helyi ideje – az országból meghatározott időzónával válik pontos időponttá.
     */
    fun eventsFor(w: Watch, o: Offer): List<CalEvent> {
        val list = mutableListOf<CalEvent>()
        val from = o.fromCode ?: w.from.substringBefore(',')
        val to = o.toCode ?: w.to.substringBefore(',')
        val link = o.url?.takeIf { it.startsWith("https://") }
        fun notes(): String = listOfNotNull(
            o.airline?.let { tr("Légitársaság: $it", "Airline: $it", "Fluggesellschaft: $it") },
            tr(
                "Ár a REFI szerint: ${formatPrice(o.price, Store.settings.value.currency)} (a foglaláskor ellenőrizd!)",
                "Price according to REFI: ${formatPrice(o.price, Store.settings.value.currency)} (check it when booking!)",
                "Preis laut REFI: ${formatPrice(o.price, Store.settings.value.currency)} (prüfe ihn bei der Buchung!)",
            ),
            link?.let { tr("Foglalás: $it", "Booking: $it", "Buchung: $it") },
        ).joinToString("\n")
        parse(o.departure)?.let { dep ->
            list += CalEvent(
                "✈ $from → $to" + (o.airline?.let { " ($it)" } ?: ""),
                dep, zoneFor(from), arrivalIf(from, to, o.arrival), zoneFor(to), tr("$from repülőtér", "$from airport", "Flughafen $from"), notes(),
            )
        }
        parse(o.returnDeparture)?.let { dep ->
            list += CalEvent(
                "✈ $to → $from" + (o.airline?.let { " ($it)" } ?: ""),
                dep, zoneFor(to), arrivalIf(to, from, o.returnArrival), zoneFor(from), tr("$to repülőtér", "$to airport", "Flughafen $to"), notes(),
            )
        }
        return list
    }

    /**
     * Az érkezés csak akkor használható, ha mindkét reptér zónáját ismerjük (vagy egyikét sem): különben az
     * érkezési helyi időt rossz zónában értelmeznénk – ilyenkor a naptár 2 órás bejegyzést kap.
     */
    private fun arrivalIf(from: String, to: String, arrival: String?): LocalDateTime? =
        if ((zoneFor(from) == null) == (zoneFor(to) == null)) parse(arrival) else null

    private fun parse(s: String?): LocalDateTime? = s?.let { runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull() }

    /** iCalendar (.ics) szöveg – Windowson ezt nyitja meg az Outlook / Naptár app. Az idők UTC-ben (pontos időpont). */
    fun ics(events: List<CalEvent>, nowMs: Long = System.currentTimeMillis()): String {
        val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val floating = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        fun stamp(t: LocalDateTime, z: ZoneId?): String =
            if (z != null) t.atZone(z).withZoneSameInstant(ZoneOffset.UTC).format(utc) else t.format(floating)
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//REFI//repjegy-figyelo//HU\r\nCALSCALE:GREGORIAN\r\n")
        events.forEachIndexed { i, e ->
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:refi-$nowMs-$i@repjegy-figyelo\r\n")
            sb.append("DTSTAMP:").append(java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).format(utc)).append("\r\n")
            sb.append("DTSTART:").append(stamp(e.start, e.startZone)).append("\r\n")
            // Hibás (a kezdés előtti) vagy hiányzó érkezésnél 2 órás bejegyzés; a zónák nem keveredhetnek
            val realEnd = e.end?.takeIf { e.endMillis() > e.startMillis() && (e.endZone == null) == (e.startZone == null) }
            val endStamp = if (realEnd != null) stamp(realEnd, e.endZone) else stamp(e.start.plusHours(2), e.startZone)
            sb.append("DTEND:").append(endStamp).append("\r\n")
            sb.append(fold("SUMMARY:" + esc(e.title)))
            sb.append(fold("LOCATION:" + esc(e.location)))
            sb.append(fold("DESCRIPTION:" + esc(e.notes)))
            sb.append("END:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** RFC 5545: legfeljebb 75 bájtos sorok (UTF-8-ban, karakterhatáron törve). */
    private fun fold(line: String): String {
        val out = StringBuilder()
        var bytes = 0
        for (ch in line) {
            val n = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + n > 74) { out.append("\r\n "); bytes = 1 }
            out.append(ch); bytes += n
        }
        return out.append("\r\n").toString()
    }

    /** A reptér időzónája az országa alapján (a több zónás országoknál csak a biztos esetekben). */
    fun zoneFor(code: String?): ZoneId? {
        val cc = Airports.countryOf(code) ?: return null
        // Kanári-szigetek, Azori-szigetek, Madeira: más zóna, mint az anyaország
        when (code?.uppercase()) {
            "LPA", "TFS", "TFN", "ACE", "FUE", "SPC", "GMZ", "VDE" -> return ZoneId.of("Atlantic/Canary")
            "PDL", "TER", "HOR", "PIX", "SMA", "FLW", "CVU", "GRW", "SJZ" -> return ZoneId.of("Atlantic/Azores")
            "FNC", "PXO" -> return ZoneId.of("Atlantic/Madeira")
        }
        return ZONES[cc]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    }

    private val ZONES = mapOf(
        "HU" to "Europe/Budapest", "AT" to "Europe/Vienna", "DE" to "Europe/Berlin", "CH" to "Europe/Zurich",
        "IT" to "Europe/Rome", "MT" to "Europe/Malta", "FR" to "Europe/Paris", "BE" to "Europe/Brussels",
        "NL" to "Europe/Amsterdam", "LU" to "Europe/Luxembourg", "ES" to "Europe/Madrid", "PT" to "Europe/Lisbon",
        "GB" to "Europe/London", "IE" to "Europe/Dublin", "IS" to "Atlantic/Reykjavik", "DK" to "Europe/Copenhagen",
        "NO" to "Europe/Oslo", "SE" to "Europe/Stockholm", "FI" to "Europe/Helsinki", "EE" to "Europe/Tallinn",
        "LV" to "Europe/Riga", "LT" to "Europe/Vilnius", "PL" to "Europe/Warsaw", "CZ" to "Europe/Prague",
        "SK" to "Europe/Bratislava", "SI" to "Europe/Ljubljana", "HR" to "Europe/Zagreb", "BA" to "Europe/Sarajevo",
        "RS" to "Europe/Belgrade", "ME" to "Europe/Podgorica", "MK" to "Europe/Skopje", "AL" to "Europe/Tirane",
        "XK" to "Europe/Belgrade", "GR" to "Europe/Athens", "CY" to "Asia/Nicosia", "BG" to "Europe/Sofia",
        "RO" to "Europe/Bucharest", "MD" to "Europe/Chisinau", "UA" to "Europe/Kyiv", "TR" to "Europe/Istanbul",
        "GE" to "Asia/Tbilisi", "AM" to "Asia/Yerevan", "AZ" to "Asia/Baku", "IL" to "Asia/Jerusalem",
        "JO" to "Asia/Amman", "EG" to "Africa/Cairo", "MA" to "Africa/Casablanca", "TN" to "Africa/Tunis",
        "AE" to "Asia/Dubai", "QA" to "Asia/Qatar", "SA" to "Asia/Riyadh", "JP" to "Asia/Tokyo",
        "TH" to "Asia/Bangkok", "SG" to "Asia/Singapore", "IN" to "Asia/Kolkata", "CN" to "Asia/Shanghai",
        "KR" to "Asia/Seoul", "MV" to "Indian/Maldives", "LK" to "Asia/Colombo", "KE" to "Africa/Nairobi",
        "ZA" to "Africa/Johannesburg", "CV" to "Atlantic/Cape_Verde",
    )
}
