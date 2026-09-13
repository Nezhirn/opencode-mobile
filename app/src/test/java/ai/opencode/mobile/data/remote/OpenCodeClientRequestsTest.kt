package ai.opencode.mobile.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun rejectQuestionSendsPostWithEmptyBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client().rejectQuestion("que_1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/question/que_1/reject", request.path)
        assertEquals("", request.body.readUtf8())
    }
}
