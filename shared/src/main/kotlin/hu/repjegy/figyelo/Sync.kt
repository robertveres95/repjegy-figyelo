package hu.repjegy.figyelo

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    )

    val state = MutableStateFlow(State())

    private val mutex = Mutex()
    private var pending: Job? = null

    /** Induláskor: a mentett állapot betöltése. */
    fun load() {
        val p = Store.prefs
        state.value = State(
            enabled = p.getBoolean("syncOn", false),
            account = p.getString("syncAccount", null),
            lastSync = p.getLong("syncLast", 0L).takeIf { it > 0 },
        )
    }

    /** Bejelentkezés (ha kell, a Google ablakával) és az első szinkronizálás. */
    suspend fun enable(): Boolean {
        state.value = state.value.copy(running = true, error = null)
        val token = runCatching { Platform.current.googleAccessToken(interactive = true) }.getOrNull()
        if (token == null) {
            state.value = state.value.copy(running = false, error = "A bejelentkezés nem sikerült vagy megszakadt.")
            return false
        }
        val email = runCatching { accountEmail(token) }.getOrNull()
        Store.prefs.edit {
            putBoolean("syncOn", true)
            email?.let { putString("syncAccount", it) }
        }
        state.value = state.value.copy(enabled = true, account = email, running = false)
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
            state.value = state.value.copy(running = true)
            val r = runCatching { syncOnce() }
            val now = System.currentTimeMillis()
            state.value = if (r.isSuccess) {
                Store.prefs.edit { putLong("syncLast", now) }
                state.value.copy(running = false, lastSync = now, error = null)
            } else {
                state.value.copy(running = false, error = "Szinkronizálási hiba: ${r.exceptionOrNull()?.message?.take(120)}")
            }
            r.isSuccess
        }
    }

    private suspend fun syncOnce() {
        val token = Platform.current.googleAccessToken(interactive = false)
            ?: throw IOException("Jelentkezz be újra a Google-fiókkal (Beállítások)")
        val auth = mapOf("Authorization" to "Bearer $token")

        // 1. Meglévő fájl keresése a rejtett mappában
        val q = URLEncoder.encode("name='$FILE_NAME'", "UTF-8")
        val list = Http.request("$API/files?spaces=appDataFolder&q=$q&fields=files(id)&pageSize=10", headers = auth, timeoutMs = 30_000)
        check401(list.code)
        if (list.code !in 200..299) throw IOException("Drive HTTP ${list.code}")
        val fileId = JSONObject(list.body).optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }

        // 2. Letöltés és összefésülés
        val currency = Store.settings.value.currency
        var remote: Snapshot? = null
        if (fileId != null) {
            val dl = Http.request("$API/files/$fileId?alt=media", headers = auth, timeoutMs = 30_000)
            check401(dl.code)
            if (dl.code !in 200..299) throw IOException("Drive HTTP ${dl.code}")
            remote = parse(dl.body)
        }
        val localWatches = Store.watches.value
        val localTomb = Store.tombstones()
        val merged = if (remote != null) {
            val remoteWatches = convertCurrency(remote.watches, remote.currency, currency)
            merge(localWatches, localTomb, remoteWatches, remote.tombstones)
        } else {
            localWatches to localTomb
        }
        Store.replaceFromSync(merged.first, merged.second)

        // 3. Feltöltés (csak ha változott a felhőben lévőhöz képest)
        val body = serialize(merged.first, merged.second, currency)
        if (remote != null && remote.currency == currency && remote.watches == merged.first && remote.tombstones == merged.second) return
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
            // A HttpURLConnection nem ismeri a PATCH-et; a Google API-k elfogadják így is
            Http.request(
                "$UPLOAD/files/$fileId?uploadType=media&fields=id", method = "POST",
                headers = auth + mapOf("Content-Type" to "application/json; charset=UTF-8", "X-HTTP-Method-Override" to "PATCH"),
                body = body, timeoutMs = 30_000,
            )
        }
        check401(res.code)
        if (res.code !in 200..299) throw IOException("Drive feltöltés HTTP ${res.code}")
    }

    private fun check401(code: Int) {
        if (code == 401 || code == 403) {
            runCatching { Platform.current.googleInvalidateToken() }
            throw IOException("A Google-hozzáférés lejárt – jelentkezz be újra (Beállítások)")
        }
    }

    private fun accountEmail(token: String): String? {
        val r = Http.request("$API/about?fields=user(emailAddress)", headers = mapOf("Authorization" to "Bearer $token"), timeoutMs = 20_000)
        if (r.code !in 200..299) return null
        return JSONObject(r.body).optJSONObject("user")?.optString("emailAddress")?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------ formátum és összefésülés

    internal class Snapshot(val watches: List<Watch>, val tombstones: Map<String, Long>, val currency: String)

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
        val arr = json.optJSONArray("watches") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { Watch.fromJson(o).sanitized() }.getOrNull() }
        }
        val t = json.optJSONObject("tombstones")
        val tomb = t?.keys()?.asSequence()?.associateWith { t.optLong(it, 0L) }.orEmpty()
        val cur = json.optString("currency", "HUF").takeIf { c -> CURRENCIES.any { it.first == c } } ?: "HUF"
        return Snapshot(list, tomb, cur)
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
        val tomb = (localTomb.keys + remoteTomb.keys).associateWith { maxOf(localTomb[it] ?: 0L, remoteTomb[it] ?: 0L) }
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
        val notified = listOfNotNull(base.lastNotifiedPrice, other.lastNotifiedPrice).minOrNull()
        val lowest = listOfNotNull(base.lowestPrice, other.lowestPrice).minOrNull()
        val fresher = if ((other.lastChecked ?: 0L) > (base.lastChecked ?: 0L)) other else base
        return base.copy(
            lastPrice = fresher.lastPrice,
            lastChecked = fresher.lastChecked,
            lastError = fresher.lastError,
            offers = fresher.offers,
            sourceStatus = fresher.sourceStatus,
            lowestPrice = lowest,
            history = history,
            // Ha már valamelyik eszköz szólt erről az árról, a másik ne szóljon újra
            lastNotifiedPrice = if (base.notify) notified else base.lastNotifiedPrice,
        )
    }
}
