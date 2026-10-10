package hu.repjegy.figyelo

import java.text.Normalizer
import java.util.Locale

/** Egy választható hely: egy repülőtér, vagy egy város összes repülőtere. */
data class Place(
    val codes: String,      // pl. "BUD" vagy "LHR,LGW,STN"
    val city: String,       // rövid név a kártyákhoz, pl. "London"
    val title: String,      // a lista első sora
    val subtitle: String,   // a lista második sora
    val fieldText: String,  // ami kiválasztás után a mezőben látszik (rövid: „Budapest (BUD)”)
    /** A kiválasztott hely részletei a mező alatt (pl. „Budapest Liszt Ferenc International Airport · Magyarország”). */
    val detail: String = subtitle,
)

/**
 * Repülőtér-kereső a beépített listából (OurAirports, menetrend szerinti járatú repterek).
 * Nem használ internetet és nem fogyaszt SerpApi-keresést.
 */
object Airports {

    private class Entry(val place: Place, val keys: List<String>, val rank: Int)

    /** A betöltött lista nyelvenként ("hu", "en", "de"): a városnevek és országnevek a felület nyelvén. */
    private val entriesByLang = java.util.concurrent.ConcurrentHashMap<String, List<Entry>>()
    private val byCodesByLang = java.util.concurrent.ConcurrentHashMap<String, Map<String, Place>>()

    /** Német városnevek (ahol eltér az angoltól), hogy pl. „Mailand” vagy „Wien” is találjon. */
    private val germanNames = mapOf(
        "VIE" to "Wien", "FCO" to "Rom", "CIA" to "Rom", "MXP" to "Mailand", "LIN" to "Mailand", "BGY" to "Mailand",
        "VCE" to "Venedig", "TSF" to "Venedig", "NAP" to "Neapel", "FLR" to "Florenz", "WAW" to "Warschau",
        "WMI" to "Warschau", "PRG" to "Prag", "BRU" to "Brüssel", "CRL" to "Brüssel", "CPH" to "Kopenhagen",
        "ATH" to "Athen", "LIS" to "Lissabon", "SVO" to "Moskau", "DME" to "Moskau", "VKO" to "Moskau",
        "PEK" to "Peking", "PKX" to "Peking", "HND" to "Tokio", "NRT" to "Tokio", "OTP" to "Bukarest",
        "BEG" to "Belgrad", "KBP" to "Kiew", "KRK" to "Krakau", "MUC" to "München", "NUE" to "Nürnberg",
        "CGN" to "Köln", "GVA" to "Genf", "NCE" to "Nizza", "CAI" to "Kairo", "HER" to "Kreta", "CHQ" to "Kreta",
        "PMO" to "Sizilien", "CTA" to "Sizilien", "CAG" to "Sardinien", "OLB" to "Sardinien", "AHO" to "Sardinien",
        "LCA" to "Zypern", "PFO" to "Zypern", "CFU" to "Korfu", "RHO" to "Rhodos", "JMK" to "Mykonos",
        "JTR" to "Santorini", "KIV" to "Chișinău", "SIN" to "Singapur", "DEL" to "Neu-Delhi", "MLE" to "Malediven",
        "SSH" to "Scharm El-Scheich", "RAK" to "Marrakesch", "TFS" to "Teneriffa", "TFN" to "Teneriffa",
        "ZTH" to "Zakynthos", "EFL" to "Kefalonia", "MEX" to "Mexiko-Stadt", "CPT" to "Kapstadt",
        "LPA" to "Gran Canaria", "PMI" to "Mallorca", "DXB" to "Dubai", "DWC" to "Dubai", "BSL" to "Basel",
        "TLV" to "Tel Aviv", "AUH" to "Abu Dhabi", "BTS" to "Bratislava", "TGM" to "Târgu Mureș",
        "GOA" to "Genua", "TRN" to "Turin", "SVQ" to "Sevilla", "ZIA" to "Moskau",
        "ZRH" to "Zürich", "LUX" to "Luxemburg", "ANR" to "Antwerpen", "IKA" to "Teheran", "THR" to "Teheran",
        "DAM" to "Damaskus", "TSR" to "Timișoara", "HKG" to "Hongkong", "GOT" to "Göteborg",
    )

    /** A felület nyelvén a város neve a kód(ok) alapján (pl. "MXP" → Milánó / Milan / Mailand), ha ismert. */
    fun cityName(codes: String?): String? {
        if (codes.isNullOrBlank()) return null
        return runCatching {
            load()
            byCodesByLang[Lang.code]?.get(codes.uppercase())?.city?.takeIf { it.isNotBlank() && it != codes }
        }.getOrNull()
    }

