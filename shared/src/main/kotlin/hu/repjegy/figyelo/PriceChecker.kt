package hu.repjegy.figyelo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

object PriceChecker {

    private const val MAX_HISTORY = 120
    private const val MAX_OFFERS = 10
    const val CURRENCY_HINT_PREFIX = "Pénznemet váltottál"

    private class SourceRun(val name: String, val search: suspend () -> List<Offer>)

    suspend fun checkAll() {
        val attempts = loadAttempts()
        Store.watches.value
            .filter { !it.isExpired() }
            // A legrégebben ellenőrzött (vagy megkísérelt) először: ha a háttérfutás időkorlátja
            // (Androidon ~10 perc) egy lassú figyelésnél közbeszól, a következő futás nem ugyanazzal
            // kezd újra, hanem a kimaradtakkal
            .sortedBy { maxOf(it.lastChecked ?: 0L, attempts[it.id] ?: 0L) }
            .forEach { w ->
                attempts[w.id] = System.currentTimeMillis()
                saveAttempts(attempts)
                checkOne(w.id)
            }
    }

    private fun loadAttempts(): MutableMap<String, Long> {
        val o = runCatching { org.json.JSONObject(Store.prefs.getString("checkAttempts", "{}") ?: "{}") }.getOrNull()
            ?: return mutableMapOf()
        val ids = Store.watches.value.map { it.id }.toSet()
        return o.keys().asSequence().filter { it in ids }.associateWith { o.optLong(it, 0L) }.toMutableMap()
    }

    private fun saveAttempts(m: Map<String, Long>) {
        val o = org.json.JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        runCatching { Store.prefs.edit { putString("checkAttempts", o.toString()) } }
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
                // Rugalmas dátumnál a kérések száma gyorsan nő (dátumok × reptérpárok), ezért
                // ilyenkor kevesebb reptérpárt kérdezünk, és a Google repterenkénti tartaléka sem fut
                val flex = watch.flexDays > 0
                val pairsCap = if (flex) MAX_PAIRS_FLEX else MAX_PAIRS
                if (settings.googleOn) add(SourceRun(GoogleFlights.NAME) {
                    flexSearch(watch) { GoogleFlights.search(it, currency, allowFallback = !flex) }
                })
                if (settings.ryanairOn) add(SourceRun(Ryanair.NAME) {
                    airlineAllowed(watch, "Ryanair")
                    flexSearch(watch) { Ryanair.search(it, currency, pairsCap) }
                })
                if (settings.wizzOn) add(SourceRun(WizzAir.NAME) {
                    airlineAllowed(watch, "Wizz Air")
                    flexSearch(watch) { WizzAir.search(it, currency, pairsCap) }
                })
                // A kulcsos források csak egy dátumot kérdeznek: a rugalmas tartomány első
                // érvényes (nem múltbeli) napját – így a keretük nem fogy el
                val single = watch.datePairs().first().let { (o, r) -> watch.copy(outboundDate = o, returnDate = r, flexDays = 0) }
                if (settings.useSerpApi) add(SourceRun(SerpApi.NAME) { SerpApi.search(single, settings.apiKey, currency) })
                if (settings.useIgnav) add(SourceRun(Ignav.NAME) { Ignav.search(single, settings.ignavKey, currency) })
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
                            // Talált ajánlatot, de nem minden kérése sikerült: nem számít teljes válasznak
                            is PartialSourceException -> SourceStatus(name, false, "${e.offers.size} ajánlat, ${e.message}".take(160))
                            // A szerverek hibaszövege lehet hosszú (akár HTML) – röviden tároljuk
                            else -> SourceStatus(name, false, (e.message ?: e.javaClass.simpleName).take(160))
                        }
                    },
                )
            }
            val allOffers = outcomes.flatMap { (_, r) ->
                r.getOrNull() ?: (r.exceptionOrNull() as? PartialSourceException)?.offers.orEmpty()
            }
            // Minden forrás teljesen válaszolt-e (hiányzó válasznál a legolcsóbb ár épp a hiányzó lehet)
            val allAnswered = statuses.isNotEmpty() && statuses.all { it.ok }
            val offers = rank(watch, allOffers)

            if (offers.isEmpty()) {
                val anyWorked = statuses.any { it.ok }
                // Csak akkor „nincs járat”, ha minden forrás válaszolt; ha valamelyik hibázott,
                // a korábbi ár megmarad (lehet, hogy éppen az a forrás ismeri ezt az útvonalat)
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
                // Ha nem minden forrás válaszolt, és a talált ár drágább a korábbinál, lehet, hogy épp a
                // hiányzó forrás tudta az olcsóbbat: ilyenkor nem kerül hamis „drágulás” az árgörbére
                val trusted = allAnswered || cur.lastPrice == null || best.price <= cur.lastPrice
                val shouldNotify = comparable && cur.notify && best.price <= cur.targetPrice &&
                    (cur.lastNotifiedPrice == null || best.price.toLong() * 100 <= cur.lastNotifiedPrice.toLong() * 98)
                val next = cur.copy(
                    lastPrice = best.price,
                    lowestPrice = if (comparable) minOf(cur.lowestPrice ?: best.price, best.price) else cur.lowestPrice,
                    lastChecked = now,
                    lastError = keepCurrencyHint(cur),
                    offers = offers,
                    sourceStatus = statuses,
                    history = if (comparable && trusted) (cur.history + PricePoint(now, best.price)).takeLast(MAX_HISTORY) else cur.history,
                    lastNotifiedPrice = when {
                        shouldNotify -> best.price
                        // Ha visszament a célár fölé, a következő eséskor újra szólunk (csak teljes válasznál:
                        // egy hibázó forrás miatti „drágulás” ne okozzon dupla értesítést)
                        comparable && allAnswered && best.price > cur.targetPrice -> null
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
        if (!w.airlineMatches(name)) throw SkipSourceException("kizárva a légitársaság-szűrővel")
    }

    /**
     * Rugalmas dátum: minden dátumpárra lefuttatja a keresést és összefésüli. Ha egy-egy
     * dátum hibázik, a többi eredménye megmarad; ha mind hibázik, az első hibát adja tovább.
     */
    internal suspend fun flexSearch(w: Watch, search: (Watch) -> List<Offer>): List<Offer> {
        val pairs = w.datePairs()
        if (pairs.size <= 1 && w.flexDays == 0) return search(w)
        val ctx = kotlin.coroutines.coroutineContext
        // ne zúdítsunk egyszerre sok kérést a forrásra; leállításkor (pl. háttérmunka vége) itt megáll
        return collectOffers(pairs, betweenEach = { Thread.sleep(400); ctx.ensureActive() }) { pair ->
            search(w.copy(outboundDate = pair.first, returnDate = pair.second, flexDays = 0))
        }
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
