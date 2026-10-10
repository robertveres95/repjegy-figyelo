package hu.repjegy.figyelo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate

/** A kulcsos árforrások, amelyekhez lépésről lépésre segítünk kulcsot szerezni. */
enum class KeyProvider(val title: String) { SERPAPI("SerpApi"), IGNAV("Ignav") }

/** Egy lépés a varázslóban: cím, magyarázat, és (ha van) egy gomb, ami megnyit egy oldalt. */
private class GuideStep(val title: String, val body: String, val button: String? = null, val url: String? = null)

private fun stepsFor(p: KeyProvider): List<GuideStep> = when (p) {
    KeyProvider.SERPAPI -> listOf(
        GuideStep(
            tr("Mi ez, és mire jó?", "What is it, and why use it?", "Was ist das, und wozu ist es gut?"),
            tr(
                "A SerpApi egy szolgáltatás, ami a Google Flights találatait megbízhatóan, „hivatalos úton” adja át " +
                    "a REFI-nek. A kulcs nélküli Google-keresés néha akadozik vagy hibát jelez – a SerpApi-val ilyenkor " +
                    "is lesz ár, és több járatot is lát.\n\n" +
                    "Havonta 250 keresés ingyenes, bankkártyát nem kér, így véletlenül sem kerülhet pénzbe.",
                "SerpApi is a service that passes Google Flights results to REFI reliably, the “official way”. " +
                    "The Google search without a key sometimes stalls or shows an error – with SerpApi you still get " +
                    "a price, and it sees more flights too.\n\n" +
                    "250 searches a month are free, and it doesn’t ask for a bank card, so it can never cost you money by accident.",
                "SerpApi ist ein Dienst, der REFI die Ergebnisse von Google Flights zuverlässig und „offiziell“ liefert. " +
                    "Die Google-Suche ohne Schlüssel hakt manchmal oder zeigt einen Fehler – mit SerpApi gibt es auch dann " +
                    "einen Preis, und SerpApi findet außerdem mehr Flüge.\n\n" +
                    "250 Suchen pro Monat sind kostenlos, und es wird keine Bankkarte verlangt – es kann dich also nie versehentlich Geld kosten.",
            ),
        ),
        GuideStep(
            tr("Regisztráció – 1 perc", "Sign up – 1 minute", "Registrierung – 1 Minute"),
            tr(
                "Nyomd meg a gombot: megnyílik a SerpApi oldala (angol nyelvű, de nem kell sokat olvasni).\n\n" +
                    "Válaszd a „Sign up with Google” gombot (= regisztráció Google-fiókkal). Így nem kell jelszót " +
                    "kitalálnod és megjegyezned: két kattintás, és kész.",
                "Tap the button: the SerpApi website opens.\n\n" +
                    "Choose “Sign up with Google”. That way you don’t need to make up and remember a password: " +
                    "two clicks and you’re done.",
                "Tippe auf den Knopf: Die SerpApi-Website öffnet sich (auf Englisch, aber du musst nicht viel lesen).\n\n" +
                    "Wähle „Sign up with Google“ (= mit Google-Konto registrieren). So musst du dir kein Passwort " +
                    "ausdenken und merken: zwei Klicks, und fertig.",
            ),
            tr("Regisztráció megnyitása", "Open sign-up page", "Registrierung öffnen"), "https://serpapi.com/users/sign_up",
        ),
        GuideStep(
            tr("A kulcs kimásolása", "Copy the key", "Den Schlüssel kopieren"),
            tr(
                "Ha bejelentkeztél, nyisd meg a kulcs oldalát a gombbal. Ott a „Your Private API Key” felirat alatt " +
                    "van egy hosszú betű-szám sor – ez a kulcsod.\n\n" +
                    "Jelöld ki és másold ki (vagy nyomd meg mellette a másolás ikont), aztán gyere vissza ide.",
                "Once you’re signed in, open the key page with the button. Under “Your Private API Key” " +
                    "there is a long row of letters and numbers – that’s your key.\n\n" +
                    "Select it and copy it (or tap the copy icon next to it), then come back here.",
                "Wenn du angemeldet bist, öffne mit dem Knopf die Schlüssel-Seite. Dort steht unter „Your Private API Key“ " +
                    "eine lange Reihe aus Buchstaben und Zahlen – das ist dein Schlüssel.\n\n" +
                    "Markiere und kopiere ihn (oder tippe auf das Kopier-Symbol daneben) und komm dann hierher zurück.",
            ),
            tr("Kulcs oldalának megnyitása", "Open key page", "Schlüssel-Seite öffnen"), "https://serpapi.com/manage-api-key",
        ),
    )
    KeyProvider.IGNAV -> listOf(
        GuideStep(
            tr("Mi ez, és mire jó?", "What is it, and why use it?", "Was ist das, und wozu ist es gut?"),
            tr(
                "Az Ignav egy repülőjegy-kereső szolgáltatás saját adatforrással – vagyis olyan ajánlatokat is " +
                    "találhat, amiket a többi forrás nem lát. Minél több helyen keres a REFI, annál nagyobb az esély " +
                    "egy olcsóbb jegyre.\n\n" +
                    "Az első 1000 keresés ingyenes (ez egyszeri keret, nem havi). Bankkártyát nem kér, így ha elfogy, " +
                    "egyszerűen leáll – magától nem kerül pénzbe.",
                "Ignav is a flight search service with its own data source – so it can find deals " +
                    "the other sources don’t see. The more places REFI searches, the better the chance " +
                    "of a cheaper ticket.\n\n" +
                    "The first 1000 searches are free (a one-off allowance, not monthly). It doesn’t ask for a bank card, " +
                    "so when it runs out it simply stops – it never costs you money on its own.",
                "Ignav ist ein Flugsuchdienst mit eigener Datenquelle – er kann also Angebote finden, " +
                    "die die anderen Quellen nicht sehen. Je mehr Stellen REFI durchsucht, desto größer die Chance " +
                    "auf ein günstigeres Ticket.\n\n" +
                    "Die ersten 1000 Suchen sind kostenlos (ein einmaliges Kontingent, nicht monatlich). Es wird keine Bankkarte verlangt – " +
                    "wenn das Kontingent aufgebraucht ist, hört es einfach auf und kostet dich von selbst nie Geld.",
            ),
        ),
        GuideStep(
            tr("Regisztráció – 2 perc", "Sign up – 2 minutes", "Registrierung – 2 Minuten"),
            tr(
                "Nyomd meg a gombot: megnyílik az Ignav regisztrációs oldala. Itt nincs Google-gomb: add meg az " +
                    "e-mail-címed és egy új jelszót, majd „Create account”.\n\n" +
                    "Ezután kapsz egy levelet – kattints benne a megerősítő linkre (ha nem látod, nézd meg a " +
                    "Spam / Promóciók mappát is).",
                "Tap the button: the Ignav sign-up page opens. There’s no Google button here: enter your " +
                    "email address and a new password, then “Create account”.\n\n" +
                    "You’ll then get an email – click the confirmation link in it (if you can’t see it, check " +
                    "your Spam / Promotions folder too).",
                "Tippe auf den Knopf: Die Registrierungsseite von Ignav öffnet sich. Hier gibt es keinen Google-Knopf: Gib deine " +
                    "E-Mail-Adresse und ein neues Passwort ein und dann „Create account“.\n\n" +
                    "Danach bekommst du eine E-Mail – klicke darin auf den Bestätigungslink (wenn du sie nicht siehst, schau auch " +
                    "im Spam- oder Werbe-Ordner nach).",
            ),
            tr("Regisztráció megnyitása", "Open sign-up page", "Registrierung öffnen"), "https://ignav.com/signup",
        ),
        GuideStep(
            tr("A kulcs kimásolása", "Copy the key", "Den Schlüssel kopieren"),
            tr(
                "Jelentkezz be, és nyisd meg a kezelőfelületet (Dashboard) a gombbal. Ott találod az „API key” " +
                    "feliratú hosszú betű-szám sort – ez a kulcsod.\n\n" +
                    "Másold ki, aztán gyere vissza ide.",
                "Sign in and open the Dashboard with the button. There you’ll find a long row of letters and " +
                    "numbers labelled “API key” – that’s your key.\n\n" +
                    "Copy it, then come back here.",
                "Melde dich an und öffne mit dem Knopf das Dashboard. Dort findest du eine lange Reihe aus Buchstaben und " +
                    "Zahlen mit der Bezeichnung „API key“ – das ist dein Schlüssel.\n\n" +
                    "Kopiere ihn und komm dann hierher zurück.",
            ),
            tr("Kezelőfelület megnyitása", "Open Dashboard", "Dashboard öffnen"), "https://ignav.com/dashboard",
        ),
    )
}

