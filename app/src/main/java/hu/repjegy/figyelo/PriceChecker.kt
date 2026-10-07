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

        if (settings.apiKey.isBlank()) {
            Store.update(id) { it.copy(lastError = "Nincs megadva SerpApi-kulcs (Beállítások)") }
            return@withContext
        }
        if (watch.isExpired()) {
            Store.update(id) { it.copy(lastError = "Az indulás dátuma már elmúlt") }
            return@withContext
        }
        if (id in Store.checking.value) return@withContext

        Store.checking.update { it + id }
        try {
            val result = runCatching { SerpApi.search(watch, settings.apiKey, settings.currency) }
            result.onSuccess { res ->
                var toNotify: Watch? = null
                Store.update(id) { cur ->
                    // Közben módosított keresést nem keverünk régi eredménnyel
                    if (cur.searchKey() != watch.searchKey()) return@update cur
                    val shouldNotify = cur.notify && res.price <= cur.targetPrice &&
                        (cur.lastNotifiedPrice == null || res.price < cur.lastNotifiedPrice)
                    val next = cur.copy(
                        lastPrice = res.price,
                        lowestPrice = minOf(cur.lowestPrice ?: res.price, res.price),
                        lastChecked = now,
                        lastError = null,
                        bestAirline = res.airline,
                        flightsUrl = res.flightsUrl ?: cur.flightsUrl,
                        history = (cur.history + PricePoint(now, res.price)).takeLast(MAX_HISTORY),
                        lastNotifiedPrice = when {
                            shouldNotify -> res.price
                            // Ha visszament a célár fölé, a következő eséskor újra szólunk
                            res.price > cur.targetPrice -> null
                            else -> cur.lastNotifiedPrice
                        },
                    )
                    if (shouldNotify) toNotify = next
                    next
                }
                toNotify?.let { Notifier.priceDrop(context, it, settings.currency) }
            }.onFailure { e ->
                Store.update(id) {
                    it.copy(lastChecked = now, lastError = e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            Store.checking.update { it - id }
        }
    }
}
