package hu.repjegy.figyelo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val chartDay = DateTimeFormatter.ofPattern("MMM d.", HU)
private const val DAY_MS = 24 * 3_600_000L

/** Mennyi Google-előzményt mutatunk a saját mérések előtt. */
private const val MARKET_DAYS = 14

/** A grafikon adatai: a Google előzménye (a saját mérések előtti 2 hét) és a saját mérések. */
internal class ChartData(val market: List<PricePoint>, val own: List<PricePoint>, val target: Int) {
    val all get() = market + own
    val boundary: Long? get() = if (market.isNotEmpty()) own.firstOrNull()?.time ?: market.last().time else null
    val drawable: Boolean get() = all.size >= 2
}

internal fun chartDataFor(w: Watch, now: Long = System.currentTimeMillis()): ChartData {
    val own = w.history.sortedBy { it.time }
    // Az előzmény a saját mérések kezdete előtti 2 hét (ha még nincs saját mérés: a mai napig)
    val start = own.firstOrNull()?.time ?: now
    // A Google előzménye poggyász nélküli ár: poggyászos figyelésnél nem összevethető, ezért nem mutatjuk
    val market = (if (w.wantsBags) null else w.market)?.points.orEmpty()
        .filter { it.time < start - DAY_MS / 2 && it.time >= start - MARKET_DAYS * DAY_MS }
        .sortedBy { it.time }
    return ChartData(market, own, w.targetPrice)
}

/**
 * Árgörbe: balra halványan a Google árelőzménye, a függőleges szaggatott vonaltól jobbra a REFI
 * saját mérései. Szaggatott vízszintes vonal: a célár. A legmagasabb és legalacsonyabb pont
 * árát és az időszak dátumait is kiírja.
 */
