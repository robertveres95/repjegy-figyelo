package hu.repjegy.figyelo

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
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

    @Volatile private var picker: ActivityResultLauncher<android.content.Intent>? = null
    @Volatile private var pendingPick: CompletableDeferred<String?>? = null

    /**
     * Kijelentkezés után a következő bejelentkezésnél fiókválasztót mutatunk (különben a Google
     * csendben a korábbi fiókot adná vissza, és nem lehetne fiókot váltani).
     */
    private const val PICK = "androidPickAccount"

    private fun request(account: String? = null) = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .apply { if (account != null) setAccount(Account(account, "com.google")) }
        .build()

    private suspend fun pickAccount(): String? {
        val l = picker ?: return null
        val deferred = CompletableDeferred<String?>()
        pendingPick?.complete(null)
        pendingPick = deferred
        val intent = AccountPicker.newChooseAccountIntent(
            AccountPicker.AccountChooserOptions.Builder()
                .setAllowableAccountsTypes(listOf("com.google"))
                .setAlwaysShowAccountPicker(true)
                .build()
        )
        withContext(Dispatchers.Main) {
            runCatching { l.launch(intent) }.onFailure { deferred.complete(null) }
        }
        return withTimeoutOrNull(5 * 60_000L) { deferred.await() }
    }

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
        picker = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            pendingPick?.complete(res.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME))
            pendingPick = null
        }
    }

    fun unregister(a: ComponentActivity) {
        if (activity?.get() !== a) return
        activity = null
        launcher = null
        picker = null
        // Újraépülésnél (pl. elforgatás a Google ablaka alatt) az új Activity kapja meg az eredményt
        if (a.isChangingConfigurations) return
        pending?.complete(null)
        pending = null
        pendingPick?.complete(null)
        pendingPick = null
    }

    suspend fun token(context: Context, interactive: Boolean): String? {
        var account: String? = null
        if (Store.prefs.getBoolean(PICK, false)) {
            if (!interactive) return null
            account = pickAccount() ?: return null
        }
        val result: AuthorizationResult = suspendCancellableCoroutine<AuthorizationResult?> { cont ->
            Identity.getAuthorizationClient(context).authorize(request(account))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        } ?: return null
        result.accessToken?.let {
            if (!result.hasResolution()) {
                lastToken = it
                if (account != null) Store.prefs.edit { putBoolean(PICK, false) }
                return it
            }
        }
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
        val t = withTimeoutOrNull(5 * 60_000L) { deferred.await() }
        if (t != null && account != null) Store.prefs.edit { putBoolean(PICK, false) }
        return t
    }

    /**
     * Kijelentkezés ezen a telefonon: a token törlése. A hozzáférést szándékosan nem vonjuk vissza a
     * Google-nél, mert az a REFI többi eszközén (számítógép, Chrome-bővítmény) is kijelentkeztetne.
     * Fiókot váltani a következő bejelentkezéskor felugró fiókválasztóval lehet.
     */
    suspend fun signOut(context: Context) {
        val t = runCatching { token(context, interactive = false) }.getOrNull()
        if (t != null) withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, t) } }
        invalidate(context)
        // A következő bejelentkezéskor választható legyen másik fiók
        Store.prefs.edit { putBoolean(PICK, true) }
    }

    /** Lejárt / visszavont token törlése a Google Play-szolgáltatások gyorsítótárából. */
    fun invalidate(context: Context) {
        val t = lastToken ?: return
        lastToken = null
        runCatching { GoogleAuthUtil.clearToken(context, t) }
    }
}
