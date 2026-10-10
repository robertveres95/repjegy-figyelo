package hu.repjegy.figyelo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat

/**
 * Átlátszó, azonnal bezáródó Activity az értesítés gombjaihoz: eltünteti az értesítést, majd megnyitja a
 * kért célt (foglalási oldal, megosztás, az app). Közvetlen Activity-indítás – a rendszer ezt engedi
 * (a „trambulin” BroadcastReceiver Android 12-től tiltott).
 */
class NotificationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) runCatching { NotificationManagerCompat.from(this).cancel(id) }
        // Az IntentCompat kerüli az Android 13 típusos getParcelableExtra hibáját
        val target: Intent? = runCatching {
            androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, Intent::class.java)
        }.getOrNull()
        // Csak a saját, ismert célok (biztonság: kívülről érkező Intent nem indíthat tetszőleges dolgot)
        if (target != null && isAllowed(target)) runCatching { startActivity(target) }
        finish()
    }

    private fun isAllowed(t: Intent): Boolean = when (t.action) {
        Intent.ACTION_VIEW -> isSafeWebUrl(t.dataString)
        Intent.ACTION_CHOOSER -> true
        else -> t.component?.packageName == packageName
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "refi.notificationId"
        const val EXTRA_TARGET = "refi.target"

        fun wrap(context: Context, notificationId: Int, target: Intent): Intent =
            Intent(context, NotificationActionActivity::class.java)
                // Egyedi adat: a rendszer az extrákat nem nézi, így két figyelés gombjai nem írhatják felül egymást
                .setData(android.net.Uri.parse("refi-action://$notificationId/${target.action ?: "open"}"))
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