    /** Ország neve a felület nyelvén a reptér kódjából. */
    fun countryName(code: String?): String? =
        countryOf(code)?.let { cc -> Locale("", cc).getDisplayCountry(Lang.locale).takeIf { it.isNotBlank() && it != cc } }

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
        "FLR" to "Firenze", "CGN" to "Köln", "NUE" to "Nürnberg", "TRN" to "Torino", "GOA" to "Genova",
        "ZIA" to "Moszkva", "PSA" to "Pisa", "SVQ" to "Sevilla", "OPO" to "Porto",
        "ZRH" to "Zürich", "ANR" to "Antwerpen", "IKA" to "Teherán", "THR" to "Teherán", "DAM" to "Damaszkusz",
        "GOT" to "Göteborg",
    )

    /**
     * Angol városnév-javítások: az OurAirports „municipality” mezője sokszor a reptér települése
     * (pl. Spata-Artemida, Zaventem, Orio al Serio), nem a város, amit kiszolgál (Athén, Brüsszel, Bergamo).
     */
    private val englishCityFixes = mapOf(
        "BVA" to "Paris", "CRL" to "Brussels", "TSF" to "Venice", "KBP" to "Kyiv", "KIV" to "Chișinău",
        "ACE" to "Lanzarote", "ADB" to "Izmir", "AGA" to "Agadir", "AMM" to "Amman", "AOI" to "Ancona", "AOK" to "Karpathos",
        "ATH" to "Athens", "AUH" to "Abu Dhabi", "BAY" to "Baia Mare", "BEM" to "Beni Mellal", "BEN" to "Benghazi",
        "BGY" to "Milan", "BHX" to "Birmingham", "BNX" to "Banja Luka", "BRU" to "Brussels", "BSL" to "Basel",
        "BVC" to "Boa Vista", "BWK" to "Brač", "BZO" to "Bolzano", "CAN" to "Guangzhou", "CCE" to "Cairo", "CDG" to "Paris",
        "CFU" to "Corfu", "CGN" to "Cologne", "CHQ" to "Chania", "CIA" to "Rome", "CRK" to "Clark", "CRV" to "Crotone",
        "CTU" to "Chengdu", "CUF" to "Cuneo", "CVG" to "Cincinnati", "CXR" to "Nha Trang", "DJE" to "Djerba", "DPS" to "Bali",
        "DWC" to "Dubai", "DXB" to "Dubai", "EAS" to "San Sebastián", "EBA" to "Elba", "ECN" to "Nicosia", "EDI" to "Edinburgh",
        "EDO" to "Edremit", "EFL" to "Kefalonia", "EMA" to "East Midlands", "ESB" to "Ankara", "ETZ" to "Metz/Nancy",
        "EVE" to "Harstad/Narvik", "EXT" to "Exeter", "EZE" to "Buenos Aires", "FEZ" to "Fez", "FKB" to "Karlsruhe/Baden-Baden",
        "FLR" to "Florence", "FMO" to "Münster/Osnabrück", "FNI" to "Nîmes", "FOG" to "Foggia", "FRA" to "Frankfurt",
        "FRL" to "Forlì", "FUE" to "Fuerteventura", "GHV" to "Brașov", "GMP" to "Seoul", "GOA" to "Genoa", "GRZ" to "Graz",
        "HAN" to "Hanoi", "HBE" to "Alexandria", "HEL" to "Helsinki", "HER" to "Heraklion", "HHN" to "Frankfurt-Hahn",
        "HKT" to "Phuket", "HUY" to "Humberside", "IAD" to "Washington", "IAR" to "Yaroslavl", "IBZ" to "Ibiza",
        "ICN" to "Seoul", "IEG" to "Zielona Góra", "ILY" to "Islay", "INV" to "Inverness", "ISB" to "Islamabad",
        "JSH" to "Sitia", "JTR" to "Santorini", "JYV" to "Jyväskylä", "KEM" to "Kemi/Tornio", "KGS" to "Kos", "KIR" to "Kerry",
        "KIX" to "Osaka", "KLU" to "Klagenfurt", "KNO" to "Medan", "KOI" to "Kirkwall", "KOK" to "Kokkola", "KRK" to "Kraków",
        "KRS" to "Kristiansand", "KSF" to "Kassel", "KUL" to "Kuala Lumpur", "KUO" to "Kuopio", "KUT" to "Kutaisi",
        "LBA" to "Leeds/Bradford", "LCG" to "A Coruña", "LDE" to "Lourdes", "LDY" to "Derry", "LEJ" to "Leipzig/Halle",
        "LEN" to "León", "LGG" to "Liège", "LIG" to "Limoges", "LIL" to "Lille", "LIN" to "Milan", "LJU" to "Ljubljana",
        "LPA" to "Gran Canaria", "LSI" to "Shetland", "LTN" to "London", "LUG" to "Lugano", "LYS" to "Lyon", "MAH" to "Menorca",
        "MAN" to "Manchester", "MFM" to "Macau", "MLA" to "Malta", "MME" to "Teesside", "MNL" to "Manila", "MPL" to "Montpellier",
        "MRS" to "Marseille", "MRU" to "Mauritius", "MVD" to "Montevideo", "MXP" to "Milan", "NAP" to "Naples",
        "NCE" to "Nice", "NCL" to "Newcastle", "NGO" to "Nagoya", "NOC" to "Knock", "NRT" to "Tokyo", "NWI" to "Norwich",
        "NYO" to "Stockholm", "OLB" to "Olbia", "ORN" to "Oran", "ORY" to "Paris", "OSI" to "Osijek", "OSL" to "Oslo",
        "OSR" to "Ostrava", "OST" to "Ostend", "OTP" to "Bucharest", "OUD" to "Oujda", "OUL" to "Oulu", "OVD" to "Asturias",
        "PAD" to "Paderborn", "PEG" to "Perugia", "PGF" to "Perpignan", "PIK" to "Glasgow Prestwick", "PIS" to "Poitiers",
        "PIX" to "Pico", "PMF" to "Parma", "PNL" to "Pantelleria", "PSA" to "Pisa", "PTY" to "Panama City", "PUF" to "Pau",
        "PVG" to "Shanghai", "PVK" to "Preveza", "PXO" to "Porto Santo", "RAI" to "Praia", "RDZ" to "Rodez", "RJK" to "Rijeka",
        "RMI" to "Rimini", "RMU" to "Murcia", "RNS" to "Rennes", "SAW" to "Istanbul", "SDL" to "Sundsvall", "SEN" to "London",
        "SID" to "Sal", "SJZ" to "São Jorge", "SKP" to "Skopje", "SMA" to "Santa Maria", "SMI" to "Samos", "SOB" to "Hévíz-Balaton",
        "SPC" to "La Palma", "SPX" to "Giza", "STN" to "London", "SUF" to "Lamezia Terme", "SYY" to "Stornoway",
        "SZY" to "Olsztyn", "SZZ" to "Szczecin", "TER" to "Terceira", "TGM" to "Târgu Mureș", "TIA" to "Tirana",
        "TLN" to "Toulon", "TLS" to "Toulouse", "TMP" to "Tampere", "TPS" to "Trapani", "TRF" to "Oslo Torp", "TRN" to "Turin",
        "TRS" to "Trieste", "TUF" to "Tours", "TZL" to "Tuzla", "VBS" to "Brescia", "VCE" to "Venice",
        "VDE" to "El Hierro", "VIT" to "Vitoria", "VRN" to "Verona", "VST" to "Västerås", "VVO" to "Vladivostok",
        "VXE" to "São Vicente", "WMI" to "Warsaw", "XRY" to "Jerez", "ZAG" to "Zagreb", "ZIA" to "Moscow",
        "GOT" to "Gothenburg", "TSR" to "Timișoara",
    )

    /** A nyers településnév tisztítása: zárójeles és vesszős kiegészítések nélkül (pl. „Firenze (FI)” → „Firenze”). */
    private fun cleanCity(raw: String): String =
        raw.substringBefore(',').substringBefore(" (").substringBefore("(").trim()

    /** A több repülőteres városok angol és német neve (a keresés mindegyiket ismeri). */
    private val groupEnglishNames = mapOf(
        "Párizs" to "Paris", "Milánó" to "Milan", "Róma" to "Rome", "Velence" to "Venice",
        "Brüsszel" to "Brussels", "Varsó" to "Warsaw", "Moszkva" to "Moscow", "Isztambul" to "Istanbul",
        "Kréta" to "Crete", "Szicília" to "Sicily", "Ciprus" to "Cyprus", "Dubaj" to "Dubai",
        "Tokió" to "Tokyo", "Peking" to "Beijing",
    )
    private val groupGermanNames = mapOf(
        "Párizs" to "Paris", "Milánó" to "Mailand", "Róma" to "Rom", "Velence" to "Venedig",
        "Brüsszel" to "Brüssel", "Varsó" to "Warschau", "Moszkva" to "Moskau", "Isztambul" to "Istanbul",
        "Kréta" to "Kreta", "Szicília" to "Sizilien", "Ciprus" to "Zypern", "Dubaj" to "Dubai",
        "Tokió" to "Tokio", "Peking" to "Peking", "Tenerife" to "Teneriffa",
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

    /** Reptérkód → országkód. Egyszerre töltődik fel (a betöltés végén), így sosem félkész. */
    private val countryCodes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** A reptér országkódja (pl. "HU"), ha ismert. */
    fun countryOf(code: String?): String? {
        if (code == null) return null
        // Az országkód nyelvfüggetlen: elég egyszer betölteni (nyelvváltás után nem olvassuk újra a listát).
        // A load() zárolt: ha épp egy másik szál tölt, megvárjuk.
        if (countryCodes.isEmpty()) runCatching { load() }
        return countryCodes[code.uppercase()]
    }

    /** Mentett kód(ok) visszaalakítása választott hellyé (szerkesztéshez). */
    fun placeFor(codes: String, label: String?): Place {
        load()
        byCodesByLang[Lang.code]?.get(codes)?.let { return it }
        return Place(codes, label ?: codes, codes, codes, if (label != null) "$label ($codes)" else codes)
    }

    @Synchronized
    private fun load(): List<Entry> {
        val lang = Lang.code
        entriesByLang[lang]?.let { return it }
        val locale = Lang.locale
        val countryName = { cc: String -> Locale("", cc).getDisplayCountry(locale).ifBlank { cc } }
        val countries = HashMap<String, String>()

        val airports = Platform.current.openAsset("airports.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                val code = p[0]
                val name = p[1]
                val city = englishCityFixes[code] ?: cleanCity(p[2]).ifBlank { name }
                val country = countryName(p[3])
                countries[code] = p[3]
                val rank = p[4].toIntOrNull() ?: 2
                val huName = hungarianNames[code]
                val deName = germanNames[code]
                val shownCity = when (lang) {
                    Lang.HU_CODE -> huName ?: city
                    Lang.DE_CODE -> deName ?: city
                    else -> city
                }
                val place = Place(
                    codes = code,
                    city = shownCity,
                    title = name,
                    subtitle = "$shownCity, $country · $code",
                    // A mezőben rövid, jól olvasható forma; a teljes név a mező alatti sorba kerül
                    fieldText = "$shownCity ($code)",
                    detail = "$name · $country",
                )
                val keys = listOfNotNull(code.lowercase(), city, name, huName, deName).map(::normalize)
                Entry(place, keys, rank)
            }.toList()
        }

        val groups = cityGroups.map { (huCity, cc, codes) ->
            val enCity = groupEnglishNames[huCity] ?: huCity
            val deCity = groupGermanNames[huCity] ?: enCity
            val city = when (lang) {
                Lang.HU_CODE -> huCity
                Lang.DE_CODE -> deCity
                else -> enCity
            }
            val title = when (lang) {
                Lang.HU_CODE -> "$city – minden repülőtér"
                Lang.DE_CODE -> "$city – alle Flughäfen"
                else -> "$city – all airports"
            }
            val place = Place(
                codes = codes,
                city = city,
                title = title,
                subtitle = "${countryName(cc)} · ${codes.replace(",", ", ")}",
                fieldText = title,
            )
            val englishCities = codes.split(',').mapNotNull { c ->
                airports.firstOrNull { it.place.codes == c }?.keys?.getOrNull(1)
            }
            Entry(place, (listOf(normalize(huCity), normalize(enCity), normalize(deCity)) + englishCities).distinct(), -1)
        }

        val all = groups + airports
        countryCodes.putAll(countries)
        byCodesByLang[lang] = all.associate { it.place.codes to it.place }
        return all.also { entriesByLang[lang] = it }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            // Nem bontható betűk (pl. Łódź, København, Düsseldorf ß-vel)
            .replace("ł", "l").replace("ø", "o").replace("ß", "ss").replace("æ", "ae")
            .replace("đ", "d").replace("ı", "i").replace("œ", "oe").replace("þ", "th")
}
