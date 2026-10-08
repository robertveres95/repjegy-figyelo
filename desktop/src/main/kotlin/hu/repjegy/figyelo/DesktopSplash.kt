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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Windowsos nyitóanimáció: ugyanaz a fehér 3D-s utasszállító, mint a telefonon, előre
 * képkockákra rögzítve (resources/splash3d, 30 kép/mp). A gép alulról, közelről érkezik,
 * óriásként végigsöpör az ablakon, majd felfelé, a távolba húz el halvány szárnyvég-csíkokkal.
 */
private const val FPS = 30f
private const val PLANE_START = 0.9f
private const val PLANE_END = 2.85f

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
    // A képkockák kibontása (~59 nagy kép) a háttérben fut, hogy az ablak azonnal megjelenjen
    var frames by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    LaunchedEffect(Unit) {
        frames = withContext(Dispatchers.Default) { runCatching { loadFrames() }.getOrDefault(emptyList()) }
    }
    // A kibontott képek sok memóriát foglalnak: a nyitókép után azonnal felszabadítjuk
    DisposableEffect(Unit) {
        onDispose {
            frames?.forEach { runCatching { it.asSkiaBitmap().close() } }
        }
    }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (t < 3.2f) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                // Ha a repülő jönne, de a képkockák még töltődnek, megvárjuk őket
                if (t < PLANE_START || frames != null) t += dt
            }
            if (t >= 2.8f && !done) done = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black),
    ) {
        val textAlpha = when {
            t < 0.25f -> t / 0.25f
            t < 0.9f -> 1f
            else -> max(0f, 1f - (t - 0.9f) / 0.3f)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(top = 40.dp)
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

        // A repülő a szöveg fölött halad el
        Canvas(Modifier.fillMaxSize()) {
            val list = frames
            if (list.isNullOrEmpty() || t < PLANE_START || t >= PLANE_END) return@Canvas
            val index = ((t - PLANE_START) * FPS).toInt().coerceIn(0, list.size - 1)
            val img = list[index]
            // Kitöltés középre igazítva (mint a „crop”): bármilyen ablakméretnél teljes képernyős
            val scale = max(size.width / img.width, size.height / img.height)
            val w = (img.width * scale).roundToInt()
            val h = (img.height * scale).roundToInt()
            drawImage(
                image = img,
                dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                dstSize = IntSize(w, h),
            )
        }
    }
}

private fun loadFrames(): List<ImageBitmap> {
    val cl = DesktopPlatform::class.java
    val list = mutableListOf<ImageBitmap>()
    var i = 0
    while (true) {
        val bytes = cl.getResourceAsStream("/splash3d/f%03d.webp".format(i))?.use { it.readBytes() } ?: break
        val img = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() ?: break
        list += img
        i++
    }
    return list
}
