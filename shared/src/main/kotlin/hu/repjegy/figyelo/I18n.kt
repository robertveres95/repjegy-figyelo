package hu.repjegy.figyelo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Nyelv: magyar vagy angol. Alapból a készülék nyelve dönt (magyar rendszeren magyar, máshol angol),
 * a Beállításokban felülírható. A felületi szövegek a [tr] függvénnyel, mindkét nyelven, a használat
 * helyén szerepelnek (így a magyar szöveg mellett mindig ott az angol párja).
 */
object Lang {
    const val AUTO = "auto"
    const val HU_CODE = "hu"
    const val EN_CODE = "en"
    private const val KEY = "language"

    /** A választott beállítás (auto / hu / en). Compose-állapot: váltáskor az egész felület újrarajzolódik. */
    var setting by mutableStateOf(System.getProperty("refi.lang")?.takeIf { it == HU_CODE || it == EN_CODE } ?: AUTO)
        private set

    /** Angol-e most a felület. */
    val en: Boolean
        get() = when (setting) {
            EN_CODE -> true
            HU_CODE -> false
            else -> Locale.getDefault().language != "hu"
        }

    /** A dátumok és hónapnevek nyelve. */
    val locale: Locale get() = if (en) Locale.UK else HU

    /** Indításkor a mentett választás betöltése (a tesztek a refi.lang rendszerjellemzővel rögzíthetik). */
    fun load() {
        if (System.getProperty("refi.lang") != null) return
        setting = runCatching { Store.prefs.getString(KEY, AUTO) }.getOrNull()?.takeIf { it in listOf(AUTO, HU_CODE, EN_CODE) } ?: AUTO
    }

    fun set(value: String) {
        setting = value
        runCatching { Store.prefs.edit { putString(KEY, value) } }
    }

    val OPTIONS: List<Pair<String, String>>
        get() = listOf(AUTO to tr("A készülék nyelve", "Device language"), HU_CODE to "Magyar", EN_CODE to "English")
}

/** Szöveg a felület nyelvén. */
fun tr(hu: String, en: String): String = if (Lang.en) en else hu
