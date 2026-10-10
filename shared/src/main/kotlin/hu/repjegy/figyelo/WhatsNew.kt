package hu.repjegy.figyelo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
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
    /** A felület nyelvén (a magyar a mérvadó; angolul a [englishNotes] táblából, ha van fordítás). */
    val notes: List<Pair<String, List<String>>>
        get() = if (Lang.en) hungarian.map { (v, lines) -> v to (englishNotes[v] ?: lines) } else hungarian

    private val hungarian: List<Pair<String, List<String>>> = listOf(
        "1.4.0" to listOf(
            "A REFI már angolul is beszél (Beállítások → Nyelv).",
            "„Minden héten” rugalmasság: pl. péntek–vasárnapot megadva bármelyik hétvégét figyeli, és a legolcsóbbat mutatja.",
            "„Bárhová, olcsón” riasztás a Felfedezésben: szólunk, ha bármelyik úti célra a megadott ár alá megy a jegy.",
            "„Vegyem most vagy várjak?” már az első ellenőrzés után, és árelőrejelzés az eddigi árak alapján.",
            "A kártyán: mennyit nyersz a figyeléssel, a távoli repterek transzferköltsége és a fejenkénti teljes költség.",
            "Egy koppintással a naptárba teheted a járatot.",
            "Közös figyelés: az újra elküldött figyelés frissül, nem lesz belőle kettő; az üzenetben az aktuális ár és a foglalási link is benne van.",
            "Windowson az értesítés gombokkal jön: Megnyitás, Foglalás, Megosztás. A Chrome-bővítmény ikonján szám mutatja a célár alatti figyeléseket.",
        ),
        "1.3.3" to listOf(
            "Windowson a frissítés egy kattintás: a REFI maga tölti le és telepíti az új verziót.",
            "Beállítható, hogy a REFI elinduljon-e a Windows-zal.",
            "Még megbízhatóbb szinkronizálás, ha több eszközön is használod – a kulcsok és a pénznem is.",
            "Kijelentkezéskor a többi eszközöd bejelentkezve marad; a telefonon a következő belépéskor fiókot is választhatsz.",
            "A beállításokból kilépve rákérdezünk, ha nem mentetted a változásokat.",
            "A tippeket egyenként vagy mindet kikapcsolhatod (és a Beállításokban vissza is kapcsolhatod).",
            "Pontosabb Wizz Air- és Ignav-keresés, jobban olvasható felület, és sok apró javítás.",
        ),
        "1.3.2" to listOf(
            "Új, szemkímélőbb kék színvilág.",
            "Részletesebb árgörbe: dátumokkal, a legmagasabb és legalacsonyabb árral, poggyász nélküli utaknál a Google 2 hetes árelőzményével is.",
            "Felfedezés: most már egy évre előre is kereshetsz olcsó úti célt.",
            "Megbízhatóbb szinkronizálás a telefon és a számítógép között – a törölt figyelés nem jön vissza.",
            "Kevesebb fölösleges értesítés: ugyanarról az árról nem szólunk kétszer.",
            "Figyelés törlése és kijelentkezés előtt az app rákérdez.",
            "Ha a bejelentkezés nem sikerül (pl. nincs internet), akkor is tovább tudsz lépni.",
            "Tippek és trükkök a figyelés készítésekor – és egy varázsló, ami végigvezet az ingyenes kulcsok beállításán. A kulcsok a többi eszközödre is magától átkerülnek.",
            "Sok apró hibajavítás a háttérben.",
        ),
    )

    private fun english(version: String, lines: List<String>) = version to lines

    /** Az újdonságok angolul, verziónként. */
    private val englishNotes: Map<String, List<String>> = mapOf(
        english("1.4.0", listOf(
            "REFI now speaks English too (Settings → Language).",
            "“Every week” flexibility: e.g. set Friday–Sunday and REFI watches every weekend and shows you the cheapest.",
            "“Anywhere, cheap” alert in Discover: we tell you when a trip to any destination drops below your price.",
            "“Buy now or wait?” right after the first check, plus a price forecast based on the prices so far.",
            "On the card: how much you save by watching, the transfer cost at remote airports and the total cost per person.",
            "Add the flight to your calendar with one tap.",
            "Shared watches: a watch sent again is updated instead of duplicated; the message includes the current price and the booking link.",
            "On Windows, notifications come with buttons: Open, Book, Share. The Chrome extension icon shows how many watches are below target.",
        )),
        english("1.3.3", listOf(
            "On Windows, updating is one click: REFI downloads and installs the new version itself.",
            "You can choose whether REFI starts with Windows.",
            "Even more reliable sync if you use several devices – keys and currency included.",
            "When you sign out, your other devices stay signed in; on the phone you can also pick an account the next time you sign in.",
            "When leaving Settings, we ask if you haven’t saved your changes.",
            "You can turn tips off one by one or all at once (and turn them back on in Settings).",
            "More accurate Wizz Air and Ignav searches, a more readable look, and lots of small fixes.",
        )),
        english("1.3.2", listOf(
            "A new, easier-on-the-eyes blue colour theme.",
            "More detailed price chart: with dates, the highest and lowest price, and for trips without baggage Google’s 2-week price history too.",
            "Discover: you can now search for cheap destinations up to a year ahead.",
            "More reliable sync between phone and computer – a deleted watch won’t come back.",
            "Fewer unnecessary notifications: we don’t tell you about the same price twice.",
            "The app asks before deleting a watch and before signing out.",
            "If signing in fails (e.g. no internet), you can still continue.",
            "Tips and tricks when creating a watch – and a wizard that guides you through setting up the free keys. The keys also move to your other devices automatically.",
            "Lots of small fixes behind the scenes.",
        )),
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        NeonCard(modifier = Modifier.fillMaxWidth().enterAnimation()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("ÚJDONSÁGOK", "WHAT’S NEW"), style = MaterialTheme.typography.headlineMedium.glow(), color = Neon.Green)
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
                    Text(tr("Rendben", "OK"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
