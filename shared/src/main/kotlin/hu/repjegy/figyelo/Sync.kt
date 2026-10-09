package hu.repjegy.figyelo

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Szinkronizálás a felhasználó Google-fiókjával: a figyelések egy rejtett, csak a REFI által
 * látható fájlban vannak a Google Drive „alkalmazásadat” területén (appDataFolder).
 * Minden eszköz letölti, összefésüli a sajátjával, és visszatölti.
 *
 * Összefésülés figyelésenként: a beállításoknál a későbbi felhasználói módosítás nyer
 * (editedAt), az árakból a frissebb ellenőrzés; a törlés törlésjellel terjed.
 * A beállítások (téma, pénznem stb.) eszközönként külön maradnak.
 */
object Sync {
    private const val FILE_NAME = "refi-sync.json"
    private const val FORMAT = "refi-sync"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

    data class State(
        val enabled: Boolean = false,
        val account: String? = null,
        val lastSync: Long? = null,
        val running: Boolean = false,
        val error: String? = null,
        /** Bejelentkezés folyamatban (a Google ablaka nyitva) – a háttér-szinkron nem írja felül. */
        val signingIn: Boolean = false,
    ) {
        val busy: Boolean get() = running || signingIn
    }

    val state = MutableStateFlow(State())

    private val mutex = Mutex()
    private var pending: Job? = null

    /** Induláskor: a mentett állapot betöltése. */
    fun load() {
        val p = Store.prefs
        state.value = State(
            enabled = p.getBoolean("syncOn", false),
            account = p.getString("syncAccount", null)?.takeIf { it.isNotBlank() },
            lastSync = p.getLong("syncLast", 0L).takeIf { it > 0 },
        )
    }

    /** Bejelentkezés (ha kell, a Google ablakával) és az első szinkronizálás. */
    suspend fun enable(): Boolean {
        state.update { it.copy(signingIn = true, error = null) }
        try {
            val token = runCatching { Platform.current.googleAccessToken(interactive = true) }.getOrNull()
            if (token == null) {
                state.update { it.copy(error = "A bejelentkezés nem sikerült vagy megszakadt.") }
                return false
            }
            val email = runCatching { accountEmail(token) }.getOrNull()
            Store.prefs.edit {
                putBoolean("syncOn", true)
                email?.let { putString("syncAccount", it) }
            }
            state.update { it.copy(enabled = true, account = email) }
        } finally {
            state.update { it.copy(signingIn = false) }
        }
        return syncNow()
    }

    fun disable() {
        Store.prefs.edit {
            putBoolean("syncOn", false)
            putString("syncAccount", "")
        }
        runCatching { Platform.current.googleSignOut() }
        state.value = State()
    }

    /** Egy változtatás után néhány másodperccel (gyors egymásutáni módosításoknál csak egyszer). */
    @Synchronized
    fun scheduleSoon() {
        if (!state.value.enabled) return
        pending?.cancel()
        pending = AppScope.scope.launch {
            delay(4000)
            syncNow()
        }
    }

