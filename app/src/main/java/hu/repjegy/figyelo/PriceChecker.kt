package hu.repjegy.figyelo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

object PriceChecker {

    private const val MAX_HISTORY = 120
    private const val MAX_OFFERS = 10

    private class SourceRun(val name: String, val search: () -> List<Offer>)

    suspend fun checkAll(context: Context) {
        Store.watches.value
            .filter { !it.isExpired() }
            .forEach { checkOne(context, it.id) }
    }

    suspend fun checkOne(context: Context, id: String) = withContext(Dispatchers.IO) {
        val watch = Store.watches.value.find { it.id == id } ?: return@withContext
        val settings = Store.settings.value
        val currency = settings.currency
        val now = System.currentTimeMillis()

        if (!settings.isReady) {
            Store.update(id) { it.copy(lastError = "Nincs bekapcsolt árforrás (Beállítások)") }
            return@withContext
        }
        if (watch.isExpired()) {
            Store.update(id) { it.copy(lastError = "Az indulás dátuma már elmúlt") }
            return@withContext
        }
        if (id in Store.checking.value) return@withContext

        Store.checking.update { it + id }
        try {
            val runs = buildList {
                if (settings.googleOn) add(SourceRun(GoogleFlights.NAME) { GoogleFlights.search(watch, currency) })
                if (settings.ryanairOn) add(SourceRun(Ryanair.NAME) { Ryanair.search(watch, currency) })
                if (settings.wizzOn) add(SourceRun(WizzAir.NAME) { WizzAir.search(watch, currency) })
                if (settings.useSerpApi) add(SourceRun(SerpApi.NAME) { SerpApi.search(watch, settings.apiKey, currency) })
                if (settings.useIgnav) add(SourceRun(Ignav.NAME) { Ignav.search(watch, settings.ignavKey, currency) })
            }

            // Minden forrás párhuzamosan fut; egyik hibája sem akasztja meg a többit.
            val outcomes = coroutineScope {
                runs.map { run ->
                    async { run.name to runCatching { run.search() } }
                }.awaitAll()
            }

            val statuses = outcomes.map { (name, result) ->
                result.fold(
                    onSuccess = { offers ->
                        SourceStatus(name, true, if (offers.isEmpty()) "nincs járat" else "${offers.size} ajánlat")
                    },
                    onFailure = { e ->
                        when (e) {
                            is SkipSourceException -> SourceStatus(name, true, e.message ?: "kihagyva")
                            else -> SourceStatus(name, false, e.message ?: e.javaClass.simpleName)
                        }
                    },
                )
            }
            val allOffers = outcomes.flatMap { it.second.getOrNull().orEmpty() }
            val offers = rank(watch, allOffers)

            if (offers.isEmpty()) {
                val anyWorked = statuses.any { it.ok }
                Store.update(id) {
                    it.copy(
                        lastChecked = now,
                        sourceStatus = statuses,
                        lastError = if (anyWorked) "Nincs találat ezekkel a beállításokkal" else "Egyik forrás sem válaszolt",
                    )
                }
                return@withContext
            }

            val best = offers.first()
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
                    offers = offers,
                    sourceStatus = statuses,
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
            toNotify?.let { Notifier.priceDrop(context, it, currency) }
        } finally {
            Store.checking.update { it - id }
        }
    }

    /**
     * Ugyanaz a járat több forrásból is jöhet: ilyenkor a legolcsóbbat tartjuk meg.
     * Ha poggyászt kértél, a poggyász nélküli (fapados alap-) árak a lista végére kerülnek,
     * hogy ne ezek nyerjenek tévesen.
     */
    private fun rank(w: Watch, offers: List<Offer>): List<Offer> {
        val deduped = offers
            .groupBy { o ->
                if (o.departure != null) {
                    listOf(o.departure, o.returnDeparture, o.fromCode, o.toCode, o.airline?.lowercase()).joinToString("|")
                } else {
                    "${o.source}|${o.price}|${o.airline}"
                }
            }
            .map { (_, same) -> same.minWith(compareBy<Offer>({ !it.bagsIncluded }, { it.price })) }
        return deduped
            .sortedWith(compareBy<Offer>({ w.wantsBags && !it.bagsIncluded }, { it.price }))
            .take(MAX_OFFERS)
    }
}
