package hu.repjegy.figyelo

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Google-bejelentkezés Androidon (Google Identity Services, AuthorizationClient).
 * Csak a Drive rejtett alkalmazásadat-területéhez kér hozzáférést. A Google a csomagnév és
 * az aláíró kulcs ujjlenyomata alapján azonosítja az appot (a Google Cloud „REFI” projektben
 * regisztrálva), ezért kliensazonosító nem kell a kódba.
 */
object GoogleAuthAndroid {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    private var activity: WeakReference<ComponentActivity>? = null
    @Volatile private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    @Volatile private var pending: CompletableDeferred<String?>? = null

    fun cancel() {
        pending?.complete(null)
        pending = null
    }
    @Volatile private var lastToken: String? = null

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    /** Az Activity onCreate-jéből: a Google engedélykérő ablakának indítója. */
    fun register(a: ComponentActivity) {
        activity = WeakReference(a)
        launcher = a.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val token = runCatching {
                Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(res.data).accessToken
            }.getOrNull()
            if (token != null) lastToken = token
            pending?.complete(token)
            pending = null
        }
    }

    fun unregister(a: ComponentActivity) {
        if (activity?.get() !== a) return
        activity = null
        launcher = null
        // Újraépülésnél (pl. elforgatás a Google ablaka alatt) az új Activity kapja meg az eredményt
        if (a.isChangingConfigurations) return
        pending?.complete(null)
        pending = null
    }

    suspend fun token(context: Context, interactive: Boolean): String? {
        val result: AuthorizationResult = suspendCancellableCoroutine<AuthorizationResult?> { cont ->
            Identity.getAuthorizationClient(context).authorize(request())
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        } ?: return null
        result.accessToken?.let { if (!result.hasResolution()) { lastToken = it; return it } }
        if (!result.hasResolution() || !interactive) return null
        // Engedélykérés: a Google saját ablaka (fiókválasztás + hozzájárulás)
        val pi = result.pendingIntent ?: return null
        val l = launcher ?: return null
        val deferred = CompletableDeferred<String?>()
        pending?.complete(null)
        pending = deferred
        withContext(Dispatchers.Main) {
            runCatching { l.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
                .onFailure { deferred.complete(null) }
        }
        return withTimeoutOrNull(5 * 60_000L) { deferred.await() }
    }

    /**
     * Kijelentkezés: a hozzáférés visszavonása a Google-nél (különben a következő bejelentkezés
     * fiókválasztó nélkül ugyanazt a fiókot adná vissza), majd a token törlése.
     */
    suspend fun signOut(context: Context) {
        val t = runCatching { token(context, interactive = false) }.getOrNull()
        if (t != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Http.request(
                    "https://oauth2.googleapis.com/revoke", method = "POST",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                        body = "token=" + java.net.URLEncoder.encode(t, "UTF-8"), timeoutMs = 20_000,
                    )
                }
                runCatching { GoogleAuthUtil.clearToken(context, t) }
            }
        }
        invalidate(context)
    }

    /** Lejárt / visszavont token törlése a Google Play-szolgáltatások gyorsítótárából. */
    fun invalidate(context: Context) {
        val t = lastToken ?: return
        lastToken = null
        runCatching { GoogleAuthUtil.clearToken(context, t) }
    }
}
