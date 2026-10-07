package hu.repjegy.figyelo

import androidx.compose.animation.animateColorAsState
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

// ---------------------------------------------------------------- Színek

object Neon {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF060B07)
    val SurfaceHigh = Color(0xFF0C140E)
    val Green = Color(0xFF39FF14)
    val GreenSoft = Color(0xFF8CFF6B)
    val Mint = Color(0xFF00FFA3)
    val Text = Color(0xFFDCFFD2)
    val TextDim = Color(0xFF7DBF73)
    val Line = Color(0xFF1B5E20)
    val Pink = Color(0xFFFF2E88)
    val Amber = Color(0xFFFFD23F)
}

private val NeonColors = darkColorScheme(
    primary = Neon.Green,
    onPrimary = Neon.Black,
    primaryContainer = Color(0xFF0E3B12),
    onPrimaryContainer = Neon.GreenSoft,
    secondary = Neon.Mint,
    onSecondary = Neon.Black,
    secondaryContainer = Color(0xFF06291C),
    onSecondaryContainer = Neon.Mint,
    tertiary = Neon.Amber,
    onTertiary = Neon.Black,
    background = Neon.Black,
    onBackground = Neon.Text,
    surface = Neon.Black,
    onSurface = Neon.Text,
    surfaceVariant = Neon.SurfaceHigh,
    onSurfaceVariant = Neon.TextDim,
    surfaceContainer = Neon.Surface,
    surfaceContainerLow = Neon.Surface,
    surfaceContainerLowest = Neon.Black,
    surfaceContainerHigh = Neon.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF122016),
    outline = Neon.Line,
    outlineVariant = Color(0xFF123816),
    error = Neon.Pink,
    onError = Neon.Black,
    inverseSurface = Neon.Green,
    inverseOnSurface = Neon.Black,
)

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
fun NeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NeonColors, typography = NeonTypography, content = content)
}

/** Izzó szövegstílus (fényudvar a betűk körül). */
fun TextStyle.glow(color: Color = Neon.Green, radius: Float = 18f): TextStyle =
    copy(shadow = Shadow(color = color.copy(alpha = 0.85f), offset = Offset.Zero, blurRadius = radius))

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
    val glowStrength = if (pulse) pulseAlpha else 0.45f
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .drawBehind {
                // Hamis fényudvar: egyre halványabb, vastagabb keretek
                val r = 18.dp.toPx()
                for (i in 1..4) {
                    val spread = i * 3.dp.toPx()
                    drawRoundRect(
                        color = color.copy(alpha = 0.10f * glowStrength / i),
                        topLeft = Offset(-spread / 2, -spread / 2),
                        size = Size(size.width + spread, size.height + spread),
                        cornerRadius = CornerRadius(r + spread / 2),
                        style = Stroke(width = spread),
                    )
                }
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Neon.SurfaceHigh, Neon.Black)))
            .border(1.2.dp, color.copy(alpha = 0.55f + 0.45f * glowStrength), shape)
            .drawWithContent {
                drawContent()
                if (scanning) {
                    // Fentről lefelé pásztázó fénycsík
                    val y = size.height * scan
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, color.copy(alpha = 0.22f), Color.Transparent),
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
