package hu.repjegy.figyelo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Kijelentkezel?") },
            text = { Text("A REFI használatához be kell jelentkezni, így kijelentkezés után rögtön a bejelentkezés jön. A figyeléseid megmaradnak.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; Sync.disable() }) { Text("Kijelentkezés") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Mégse") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "A figyeléseid a Google-fiókodba mentődnek (egy rejtett, csak a REFI által látható helyre), " +
                "és minden eszközödön, ahol bejelentkezel, ugyanazok lesznek.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!s.enabled) {
            Button(onClick = { AppScope.scope.launch { Sync.enable() } }, enabled = !s.busy) {
                Text(if (s.busy) "Bejelentkezés…" else "Bejelentkezés Google-fiókkal")
            }
            if (s.busy) {
                TextButton(onClick = { Platform.current.googleCancelSignIn() }) { Text("Mégse") }
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
                OutlinedButton(onClick = { AppScope.scope.launch { Sync.syncNow() } }, enabled = !s.busy) {
                    Text("Szinkronizálás most")
                }
                TextButton(onClick = { confirmLogout = true }) { Text("Kijelentkezés") }
            }
        }
        s.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (s.enabled) {
                TextButton(onClick = { AppScope.scope.launch { Sync.enable() } }, enabled = !s.busy) { Text("Újra bejelentkezés") }
            }
        }
    }
}

/**
 * Kötelező bejelentkezés a nyitóanimáció után: a figyelések a Google-fiókban tárolódnak
 * (szinkronizálás, és nem vesznek el telefoncserénél). Amíg nincs bejelentkezés, ez takarja az appot.
 */
@Composable
internal fun LoginGate(onSkip: () -> Unit = {}) {
    val s by Sync.state.collectAsState()
    CenteredScroll(
        androidx.compose.ui.Modifier
            .fillMaxSize()
            .blockInput()
            .background(Neon.Black)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        NeonCard(modifier = androidx.compose.ui.Modifier.fillMaxWidth().enterAnimation()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "REFI",
                    style = MaterialTheme.typography.headlineMedium.glow(),
                    color = Neon.Green,
                )
                Text("Jelentkezz be a Google-fiókoddal", style = MaterialTheme.typography.titleLarge)
                Text(
                    "A figyeléseidet a Google-fiókodban tároljuk, így minden eszközödön – telefonon és " +
                        "számítógépen – ugyanazok lesznek, és telefoncserénél sem vesznek el.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "A REFI csak a saját, rejtett adatterületét látja a Google Drive-odon; a többi fájlodhoz, " +
                        "leveleidhez nem fér hozzá.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { AppScope.scope.launch { Sync.enable() } },
                    enabled = !s.busy,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    Text(if (s.busy) "Bejelentkezés folyamatban…" else "Bejelentkezés Google-fiókkal")
                }
                if (s.busy) {
                    Text(
                        "Válaszd ki a fiókodat a megnyíló Google-ablakban.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { Platform.current.googleCancelSignIn() }) { Text("Mégse") }
                }
                s.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (!s.busy) {
                        // Ha nem megy (nincs internet, nincs Google Play, nem nyílik böngésző), ne zárja ki
                        // a figyeléseiből: erre az indításra tovább lehet lépni, legközelebb újra kéri
                        Text(
                            "Ha most nem sikerül (pl. nincs internet), később a Beállításokban is bejelentkezhetsz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onSkip, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                            Text("Folytatás most bejelentkezés nélkül")
                        }
                    }
                }
                TextButton(onClick = { Platform.current.openUrl("https://github.com/robertveres95/repjegy-figyelo/blob/main/PRIVACY.md") }) {
                    Text("Adatkezelési tájékoztató")
                }
            }
        }
    }
}
