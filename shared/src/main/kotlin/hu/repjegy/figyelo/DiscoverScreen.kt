@file:OptIn(ExperimentalMaterial3Api::class)

package hu.repjegy.figyelo

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Kód beillesztése

private val shortDayFormat get() = java.time.format.DateTimeFormatter.ofPattern(
    when (Lang.code) {
        Lang.HU_CODE -> "yyyy. MMM d."
        Lang.DE_CODE -> "d. MMM yyyy"
        else -> "d MMM yyyy"
    },
    Lang.locale,
)

private fun shortDay(iso: String): String =
    runCatching { java.time.LocalDate.parse(iso).format(shortDayFormat) }.getOrDefault(iso)

/** Egy megosztott figyelés átvétele kódból (vagy a teljes üzenetből, amiben a kód van). */
@Composable
internal fun ImportCodeDialog(initial: String, onDismiss: () -> Unit, onImported: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val decoded = remember(text) { ShareCode.decode(text) }
    // Ha ugyanezt a figyelést korábban már átvette (vagy épp a sajátja), azt frissítjük
    val watches by Store.watches.collectAsState()
    val existing = decoded?.let { ShareCode.existingFor(it.first, watches) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Figyelés átvétele", "Import a watch", "Beobachtung übernehmen")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Illeszd be a kapott üzenetet vagy a „REFI1:” kezdetű kódot.", "Paste the message you received or the code starting with “REFI1:”.", "Füge die erhaltene Nachricht oder den Code ein, der mit „REFI1:“ beginnt."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4000); error = null },
                    label = { Text(tr("Kód", "Code", "Code")) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    decoded != null -> Text(
                        "${decoded.first.routeTitle} · ${shortDay(decoded.first.outboundDate)}" +
                            (decoded.first.returnDate?.let { " – ${shortDay(it)}" } ?: "") +
                            (decoded.first.weeklyUntil?.let { tr(", minden héten ${shortDay(it)}-ig", ", every week until ${shortDay(it)}", ", jede Woche bis ${shortDay(it)}") } ?: "") +
                            tr(" · célár ${formatPrice(decoded.first.targetPrice, decoded.second)}", " · target price ${formatPrice(decoded.first.targetPrice, decoded.second)}", " · Zielpreis ${formatPrice(decoded.first.targetPrice, decoded.second)}"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Neon.Green,
                    )
                    text.isNotBlank() -> Text(
                        tr("Nem található érvényes REFI-kód a szövegben.", "No valid REFI code found in the text.", "Im Text wurde kein gültiger REFI-Code gefunden."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (existing != null) Text(
                    tr(
                        "Ez a figyelés már megvan nálad – a dátumait és beállításait a kapott kód szerint frissítjük " +
                            "(az értesítés be- vagy kikapcsolása marad, ahogy nálad volt).",
                        "You already have this watch – we’ll update its dates and settings from the code you received " +
                            "(your notification on/off setting stays as it was).",
                        "Diese Beobachtung hast du schon – wir aktualisieren ihre Daten und Einstellungen nach dem erhaltenen Code " +
                            "(ob die Benachrichtigung an oder aus ist, bleibt wie bei dir).",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = decoded != null && !busy,
                onClick = click@{
                    val (w0, cur) = decoded ?: return@click
                    val ex = existing
                    // Frissítésnél a meglévő azonosító és a saját értesítés-beállítás marad (a többi a kapott kód szerint)
                    val w = if (ex != null) w0.copy(id = ex.id, notify = ex.notify, sharedFrom = ex.sharedFrom) else w0
                    busy = true
                    AppScope.scope.launch {
                        val r = runCatching { Store.importWatches(listOf(w), cur) }
                        busy = false
                        if (r.isSuccess) {
                            onImported(if (ex != null) tr("Frissítve: ${w.routeTitle}", "Updated: ${w.routeTitle}", "Aktualisiert: ${w.routeTitle}") else tr("Új figyelés: ${w.routeTitle}", "New watch: ${w.routeTitle}", "Neue Beobachtung: ${w.routeTitle}"))
                            if (Store.settings.value.isReady) PriceChecker.checkOne(w.id)
                        } else {
                            error = tr("Nem sikerült: ${r.exceptionOrNull()?.message}", "That didn’t work: ${r.exceptionOrNull()?.message}", "Das hat nicht geklappt: ${r.exceptionOrNull()?.message}")
                        }
                    }
                },
            ) { Text(if (existing != null) tr("Frissítés", "Update", "Aktualisieren") else tr("Hozzáadás", "Add", "Hinzufügen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Mégse", "Cancel", "Abbrechen")) } },
    )
}

// ---------------------------------------------------------------- Felfedezés

/** „Hova repülhetek olcsón?” – a legolcsóbb célállomások egy reptérről, egy időszakban. */
@Composable
internal fun DiscoverScreen(
    onBack: () -> Unit,
    onPick: (Watch) -> Unit,
    initialResults: List<Discover.Result>? = null,
) {
    val currency = Store.settings.collectAsState().value.currency
    val mem = Discover.Memory
    var from by remember { mutableStateOf<Place?>(Airports.placeFor(mem.fromCodes ?: "BUD", null)) }
    val periods = remember(Lang.code) { Discover.periods() }
    var period by remember { mutableStateOf(mem.period.takeIf { p -> periods.any { it.first == p } } ?: 0) }
    var tripType by remember { mutableStateOf(mem.tripType) }
    var adults by remember { mutableStateOf(mem.adults) }
    var maxPrice by remember { mutableStateOf(mem.maxPrice) }
    val loading by mem.busy.collectAsState()
    val error by mem.lastError.collectAsState()
    var formError by remember { mutableStateOf<String?>(null) }
    val stored by mem.lastResults.collectAsState()
    // A korábbi találatok csak ugyanabban a pénznemben érvényesek
    val results = initialResults ?: stored?.takeIf { mem.currency == currency }

    fun startSearch() {
        val origin = from
        if (origin == null) {
            formError = tr("Válassz indulási helyet.", "Choose where you’re flying from.", "Wähle, von wo du abfliegst.")
            return
        }
        formError = null
        mem.lastError.value = null
        mem.period = period
        mem.tripType = tripType
        mem.adults = adults
        mem.maxPrice = maxPrice
        mem.fromCodes = origin.codes
        // Egy korábbi, még futó keresés eredménye ne írja felül az újat
        mem.job?.cancel()
        mem.busy.value = true
        mem.job = AppScope.scope.launch {
            val r = runCatching {
                Discover.search(origin.codes, period, tripType, maxPrice.toIntOrNull()?.takeIf { it > 0 }, currency)
            }
            // Közben újabb keresés indult (ez a lekérés nem szakítható meg, csak az eredményét dobjuk el)
            if (!isActive) return@launch
            r.onSuccess {
                mem.currency = currency
                mem.results = it
                mem.lastResults.value = it
            }
            // Hibánál a korábbi találatok maradnak
            mem.lastError.value = r.exceptionOrNull()?.let { tr("Nem sikerült a keresés: ${it.message?.take(120)}", "The search didn’t work: ${it.message?.take(120)}", "Die Suche hat nicht geklappt: ${it.message?.take(120)}") }
            mem.busy.value = false
        }
    }

    Scaffold(
        containerColor = Neon.Black,
        topBar = { NeonTopBar(tr("FELFEDEZÉS", "DISCOVER", "ENTDECKEN"), onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        tr("Hova repülhetsz a legolcsóbban? Egy találatra koppintva figyelést készíthetsz belőle.", "Where can you fly the cheapest? Tap a result to make a watch from it.", "Wohin kannst du am günstigsten fliegen? Tippe auf ein Ergebnis, um daraus eine Beobachtung zu machen."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AirportField(tr("Honnan", "From", "Von"), from) { from = it }
                    ChoiceField(tr("Mikor", "When", "Wann"), periods, period) { period = it }
                    ChoiceField(tr("Út", "Trip", "Reise"), Discover.TRIP_TYPES, tripType) { tripType = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = maxPrice,
                            onValueChange = { v -> maxPrice = v.filter(Char::isDigit).take(7) },
                            label = { Text(tr("Max. ár / fő (${currencySymbol(currency)})", "Max. price / person (${currencySymbol(currency)})", "Max. Preis / Person (${currencySymbol(currency)})")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Column(Modifier.weight(1f)) {
                            ChoiceField(tr("Felnőtt", "Adults", "Erwachsene"), (1..6).map { it to tr("$it fő", if (it == 1) "1 person" else "$it people", if (it == 1) "1 Person" else "$it Personen") }, adults) { adults = it }
                        }
                    }
                    Button(onClick = { startSearch() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (loading) tr("Keresés…", "Searching…", "Suche läuft…") else tr("Keresés", "Search", "Suchen"))
                    }
                    Text(
                        tr(
                            "Jelenleg a Ryanair járataiból keres (az ő árkeresője tud „bárhová” keresni). " +
                                "Az ár egy főre szól, poggyász nélkül.",
                            "For now it searches Ryanair flights (their fare finder can search “anywhere”). " +
                                "Prices are per person, without baggage.",
                            "Derzeit sucht es unter den Flügen von Ryanair (deren Tarifsuche kann „überallhin“ suchen). " +
                                "Die Preise gelten pro Person, ohne Gepäck.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // „Bárhová, olcsón” riasztás ugyanezekkel a feltételekkel
                    val alerts by DealAlerts.all.collectAsState()
                    var alertMsg by remember { mutableStateOf<String?>(null) }
                    OutlinedButton(
                        onClick = {
                            val origin = from
                            val limit = maxPrice.toIntOrNull()?.takeIf { it > 0 }
                            alertMsg = when {
                                origin == null -> tr("Válassz indulási helyet.", "Choose where you’re flying from.", "Wähle, von wo du abfliegst.")
                                limit == null -> tr("Add meg a „Max. ár / fő” mezőt – ez alatt szólunk.", "Fill in “Max. price / person” – we’ll alert you below that.", "Fülle „Max. Preis / Person“ aus – darunter sagen wir dir Bescheid.")
                                alerts.size >= DealAlerts.MAX -> tr("Legfeljebb ${DealAlerts.MAX} ilyen riasztásod lehet.", "You can have at most ${DealAlerts.MAX} of these alerts.", "Du kannst höchstens ${DealAlerts.MAX} solcher Alarme haben.")
                                else -> {
                                    val month = if (period <= 0) null
                                    else java.time.YearMonth.now().plusMonths((period - 1).toLong()).toString()
                                    val a = DealAlerts.create(origin, month, tripType, limit, currency)
                                    DealAlerts.add(a)
                                    AppScope.scope.launch { DealAlerts.checkOne(a) }
                                    tr("Kész! 12 óránként megnézzük, és szólunk, ha bárhová ${formatPrice(limit, currency)}/fő alá megy.", "Done! We’ll check every 12 hours and let you know if a trip anywhere drops below ${formatPrice(limit, currency)}/person.", "Fertig! Wir prüfen alle 12 Stunden und sagen dir Bescheid, wenn ein Flug irgendwohin unter ${formatPrice(limit, currency)}/Person fällt.")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("🔔 Szólj, ha bárhová ennyi alá megy", "🔔 Alert me if anywhere drops below this", "🔔 Sag Bescheid, wenn es irgendwohin darunter fällt")) }
                    alertMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Neon.Mint) }
                    if (alerts.isNotEmpty()) {
                        SectionTitle(tr("BÁRHOVÁ-RIASZTÁSAID", "YOUR ANYWHERE ALERTS", "DEINE „ÜBERALL GÜNSTIG“-ALARME"))
                        alerts.forEach { a -> DealAlertRow(a, currency) }
                    }
                    (formError ?: error)?.let { StatusText(it, true) }
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Neon.Green)
                            Spacer(Modifier.size(8.dp))
                            Text(tr("Keresés…", "Searching…", "Suche läuft…"), color = Neon.Green)
                        }
                    }
                    results?.let { list ->
                        SectionTitle(if (list.isEmpty()) tr("Nincs találat", "No results", "Keine Ergebnisse") else tr("${list.size} CÉLÁLLOMÁS", if (list.size == 1) "1 DESTINATION" else "${list.size} DESTINATIONS", if (list.size == 1) "1 REISEZIEL" else "${list.size} REISEZIELE"))
                    }
                }
            }
            items(results.orEmpty(), key = { it.code }) { r ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(0.6.dp, Neon.Line, RoundedCornerShape(14.dp))
                        .clickable { onPick(Discover.templateFor(r, adults, Airports.placeFor(r.fromCode, null).city)) }
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.shownCity,
                                style = MaterialTheme.typography.titleLarge,
                                color = Neon.Green,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(r.shownCountry, "${r.fromCode} → ${r.code}").filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatPrice(r.pricePerPerson, currency), style = MaterialTheme.typography.titleLarge)
                            Text(tr("/ fő", "/ person", "/ Person"), style = MaterialTheme.typography.labelSmall, color = Neon.TextDim)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(Discover.describeDates(r), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Egy „bárhová, olcsón” riasztás: feltételek, legutóbbi találatok, törlés. */
@Composable
private fun DealAlertRow(a: DealAlert, currency: String) {
    var confirm by remember(a.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().border(0.6.dp, Neon.Line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            tr("${a.fromName} → bárhová, ${DealAlerts.periodLabel(a)}", "${a.fromName} → anywhere, ${DealAlerts.periodLabel(a)}", "${a.fromName} → überallhin, ${DealAlerts.periodLabel(a)}"),
            style = MaterialTheme.typography.titleSmall, color = Neon.Green,
        )
        Text(
            tr(
                "${Discover.TRIP_TYPES.firstOrNull { it.first == a.tripType }?.second ?: ""} · max. ${formatPrice(a.maxPrice, a.currency)}/fő",
                "${Discover.TRIP_TYPES.firstOrNull { it.first == a.tripType }?.second ?: ""} · max. ${formatPrice(a.maxPrice, a.currency)}/person",
                "${Discover.TRIP_TYPES.firstOrNull { it.first == a.tripType }?.second ?: ""} · max. ${formatPrice(a.maxPrice, a.currency)}/Person",
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (a.latest.isNotEmpty()) {
            a.latest.take(3).forEach { r ->
                Text(tr("✈ ${r.shownCity}: ${formatPrice(r.pricePerPerson, currency)}/fő · ${Discover.describeDates(r)}", "✈ ${r.shownCity}: ${formatPrice(r.pricePerPerson, currency)}/person · ${Discover.describeDates(r)}", "✈ ${r.shownCity}: ${formatPrice(r.pricePerPerson, currency)}/Person · ${Discover.describeDates(r)}"), style = MaterialTheme.typography.bodySmall)
            }
        } else if (a.lastChecked != null && a.lastError == null) {
            Text(tr("Most nincs a határ alatti út – szólunk, ha lesz.", "No trips below your limit right now – we’ll let you know when there are.", "Gerade gibt es keine Flüge unter deinem Limit – wir sagen dir Bescheid, sobald es welche gibt."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        a.lastError?.let { StatusText(tr("Legutóbb nem sikerült: $it", "Last check failed: $it", "Letzte Prüfung fehlgeschlagen: $it"), true) }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { AppScope.scope.launch { DealAlerts.checkOne(a) } }) { Text(tr("Ellenőrzés most", "Check now", "Jetzt prüfen")) }
            TextButton(onClick = { confirm = true }) { Text(tr("Törlés", "Delete", "Löschen"), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Riasztás törlése?", "Delete alert?", "Alarm löschen?")) },
        text = { Text(tr("${a.fromName} → bárhová, ${DealAlerts.periodLabel(a)}", "${a.fromName} → anywhere, ${DealAlerts.periodLabel(a)}", "${a.fromName} → überallhin, ${DealAlerts.periodLabel(a)}")) },
        confirmButton = { TextButton(onClick = { DealAlerts.remove(a.id); confirm = false }) { Text(tr("Törlés", "Delete", "Löschen")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Mégse", "Cancel", "Abbrechen")) } },
    )
}
