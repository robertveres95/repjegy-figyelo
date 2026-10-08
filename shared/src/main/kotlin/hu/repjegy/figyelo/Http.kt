package hu.repjegy.figyelo

import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Ha egy forrás az adott útra nem értelmezhető (pl. business osztály egy fapadosnál). */
class SkipSourceException(message: String) : Exception(message)

/** Olyan hiba, aminél nincs értelme a többi repülőtér-párral próbálkozni (pl. rossz kulcs, letiltás). */
class FatalSourceException(message: String) : IOException(message)

/** Egyszerű HTTP-kliens böngészőszerű fejlécekkel és közös sütitárral. */
object Http {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Safari/537.36"

    class Response(val code: Int, val body: String)

    private val cookieManager: CookieManager by lazy {
        CookieManager(null, CookiePolicy.ACCEPT_ALL).also { CookieHandler.setDefault(it) }
    }

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 45_000,
    ): Response {
        cookieManager // sütitár telepítése az első híváskor
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "en-GB,en;q=0.9,hu;q=0.8")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    fun cookie(host: String, name: String): String? =
        cookieManager.cookieStore.get(URI("https://$host")).firstOrNull { it.name == name }?.value

    fun setCookie(host: String, domain: String, name: String, value: String) {
        val cookie = HttpCookie(name, value).apply {
            this.domain = domain
            path = "/"
            version = 0
            maxAge = 60L * 60 * 24 * 365
        }
        cookieManager.cookieStore.add(URI("https://$host"), cookie)
    }
}
