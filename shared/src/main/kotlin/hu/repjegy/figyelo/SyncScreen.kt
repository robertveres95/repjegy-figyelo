package hu.repjegy.figyelo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
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

private val syncTime get() = DateTimeFormatter.ofPattern(
    when (Lang.code) {
        Lang.HU_CODE -> "MMM d. HH:mm"
        Lang.DE_CODE -> "d. MMM HH:mm"
        else -> "d MMM HH:mm"
    },
    Lang.locale,
)

/** Beállítások: szinkronizálás a Google-fiókkal (telefon ↔ Windows). */
@Composable
internal fun SyncSection() {
    val s by Sync.state.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(tr("Kijelentkezel?", "Sign out?", "Abmelden?")) },
            text = { Text(tr("A REFI használatához be kell jelentkezni, így kijelentkezés után rögtön a bejelentkezés jön. A figyeléseid megmaradnak.", "You need to be signed in to use REFI, so after signing out the sign-in screen comes up right away. Your watches stay safe.", "Für REFI musst du angemeldet sein, deshalb erscheint nach dem Abmelden sofort die Anmeldung. Deine Beobachtungen bleiben erhalten.")) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; Sync.disable() }) { Text(tr("Kijelentkezés", "Sign out", "Abmelden")) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(tr("Mégse", "Cancel", "Abbrechen")) } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr(
                "A figyeléseid a Google-fiókodba mentődnek (egy rejtett, csak a REFI által látható helyre), " +
                    "és minden eszközödön, ahol bejelentkezel, ugyanazok lesznek.",
                "Your watches are saved to your Google account (in a hidden place only REFI can see), " +
                    "and they’ll be the same on every device where you sign in.",
                "Deine Beobachtungen werden in deinem Google-Konto gespeichert (an einem versteckten Ort, den nur REFI sieht), " +
                    "und sie sind auf jedem Gerät, auf dem du dich anmeldest, dieselben.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!s.enabled) {
            Button(onClick = { AppScope.scope.launch { Sync.enable() } }, enabled = !s.busy) {
                Text(if (s.busy) tr("Bejelentkezés…", "Signing in…", "Anmeldung…") else tr("Bejelentkezés Google-fiókkal", "Sign in with Google", "Mit Google anmelden"))
            }
            if (s.busy) {
                TextButton(onClick = { Platform.current.googleCancelSignIn() }) { Text(tr("Mégse", "Cancel", "Abbrechen")) }
            }
        } else {
            Text(
                tr("Bejelentkezve: ${s.account ?: "Google-fiók"}", "Signed in: ${s.account ?: "Google account"}", "Angemeldet: ${s.account ?: "Google-Konto"}"),
                style = MaterialTheme.typography.bodyMedium,
                color = Neon.Green,
            )
            Text(
                when {
                    s.running -> tr("Szinkronizálás…", "Syncing…", "Synchronisierung…")
                    s.lastSync != null -> tr("Utoljára: ", "Last sync: ", "Zuletzt: ") + Instant.ofEpochMilli(s.lastSync!!).atZone(ZoneId.systemDefault()).format(syncTime)
                    else -> tr("Még nem volt szinkronizálás.", "No sync yet.", "Noch keine Synchronisierung.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AppScope.scope.launch { Sync.syncNow() } }, enabled = !s.busy) {
                    Text(tr("Szinkronizálás most", "Sync now", "Jetzt synchronisieren"))
                }
                TextButton(onClick = { confirmLogout = true }) { Text(tr("Kijelentkezés", "Sign out", "Abmelden")) }
            }
        }
        s.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (s.enabled) {
                TextButton(onClick = { AppScope.scope.launch { Sync.enable() } }, enabled = !s.busy) { Text(tr("Újra bejelentkezés", "Sign in again", "Erneut anmelden")) }
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        NeonCard(modifier = androidx.compose.ui.Modifier.fillMaxWidth().enterAnimation()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "REFI",
                    style = MaterialTheme.typography.headlineMedium.glow(),
                    color = Neon.Green,
                )
                Text(tr("Jelentkezz be a Google-fiókoddal", "Sign in with your Google account", "Melde dich mit deinem Google-Konto an"), style = MaterialTheme.typography.titleLarge)
                Text(
                    tr(
                        "A figyeléseidet a Google-fiókodban tároljuk, így minden eszközödön – telefonon és " +
                            "számítógépen – ugyanazok lesznek, és telefoncserénél sem vesznek el.",
                        "We store your watches in your Google account, so they’re the same on all your devices – phone and " +
                            "computer – and you won’t lose them when you change phones.",
                        "Wir speichern deine Beobachtungen in deinem Google-Konto, so sind sie auf allen deinen Geräten – Handy und " +
                            "Computer – dieselben, und bei einem Handywechsel gehen sie nicht verloren.",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    tr(
                        "A REFI csak a saját, rejtett adatterületét látja a Google Drive-odon; a többi fájlodhoz, " +
                            "leveleidhez nem fér hozzá.",
                        "REFI only sees its own hidden data area in your Google Drive; it can’t access your other files " +
                            "or emails.",
                        "REFI sieht nur seinen eigenen, versteckten Datenbereich in deinem Google Drive; auf deine anderen Dateien " +
                            "und E-Mails hat es keinen Zugriff.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { AppScope.scope.launch { Sync.enable() } },
                    enabled = !s.busy,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    Text(if (s.busy) tr("Bejelentkezés folyamatban…", "Signing in…", "Anmeldung läuft…") else tr("Bejelentkezés Google-fiókkal", "Sign in with Google", "Mit Google anmelden"))
                }
                if (s.busy) {
                    Text(
                        tr("Válaszd ki a fiókodat a megnyíló Google-ablakban.", "Choose your account in the Google window that opens.", "Wähle dein Konto im Google-Fenster, das sich öffnet."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { Platform.current.googleCancelSignIn() }) { Text(tr("Mégse", "Cancel", "Abbrechen")) }
                }
                s.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (!s.busy) {
                        // Ha nem megy (nincs internet, nincs Google Play, nem nyílik böngésző), ne zárja ki
                        // a figyeléseiből: erre az indításra tovább lehet lépni, legközelebb újra kéri
                        Text(
                            tr("Ha most nem sikerül (pl. nincs internet), később a Beállításokban is bejelentkezhetsz.", "If it doesn’t work now (e.g. no internet), you can sign in later in Settings.", "Wenn es jetzt nicht klappt (z. B. kein Internet), kannst du dich später in den Einstellungen anmelden."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onSkip, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                            Text(tr("Folytatás most bejelentkezés nélkül", "Continue without signing in for now", "Vorerst ohne Anmeldung weiter"))
                        }
                    }
                }
                TextButton(onClick = { Platform.current.openUrl("https://github.com/robertveres95/repjegy-figyelo/blob/main/PRIVACY.md") }) {
                    Text(tr("Adatkezelési tájékoztató", "Privacy policy", "Datenschutzerklärung"))
                }
            }
        }
    }
}
