package hu.repjegy.figyelo

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

    suspend fun checkAll() {
        Store.watches.value
            .filter { !it.isExpired() }
            .forEach { checkOne(it.id) }
    }

    suspend fun checkOne(id: String) {
        val rerun = checkOnce(id)
        // Ha futás közben megváltozott a keresés (pl. más dátum), az újat is lefuttatjuk;
        // különben a régi eredményt eldobnánk, az újat pedig ki se próbálnánk.
        if (rerun) checkOnce(id)
    }

    /** Igazzal tér vissza, ha az ellenőrzés közben a figyelés keresése megváltozott. */
    private suspend fun checkOnce(id: String): Boolean = withContext(Dispatchers.IO) {
        val watch = Store.watches.value.find { it.id == id } ?: return@withContext false
        val settings = Store.settings.value
        val currency = settings.currency
        val now = System.currentTimeMillis()

        if (!settings.isReady) {
            Store.update(id) { it.copy(lastError = "Nincs bekapcsolt árforrás (Beállítások)") }
            return@withContext false
        }
        if (watch.isExpired()) {
            Store.update(id) { it.copy(lastError = "Az indulás dátuma már elmúlt") }
            return@withContext false
        }
        // Atomi foglalás: két egyszerre induló ellenőrzés (pl. kézi + háttér) ne fusson kétszer
        while (true) {
            val cur = Store.checking.value
            if (id in cur) return@withContext false
            if (Store.checking.compareAndSet(cur, cur + id)) break
        }
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
                    if (stale(it, watch, currency)) return@update it
                    it.copy(
                        lastChecked = now,
                        sourceStatus = statuses,
                        // Ha volt válasz, de nincs járat, a régi ár ne látsszon frissnek
                        offers = if (anyWorked) emptyList() else it.offers,
                        lastPrice = if (anyWorked) null else it.lastPrice,
                        lastError = if (anyWorked) "Nincs találat ezekkel a beállításokkal" else "Egyik forrás sem válaszolt",
                    )
                }
                return@withContext changedSince(id, watch)
            }

            val best = offers.first()
            var toNotify: Watch? = null
            Store.update(id) { cur ->
                // Közben módosított keresést / pénznemet nem keverünk régi eredménnyel
                if (stale(cur, watch, currency)) return@update cur
                // Poggyászt kértél, de csak poggyász nélküli fapados alapár jött: ez nem
                // összevethető a célárral, ezért nem riaszt és nem kerül az árgörbére
                val comparable = !(cur.wantsBags && !best.bagsIncluded)
                val shouldNotify = comparable && cur.notify && best.price <= cur.targetPrice &&
                    (cur.lastNotifiedPrice == null || best.price.toLong() * 100 <= cur.lastNotifiedPrice.toLong() * 98)
                val next = cur.copy(
                    lastPrice = best.price,
                    lowestPrice = if (comparable) minOf(cur.lowestPrice ?: best.price, best.price) else cur.lowestPrice,
                    lastChecked = now,
                    lastError = null,
                    offers = offers,
                    sourceStatus = statuses,
                    history = if (comparable) (cur.history + PricePoint(now, best.price)).takeLast(MAX_HISTORY) else cur.history,
                    lastNotifiedPrice = when {
                        shouldNotify -> best.price
                        // Ha visszament a célár fölé, a következő eséskor újra szólunk
                        comparable && best.price > cur.targetPrice -> null
                        else -> cur.lastNotifiedPrice
                    },
                )
                if (shouldNotify) toNotify = next
                next
            }
            toNotify?.let { Platform.current.notifyPriceDrop(it, currency) }
            changedSince(id, watch)
        } finally {
            Store.checking.update { it - id }
        }
    }

    private fun stale(cur: Watch, started: Watch, currency: String) =
        cur.searchKey() != started.searchKey() || Store.settings.value.currency != currency

    private fun changedSince(id: String, watch: Watch): Boolean {
        val cur = Store.watches.value.find { it.id == id } ?: return false
        return cur.searchKey() != watch.searchKey()
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
