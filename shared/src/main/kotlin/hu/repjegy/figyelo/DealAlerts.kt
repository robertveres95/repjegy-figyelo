package hu.repjegy.figyelo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * „Bárhová, olcsón” riasztás: egy indulási helyről, egy időszakban bármelyik úti célra – szól, ha
 * valahová a megadott fejenkénti ár alá (vagy az eddig jelzettnél olcsóbbra) megy a jegy.
 * Ez a Felfedezés figyelős változata. A beállításai szinkronizálódnak, az állapota (jelzések, találatok) helyi.
 */
data class DealAlert(
    val id: String,
    val fromCodes: String,
    val fromLabel: String,
    /** null = mindig a következő 30 nap; különben egy naptári hónap, pl. "2026-12". */
    val month: String?,
    val tripType: Int,
    val maxPrice: Int,
    val currency: String,
    val createdAt: Long,
    val lastChecked: Long? = null,
    val lastError: String? = null,
    /** úti cél kódja → az utoljára jelzett fejenkénti ár (ugyanarról az árról nem szólunk újra) */
    val notified: Map<String, Int> = emptyMap(),
    /** a legutóbbi találatok (a legolcsóbbak), a képernyőn ezek látszanak */
    val latest: List<Discover.Result> = emptyList(),
    /** Utolsó felhasználói módosítás (szinkronizáláshoz). */
    val editedAt: Long = createdAt,
    /** A [notified] árak pénzneme (csak helyi; más pénznemben ellenőrizve a jelzések nem érvényesek). */
    val notifiedCurrency: String? = null,
    /** A hibaüzenet minden nyelven (csak helyi). */
    val lastErrorL: L10n? = null,
) {
    /** A hibaüzenet a felület nyelvén. */
    val errorText: String? get() = lastErrorL?.takeIf { it.has(lastError) }?.text ?: storedText(lastError)

    /** A szinkronizált rész: csak a beállítások (az eszközönkénti állapot – jelzett árak, találatok – nem). */
    fun toSyncJson(): JSONObject = JSONObject()
        .put("id", id).put("fromCodes", fromCodes).put("fromLabel", fromLabel).putOpt("month", month)
        .put("tripType", tripType).put("maxPrice", maxPrice).put("currency", currency)
        .put("createdAt", createdAt).put("editedAt", editedAt)

    /** Ugyanazok-e a beállítások (az eszközönkénti állapottól függetlenül). */
    fun sameSettings(o: DealAlert) = toSyncJson().toString() == o.toSyncJson().toString()

    /** Az indulási hely neve a felület nyelvén. */
    val fromName: String get() = Airports.cityName(fromCodes) ?: fromLabel

    /** A Discover.search időszak-indexe ma (0 = következő 30 nap), vagy null, ha a hónap már elmúlt. */
    fun periodIndex(today: LocalDate = LocalDate.now()): Int? {
        val m = month?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return 0
        val diff = (m.year - today.year) * 12 + (m.monthValue - today.monthValue)
        return if (diff < 0 || diff > 11) null else diff + 1
    }

    fun isExpired(today: LocalDate = LocalDate.now()): Boolean = month != null && periodIndex(today) == null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("fromCodes", fromCodes).put("fromLabel", fromLabel)
        .putOpt("month", month).put("tripType", tripType).put("maxPrice", maxPrice).put("currency", currency)
        .put("createdAt", createdAt).put("editedAt", editedAt).putOpt("lastChecked", lastChecked).putOpt("lastError", lastError)
        .putOpt("lastErrorL", lastErrorL?.takeIf { it.has(lastError) }?.toJson())
        .put("notified", JSONObject().apply { notified.forEach { (k, v) -> put(k, v) } })
        .putOpt("notifiedCurrency", notifiedCurrency)
        .put("latest", JSONArray().apply {
            latest.forEach { r ->
                put(JSONObject().put("code", r.code).put("city", r.city).put("country", r.country)
                    .put("price", r.pricePerPerson).putOpt("dep", r.departure).putOpt("ret", r.returnDeparture)
                    .put("from", r.fromCode))
            }
        })

    companion object {
        fun fromJson(o: JSONObject): DealAlert? = runCatching {
            val n = o.optJSONObject("notified")
            val l = o.optJSONArray("latest") ?: JSONArray()
            DealAlert(
                id = o.getString("id"),
                fromCodes = o.getString("fromCodes"),
                fromLabel = o.optString("fromLabel", o.getString("fromCodes")),
                month = o.optString("month", "").takeIf { it.isNotBlank() },
                tripType = o.optInt("tripType", 1).coerceIn(0, 2),
                maxPrice = o.getInt("maxPrice").also { require(it > 0) },
                currency = o.optString("currency", "HUF"),
                createdAt = o.optLong("createdAt", 0L),
                editedAt = o.optLong("editedAt", o.optLong("createdAt", 0L)),
                lastChecked = o.optLong("lastChecked", 0L).takeIf { it > 0 },
                lastError = o.optString("lastError", "").takeIf { it.isNotBlank() },
                notified = n?.keys()?.asSequence()?.associateWith { n.optInt(it) }.orEmpty(),
                notifiedCurrency = o.optString("notifiedCurrency", "").takeIf { it.isNotBlank() },
                lastErrorL = L10n.fromJson(o.optJSONObject("lastErrorL")),
                latest = (0 until l.length()).mapNotNull { i ->
                    l.optJSONObject(i)?.let { r ->
                        Discover.Result(
                            r.getString("code"), r.optString("city"), r.optString("country"), r.getInt("price"),
                            r.optString("dep", "").takeIf { it.isNotBlank() }, r.optString("ret", "").takeIf { it.isNotBlank() },
                            r.optString("from"),
                        )
                    }
                },
            )
        }.getOrNull()
    }
}

