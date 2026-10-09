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

private val shortDayFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy. MMM d.", HU)

private fun shortDay(iso: String): String =
    runCatching { java.time.LocalDate.parse(iso).format(shortDayFormat) }.getOrDefault(iso)

/** Egy megosztott figyelés átvétele kódból (vagy a teljes üzenetből, amiben a kód van). */
@Composable
internal fun ImportCodeDialog(initial: String, onDismiss: () -> Unit, onImported: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val decoded = remember(text) { ShareCode.decode(text) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Figyelés átvétele") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Illeszd be a kapott üzenetet vagy a „REFI1:” kezdetű kódot.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4000); error = null },
                    label = { Text("Kód") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    decoded != null -> Text(
                        "${decoded.first.routeTitle} · ${shortDay(decoded.first.outboundDate)}" +
                            (decoded.first.returnDate?.let { " – ${shortDay(it)}" } ?: "") +
                            " · célár ${formatPrice(decoded.first.targetPrice, decoded.second)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Neon.Green,
                    )
                    text.isNotBlank() -> Text(
                        "Nem található érvényes REFI-kód a szövegben.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = decoded != null && !busy,
                onClick = click@{
                    val (w, cur) = decoded ?: return@click
                    busy = true
                    AppScope.scope.launch {
                        val r = runCatching { Store.importWatches(listOf(w), cur) }
                        busy = false
                        if (r.isSuccess) {
                            onImported("Új figyelés: ${w.routeTitle}")
                            if (Store.settings.value.isReady) PriceChecker.checkOne(w.id)
                        } else {
                            error = "Nem sikerült: ${r.exceptionOrNull()?.message}"
                        }
                    }
                },
            ) { Text("Hozzáadás") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Mégse") } },
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
    val periods = remember { Discover.periods() }
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
            formError = "Válassz indulási helyet."
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
            mem.lastError.value = r.exceptionOrNull()?.let { "Nem sikerült a keresés: ${it.message?.take(120)}" }
            mem.busy.value = false
        }
    }

    Scaffold(
        containerColor = Neon.Black,
        topBar = { NeonTopBar("FELFEDEZÉS", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Hova repülhetsz a legolcsóbban? Egy találatra koppintva figyelést készíthetsz belőle.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AirportField("Honnan", from) { from = it }
                    ChoiceField("Mikor", periods, period) { period = it }
                    ChoiceField("Út", Discover.TRIP_TYPES, tripType) { tripType = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = maxPrice,
                            onValueChange = { v -> maxPrice = v.filter(Char::isDigit).take(7) },
                            label = { Text("Max. ár / fő (${currencySymbol(currency)})") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Column(Modifier.weight(1f)) {
                            ChoiceField("Felnőtt", (1..6).map { it to "$it fő" }, adults) { adults = it }
                        }
                    }
                    Button(onClick = { startSearch() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (loading) "Keresés…" else "Keresés")
                    }
                    Text(
                        "Jelenleg a Ryanair járataiból keres (az ő árkeresője tud „bárhová” keresni). " +
                            "Az ár egy főre szól, poggyász nélkül.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (formError ?: error)?.let { StatusText(it, true) }
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Neon.Green)
                            Spacer(Modifier.size(8.dp))
                            Text("Keresés…", color = Neon.Green)
                        }
                    }
                    results?.let { list ->
                        SectionTitle(if (list.isEmpty()) "Nincs találat" else "${list.size} CÉLÁLLOMÁS")
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
                                r.city,
                                style = MaterialTheme.typography.titleLarge,
                                color = Neon.Green,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(r.country, "${r.fromCode} → ${r.code}").filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatPrice(r.pricePerPerson, currency), style = MaterialTheme.typography.titleLarge)
                            Text("/ fő", style = MaterialTheme.typography.labelSmall, color = Neon.TextDim)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(Discover.describeDates(r), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
