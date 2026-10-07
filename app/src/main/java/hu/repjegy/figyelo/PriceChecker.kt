package hu.repjegy.figyelo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

object PriceChecker {

    private const val MAX_HISTORY = 120

    suspend fun checkAll(context: Context) {
        Store.watches.value
            .filter { !it.isExpired() }
            .forEach { checkOne(context, it.id) }
    }

    suspend fun checkOne(context: Context, id: String) = withContext(Dispatchers.IO) {
        val watch = Store.watches.value.find { it.id == id } ?: return@withContext
        val settings = Store.settings.value
        val now = System.currentTimeMillis()

        if (!settings.isReady) {
            Store.update(id) { it.copy(lastError = "Hiányzik az árforrás kulcsa (Beállítások)") }
            return@withContext
        }
        if (watch.isExpired()) {
            Store.update(id) { it.copy(lastError = "Az indulás dátuma már elmúlt") }
            return@withContext
        }
        if (id in Store.checking.value) return@withContext

        Store.checking.update { it + id }
        try {
            val results = mutableListOf<FareResult>()
            val errors = mutableListOf<String>()

            // Feladott poggyásznál a SerpApi ára félrevezető lenne, ha az Ignav is elérhető.
            val skipSerpApi = watch.checkedBag && settings.useIgnav
            if (settings.useSerpApi && !skipSerpApi) {
                runCatching {
                    val r = SerpApi.search(watch, settings.apiKey, settings.currency)
                    FareResult(r.price, r.airline, r.flightsUrl, "SerpApi")
                }.onSuccess { results += it }
                    .onFailure { errors += "SerpApi: ${it.message ?: it.javaClass.simpleName}" }
            }
            if (settings.useIgnav) {
                runCatching { Ignav.search(watch, settings.ignavKey, settings.currency) }
                    .onSuccess { results += it }
                    .onFailure { errors += "Ignav: ${it.message ?: it.javaClass.simpleName}" }
            }

            val best = results.minByOrNull { it.price }
            if (best == null) {
                Store.update(id) {
                    it.copy(lastChecked = now, lastError = errors.joinToString("\n").ifBlank { "Nincs találat" })
                }
                return@withContext
            }

            val warnings = errors.toMutableList()
            if (watch.checkedBag && !settings.useIgnav) {
                warnings += "A feladott poggyász díja nincs benne az árban (csak az Ignav számolja)."
            }

            var toNotify: Watch? = null
            Store.update(id) { cur ->
                // Közben módosított keresést nem keverünk régi eredménnyel
                if (cur.searchKey() != watch.searchKey()) return@update cur
                val shouldNotify = cur.notify && best.price <= cur.targetPrice &&
                    (cur.lastNotifiedPrice == null || best.price < cur.lastNotifiedPrice)
                val next = cur.copy(
                    lastPrice = best.price,
                    lowestPrice = minOf(cur.lowestPrice ?: best.price, best.price),
                    lastChecked = now,
                    lastError = null,
                    bestAirline = best.airline,
                    bestSource = best.source,
                    sourceWarning = warnings.joinToString("\n").ifBlank { null },
                    flightsUrl = best.flightsUrl ?: cur.flightsUrl,
                    history = (cur.history + PricePoint(now, best.price)).takeLast(MAX_HISTORY),
                    lastNotifiedPrice = when {
                        shouldNotify -> best.price
                        // Ha visszament a célár fölé, a következő eséskor újra szólunk
                        best.price > cur.targetPrice -> null
                        else -> cur.lastNotifiedPrice
                    },
                )
                if (shouldNotify) toNotify = next
                next
            }
            toNotify?.let { Notifier.priceDrop(context, it, settings.currency) }
        } finally {
            Store.checking.update { it - id }
        }
    }
}
