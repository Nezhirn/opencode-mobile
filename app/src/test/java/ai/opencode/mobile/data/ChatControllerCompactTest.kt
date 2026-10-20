package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.SessionRevert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** `/compact` typed in the chat: [ChatController] runs it instead of sending it to the model. */
class ChatControllerCompactTest {

    private class FakeSettings : SettingsSource {
        override val settings: Flow<ConnectionSettings> = MutableStateFlow(ConnectionSettings())
        override suspend fun save(settings: ConnectionSettings) = Unit
        override val modelSelection: Flow<ModelSelection> = MutableStateFlow(ModelSelection())
        override suspend fun saveModelSelection(selection: ModelSelection) = Unit
        override fun enabledModels(serverUrl: String): Flow<Set<String>?> = MutableStateFlow(null)
        override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) = Unit
        override val modelVariants: Flow<Map<String, String>> = MutableStateFlow(emptyMap())
        override suspend fun saveModelVariant(modelKey: String, variant: String?) = Unit
    }

    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Answers by "METHOD path" (query dropped); everything else is 404. */
    private val routes = HashMap<String, () -> MockResponse>()

    /** "METHOD path" and body of every request, in order. */
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.path.orEmpty().substringBefore('?')}"
                requests += key to request.body.readUtf8()
                return routes[key]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        routes["GET /session/ses_1/message"] = { json("[]") }
        routes["GET /session/ses_1/todo"] = { json("[]") }
        routes["GET /session/status"] = { json("{}") }
        routes["GET /session/ses_1"] = { json("""{"id":"ses_1"}""") }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun requestsTo(key: String) = requests.filter { it.first == key }

    private fun controller(model: PromptModel? = PromptModel("p", "m")): ChatController {
        val client = OpenCodeClient(server.url("/").toString())
        val selection = SelectionStore(scope, FakeSettings()).apply { selectModel(model) }
        return ChatController(
            scope = scope,
            clientProvider = { client },
            sessionTree = SessionTree(),
            selection = selection,
            sessionById = { null },
            awaitingUserInput = { false },
            onUnattributedError = {},
        )
    }

    private fun message(id: String, role: String) = ChatMessageUi(
        info = Message(id = id, sessionID = "ses_1", role = role),
        parts = listOf(Part(id = "$id-p", messageID = id, sessionID = "ses_1", type = "text", text = id)),
    )

    private val conversation = listOf(message("u1", "user"), message("a1", "assistant"))

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun compactSummarizesWithTheSelectedModelInsteadOfPrompting() = runBlocking {
        routes["POST /session/ses_1/summarize"] = { json("true") }
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = conversation))

        assertTrue(chat.sendPrompt("  /compact ").await())

        val summarize = requestsTo("POST /session/ses_1/summarize").single()
        assertTrue(summarize.second, summarize.second.contains("\"providerID\":\"p\""))
        assertTrue(summarize.second, summarize.second.contains("\"modelID\":\"m\""))
        assertTrue("the command is not a prompt", requestsTo("POST /session/ses_1/prompt_async").isEmpty())
        // No bubble with the command: the server's marker shows the compaction.
        assertEquals(listOf("u1", "a1"), chat.state.value.messages.map { it.info.id })
        assertNull(chat.state.value.error)
    }

    @Test
    fun summarizeIsTheSameCommand() = runBlocking {
        routes["POST /session/ses_1/summarize"] = { json("true") }
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = conversation))

        assertTrue(chat.sendPrompt("/summarize").await())

        assertEquals(1, requestsTo("POST /session/ses_1/summarize").size)
    }

    @Test
    fun aLongCompactionShowsTheChatBusyAndIsAcceptedBeforeItEnds() = runBlocking {
        routes["POST /session/ses_1/summarize"] = { json("true").setBodyDelay(4, TimeUnit.SECONDS) }
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = conversation))

        val accepted = chat.sendPrompt("/compact")

        assertTrue("Stop must be available while it runs", chat.state.value.busy)
        // Accepted while the server is still summarizing, so the field stays clear.
        assertTrue(withTimeout(3_500) { accepted.await() })
    }

    @Test
    fun aRefusedCompactionEndsTheRunAndSaysWhy() = runBlocking {
        routes["POST /session/ses_1/summarize"] = { json("""{"message":"bad model"}""").setResponseCode(400) }
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = conversation))

        assertFalse(chat.sendPrompt("/compact").await())

        awaitUntil { chat.state.value.error != null }
        assertFalse(chat.state.value.busy)
    }

    @Test
    fun aFailureAfterStopIsNotReported() = runBlocking {
        routes["POST /session/ses_1/summarize"] = {
            json("""{"message":"aborted"}""").setResponseCode(500).setBodyDelay(300, TimeUnit.MILLISECONDS)
        }
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = conversation))

        val accepted = chat.sendPrompt("/compact")
        // What Stop leaves behind: the run is over before the request returns.
        chat.handleEvent("session.idle", buildJsonObject { put("sessionID", JsonPrimitive("ses_1")) })

        assertFalse(accepted.await())
        delay(100)
        assertNull(chat.state.value.error)
    }

    @Test
    fun anEmptyChatHasNothingToCompact() = runBlocking {
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1"))

        assertFalse(chat.sendPrompt("/compact").await())

        assertNotNull(chat.state.value.error)
        assertFalse(chat.state.value.busy)
        assertTrue(requestsTo("POST /session/ses_1/summarize").isEmpty())
    }

    @Test
    fun compactWithoutAModelIsRefusedLocally() = runBlocking {
        val chat = controller(model = null)
        chat.setForTest(ChatState(sessionId = "ses_1", messages = conversation))

        assertFalse(chat.sendPrompt("/compact").await())

        assertNotNull(chat.state.value.error)
        assertTrue(requestsTo("POST /session/ses_1/summarize").isEmpty())
    }

    @Test
    fun compactCommitsARollbackLikeAPrompt() = runBlocking {
        routes["POST /session/ses_1/summarize"] = { json("true") }
        val all = conversation + listOf(message("u2", "user"), message("a2", "assistant"))
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = all, revert = SessionRevert(messageID = "u2")))

        assertTrue(chat.sendPrompt("/compact").await())

        assertNull(chat.state.value.revert)
        assertEquals(listOf("u1", "a1"), chat.state.value.messages.map { it.info.id })
    }

    @Test
    fun theCommandWithFilesIsAPrompt() = runBlocking {
        routes["POST /session/ses_1/prompt_async"] = { MockResponse().setResponseCode(204) }
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = conversation))
        val file = Attachment(id = "f", filename = "a.txt", mime = "text/plain", url = "data:text/plain;base64,YQ==")

        assertTrue(chat.sendPrompt("/compact", listOf(file)).await())

        assertTrue(requestsTo("POST /session/ses_1/summarize").isEmpty())
        assertEquals(1, requestsTo("POST /session/ses_1/prompt_async").size)
    }

    @Test
    fun sessionCompactedReadsTheChatBack() = runBlocking {
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = conversation))

        chat.handleEvent("session.compacted", buildJsonObject { put("sessionID", JsonPrimitive("ses_1")) })

        awaitUntil { requestsTo("GET /session/ses_1/message").isNotEmpty() }
    }
}
