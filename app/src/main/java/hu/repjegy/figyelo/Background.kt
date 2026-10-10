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
import androidx.glance.appwidget.updateAll
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
    override val updatesViaStore: Boolean get() = BuildConfig.PLAY_STORE
    override val versionName: String get() = BuildConfig.VERSION_NAME
    override val buildNumber: Int get() = BuildConfig.VERSION_CODE
    override val installerSuffix = ".apk"
    override val deviceWord: String get() = tr("telefonon", "on your phone", "auf deinem Handy")
    override val updateSteps: String
        get() = tr(
            "1. Koppints a gombra, a böngésző letölti az új verziót.\n" +
                "2. Nyisd meg a letöltött fájlt, és telepítsd (idegen forrásból származó " +
                "alkalmazás telepítését engedélyezni kell).",
            "1. Tap the button and your browser downloads the new version.\n" +
                "2. Open the downloaded file and install it (you may need to allow " +
                "installing apps from unknown sources).",
            "1. Tippe auf den Button, dein Browser lädt die neue Version herunter.\n" +
                "2. Öffne die heruntergeladene Datei und installiere sie (eventuell musst du " +
                "die Installation aus unbekannten Quellen erlauben).",
        )
    override val backgroundHint: String
        get() = tr(
            "Az Android energiatakarékossága miatt a háttér-ellenőrzés kicsit csúszhat.",
            "Because of Android's battery saving, background checks may run a little late.",
            "Wegen des Energiesparmodus von Android können Prüfungen im Hintergrund etwas später laufen.",
        )
    override val appFont = FontFamily(
        Font(R.font.jakarta_regular, FontWeight.Normal),
        Font(R.font.jakarta_medium, FontWeight.Medium),
        Font(R.font.jakarta_semibold, FontWeight.SemiBold),
        Font(R.font.jakarta_bold, FontWeight.Bold),
    )

    override fun openUrl(url: String) = openUrl(context, url)
    override fun openAsset(name: String): InputStream = context.assets.open(name)

    override fun addToCalendar(events: List<CalEvent>): Boolean {
        // Fordított sorrendben indítjuk: így az odaút ablaka van felül, mentés után jön a visszaút
        var ok = false
        for (e in events.reversed()) {
            val intent = android.content.Intent(android.content.Intent.ACTION_INSERT)
                .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startMillis())
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, e.endMillis())
                .putExtra(android.provider.CalendarContract.Events.TITLE, e.title)
                .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, e.location)
                .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, e.notes)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ok = runCatching { context.startActivity(intent); true }.getOrDefault(false) || ok
        }
        return ok
    }
    override fun notifyPriceDrop(w: Watch, currency: String) = Notifier.priceDrop(context, w, currency)
    override fun notifyUpdate(release: Updater.Release) = Notifier.update(context, release)
    override fun notifyMessage(key: String, title: String, text: String, url: String?) =
        Notifier.message(context, key, title, text, url)
    override fun reschedule() = Scheduler.schedule(context)

    override fun notificationsBlocked(): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return true
        val channel = nm.getNotificationChannel(Notifier.PRICE_CHANNEL_ID)
        return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    override fun shareText(text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return try {
            context.startActivity(
                Intent.createChooser(send, tr("Figyelés megosztása", "Share watch", "Beobachtung teilen")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            false
        } catch (_: RuntimeException) {
            // Nincs megosztásra alkalmas app: vágólapra tesszük
            runCatching {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("REFI", text))
            }.isSuccess
        }
    }

    override fun readClipboard(): String? = runCatching {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    override fun exportFile(suggestedName: String, content: String, onDone: (Boolean) -> Unit) =
        FileBridge.export(suggestedName, content, onDone)

    override fun importFile(onResult: (String?) -> Unit) = FileBridge.import(onResult)

    override suspend fun googleAccessToken(interactive: Boolean): String? =
        GoogleAuthAndroid.token(context, interactive)

    override fun googleInvalidateToken() {
        // A clearToken hálózatot/IPC-t használhat: háttérszálon
        AppScope.scope.launch { GoogleAuthAndroid.invalidate(context) }
    }

    override fun googleSignOut() {
        AppScope.scope.launch { GoogleAuthAndroid.signOut(context) }
    }

    override fun googleCancelSignIn() = GoogleAuthAndroid.cancel()

    private var widgetJob: kotlinx.coroutines.Job? = null

    /** A widget frissítése; gyors egymásutáni változásoknál csak egyszer. */
    @Synchronized
    override fun watchesChanged() {
        widgetJob?.cancel()
        widgetJob = AppScope.scope.launch {
            kotlinx.coroutines.delay(1500)
            runCatching { RefiWidget().updateAll(context) }
        }
    }

    /** Nyelvváltás: az értesítési csatornák neve és a widget szövegei az új nyelven. */
    override fun languageChanged() {
        Notifier.relocalizeChannels(context)
        AppScope.scope.launch { runCatching { RefiWidget().updateAll(context) } }
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
        // Előbb a másik eszköz módosításai, utána ellenőrzés, végül a friss árak vissza a felhőbe
        try {
            runCatching { Sync.syncNow() }
            // Egy váratlan hiba az ellenőrzésben ne vigye el a szinkront, a frissítésfigyelést és a widgetet
            if (Store.settings.value.intervalHours > 0) {
                try {
                    PriceChecker.checkAll()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            runCatching { Sync.syncNow() }
            runCatching { Updater.dailyCheck() }
        } finally {
            // A widget frissítése még a háttérmunka vége előtt (utána a folyamat leállhat) – akkor is,
            // ha a rendszer közben leállította a munkát
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { RefiWidget().updateAll(applicationContext) }
            }
        }
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

    /** Csendes órákban: hang és rezgés nélkül (reggel ott vár az értesítések között). */
    private const val QUIET_CHANNEL_ID = "price_alerts_quiet"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL_ID, tr("Árriasztások (csendes órák)", "Price alerts (quiet hours)", "Preisalarme (Ruhezeiten)"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Csendes órákban érkező árriasztások, hang és rezgés nélkül", "Price alerts during quiet hours, without sound or vibration", "Preisalarme während der Ruhezeiten, ohne Ton und Vibration")
                enableVibration(false)
                setSound(null, null)
            }
        )
        val channel = NotificationChannel(
            CHANNEL_ID,
            tr("Árriasztások", "Price alerts", "Preisalarme"),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = tr("Értesítés, ha egy figyelt jegy a célár alá esik", "Notifies you when a watched ticket drops below your target price", "Benachrichtigung, wenn ein beobachtetes Ticket unter deinen Zielpreis fällt")
            enableVibration(true)
            vibrationPattern = VIBRATION
        }
        manager.createNotificationChannel(channel)
    }

    private const val UPDATE_CHANNEL_ID = "app_updates"
    private const val UPDATE_NOTIFICATION_ID = 4242

    private fun createUpdateChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL_ID, tr("Frissítések", "Updates", "Updates"), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = tr("Értesítés, ha az appból új verzió jelent meg", "Notifies you when a new version of the app is out", "Benachrichtigung, wenn eine neue Version der App erschienen ist") }
        )
    }

    /**
     * Nyelvváltáskor a csatornák nevének frissítése (ugyanazzal az azonosítóval újra létrehozva
     * az Android csak a nevet és a leírást írja át; a hang/rezgés beállítás marad).
     */
    fun relocalizeChannels(context: Context) {
        runCatching { createChannel(context) }
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(UPDATE_CHANNEL_ID) != null) createUpdateChannel(manager)
        }
    }

    fun update(context: Context, release: Updater.Release) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        createUpdateChannel(manager)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, UPDATE_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle(tr("Új verzió érhető el", "New version available", "Neue Version verfügbar"))
            .setContentText(tr("Megjelent a REFI ${release.version}. Koppints a frissítéshez.", "REFI ${release.version} is out. Tap to update.", "REFI ${release.version} ist da. Tippe zum Aktualisieren."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    fun message(context: Context, key: String, title: String, text: String, url: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = (url?.takeIf(::isSafeWebUrl)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it.trim())) }
            ?: Intent(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quiet = Store.settings.value.isQuiet()
        val n = NotificationCompat.Builder(context, if (quiet) QUIET_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (!quiet) setVibrate(VIBRATION) else setSilent(true) }
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(key.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    /** Gombok az árértesítésen: Foglalás (a legjobb ajánlat oldala), Megosztás (a családnak), Megnyitás (az app). */
    private fun priceActions(context: Context, w: Watch, best: Offer, currency: String): List<NotificationCompat.Action> {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val base = w.id.hashCode()
        val list = mutableListOf<NotificationCompat.Action>()
        best.url?.takeIf(::isSafeWebUrl)?.let { url ->
            val book = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            list += NotificationCompat.Action(0, tr("Foglalás", "Book", "Buchen"),
                PendingIntent.getActivity(context, base + 1, book, flags))
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, ShareCode.message(w, currency))
        val chooser = Intent.createChooser(send, tr("Megosztás", "Share", "Teilen")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        list += NotificationCompat.Action(0, tr("Megosztás", "Share", "Teilen"),
            PendingIntent.getActivity(context, base + 2, chooser, flags))
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        list += NotificationCompat.Action(0, tr("Megnyitás", "Open", "Öffnen"),
            PendingIntent.getActivity(context, base + 3, open, flags))
        return list
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
            append(tr("A célár (${formatPrice(w.targetPrice, currency)}) alá esett.", "Dropped below your target price (${formatPrice(w.targetPrice, currency)}).", "Unter deinen Zielpreis (${formatPrice(w.targetPrice, currency)}) gefallen."))
            best.outboundText()?.let { append(tr("\nIndulás: $it", "\nDeparture: $it", "\nAbflug: $it")) }
            best.returnText()?.let { append(tr("\nVissza: $it", "\nReturn: $it", "\nRückflug: $it")) }
            append("\n")
            append(listOfNotNull(best.airline, best.source).distinct().joinToString(" · "))
            best.noteText?.let { append(" ($it)") }
        }
        val quiet = Store.settings.value.isQuiet()
        val notification = NotificationCompat.Builder(context, if (quiet) QUIET_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle("${w.routeTitle}: ${formatPrice(price, currency)}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (!quiet) setVibrate(VIBRATION) else setSilent(true) }
            .setContentIntent(pending)
            .setAutoCancel(true)
            .apply { priceActions(context, w, best, currency).forEach { addAction(it) } }
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
