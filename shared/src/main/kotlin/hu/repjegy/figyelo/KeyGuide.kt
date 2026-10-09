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
            "Mi ez, és mire jó?",
            "A SerpApi egy szolgáltatás, ami a Google Flights találatait megbízhatóan, „hivatalos úton” adja át " +
                "a REFI-nek. A kulcs nélküli Google-keresés néha akadozik vagy hibát jelez – a SerpApi-val ilyenkor " +
                "is lesz ár, és több járatot is lát.\n\n" +
                "Havonta 250 keresés ingyenes, bankkártyát nem kér, így véletlenül sem kerülhet pénzbe.",
        ),
        GuideStep(
            "Regisztráció – 1 perc",
            "Nyomd meg a gombot: megnyílik a SerpApi oldala (angol nyelvű, de nem kell sokat olvasni).\n\n" +
                "Válaszd a „Sign up with Google” gombot (= regisztráció Google-fiókkal). Így nem kell jelszót " +
                "kitalálnod és megjegyezned: két kattintás, és kész.",
            "Regisztráció megnyitása", "https://serpapi.com/users/sign_up",
        ),
        GuideStep(
            "A kulcs kimásolása",
            "Ha bejelentkeztél, nyisd meg a kulcs oldalát a gombbal. Ott a „Your Private API Key” felirat alatt " +
                "van egy hosszú betű-szám sor – ez a kulcsod.\n\n" +
                "Jelöld ki és másold ki (vagy nyomd meg mellette a másolás ikont), aztán gyere vissza ide.",
            "Kulcs oldalának megnyitása", "https://serpapi.com/manage-api-key",
        ),
    )
    KeyProvider.IGNAV -> listOf(
        GuideStep(
            "Mi ez, és mire jó?",
            "Az Ignav egy repülőjegy-kereső szolgáltatás saját adatforrással – vagyis olyan ajánlatokat is " +
                "találhat, amiket a többi forrás nem lát. Minél több helyen keres a REFI, annál nagyobb az esély " +
                "egy olcsóbb jegyre.\n\n" +
                "Az első 1000 keresés ingyenes (ez egyszeri keret, nem havi). Bankkártyát nem kér, így ha elfogy, " +
                "egyszerűen leáll – magától nem kerül pénzbe.",
        ),
        GuideStep(
            "Regisztráció – 2 perc",
            "Nyomd meg a gombot: megnyílik az Ignav regisztrációs oldala. Itt nincs Google-gomb: add meg az " +
                "e-mail-címed és egy új jelszót, majd „Create account”.\n\n" +
                "Ezután kapsz egy levelet – kattints benne a megerősítő linkre (ha nem látod, nézd meg a " +
                "Spam / Promóciók mappát is).",
            "Regisztráció megnyitása", "https://ignav.com/signup",
        ),
        GuideStep(
            "A kulcs kimásolása",
            "Jelentkezz be, és nyisd meg a kezelőfelületet (Dashboard) a gombbal. Ott találod az „API key” " +
                "feliratú hosszú betű-szám sort – ez a kulcsod.\n\n" +
                "Másold ki, aztán gyere vissza ide.",
            "Kezelőfelület megnyitása", "https://ignav.com/dashboard",
        ),
    )
}

/** A kulcs kipróbálása. Siker esetén egy rövid, barátságos üzenet, hiba esetén kivétel érthető szöveggel. */
internal suspend fun testKey(p: KeyProvider, key: String, currency: String): String = withContext(Dispatchers.IO) {
    when (p) {
        KeyProvider.SERPAPI -> {
            // A fiókadatok lekérése ingyenes, nem fogy tőle a keret
            val res = Http.request(
                "https://serpapi.com/account.json?api_key=" + URLEncoder.encode(key, "UTF-8"),
                headers = mapOf("Accept" to "application/json"), timeoutMs = 20_000,
            )
            val json = runCatching { JSONObject(res.body) }.getOrNull()
            if (res.code == 401 || json?.has("error") == true || res.code !in 200..299) {
                throw IOException("Ez a kulcs nem jó – ellenőrizd, hogy az egészet kimásoltad-e (szóköz nélkül).")
            }
            val left = json?.optInt("total_searches_left", -1)?.takeIf { it >= 0 }
                ?: json?.optInt("plan_searches_left", -1)?.takeIf { it >= 0 }
            "Működik! " + (left?.let { "Ebben a hónapban még $it ingyenes keresésed van." } ?: "")
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
                throw IOException("Ez a kulcs nem jó – ellenőrizd, hogy az egészet kimásoltad-e (szóköz nélkül).")
            } catch (e: Exception) {
                throw IOException("Most nem sikerült kipróbálni (${e.message?.take(80)}). Nézd meg az internetet, és próbáld újra.")
            }
            "Működik! Az Ignav mostantól a többi forrással együtt keres."
        }
    }
}

/**
 * Lépésről lépésre: mi ez, regisztráció (gombbal az oldalra), kulcs kimásolása, beillesztés és kipróbálás.
 * Sikeres próba után a kulcsot elmenti és a forrást bekapcsolja ([onSaved] a beállítás-képernyőnek is szól).
 */
@Composable
fun KeyGuideDialog(provider: KeyProvider, onClose: () -> Unit, onSaved: (String) -> Unit = {}) {
    val steps = remember(provider) { stepsFor(provider) }
    val total = steps.size + 1
    var step by remember { mutableStateOf(0) }
    var key by remember { mutableStateOf("") }
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
                    "${provider.title} beállítása · ${minOf(step + 1, total)}/$total",
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
                    Text("Illeszd be a kulcsot", style = MaterialTheme.typography.titleLarge, color = Neon.Green, fontWeight = FontWeight.Bold)
                    Text(
                        "Ha kimásoltad, nyomd meg a „Beillesztés” gombot, majd a „Kipróbálom és mentem” gombot. " +
                            "A REFI megnézi, hogy működik-e, és ha igen, rögtön be is kapcsolja.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.trim().take(200); error = null },
                        label = { Text("${provider.title} API-kulcs") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { key = it.take(200); error = null }
                                ?: run { error = "A vágólap üres – előbb másold ki a kulcsot." }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Beillesztés a vágólapról") }
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
                            Text("Kipróbálás…")
                        } else {
                            Text("Kipróbálom és mentem")
                        }
                    }
                    Text(
                        "A kulcs csak ezen a ${Platform.current.deviceWord} tárolódik. A másik eszközödön ugyanezt a " +
                            "kulcsot kell beírnod (nem kell újra regisztrálni).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Kész! 🎉", style = MaterialTheme.typography.titleLarge, color = Neon.Mint, fontWeight = FontWeight.Bold)
                    Text(success!!, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A következő ellenőrzéstől a ${provider.title} is keres neked. Nincs más teendőd.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (success == null && step > 0) {
                        TextButton(onClick = { step--; error = null }, enabled = !busy) { Text("Vissza") }
                    }
                    Spacer(Modifier.weight(1f))
                    when {
                        success != null -> Button(onClick = onClose) { Text("Bezárás") }
                        step < steps.size -> {
                            TextButton(onClick = onClose) { Text("Később") }
                            Button(onClick = { step++ }) { Text(if (step == steps.size - 1) "Megvan a kulcs" else "Tovább") }
                        }
                        else -> TextButton(onClick = onClose, enabled = !busy) { Text("Mégse") }
                    }
                }
            }
        }
    }
}