    /** Szinkronizálás most; csendben semmit sem csinál, ha nincs bekapcsolva. */
    suspend fun syncNow(): Boolean {
        if (!state.value.enabled) return false
        return mutex.withLock {
            // Közben kijelentkezhetett: akkor már nem töltünk fel semmit
            if (!state.value.enabled) return@withLock false
            state.update { it.copy(running = true) }
            try {
                syncOnce()
                val now = System.currentTimeMillis()
                Store.prefs.edit { putLong("syncLast", now) }
                state.update { it.copy(lastSync = now, error = null) }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Egy újabb módosítás miatt újraindul – ez nem hiba
                throw e
            } catch (e: Exception) {
                state.update { it.copy(error = "Szinkronizálási hiba: ${e.message?.take(120)}") }
                false
            } finally {
                state.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun syncOnce() {
        val token = Platform.current.googleAccessToken(interactive = false)
            ?: throw IOException("Jelentkezz be újra a Google-fiókkal (Beállítások)")
        val auth = mapOf("Authorization" to "Bearer $token")

        // 1. Meglévő fájl keresése a rejtett mappában
        val q = URLEncoder.encode("name='$FILE_NAME'", "UTF-8")
        // A legrégebbi fájl a „hivatalos” (ha két eszköz egyszerre hozott létre egyet, mindkettő ugyanazt választja)
        val list = Http.request("$API/files?spaces=appDataFolder&q=$q&fields=files(id)&orderBy=createdTime&pageSize=10", headers = auth, timeoutMs = 30_000)
        check401(list.code, list.body)
        if (list.code !in 200..299) throw IOException("Drive HTTP ${list.code}")
        val fileId = JSONObject(list.body).optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }

        // 2. Letöltés és összefésülés
        val currency = Store.settings.value.currency
        var remote: Snapshot? = null
        if (fileId != null) {
            val dl = Http.request("$API/files/$fileId?alt=media", headers = auth, timeoutMs = 30_000)
            check401(dl.code, dl.body)
            if (dl.code !in 200..299) throw IOException("Drive HTTP ${dl.code}")
            // Ha a meglévő fájl nem olvasható (sérült, vagy egy újabb app-verzió formátuma),
            // semmiképp ne írjuk felül a helyi adatokkal – különben a felhőben lévő elveszne
            remote = parse(dl.body)
                ?: throw IOException("a felhőben lévő adat nem olvasható – frissítsd a REFI-t a legújabb verzióra")
        }
        // A pénznem-átváltás (hálózat) még az összefésülés előtt; ha nem megy, most kihagyjuk,
        // különben a felhőbe a másik eszköz változásai nélkül töltenénk fel
        val remoteWatches = remote?.let {
            runCatching { convertCurrency(it.watches, it.currency, currency) }.getOrElse {
                throw IOException("az árfolyam most nem érhető el (a két eszköz pénzneme eltér) – később újrapróbálja")
            }
        }
        // Az összefésülés a tároló zárján belül, a legfrissebb helyi állapottal (közben érkezett
        // szerkesztés, törlés vagy ár nem vész el)
        val merged = Store.mergeFromSync(remoteWatches.orEmpty(), remote?.tombstones.orEmpty())
        // Ha egy-egy figyelés nem volt beolvasható, a feltöltés kihagyná őket: inkább nem töltünk fel
        if (remote != null && remote.skipped > 0) {
            throw IOException("${remote.skipped} figyelés a felhőben nem olvasható – frissítsd a REFI-t a legújabb verzióra")
        }

        // 3. Feltöltés (csak ha változott a felhőben lévőhöz képest)
        val body = serialize(merged.first, merged.second, currency)
        if (remote != null && remote.currency == currency &&
            remote.watches.associateBy { it.id } == merged.first.associateBy { it.id } &&
            remote.tombstones == merged.second
        ) return
        val res = if (fileId == null) {
            val boundary = "refi" + System.nanoTime()
            val meta = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder")).toString()
            val multipart = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$body\r\n--$boundary--\r\n"
            Http.request(
                "$UPLOAD/files?uploadType=multipart&fields=id", method = "POST",
                headers = auth + ("Content-Type" to "multipart/related; boundary=$boundary"), body = multipart, timeoutMs = 30_000,
            )
        } else {
            // Tartalom cseréje: a Drive v2 PUT-ot fogad (a HttpURLConnection nem tud PATCH-et küldeni,
            // amit a v3 várna); ugyanazt a fájlt és jogosultságot használja
            Http.request(
                "https://www.googleapis.com/upload/drive/v2/files/$fileId?uploadType=media", method = "PUT",
                headers = auth + ("Content-Type" to "application/json; charset=UTF-8"),
                body = body, timeoutMs = 30_000,
            )
        }
        check401(res.code, res.body)
        if (res.code !in 200..299) throw IOException("Drive feltöltés HTTP ${res.code}")
    }

    private fun check401(code: Int, body: String = "") {
        // 403 lehet kvóta/korlát is: csak jogosultsági hibánál kérünk új bejelentkezést
        val authProblem = code == 401 || (code == 403 && (body.contains("insufficientPermissions") || body.contains("authError")))
        if (authProblem) {
            runCatching { Platform.current.googleInvalidateToken() }
            throw IOException("A Google-hozzáférés lejárt – jelentkezz be újra (Beállítások)")
        }
        if (code == 403 || code == 429) throw IOException("a Google ideiglenesen korlátozta a kéréseket – később újrapróbálja")
    }

    private fun accountEmail(token: String): String? {
        val r = Http.request("$API/about?fields=user(emailAddress)", headers = mapOf("Authorization" to "Bearer $token"), timeoutMs = 20_000)
        if (r.code !in 200..299) return null
        return JSONObject(r.body).optJSONObject("user")?.optString("emailAddress")?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------ formátum és összefésülés

    internal class Snapshot(val watches: List<Watch>, val tombstones: Map<String, Long>, val currency: String, val skipped: Int = 0)

    internal fun serialize(watches: List<Watch>, tombstones: Map<String, Long>, currency: String): String =
        JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("currency", currency)
            .put("updatedAt", System.currentTimeMillis())
            .put("watches", JSONArray().apply { watches.forEach { put(it.toJson()) } })
            .put("tombstones", JSONObject().apply { tombstones.forEach { (k, v) -> put(k, v) } })
            .toString()

    internal fun parse(text: String): Snapshot? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optString("format") != FORMAT) return null
        // Újabb, ismeretlen formátumverziót nem fésülünk össze (és nem írunk felül)
        if (json.optInt("version", 1) > 1) return null
        val arr = json.optJSONArray("watches") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { Watch.fromJson(o).sanitized() }.getOrNull() }
        }
        val t = json.optJSONObject("tombstones")
        val tomb = t?.keys()?.asSequence()?.associateWith { t.optLong(it, 0L) }.orEmpty()
        val cur = json.optString("currency", "HUF").takeIf { c -> CURRENCIES.any { it.first == c } } ?: "HUF"
        return Snapshot(list, tomb, cur, skipped = arr.length() - list.size)
    }

    /** Más pénznemben tárolt figyelések célárának átváltása (az árak törlődnek, a következő ellenőrzés frissíti). */
    private fun convertCurrency(list: List<Watch>, from: String, to: String): List<Watch> {
        if (from == to) return list
        val factor = Rates.convert(1.0, from, to)
        return list.map { w ->
            val raw = w.targetPrice * factor
            val target = if (to == "HUF") (Math.round(raw / 100.0) * 100).toInt() else Math.round(raw).toInt()
            w.clearResults().copy(targetPrice = target.coerceAtLeast(1))
        }
    }

    /**
     * Két állapot összefésülése. Figyelésenként: a felhasználói beállítások a később módosított
     * változatból; az árak a frissebben ellenőrzöttből (ha ugyanarra a keresésre vonatkoznak);
     * a törlés nyer, ha későbbi, mint az utolsó módosítás.
     */
    internal fun merge(
        local: List<Watch>,
        localTomb: Map<String, Long>,
        remote: List<Watch>,
        remoteTomb: Map<String, Long>,
    ): Pair<List<Watch>, Map<String, Long>> {
        val cutoff = System.currentTimeMillis() - 120L * 24 * 3_600_000L
        val tomb = (localTomb.keys + remoteTomb.keys).associateWith { maxOf(localTomb[it] ?: 0L, remoteTomb[it] ?: 0L) }
            .filterValues { it > cutoff }
        val byIdRemote = remote.associateBy { it.id }
        val byIdLocal = local.associateBy { it.id }
        // Sorrend: a helyi sorrend, utána a csak távol meglévők
        val ids = local.map { it.id } + remote.map { it.id }.filter { it !in byIdLocal }
        val result = ids.mapNotNull { id ->
            val l = byIdLocal[id]
            val r = byIdRemote[id]
            val base = when {
                l == null -> r!!
                r == null -> l
                r.editedAt > l.editedAt -> r
                else -> l
            }
            val other = if (base === l) r else l
            val deletedAt = tomb[id]
            if (deletedAt != null && deletedAt >= base.editedAt) return@mapNotNull null
            if (other == null) return@mapNotNull base
            mergeResults(base, other)
        }
        return result to tomb
    }

    private fun mergeResults(base: Watch, other: Watch): Watch {
        if (base.searchKey() != other.searchKey()) return base
        val history = (base.history + other.history).distinctBy { it.time }.sortedBy { it.time }.takeLast(120)
        val lowest = listOfNotNull(base.lowestPrice, other.lowestPrice).minOrNull()
        val fresher = if ((other.lastChecked ?: 0L) > (base.lastChecked ?: 0L)) other else base
        val staler = if (fresher === base) other else base
        // A frissebben ellenőrző eszköz döntése számít: ha nála nincs jelzett ár (pl. visszament a
        // célár fölé), az a mérvadó; ha mindkettőnél van, a kisebb (a másik már szólt erről)
        val notified = fresher.lastNotifiedPrice?.let { f -> staler.lastNotifiedPrice?.let { minOf(f, it) } ?: f }
        return base.copy(
            lastPrice = fresher.lastPrice,
            lastChecked = fresher.lastChecked,
            lastError = fresher.lastError,
            offers = fresher.offers,
            sourceStatus = fresher.sourceStatus,
            lowestPrice = lowest,
            history = history,
            market = fresher.market ?: staler.market,
            // Ha már valamelyik eszköz szólt erről az árról, a másik ne szóljon újra
            // Ha a felhasználó épp most kapcsolta át a csengőt (újabb módosítás), az ő törlése nyer
            lastNotifiedPrice = if (base.editedAt > other.editedAt && base.lastNotifiedPrice == null) null else notified,
        )
    }
}
