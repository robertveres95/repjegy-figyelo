@file:OptIn(ExperimentalMaterial3Api::class)

package hu.repjegy.figyelo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val id: String?) : Screen
    data object Options : Screen
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy. MMM d., EEE", HU)
private val shortDate = DateTimeFormatter.ofPattern("MMM d.", HU)
private val timeFormat = DateTimeFormatter.ofPattern("MMM d. HH:mm", HU)

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            onAdd = { screen = Screen.Edit(null) },
            onEdit = { screen = Screen.Edit(it) },
            onSettings = { screen = Screen.Options },
        )
        is Screen.Edit -> EditScreen(s.id, onDone = { screen = Screen.Home })
        Screen.Options -> SettingsScreen(onDone = { screen = Screen.Home })
    }
}

// ---------------------------------------------------------------- Főképernyő

@Composable
private fun HomeScreen(onAdd: () -> Unit, onEdit: (String) -> Unit, onSettings: () -> Unit) {
    val watches by Store.watches.collectAsState()
    val settings by Store.settings.collectAsState()
    val checking by Store.checking.collectAsState()
    val appContext = LocalContext.current.applicationContext

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Repjegy figyelő") },
                actions = {
                    IconButton(
                        onClick = { App.scope.launch { PriceChecker.checkAll(appContext) } },
                        enabled = checking.isEmpty() && watches.isNotEmpty() && settings.isReady,
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "Összes ellenőrzése") }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Beállítások")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Új figyelés") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!settings.isReady) {
                item { SetupCard(onSettings) }
            }
            if (watches.isEmpty()) {
                item {
                    Text(
                        "Még nincs figyelt út. Az „Új figyelés” gombbal adhatsz hozzá egyet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(watches, key = { it.id }) { w ->
                WatchCard(
                    w = w,
                    currency = settings.currency,
                    isChecking = w.id in checking,
                    canCheck = settings.isReady,
                    onCheck = { App.scope.launch { PriceChecker.checkOne(appContext, w.id) } },
                    onEdit = { onEdit(w.id) },
                    onOpen = { url -> openUrl(appContext, url) },
                )
            }
        }
    }
}

@Composable
private fun SetupCard(onSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nincs bekapcsolt árforrás", style = MaterialTheme.typography.titleMedium)
            Text(
                "Kapcsolj be legalább egy árforrást a Beállításokban (a Google Flights, a Ryanair " +
                    "és a Wizz Air kulcs nélkül működik).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSettings) { Text("Beállítások") }
        }
    }
}

