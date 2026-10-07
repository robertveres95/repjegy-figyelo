package hu.repjegy.figyelo

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- Színek és témák

/** Egy téma színei. A név („Neon.*”) történeti: minden témában ezeket használja a felület. */
data class AppPalette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val accent: Color,
    val accentSoft: Color,
    val mint: Color,
    val text: Color,
    val textDim: Color,
    val line: Color,
    val error: Color,
    val warn: Color,
    val glow: Boolean,       // izzó fényudvar a szövegek és keretek körül
    val isLight: Boolean,
)

val NightPalette = AppPalette(
    background = Color(0xFF000000),
    surface = Color(0xFF060B07),
    surfaceHigh = Color(0xFF0B120C),
    accent = Color(0xFF39FF14),
    accentSoft = Color(0xFF8CFF6B),
    mint = Color(0xFF00FFA3),
    text = Color(0xFFD5F5CC),
    textDim = Color(0xFF7DAF75),
    line = Color(0xFF1B4D20),
    error = Color(0xFFFF4D8D),
    warn = Color(0xFFFFD23F),
    glow = true,
    isLight = false,
)

val DayPalette = AppPalette(
    background = Color(0xFFF5F8F3),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDF3EA),
    accent = Color(0xFF1E7A2C),
    accentSoft = Color(0xFF2E7D32),
    mint = Color(0xFF00796B),
    text = Color(0xFF14201A),
    textDim = Color(0xFF52665A),
    line = Color(0xFFB4CDB6),
    error = Color(0xFFC2185B),
    warn = Color(0xFFA15C00),
    glow = false,
    isLight = true,
)

/** Meleg, sötét, alacsony kontrasztú, kékfény-szegény téma esti olvasáshoz. */
val EyeCarePalette = AppPalette(
    background = Color(0xFF1A1510),
    surface = Color(0xFF211B15),
    surfaceHigh = Color(0xFF29211A),
    accent = Color(0xFFCFAE72),
    accentSoft = Color(0xFFDCC08E),
    mint = Color(0xFFA9B67C),
    text = Color(0xFFE6D9C2),
    textDim = Color(0xFFA6957B),
    line = Color(0xFF4A3D2D),
    error = Color(0xFFD98270),
    warn = Color(0xFFE0A75E),
    glow = false,
    isLight = false,
)

const val THEME_AUTO = "auto"
const val THEME_DAY = "day"
const val THEME_NIGHT = "night"
const val THEME_EYE = "eye"

val THEMES = listOf(
    THEME_AUTO to "Automatikus (rendszer szerint)",
    THEME_DAY to "Nappali",
    THEME_NIGHT to "Éjszakai (neon)",
    THEME_EYE to "Szemkímélő",
)

fun paletteFor(mode: String, systemDark: Boolean): AppPalette = when (mode) {
    THEME_DAY -> DayPalette
    THEME_NIGHT -> NightPalette
    THEME_EYE -> EyeCarePalette
    else -> if (systemDark) NightPalette else DayPalette
}

/** Az aktuális téma színei. Állapotként tárolva, így témaváltáskor az egész felület újrarajzolódik. */
object Neon {
    var palette by mutableStateOf(NightPalette)

    val Black get() = palette.background
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.surfaceHigh
    val Green get() = palette.accent
    val GreenSoft get() = palette.accentSoft
    val Mint get() = palette.mint
    val Text get() = palette.text
    val TextDim get() = palette.textDim
    val Line get() = palette.line
    val Pink get() = palette.error
    val Amber get() = palette.warn
}

private fun colorSchemeFor(p: AppPalette) = if (p.isLight) {
    lightColorScheme(
        primary = p.accent, onPrimary = Color.White,
        primaryContainer = p.surfaceHigh, onPrimaryContainer = p.accent,
        secondary = p.mint, onSecondary = Color.White,
        tertiary = p.warn, onTertiary = Color.White,
        background = p.background, onBackground = p.text,
        surface = p.background, onSurface = p.text,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim,
        surfaceContainer = p.surface, surfaceContainerLow = p.surface, surfaceContainerLowest = p.surface,
        surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh,
        outline = p.line, outlineVariant = p.line,
        error = p.error, onError = Color.White,
    )
} else {
    darkColorScheme(
        primary = p.accent, onPrimary = p.background,
        primaryContainer = p.surfaceHigh, onPrimaryContainer = p.accentSoft,
        secondary = p.mint, onSecondary = p.background,
        tertiary = p.warn, onTertiary = p.background,
        background = p.background, onBackground = p.text,
        surface = p.background, onSurface = p.text,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim,
        surfaceContainer = p.surface, surfaceContainerLow = p.surface, surfaceContainerLowest = p.background,
        surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh,
        outline = p.line, outlineVariant = p.line,
        error = p.error, onError = p.background,
    )
}

