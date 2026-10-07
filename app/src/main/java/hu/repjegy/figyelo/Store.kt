package hu.repjegy.figyelo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/** A figyelések és beállítások tárolása a telefonon (SharedPreferences, JSON). */
object Store {
    private lateinit var prefs: SharedPreferences
    private var initialized = false

    private val _watches = MutableStateFlow<List<Watch>>(emptyList())
    val watches: StateFlow<List<Watch>> = _watches

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    /** Éppen ellenőrzés alatt álló figyelések azonosítói. */
    val checking = MutableStateFlow<Set<String>>(emptySet())

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences("repjegy", Context.MODE_PRIVATE)
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
        val arr = runCatching { JSONArray(prefs.getString("watches", "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        _watches.value = (0 until arr.length()).mapNotNull { i ->
            runCatching { Watch.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
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

    @Synchronized
    fun saveSettings(settings: Settings) {
        prefs.edit()
            .putBoolean("googleOn", settings.googleOn)
            .putBoolean("ryanairOn", settings.ryanairOn)
            .putBoolean("wizzOn", settings.wizzOn)
            .putBoolean("serpOn", settings.serpOn)
            .putBoolean("ignavOn", settings.ignavOn)
            .putString("apiKey", settings.apiKey)
            .putString("ignavKey", settings.ignavKey)
            .putString("currency", settings.currency)
            .putInt("intervalHours", settings.intervalHours)
            .putString("themeMode", settings.themeMode)
            .putInt("textScale", settings.textScale)
            .apply()
        _settings.value = settings
    }

    private fun persist(list: List<Watch>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("watches", arr.toString()).apply()
        _watches.value = list
    }
}
