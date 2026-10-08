package hu.repjegy.figyelo

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Új verzió keresése: a GitHub-repó legfrissebb kiadását („build-N”) veti össze
 * a telepített verzió számával. A repó nyilvános, így kulcs nem kell.
 */
object Updater {
    private const val REPO = "robertveres95/repjegy-figyelo"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Egy kiadás: verziószám (pl. "1.1.0") és a build sorszáma. */
    data class Release(val version: String, val build: Int, val apkUrl: String, val pageUrl: String)
    // apkUrl: a platformnak megfelelő telepítő (.apk vagy .msi) letöltési címe

    /** Ha nem null, van újabb verzió, és a felület kötelező frissítést kér. */
    val available = MutableStateFlow<Release?>(null)

    val currentBuild: Int get() = Platform.current.buildNumber
    val currentVersion: String get() = Platform.current.versionName

    private fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)

    /** Újabb-e a kiadás: előbb a verziószám dönt, egyezésnél a build sorszáma. */
    private fun isNewer(r: Release): Boolean {
        val a = parts(r.version)
        val b = parts(currentVersion)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return r.build > currentBuild
    }

    /**
     * Lekéri a legfrissebb éles kiadást (a teszt-buildek „prerelease”-ként nem számítanak).
     * A kiadás címkéje: v1.1.0-build-25. Hálózati hiba esetén csendben null.
     */
    fun check(): Release? = runCatching {
        val res = Http.request(
            "https://api.github.com/repos/$REPO/releases/latest",
            headers = mapOf("Accept" to "application/vnd.github+json"),
            timeoutMs = 20_000,
        )
        if (res.code !in 200..299) return@runCatching null
        val json = JSONObject(res.body)
        val tag = json.optString("tag_name")
        val build = Regex("""build-(\d+)""").find(tag)?.groupValues?.get(1)?.toInt() ?: return@runCatching null
        val version = Regex("""v(\d+\.\d+\.\d+)""").find(tag)?.groupValues?.get(1) ?: "1.0.$build"
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(Platform.current.installerSuffix)) apkUrl = a.optString("browser_download_url")
            }
        }
        val pageUrl = json.optString("html_url", "https://github.com/$REPO/releases/latest")
        val release = Release(version, build, apkUrl ?: pageUrl, pageUrl)
        if (isNewer(release)) release else null
    }.getOrNull().also { if (it != null) available.value = it }

    /** A háttérellenőrzésből naponta egyszer: ha van új verzió, értesítést is küld. */
    fun dailyCheck() {
        val prefs = Store.prefs
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastUpdateCheck", 0L) < DAY_MS) return
        prefs.edit { putLong("lastUpdateCheck", now) }
        val release = check() ?: return
        if (prefs.getInt("notifiedBuild", 0) >= release.build) return
        prefs.edit { putInt("notifiedBuild", release.build) }
        Platform.current.notifyUpdate(release)
    }
}
