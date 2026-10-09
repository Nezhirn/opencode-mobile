package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.MessageWithParts
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.OpenCodeJson
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
import kotlinx.serialization.builtins.ListSerializer
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** [ChatController] rollback, send and resync paths against a fake server. */
class ChatControllerRevertTest {

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
    private val hits = HashMap<String, AtomicInteger>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.path.orEmpty().substringBefore('?')}"
                synchronized(hits) { hits.getOrPut(key) { AtomicInteger() }.incrementAndGet() }
                return routes[key]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        routes["GET /session/ses_1/todo"] = { json("[]") }
        routes["GET /session/status"] = { json("{}") }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun hitsOf(key: String) = synchronized(hits) { hits[key]?.get() ?: 0 }

    private fun controller(): ChatController {
        val client = OpenCodeClient(server.url("/").toString())
        val selection = SelectionStore(scope, FakeSettings()).apply { selectModel(PromptModel("p", "m")) }
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

    private fun history(vararg messages: ChatMessageUi) = OpenCodeJson.encodeToString(
        ListSerializer(MessageWithParts.serializer()),
        messages.map { MessageWithParts(info = it.info, parts = it.parts) },
    )

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun aPromptThatFailsAfterARollbackLeavesTheRollbackInPlace() = runBlocking {
        val all = arrayOf(message("u1", "user"), message("a1", "assistant"), message("u2", "user"), message("a2", "assistant"))
        routes["POST /session/ses_1/prompt_async"] = { json("""{"message":"boom"}""").setResponseCode(500) }
        routes["GET /session/ses_1/message"] = { json(history(*all)) }
        routes["GET /session/ses_1"] = { json("""{"id":"ses_1","revert":{"messageID":"u2"}}""") }
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", messages = all.toList(), revert = SessionRevert(messageID = "u2")))

        assertFalse(chat.sendPrompt("instead").await())

        // At once: the rolled back messages are hidden again, not shown as live.
        assertEquals("u2", chat.state.value.revert?.messageID)
        assertEquals(listOf("u1", "a1"), chat.state.value.visibleMessages.map { it.info.id })
        assertEquals(listOf("u2"), chat.state.value.revertedUserMessages.map { it.info.id })
        // And after the chat and the session were read back.
        awaitUntil { hitsOf("GET /session/ses_1") == 1 && hitsOf("GET /session/status") >= 1 }
        delay(100)
        val state = chat.state.value
        assertEquals("u2", state.revert?.messageID)
        assertEquals(listOf("u1", "a1"), state.visibleMessages.map { it.info.id })
        assertNotNull("the failure stays visible", state.error)
        assertFalse(state.busy)
    }

    @Test
    fun editDuringARunThatCannotBeStoppedKeepsStop() = runBlocking {
        routes["POST /session/ses_1/abort"] = { json("""{"message":"no"}""").setResponseCode(500) }
        routes["POST /session/ses_1/revert"] = { json("""{"message":"busy"}""").setResponseCode(400) }
        val chat = controller()
        chat.setForTest(ChatState(sessionId = "ses_1", busy = true, messages = listOf(message("u1", "user"), message("a1", "assistant"))))

        assertNull(chat.revertTo("u1").await())

        val state = chat.state.value
        assertTrue("the run goes on, so Stop must stay", state.busy)
        assertNull(state.revert)
        assertFalse(state.reverting)
        assertNotNull(state.error)
    }

    @Test
    fun aResyncDuringALoadRunsOnceThatLoadIsDone() = runBlocking {
        routes["GET /session/ses_1/message"] = { json(history(message("u1", "user"))).setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val chat = controller()

        chat.open("ses_1")
        awaitUntil { hitsOf("GET /session/ses_1/message") == 1 }
        chat.resync()

        awaitUntil { hitsOf("GET /session/ses_1/message") == 2 }
        awaitUntil { !chat.state.value.loading && hitsOf("GET /session/status") == 2 }
        assertEquals(listOf("u1"), chat.state.value.messages.map { it.info.id })
    }
}
