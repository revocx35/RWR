package io.github.revocx35.rwr.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * authentik forward-auth support (shared, identical code in wa_logger, insta_logger, web-ip-phone and RWR;
 * see ARCHITECTURE.md "authentik").
 *
 * A server can sit behind a reverse proxy (e.g. Nginx Proxy Manager) that asks authentik's outpost about every
 * request. Browsers get a login page; this app instead signs in once with OAuth 2 (authorization code + PKCE in
 * the system browser) against a public authentik client, keeps the refresh token (encrypted by the caller's
 * [AuthentikTokenStore]) and sends a short-lived access token as `Authorization: Bearer` on every request to that
 * server. If the app has its own `Authorization` header (an app token), it moves to `X-App-Authorization`; the
 * proxy puts it back before the request reaches the server. The server's own login stays as it is.
 */

/**
 * The server answered with authentik's sign-in redirect: this app has no (valid) authentik sign-in for it.
 * An [IOException] so it is safe to throw on OkHttp's threads. [location] is where the proxy redirected to.
 */
class AuthentikRequiredException(val location: HttpUrl?) : IOException("This server is protected by authentik. Sign in with authentik to continue.")

/** The authentik sign-in for one server ([site] = its origin). */
data class AuthentikTokens(
    val site: String,
    val authBase: String,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
) {
    fun toJson(): String = buildJsonObject {
        put("site", site); put("authBase", authBase); put("clientId", clientId)
        put("accessToken", accessToken); put("refreshToken", refreshToken); put("expiresAtMs", expiresAtMs)
    }.toString()

    override fun toString(): String = "AuthentikTokens(site=$site, authBase=$authBase, clientId=$clientId)"

    companion object {
        fun fromJson(text: String): AuthentikTokens? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            fun s(k: String) = o[k]!!.jsonPrimitive.content
            AuthentikTokens(s("site"), s("authBase"), s("clientId"), s("accessToken"), s("refreshToken"), o["expiresAtMs"]!!.jsonPrimitive.longOrNull!!)
        }.getOrNull()
    }
}

/** Persists the sign-in; implementations encrypt it at rest and must never throw. */
interface AuthentikTokenStore {
    fun load(): AuthentikTokens?
    fun save(tokens: AuthentikTokens?)
}

/** A sign-in started with [Authentik.begin], waiting for the browser to come back to [redirectUri]. */
class AuthentikPending(
    val site: String,
    val authBase: HttpUrl,
    val clientId: String,
    val redirectUri: String,
    val state: String,
    internal val verifier: String,
    val authorizeUrl: HttpUrl,
)

class AuthentikException(val code: String, message: String) : IOException(message)

/**
 * One app-wide instance. Add [interceptor] and [networkInterceptor] to the OkHttp client that talks to the
 * server; [tokenHttp] is a plain client for authentik itself (system trust, no interceptors).
 */
