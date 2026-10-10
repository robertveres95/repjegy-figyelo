package hu.repjegy.figyelo

import kotlin.math.ceil

/**
 * A városközponttól távoli (főleg fapados) repterek: a legolcsóbb szokásos tömegközlekedés a
 * belvárosig. Így látszik, ha egy olcsóbb jegy a drága transzfer miatt valójában nem olcsóbb.
 * Tájékoztató értékek (2026-os átlagos jegyárak, EUR / fő / út) – a pontos árat a szolgáltató adja.
 */
object Transfers {
    class Info(private val cityHu: String, private val cityEn: String, private val howHu: String, private val howEn: String, val eur: Double, val minutes: Int) {
        val city: String get() = tr(cityHu, cityEn)
        val how: String get() = tr(howHu, howEn)
    }

    private val table = mapOf(
        "STN" to Info("London", "London", "busz", "bus", 15.0, 80),
        "LTN" to Info("London", "London", "busz vagy vonat", "bus or train", 16.0, 60),
        "SEN" to Info("London", "London", "vonat", "train", 22.0, 55),
        "LGW" to Info("London", "London", "vonat", "train", 15.0, 35),
        "BVA" to Info("Párizs", "Paris", "reptéri busz", "airport bus", 17.0, 75),
        "CRL" to Info("Brüsszel", "Brussels", "reptéri busz", "airport bus", 17.0, 55),
        "BGY" to Info("Milánó", "Milan", "reptéri busz", "airport bus", 10.0, 50),
        "MXP" to Info("Milánó", "Milan", "Malpensa Express vonat", "Malpensa Express train", 13.0, 50),
        "TSF" to Info("Velence", "Venice", "reptéri busz", "airport bus", 12.0, 70),
        "CIA" to Info("Róma", "Rome", "reptéri busz", "airport bus", 6.0, 40),
        "FCO" to Info("Róma", "Rome", "Leonardo Express vonat", "Leonardo Express train", 14.0, 32),
        "GRO" to Info("Barcelona", "Barcelona", "reptéri busz", "airport bus", 16.0, 75),
        "REU" to Info("Barcelona", "Barcelona", "reptéri busz", "airport bus", 16.0, 90),
        "NYO" to Info("Stockholm", "Stockholm", "reptéri busz", "airport bus", 24.0, 80),
        "TRF" to Info("Oslo", "Oslo", "reptéri busz", "airport bus", 30.0, 110),
        "WMI" to Info("Varsó", "Warsaw", "busz vagy vonat", "bus or train", 9.0, 50),
        "HHN" to Info("Frankfurt", "Frankfurt", "reptéri busz", "airport bus", 18.0, 105),
        "NRN" to Info("Düsseldorf", "Düsseldorf", "reptéri busz", "airport bus", 18.0, 75),
        "EIN" to Info("Amszterdam", "Amsterdam", "busz és vonat", "bus and train", 25.0, 110),
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
            val who = if (w.seatedPassengers > 1) tr("${w.seatedPassengers} főre", "for ${w.seatedPassengers} people") else tr("1 főre", "for 1 person")
            val dir = if (w.isRoundTrip) tr("oda-vissza, ", "round trip, ") else ""
            tr(" ($dir$who kb. ${money(totalEur(w, info))})", " ($dir$who about ${money(totalEur(w, info))})")
        } else ""
        return tr(
            "🚌 ${info.city} belvárosa: ${info.how}, kb. ${info.minutes} perc · kb. ${money(info.eur)}/fő/út$total",
            "🚌 To central ${info.city}: ${info.how}, about ${info.minutes} min · about ${money(info.eur)}/person/trip$total",
        )
    }

    private fun roundNice(v: Double, currency: String): Int =
        if (currency == "HUF") (Math.round(v / 100.0) * 100).toInt().coerceAtLeast(100) else ceil(v).toInt()
}
