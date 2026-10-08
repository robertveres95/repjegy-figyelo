package hu.repjegy.figyelo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Egyszerűsített nyitóanimáció Windowsra (3D-motor nélkül, ugyanazzal a forgatókönyvvel):
 * fekete háttér, „REFI” felirat és verziószám, a kis fehér repülő kört tesz, a szárnyvégekről
 * halvány csíkot húz, majd a néző feje fölött elhúz; utána felerősödik a főképernyő.
 */
@Composable
fun DesktopSplash(onFinished: () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (done) 0f else 1f,
        animationSpec = tween(350),
        label = "splash",
        finishedListener = { if (done) onFinished() },
    )
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < 3.2f) {
            withFrameNanos { now -> t = (now - start) / 1_000_000_000f }
            if (t >= 2.8f && !done) done = true
        }
    }
    val parts = remember { planeParts() }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (t < 2.92f) {
                drawTrails(t)
                val pose = pose(t, size.width, size.height)
                drawPlane(parts, pose)
            }
        }
        val textAlpha = when {
            t < 0.25f -> t / 0.25f
            t < 0.75f -> 1f
            else -> max(0f, 1f - (t - 0.75f) / 0.3f)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(top = 160.dp)
                .graphicsLayer { this.alpha = textAlpha },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "REFI",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 6.sp,
                fontFamily = AppFont,
                textAlign = TextAlign.Center,
            )
            Text(
                Updater.currentVersion,
                color = Color(0xFF9AA3AD),
                fontSize = 14.sp,
                fontFamily = AppFont,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private class Pose(val pos: Offset, val headingDeg: Float, val sx: Float, val sy: Float)

private fun ease(x: Float): Float = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

/** A repülő helyzete a t. másodpercben: kör a távolban, aztán közeledés és elhúzás a néző fölött. */
private fun position(t: Float, w: Float, h: Float): Triple<Offset, Float, Float> {
    val base = min(w, h) * 0.16f / 100f
    val u = ease(((t - 0.7f) / 2.2f).coerceIn(0f, 1f))
    val cx = w / 2f
    val loopEnd = 0.66f
    val rx = w * 0.24f
    val ry = h * 0.12f
    val center = Offset(cx - rx * 0.15f, h * 0.36f)
    fun loopPoint(theta: Float): Offset {
        val a = theta + PI.toFloat() * 0.35f
        return Offset(center.x + rx * cos(a), center.y - ry * sin(a))
    }
    return if (u <= loopEnd) {
        // Teljes kör az óramutatóval ellentétesen; fent (messzebb) kisebb, lent (közelebb) nagyobb
        val theta = 2f * PI.toFloat() * (u / loopEnd)
        val p = loopPoint(theta)
        val depth = 1f + 0.35f * (p.y - center.y) / ry
        Triple(p, base * depth, 0.82f)
    } else {
        val v = (u - loopEnd) / (1f - loopEnd)
        val start = loopPoint(2f * PI.toFloat())
        val startScale = base * (1f + 0.35f * (start.y - center.y) / ry)
        val target = Offset(cx, -h * 0.9f)
        val k = v.pow(1.6f)
        val p = Offset(start.x + (target.x - start.x) * k, start.y + (target.y - start.y) * k)
        val scale = startScale * 40f.pow(v * v)
        Triple(p, scale, 0.82f + 0.18f * v)
    }
}

private fun pose(t: Float, w: Float, h: Float): Pose {
    val (p, s, bank) = position(t, w, h)
    val (q, _, _) = position(t + 0.012f, w, h)
    val dx = q.x - p.x
    val dy = q.y - p.y
    val heading = if (dx * dx + dy * dy < 1e-4f) 0f else atan2(dx, -dy) * 180f / PI.toFloat()
    return Pose(p, heading, s * bank, s)
}

/** Szárnyvég (repülő-koordinátában, középponthoz képest) képernyőpontra vetítve. */
private fun tip(pose: Pose, dx: Float, dy: Float): Offset {
    val r = pose.headingDeg * PI.toFloat() / 180f
    val x = dx * pose.sx
    val y = dy * pose.sy
    return Offset(pose.pos.x + x * cos(r) - y * sin(r), pose.pos.y + x * sin(r) + y * cos(r))
}

private fun DrawScope.drawTrails(t: Float) {
    val from = max(0.75f, t - 1.0f)
    var prev: Pair<Offset, Offset>? = null
    var ts = from
    while (ts <= t) {
        val pz = pose(ts, size.width, size.height)
        val cur = tip(pz, -45f, 13f) to tip(pz, 45f, 13f)
        prev?.let { (l0, r0) ->
            val age = (t - ts) / 1.0f
            val a = (0.22f * (1f - age)).coerceIn(0f, 1f)
            val width = (1.2f * pz.sy * 2.2f).coerceIn(0.8f, 6f)
            drawLine(Color.White.copy(alpha = a), l0, cur.first, strokeWidth = width, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = a), r0, cur.second, strokeWidth = width, cap = StrokeCap.Round)
        }
        prev = cur
        ts += 1f / 90f
    }
}

private fun DrawScope.drawPlane(parts: List<Pair<Path, Color>>, pose: Pose) {
    withTransform({
        translate(pose.pos.x, pose.pos.y)
        rotate(pose.headingDeg, Offset.Zero)
        scale(pose.sx, pose.sy, Offset.Zero)
        translate(-50f, -50f)
    }) {
        parts.forEach { (path, color) -> drawPath(path, color) }
    }
}

/** Ugyanaz a fehér utasszállító, mint az app ikonján (100×100-as rajz, orra felfelé). */
private fun planeParts(): List<Pair<Path, Color>> {
    fun p(d: String) = PathParser().parsePathString(d).toPath()
    val wing = Color(0xFFE9EDF1)
    val engine = Color(0xFFC9D0D8)
    return listOf(
        p("M46,40 L6,60 L5,64 L9,65 L46,55 Z") to wing,
        p("M54,40 L94,60 L95,64 L91,65 L54,55 Z") to wing,
        p("M25,50 a3,3 0 0 1 6,0 v5 a3,3 0 0 1 -6,0 Z") to engine,
        p("M69,50 a3,3 0 0 1 6,0 v5 a3,3 0 0 1 -6,0 Z") to engine,
        p("M47,81 L30,90 L30,93 L47,89 Z") to wing,
        p("M53,81 L70,90 L70,93 L53,89 Z") to wing,
        p("M50,3 C55,3 56,12 56,22 L56,80 C56,88 53,96 50,98 C47,96 44,88 44,80 L44,22 C44,12 45,3 50,3 Z") to Color.White,
        p("M47.2,10 Q50,7.5 52.8,10 L52.4,12 Q50,10.4 47.6,12 Z") to Color(0xFF2C3540),
    )
}
