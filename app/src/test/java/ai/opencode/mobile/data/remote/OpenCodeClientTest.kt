package ai.opencode.mobile.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeClientTest {

    @Test
    fun addsHttpSchemeWhenMissing() {
        assertEquals("http://192.168.1.10:4096", OpenCodeClient.normalizeBaseUrl("192.168.1.10:4096"))
    }

    @Test
    fun keepsHttpsScheme() {
        assertEquals("https://opencode.example.com", OpenCodeClient.normalizeBaseUrl("https://opencode.example.com"))
    }

    @Test
    fun stripsTrailingSlash() {
        assertEquals("http://localhost:4096", OpenCodeClient.normalizeBaseUrl("http://localhost:4096/"))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("http://localhost:4096", OpenCodeClient.normalizeBaseUrl("  http://localhost:4096  "))
    }
}
