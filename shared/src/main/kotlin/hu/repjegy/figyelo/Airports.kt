package hu.repjegy.figyelo

import java.text.Normalizer
import java.util.Locale

/** Egy választható hely: egy repülőtér, vagy egy város összes repülőtere. */
data class Place(
    val codes: String,      // pl. "BUD" vagy "LHR,LGW,STN"
    val city: String,       // rövid név a kártyákhoz, pl. "London"
    val title: String,      // a lista első sora
    val subtitle: String,   // a lista második sora
    val fieldText: String,  // ami kiválasztás után a mezőben látszik
)

/**
 * Repülőtér-kereső a beépített listából (OurAirports, menetrend szerinti járatú repterek).
 * Nem használ internetet és nem fogyaszt SerpApi-keresést.
 */
object Airports {

    private class Entry(val place: Place, val keys: List<String>, val rank: Int)

    @Volatile
    private var entries: List<Entry>? = null

    /** Magyar városnevek, hogy pl. „Bécs” vagy „Kolozsvár” is találjon. */
    private val hungarianNames = mapOf(
        "VIE" to "Bécs", "CDG" to "Párizs", "ORY" to "Párizs", "BVA" to "Párizs",
        "FCO" to "Róma", "CIA" to "Róma", "MXP" to "Milánó", "LIN" to "Milánó", "BGY" to "Milánó",
        "VCE" to "Velence", "TSF" to "Velence", "NAP" to "Nápoly", "WAW" to "Varsó", "WMI" to "Varsó",
        "PRG" to "Prága", "BTS" to "Pozsony", "CLJ" to "Kolozsvár", "TGM" to "Marosvásárhely",
        "TSR" to "Temesvár", "OMR" to "Nagyvárad", "SBZ" to "Nagyszeben", "SUJ" to "Szatmárnémeti",
        "BRU" to "Brüsszel", "CRL" to "Brüsszel", "CPH" to "Koppenhága", "ATH" to "Athén",
        "LIS" to "Lisszabon", "SVO" to "Moszkva", "DME" to "Moszkva", "VKO" to "Moszkva",
        "IST" to "Isztambul", "SAW" to "Isztambul", "PEK" to "Peking", "PKX" to "Peking",
        "HND" to "Tokió", "NRT" to "Tokió", "ICN" to "Szöul", "OTP" to "Bukarest", "BEG" to "Belgrád",
        "ZAG" to "Zágráb", "SOF" to "Szófia", "KBP" to "Kijev", "KRK" to "Krakkó", "MUC" to "München",
        "GVA" to "Genf", "NCE" to "Nizza", "DXB" to "Dubaj", "DWC" to "Dubaj", "CAI" to "Kairó",
        "HER" to "Kréta", "CHQ" to "Kréta", "PMO" to "Szicília", "CTA" to "Szicília",
        "CAG" to "Szardínia", "OLB" to "Szardínia", "AHO" to "Szardínia", "LCA" to "Ciprus",
        "PFO" to "Ciprus", "MLA" to "Málta", "CFU" to "Korfu", "RHO" to "Rodosz", "JMK" to "Mükonosz",
        "JTR" to "Szantorini", "AMS" to "Amszterdam", "SKP" to "Szkopje", "SJJ" to "Szarajevó",
        "KIV" to "Kisinyov", "BSL" to "Bázel", "SIN" to "Szingapúr", "HKG" to "Hongkong",
        "DEL" to "Újdelhi", "MLE" to "Maldív-szigetek", "SSH" to "Sarm es-Sejk", "RMF" to "Marsza Alam",
        "TUN" to "Tunisz", "RAK" to "Marrákes", "TFS" to "Tenerife", "TFN" to "Tenerife",
        "LPA" to "Gran Canaria", "PMI" to "Mallorca", "SPU" to "Split", "ZTH" to "Zakünthosz",
        "KGS" to "Kósz", "SKG" to "Thesszaloniki", "EFL" to "Kefalónia", "MEX" to "Mexikóváros",
        "GRU" to "São Paulo", "EZE" to "Buenos Aires", "JNB" to "Johannesburg", "CPT" to "Fokváros",
        "TLV" to "Tel-Aviv", "DOH" to "Doha", "AUH" to "Abu-Dzabi", "BKK" to "Bangkok", "DMK" to "Bangkok",
    )