/** A kulcs kipróbálása. Siker esetén egy rövid, barátságos üzenet, hiba esetén kivétel érthető szöveggel. */
internal suspend fun testKey(p: KeyProvider, key: String, currency: String): String = withContext(Dispatchers.IO) {
    when (p) {
        KeyProvider.SERPAPI -> {
            // A fiókadatok lekérése ingyenes, nem fogy tőle a keret
            val res = try {
                Http.request(
                    "https://serpapi.com/account.json?api_key=" + URLEncoder.encode(key, "UTF-8"),
                    headers = mapOf("Accept" to "application/json"), timeoutMs = 20_000,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw IOException(tr("Most nem sikerült kipróbálni – nézd meg az internetet, és próbáld újra.", "Couldn’t test it right now – check your internet connection and try again.", "Der Test hat gerade nicht geklappt – prüfe deine Internetverbindung und versuch es noch einmal."))
            }
            val json = runCatching { JSONObject(res.body) }.getOrNull()
            // Csak a valóban elutasított kulcs „rossz”; szerverhiba (5xx, 429) esetén újrapróbálást kérünk
            if (res.code == 401 || res.code == 403 || json?.has("error") == true) {
                throw IOException(tr("Ez a kulcs nem jó – ellenőrizd, hogy az egészet kimásoltad-e (szóköz nélkül).", "This key doesn’t work – check that you copied all of it (without spaces).", "Dieser Schlüssel funktioniert nicht – prüfe, ob du ihn vollständig kopiert hast (ohne Leerzeichen)."))
            }
            if (res.code !in 200..299) {
                throw IOException(tr("A SerpApi most nem válaszol rendesen (${res.code}). Próbáld újra pár perc múlva.", "SerpApi isn’t responding properly right now (${res.code}). Try again in a few minutes.", "SerpApi antwortet gerade nicht richtig (${res.code}). Versuch es in ein paar Minuten noch einmal."))
            }
            val left = json?.optInt("total_searches_left", -1)?.takeIf { it >= 0 }
                ?: json?.optInt("plan_searches_left", -1)?.takeIf { it >= 0 }
            tr("Működik! ", "It works! ", "Es funktioniert! ") + (left?.let { tr("Ebben a hónapban még $it ingyenes keresésed van.", "You have $it free searches left this month.", "Du hast diesen Monat noch $it kostenlose Suchen.") } ?: "")
        }
        KeyProvider.IGNAV -> {
            // Egy valódi, egyszerű keresés (1 kérés fogy az 1000-ből)
            val probe = Watch(
                id = "probe", from = "BUD", to = "STN", outboundDate = LocalDate.now().plusDays(30).toString(),
                returnDate = null, travelClass = 1, adults = 1, children = 0, infantsInSeat = 0, infantsOnLap = 0,
                bags = 0, stops = 0, targetPrice = 1, notify = false,
            )
            try {
                Ignav.search(probe, key, currency)
            } catch (e: FatalSourceException) {
                throw IOException(tr("Ez a kulcs nem jó – ellenőrizd, hogy az egészet kimásoltad-e (szóköz nélkül).", "This key doesn’t work – check that you copied all of it (without spaces).", "Dieser Schlüssel funktioniert nicht – prüfe, ob du ihn vollständig kopiert hast (ohne Leerzeichen)."))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw IOException(tr("Most nem sikerült kipróbálni – nézd meg az internetet, és próbáld újra.", "Couldn’t test it right now – check your internet connection and try again.", "Der Test hat gerade nicht geklappt – prüfe deine Internetverbindung und versuch es noch einmal."))
            }
            tr("Működik! Az Ignav mostantól a többi forrással együtt keres.", "It works! Ignav now searches along with the other sources.", "Es funktioniert! Ignav sucht ab jetzt zusammen mit den anderen Quellen.")
        }
    }
}

/**
 * Lépésről lépésre: mi ez, regisztráció (gombbal az oldalra), kulcs kimásolása, beillesztés és kipróbálás.
 * Sikeres próba után a kulcsot elmenti és a forrást bekapcsolja ([onSaved] a beállítás-képernyőnek is szól).
 */
@Composable
fun KeyGuideDialog(provider: KeyProvider, onClose: () -> Unit, onSaved: (String) -> Unit = {}) {
    val steps = remember(provider, Lang.code) { stepsFor(provider) }
    val total = steps.size + 1
    var step by androidx.compose.runtime.saveable.rememberSaveable(provider) { mutableStateOf(0) }
    var key by androidx.compose.runtime.saveable.rememberSaveable(provider) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    Dialog(onDismissRequest = { if (!busy) onClose() }) {
        NeonCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    tr("${provider.title} beállítása · ${minOf(step + 1, total)}/$total", "${provider.title} setup · ${minOf(step + 1, total)}/$total", "${provider.title} einrichten · ${minOf(step + 1, total)}/$total"),
                    style = MaterialTheme.typography.labelMedium,
                    color = Neon.TextDim,
                )
                if (step < steps.size) {
                    val s = steps[step]
                    Text(s.title, style = MaterialTheme.typography.titleLarge, color = Neon.Green, fontWeight = FontWeight.Bold)
                    Text(s.body, style = MaterialTheme.typography.bodyLarge)
                    if (s.button != null && s.url != null) {
                        Button(onClick = { Platform.current.openUrl(s.url) }, modifier = Modifier.fillMaxWidth()) {
                            Text(s.button)
                        }
                    }
                } else if (success == null) {
                    Text(tr("Illeszd be a kulcsot", "Paste the key", "Füge den Schlüssel ein"), style = MaterialTheme.typography.titleLarge, color = Neon.Green, fontWeight = FontWeight.Bold)
                    Text(
                        tr(
                            "Ha kimásoltad, nyomd meg a „Beillesztés” gombot, majd a „Kipróbálom és mentem” gombot. " +
                                "A REFI megnézi, hogy működik-e, és ha igen, rögtön be is kapcsolja.",
                            "Once you’ve copied it, tap “Paste from clipboard”, then “Test and save”. " +
                                "REFI checks that it works and, if it does, turns it on right away.",
                            "Wenn du ihn kopiert hast, tippe auf „Aus Zwischenablage einfügen“ und dann auf „Testen und speichern“. " +
                                "REFI prüft, ob er funktioniert, und schaltet ihn dann sofort ein.",
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.trim().take(200); error = null },
                        label = { Text(tr("${provider.title} API-kulcs", "${provider.title} API key", "${provider.title}-API-Schlüssel")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { key = it.take(200); error = null }
                                ?: run { error = tr("A vágólap üres – előbb másold ki a kulcsot.", "The clipboard is empty – copy the key first.", "Die Zwischenablage ist leer – kopiere zuerst den Schlüssel.") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("Beillesztés a vágólapról", "Paste from clipboard", "Aus Zwischenablage einfügen")) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Button(
                        enabled = key.length >= 16 && !busy,
                        onClick = {
                            busy = true
                            error = null
                            val k = key
                            AppScope.scope.launch {
                                val r = runCatching { testKey(provider, k, Store.settings.value.currency) }
                                r.onSuccess { msg ->
                                    val cur = Store.settings.value
                                    Store.saveSettings(
                                        when (provider) {
                                            KeyProvider.SERPAPI -> cur.copy(apiKey = k, serpOn = true)
                                            KeyProvider.IGNAV -> cur.copy(ignavKey = k, ignavOn = true)
                                        }
                                    )
                                    onSaved(k)
                                    success = msg
                                }
                                r.onFailure { error = it.message }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(tr("Kipróbálás…", "Testing…", "Wird getestet…"))
                        } else {
                            Text(tr("Kipróbálom és mentem", "Test and save", "Testen und speichern"))
                        }
                    }
                    Text(
                        tr("A kulcs a Google-fiókodon keresztül a többi eszközödre is magától átkerül – ott nem kell újra beírni.", "The key moves to your other devices automatically through your Google account – no need to enter it again there.", "Der Schlüssel landet über dein Google-Konto automatisch auch auf deinen anderen Geräten – dort musst du ihn nicht erneut eingeben."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(tr("Kész! 🎉", "Done! 🎉", "Fertig! 🎉"), style = MaterialTheme.typography.titleLarge, color = Neon.Mint, fontWeight = FontWeight.Bold)
                    Text(success!!, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        tr("A következő ellenőrzéstől a ${provider.title} is keres neked. Nincs más teendőd.", "From the next check, ${provider.title} searches for you too. Nothing else to do.", "Ab der nächsten Prüfung sucht auch ${provider.title} für dich. Sonst musst du nichts tun."),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (success == null && step > 0) {
                        TextButton(onClick = { step--; error = null }, enabled = !busy) { Text(tr("Vissza", "Back", "Zurück")) }
                    }
                    Spacer(Modifier.weight(1f))
                    when {
                        success != null -> Button(onClick = onClose) { Text(tr("Bezárás", "Close", "Schließen")) }
                        step < steps.size -> {
                            TextButton(onClick = onClose) { Text(tr("Később", "Later", "Später")) }
                            Button(onClick = { step++ }) { Text(if (step == steps.size - 1) tr("Megvan a kulcs", "I have the key", "Ich habe den Schlüssel") else tr("Tovább", "Next", "Weiter")) }
                        }
                        else -> TextButton(onClick = onClose, enabled = !busy) { Text(tr("Mégse", "Cancel", "Abbrechen")) }
                    }
                }
            }
        }
    }
}
