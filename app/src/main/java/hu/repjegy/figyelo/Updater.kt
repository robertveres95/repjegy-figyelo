package hu.repjegy.figyelo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Új verzió keresése: a GitHub-repó legfrissebb kiadását („build-N”) veti össze
 * a telepített verzió számával. A repó nyilvános, így kulcs nem kell.
 */
object Updater {
    private const val REPO = "robertveres95/repjegy-figyelo"
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 4242
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Egy kiadás: verziószám (pl. "1.1.0") és a build sorszáma. */
    data class Release(val version: String, val build: Int, val apkUrl: String, val pageUrl: String)

    /** Ha nem null, van újabb verzió, és a felület kötelező frissítést kér. */
    val available = MutableStateFlow<Release?>(null)

    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val currentVersion: String get() = BuildConfig.VERSION_NAME

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
                if (a.optString("name").endsWith(".apk")) apkUrl = a.optString("browser_download_url")
            }
        }
        val pageUrl = json.optString("html_url", "https://github.com/$REPO/releases/latest")
        val release = Release(version, build, apkUrl ?: pageUrl, pageUrl)
        if (isNewer(release)) release else null
    }.getOrNull().also { if (it != null) available.value = it }

    /** A háttérellenőrzésből naponta egyszer: ha van új verzió, értesítést is küld. */
    fun dailyCheck(context: Context) {
        val prefs = context.getSharedPreferences("repjegy", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastUpdateCheck", 0L) < DAY_MS) return
        prefs.edit().putLong("lastUpdateCheck", now).apply()
        val release = check() ?: return
        if (prefs.getInt("notifiedBuild", 0) >= release.build) return
        prefs.edit().putInt("notifiedBuild", release.build).apply()
        notify(context, release)
    }

    private fun notify(context: Context, release: Release) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Frissítések", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Értesítés, ha az appból új verzió jelent meg" }
        )
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle("Új verzió érhető el")
            .setContentText("Megjelent a REFI ${release.version}. Koppints a frissítéshez.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
