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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val chartDay get() = DateTimeFormatter.ofPattern(
    when (Lang.code) {
        Lang.HU_CODE -> "MMM d."
        Lang.DE_CODE -> "d. MMM"
        else -> "d MMM"
    },
    Lang.locale,
)
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
    val chipColor = Neon.Surface
    val draw = remember(data.all.size) { Animatable(0f) }
    LaunchedEffect(data.all.size) { draw.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            if (size.width < 40f || size.height < 40f) return@Canvas // első elrendezés / animáció közben
            val small = TextStyle(fontSize = 11.sp, color = labelColor)
            val strong = TextStyle(fontSize = 11.sp, color = textColor, fontWeight = FontWeight.SemiBold)
            val targetStyle = TextStyle(fontSize = 11.sp, color = targetColor, fontWeight = FontWeight.SemiBold)
            val chipBg = chipColor.copy(alpha = 0.88f)
            val bottomBand = 20.dp.toPx()          // dátumok helye alul
            val topPad = 26.dp.toPx()              // a legmagasabb ár felirata fölött
            val minLabelPad = 26.dp.toPx()         // a legalacsonyabb ár felirata alatt (ne lógjon a dátumokra)
            val plotBottom = size.height - bottomBand // a feliratok eddig érhetnek le (alatta a dátumok)
            val chartH = plotBottom - topPad - minLabelPad
            val pts = data.all
            val prices = pts.map { it.price }
            val lo = minOf(prices.min(), data.target).toFloat()
            val hi = maxOf(prices.max(), data.target).toFloat()
            val span = (hi - lo).takeIf { it > 0f } ?: 1f
            val minP = lo - span * 0.08f
            val maxP = hi + span * 0.08f
            val t0 = pts.first().time
            val t1 = pts.last().time.takeIf { it > t0 } ?: (t0 + 1)
            val leftPad = 4.dp.toPx()
            val rightPad = 4.dp.toPx()
            val plotW = size.width - leftPad - rightPad
            fun x(t: Long) = leftPad + (t - t0).toFloat() / (t1 - t0) * plotW
            fun y(p: Int) = topPad + (1f - (p - minP) / (maxP - minP)) * chartH

            // Célár: szaggatott vízszintes vonal (a felirata a görbék után, felülre kerül)
            val ty = y(data.target)
            drawLine(targetColor.copy(alpha = 0.85f), Offset(0f, ty), Offset(size.width, ty), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))

            // A Google-előzmény és a saját mérések határa
            data.boundary?.let { b ->
                val bx = x(b)
                drawLine(labelColor.copy(alpha = 0.5f), Offset(bx, topPad - 4.dp.toPx()), Offset(bx, topPad + chartH), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }

            fun pathOf(list: List<PricePoint>) = Path().apply {
                list.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.time), y(p.price)) else lineTo(x(p.time), y(p.price)) }
            }
            clipRect(right = size.width * draw.value) {
                if (data.market.size >= 2 || (data.market.isNotEmpty() && data.own.isNotEmpty())) {
                    // A Google-görbe a saját első méréshez csatlakozik, hogy ne legyen rés
                    val m = data.market + listOfNotNull(data.own.firstOrNull())
                    drawPath(pathOf(m), marketColor.copy(alpha = 0.7f), style = Stroke(width = 1.75.dp.toPx()))
                }
                if (data.own.size >= 2) {
                    val p = pathOf(data.own)
                    drawPath(p, ownColor.copy(alpha = 0.18f), style = Stroke(width = 7.dp.toPx()))
                    drawPath(p, ownColor, style = Stroke(width = 2.5.dp.toPx()))
                } else if (data.own.size == 1) {
                    drawCircle(ownColor, 3.5.dp.toPx(), Offset(x(data.own[0].time), y(data.own[0].price)))
                }
            }

            // Feliratok: kis, lekerekített háttérlapkán, hogy a vonalak ne fussanak át a szövegen.
            // A már elhelyezett lapkákat megjegyezzük, hogy ne kerüljenek egymásra.
            val placed = mutableListOf<Rect>()
            val chipPadH = 5.dp.toPx()
            val chipPadV = 2.dp.toPx()
            val gap = 5.dp.toPx()

            // A legmagasabb és a legalacsonyabb pont, az árukkal
            val maxPt = pts.maxBy { it.price }
            val minPt = pts.minBy { it.price }
            for ((pt, isMax) in listOf(maxPt to true, minPt to false)) {
                if (!isMax && pt === maxPt) continue
                val c = Offset(x(pt.time), y(pt.price))
                val col = if (data.own.any { it === pt }) ownColor else marketColor
                drawCircle(chipBg, 5.dp.toPx(), c)
                drawCircle(col, 3.5.dp.toPx(), c)
                val layout = measurer.measure(formatPrice(pt.price, currency), strong)
                val cw = layout.size.width + chipPadH * 2
                val h = layout.size.height + chipPadV * 2
                val left = (c.x - cw / 2f).coerceIn(0f, maxOf(0f, size.width - cw))
                val top = (if (isMax) c.y - gap - h else c.y + gap).coerceIn(0f, maxOf(0f, plotBottom - h))
                val r = Rect(left, top, left + cw, top + h)
                chip(layout, r, chipBg, chipPadH, chipPadV)
                placed += r
            }

            // Célár felirata a szaggatott vonal jobb végén: fölötte, ha ott a görbe a vonal alatt fut, különben alatta
            run {
                val layout = measurer.measure(
                    tr("célár ${formatPrice(data.target, currency)}", "target ${formatPrice(data.target, currency)}", "Zielpreis ${formatPrice(data.target, currency)}"),
                    targetStyle,
                )
                val cw = layout.size.width + chipPadH * 2
                val h = layout.size.height + chipPadV * 2
                val left = maxOf(0f, size.width - cw)
                val lastY = y(pts.last().price)
                val preferAbove = lastY > ty   // a görbe jobb vége a célár alatt (nagyobb y) van
                fun rectFor(above: Boolean): Rect {
                    val top = (if (above) ty - gap - h else ty + gap).coerceIn(0f, maxOf(0f, plotBottom - h))
                    return Rect(left, top, left + cw, top + h)
                }
                val first = rectFor(preferAbove)
                val second = rectFor(!preferAbove)
                val r = when {
                    placed.none { it.overlaps(first) } -> first
                    placed.none { it.overlaps(second) } -> second
                    else -> {
                        // Mindkét oldal foglalt a jobb szélen: a bal szélre tesszük
                        val l = rectFor(preferAbove)
                        Rect(0f, l.top, cw, l.bottom)
                    }
                }
                chip(layout, r, chipBg, chipPadH, chipPadV)
                placed += r
            }

            // Dátumok alul: az eleje, a határ és a vége – egymást nem fedhetik
            val dateY = plotBottom + 4.dp.toPx()
            fun dayText(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().format(chartDay)
            val dateGap = 8.dp.toPx()
            val dates = mutableListOf<Pair<Float, Float>>()
            fun placeDate(text: String, preferredLeft: Float) {
                val layout = measurer.measure(text, small)
                val wd = layout.size.width.toFloat()
                val left = preferredLeft.coerceIn(0f, maxOf(0f, size.width - wd))
                val right = left + wd
                if (dates.any { (l, r) -> left < r + dateGap && right > l - dateGap }) return
                drawText(layout, topLeft = Offset(left, dateY))
                dates += left to right
            }
            val firstText = dayText(t0)
            val lastText = dayText(pts.last().time)
            placeDate(firstText, 0f)
            if (lastText != firstText) placeDate(lastText, Float.MAX_VALUE)
            // A határ dátuma csak akkor, ha elfér a két szélső között
            data.boundary?.let { b ->
                val text = dayText(b)
                if (text != firstText && text != lastText) {
                    val wd = measurer.measure(text, small).size.width
                    placeDate(text, x(b) - wd / 2f)
                }
            }
        }
        // Jelmagyarázat
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (data.market.isNotEmpty()) LegendItem(marketColor, tr("Google árelőzmény", "Google price history", "Google-Preisverlauf"))
            LegendItem(ownColor, tr("REFI mérései", "REFI checks", "REFI-Prüfungen"))
        }
        w.market?.takeIf { !w.wantsBags }?.let { m ->
            if (m.typicalLow != null && m.typicalHigh != null) {
                Text(
                    tr(
                        "A Google szerint ezen az úton a szokásos ár: ${formatPrice(m.typicalLow, currency)} – ${formatPrice(m.typicalHigh, currency)}",
                        "Google says the usual price for this trip is ${formatPrice(m.typicalLow, currency)} – ${formatPrice(m.typicalHigh, currency)}",
                        "Laut Google liegt der übliche Preis für diese Reise bei ${formatPrice(m.typicalLow, currency)} – ${formatPrice(m.typicalHigh, currency)}",
                    ),
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

/** Szöveg kis, lekerekített háttérlapkán: a mögötte futó vonalak nem zavarják az olvasást. */
private fun DrawScope.chip(layout: TextLayoutResult, r: Rect, bg: Color, padH: Float, padV: Float) {
    drawRoundRect(bg, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(r.height / 2f))
    drawText(layout, topLeft = Offset(r.left + padH, r.top + padV))
}
