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
import java.util.concurrent.CopyOnWriteArrayList

/** The MCP panel against a fake server: statuses load on connect, switches round-trip. */
class McpRepositoryTest {

    private val server = MockWebServer()

    @Volatile
    private var camoufox = "disabled"
    private val posts = CopyOnWriteArrayList<String>()

    private class FakeSettingsSource(config: ConnectionSettings) : SettingsSource {
        override val settings: Flow<ConnectionSettings> = MutableStateFlow(config)
        override suspend fun save(settings: ConnectionSettings) = Unit
        override val modelSelection: Flow<ModelSelection> = MutableStateFlow(ModelSelection())
        override suspend fun saveModelSelection(selection: ModelSelection) = Unit
        override fun enabledModels(serverUrl: String): Flow<Set<String>?> = MutableStateFlow(null)
        override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) = Unit
        override val modelVariants: Flow<Map<String, String>> = MutableStateFlow(emptyMap())
        override suspend fun saveModelVariant(modelKey: String, variant: String?) = Unit
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (request.method == "POST") posts += path
                return when (path) {
                    "/global/health" -> json("""{"healthy":true,"version":"test"}""")
                    "/session", "/agent", "/question", "/permission" -> json("[]")
                    "/config/providers" -> json("""{"providers":[],"default":{}}""")
                    "/mcp" -> json("""{"tavily":{"status":"connected"},"camoufox":{"status":"$camoufox"}}""")
                    "/mcp/camoufox/connect" -> {
                        camoufox = "connected"
                        json("true")
                    }
                    "/event" -> MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"type\":\"server.connected\",\"properties\":{}}\n\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loadsStatusesAndConnectsAServer() = runBlocking {
        val repository = AppRepository(FakeSettingsSource(ConnectionSettings(baseUrl = server.url("/").toString())))

        val initial = withTimeout(15_000) { repository.mcpServers.first { it != null } }!!
        assertEquals(listOf("camoufox" to "disabled", "tavily" to "connected"), initial.map { it.name to it.status })

        repository.setMcpEnabled("camoufox", true)

        val updated = withTimeout(15_000) {
            repository.mcpServers.first { list -> list?.any { it.name == "camoufox" && it.connected } == true }
        }!!
        assertTrue(updated.all { it.connected })
        assertTrue("/mcp/camoufox/connect" in posts)
        assertTrue(repository.mcpToggling.first { it.isEmpty() }.isEmpty())
    }
}