/** Monospace „terminál” betűk a címekhez és árakhoz, normál a folyószöveghez. */
private val NeonTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = FontFamily.Monospace),
        titleSmall = titleSmall.copy(fontFamily = FontFamily.Monospace),
        labelSmall = labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
fun NeonTheme(mode: String, content: @Composable () -> Unit) {
    val palette = paletteFor(mode, isSystemInDarkTheme())
    if (Neon.palette != palette) Neon.palette = palette
    val colors = remember(palette) { colorSchemeFor(palette) }

    // Állapotsor és navigációs sáv ikonjai: világos témában sötétek
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = palette.isLight
                isAppearanceLightNavigationBars = palette.isLight
            }
            window.decorView.setBackgroundColor(palette.background.toArgb())
        }
    }
    MaterialTheme(colorScheme = colors, typography = NeonTypography, content = content)
}

/** Izzó szövegstílus (halvány fényudvar a betűk körül); a nem izzó témákban hatástalan. */
fun TextStyle.glow(color: Color = Neon.Green, radius: Float = 12f): TextStyle =
    if (!Neon.palette.glow) this
    else copy(shadow = Shadow(color = color.copy(alpha = 0.55f), offset = Offset.Zero, blurRadius = radius))

// ---------------------------------------------------------------- Neon keret

/**
 * Fekete kártya neonzöld, izzó kerettel.
 * [pulse]: lüktető fény (pl. ha az ár a célár alatt van);
 * [scanning]: körbefutó fénycsík (ellenőrzés közben).
 */
@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    color: Color = Neon.Green,
    pulse: Boolean = false,
    scanning: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "neon")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "scan",
    )
    val glowOn = Neon.palette.glow
    val glowStrength = if (pulse) pulseAlpha else 0.3f
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .drawBehind {
                // Hamis fényudvar: két halvány, vékony külső keret (csak izzó témában)
                if (!glowOn) return@drawBehind
                val r = 18.dp.toPx()
                for (i in 1..2) {
                    val spread = i * 2.dp.toPx()
                    drawRoundRect(
                        color = color.copy(alpha = 0.07f * glowStrength / i),
                        topLeft = Offset(-spread / 2, -spread / 2),
                        size = Size(size.width + spread, size.height + spread),
                        cornerRadius = CornerRadius(r + spread / 2),
                        style = Stroke(width = spread),
                    )
                }
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Neon.SurfaceHigh, Neon.Black)))
            .border(
                0.7.dp,
                if (pulse) color.copy(alpha = 0.45f + 0.45f * glowStrength) else color.copy(alpha = 0.45f),
                shape,
            )
            .drawWithContent {
                drawContent()
                if (scanning) {
                    // Fentről lefelé pásztázó fénycsík
                    val y = size.height * scan
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, color.copy(alpha = 0.14f), Color.Transparent),
                            startY = y - 60f,
                            endY = y + 60f,
                        ),
                        topLeft = Offset(0f, y - 60f),
                        size = Size(size.width, 120f),
                    )
                }
            },
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Belépő animáció: a kártya alulról, halványan érkezik. */
@Composable
fun Modifier.enterAnimation(delayMs: Int = 0): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        progress.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * 60f
        scaleX = 0.96f + 0.04f * progress.value
        scaleY = 0.96f + 0.04f * progress.value
    }
}

// ---------------------------------------------------------------- Csengő kapcsoló

/** Értesítés be/ki egy koppintással: a csengő megrázkódik, a színe vált. */
@Composable
fun BellToggle(on: Boolean, onToggle: () -> Unit) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(on) {
        shake.snapTo(0f)
        for (angle in listOf(18f, -16f, 12f, -8f, 4f, 0f)) {
            shake.animateTo(angle, tween(55))
        }
    }
    val tint by animateColorAsState(if (on) Neon.Green else Neon.TextDim, tween(300), label = "bellTint")
    val borderColor by animateColorAsState(if (on) Neon.Green else Neon.Line, tween(300), label = "bellBorder")
    val scale by animateFloatAsState(if (on) 1f else 0.92f, spring(dampingRatio = 0.4f), label = "bellScale")
    val shape = RoundedCornerShape(50)

    Row(
        Modifier
            .scale(scale)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(if (on) Neon.Green.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = if (on) "Értesítés bekapcsolva" else "Értesítés kikapcsolva",
                tint = tint,
                modifier = Modifier.size(18.dp).rotate(shake.value),
            )
            if (!on) {
                // Áthúzás, ha ki van kapcsolva
                androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                    drawLine(
                        color = Neon.Pink,
                        start = Offset(2f, 2f),
                        end = Offset(size.width - 2f, size.height - 2f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (on) "BE" else "KI",
            color = tint,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            fontSize = 12.sp,
        )
    }
}
