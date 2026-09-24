package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Events emitted while the stream is down are never replayed. A permission asked
 * during the gap used to stay invisible for good while the run waited for it.
 */
class EventStreamResyncTest {

    private val server = MockWebServer()
    private val eventStreams = AtomicInteger()
    private val permissionLoads = AtomicInteger()

    private class FakeSettingsSource(config: ConnectionSettings) : SettingsSource {
        override val settings: Flow<ConnectionSettings> = MutableStateFlow(config)
        override suspend fun save(settings: ConnectionSettings) = Unit
        override val modelSelection: Flow<ModelSelection> = MutableStateFlow(ModelSelection())
        override suspend fun saveModelSelection(selection: ModelSelection) = Unit
        override fun enabledModels(serverUrl: String): Flow<Set<String>?> = MutableStateFlow(null)
        override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) = Unit
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path.orEmpty().substringBefore('?')) {
                    "/global/health" -> json("""{"healthy":true,"version":"test"}""")
                    "/session", "/agent", "/question" -> json("[]")
                    "/provider" -> json("""{"all":[],"default":{},"connected":[]}""")
                    // Nothing pending at connect time; the request shows up only
                    // after the (simulated) gap in the event stream.
                    "/permission" -> json(
                        if (permissionLoads.incrementAndGet() == 1) {
                            "[]"
                        } else {
                            """[{"id":"per_1","sessionID":"ses_1","permission":"bash"}]"""
                        },
                    )
                    // Each stream delivers one event and ends, like a dropped connection.
                    "/event" -> {
                        eventStreams.incrementAndGet()
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"type\":\"server.connected\",\"properties\":{}}\n\n")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun reconnectReloadsWhatTheGapMissed() = runBlocking {
        val repository = AppRepository(FakeSettingsSource(ConnectionSettings(baseUrl = server.url("/").toString())))

        val permissions = withTimeout(15_000) { repository.permissions.first { it.isNotEmpty() } }

        assertEquals("per_1", permissions.single().id)
        assertTrue(eventStreams.get() >= 2)
    }
}
