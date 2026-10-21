package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.CreateSessionRequest
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.ServerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's client and chat against a real opencode 2.x server. Skipped unless
 * one is given:
 *
 *     OPENCODE_V2_URL=http://127.0.0.1:4096 OPENCODE_V2_PASSWORD=… \
 *     OPENCODE_V2_DIR=/path/to/project [OPENCODE_V2_MODEL=provider/model] \
 *     ./gradlew testDebugUnitTest --tests '*LiveV2ServerTest*'
 *
 * The directory should be a git repository with a README.md.
 */
class LiveV2ServerTest {

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

    private val url: String? = System.getenv("OPENCODE_V2_URL")
    private val password: String? = System.getenv("OPENCODE_V2_PASSWORD")
    private val directory: String? = System.getenv("OPENCODE_V2_DIR")
    private val model = (System.getenv("OPENCODE_V2_MODEL") ?: "opencode/minimax-m3.1-flash-free").let {
        PromptModel(providerID = it.substringBefore('/'), modelID = it.substringAfter('/'))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: OpenCodeClient
    private val created = ArrayList<String>()

    @Before
    fun setUp() {
        assumeTrue("no live v2 server given", url != null && directory != null)
        client = OpenCodeClient(url!!, username = "opencode", password = password, directory = directory)
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) runBlocking { created.forEach { runCatching { client.deleteSession(it) } } }
        scope.cancel()
    }

