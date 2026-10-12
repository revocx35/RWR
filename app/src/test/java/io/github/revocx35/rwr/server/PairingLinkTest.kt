package io.github.revocx35.rwr.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    private val pin = "A".repeat(43)

    @Test fun parsesServerLink() {
        val l = PairingLink.parse("rwr://pair?u=https%3A%2F%2F192.168.1.50%3A8443&c=abcdefghijklmnop&p=$pin")!!
        assertEquals("https://192.168.1.50:8443", l.baseUrl)
        assertEquals("abcdefghijklmnop", l.code)
        assertEquals(pin, l.pin)
    }

    @Test fun pinIsOptional() {
        assertNull(PairingLink.parse("rwr://pair?u=https%3A%2F%2Frwr.example.com&c=abcdefghijklmnop")!!.pin)
    }

    @Test fun rejectsUnsafeOrMalformedLinks() {
        listOf(
            "rwr://pair?u=http%3A%2F%2F192.168.1.50%3A8443&c=abcdefghijklmnop",       // plain http
            "https://pair?u=https%3A%2F%2Fx&c=abcdefghijklmnop",                    // wrong scheme
            "rwr://other?u=https%3A%2F%2Fx&c=abcdefghijklmnop",                     // wrong host
            "rwr://pair?u=https%3A%2F%2Fuser%3Apw%40x&c=abcdefghijklmnop",          // credentials in URL
            "rwr://pair?u=https%3A%2F%2Fx%2Fpath&c=abcdefghijklmnop",               // path
            "rwr://pair?u=https%3A%2F%2Fx&c=short",                                 // code too short
            "rwr://pair?u=https%3A%2F%2Fx&c=abcdefghijklmnop&p=tooshort",           // bad pin
            "rwr://pair?c=abcdefghijklmnop",                                        // no url
            "not a link at all",
        ).forEach { assertNull(it, PairingLink.parse(it)) }
    }
}
