package hu.repjegy.figyelo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val syncTime = DateTimeFormatter.ofPattern("MMM d. HH:mm", HU)

/** Beállítások: szinkronizálás a Google-fiókkal (telefon ↔ Windows). */
@Composable
internal fun SyncSection() {
    val s by Sync.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "A figyeléseid a Google-fiókodba mentődnek (egy rejtett, csak a REFI által látható helyre), " +
                "és minden eszközödön, ahol bejelentkezel, ugyanazok lesznek.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!s.enabled) {
            Button(onClick = { AppScope.scope.launch { Sync.enable() } }, enabled = !s.running) {
                Text(if (s.running) "Bejelentkezés…" else "Bejelentkezés Google-fiókkal")
            }
        } else {
            Text(
                "Bejelentkezve: ${s.account ?: "Google-fiók"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Neon.Green,
            )
            Text(
                when {
                    s.running -> "Szinkronizálás…"
                    s.lastSync != null -> "Utoljára: " + Instant.ofEpochMilli(s.lastSync!!).atZone(ZoneId.systemDefault()).format(syncTime)
                    else -> "Még nem volt szinkronizálás."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AppScope.scope.launch { Sync.syncNow() } }, enabled = !s.running) {
                    Text("Szinkronizálás most")
                }
                TextButton(onClick = { Sync.disable() }) { Text("Kikapcsolás") }
            }
        }
        s.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (s.enabled) {
                TextButton(onClick = { AppScope.scope.launch { Sync.enable() } }) { Text("Újra bejelentkezés") }
            }
        }
    }
}