class Authentik(
    private val store: AuthentikTokenStore,
    private val tokenHttp: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var current: AuthentikTokens? = null

    /** Called (on a background thread) when the sign-in is gone: refresh token expired or revoked. */
    @Volatile var onSignInRequired: (() -> Unit)? = null

    fun tokens(): AuthentikTokens? {
        if (!loaded) synchronized(lock) {
            if (!loaded) {
                current = runCatching { store.load() }.getOrNull()
                loaded = true
            }
        }
        return current
    }

    /** Signed in for the server at [site] (any URL on it)? */
    fun signedInFor(site: HttpUrl): Boolean = tokens()?.site == site.akOrigin()

    private fun set(t: AuthentikTokens?) {
        synchronized(lock) {
            current = t
            loaded = true
            runCatching { store.save(t) }
        }
    }

    /** Starts a sign-in: open [AuthentikPending.authorizeUrl] in the browser. */
    fun begin(site: HttpUrl, authBase: HttpUrl, clientId: String, redirectUri: String): AuthentikPending {
        val verifier = randomToken(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomToken(24)
        val url = authBase.newBuilder().encodedPath("/application/o/authorize/")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", "openid profile offline_access")
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
        return AuthentikPending(site.akOrigin(), authBase, clientId, redirectUri, state, verifier, url)
    }

    /**
     * Finishes [pending] with the redirect the browser delivered (blocking: call off the main thread).
     * Checks `state`, then trades the code for tokens and stores them.
     */
    fun complete(pending: AuthentikPending, redirect: HttpUrl) {
        val error = redirect.queryParameter("error")
        if (error != null) throw AuthentikException(error, if (error == "access_denied") "authentik denied access." else "authentik sign-in failed ($error).")
        val state = redirect.queryParameter("state") ?: ""
        if (!MessageDigest.isEqual(state.toByteArray(), pending.state.toByteArray())) {
            throw AuthentikException("bad_state", "The sign-in answer did not match. Try again.")
        }
        val code = redirect.queryParameter("code") ?: throw AuthentikException("no_code", "authentik sent no authorization code.")
        val json = token(pending.authBase, FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", pending.redirectUri)
            .add("client_id", pending.clientId)
            .add("code_verifier", pending.verifier)
            .build())
        set(tokensFrom(json, pending.site, pending.authBase.toString(), pending.clientId, previousRefresh = null))
    }

    /** Forgets the sign-in and revokes the refresh token at authentik (best effort; blocking). */
    fun signOut() {
        val t = tokens() ?: return
        set(null)
        runCatching {
            val base = t.authBase.toHttpUrlOrNull() ?: return
            val req = Request.Builder().url(base.resolve("/application/o/revoke/")!!)
                .post(FormBody.Builder().add("token", t.refreshToken).add("token_type_hint", "refresh_token").add("client_id", t.clientId).build())
                .build()
            tokenHttp.newCall(req).execute().close()
        }
    }

    /** A valid access token for [t], refreshed when it expires within a minute (or when [force]). */
    private fun fresh(t: AuthentikTokens, force: Boolean): AuthentikTokens {
        if (!force && t.expiresAtMs - clock() > 60_000) return t
        synchronized(lock) {
            val now = current ?: throw AuthentikRequiredException(null)
            // Another thread refreshed while we waited.
            if (now.accessToken != t.accessToken) return now
            val json = try {
                token(now.authBase.toHttpUrlOrNull() ?: throw AuthentikException("bad_config", "Bad authentik address."), FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", now.refreshToken)
                    .add("client_id", now.clientId)
                    .build())
            } catch (e: AuthentikException) {
                if (e.code == "invalid_grant" || e.code == "invalid_client") {
                    current = null
                    runCatching { store.save(null) }
                    runCatching { onSignInRequired?.invoke() }
                    throw AuthentikRequiredException(null)
                }
                throw e
            }
            val next = tokensFrom(json, now.site, now.authBase, now.clientId, previousRefresh = now.refreshToken)
            current = next
            runCatching { store.save(next) }
            return next
        }
    }

    private fun token(authBase: HttpUrl, form: FormBody): JsonObject {
        val req = Request.Builder().url(authBase.resolve("/application/o/token/")!!).header("Accept", "application/json").post(form).build()
        tokenHttp.newCall(req).execute().use { res ->
            val text = res.body.string()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!res.isSuccessful || json == null) {
                val code = json?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${res.code}"
                throw AuthentikException(code, "authentik refused the sign-in ($code).")
            }
            return json
        }
    }

    private fun tokensFrom(json: JsonObject, site: String, authBase: String, clientId: String, previousRefresh: String?): AuthentikTokens {
        val access = json["access_token"]?.jsonPrimitive?.contentOrNull ?: throw AuthentikException("no_token", "authentik sent no access token.")
        val refresh = json["refresh_token"]?.jsonPrimitive?.contentOrNull ?: previousRefresh
            ?: throw AuthentikException("no_refresh", "authentik sent no refresh token (is offline_access allowed for this client?).")
        val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 300
        return AuthentikTokens(site, authBase, clientId, access, refresh, clock() + expiresIn * 1000)
    }

    /**
     * Application interceptor: adds the access token to requests for the signed-in server and, when the proxy
     * still wants a sign-in (token expired or revoked early), refreshes once and retries.
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        val t = tokens()
        if (t == null || req.url.akOrigin() != t.site) return@Interceptor chain.proceed(req)
        try {
            chain.proceed(withToken(req, fresh(t, force = false)))
        } catch (e: AuthentikRequiredException) {
            val now = tokens() ?: throw e
            chain.proceed(withToken(req, fresh(now, force = true)))
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Nothing but IOException may leave an OkHttp interceptor (it would crash the app).
            throw IOException("authentik: ${e.javaClass.simpleName}", e)
        }
    }

    /**
     * Network interceptor: turns authentik's sign-in redirect into [AuthentikRequiredException] (instead of
     * following it into an HTML login page) and keeps the outpost's session cookie out of the app's cookie jar,
     * so every request is checked with the token (a revoked token stops working within minutes).
     */
    val networkInterceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        val cookie = req.header("Cookie")
        val clean = if (cookie != null && cookie.contains(PROXY_COOKIE)) {
            val kept = cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(PROXY_COOKIE) }
            req.newBuilder().apply { if (kept.isEmpty()) removeHeader("Cookie") else header("Cookie", kept.joinToString("; ")) }.build()
        } else {
            req
        }
        val res = chain.proceed(clean)
        val location = signInRedirect(res)
        if (location != null) {
            res.close()
            throw AuthentikRequiredException(location)
        }
        if (res.headers("Set-Cookie").none { it.startsWith(PROXY_COOKIE) }) return@Interceptor res
        val headers = Headers.Builder()
        for ((name, value) in res.headers) {
            if (!(name.equals("Set-Cookie", ignoreCase = true) && value.startsWith(PROXY_COOKIE))) headers.add(name, value)
        }
        res.newBuilder().headers(headers.build()).build()
    }

    companion object {
        const val OUTPOST = "/outpost.goauthentik.io/"
        private const val PROXY_COOKIE = "authentik_proxy_"
        private val random = SecureRandom()

        private fun randomToken(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.let(::b64url)
        private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        /** The sign-in URL when [res] is authentik asking for a login (nginx/NPM style or a direct authorize redirect). */
        fun signInRedirect(res: Response): HttpUrl? {
            if (res.code !in setOf(301, 302, 303, 307, 308)) return null
            val loc = res.header("Location")?.let { res.request.url.resolve(it) } ?: return null
            val outpostStart = loc.host == res.request.url.host && loc.encodedPath.startsWith("${OUTPOST}start")
            val authorize = loc.encodedPath == "/application/o/authorize/" &&
                (loc.queryParameter("redirect_uri") ?: "").contains(OUTPOST.removeSuffix("/") + "/callback")
            return if (outpostStart || authorize) loc else null
        }

        /**
         * Where authentik lives, found by following the proxy's redirect from [location] (the URL in
         * [AuthentikRequiredException]) one step without cookies. [client] reaches the server (same TLS trust).
         * Blocking: call off the main thread. Only https is accepted, except for local addresses.
         */
        fun discover(client: OkHttpClient, site: HttpUrl, location: HttpUrl?): HttpUrl {
            var loc = location ?: site.resolve("${OUTPOST}start?rd=${site.akOrigin()}/")!!
            if (loc.encodedPath != "/application/o/authorize/") {
                val c = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
                val req = Request.Builder().url(loc).header("Accept", "text/html").build()
                loc = try {
                    c.newCall(req).execute().use { res -> res.header("Location")?.let { res.request.url.resolve(it) } }
                } catch (e: AuthentikRequiredException) {
                    e.location // our own networkInterceptor recognised the authorize redirect
                } ?: throw AuthentikException("not_authentik", "The server did not send the authentik sign-in address.")
            }
            if (loc.encodedPath != "/application/o/authorize/") throw AuthentikException("not_authentik", "The server did not send the authentik sign-in address.")
            if (!loc.isHttps && !akLocalHost(loc.host)) throw AuthentikException("insecure", "authentik must be reached over HTTPS.")
            return loc.newBuilder().encodedPath("/").query(null).fragment(null).build()
        }

        /** `scheme://host[:port]` (default port left out), like a browser's Origin. */
        internal fun HttpUrl.akOrigin(): String {
            val h = if (host.contains(':')) "[$host]" else host
            return if (port == HttpUrl.defaultPort(scheme)) "$scheme://$h" else "$scheme://$h:$port"
        }

        private fun akLocalHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            val p = h.split('.').mapNotNull { it.toIntOrNull() }
            return p.size == 4 && (p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168))
        }

        private fun withToken(req: Request, t: AuthentikTokens): Request {
            val b = req.newBuilder()
            val own = req.header("Authorization")
            if (own != null && own != "Bearer ${t.accessToken}") b.header("X-App-Authorization", own)
            return b.header("Authorization", "Bearer ${t.accessToken}").build()
        }
    }
}
