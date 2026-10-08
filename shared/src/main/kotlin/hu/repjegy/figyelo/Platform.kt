package hu.repjegy.figyelo

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.InputStream

/**
 * Ami platformonként (Android / Windows) eltér. A közös kód (shared/) csak ezen keresztül
 * éri el a rendszert; az androidos és a windowsos app indításkor beállítja a [Platform.current]-et.
 */
interface PlatformApi {
    /** A felhasználónak mutatott verzió, pl. "1.1.0". */
    val versionName: String

    /** Build-sorszám (a GitHub Actions futásszáma). */
    val buildNumber: Int

    /** A kiadás telepítőfájljának kiterjesztése: ".apk" vagy ".msi". */
    val installerSuffix: String

    /** Az app betűtípusa (Plus Jakarta Sans). */
    val appFont: FontFamily

    /** „telefonon” / „számítógépen” – a szövegekben. */
    val deviceWord: String

    /** A frissítési ablak lépései (a jóváhagyott szöveg platformra szabva). */
    val updateSteps: String

    /** Rövid megjegyzés a háttér-ellenőrzésről a Beállításokban. */
    val backgroundHint: String

    /** Mit jelentenek a csendes órák ezen a platformon. */
    val quietHint: String get() = "Ilyenkor a riasztás hang és rezgés nélkül érkezik; reggel ott vár az értesítések között."

    fun openUrl(url: String)
    fun openAsset(name: String): InputStream
    fun notifyPriceDrop(w: Watch, currency: String)
    fun notifyUpdate(release: Updater.Release)

    /** Az automatikus ellenőrzés újraütemezése (gyakoriság változásakor). */
    fun reschedule()

    /** Igaz, ha a rendszer letiltotta az app értesítéseit (ilyenkor egy riasztás sem jut el). */
    fun notificationsBlocked(): Boolean = false

    /** Az app értesítési beállításainak megnyitása a rendszerben. */
    fun openNotificationSettings() {}

    /** Szöveg megosztása (Androidon a megosztás menü, Windowson vágólapra másolás). Igaz, ha vágólapra került. */
    fun shareText(text: String): Boolean

    /** A vágólap szövege (kód beillesztéséhez), ha van. */
    fun readClipboard(): String?

    /** Fájl mentése a felhasználó által választott helyre; a végén [onDone] (siker). */
    fun exportFile(suggestedName: String, content: String, onDone: (Boolean) -> Unit)

    /** Fájl kiválasztása és beolvasása; null, ha a felhasználó megszakította vagy hiba volt. */
    fun importFile(onResult: (String?) -> Unit)

    /** A figyelések megváltoztak (pl. a kezdőképernyő-widget frissítéséhez). */
    fun watchesChanged() {}

    /**
     * Google-hozzáférési token a Drive alkalmazásadat-területéhez (szinkronizálás).
     * [interactive] = true esetén bejelentkezést / engedélyt kérhet; különben csak csendben próbálja.
     */
    suspend fun googleAccessToken(interactive: Boolean): String? = null

    /** A tárolt token elvetése (pl. 401 után). */
    fun googleInvalidateToken() {}

    /** Kijelentkezés a szinkronizálásból (a tárolt hozzáférés törlése). */
    fun googleSignOut() {}

    /** Folyamatban lévő bejelentkezés megszakítása (pl. bezárta a böngészőt). */
    fun googleCancelSignIn() {}

    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    /** Állapotsor/ablakkeret színezése a témához. */
    @Composable
    fun SystemBars(palette: AppPalette)

    /** Nyitóanimáció; a végén meghívja az [onFinished]-et. */
    @Composable
    fun Splash(onFinished: () -> Unit)
}

object Platform {
    lateinit var current: PlatformApi
}

object AppScope {
    /** Alkalmazásszintű scope: a kézi ellenőrzés akkor is lefut, ha közben képernyőt váltasz. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Hidegindításkor igaz: ilyenkor egyszer lefut a nyitóanimáció. */
    var splashPending = true

    /** Nő, valahányszor az app előtérbe kerül (Androidon onResume). */
    val resumeCount = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Egy másik appból (pl. Messenger → Megosztás → REFI) érkezett szöveg, amit még nem dolgoztunk fel. */
    val incomingText = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Képernyőképekhez és tesztekhez: belépő animációk nélkül. */
    @Volatile
    var reduceMotion = false
}

/** Egyszerű kulcs–érték tároló (Androidon SharedPreferences, Windowson fájl). */
interface Prefs {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun edit(block: PrefsEditor.() -> Unit)
}

interface PrefsEditor {
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
}