@Composable
private fun WatchCard(
    w: Watch,
    currency: String,
    isChecking: Boolean,
    canCheck: Boolean,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val good = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val best = w.bestOffer
    val belowTarget = best != null && best.price <= w.targetPrice
    var showAll by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.routeTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!w.notify) {
                    Text(
                        "értesítés ki",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(dateLine(w), style = MaterialTheme.typography.bodyMedium)
            Text(
                detailLine(w),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Legolcsóbb most", style = MaterialTheme.typography.labelMedium)
                    Text(
                        best?.let { formatPrice(it.price, currency) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (belowTarget) good else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Célár: ${formatPrice(w.targetPrice, currency)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    w.lowestPrice?.let {
                        Text(
                            "Eddigi min.: ${formatPrice(it, currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (best != null) {
                OfferDetails(best, highlight = true)
            }

            if (w.history.size >= 2) {
                Sparkline(
                    points = w.history,
                    target = w.targetPrice,
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(6.dp))
            when {
                w.isExpired() -> StatusText("Az indulás dátuma elmúlt, a figyelés szünetel.", true)
                w.lastError != null -> StatusText(w.lastError, true)
                w.lastChecked != null -> StatusText(
                    "Utoljára ellenőrizve: " +
                        Instant.ofEpochMilli(w.lastChecked).atZone(ZoneId.systemDefault()).format(timeFormat),
                    false,
                )
                else -> StatusText("Még nem volt ellenőrzés.", false)
            }
            if (w.sourceStatus.isNotEmpty()) {
                Text(
                    w.sourceStatus.joinToString("  ·  ") { s ->
                        "${s.source} ${if (s.ok) "✓" else "✗"} ${s.text}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (w.sourceStatus.any { !it.ok }) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (showAll && w.offers.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text("Összes ajánlat", style = MaterialTheme.typography.titleSmall)
                w.offers.forEachIndexed { index, offer ->
                    Column(Modifier.padding(top = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. ${formatPrice(offer.price, currency)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            offer.url?.let { url ->
                                TextButton(onClick = { onOpen(url) }) { Text("Megnyitás") }
                            }
                        }
                        OfferDetails(offer, highlight = false)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isChecking) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    Text("Keresés…", style = MaterialTheme.typography.bodySmall)
                } else {
                    TextButton(onClick = onCheck, enabled = canCheck && !w.isExpired()) {
                        Text("Ellenőrzés")
                    }
                }
                best?.url?.let { url ->
                    TextButton(onClick = { onOpen(url) }) { Text("Megnyitás") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Szerkesztés") }
            }
            if (w.offers.size > 1) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Kevesebb" else "Mind a ${w.offers.size} ajánlat")
                }
            }
        }
    }
}

/** Egy ajánlat részletei: pontos indulás/érkezés, légitársaság, forrás, megjegyzés. */
@Composable
private fun OfferDetails(offer: Offer, highlight: Boolean) {
    val style = if (highlight) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
    Column(Modifier.padding(top = 4.dp)) {
        val out = offer.outboundText()
        val ret = offer.returnText()
        if (out != null) {
            Text("Indulás: $out", style = style, fontWeight = if (highlight) FontWeight.Medium else null)
        } else {
            Text("Indulási idő: a forrás nem adta meg", style = style)
        }
        if (ret != null) Text("Vissza: $ret", style = style, fontWeight = if (highlight) FontWeight.Medium else null)
        Text(
            listOfNotNull(offer.airline, "forrás: ${offer.source}").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        offer.note?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun StatusText(text: String, isError: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun dateLine(w: Watch): String {
    val out = runCatching { LocalDate.parse(w.outboundDate).format(shortDate) }.getOrDefault(w.outboundDate)
    val ret = w.returnDate?.let { r -> runCatching { LocalDate.parse(r).format(shortDate) }.getOrDefault(r) }
    return if (ret != null) "$out – $ret · oda-vissza" else "$out · csak oda"
}

private fun detailLine(w: Watch): String {
    val parts = mutableListOf<String>()
    parts += "${w.adults} felnőtt"
    if (w.children > 0) parts += "${w.children} gyerek"
    val infants = w.infantsInSeat + w.infantsOnLap
    if (infants > 0) parts += "$infants csecsemő"
    val pax = parts.joinToString(", ")
    val cls = TRAVEL_CLASSES.firstOrNull { it.first == w.travelClass }?.second ?: ""
    val extra = mutableListOf(pax, cls)
    if (w.bags > 0) extra += "${w.bags} kézipoggyász"
    if (w.checkedBag) extra += "feladott poggyász"
    if (w.stops != 0) extra += STOP_OPTIONS.firstOrNull { it.first == w.stops }?.second ?: ""
    return extra.joinToString(" · ")
}

@Composable
private fun Sparkline(points: List<PricePoint>, target: Int, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val prices = points.map { it.price }
        val minP = minOf(prices.min(), target).toFloat()
        val maxP = maxOf(prices.max(), target).toFloat()
        val range = (maxP - minP).takeIf { it > 0f } ?: 1f
        val pad = 4.dp.toPx()
        fun y(p: Float) = pad + (1f - (p - minP) / range) * (size.height - 2 * pad)
        val stepX = size.width / (points.size - 1)

        val ty = y(target.toFloat())
        drawLine(
            color = targetColor,
            start = Offset(0f, ty),
            end = Offset(size.width, ty),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
        )
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = i * stepX
            val py = y(p.price.toFloat())
            if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx()))
    }
}

// ---------------------------------------------------------------- Szerkesztés

@Composable
private fun EditScreen(id: String?, onDone: () -> Unit) {
    val existing = remember(id) { id?.let { i -> Store.watches.value.find { it.id == i } } }
    val currency = Store.settings.collectAsState().value.currency
    val appContext = LocalContext.current.applicationContext
    val today = remember { LocalDate.now() }

    var fromPlace by remember {
        mutableStateOf<Place?>(
            if (existing != null) Airports.placeFor(appContext, existing.from, existing.fromLabel)
            else Airports.placeFor(appContext, "BUD", null)
        )
    }
    var toPlace by remember {
        mutableStateOf<Place?>(existing?.let { Airports.placeFor(appContext, it.to, it.toLabel) })
    }
    var roundTrip by remember { mutableStateOf(existing?.isRoundTrip ?: true) }
    var outDate by remember {
        mutableStateOf(existing?.outboundDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today.plusDays(30))
    }
    var retDate by remember {
        mutableStateOf(existing?.returnDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: outDate.plusDays(7))
    }
    var travelClass by remember { mutableStateOf(existing?.travelClass ?: 1) }
    var adults by remember { mutableStateOf(existing?.adults ?: 1) }
    var children by remember { mutableStateOf(existing?.children ?: 0) }
    var infantsInSeat by remember { mutableStateOf(existing?.infantsInSeat ?: 0) }
    var infantsOnLap by remember { mutableStateOf(existing?.infantsOnLap ?: 0) }
    var bags by remember { mutableStateOf(existing?.bags ?: 0) }
    var stops by remember { mutableStateOf(existing?.stops ?: 0) }
    var target by remember { mutableStateOf(existing?.targetPrice?.toString() ?: "") }
    var notify by remember { mutableStateOf(existing?.notify ?: true) }
    var checkedBag by remember { mutableStateOf(existing?.checkedBag ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    val maxBags = adults + children + infantsInSeat
    if (bags > maxBags) bags = maxBags
    if (infantsOnLap > adults) infantsOnLap = adults

    fun save() {
        val targetValue = target.toIntOrNull()
        val from = fromPlace
        val to = toPlace
        error = when {
            from == null -> "Válaszd ki az indulási repülőteret a listából."
            to == null -> "Válaszd ki az érkezési repülőteret a listából."
            from.codes.split(',').any { it in to.codes.split(',') } ->
                "Az indulási és érkezési hely nem lehet ugyanaz."
            outDate.isBefore(today) -> "Az indulás dátuma nem lehet a múltban."
            roundTrip && retDate.isBefore(outDate) -> "A visszaút nem lehet az indulás előtt."
            adults + children + infantsInSeat + infantsOnLap > 9 -> "Legfeljebb 9 utas adható meg."
            targetValue == null || targetValue <= 0 -> "Adj meg egy célárat."
            else -> null
        }
        if (error != null || targetValue == null || from == null || to == null) return

        val fresh = Watch(
            id = existing?.id ?: UUID.randomUUID().toString(),
            from = from.codes,
            to = to.codes,
            fromLabel = from.city,
            toLabel = to.city,
            outboundDate = outDate.toString(),
            returnDate = if (roundTrip) retDate.toString() else null,
            travelClass = travelClass,
            adults = adults,
            children = children,
            infantsInSeat = infantsInSeat,
            infantsOnLap = infantsOnLap,
            bags = bags,
            stops = stops,
            checkedBag = checkedBag,
            targetPrice = targetValue,
            notify = notify,
        )
        val sameSearch = existing != null && existing.searchKey() == fresh.searchKey()
        val toSave = if (sameSearch) {
            existing!!.copy(
                targetPrice = targetValue,
                notify = notify,
                lastNotifiedPrice = null,
                fromLabel = from.city,
                toLabel = to.city,
            )
        } else {
            fresh
        }
        Store.upsert(toSave)
        if (!sameSearch && Store.settings.value.isReady) {
            App.scope.launch { PriceChecker.checkOne(appContext, toSave.id) }
        }
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Új figyelés" else "Figyelés szerkesztése") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Útvonal")
            AirportField("Honnan", fromPlace) { fromPlace = it }
            AirportField("Hova", toPlace) { toPlace = it }
            SwitchRow("Oda-vissza út", roundTrip) { roundTrip = it }

            SectionTitle("Dátum")
            DateField("Indulás", outDate, minDate = today) {
                outDate = it
                if (retDate.isBefore(it)) retDate = it
            }
            if (roundTrip) {
                DateField("Visszaút", retDate, minDate = outDate) { retDate = it }
            }

            SectionTitle("Utasok és osztály")
            ChoiceField("Osztály", TRAVEL_CLASSES, travelClass) { travelClass = it }
            Stepper("Felnőtt", "12 év felett", adults, 1..9) { adults = it }
            Stepper("Gyerek", "2–11 év", children, 0..8) { children = it }
            Stepper("Csecsemő saját ülésen", "2 év alatt", infantsInSeat, 0..4) { infantsInSeat = it }
            Stepper("Csecsemő ölben", "2 év alatt, felnőttenként 1", infantsOnLap, 0..adults) { infantsOnLap = it }

            SectionTitle("Poggyász és átszállás")
            Stepper("Kézipoggyász", "összesen, minden utasra", bags, 0..maxBags) { bags = it }
            SwitchRow("Feladott poggyász (utasonként 1)", checkedBag) { checkedBag = it }
            Text(
                "A Google Flights és az Ignav a poggyász becsült díját beleszámolja. A Ryanair és a " +
                    "Wizz Air poggyász nélküli alapárat ad, ezeket a lista végére sorolom.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceField("Átszállás", STOP_OPTIONS, stops) { stops = it }

            SectionTitle("Riasztás")
            OutlinedTextField(
                value = target,
                onValueChange = { v -> target = v.filter(Char::isDigit).take(9) },
                label = { Text("Célár (${currencySymbol(currency)})") },
                supportingText = { Text("Szólunk, ha a teljes ár (minden utassal) eddig vagy ez alá esik") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Értesítés küldése", notify) { notify = it }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Mentés") }
            if (existing != null) {
                OutlinedButton(
                    onClick = {
                        Store.delete(existing.id)
                        onDone()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Figyelés törlése") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- Beállítások

@Composable
private fun SettingsScreen(onDone: () -> Unit) {
    val initial = remember { Store.settings.value }
    val context = LocalContext.current
    var googleOn by remember { mutableStateOf(initial.googleOn) }
    var ryanairOn by remember { mutableStateOf(initial.ryanairOn) }
    var wizzOn by remember { mutableStateOf(initial.wizzOn) }
    var serpOn by remember { mutableStateOf(initial.serpOn) }
    var ignavOn by remember { mutableStateOf(initial.ignavOn) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var ignavKey by remember { mutableStateOf(initial.ignavKey) }
    var currency by remember { mutableStateOf(initial.currency) }
    var interval by remember { mutableStateOf(initial.intervalHours) }
    val draft = Settings(googleOn, ryanairOn, wizzOn, serpOn, ignavOn, apiKey, ignavKey, currency, interval)

    val watches by Store.watches.collectAsState()
    val activeWatches = watches.filter { !it.isExpired() }
    val checksPerMonth = (24 / interval) * 30
    val serpPerMonth = if (draft.useSerpApi) activeWatches.size * checksPerMonth else 0
    val ignavPerMonth = if (draft.useIgnav) {
        activeWatches.sumOf {
            (it.from.split(',').size * it.to.split(',').size).coerceAtMost(Ignav.MAX_PAIRS)
        } * checksPerMonth
    } else 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Beállítások") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Árforrások – kulcs nélkül")
            Text(
                "Minden bekapcsolt forrást egyszerre kérdez le, és az összes ajánlatot ár szerint " +
                    "versenyezteti. Ezek nem hivatalos felületek: ha valamelyik megváltozik, átmenetileg " +
                    "hibát jelez, a többi forrás ettől még működik.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Google Flights (légitársaságok és irodák)", googleOn) { googleOn = it }
            SwitchRow("Ryanair (közvetlenül)", ryanairOn) { ryanairOn = it }
            SwitchRow("Wizz Air (közvetlenül)", wizzOn) { wizzOn = it }

            SectionTitle("Opcionális tartalékok – kulccsal")
            SwitchRow("SerpApi (Google Flights)", serpOn) { serpOn = it }
            if (serpOn) {
                TextButton(onClick = { openUrl(context, "https://serpapi.com/manage-api-key") }) {
                    Text("Kulcs: serpapi.com (havi 250 ingyenes)")
                }
                SecretField("SerpApi API-kulcs", apiKey) { apiKey = it }
            }
            SwitchRow("Ignav", ignavOn) { ignavOn = it }
            if (ignavOn) {
                TextButton(onClick = { openUrl(context, "https://ignav.com") }) {
                    Text("Kulcs: ignav.com (1000 ingyenes kérés)")
                }
                SecretField("Ignav API-kulcs", ignavKey) { ignavKey = it }
            }
            if (serpOn || ignavOn) {
                Text(
                    "A kulcsok csak ezen a telefonon tárolódnak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!draft.isReady) {
                Text(
                    "Legalább egy forrást kapcsolj be.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionTitle("Pénznem")
            ChoiceField("Árak pénzneme", CURRENCIES, currency) { currency = it }
            if (currency != initial.currency) {
                Text(
                    "Pénznemváltáskor az eddigi árelőzmények törlődnek.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Ellenőrzés gyakorisága")
            ChoiceField("Automatikus ellenőrzés", INTERVALS, interval) { interval = it }
            if (draft.useSerpApi) {
                Text(
                    "SerpApi: kb. $serpPerMonth keresés/hó (ingyenes keret: 250).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (serpPerMonth > 250) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (draft.useIgnav) {
                Text(
                    "Ignav: kb. $ignavPerMonth kérés/hó (1000 ingyenes, utána fizetős).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "A kulcs nélküli forrásoknál nincs keret, de túl gyakori lekérdezésnél ideiglenesen " +
                    "letilthatnak. A 6 óránkénti ellenőrzés biztonságos. Az Android energiatakarékossága " +
                    "miatt a háttér-ellenőrzés kicsit csúszhat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    if (currency != initial.currency) Store.clearAllResults()
                    Store.saveSettings(draft)
                    if (interval != initial.intervalHours) Scheduler.schedule(context.applicationContext)
                    onDone()
                },
                enabled = draft.isReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mentés") }
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { show = !show }) { Text(if (show) "Elrejt" else "Mutat") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- Közös elemek

@Composable
private fun AirportField(label: String, selected: Place?, onSelect: (Place?) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(selected?.fieldText ?: "") }
    var expanded by remember { mutableStateOf(false) }
    val results = remember(text, selected) {
        if (selected != null && text == selected.fieldText) emptyList()
        else Airports.search(context, text)
    }
    val showMenu = expanded && results.isNotEmpty()

    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                expanded = true
                if (selected != null) onSelect(null)
            },
            label = { Text(label) },
            placeholder = { Text("Város, repülőtér vagy kód") },
            supportingText = {
                Text(
                    when {
                        selected != null -> selected.subtitle
                        text.isNotBlank() && results.isEmpty() -> "Nincs találat"
                        else -> "Kezdj el gépelni, pl. Budapest, London, Bécs"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = true,
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        onSelect(null)
                        expanded = false
                    }) { Icon(Icons.Filled.Clear, contentDescription = "Törlés") }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            results.forEach { place ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                place.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                place.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        text = place.fieldText
                        onSelect(place)
                        expanded = false
                        focusManager.clearFocus()
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, hint: String?, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalIconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp),
        )
        FilledTonalIconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = "Több")
        }
    }
}

@Composable
private fun <T> ChoiceField(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "", modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(value)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DateField(label: String, date: LocalDate, minDate: LocalDate, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.DateRange, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(date.format(dateFormat), modifier = Modifier.weight(1f))
        }
    }
    if (open) {
        val minMillis = minDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= minMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Mégse") } },
        ) {
            DatePicker(state = state)
        }
    }
}