    private fun chat(): ChatController {
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

    /** Feeds the server's events to [chat], as AppRepository does. */
    private fun stream(chat: ChatController, seen: MutableList<String>) = scope.launch {
        client.events().collect { envelope ->
            val type = envelope.type ?: return@collect
            synchronized(seen) { seen += type }
            chat.handleEvent(type, envelope.properties ?: JsonObject(emptyMap()))
        }
    }

    private suspend fun awaitUntil(millis: Long, condition: () -> Boolean) {
        withTimeout(millis) { while (!condition()) delay(100) }
    }

    @Test
    fun theClientSpeaksV2() = runBlocking<Unit> {
        assertEquals(ServerProtocol.V2, client.protocol())
        assertTrue(client.health().version.orEmpty().startsWith("2"))
        assertTrue(client.listProjects().isNotEmpty())
        val providers = client.configProviders()
        assertTrue("providers with models", providers.providers.any { it.models.isNotEmpty() })
        assertTrue(client.listAgents().any { it.name == "build" })
        assertTrue(client.pathInfo().directory.isNotBlank())
        val files = client.listFiles("")
        assertTrue(files.map { it.path }.toString(), files.any { it.path == "README.md" })
        assertEquals("text", client.readFile("README.md").type)
        assertNotNull(client.vcsInfo().branch)
        client.vcsStatus()
        client.vcsDiff("git")
        client.mcpStatus()
        client.listPermissions()
        client.listQuestions()
    }

    @Test
    fun aChatRunsAndReadsBackTheSame() = runBlocking<Unit> {
        val session = client.createSession(CreateSessionRequest()).also { created += it.id }
        assertTrue(client.listSessions().any { it.id == session.id })
        assertTrue(client.listAllSessions().any { it.id == session.id })

        val chat = chat()
        val seen = ArrayList<String>()
        val events = stream(chat, seen)
        awaitUntil(10_000) { synchronized(seen) { OpenCodeClient.STREAM_OPENED_EVENT in seen } }
        chat.open(session.id)
        awaitUntil(10_000) { !chat.state.value.loading }

        assertTrue(chat.sendPrompt("Reply with exactly one word: pong").await())
        // The model may be retried a few times; give it time.
        awaitUntil(180_000) {
            val state = chat.state.value
            !state.busy && state.messages.any { message -> message.info.role == "assistant" && message.parts.any { it.type == "text" && !it.text.isNullOrBlank() } }
        }
        val live = chat.state.value
        val prompt = live.messages.first()
        assertEquals("user", prompt.info.role)
        assertTrue("the echo was replaced by the server's prompt", !prompt.isLocalEcho)
        assertEquals("Reply with exactly one word: pong", prompt.userText())

        // What the history says is what the events built.
        val reloaded = chat()
        reloaded.open(session.id)
        awaitUntil(20_000) { !reloaded.state.value.loading }
        val history = reloaded.state.value.messages
        assertEquals(live.messages.map { it.info.id }, history.map { it.info.id })
        assertEquals(
            live.messages.map { message -> message.parts.filter { it.type == "text" }.map { it.id to it.text } },
            history.map { message -> message.parts.filter { it.type == "text" }.map { it.id to it.text } },
        )

        // Roll back to the prompt and undo it.
        assertNotNull(chat.revertTo(prompt.info.id).await())
        assertEquals(prompt.info.id, chat.state.value.revert?.messageID)
        assertNotNull(chat.restore(prompt.info.id).await())
        assertEquals(null, chat.state.value.revert)
        events.cancel()
    }

    @Test
    fun compactIsRunAndShown() = runBlocking<Unit> {
        val session = client.createSession(CreateSessionRequest()).also { created += it.id }
        val chat = chat()
        val seen = ArrayList<String>()
        val events = stream(chat, seen)
        awaitUntil(10_000) { synchronized(seen) { OpenCodeClient.STREAM_OPENED_EVENT in seen } }
        chat.open(session.id)
        awaitUntil(10_000) { !chat.state.value.loading }
        assertTrue(chat.sendPrompt("Say hello.").await())
        awaitUntil(180_000) { !chat.state.value.busy && chat.state.value.messages.any { it.info.role == "assistant" } }

        assertTrue(chat.sendPrompt("/compact").await())
        awaitUntil(180_000) {
            chat.state.value.messages.compactionStates(chat.state.value.busy).values.any { it == CompactionState.Done }
        }
        events.cancel()
    }

    @Test
    fun questionsAndPermissionsAreAskedAndAnswered() = runBlocking<Unit> {
        val session = client.createSession(CreateSessionRequest()).also { created += it.id }
        val chat = chat()
        val asked = ArrayList<Pair<String, JsonObject>>()
        val events = scope.launch {
            client.events().collect { envelope ->
                val type = envelope.type ?: return@collect
                val props = envelope.properties ?: JsonObject(emptyMap())
                if (type == "question.asked" || type == "permission.asked") synchronized(asked) { asked += type to props }
                chat.handleEvent(type, props)
            }
        }
        delay(1_000)
        chat.open(session.id)
        awaitUntil(10_000) { !chat.state.value.loading }

        assertTrue(
            chat.sendPrompt(
                "Use the question tool to ask me one question: which color I like, with the options Red and Blue. " +
                    "After my answer, read the file .env with the read tool and tell me its content.",
            ).await(),
        )
        awaitUntil(180_000) { synchronized(asked) { asked.any { it.first == "question.asked" } } }
        val question = client.listQuestions().single { it.sessionID == session.id }
        assertEquals("Red", question.questions.single().options.first().label)
        client.replyQuestion(question.id, listOf(listOf("Blue")), sessionId = session.id)

        awaitUntil(180_000) { synchronized(asked) { asked.any { it.first == "permission.asked" } } }
        val permission = client.listPermissions().single { it.sessionID == session.id }
        assertEquals("read", permission.permission)
        client.replyPermission(permission.id, "once", sessionId = session.id)

        awaitUntil(180_000) {
            val state = chat.state.value
            !state.busy && state.messages.any { message -> message.parts.any { it.type == "tool" && it.tool == "read" && it.state?.status == "completed" } }
        }
        val output = chat.state.value.messages.flatMap { it.parts }.first { it.tool == "read" }.state?.output.orEmpty()
        assertTrue(output, output.contains("SECRET=42"))
        events.cancel()
    }

    private inner class AppSettings : SettingsSource {
        override val settings: Flow<ConnectionSettings> =
            MutableStateFlow(ConnectionSettings(baseUrl = url!!, username = "opencode", password = password.orEmpty()))
        override suspend fun save(settings: ConnectionSettings) = Unit
        override val modelSelection: Flow<ModelSelection> = MutableStateFlow(ModelSelection())
        override suspend fun saveModelSelection(selection: ModelSelection) = Unit
        override fun enabledModels(serverUrl: String): Flow<Set<String>?> = MutableStateFlow(null)
        override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) = Unit
        override val modelVariants: Flow<Map<String, String>> = MutableStateFlow(emptyMap())
        override suspend fun saveModelVariant(modelKey: String, variant: String?) = Unit
        override fun lastProject(serverUrl: String): Flow<String?> = MutableStateFlow(directory)
    }

    @Test
    fun theAppWorksAgainstV2() = runBlocking<Unit> {
        val repository = AppRepository(AppSettings())
        withTimeout(20_000) { repository.connection.first { it is ConnectionState.Connected } }
        withTimeout(20_000) { repository.sessionsLoaded.first { it } }
        withTimeout(20_000) { repository.providers.first { list -> list.any { it.models.isNotEmpty() } } }
        assertTrue(repository.agents.value.any { it.name == "build" })

        val sessionId = repository.createSession()
        assertNotNull(sessionId)
        created += sessionId!!
        withTimeout(20_000) { repository.sessions.first { list -> list.any { it.id == sessionId } } }

        repository.selectModel(model)
        repository.openChat(sessionId)
        withTimeout(20_000) { repository.chat.first { !it.loading } }
        assertTrue(repository.sendPrompt("Reply with exactly one word: pong").await())
        withTimeout(180_000) {
            repository.chat.first { chat ->
                !chat.busy && chat.messages.any { message -> message.info.role == "assistant" && message.parts.any { it.type == "text" && !it.text.isNullOrBlank() } }
            }
        }
        // The server titles the session after its first prompt; the list follows.
        withTimeout(60_000) { repository.sessions.first { list -> list.any { it.id == sessionId && it.title.isNotBlank() } } }
    }
}
