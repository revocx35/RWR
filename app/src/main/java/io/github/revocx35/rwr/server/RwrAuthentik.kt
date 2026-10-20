package io.github.revocx35.rwr.server

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.revocx35.rwr.R
import io.github.revocx35.rwr.RwrApp
import io.github.revocx35.rwr.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * authentik forward auth for the RWR server (see [Authentik]): one app-wide instance, added to every
 * [PinnedTls] client. [needed] is set when the server wants an authentik sign-in (missing, expired or revoked);
 * the Server section then offers "Sign in with authentik", and a notification asks for it if detection runs
 * in the background.
 */
object RwrAuthentik {
    /** The public OAuth client the owner creates in authentik for this app (ARCHITECTURE.md "authentik"). */
    const val DEFAULT_CLIENT_ID = "rwr-android"
    private const val NOTIFICATION_ID = 7301

    @Volatile var instance: Authentik? = null
        private set
    val needed = MutableStateFlow(false)

    private var appContext: Context? = null
    private var waiting: CompletableDeferred<Uri>? = null
    private var returnTo: Class<out Activity>? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        instance = Authentik(
            KeystoreAuthentikStore(app),
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(false).build(),
        ).also { it.onSignInRequired = { markNeeded() } }
    }

    /** The server answered with authentik's sign-in redirect (from any call or the stream). */
    fun markNeeded() {
        if (needed.value) return
        needed.value = true
        val context = appContext ?: return
        runCatching {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, RwrApp.CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_eye)
                .setContentTitle("Sign in with authentik")
                .setContentText("Your RWR server asks for an authentik sign-in. Open RWR to sign in again.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
    }

    fun redirectUri(context: Context): String = "${context.packageName}:/authentik"

    /**
     * Opens authentik's sign-in for the server at [baseUrl] in the browser and waits for it to come back to
     * [activity]. [location] is where the server redirected to, if known. Throws an IOException with a
     * user-facing message on failure.
     */
    suspend fun signIn(activity: Activity, baseUrl: String, pin: String?, location: HttpUrl?, clientId: String = DEFAULT_CLIENT_ID) {
        val authentik = instance ?: throw java.io.IOException("authentik is not available")
        val site = baseUrl.toHttpUrl()
        val authBase = withContext(Dispatchers.IO) { Authentik.discover(PinnedTls.client(pin), site, location) }
        val pending = authentik.begin(site, authBase, clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }, redirectUri(activity))
        val result = CompletableDeferred<Uri>()
        synchronized(this) {
            waiting?.cancel()
            waiting = result
            returnTo = activity.javaClass
        }
        val intent = Intent(Intent.ACTION_VIEW, pending.authorizeUrl.toString().toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        // The Custom Tabs request is only this extra (androidx.browser's CustomTabsIntent does the same).
        intent.putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
        activity.startActivity(intent)
        val uri = try {
            result.await()
        } finally {
            synchronized(this) { if (waiting === result) waiting = null }
        }
        // Only the query (code, state or error) matters; give it an http(s) shape for OkHttp's parser.
        withContext(Dispatchers.IO) { authentik.complete(pending, "https://callback.invalid/?${uri.encodedQuery ?: ""}".toHttpUrl()) }
        needed.value = false
        activity.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun cancel() {
        synchronized(this) { waiting?.cancel(); waiting = null }
    }

    /** Forgets the sign-in (on unpair) and revokes it at authentik. Blocking. */
    fun signOut() {
        instance?.signOut()
        needed.value = false
    }

    internal fun deliver(uri: Uri): Class<out Activity>? = synchronized(this) {
        val w = waiting ?: return null
        w.complete(uri)
        returnTo
    }
}

/** Receives `<applicationId>:/authentik?code=…&state=…` from the browser and returns to the screen that asked. */
class AuthentikRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val back = if (uri != null && uri.scheme == packageName && uri.path == "/authentik") RwrAuthentik.deliver(uri) else null
        // CLEAR_TOP closes the Custom Tab above that screen and keeps the screens below it.
        startActivity(Intent(this, back ?: MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

/** The sign-in, encrypted with a non-exportable Android Keystore key (like [ServerAccount]'s token). Never throws. */
private class KeystoreAuthentikStore(context: Context) : AuthentikTokenStore {
    private val sp = context.getSharedPreferences("rwr_authentik", Context.MODE_PRIVATE)

    override fun load(): AuthentikTokens? = runCatching {
        val data = Base64.decode(sp.getString("tokens", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        AuthentikTokens.fromJson(String(c.doFinal(data, 12, data.size - 12)))
    }.getOrNull()

    override fun save(tokens: AuthentikTokens?) {
        runCatching {
            if (tokens == null) {
                sp.edit(commit = true) { remove("tokens") }
                return
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            sp.edit(commit = true) { putString("tokens", Base64.encodeToString(c.iv + c.doFinal(tokens.toJson().toByteArray()), Base64.NO_WRAP)) }
        }.onFailure { runCatching { sp.edit(commit = true) { remove("tokens") } } }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "rwr_authentik"
    }
}
