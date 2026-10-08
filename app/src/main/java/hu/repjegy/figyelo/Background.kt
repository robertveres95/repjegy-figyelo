package hu.repjegy.figyelo

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.ensure(this)
        AppScope.scope.launch { Airports.preload() }
        Notifier.createChannel(this)
        Scheduler.schedule(this)
    }
}

/** SharedPreferences a közös [Prefs] felület mögött. */
class AndroidPrefs(context: Context) : Prefs {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("repjegy", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?) = sp.getString(key, default)
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun getLong(key: String, default: Long) = sp.getLong(key, default)
    override fun edit(block: PrefsEditor.() -> Unit) {
        val e = sp.edit()
        object : PrefsEditor {
            override fun putString(key: String, value: String) { e.putString(key, value) }
            override fun putBoolean(key: String, value: Boolean) { e.putBoolean(key, value) }
            override fun putInt(key: String, value: Int) { e.putInt(key, value) }
            override fun putLong(key: String, value: Long) { e.putLong(key, value) }
        }.block()
        e.apply()
    }
}

/** Az androidos megvalósítás a közös kód platform-igényeihez. */
class AndroidPlatform private constructor(private val context: Context) : PlatformApi {
    override val versionName: String get() = BuildConfig.VERSION_NAME
    override val buildNumber: Int get() = BuildConfig.VERSION_CODE
    override val installerSuffix = ".apk"
    override val deviceWord = "telefonon"
    override val updateSteps =
        "1. Koppints a gombra, a böngésző letölti az új verziót.\n" +
            "2. Nyisd meg a letöltött fájlt, és telepítsd (idegen forrásból származó " +
            "alkalmazás telepítését engedélyezni kell)."
    override val backgroundHint = "Az Android energiatakarékossága miatt a háttér-ellenőrzés kicsit csúszhat."
    override val appFont = FontFamily(
        Font(R.font.jakarta_regular, FontWeight.Normal),
        Font(R.font.jakarta_medium, FontWeight.Medium),
        Font(R.font.jakarta_semibold, FontWeight.SemiBold),
        Font(R.font.jakarta_bold, FontWeight.Bold),
    )

    override fun openUrl(url: String) = openUrl(context, url)
    override fun openAsset(name: String): InputStream = context.assets.open(name)
    override fun notifyPriceDrop(w: Watch, currency: String) = Notifier.priceDrop(context, w, currency)
    override fun notifyUpdate(release: Updater.Release) = Notifier.update(context, release)
    override fun reschedule() = Scheduler.schedule(context)

    override fun notificationsBlocked(): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return true
        val channel = nm.getNotificationChannel(Notifier.PRICE_CHANNEL_ID)
        return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    override fun openNotificationSettings() {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
        }
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
    }

    @Composable
    override fun SystemBars(palette: AppPalette) {
        // Állapotsor és navigációs sáv ikonjai: világos témában sötétek
        val view = LocalView.current
        if (view.isInEditMode) return
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = palette.isLight
                isAppearanceLightNavigationBars = palette.isLight
            }
            window.decorView.setBackgroundColor(palette.background.toArgb())
        }
    }

    @Composable
    override fun Splash(onFinished: () -> Unit) = SplashOverlay(onFinished)

    companion object {
        /** Platform és tároló beállítása; többször is hívható (App, Activity, háttérmunka). */
        @Synchronized
        fun ensure(context: Context) {
            val app = context.applicationContext
            if (!platformReady) {
                Platform.current = AndroidPlatform(app)
                platformReady = true
            }
            Store.init(AndroidPrefs(app))
        }

        private var platformReady = false
    }
}

/** Háttérben, rendszeresen lefutó árellenőrzés. */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AndroidPlatform.ensure(applicationContext)
        if (Store.settings.value.intervalHours > 0) PriceChecker.checkAll()
        Updater.dailyCheck()
        return Result.success()
    }
}

object Scheduler {
    private const val WORK_NAME = "price-check"

    fun schedule(context: Context) {
        val hours = Store.settings.value.intervalHours.toLong()
        if (hours <= 0) {
            // Automatikus ellenőrzés kikapcsolva; a frissítésfigyelő miatt naponta egyszer azért fut
            val daily = PeriodicWorkRequestBuilder<CheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, daily)
            return
        }
        val request = PeriodicWorkRequestBuilder<CheckWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

object Notifier {
    // Új csatorna kell, mert a meglévő csatorna rezgését az Android nem engedi utólag módosítani
    const val PRICE_CHANNEL_ID = "price_alerts_v2"
    private const val CHANNEL_ID = PRICE_CHANNEL_ID
    private const val OLD_CHANNEL_ID = "price_alerts"

    /** Két rövid rezgés: várakozás, rezgés, szünet, rezgés (ms). */
    private val VIBRATION = longArrayOf(0, 180, 140, 180)

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Árriasztások",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Értesítés, ha egy figyelt jegy a célár alá esik"
            enableVibration(true)
            vibrationPattern = VIBRATION
        }
        manager.createNotificationChannel(channel)
    }

    private const val UPDATE_CHANNEL_ID = "app_updates"
    private const val UPDATE_NOTIFICATION_ID = 4242

    fun update(context: Context, release: Updater.Release) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL_ID, "Frissítések", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Értesítés, ha az appból új verzió jelent meg" }
        )
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, UPDATE_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle("Új verzió érhető el")
            .setContentText("Megjelent a REFI ${release.version}. Koppints a frissítéshez.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    fun priceDrop(context: Context, w: Watch, currency: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val best = w.bestOffer ?: return
        val price = best.price
        val intent = (best.url?.takeIf(::isSafeWebUrl)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it.trim())) }
            ?: Intent(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            w.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append("A célár (${formatPrice(w.targetPrice, currency)}) alá esett.")
            best.outboundText()?.let { append("\nIndulás: $it") }
            best.returnText()?.let { append("\nVissza: $it") }
            append("\n")
            append(listOfNotNull(best.airline, best.source).distinct().joinToString(" · "))
            best.note?.let { append(" ($it)") }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle("${w.routeTitle}: ${formatPrice(price, currency)}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(VIBRATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(w.id.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }
}

fun openUrl(context: Context, url: String?) {
    if (!isSafeWebUrl(url)) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url!!.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
    } catch (_: RuntimeException) {
    }
}
