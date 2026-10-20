package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.MessageWithParts
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.OpenCodeJson
import ai.opencode.mobile.data.remote.Part
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
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A resync after the stream reopened reads only the newest messages when it can. */
class ChatControllerResyncTest {

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

    /** The server's history; `?limit=n` answers with its newest n, as opencode does. */
    @Volatile
    private var history: List<ChatMessageUi> = emptyList()

    /** Paths (with query) of the history requests, in order. */
    private val historyRequests = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/session/ses_1/message" -> {
                        historyRequests += request.path.orEmpty()
                        val limit = url.queryParameter("limit")?.toInt()
                        json(encode(if (limit == null) history else history.takeLast(limit)))
                    }
                    "/session/ses_1/todo" -> json("[]")
                    "/session/status" -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun encode(messages: List<ChatMessageUi>) = OpenCodeJson.encodeToString(
        ListSerializer(MessageWithParts.serializer()),
        messages.map { MessageWithParts(info = it.info, parts = it.parts) },
    )

    private fun message(index: Int) = ChatMessageUi(
        info = Message(id = "msg_%04d".format(index), sessionID = "ses_1", role = if (index % 2 == 0) "user" else "assistant"),
        parts = listOf(Part(id = "prt_$index", messageID = "msg_%04d".format(index), sessionID = "ses_1", type = "text", text = "m$index")),
    )

    private fun controller(): ChatController {
        val client = OpenCodeClient(server.url("/").toString())
        return ChatController(
            scope = scope,
            clientProvider = { client },
            sessionTree = SessionTree(),
            selection = SelectionStore(scope, FakeSettings()),
            sessionById = { null },
            awaitingUserInput = { false },
            onUnattributedError = {},
        )
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun aResyncReadsOnlyTheNewestMessagesAndKeepsTheRest() = runBlocking {
        history = (0 until 200).map(::message)
        val chat = controller()
        chat.open("ses_1")
        awaitUntil { chat.state.value.messages.size == 200 }

        // What the gap missed: a reply grew, and two messages came.
        history = history.dropLast(1) + message(199).copy(parts = listOf(message(199).parts.single().copy(text = "m199 done"))) +
            message(200) + message(201)
        chat.resync()
        awaitUntil { chat.state.value.messages.size == 202 }

        val state = chat.state.value
        assertEquals(history.map { it.info.id }, state.messages.map { it.info.id })
        assertEquals("m199 done", state.messages.first { it.info.id == "msg_0199" }.parts.single().text)
        assertEquals(listOf("/session/ses_1/message", "/session/ses_1/message?limit=50"), historyRequests.toList())
    }

    @Test
    fun aResyncAfterALongGapReadsEverything() = runBlocking {
        history = (0 until 60).map(::message)
        val chat = controller()
        chat.open("ses_1")
        awaitUntil { chat.state.value.messages.size == 60 }

        // More happened than the newest messages reach back over.
        history = history + (60 until 130).map(::message)
        chat.resync()
        awaitUntil { chat.state.value.messages.size == 130 }

        assertEquals(history.map { it.info.id }, chat.state.value.messages.map { it.info.id })
        assertEquals(
            listOf("/session/ses_1/message", "/session/ses_1/message?limit=50", "/session/ses_1/message"),
            historyRequests.toList(),
        )
    }
}
