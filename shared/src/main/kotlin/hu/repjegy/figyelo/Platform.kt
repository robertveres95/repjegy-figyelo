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

    fun openUrl(url: String)
    fun openAsset(name: String): InputStream
    fun notifyPriceDrop(w: Watch, currency: String)
    fun notifyUpdate(release: Updater.Release)

    /** Az automatikus ellenőrzés újraütemezése (gyakoriság változásakor). */
    fun reschedule()

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
