package hu.repjegy.figyelo

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Google-bejelentkezés Windowson: a szabványos „telepített alkalmazás” folyamat (OAuth 2.0,
 * PKCE, visszairányítás a gépen futó helyi címre). A böngészőben jelentkezel be; az app csak
 * egy frissítő kulcsot tárol, amivel később magától kér új hozzáférést.
 *
 * Az asztali kliens „titka” a Google szerint nem titok (minden telepített példányban benne van);
 * csak azért kódolt, hogy a kódkeresők ne jelezzék tévesen kiszivárgott jelszónak.
 */
object GoogleAuthDesktop {
    private const val CLIENT_ID = "788600778570-ka2snrvhttprnd3k3628q13o3ur4ehkk.apps.googleusercontent.com"
    private const val SECRET_ENC = "WXhLaE9Xb1ZpSnFmbkRiR2V5Y2dyemRjRW9YWi1YUFNDT0c="
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    private val secret: String by lazy { String(Base64.getDecoder().decode(SECRET_ENC)).reversed() }

    @Volatile private var accessToken: String? = null
    @Volatile private var waiting: CompletableFuture<Map<String, String>>? = null

    /** A böngészős bejelentkezés várakozásának megszakítása. */
    fun cancel() {
        waiting?.completeExceptionally(java.util.concurrent.CancellationException("megszakítva"))
    }
    @Volatile private var expiresAt = 0L

    suspend fun token(interactive: Boolean): String? = withContext(Dispatchers.IO) {
        accessToken?.let { if (System.currentTimeMillis() < expiresAt - 60_000) return@withContext it }
        val refresh = DesktopPrefs.getString("googleRefresh", null)?.takeIf { it.isNotBlank() }
        if (refresh != null) {
            refreshAccess(refresh)?.let { return@withContext it }
        }
        if (!interactive) return@withContext null
        signIn()
    }

    fun invalidate() {
        accessToken = null
        expiresAt = 0
    }

    /**
     * Kijelentkezés ezen a gépen: a frissítő kulcs törlése. A Google-nél szándékosan nem vonjuk vissza
     * a hozzáférést, mert az a REFI többi eszközén (telefon, Chrome-bővítmény) is kijelentkeztetne.
     * (A fiókválasztót a következő bejelentkezés úgyis megmutatja.)
     */
    fun signOut() {
        invalidate()
        DesktopPrefs.edit { putString("googleRefresh", "") }
    }

    private fun refreshAccess(refresh: String): String? {
        val res = runCatching {
            Http.request(
                TOKEN_URL, method = "POST",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = form("client_id" to CLIENT_ID, "client_secret" to secret, "refresh_token" to refresh, "grant_type" to "refresh_token"),
                timeoutMs = 20_000,
            )
        }.getOrNull() ?: return null
        val revoked = res.code == 401 ||
            (res.code == 400 && runCatching { JSONObject(res.body).optString("error") }.getOrNull() == "invalid_grant")
        if (revoked) {
            // Visszavont vagy lejárt frissítő kulcs: újra be kell jelentkezni (más 400-as hibánál – pl. egy
            // proxy miatt – megtartjuk, és később újrapróbáljuk)
            DesktopPrefs.edit { putString("googleRefresh", "") }
            return null
        }
        if (res.code !in 200..299) return null
        return store(JSONObject(res.body))
    }

    private fun store(json: JSONObject): String? {
        val token = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        accessToken = token
        expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { r -> DesktopPrefs.edit { putString("googleRefresh", r) } }
        return token
    }

    /** Böngészős bejelentkezés: helyi visszahívási cím + PKCE, legfeljebb 5 percig vár. */
    private fun signIn(): String? {
        val random = SecureRandom()
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also(random::nextBytes))
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val result = CompletableFuture<Map<String, String>>()
        waiting?.completeExceptionally(java.util.concurrent.CancellationException("új bejelentkezés"))
        waiting = result
        server.createContext("/") { ex ->
            val params = (ex.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }.mapNotNull {
                val (k, v) = it.split('=', limit = 2)
                runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrNull()
            }.toMap()
            // Csak a saját (helyes state-ű) visszahívás számít; egy böngésző-előtöltés, favicon-kérés
            // vagy más helyi program kérése nem szakíthatja meg a bejelentkezést
            val ours = params["state"] == state && (params["code"] != null || params["error"] != null)
            if (!ours) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return@createContext
            }
            val ok = params["code"] != null && params["state"] == state
            val html = if (ok) "<h2>REFI: sikeres bejelentkezés.</h2><p>Ezt a lapot bezárhatod, és visszatérhetsz az apphoz.</p>"
            else "<h2>REFI: a bejelentkezés nem sikerült.</h2><p>Próbáld újra az appban.</p>"
            val bytes = ("<!doctype html><meta charset=utf-8><body style='font-family:sans-serif;padding:40px'>$html").toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            result.complete(params)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(
                "client_id" to CLIENT_ID,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to SCOPE,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "access_type" to "offline",
                // Fiókválasztó is: kijelentkezés után más fiókkal is be lehessen lépni
                "prompt" to "select_account consent",
                "state" to state,
            )
            Platform.current.openUrl(url)
            val params = runCatching { result.get(5, TimeUnit.MINUTES) }.getOrNull() ?: return null
            if (params["state"] != state) return null
            val code = params["code"] ?: return null
            val res = Http.request(
                TOKEN_URL, method = "POST",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = form(
                    "client_id" to CLIENT_ID, "client_secret" to secret, "code" to code,
                    "code_verifier" to verifier, "redirect_uri" to redirect, "grant_type" to "authorization_code",
                ),
                timeoutMs = 20_000,
            )
            if (res.code !in 200..299) return null
            return store(JSONObject(res.body))
        } finally {
            server.stop(0)
            if (waiting === result) waiting = null
        }
    }

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}
