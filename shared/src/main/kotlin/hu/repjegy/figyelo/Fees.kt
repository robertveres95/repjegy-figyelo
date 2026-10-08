package hu.repjegy.figyelo

import kotlin.math.ceil

/**
 * Becsült poggyász- és csecsemődíjak a fapados légitársaságoknál.
 *
 * Az élő próbák szerint a Google a poggyászdíjat csak részben számolja bele (pl. a Ryanair
 * kézipoggyászát igen, a feladott poggyászt és a Wizz Air díjait nem), a Ryanair/Wizz saját
 * felülete pedig csak az alapárat adja. Ezért minden forrásnál ugyanígy járunk el: az
 * alapárhoz a fapadosoknál becsült díjat adunk, és ezt a megjegyzésben jelezzük. Így a
 * célárral összevethető (és a források egymással is), a riasztás pedig nem szól tévesen
 * egy poggyász nélküli alapárra.
 *
 * A hagyományos légitársaságok turistajegyében a kézipoggyász benne van; ott nem adunk hozzá.
 */
object Fees {
    private class Lcc(val name: String, val cabinEur: Double, val checkedEur: Double, val infantEur: Double)

    // Tájékoztató értékek (EUR / darab / út), 2026-os átlagos online árak alapján
    private val carriers = listOf(
        Lcc("ryanair", 25.0, 35.0, 30.0),
        Lcc("malta air", 25.0, 35.0, 30.0),
        Lcc("buzz", 25.0, 35.0, 30.0),
        Lcc("lauda", 25.0, 35.0, 30.0),
        Lcc("wizz", 30.0, 38.0, 35.0),
        Lcc("easyjet", 20.0, 32.0, 30.0),
        Lcc("vueling", 20.0, 30.0, 30.0),
        Lcc("volotea", 20.0, 30.0, 30.0),
        Lcc("transavia", 20.0, 30.0, 30.0),
        Lcc("eurowings", 20.0, 30.0, 30.0),
        Lcc("jet2", 20.0, 30.0, 30.0),
        Lcc("pegasus", 20.0, 30.0, 25.0),
        Lcc("sunexpress", 20.0, 30.0, 25.0),
        Lcc("norwegian", 20.0, 30.0, 30.0),
        Lcc("play", 25.0, 35.0, 30.0),
        Lcc("spirit", 45.0, 45.0, 0.0),
        Lcc("frontier", 45.0, 45.0, 0.0),
    )

    private fun lccOf(airline: String?): Lcc? {
        val a = airline?.lowercase() ?: return null
        return carriers.firstOrNull { a.contains(it.name) }
    }

    fun isLowCost(airline: String?): Boolean = lccOf(airline) != null

    /**
     * A becsült többletdíj euróban (0, ha nincs mit hozzáadni).
     * [includeInfants]: a fapadosok saját felületénél az ölben utazó csecsemő díja is hiányzik
     * (a Google-ár viszont tartalmazza).
     */
    fun extraEur(w: Watch, airline: String?, includeInfants: Boolean): Double {
        val lcc = lccOf(airline) ?: return 0.0
        val legs = if (w.isRoundTrip) 2 else 1
        var perLeg = w.bags * lcc.cabinEur + (if (w.checkedBag) lcc.checkedEur else 0.0)
        if (includeInfants) perLeg += w.infantsOnLap * lcc.infantEur
        return perLeg * legs
    }

    /** Az ajánlat kiegészítése a becsült díjjal (ár + megjegyzés). */
    fun apply(o: Offer, w: Watch, currency: String, includeInfants: Boolean): Offer {
        val eur = extraEur(w, o.airline, includeInfants)
        if (eur <= 0.0) return o.copy(bagsIncluded = true)
        val extra = ceil(Rates.convert(eur, "EUR", currency)).toInt()
        val what = buildList {
            if (w.wantsBags) add("poggyász")
            if (includeInfants && w.infantsOnLap > 0) add("csecsemő")
        }.joinToString(" és ")
        val tag = "becsült $what-díjjal (+${formatPrice(extra, currency)})"
        return o.copy(
            price = o.price + extra,
            bagsIncluded = true,
            note = listOfNotNull(o.note, tag).joinToString(", "),
        )
    }
}
