package hu.repjegy.figyelo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

object PriceChecker {

    private const val MAX_HISTORY = 120
    private const val MAX_OFFERS = 10
    const val CURRENCY_HINT_PREFIX = "Pénznemet váltottál"

    private class SourceRun(val name: String, val search: () -> List<Offer>)

    suspend fun checkAll() {
        Store.watches.value
            .filter { !it.isExpired() }
            // A legrégebben ellenőrzött először: ha a háttérfutás időkorlátja (Androidon
            // ~10 perc) közbeszól, a következő futás a kimaradtakkal kezd
            .sortedBy { it.lastChecked ?: 0L }
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
                // Rugalmas dátumnál a kulcs nélküli források minden dátumpárt lekérdeznek
                // (a kulcsos SerpApi/Ignav csak a pontos dátumot – azok keretét ne égessük el)
                if (settings.googleOn) add(SourceRun(GoogleFlights.NAME) { flexSearch(watch) { GoogleFlights.search(it, currency) } })
                if (settings.ryanairOn) add(SourceRun(Ryanair.NAME) {
                    airlineAllowed(watch, "ryanair")
                    flexSearch(watch) { Ryanair.search(it, currency) }
                })
                if (settings.wizzOn) add(SourceRun(WizzAir.NAME) {
                    airlineAllowed(watch, "wizz")
                    flexSearch(watch) { WizzAir.search(it, currency) }
                })
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
                            // A szerverek hibaszövege lehet hosszú (akár HTML) – röviden tároljuk
                            else -> SourceStatus(name, false, (e.message ?: e.javaClass.simpleName).take(160))
                        }
                    },
                )
            }
            val allOffers = outcomes.flatMap { it.second.getOrNull().orEmpty() }
            val offers = rank(watch, allOffers)

            if (offers.isEmpty()) {
                val anyWorked = statuses.any { it.ok }
                // Csak akkor „nincs járat”, ha minden forrás válaszolt; ha valamelyik hibázott,
                // a korábbi ár megmarad (lehet, hogy éppen az a forrás ismeri ezt az útvonalat)
                val allAnswered = statuses.isNotEmpty() && statuses.all { it.ok }
                Store.update(id) {
                    if (stale(it, watch, currency)) return@update it
                    it.copy(
                        lastChecked = now,
                        sourceStatus = statuses,
                        offers = if (allAnswered) emptyList() else it.offers,
                        lastPrice = if (allAnswered) null else it.lastPrice,
                        lastError = when {
                            allAnswered -> "Nincs találat ezekkel a beállításokkal"
                            anyWorked -> "Nem minden forrás válaszolt, és a többi nem talált járatot"
                            else -> "Egyik forrás sem válaszolt"
                        }.let { msg -> keepCurrencyHint(it) ?: msg },
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
                val comparable = cur.comparable(best)
                val shouldNotify = comparable && cur.notify && best.price <= cur.targetPrice &&
                    (cur.lastNotifiedPrice == null || best.price.toLong() * 100 <= cur.lastNotifiedPrice.toLong() * 98)
                val next = cur.copy(
                    lastPrice = best.price,
                    lowestPrice = if (comparable) minOf(cur.lowestPrice ?: best.price, best.price) else cur.lowestPrice,
                    lastChecked = now,
                    lastError = keepCurrencyHint(cur),
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

    /** Ha a légitársaság-szűrő kizárja ezt a fapadost, a lekérdezést meg sem kezdjük. */
    private fun airlineAllowed(w: Watch, name: String) {
        val tokens = w.airlineTokens
        if (tokens.isNotEmpty() && tokens.none { name.contains(it) || it.contains(name) }) {
            throw SkipSourceException("kizárva a légitársaság-szűrővel")
        }
    }

    /**
     * Rugalmas dátum: minden dátumpárra lefuttatja a keresést és összefésüli. Ha egy-egy
     * dátum hibázik, a többi eredménye megmarad; ha mind hibázik, az első hibát adja tovább.
     */
    internal fun flexSearch(w: Watch, search: (Watch) -> List<Offer>): List<Offer> {
        val pairs = w.datePairs()
        if (pairs.size <= 1 && w.flexDays == 0) return search(w)
        val results = mutableListOf<Offer>()
        var firstError: Exception? = null
        var anyOk = false
        for ((index, pair) in pairs.withIndex()) {
            if (index > 0) Thread.sleep(400) // ne zúdítsunk egyszerre sok kérést a forrásra
            try {
                results += search(w.copy(outboundDate = pair.first, returnDate = pair.second, flexDays = 0))
                anyOk = true
            } catch (e: SkipSourceException) {
                throw e
            } catch (e: FatalSourceException) {
                if (anyOk) break else throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (!anyOk && firstError != null) throw firstError
        return results
    }

    /** A pénznemváltás miatti „add meg újra a célárat” figyelmeztetés maradjon, amíg az értesítés ki van kapcsolva. */
    private fun keepCurrencyHint(w: Watch): String? =
        w.lastError?.takeIf { !w.notify && it.startsWith(CURRENCY_HINT_PREFIX) }

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
    internal fun rank(w: Watch, offers: List<Offer>, now: LocalDateTime = LocalDateTime.now()): List<Offer> {
        val deduped = offers
            // Időablak és légitársaság-szűrő
            .filter { w.matchesFilters(it) }
            // Már elindult járat ne legyen „legjobb ajánlat”. Az idő a reptér helyi ideje, a
            // készüléké más időzónában lehet (akár 9-12 óra), ezért bőven hagyunk ráhagyást
            .filter { o ->
                o.departure?.let { d -> runCatching { LocalDateTime.parse(d) }.getOrNull() }
                    ?.isAfter(now.minusHours(12)) ?: true
            }
            .groupBy { o ->
                if (o.departure != null) {
                    listOf(o.departure, o.returnDeparture, o.fromCode, o.toCode, o.airline?.lowercase()).joinToString("|")
                } else {
                    "${o.source}|${o.price}|${o.airline}"
                }
            }
            .map { (_, same) -> same.minWith(compareBy<Offer>({ !w.comparable(it) }, { !it.bagsIncluded }, { it.price })) }
        return deduped
            .sortedWith(compareBy<Offer>({ !w.comparable(it) }, { it.price }))
            .take(MAX_OFFERS)
    }
}
