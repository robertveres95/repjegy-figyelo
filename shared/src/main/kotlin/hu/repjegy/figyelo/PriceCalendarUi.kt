package hu.repjegy.figyelo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * Havi árnaptár: a hónap napjai a legolcsóbb egyirányú árral, színezve (zöld = olcsó, sárga = közepes,
 * piros = drága). Egy napra koppintva az lesz az indulás napja.
 */
@Composable
fun PriceCalendarDialog(from: String, to: String, initial: LocalDate, onPick: (LocalDate) -> Unit, onClose: () -> Unit) {
    val currency = Store.settings.value.currency
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(maxOf(initial, today))) }
    val lastMonth = remember { YearMonth.from(maxTravelDate(today)) }
    // null = töltés; a hibát külön tartjuk
    val state by produceState<Pair<Map<LocalDate, PriceCalendar.Day>?, String?>>(null to null, from, to, month, currency) {
        value = null to null
        value = withContext(Dispatchers.IO) {
            runCatching { PriceCalendar.month(from, to, month, currency, today) }
                .fold({ it to null }, { null to (it.message ?: "?") })
        }
    }
    val (days, error) = state
    Dialog(onDismissRequest = onClose) {
        NeonCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr("ÁRNAPTÁR", "PRICE CALENDAR", "PREISKALENDER"), style = MaterialTheme.typography.titleLarge,
                    color = Neon.Green, fontWeight = FontWeight.Bold)
                Text(
                    "${Airports.cityName(from) ?: from} → ${Airports.cityName(to) ?: to}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }, enabled = month > YearMonth.from(today)) { Text("‹") }
                    Text(
                        month.format(DateTimeFormatter.ofPattern(tr("yyyy. LLLL", "LLLL yyyy", "LLLL yyyy"), Lang.locale))
                            .replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < lastMonth) { Text("›") }
                }
                // A hét napjai (hétfőtől)
                Row(Modifier.fillMaxWidth()) {
                    DayOfWeek.values().forEach { d ->
                        Text(
                            d.getDisplayName(TextStyle.SHORT, Lang.locale).take(3),
                            style = MaterialTheme.typography.labelSmall,
                            color = Neon.TextDim,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                when {
                    error != null -> StatusText(error, true)
                    days == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Neon.Green)
                        Text(tr("Árak betöltése…", "Loading prices…", "Preise werden geladen…"), color = Neon.Green)
                    }
                    else -> MonthGrid(month, days, today, initial, currency) { onPick(it); onClose() }
                }
                Text(
                    tr(
                        "Egy főre, csak oda, poggyász nélkül – a Ryanair és a Wizz Air árnaptárából. Koppints egy napra: az lesz az indulás.",
                        "Per person, one way, without bags – from the Ryanair and Wizz Air fare calendars. Tap a day to make it the departure date.",
                        "Pro Person, nur Hinflug, ohne Gepäck – aus den Preiskalendern von Ryanair und Wizz Air. Tippe auf einen Tag, um ihn als Abflug zu wählen.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(tr("Bezárás", "Close", "Schließen")) }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    days: Map<LocalDate, PriceCalendar.Day>,
    today: LocalDate,
    selected: LocalDate,
    currency: String,
    onPick: (LocalDate) -> Unit,
) {
    val tiers = remember(days) { PriceCalendar.tiers(days.values) }
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1 // hétfő = 0
    val cells = lead + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    if (days.isEmpty()) {
        Text(
            tr("Ebben a hónapban nincs fapados járat ezen az úton.", "No low-cost flights on this route this month.",
                "In diesem Monat gibt es auf dieser Strecke keine Billigflüge."),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c - lead
                    if (idx < 0 || idx >= month.lengthOfMonth()) {
                        Box(Modifier.weight(1f).aspectRatio(0.8f))
                        continue
                    }
                    val date = month.atDay(idx + 1)
                    val day = days[date]
                    val past = date.isBefore(today)
                    val color = when {
                        day == null || tiers == null -> Neon.Surface
                        day.pricePerPerson <= tiers.first -> Neon.Mint.copy(alpha = 0.35f)
                        day.pricePerPerson <= tiers.second -> Neon.Amber.copy(alpha = 0.30f)
                        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.28f)
                    }
                    val label = day?.let { shortPrice(it.pricePerPerson, currency) } ?: ""
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.8f)
                            .background(color, RoundedCornerShape(8.dp))
                            .border(if (date == selected) 2.dp else 0.dp, if (date == selected) Neon.Green else Neon.Surface, RoundedCornerShape(8.dp))
                            .clickable(enabled = !past && day != null) { onPick(date) }
                            .semantics { contentDescription = "${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))} $label" }
                            .padding(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelMedium,
                            color = if (past) Neon.TextDim else Neon.Text)
                        if (label.isNotEmpty()) Text(label, fontSize = 9.sp, maxLines = 1, color = Neon.Text)
                    }
                }
            }
        }
    }
}

/** Rövid ár a cellába: 12 300 Ft → „12,3e”; 45 € → „45€”. */
internal fun shortPrice(p: Int, currency: String): String = when {
    currency == "HUF" && p >= 10_000 -> tr("${p / 1000}e", "${p / 1000}k", "${p / 1000}T")
    currency == "HUF" -> tr("${"%.1f".format(java.util.Locale.ROOT, p / 1000.0).replace('.', ',')}e", "${"%.1f".format(java.util.Locale.ROOT, p / 1000.0)}k", "${"%.1f".format(java.util.Locale.ROOT, p / 1000.0).replace('.', ',')}T")
    else -> "$p${currencySymbol(currency)}"
}
