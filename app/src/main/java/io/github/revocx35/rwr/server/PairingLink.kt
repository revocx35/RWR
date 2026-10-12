package io.github.revocx35.rwr.server

import java.net.URI
import java.net.URLDecoder

/**
 * The QR code shown by the server's web UI: `rwr://pair?u=<https url>&c=<one-time code>&p=<key pin>`.
 * The pin (base64url SHA-256 of the server certificate's public key) is how the app trusts a self-signed
 * certificate without trusting anyone else. Pure Kotlin so it is unit-tested.
 */
data class PairingLink(val baseUrl: String, val code: String, val pin: String?) {
    companion object {
        private val PIN = Regex("^[A-Za-z0-9_-]{43}$")
        private val CODE = Regex("^[A-Za-z0-9_-]{10,100}$")

        /** Returns null for anything that isn't a well-formed RWR pairing link with an https server URL. */
        fun parse(text: String): PairingLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "rwr" || uri.host != "pair") return null
            val q = (uri.rawQuery ?: return null).split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val base = q["u"]?.trimEnd('/') ?: return null
            val code = q["c"] ?: return null
            val pin = q["p"]
            val server = runCatching { URI(base) }.getOrNull() ?: return null
            // TLS only; no credentials, paths or queries smuggled into the base URL.
            if (server.scheme != "https" || server.host.isNullOrEmpty() || server.userInfo != null ||
                server.rawQuery != null || server.rawFragment != null || !(server.rawPath.isNullOrEmpty())
            ) return null
            if (!CODE.matches(code)) return null
            if (pin != null && !PIN.matches(pin)) return null
            return PairingLink(base, code, pin)
        }
    }
}
