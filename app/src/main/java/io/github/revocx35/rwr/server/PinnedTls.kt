package io.github.revocx35.rwr.server

import android.util.Base64
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * HTTP client for the RWR server. With a pin (self-signed server), the certificate is accepted only if its
 * public key hashes to the pin from the pairing QR code: nothing else, not even a CA-signed certificate for
 * the same name, is trusted. Without a pin, normal CA validation and hostname checks apply.
 * TLS 1.2+ with modern ciphers only; cleartext is blocked app-wide (network_security_config.xml).
 */
object PinnedTls {
    fun client(pin: String?, readTimeoutS: Long = 30): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(readTimeoutS, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
        if (pin != null) {
            val tm = PinTrustManager(pin)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            b.sslSocketFactory(ctx.socketFactory, tm)
            // The pin identifies the server; the certificate may not list the IP/name the phone uses.
            b.hostnameVerifier { _, _ -> true }
        }
        // Server behind authentik forward auth: bearer token + sign-in detection (RwrAuthentik).
        RwrAuthentik.instance?.let {
            b.addInterceptor(it.interceptor)
            b.addNetworkInterceptor(it.networkInterceptor)
        }
        return b.build()
    }

    fun spkiPin(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private class PinTrustManager(private val pin: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
            leaf.checkValidity()
            val got = spkiPin(leaf)
            if (!MessageDigest.isEqual(got.toByteArray(), pin.toByteArray())) {
                throw CertificateException("server certificate does not match the paired key")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates not supported")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
