package io.github.revocx35.rwr.server

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** authentik forward auth against a fake NPM + outpost + authentik on one MockWebServer. */
class AuthentikTest {
    private lateinit var server: MockWebServer
    private val valid = mutableSetOf<String>()
    private val refresh = mutableMapOf("R1" to "2", "R2" to "3")
    private var challenge: String? = null
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private var now = 1_000_000L

    private class MemStore : AuthentikTokenStore {
        var t: AuthentikTokens? = null
        override fun load() = t
        override fun save(tokens: AuthentikTokens?) { t = tokens }
    }

    private class MemJar : CookieJar {
        val cookies = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { this.cookies += cookies }
        override fun loadForRequest(url: HttpUrl) = cookies.toList()
    }

    private lateinit var store: MemStore
    private lateinit var jar: MemJar
    private lateinit var ak: Authentik
    private lateinit var client: OkHttpClient
    private lateinit var site: HttpUrl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val base = "http://${server.hostName}:${server.port}"
                val path = request.url.encodedPath
                return when {
                    path == "/outpost.goauthentik.io/start" -> redirect("$base/application/o/authorize/?client_id=proxy&redirect_uri=${base}/outpost.goauthentik.io/callback%3FX-authentik-auth-callback%3Dtrue")
                    path == "/application/o/token/" -> token(request)
                    path.startsWith("/api/") -> {
                        val auth = request.headers["Authorization"]?.removePrefix("Bearer ")
                        if (auth != null && auth in valid) {
                            MockResponse.Builder().code(200)
                                .addHeader("Set-Cookie", "authentik_proxy_abc=sess; Path=/")
                                .addHeader("Set-Cookie", "app_session=keep; Path=/")
                                .body("""{"ok":true}""").build()
                        } else {
                            redirect("/outpost.goauthentik.io/start?rd=$base$path")
                        }
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        site = server.url("/")
        store = MemStore()
        jar = MemJar()
        ak = Authentik(store, OkHttpClient(), clock = { now })
        client = OkHttpClient.Builder().cookieJar(jar).followRedirects(false)
            .addInterceptor(ak.interceptor).addNetworkInterceptor(ak.networkInterceptor).build()
    }

    @After
    fun tearDown() = server.close()

    private fun redirect(to: String) = MockResponse.Builder().code(302).addHeader("Location", to).build()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun token(r: RecordedRequest): MockResponse {
        val form = r.body!!.utf8().split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return when (form["grant_type"]) {
            "authorization_code" -> {
                val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(form["code_verifier"]!!.toByteArray()))
                if (form["code"] != "C1" || hash != challenge || form["client_id"] != "rwr-android") json("""{"error":"invalid_grant"}""", 400)
                else { valid += "A1"; json("""{"access_token":"A1","refresh_token":"R1","expires_in":600,"token_type":"Bearer"}""") }
            }
            "refresh_token" -> {
                val n = refresh.remove(form["refresh_token"]) ?: return json("""{"error":"invalid_grant"}""", 400)
                valid += "A$n"
                json("""{"access_token":"A$n","refresh_token":"R$n","expires_in":600}""")
            }
            else -> json("""{"error":"unsupported_grant_type"}""", 400)
        }
    }

    private fun get(path: String, auth: String? = null): Int {
        val b = Request.Builder().url(site.resolve(path)!!)
        auth?.let { b.header("Authorization", it) }
        return client.newCall(b.build()).execute().use { it.code }
    }

    private fun signIn() {
        val pending = ak.begin(site, Authentik.discover(client, site, null), "rwr-android", "io.github.revocx35.rwr:/authentik")
        challenge = pending.authorizeUrl.queryParameter("code_challenge")
        ak.complete(pending, "http://cb/?code=C1&state=${pending.state}".toHttpUrl())
    }

    @Test
    fun signInRedirectBecomesAuthentikRequired() {
        try {
            get("/api/state")
            fail("expected AuthentikRequiredException")
        } catch (e: AuthentikRequiredException) {
            assertEquals("/outpost.goauthentik.io/start", e.location?.encodedPath)
            val authBase = Authentik.discover(client, site, e.location)
            assertEquals(site.toString(), authBase.toString())
        }
    }

    @Test
    fun pkceSignInStoresTokensAndSendsBearer() {
        signIn()
        val pending = ak.begin(site, site, "rwr-android", "x:/authentik")
        assertEquals("S256", pending.authorizeUrl.queryParameter("code_challenge_method"))
        assertEquals("openid profile offline_access", pending.authorizeUrl.queryParameter("scope"))
        assertEquals("A1", store.t?.accessToken)
        assertTrue(ak.signedInFor(site.resolve("/api/x")!!))
        assertFalse(store.t.toString().contains("A1"))

        assertEquals(200, get("/api/state", auth = "Bearer app-token"))
        val r = seen.last { it.url.encodedPath == "/api/state" }
        assertEquals("Bearer A1", r.headers["Authorization"])
        assertEquals("Bearer app-token", r.headers["X-App-Authorization"])
        // The outpost's session cookie never reaches the jar; the app's own cookie does.
        assertEquals(listOf("app_session"), jar.cookies.map { it.name })
    }

    @Test
    fun rejectsWrongState() {
        val pending = ak.begin(site, site, "rwr-android", "x:/authentik")
        try {
            ak.complete(pending, "http://cb/?code=C1&state=other".toHttpUrl())
            fail("expected AuthentikException")
        } catch (e: AuthentikException) {
            assertEquals("bad_state", e.code)
        }
        assertNull(store.t)
    }

    @Test
    fun refreshesBeforeExpiryAndAfterEarlyRevocation() {
        signIn()
        now += 590_000 // within a minute of expiry
        assertEquals(200, get("/api/a"))
        assertEquals("A2", store.t?.accessToken)
        assertEquals("R2", store.t?.refreshToken)
        valid.remove("A2") // revoked at authentik before it expired
        assertEquals(200, get("/api/b"))
        assertEquals("A3", store.t?.accessToken)
    }

    @Test
    fun deadRefreshTokenAsksForSignInAgain() {
        signIn()
        refresh.clear()
        var asked = false
        ak.onSignInRequired = { asked = true }
        now += 600_000
        try {
            get("/api/a")
            fail("expected AuthentikRequiredException")
        } catch (e: AuthentikRequiredException) {
            assertTrue(asked)
            assertNull(store.t)
            assertNull(ak.tokens())
        }
    }

    @Test
    fun neverSendsTheTokenToAnotherHost() {
        signIn()
        val other = OkHttpClient.Builder().addInterceptor(ak.interceptor).build()
        val otherServer = MockWebServer().apply { start() }
        otherServer.enqueue(MockResponse.Builder().code(200).build())
        other.newCall(Request.Builder().url(otherServer.url("/x")).build()).execute().close()
        assertNull(otherServer.takeRequest().headers["Authorization"])
        otherServer.close()
    }

    @Test
    fun tokensRoundTripAsJson() {
        val t = AuthentikTokens("https://a.example", "https://auth.example/", "c", "at", "rt", 42)
        assertEquals(t, AuthentikTokens.fromJson(t.toJson()))
        assertNull(AuthentikTokens.fromJson("{}"))
    }
}
