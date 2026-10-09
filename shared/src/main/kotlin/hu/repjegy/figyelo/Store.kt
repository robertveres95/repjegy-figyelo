package hu.repjegy.figyelo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** A figyelések és beállítások tárolása (Androidon SharedPreferences, Windowson fájl; JSON). */
object Store {
    lateinit var prefs: Prefs
        private set
    private var initialized = false

    private val _watches = MutableStateFlow<List<Watch>>(emptyList())
    val watches: StateFlow<List<Watch>> = _watches

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    /** Éppen ellenőrzés alatt álló figyelések azonosítói. */
    val checking = MutableStateFlow<Set<String>>(emptySet())

    @Synchronized
    fun init(storage: Prefs) {
        if (initialized) return
        prefs = storage
        val oldSource = prefs.getString("source", null)
        val serpKey = prefs.getString("apiKey", "") ?: ""
        val ignavKey = prefs.getString("ignavKey", "") ?: ""
        _settings.value = Settings(
            googleOn = prefs.getBoolean("googleOn", true),
            ryanairOn = prefs.getBoolean("ryanairOn", true),
            wizzOn = prefs.getBoolean("wizzOn", true),
            // Korábbi verzióból: ha volt kulcs és használta, maradjon bekapcsolva
            serpOn = prefs.getBoolean("serpOn", serpKey.isNotBlank() && oldSource != "ignav"),
            ignavOn = prefs.getBoolean("ignavOn", ignavKey.isNotBlank() && oldSource != "serpapi"),
            apiKey = serpKey,
            ignavKey = ignavKey,
            currency = prefs.getString("currency", "HUF") ?: "HUF",
            intervalHours = prefs.getInt("intervalHours", 6),
            themeMode = prefs.getString("themeMode", THEME_AUTO) ?: THEME_AUTO,
            textScale = prefs.getInt("textScale", 100),
            quietOn = prefs.getBoolean("quietOn", false),
            quietFrom = prefs.getInt("quietFrom", 22).coerceIn(0, 23),
            quietTo = prefs.getInt("quietTo", 7).coerceIn(0, 23),
        )
        val raw = prefs.getString("watches", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull()
        val parsed = arr?.let { a ->
            (0 until a.length()).mapNotNull { i -> runCatching { Watch.fromJson(a.getJSONObject(i)) }.getOrNull() }
        }.orEmpty()
        // Ha valami nem olvasható be, a nyers adatot félretesszük, mielőtt a következő
        // mentés felülírná – így nem vész el végleg egy figyelés sem.
        if (arr == null || parsed.size < arr.length()) {
            prefs.edit { putString("watches_backup", raw) }
        }
        _watches.value = parsed
        initialized = true
        Sync.load()
    }

    @Synchronized
    fun upsert(watch: Watch) {
        val list = _watches.value.toMutableList()
        val index = list.indexOfFirst { it.id == watch.id }
        if (index >= 0) list[index] = watch else list.add(watch)
        persist(list)
    }

    /** A legfrissebb állapotból számol, így nem írja felül a közben történt szerkesztést. */
    @Synchronized
    fun update(id: String, transform: (Watch) -> Watch) {
        val current = _watches.value.find { it.id == id } ?: return
        upsert(transform(current))
    }

    @Synchronized
    fun delete(id: String) {
        val gone = _watches.value.find { it.id == id }
        val list = _watches.value.filterNot { it.id == id }
        // Törlésjel: a szinkronizálás így a másik eszközön is törli. Legalább az utolsó módosítás
        // utánra kerül (ha egy másik eszköz órája siet, a törlés akkor is nyerjen).
        val t = tombstones().toMutableMap()
        t[id] = stamp(gone?.editedAt ?: 0L)
        // Egy írásban: ha közben leállna az app, ne maradjon törlésjel nélküli törlés
        val cutoff = System.currentTimeMillis() - 120L * 24 * 3_600_000L
        val obj = org.json.JSONObject()
        t.filterValues { it > cutoff }.forEach { (k, v) -> obj.put(k, v) }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit {
            putString("watches", arr.toString())
            putString("tombstones", obj.toString())
        }
        _watches.value = list
        runCatching { Platform.current.watchesChanged() }
        Sync.scheduleSoon()
    }

    /** Módosítási időbélyeg: most, de mindig az előző után (eltérő órájú eszközök között is). */
    internal fun stamp(previous: Long): Long = maxOf(System.currentTimeMillis(), previous + 1)

    /** Felhasználói módosítás (szerkesztés, csengő): megjelöljük az időpontját a szinkronizáláshoz. */
    fun userUpdate(id: String, transform: (Watch) -> Watch) {
        update(id) { transform(it).copy(editedAt = stamp(it.editedAt)) }
        Sync.scheduleSoon()
    }

    @Synchronized
    fun userUpsert(watch: Watch) {
        val prev = _watches.value.find { it.id == watch.id }?.editedAt ?: watch.editedAt
        upsert(watch.copy(editedAt = stamp(prev)))
        Sync.scheduleSoon()
    }

    /** Törölt figyelések azonosítója → törlés ideje (a szinkronizáláshoz). */
    @Synchronized
    fun tombstones(): Map<String, Long> {
        val obj = runCatching { org.json.JSONObject(prefs.getString("tombstones", "{}") ?: "{}") }.getOrNull()
            ?: return emptyMap()
        return obj.keys().asSequence().associateWith { obj.optLong(it, 0L) }
    }

    @Synchronized
    internal fun saveTombstones(t: Map<String, Long>) {
        val cutoff = System.currentTimeMillis() - 120L * 24 * 3_600_000L
        val obj = org.json.JSONObject()
        t.filterValues { it > cutoff }.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit { putString("tombstones", obj.toString()) }
    }

    /**
     * A felhőből jött állapot összefésülése a mostani helyi állapottal, a zár alatt (így a
     * hálózati lekérés közben történt szerkesztés, törlés vagy ellenőrzés nem vész el).
     * Felhasználói időbélyeget nem változtat. Visszaadja a feltöltendő állapotot.
     */
    @Synchronized
    internal fun mergeFromSync(remote: List<Watch>, remoteTomb: Map<String, Long>): Pair<List<Watch>, Map<String, Long>> {
        val merged = Sync.merge(_watches.value, tombstones(), remote, remoteTomb)
        if (merged.first != _watches.value) persist(merged.first)
        saveTombstones(merged.second)
        return merged
    }

    @Synchronized
    fun clearAllResults() {
        persist(_watches.value.map { it.clearResults() })
    }

    /**
     * Pénznemváltás: a célárakat is átváltjuk ([factor] = 1 régi egység hány új egység).
     * Ha az árfolyam nem érhető el (factor == null), a célár marad, de az értesítést
     * kikapcsoljuk, és kérjük az új célárat – különben a régi szám (pl. 30 000 Ft → 30 000 €)
     * azonnal téves riasztást adna.
     */
    private val currencyMutex = Mutex()

    /**
     * Pénznemváltás a háttérben, egymás után sorban: ha gyorsan kétszer váltasz, a második
     * a már átváltott célárakból és a valóban aktuális pénznemből számol.
     */
    suspend fun switchCurrency(to: String) = currencyMutex.withLock {
        val from = settings.value.currency
        if (from == to) return@withLock
        val factor = runCatching { Rates.convert(1.0, from, to) }.getOrNull()?.takeIf { it > 0 && it.isFinite() }
        changeCurrency(to, factor)
    }

    @Synchronized
    private fun changeCurrency(to: String, factor: Double?) {
        persist(_watches.value.map { w ->
            val cleared = w.clearResults()
            if (factor != null) {
                val raw = w.targetPrice * factor
                val target = if (to == "HUF") (Math.round(raw / 100.0) * 100).toInt() else Math.round(raw).toInt()
                cleared.copy(targetPrice = target.coerceAtLeast(1))
            } else {
                cleared.copy(notify = false, lastError = "${PriceChecker.CURRENCY_HINT_PREFIX}: add meg újra a célárat, és kapcsold vissza az értesítést.")
            }
        })
        saveSettings(_settings.value.copy(currency = to))
    }

    @Synchronized
    fun saveSettings(settings: Settings) {
        prefs.edit {
            putBoolean("googleOn", settings.googleOn)
            putBoolean("ryanairOn", settings.ryanairOn)
            putBoolean("wizzOn", settings.wizzOn)
            putBoolean("serpOn", settings.serpOn)
            putBoolean("ignavOn", settings.ignavOn)
            putString("apiKey", settings.apiKey)
            putString("ignavKey", settings.ignavKey)
            putString("currency", settings.currency)
            putInt("intervalHours", settings.intervalHours)
            putString("themeMode", settings.themeMode)
            putInt("textScale", settings.textScale)
            putBoolean("quietOn", settings.quietOn)
            putInt("quietFrom", settings.quietFrom)
            putInt("quietTo", settings.quietTo)
        }
        _settings.value = settings
    }

    /**
     * Figyelések átvétele (visszaállítás fájlból vagy megosztott kódból). Az azonos azonosítójú
     * figyelést frissíti, a többit hozzáadja. Ha a figyelések más pénznemben készültek, a
     * célárakat átváltja (lásd [convertTargets]). Visszaadja: (új, frissített) darabszám.
     */
    suspend fun importWatches(incoming: List<Watch>, currency: String): Pair<Int, Int> {
        val converted = convertTargets(incoming, currency, settings.value.currency)
        return synchronized(this) {
            val list = _watches.value.toMutableList()
            var added = 0
            var updated = 0
            val now = System.currentTimeMillis()
            for (w0 in converted) {
                val w = w0.copy(editedAt = now)
                val i = list.indexOfFirst { it.id == w.id }
                if (i >= 0) {
                    // A meglévő eredmények maradnak, ha a keresés ugyanaz
                    val old = list[i]
                    list[i] = if (old.searchKey() == w.searchKey()) w.copy(
                        lastPrice = old.lastPrice, lowestPrice = old.lowestPrice, lastChecked = old.lastChecked,
                        offers = old.offers, sourceStatus = old.sourceStatus, history = old.history,
                    ) else w
                    updated++
                } else {
                    list += w
                    added++
                }
            }
            persist(list)
            added to updated
        }.also { Sync.scheduleSoon() }
    }

    /** Célárak átváltása egyik pénznemről a másikra (árfolyamhiba esetén értesítés ki + figyelmeztetés). */
    private fun convertTargets(list: List<Watch>, from: String, to: String): List<Watch> {
        if (from == to) return list
        val factor = runCatching { Rates.convert(1.0, from, to) }.getOrNull()?.takeIf { it > 0 && it.isFinite() }
        return list.map { w ->
            val cleared = w.clearResults()
            if (factor != null) {
                val raw = w.targetPrice * factor
                val target = if (to == "HUF") (Math.round(raw / 100.0) * 100).toInt() else Math.round(raw).toInt()
                cleared.copy(targetPrice = target.coerceAtLeast(1))
            } else {
                cleared.copy(notify = false, lastError = "${PriceChecker.CURRENCY_HINT_PREFIX}: add meg újra a célárat, és kapcsold vissza az értesítést.")
            }
        }
    }

    private fun persist(list: List<Watch>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit { putString("watches", arr.toString()) }
        _watches.value = list
        runCatching { Platform.current.watchesChanged() }
    }
}
