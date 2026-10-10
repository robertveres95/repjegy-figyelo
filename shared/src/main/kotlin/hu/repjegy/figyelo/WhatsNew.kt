package hu.repjegy.figyelo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
    /**
     * A felület nyelvén (a magyar a mérvadó; angolul az [englishNotes], németül a [germanNotes]
     * táblából, ha van fordítás – németül ennek hiányában angolul, végül magyarul).
     */
    val notes: List<Pair<String, List<String>>>
        get() = when (Lang.code) {
            Lang.HU_CODE -> hungarian
            Lang.DE_CODE -> hungarian.map { (v, lines) -> v to (germanNotes[v] ?: englishNotes[v] ?: lines) }
            else -> hungarian.map { (v, lines) -> v to (englishNotes[v] ?: lines) }
        }

    private val hungarian: List<Pair<String, List<String>>> = listOf(
        "1.4.0" to listOf(
            "A REFI már angolul és németül is beszél (Beállítások → Nyelv) – a városnevek is a választott nyelven.",
            "„Minden héten” rugalmasság: pl. péntek–vasárnapot megadva bármelyik hétvégét figyeli, és a legolcsóbbat mutatja.",
            "Havi árnaptár a szerkesztőben: egy pillantással látod, melyik napon a legolcsóbb indulni.",
            "„Bárhová, olcsón” riasztás a Felfedezésben (a Wizz Air ajánlataival is): szólunk, ha bármelyik úti célra a megadott ár alá megy a jegy. A riasztások minden eszközödre átkerülnek.",
            "„Vegyem most vagy várjak?” már az első ellenőrzés után, és árelőrejelzés az eddigi árak alapján.",
            "A kártyán: mennyit nyersz a figyeléssel, a távoli repterek transzferköltsége, a fejenkénti teljes költség, és egy koppintással a naptárba teheted a járatot.",
            "Közös figyelés: az újra elküldött figyelés frissül, nem lesz belőle kettő; az üzenetben az aktuális ár és a foglalási link is benne van.",
            "Az értesítéseken gombok: Foglalás, Megosztás, Megnyitás (telefonon és Windowson is). A Chrome-bővítmény ikonján szám mutatja a célár alatti figyeléseket.",
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
            "REFI now speaks English and German too (Settings → Language) – city names follow the chosen language as well.",
            "“Every week” flexibility: e.g. set Friday–Sunday and REFI watches every weekend and shows you the cheapest.",
            "Monthly price calendar in the editor: see at a glance which day is cheapest to fly.",
            "“Anywhere, cheap” alerts in Discover (now with Wizz Air deals too): we tell you when a trip to any destination drops below your price. Alerts sync to all your devices.",
            "“Buy now or wait?” right after the first check, plus a price forecast based on the prices so far.",
            "On the card: how much you save by watching, the transfer cost at remote airports, the total cost per person, and add the flight to your calendar with one tap.",
            "Shared watches: a watch sent again is updated instead of duplicated; the message includes the current price and the booking link.",
            "Notifications now have buttons: Book, Share, Open (on your phone and on Windows). The Chrome extension icon shows how many watches are below target.",
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

    private fun german(version: String, lines: List<String>) = version to lines

    /** Az újdonságok németül, verziónként. */
    private val germanNotes: Map<String, List<String>> = mapOf(
        german("1.4.0", listOf(
            "REFI spricht jetzt auch Englisch und Deutsch (Einstellungen → Sprache) – auch die Städtenamen in der gewählten Sprache.",
            "„Jede Woche“-Flexibilität: z. B. Freitag–Sonntag wählen, und REFI beobachtet jedes Wochenende und zeigt das günstigste.",
            "Monatlicher Preiskalender im Editor: Auf einen Blick siehst du, an welchem Tag der Flug am günstigsten ist.",
            "„Überall günstig“-Alarm unter Entdecken (jetzt auch mit Wizz-Air-Angeboten): Wir melden uns, wenn ein Flug zu irgendeinem Ziel unter deinen Preis fällt. Die Alarme werden auf alle deine Geräte übertragen.",
            "„Jetzt buchen oder warten?“ schon nach der ersten Prüfung, dazu eine Preisprognose aus den bisherigen Preisen.",
            "Auf der Karte: wie viel du durchs Beobachten sparst, die Transferkosten an abgelegenen Flughäfen, die Gesamtkosten pro Person – und mit einem Tippen kommt der Flug in deinen Kalender.",
            "Geteilte Beobachtungen: Eine erneut gesendete Beobachtung wird aktualisiert statt verdoppelt; die Nachricht enthält den aktuellen Preis und den Buchungslink.",
            "Benachrichtigungen mit Knöpfen: Buchen, Teilen, Öffnen (auf dem Handy und unter Windows). Das Symbol der Chrome-Erweiterung zeigt, wie viele Beobachtungen unter dem Zielpreis liegen.",
        )),
        german("1.3.3", listOf(
            "Unter Windows ist das Update ein Klick: REFI lädt die neue Version selbst herunter und installiert sie.",
            "Du kannst einstellen, ob REFI mit Windows startet.",
            "Noch zuverlässigere Synchronisierung, wenn du mehrere Geräte nutzt – auch Schlüssel und Währung.",
            "Beim Abmelden bleiben deine anderen Geräte angemeldet; auf dem Handy kannst du bei der nächsten Anmeldung auch ein Konto wählen.",
            "Wenn du die Einstellungen verlässt, fragen wir nach, falls du deine Änderungen nicht gespeichert hast.",
            "Du kannst die Tipps einzeln oder alle auf einmal ausschalten (und in den Einstellungen wieder einschalten).",
            "Genauere Wizz-Air- und Ignav-Suche, eine besser lesbare Oberfläche und viele kleine Korrekturen.",
        )),
        german("1.3.2", listOf(
            "Ein neues, augenschonenderes blaues Farbschema.",
            "Detailliertere Preiskurve: mit Datum, höchstem und niedrigstem Preis, und bei Reisen ohne Gepäck auch mit Googles Preisverlauf der letzten 2 Wochen.",
            "Entdecken: Du kannst jetzt bis zu ein Jahr im Voraus nach günstigen Zielen suchen.",
            "Zuverlässigere Synchronisierung zwischen Handy und Computer – eine gelöschte Beobachtung kommt nicht zurück.",
            "Weniger unnötige Benachrichtigungen: Über denselben Preis sagen wir dir nicht zweimal Bescheid.",
            "Die App fragt nach, bevor sie eine Beobachtung löscht und bevor du dich abmeldest.",
            "Wenn die Anmeldung fehlschlägt (z. B. kein Internet), kannst du trotzdem weitermachen.",
            "Tipps und Tricks beim Anlegen einer Beobachtung – und ein Assistent, der dich durch die Einrichtung der kostenlosen Schlüssel führt. Die Schlüssel landen auch automatisch auf deinen anderen Geräten.",
            "Viele kleine Fehlerbehebungen im Hintergrund.",
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
                Text(tr("ÚJDONSÁGOK", "WHAT’S NEW", "NEUIGKEITEN"), style = MaterialTheme.typography.headlineMedium.glow(), color = Neon.Green)
                items.forEach { (version, lines) ->
                    Text("REFI $version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    lines.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Kis kiemelőszínű pötty, az első sor közepéhez igazítva
                            Box(Modifier.padding(top = 9.dp).size(6.dp).background(Neon.Green, CircleShape))
                            Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(tr("Rendben", "OK", "OK"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
