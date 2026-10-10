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
 * Ez a Felfedezés figyelős változata. Csak ezen az eszközön tárolódik (nem szinkronizálódik).
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
) {
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
        .put("notified", JSONObject().apply { notified.forEach { (k, v) -> put(k, v) } })
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
        save(_all.value.filterNot { it.id == id })
        // Törlésjel: a többi eszközön is törlődjön (és onnan ne jöjjön vissza)
        saveTombstones(tombstones() + (id to Store.stamp(0L)))
        Sync.scheduleSoon()
    }

    private const val TOMB_KEY = "dealAlertTombstones"

    @Synchronized
    fun tombstones(): Map<String, Long> {
        val o = runCatching { JSONObject(Store.prefs.getString(TOMB_KEY, "{}") ?: "{}") }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optLong(it, 0L) }
    }

    @Synchronized
    private fun saveTombstones(t: Map<String, Long>) {
        val cutoff = System.currentTimeMillis() - 120L * 24 * 3_600_000L
        val o = JSONObject()
        t.filterValues { it >= cutoff }.forEach { (k, v) -> o.put(k, v) }
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
        val tomb = (localTomb.keys + remoteTomb.keys).associateWith { maxOf(localTomb[it] ?: 0L, remoteTomb[it] ?: 0L) }
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
                )
                else -> l
            }
            newer.takeUnless { (tomb[id] ?: 0L) >= newer.editedAt }
        }.sortedBy { it.createdAt }.take(MAX)
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
    internal fun fresh(a: DealAlert, results: List<Discover.Result>): List<Discover.Result> =
        results.filter { r ->
            r.pricePerPerson <= a.maxPrice &&
                (a.notified[r.code]?.let { prev -> r.pricePerPerson * 100 <= prev * 95 } ?: true)
        }

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
            update(a.id) { it.copy(lastChecked = now, lastError = e.message?.take(160) ?: trs("hiba", "error")) }
        }
        r.onSuccess { results ->
            val sameCur = a.currency == currency
            val cur = a.copy(maxPrice = limit, currency = currency, notified = if (sameCur) a.notified else emptyMap())
            val news = fresh(cur, results)
            // Letiltott értesítésnél nem vesszük „jelzettnek” (bekapcsolás után szólunk róla)
            val blocked = runCatching { Platform.current.notificationsBlocked() }.getOrDefault(false)
            if (news.isNotEmpty() && !blocked) {
                val top = news.sortedBy { it.pricePerPerson }.take(3)
                val title = trs(
                    "Olcsó út innen: ${a.fromName} – ${formatPrice(top.first().pricePerPerson, currency)}/fő",
                    "Cheap trip from ${a.fromName} – ${formatPrice(top.first().pricePerPerson, currency)}/person",
                )
                val text = top.joinToString("\n") {
                    "${it.shownCity}: ${formatPrice(it.pricePerPerson, currency)}${trs("/fő", "/person")} · ${Discover.describeDates(it)}"
                } + if (news.size > 3) trs("\n…és még ${news.size - 3} úti cél", "\n…and ${news.size - 3} more destinations") else ""
                Platform.current.notifyMessage("deal-${a.id}", title, text, null)
            }
            update(a.id) {
                it.copy(
                    maxPrice = limit, currency = currency, lastChecked = now, lastError = null,
                    notified = (if (it.currency == currency) it.notified else emptyMap()) +
                        (if (blocked) emptyMap() else news.associate { n -> n.code to n.pricePerPerson }),
                    latest = results.sortedBy { x -> x.pricePerPerson }.take(5),
                )
            }
        }
    }

    /** Az időszak felirata a riasztáshoz. */
    fun periodLabel(a: DealAlert): String {
        val m = a.month?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return trs("a következő 30 napban", "in the next 30 days")
        if (Lang.en) return "in " + m.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Lang.locale))
        // „szeptemberben”, „októberben”, „novemberben”, „decemberben” – a többi hónap „-ban”
        return m.format(java.time.format.DateTimeFormatter.ofPattern("yyyy. LLLL", HU)) + if (m.monthValue >= 9) "ben" else "ban"
    }
}