object DealAlerts {
    private const val KEY = "dealAlerts"
    const val MAX = 5
    /** Ennyi időnként nézzük meg (a háttér-ellenőrzéssel együtt fut, de nem gyakrabban). */
    private const val INTERVAL_MS = 12 * 3_600_000L

    private val _all = MutableStateFlow<List<DealAlert>>(emptyList())
    val all: StateFlow<List<DealAlert>> get() { ensureLoaded(); return _all }
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val arr = runCatching { JSONArray(Store.prefs.getString(KEY, "[]") ?: "[]") }.getOrNull() ?: JSONArray()
        _all.value = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(DealAlert::fromJson) }
    }

    @Synchronized
    private fun save(list: List<DealAlert>) {
        _all.value = list
        Store.prefs.edit { putString(KEY, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()) }
    }

    @Synchronized
    fun add(a: DealAlert): Boolean {
        ensureLoaded()
        if (_all.value.size >= MAX) return false
        save(_all.value + a.copy(editedAt = Store.stamp(a.createdAt)))
        Sync.scheduleSoon()
        return true
    }

    @Synchronized
    fun remove(id: String) {
        ensureLoaded()
        val existing = _all.value.firstOrNull { it.id == id }
        save(_all.value.filterNot { it.id == id })
        // Törlésjel: a többi eszközön is törlődjön (és onnan ne jöjjön vissza). Legalább az utolsó
        // módosítás utánra kerül, hogy egy siető órájú eszköz módosítása után is nyerjen.
        saveTombstones(tombstones() + (id to Store.stamp(existing?.editedAt ?: 0L)))
        Sync.scheduleSoon()
    }

    private const val TOMB_KEY = "dealAlertTombstones"

    @Synchronized
    fun tombstones(): Map<String, Long> {
        val o = runCatching { JSONObject(Store.prefs.getString(TOMB_KEY, "{}") ?: "{}") }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optLong(it, 0L) }
    }

    /** A 120 napnál régebbi törlésjelek elhagyása (addigra minden eszköz megkapta őket). */
    private fun pruneTombstones(t: Map<String, Long>): Map<String, Long> {
        val cutoff = System.currentTimeMillis() - 120L * 24 * 3_600_000L
        return t.filterValues { it >= cutoff }
    }

    @Synchronized
    private fun saveTombstones(t: Map<String, Long>) {
        val o = JSONObject()
        pruneTombstones(t).forEach { (k, v) -> o.put(k, v) }
        Store.prefs.edit { putString(TOMB_KEY, o.toString()) }
    }

    /**
     * Összefésülés a felhőben lévővel: azonosító szerint a később módosított beállítás nyer, a törlésjel
     * erősebb a nála régebbi módosításnál; az eszközönkénti állapot (jelzett árak, találatok) helyi marad.
     */
    @Synchronized
    fun mergeFromSync(remote: List<DealAlert>, remoteTomb: Map<String, Long>): Pair<List<DealAlert>, Map<String, Long>> {
        ensureLoaded()
        val localTomb = tombstones()
        val allTomb = (localTomb.keys + remoteTomb.keys).associateWith { maxOf(localTomb[it] ?: 0L, remoteTomb[it] ?: 0L) }
        val local = _all.value.associateBy { it.id }
        val rem = remote.associateBy { it.id }
        val merged = (local.keys + rem.keys).mapNotNull { id ->
            val l = local[id]
            val r = rem[id]
            val newer = when {
                l == null -> r!!
                r == null -> l
                r.editedAt > l.editedAt -> l.copy(
                    fromCodes = r.fromCodes, fromLabel = r.fromLabel, month = r.month, tripType = r.tripType,
                    maxPrice = r.maxPrice, currency = r.currency, editedAt = r.editedAt,
                    // Más feltételek: a korábbi jelzések már nem érvényesek
                    notified = if (r.maxPrice == l.maxPrice && r.currency == l.currency) l.notified else emptyMap(),
                    notifiedCurrency = if (r.maxPrice == l.maxPrice && r.currency == l.currency) l.notifiedCurrency else null,
                )
                else -> l
            }
            newer.takeUnless { (allTomb[id] ?: 0L) >= newer.editedAt }
        }.sortedBy { it.createdAt }
        // A lejárt törlésjelek a felhőből is tűnjenek el (a fenti szűréshez még mind számított)
        val tomb = pruneTombstones(allTomb)
        save(merged)
        saveTombstones(tomb)
        return merged to tomb
    }

    @Synchronized
    private fun update(id: String, f: (DealAlert) -> DealAlert) {
        ensureLoaded()
        save(_all.value.map { if (it.id == id) f(it) else it })
    }

    fun create(from: Place, month: String?, tripType: Int, maxPrice: Int, currency: String) = DealAlert(
        id = UUID.randomUUID().toString(), fromCodes = from.codes, fromLabel = from.city, month = month,
        tripType = tripType, maxPrice = maxPrice, currency = currency, createdAt = System.currentTimeMillis(),
    )

    /**
     * Az újonnan jelzendő találatok: a határ alattiak közül azok, amelyekről még nem szóltunk, vagy
     * azóta legalább 5%-kal olcsóbbak lettek.
     */
    internal fun fresh(
        a: DealAlert,
        results: List<Discover.Result>,
        limit: Int = a.maxPrice,
        notified: Map<String, Int> = a.notified,
    ): List<Discover.Result> =
        results.filter { r ->
            r.pricePerPerson <= limit &&
                (notified[r.code]?.let { prev -> r.pricePerPerson * 100 <= prev * 95 } ?: true)
        }

    /** A jelzett árak pénzneme (a régebbi, ezt még nem tároló változatnál a riasztás pénzneme). */
    private fun notifiedCur(a: DealAlert): String = a.notifiedCurrency ?: a.currency

    /** A háttér-ellenőrzésből: a régóta nem nézett riasztások lefuttatása. [force]: most mindegyik. */
    suspend fun checkDue(force: Boolean = false) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        // Lejárt hónapú riasztások törlése
        _all.value.filter { it.isExpired() }.forEach { remove(it.id) }
        // Csendes órákban nem nézzük (különben a találatot „jelzettnek” vennénk, de nem szólnánk) –
        // a csend vége utáni első ellenőrzés pótolja
        if (!force && runCatching { Store.settings.value.isQuiet() }.getOrDefault(false)) return
        for (a in _all.value) {
            if (!force && a.lastChecked != null && now - a.lastChecked < INTERVAL_MS) continue
            checkOne(a)
        }
    }

    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Egy riasztás ellenőrzése; ugyanaz egyszerre csak egyszer fut (gomb + háttér). */
    suspend fun checkOne(a0: DealAlert) {
        if (!inFlight.add(a0.id)) return
        try {
            // A legfrissebb tárolt állapotból (egy korábbi futás azóta jelezhetett)
            val a = _all.value.firstOrNull { it.id == a0.id } ?: return
            checkLocked(a)
        } finally {
            inFlight.remove(a0.id)
        }
    }

    private suspend fun checkLocked(a: DealAlert) {
        val currency = Store.settings.value.currency
        val period = a.periodIndex() ?: return
        // Más pénznemben megadott határ: átváltjuk (ha nem megy, most kihagyjuk)
        val limit = if (a.currency == currency) a.maxPrice else runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Math.round(Rates.convert(a.maxPrice.toDouble(), a.currency, currency)).toInt()
            }
        }.getOrNull() ?: return
        val r = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Discover.search(a.fromCodes, period, a.tripType, limit, currency)
            }
        }
        val now = System.currentTimeMillis()
        r.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Vágás úgy, hogy a fordítás megmaradjon (a Texts.find a teljes szöveget ismeri)
            val msg = e.message?.let { shortText(it, 160) } ?: trs("hiba", "error", "Fehler")
            update(a.id) { it.copy(lastChecked = now, lastError = msg, lastErrorL = Texts.find(msg)) }
        }
        r.onSuccess { results ->
            // A korábbi jelzések csak ugyanabban a pénznemben összevethetők
            val validNotified = if (notifiedCur(a) == currency) a.notified else emptyMap()
            val news = fresh(a, results, limit, validNotified)
            // Letiltott értesítésnél nem vesszük „jelzettnek” (bekapcsolás után szólunk róla)
            val blocked = runCatching { Platform.current.notificationsBlocked() }.getOrDefault(false)
            if (news.isNotEmpty() && !blocked) {
                val top = news.sortedBy { it.pricePerPerson }.take(3)
                val title = trs(
                    "Olcsó út innen: ${a.fromName} – ${formatPrice(top.first().pricePerPerson, currency)}/fő",
                    "Cheap trip from ${a.fromName} – ${formatPrice(top.first().pricePerPerson, currency)}/person",
                    "Günstige Reise ab ${a.fromName} – ${formatPrice(top.first().pricePerPerson, currency)}/Person",
                )
                val text = top.joinToString("\n") {
                    "${it.shownCity}: ${formatPrice(it.pricePerPerson, currency)}${trs("/fő", "/person", "/Person")} · ${Discover.describeDates(it)}"
                } + if (news.size > 3) trs("\n…és még ${news.size - 3} úti cél", "\n…and ${news.size - 3} more destinations", "\n…und ${news.size - 3} weitere Reiseziele") else ""
                Platform.current.notifyMessage("deal-${a.id}", title, text, null)
            }
            update(a.id) {
                // A beállítások (szinkronizált rész) érintetlenek maradnak: az átváltott határt nem mentjük
                if (it.editedAt != a.editedAt) {
                    // Közben módosultak a feltételek: ez az eredmény már nem rájuk vonatkozik
                    it.copy(lastChecked = now, lastError = null, lastErrorL = null)
                } else {
                    it.copy(
                        lastChecked = now, lastError = null, lastErrorL = null, notifiedCurrency = currency,
                        notified = (if (notifiedCur(it) == currency) it.notified else emptyMap()) +
                            (if (blocked) emptyMap() else news.associate { n -> n.code to n.pricePerPerson }),
                        latest = results.sortedBy { x -> x.pricePerPerson }.take(5),
                    )
                }
            }
        }
    }

    /** Az időszak felirata a riasztáshoz. */
    fun periodLabel(a: DealAlert): String {
        val m = a.month?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return trs("a következő 30 napban", "in the next 30 days", "in den nächsten 30 Tagen")
        if (Lang.en) return "in " + m.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Lang.locale))
        if (Lang.de) return "im " + m.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", Lang.locale))
        // „szeptemberben”, „októberben”, „novemberben”, „decemberben” – a többi hónap „-ban”
        return m.format(java.time.format.DateTimeFormatter.ofPattern("yyyy. LLLL", HU)) + if (m.monthValue >= 9) "ben" else "ban"
    }
}
