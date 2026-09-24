package ai.opencode.mobile.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for request construction. OkHttp throws
 * IllegalArgumentException when a body-requiring method (POST/PUT/PATCH) is sent
 * without a body, which silently broke abort/reject until an empty body was
 * supplied.
 */
class OpenCodeClientRequestsTest {

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

    private fun client() = OpenCodeClient(server.url("/").toString())

    @Test
    fun abortSendsPostWithEmptyBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client().abort("ses_1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/ses_1/abort", request.path)
        assertEquals("", request.body.readUtf8())
    }

    @Test
    fun sessionStatusParsesRunStateBySession() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ses_1":{"type":"busy"},"ses_2":{"type":"retry","attempt":2,"message":"rate limited","next":1}}""",
            ),
        )

        val statuses = client().sessionStatus()

        assertEquals("/session/status", server.takeRequest().path)
        assertEquals("busy", statuses["ses_1"]?.type)
        assertEquals("retry", statuses["ses_2"]?.type)
    }

    @Test
    fun configProvidersParsesConfiguredProvidersOnly() = runBlocking {
        // Shape of opencode 1.18.32; secrets stripped from `options`.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"providers":[{"id":"runware","name":"RunWare","source":"config","env":[],"options":{},
                  "models":{"zai:glm@5.3-flash":{"id":"zai:glm@5.3-flash","providerID":"runware",
                    "api":{"id":"zai:glm@5.3-flash","url":"","npm":"@ai-sdk/openai-compatible"},
                    "name":"GLM 5.3 Flash","family":"","capabilities":{"reasoning":true,"toolcall":true},
                    "cost":{"input":0,"output":0,"cache":{"read":0,"write":0}},
                    "limit":{"context":1048576,"output":131072},"status":"active","options":{},
                    "headers":{},"release_date":"","variants":{"max":{"reasoning_effort":"max"}}}}}],
                 "default":{"runware":"zai:glm@5.3-flash"}}
                """.trimIndent(),
            ),
        )

        val list = client().configProviders().asProviderList()

        assertEquals("/config/providers", server.takeRequest().path)
        assertEquals(listOf("runware"), list.connected)
        assertEquals(listOf("zai:glm@5.3-flash"), list.all.single().models.keys.toList())
        assertEquals(mapOf("runware" to "zai:glm@5.3-flash"), list.default)
    }

    @Test
    fun rejectQuestionSendsPostWithEmptyBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client().rejectQuestion("que_1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/question/que_1/reject", request.path)
        assertEquals("", request.body.readUtf8())
    }

    @Test
    fun emptyResponseBodyThrowsMeaningfulError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "0"))

        val error = runCatching {
            client().createSession(CreateSessionRequest(title = "t"))
        }.exceptionOrNull()

        assertTrue("expected OpenCodeException but was $error", error is OpenCodeException)
        assertEquals(200, (error as OpenCodeException).code)
        assertTrue(
            "message should name the problem: ${error.message}",
            error.message.orEmpty().contains("Empty response body"),
        )
    }

    @Test
    fun eventStreamClientDoesNotUseBodyLogging() {
        // BODY logging reads the response to EOF, which never happens on SSE.
        assertFalse(client().eventStreamHasBodyLogging())
    }

    @Test
    fun errorMessageIsNotQuoted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"boom"}"""))

        val error = runCatching { client().abort("s") }.exceptionOrNull() as? OpenCodeException

        assertEquals("boom", error?.message)
    }

    @Test
    fun errorMessageReadsNestedData() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"name":"ProviderAuthError","data":{"message":"bad key"}}}"""),
        )

        val error = runCatching { client().abort("s") }.exceptionOrNull() as? OpenCodeException

        assertEquals("bad key", error?.message)
    }

    @Test
    fun errorMessageReadsStringError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"nope"}"""))

        val error = runCatching { client().abort("s") }.exceptionOrNull() as? OpenCodeException

        assertEquals("nope", error?.message)
    }
}
