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

/**
 * A forrás egyes kérései (reptérpárok, rugalmas dátumok) hibáztak, de a többi talált valamit:
 * az ajánlatok használhatók, de a forrás nem „válaszolt teljesen” – így egy hiányzó (lehet, hogy
 * épp a legolcsóbb) ár miatt nem töröljük a korábbit, és nem szólunk újra ugyanarról az árról.
 */
class PartialSourceException(
    val offers: List<Offer>,
    val failed: Int,
    val total: Int,
    cause: Exception?,
    /** Letiltás miatt maradt félbe: a külső ciklus (pl. rugalmas dátumok) se próbálkozzon tovább. */
    val fatal: Boolean = false,
) :
    IOException(
        trs("részleges válasz: $failed/$total kérés hibázott", "partial answer: $failed/$total requests failed") +
            (cause?.message?.let { " ($it)" } ?: "").take(120),
        cause,
    )

/** Több kérés (reptérpár vagy dátum) eredményének összegzése: hiba, részleges vagy teljes. */
internal inline fun <K> collectOffers(keys: List<K>, betweenEach: () -> Unit = {}, search: (K) -> List<Offer>): List<Offer> {
    val results = mutableListOf<Offer>()
    var firstError: Exception? = null
    var failed = 0
    var ok = 0
    for ((index, key) in keys.withIndex()) {
        if (index > 0) betweenEach()
        try {
            results += search(key)
            ok++
        } catch (e: PartialSourceException) {
            results += e.offers
            ok++
            failed++
            if (firstError == null) firstError = e
            if (e.fatal) throw PartialSourceException(results, failed + keys.size - index - 1, keys.size, e, fatal = true)
        } catch (e: SkipSourceException) {
            throw e
        } catch (e: FatalSourceException) {
            // Letiltásnál a többit nem próbáljuk; ha volt már találat, az részleges eredmény
            if (ok == 0) throw e
            throw PartialSourceException(results, failed + keys.size - index, keys.size, e, fatal = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed++
            if (firstError == null) firstError = e
        }
    }
    if (ok == 0 && firstError != null) throw firstError
    if (failed > 0) throw PartialSourceException(results, failed, keys.size, firstError)
    return results
}

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
        // Kapcsolódásra elég 15 mp (ami addig nem jön létre, nem is fog); így egy elérhetetlen
        // szerver nem emészti fel a háttérfutás idejét
        conn.connectTimeout = minOf(timeoutMs, 15_000)
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
            val text = stream?.use { readLimited(it) } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** Legfeljebb 12 MB-ot olvas (egy elromlott vagy rosszindulatú válasz ne fogyassza el a memóriát). */
    private fun readLimited(input: java.io.InputStream): String {
        val limit = 12 * 1024 * 1024
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException(trs("Túl nagy válasz", "Response too large"))
        }
        return out.toString(Charsets.UTF_8.name())
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
