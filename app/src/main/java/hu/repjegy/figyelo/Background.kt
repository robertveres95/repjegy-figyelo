package hu.repjegy.figyelo

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        scope.launch { Airports.preload(this@App) }
        Notifier.createChannel(this)
        Scheduler.schedule(this)
    }

    companion object {
        /** Alkalmazásszintű scope: a kézi ellenőrzés akkor is lefut, ha közben képernyőt váltasz. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Háttérben, rendszeresen lefutó árellenőrzés. */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        PriceChecker.checkAll(applicationContext)
        return Result.success()
    }
}

object Scheduler {
    private const val WORK_NAME = "price-check"

    fun schedule(context: Context) {
        val hours = Store.settings.value.intervalHours.toLong().coerceAtLeast(1)
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
    private const val CHANNEL_ID = "price_alerts"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Árriasztások",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Értesítés, ha egy figyelt jegy a célár alá esik" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun priceDrop(context: Context, w: Watch, currency: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val best = w.bestOffer ?: return
        val price = best.price
        val intent = (best.url?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
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
    if (url.isNullOrBlank()) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
    }
}
