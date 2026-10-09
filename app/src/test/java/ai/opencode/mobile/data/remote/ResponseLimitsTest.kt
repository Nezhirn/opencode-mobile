package ai.opencode.mobile.data.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Bodies too large to hold in memory are refused, not read. */
class ResponseLimitsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(directory: String? = null) = OpenCodeClient(server.url("/").toString(), directory = directory)

    private fun hugeFile(): String {
        val content = "x".repeat((OpenCodeClient.MAX_FILE_BYTES + 1).toInt())
        return """{"type":"text","content":"$content"}"""
    }

    private suspend fun assertTooLarge(block: suspend () -> Unit) {
        try {
            block()
            fail("expected ResponseTooLargeException")
        } catch (expected: ResponseTooLargeException) {
            assertEquals(OpenCodeClient.MAX_FILE_BYTES, expected.limitBytes)
        }
    }

    @Test
    fun aFileLargerThanTheLimitIsRefused() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(hugeFile()))

        assertTooLarge { client().readFile("big.log") }
    }

    @Test
    fun aBodyOfUndeclaredLengthIsCutAtTheLimitToo() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setChunkedBody(hugeFile(), 1024 * 1024))

        assertTooLarge { client().readFile("big.log") }
    }

    @Test
    fun aFileUnderTheLimitIsRead() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"type":"text","content":"hi"}"""))

        assertEquals("hi", client().readFile("small.txt").content)
    }

    @Test
    fun theEventStreamCarriesTheProjectDirectory() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))

        val first = withTimeout(5_000) { client("/p").events().first() }

        assertEquals(OpenCodeClient.STREAM_OPENED_EVENT, first.type)
        assertEquals("/event?directory=%2Fp", server.takeRequest().path)
    }

    @Test
    fun anUpperCaseSchemeIsNotPrefixedAgain() {
        assertEquals("https://Host:4096", OpenCodeClient.normalizeBaseUrl(" HTTPS://Host:4096/ "))
        assertEquals("http://host", OpenCodeClient.normalizeBaseUrl("Http://host"))
        assertTrue(OpenCodeClient.normalizeBaseUrl("192.168.1.10:4096").startsWith("http://"))
    }
}
