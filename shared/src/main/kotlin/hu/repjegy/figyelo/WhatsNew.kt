package hu.repjegy.figyelo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * „Újdonságok”: új verzió első megnyitásakor röviden, közérthetően leírjuk, mi változott.
 * Kiadáskor ide kerül az új verzió pár pontja (a legújabb legyen elöl).
 */
object WhatsNew {
    val notes: List<Pair<String, List<String>>> = listOf(
        "1.3.2" to listOf(
            "Új, szemkímélőbb kék színvilág.",
            "Részletesebb árgörbe: dátumokkal, a legmagasabb és legalacsonyabb árral, és a Google 2 hetes árelőzményével.",
            "Felfedezés: most már egy évre előre is kereshetsz olcsó úti célt.",
            "Megbízhatóbb szinkronizálás a telefon és a számítógép között – a törölt figyelés nem jön vissza.",
            "Kevesebb fölösleges értesítés: ugyanarról az árról nem szólunk kétszer.",
            "Figyelés törlése és kijelentkezés előtt az app rákérdez.",
            "Ha a bejelentkezés nem sikerül (pl. nincs internet), akkor is tovább tudsz lépni.",
            "Sok apró hibajavítás a háttérben.",
        ),
    )

    private const val KEY = "seenVersion"

    private fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)

    internal fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until 3) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }

    /**
     * A még nem látott verziók újdonságai (a mostani verzióig). Friss telepítésnél (nincs még
     * figyelés, nem volt bejelentkezve) nincs mit mutatni: csak megjegyezzük a verziót.
     */
    fun pending(current: String = Updater.currentVersion): List<Pair<String, List<String>>> {
        val seen = Store.prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
        if (seen == null) {
            val fresh = Store.watches.value.isEmpty() && !Store.prefs.getBoolean("syncOn", false)
            if (fresh) {
                markSeen(current)
                return emptyList()
            }
        }
        return notes.filter { (v, _) ->
            compare(v, current) <= 0 && (seen == null || compare(v, seen) > 0)
        }.take(3)
    }

    fun markSeen(current: String = Updater.currentVersion) {
        Store.prefs.edit { putString(KEY, current) }
    }
}

/** Az újdonságok ablaka; a „Rendben” után ennél a verziónál többet nem jelenik meg. */
@Composable
internal fun WhatsNewOverlay(items: List<Pair<String, List<String>>>, onClose: () -> Unit) {
    Platform.current.BackHandler(enabled = true) { onClose() }
    CenteredScroll(
        Modifier
            .fillMaxSize()
            .blockInput()
            .background(Neon.Black.copy(alpha = 0.96f))
            .padding(24.dp),
    ) {
        NeonCard(modifier = Modifier.fillMaxWidth().enterAnimation()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ÚJDONSÁGOK", style = MaterialTheme.typography.headlineMedium.glow(), color = Neon.Green)
                items.forEach { (version, lines) ->
                    Text("REFI $version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    lines.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("✈", color = Neon.Green, style = MaterialTheme.typography.bodyLarge)
                            Text(line, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("Rendben", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
