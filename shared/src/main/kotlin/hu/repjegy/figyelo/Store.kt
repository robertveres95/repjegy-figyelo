package hu.repjegy.figyelo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        )
        val raw = prefs.getString("watches", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull()
        val parsed = arr?.let { a ->
            (0 until a.length()).mapNotNull { i -> runCatching { Watch.fromJson(a.getJSONObject(i)) }.getOrNull() }
        }.orEmpty()
        // Ha valami nem olvasható be, a nyers adatot félretesszük, mielőtt a következő
        // mentés felülírná – így nem vész el végleg egy figyelés sem.
        if (arr == null || parsed.size < arr.length()) {
            prefs.edit { putString("watches_backup_${System.currentTimeMillis()}", raw) }
        }
        _watches.value = parsed
        initialized = true
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
        persist(_watches.value.filterNot { it.id == id })
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
    @Synchronized
    fun changeCurrency(to: String, factor: Double?) {
        persist(_watches.value.map { w ->
            val cleared = w.clearResults()
            if (factor != null) {
                val raw = w.targetPrice * factor
                val target = if (to == "HUF") (Math.round(raw / 100.0) * 100).toInt() else Math.round(raw).toInt()
                cleared.copy(targetPrice = target.coerceAtLeast(1))
            } else {
                cleared.copy(notify = false, lastError = "Pénznemet váltottál: add meg újra a célárat, és kapcsold vissza az értesítést.")
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
        }
        _settings.value = settings
    }

    private fun persist(list: List<Watch>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit { putString("watches", arr.toString()) }
        _watches.value = list
    }
}
