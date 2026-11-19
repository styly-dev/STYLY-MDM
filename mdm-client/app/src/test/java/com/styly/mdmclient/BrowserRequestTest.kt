package com.styly.mdmclient

import org.junit.Assert.*
import org.junit.Test

class BrowserRequestTest {
    @Test fun validUrlsKeepQueryFragmentsAndEncodedCharacters() {
        for (url in listOf(
            "https://example.com/path?q=a%26b&token=abc#fragment",
            "http://192.168.1.20:8811/a?x='hello'&y=1",
            "http://[::1]:8080/", "HTTP://example.com/", "https://例え.jp/hello"
        )) assertEquals(url, validBrowserUrl(url))
    }

    @Test fun rejectsNonWebCredentialsAndAmbiguousInput() {
        for (value in listOf(
            null, 1, "", "example.com", "intent://example.com", "javascript:alert(1)",
            "file:///sdcard/file", "https://user:pass@example.com", "https://@example.com",
            "https://example.com\\@evil.com", "https://example.com/\n?token=secret",
            " https://example.com", "https://example.com ", "https://example.com:0/",
            "https://example.com:65536/", "https://example.com:bad/", "https://",
            "https://example.com/%xx", "https://example.com/\u00a0", "https://example.com/" + "a".repeat(4096)
        )) assertNull("Rejected input", validBrowserUrl(value))
    }

    @Test fun unsolicitedAndDuplicateTokensCannotConsumeALiveRequest() {
        val gate = BrowserRequestGate<String>()
        assertNull(gate.take("unknown", 0))
        assertTrue(gate.offer("secret", 12000, "url"))
        assertFalse(gate.offer("other", 12000, "other url"))
        assertNull(gate.take("unknown", 1))
        assertNull(gate.take(null, 1))
        assertEquals("url", gate.take("secret", 1))
        assertNull(gate.take("secret", 2))
        assertTrue(gate.offer("new", 13000, "new url"))
        assertNull(gate.take("secret", 3))
        assertEquals("new url", gate.take("new", 3))
    }

    @Test fun expirationAndServiceDestructionInvalidateNavigation() {
        val gate = BrowserRequestGate<String>()
        gate.offer("expired", 12000, "url")
        assertNull(gate.take("expired", 12000))
        assertTrue(gate.offer("new", 15000, "url"))
        assertFalse(gate.matches("expired"))
        assertTrue(gate.matches("new"))
        gate.clear()
        assertNull(gate.take("new", 12001))
    }
}
