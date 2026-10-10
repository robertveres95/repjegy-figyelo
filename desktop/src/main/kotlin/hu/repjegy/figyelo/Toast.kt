package hu.repjegy.figyelo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Saját értesítőablak a képernyő jobb alsó sarkában, gombokkal (a Windows tálcabuborékja nem tud
 * gombot): „Megnyitás”, „Foglalás” (a legjobb ajánlat oldala) és „Megosztás” (a családnak szóló üzenet
 * a vágólapra). 25 másodperc után magától eltűnik; nem veszi el a fókuszt attól, amin épp dolgozol.
 */
object ToastCenter {
    data class Toast(val title: String, val text: String, val url: String?, val share: String?, val id: Long = System.nanoTime())

    val current = MutableStateFlow<Toast?>(null)

    fun show(title: String, text: String, url: String?, share: String?) {
        current.value = Toast(title, text, url?.takeIf { isSafeWebUrl(it) }, share)
    }
}

@Composable
fun ToastWindow(onOpenApp: () -> Unit) {
    val toast by ToastCenter.current.collectAsState()
    val t = toast ?: return
    val bounds = remember {
        runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    }
    val size = DpSize(380.dp, 190.dp)
    // A munkaterület (tálca nélküli rész) jobb alsó sarka; a bounds képpontban van, a nagyítással osztjuk
    val scale = remember { runCatching { java.awt.Toolkit.getDefaultToolkit().screenResolution / 96f }.getOrDefault(1f).coerceAtLeast(1f) }
    val pos = if (bounds != null) WindowPosition(
        ((bounds.x + bounds.width) / scale).dp - size.width - 16.dp,
        ((bounds.y + bounds.height) / scale).dp - size.height - 16.dp,
    ) else WindowPosition(Alignment.BottomEnd)
    val state = rememberWindowState(size = size, position = pos)
    var copied by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(t.id) {
        delay(25_000)
        if (ToastCenter.current.value?.id == t.id) ToastCenter.current.value = null
    }
    Window(
        onCloseRequest = { ToastCenter.current.value = null },
        state = state,
        title = "REFI",
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        NeonTheme(mode = Store.settings.value.themeMode) {
            Column(
                Modifier.fillMaxSize()
                    .background(Neon.Surface)
                    .border(1.dp, Neon.Green.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✈ " + t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = Neon.Green, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ToastCenter.current.value = null }) { Text("✕", color = Neon.TextDim) }
                }
                Text(t.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onOpenApp(); ToastCenter.current.value = null }) { Text(tr("Megnyitás", "Open")) }
                    t.url?.let { u -> OutlinedButton(onClick = { DesktopPlatform.openUrl(u) }) { Text(tr("Foglalás", "Book")) } }
                    t.share?.let { s ->
                        OutlinedButton(onClick = { copied = DesktopPlatform.shareText(s) }) {
                            Text(if (copied) tr("Másolva ✓", "Copied ✓") else tr("Megosztás", "Share"))
                        }
                    }
                }
            }
        }
    }
}