    /** Több repülőteres városok: egyben is választhatók. */
    private val cityGroups = listOf(
        Triple("London", "GB", "LHR,LGW,STN,LTN,LCY,SEN"),
        Triple("Párizs", "FR", "CDG,ORY,BVA"),
        Triple("Milánó", "IT", "MXP,LIN,BGY"),
        Triple("Róma", "IT", "FCO,CIA"),
        Triple("Velence", "IT", "VCE,TSF"),
        Triple("Brüsszel", "BE", "BRU,CRL"),
        Triple("Varsó", "PL", "WAW,WMI"),
        Triple("Stockholm", "SE", "ARN,BMA,NYO"),
        Triple("Moszkva", "RU", "SVO,DME,VKO"),
        Triple("Isztambul", "TR", "IST,SAW"),
        Triple("Tenerife", "ES", "TFS,TFN"),
        Triple("Kréta", "GR", "HER,CHQ"),
        Triple("Szicília", "IT", "PMO,CTA"),
        Triple("Ciprus", "CY", "LCA,PFO"),
        Triple("Dubaj", "AE", "DXB,DWC"),
        Triple("Bangkok", "TH", "BKK,DMK"),
        Triple("Tokió", "JP", "HND,NRT"),
        Triple("Peking", "CN", "PEK,PKX"),
        Triple("New York", "US", "JFK,EWR,LGA"),
        Triple("Washington", "US", "IAD,DCA,BWI"),
        Triple("Chicago", "US", "ORD,MDW"),
    )

    fun preload() {
        load()
    }

    fun search(query: String, limit: Int = 8): List<Place> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val upper = query.trim().uppercase(Locale.ROOT)
        return load()
            .mapNotNull { e ->
                val score = when {
                    e.place.codes == upper -> 0
                    e.keys.any { it.startsWith(q) } -> 10 + e.rank
                    e.keys.any { it.contains(" $q") || it.contains("-$q") } -> 20 + e.rank
                    q.length >= 3 && e.keys.any { it.contains(q) } -> 30 + e.rank
                    else -> return@mapNotNull null
                }
                score to e
            }
            .sortedWith(compareBy<Pair<Int, Entry>>({ it.first }, { it.second.place.title }))
            .take(limit)
            .map { it.second.place }
    }

    /** Mentett kód(ok) visszaalakítása választott hellyé (szerkesztéshez). */
    fun placeFor(codes: String, label: String?): Place {
        load().firstOrNull { it.place.codes == codes }?.let { return it.place }
        return Place(codes, label ?: codes, codes, codes, if (label != null) "$label ($codes)" else codes)
    }

    @Synchronized
    private fun load(): List<Entry> {
        entries?.let { return it }
        val hu = Locale.forLanguageTag("hu")
        val countryName = { cc: String -> Locale("", cc).getDisplayCountry(hu).ifBlank { cc } }

        val airports = Platform.current.openAsset("airports.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                val code = p[0]
                val name = p[1]
                val city = p[2].substringBefore(',').trim().ifBlank { name }
                val country = countryName(p[3])
                val rank = p[4].toIntOrNull() ?: 2
                val huName = hungarianNames[code]
                val shownCity = huName ?: city
                val place = Place(
                    codes = code,
                    city = shownCity,
                    title = name,
                    subtitle = "$shownCity, $country · $code",
                    fieldText = "$name ($code)",
                )
                val keys = listOfNotNull(code.lowercase(), city, name, huName).map(::normalize)
                Entry(place, keys, rank)
            }.toList()
        }

        val groups = cityGroups.map { (city, cc, codes) ->
            val place = Place(
                codes = codes,
                city = city,
                title = "$city – minden repülőtér",
                subtitle = "${countryName(cc)} · ${codes.replace(",", ", ")}",
                fieldText = "$city – minden repülőtér",
            )
            val englishCities = codes.split(',').mapNotNull { c ->
                airports.firstOrNull { it.place.codes == c }?.keys?.getOrNull(1)
            }
            Entry(place, (listOf(normalize(city)) + englishCities).distinct(), -1)
        }

        return (groups + airports).also { entries = it }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            // Nem bontható betűk (pl. Łódź, København, Düsseldorf ß-vel)
            .replace("ł", "l").replace("ø", "o").replace("ß", "ss").replace("æ", "ae")
            .replace("đ", "d").replace("ı", "i").replace("œ", "oe").replace("þ", "th")
}
