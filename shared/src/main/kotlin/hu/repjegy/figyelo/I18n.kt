package hu.repjegy.figyelo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Nyelv: magyar, angol vagy német. Alapból a készülék nyelve dönt (magyar rendszeren magyar, német
 * rendszeren német, minden más nyelvű rendszeren angol), a Beállításokban felülírható. A felületi
 * szövegek a [tr] függvénnyel, minden nyelven, a használat helyén szerepelnek.
 */
object Lang {
    const val AUTO = "auto"
    const val HU_CODE = "hu"
    const val EN_CODE = "en"
    const val DE_CODE = "de"
    private const val KEY = "language"
    private val CODES = listOf(HU_CODE, EN_CODE, DE_CODE)

    /** A választott beállítás (auto / hu / en / de). Compose-állapot: váltáskor az egész felület újrarajzolódik. */
    var setting by mutableStateOf(System.getProperty("refi.lang")?.takeIf { it in CODES } ?: AUTO)
        private set

    /** A felület tényleges nyelve: "hu", "en" vagy "de". */
    val code: String
        get() = when (setting) {
            HU_CODE, EN_CODE, DE_CODE -> setting
            else -> when (Locale.getDefault().language) {
                "hu" -> HU_CODE
                "de" -> DE_CODE
                else -> EN_CODE
            }
        }

    val hu: Boolean get() = code == HU_CODE
    val en: Boolean get() = code == EN_CODE
    val de: Boolean get() = code == DE_CODE

    /** A dátumok, hónap- és országnevek nyelve. */
    val locale: Locale
        get() = when (code) {
            HU_CODE -> HU
            DE_CODE -> Locale.GERMANY
            else -> Locale.UK
        }

    /** Indításkor a mentett választás betöltése (a tesztek a refi.lang rendszerjellemzővel rögzíthetik). */
    fun load() {
        if (System.getProperty("refi.lang") != null) return
        val saved = runCatching { Store.prefs.getString(KEY, null) }.getOrNull()
        // Frissítés egy korábbi (csak magyar) verzióról: marad magyar, akkor is, ha a gép más nyelvű –
        // csak az új telepítések követik a készülék nyelvét
        if (saved == null && runCatching { Store.prefs.getString("seenVersion", null) }.getOrNull() != null) {
            set(HU_CODE)
            return
        }
        setting = saved?.takeIf { it == AUTO || it in CODES } ?: AUTO
    }

    fun set(value: String) {
        setting = value
        runCatching { Store.prefs.edit { putString(KEY, value) } }
        // A reptérlista az új nyelven a háttérben töltődjön be (ne a felület szálán, az első kártyánál)
        runCatching { AppScope.scope.launch { runCatching { Airports.preload() } } }
    }

    val OPTIONS: List<Pair<String, String>>
        get() = listOf(
            AUTO to tr("A készülék nyelve", "Device language", "Gerätesprache"),
            HU_CODE to "Magyar", EN_CODE to "English", DE_CODE to "Deutsch",
        )
}

/** Szöveg a felület nyelvén. A német hiányában angolul. */
fun tr(hu: String, en: String, de: String? = null): String = when (Lang.code) {
    Lang.HU_CODE -> hu
    Lang.DE_CODE -> de ?: en
    else -> en
}

/**
 * Egy szöveg minden nyelven – a figyelésekben eltárolt szövegekhez (hibaüzenet, forrásállapot, ajánlat-
 * megjegyzés), hogy nyelvváltás után is a felület nyelvén látszódjanak, ne azon, amelyiken épp keletkeztek.
 */
data class L10n(val hu: String, val en: String, val de: String? = null) {
    /** A felület nyelvén. */
    val text: String get() = tr(hu, en, de)

    /** Ennek a szövegnek valamelyik nyelvű változata-e [s]. */
    fun has(s: String?): Boolean = s != null && (s == hu || s == en || s == (de ?: en))

    fun toJson(): JSONObject = JSONObject().put("hu", hu).put("en", en).putOpt("de", de)

    operator fun plus(other: L10n) = L10n(hu + other.hu, en + other.en, (de ?: en) + (other.de ?: other.en))

    companion object {
        fun of(s: String) = L10n(s, s, s)

        fun fromJson(o: JSONObject?): L10n? {
            if (o == null) return null
            val hu = o.optString("hu", "").ifBlank { return null }
            val en = o.optString("en", "").ifBlank { return null }
            return L10n(hu.take(400), en.take(400), o.optString("de", "").ifBlank { null }?.take(400))
        }

        /** Több rész összefűzése (pl. megjegyzések vesszővel). */
        fun join(parts: List<L10n>, sep: String): L10n? = if (parts.isEmpty()) null else
            L10n(parts.joinToString(sep) { it.hu }, parts.joinToString(sep) { it.en }, parts.joinToString(sep) { it.de ?: it.en })
    }
}

/**
 * Mint a [tr], de a szöveget minden nyelven megjegyzi: amikor később eltároljuk (pl. egy hiba üzenetét
 * a figyelésbe), a [Texts.find] visszaadja mindhárom nyelvű változatát. A forrásoknál és hibáknál ezt
 * használjuk; a felületen a sima [tr]-t.
 */
fun trs(hu: String, en: String, de: String? = null): String {
    val l = L10n(hu, en, de)
    val out = l.text
    Texts.remember(out, l)
    return out
}

/** A [trs]-sel készült szövegek nyelvi változatai (a legutóbbi néhány száz). */
object Texts {
    private val map = object : LinkedHashMap<String, L10n>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, L10n>?) = size > 600
    }

    @Synchronized
    fun remember(text: String, l: L10n) {
        map[text] = l
    }

    /** Egy (a [trs]-sel, bármelyik nyelven készült) szöveg minden nyelvű változata, ha ismert. */
    @Synchronized
    fun find(text: String?): L10n? {
        if (text == null) return null
        map[text]?.let { return it }
        // Vesszővel összefűzött részek (pl. ajánlat-megjegyzések)
        if (text.contains(", ")) {
            val parts = text.split(", ").map { map[it] }
            if (parts.all { it != null }) return L10n.join(parts.filterNotNull(), ", ")
        }
        // „Előtag: belső üzenet” alakú összetett szöveg: mindkét rész külön ismert lehet
        val i = text.indexOf(": ")
        if (i > 0) {
            val head = map[text.substring(0, i + 1)] ?: map[text.substring(0, i)]?.let { L10n(it.hu + ":", it.en + ":", it.de?.plus(":")) }
            val tail = map[text.substring(i + 2)]
            if (head != null && tail != null) return head + L10n.of(" ") + tail
        }
        return null
    }
}
