package hu.repjegy.figyelo

import kotlin.math.ceil

/**
 * A városközponttól távoli (főleg fapados) repterek: a legolcsóbb szokásos tömegközlekedés a
 * belvárosig. Így látszik, ha egy olcsóbb jegy a drága transzfer miatt valójában nem olcsóbb.
 * Tájékoztató értékek (2026-os átlagos jegyárak, EUR / fő / út) – a pontos árat a szolgáltató adja.
 */
object Transfers {
    class Info(val city: String, val how: String, val eur: Double, val minutes: Int)

    private val table = mapOf(
        "STN" to Info("London", "busz", 15.0, 80),
        "LTN" to Info("London", "busz vagy vonat", 16.0, 60),
        "SEN" to Info("London", "vonat", 22.0, 55),
        "LGW" to Info("London", "vonat", 15.0, 35),
        "BVA" to Info("Párizs", "reptéri busz", 17.0, 75),
        "CRL" to Info("Brüsszel", "reptéri busz", 17.0, 55),
        "BGY" to Info("Milánó", "reptéri busz", 10.0, 50),
        "MXP" to Info("Milánó", "Malpensa Express vonat", 13.0, 50),
        "TSF" to Info("Velence", "reptéri busz", 12.0, 70),
        "CIA" to Info("Róma", "reptéri busz", 6.0, 40),
        "FCO" to Info("Róma", "Leonardo Express vonat", 14.0, 32),
        "GRO" to Info("Barcelona", "reptéri busz", 16.0, 75),
        "REU" to Info("Barcelona", "reptéri busz", 16.0, 90),
        "NYO" to Info("Stockholm", "reptéri busz", 24.0, 80),
        "TRF" to Info("Oslo", "reptéri busz", 30.0, 110),
        "WMI" to Info("Varsó", "busz vagy vonat", 9.0, 50),
        "HHN" to Info("Frankfurt", "reptéri busz", 18.0, 105),
        "NRN" to Info("Düsseldorf", "reptéri busz", 18.0, 75),
        "EIN" to Info("Amszterdam", "busz és vonat", 25.0, 110),
    )

    fun infoFor(code: String?): Info? = code?.uppercase()?.let { table[it] }

    /** Hány utat kell megtenni: oda (és oda-vissza útnál vissza is) – utasonként (az ölben ülő csecsemő ingyen). */
    fun trips(w: Watch): Int = w.seatedPassengers * (if (w.isRoundTrip) 2 else 1)

    /** A teljes becsült transzferköltség euróban az egész társaságnak. */
    fun totalEur(w: Watch, info: Info): Double = info.eur * trips(w)

    /**
     * Egy sor a kártyára, pl. „🚌 London belvárosa: busz, kb. 80 perc · kb. 6 000 Ft/fő/út (oda-vissza, 2 főre kb. 24 000 Ft)”.
     * [convert]: euró → a felhasználó pénzneme (null, ha most nem érhető el – ilyenkor euróban írjuk).
     */
    fun line(w: Watch, info: Info, currency: String, convert: (Double) -> Double?): String {
        fun money(eur: Double): String = convert(eur)?.let { formatPrice(roundNice(it, currency), currency) }
            ?: formatPrice(ceil(eur).toInt(), "EUR")
        val trips = trips(w)
        val total = if (trips > 1) {
            val who = if (w.seatedPassengers > 1) "${w.seatedPassengers} főre" else "1 főre"
            val dir = if (w.isRoundTrip) "oda-vissza, " else ""
            " ($dir$who kb. ${money(totalEur(w, info))})"
        } else ""
        return "🚌 ${info.city} belvárosa: ${info.how}, kb. ${info.minutes} perc · kb. ${money(info.eur)}/fő/út$total"
    }

    private fun roundNice(v: Double, currency: String): Int =
        if (currency == "HUF") (Math.round(v / 100.0) * 100).toInt().coerceAtLeast(100) else ceil(v).toInt()
}