@Composable
internal fun PriceChart(w: Watch, currency: String, modifier: Modifier = Modifier) {
    val data = remember(w.history, w.market, w.targetPrice) { chartDataFor(w) }
    if (!data.drawable) return
    val measurer = rememberTextMeasurer()
    val ownColor = Neon.Green
    val marketColor = Neon.TextDim
    val targetColor = Neon.Pink
    val labelColor = Neon.TextDim
    val textColor = Neon.Text
    val draw = remember(data.all.size) { Animatable(0f) }
    LaunchedEffect(data.all.size) { draw.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            if (size.width < 40f || size.height < 40f) return@Canvas // első elrendezés / animáció közben
            val small = TextStyle(fontSize = 11.sp, color = labelColor)
            val strong = TextStyle(fontSize = 11.sp, color = textColor)
            val bottomBand = 18.dp.toPx()          // dátumok helye alul
            val topPad = 16.dp.toPx()              // a legmagasabb ár felirata fölött
            val minLabelPad = 16.dp.toPx()         // a legalacsonyabb ár felirata alatt (ne lógjon a dátumokra)
            val chartH = size.height - bottomBand - topPad - minLabelPad
            val pts = data.all
            val prices = pts.map { it.price }
            val lo = minOf(prices.min(), data.target).toFloat()
            val hi = maxOf(prices.max(), data.target).toFloat()
            val span = (hi - lo).takeIf { it > 0f } ?: 1f
            val minP = lo - span * 0.08f
            val maxP = hi + span * 0.08f
            val t0 = pts.first().time
            val t1 = pts.last().time.takeIf { it > t0 } ?: (t0 + 1)
            val leftPad = 2.dp.toPx()
            val rightPad = 2.dp.toPx()
            val plotW = size.width - leftPad - rightPad
            fun x(t: Long) = leftPad + (t - t0).toFloat() / (t1 - t0) * plotW
            fun y(p: Int) = topPad + (1f - (p - minP) / (maxP - minP)) * chartH

            // Célár: szaggatott vízszintes vonal, a bal szélén felirattal
            val ty = y(data.target)
            drawLine(targetColor, Offset(0f, ty), Offset(size.width, ty), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            label(measurer, "célár ${formatPrice(data.target, currency)}", TextStyle(fontSize = 11.sp, color = targetColor),
                Offset(4.dp.toPx(), ty), above = ty > topPad + chartH / 2)

            // A Google-előzmény és a saját mérések határa
            data.boundary?.let { b ->
                val bx = x(b)
                drawLine(labelColor.copy(alpha = 0.6f), Offset(bx, topPad - 4.dp.toPx()), Offset(bx, topPad + chartH), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }

            fun pathOf(list: List<PricePoint>) = Path().apply {
                list.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.time), y(p.price)) else lineTo(x(p.time), y(p.price)) }
            }
            clipRect(right = size.width * draw.value) {
                if (data.market.size >= 2 || (data.market.isNotEmpty() && data.own.isNotEmpty())) {
                    // A Google-görbe a saját első méréshez csatlakozik, hogy ne legyen rés
                    val m = data.market + listOfNotNull(data.own.firstOrNull())
                    drawPath(pathOf(m), marketColor, style = Stroke(width = 2.dp.toPx()))
                }
                if (data.own.size >= 2) {
                    val p = pathOf(data.own)
                    drawPath(p, ownColor.copy(alpha = 0.22f), style = Stroke(width = 7.dp.toPx()))
                    drawPath(p, ownColor, style = Stroke(width = 2.5.dp.toPx()))
                } else if (data.own.size == 1) {
                    drawCircle(ownColor, 3.5.dp.toPx(), Offset(x(data.own[0].time), y(data.own[0].price)))
                }
            }

            // A legmagasabb és a legalacsonyabb pont, az árukkal
            val maxPt = pts.maxBy { it.price }
            val minPt = pts.minBy { it.price }
            for ((pt, isMax) in listOf(maxPt to true, minPt to false)) {
                if (!isMax && pt === maxPt) continue
                val c = Offset(x(pt.time), y(pt.price))
                val col = if (data.own.any { it === pt }) ownColor else marketColor
                drawCircle(col, 3.5.dp.toPx(), c)
                label(measurer, formatPrice(pt.price, currency), strong, c, above = isMax, centered = true)
            }

            // Dátumok alul: az eleje, a határ és a vége
            val dateY = size.height - bottomBand + 3.dp.toPx()
            fun dayText(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().format(chartDay)
            val first = measurer.measure(dayText(t0), small)
            val last = measurer.measure(dayText(pts.last().time), small)
            val placed = mutableListOf(0f to first.size.width.toFloat(), (size.width - last.size.width) to size.width)
            drawText(first, topLeft = Offset(0f, dateY))
            if (dayText(pts.last().time) != dayText(t0)) drawText(last, topLeft = Offset(size.width - last.size.width, dateY))
            // A határ dátuma csak akkor, ha elfér a két szélső között (különben egymásra csúsznának)
            data.boundary?.let { b ->
                val text = dayText(b)
                if (text != dayText(t0) && text != dayText(pts.last().time)) {
                    val layout = measurer.measure(text, small)
                    val left = (x(b) - layout.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - layout.size.width))
                    val right = left + layout.size.width
                    val gap = 6.dp.toPx()
                    if (placed.none { (l, r) -> left < r + gap && right > l - gap }) {
                        drawText(layout, topLeft = Offset(left, dateY))
                    }
                }
            }
        }
        // Jelmagyarázat
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (data.market.isNotEmpty()) LegendItem(marketColor, "Google árelőzmény")
            LegendItem(ownColor, "REFI mérései")
        }
        w.market?.takeIf { !w.wantsBags }?.let { m ->
            if (m.typicalLow != null && m.typicalHigh != null) {
                Text(
                    "A Google szerint ezen az úton a szokásos ár: ${formatPrice(m.typicalLow, currency)} – ${formatPrice(m.typicalHigh, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.width(16.dp).height(3.dp)) { drawRect(color) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Felirat egy pont fölé vagy alá, a rajzterületen belül tartva. */
private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    at: Offset,
    above: Boolean,
    centered: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    val gap = 4.dp.toPx()
    // (a felirat szélesebb is lehet a rajzterületnél – nagy betűméret, keskeny ablak –, ilyenkor 0-tól indul)
    val left = (if (centered) at.x - layout.size.width / 2f else at.x).coerceIn(0f, maxOf(0f, size.width - layout.size.width))
    val top = (if (above) at.y - gap - layout.size.height else at.y + gap).coerceIn(0f, maxOf(0f, size.height - layout.size.height))
    drawText(layout, topLeft = Offset(left, top))
}
