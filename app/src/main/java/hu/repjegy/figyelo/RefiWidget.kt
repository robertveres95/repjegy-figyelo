package hu.repjegy.figyelo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Kezdőképernyő-widget: a figyelt utak legjobb ára, a célár alattiak zölden.
 * Koppintásra megnyílik az app. Frissül minden ellenőrzés és szerkesztés után.
 */
class RefiWidget : GlanceAppWidget() {

    private class Row4(val title: String, val price: String, val good: Boolean, val sub: String)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AndroidPlatform.ensure(context)
        val currency = Store.settings.value.currency
        val rows = Store.watches.value
            .filter { !it.isExpired() }
            .sortedByDescending { w -> w.bestOffer?.let { w.alertable(it) } == true }
            .take(4)
            .map { w ->
                val best = w.bestOffer
                Row4(
                    title = w.routeTitle,
                    price = best?.let { formatPrice(it.price, currency) } ?: "—",
                    good = best != null && w.alertable(best),
                    sub = "célár ${formatPrice(w.targetPrice, currency)}",
                )
            }
        provideContent { Content(rows) }
    }

    @Composable
    private fun Content(rows: List<Row4>) {
        val green = ColorProvider(Color(0xFF39FF88))
        val text = ColorProvider(Color(0xFFE8F2EC))
        val dim = ColorProvider(Color(0xFF8A9A92))
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF050807))
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text("REFI", style = TextStyle(color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            if (rows.isEmpty()) {
                Spacer(GlanceModifier.height(6.dp))
                Text("Még nincs figyelt út.", style = TextStyle(color = dim, fontSize = 13.sp))
            }
            rows.forEach { r ->
                Spacer(GlanceModifier.height(6.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(r.title, maxLines = 1, style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        Text(r.sub, maxLines = 1, style = TextStyle(color = dim, fontSize = 11.sp))
                    }
                    Text(
                        r.price,
                        style = TextStyle(color = if (r.good) green else text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

class RefiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RefiWidget()
}
