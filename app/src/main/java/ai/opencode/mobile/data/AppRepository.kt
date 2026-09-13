package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.SettingsStore
import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.CreateSessionRequest
import ai.opencode.mobile.data.remote.EventEnvelope
import ai.opencode.mobile.data.remote.FileContent
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.MessageWithParts
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.OpenCodeException
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.PromptRequest
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.TextPartInput
import ai.opencode.mobile.data.remote.Todo
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.data.remote.VcsInfo
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverName: String?) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class ChatMessageUi(
    val info: Message,
    val parts: List<Part>,
)

data class ChatState(
    val sessionId: String? = null,
    val title: String = "",
    val messages: List<ChatMessageUi> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class AppRepository(private val settingsStore: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var serverVersion: String? = null

    val settings: StateFlow<ConnectionSettings> = settingsStore.settings
        .stateIn(scope, SharingStarted.Eagerly, ConnectionSettings())

    private val clientFlow: StateFlow<OpenCodeClient?> = settings
        .map { config ->
            if (config.isConfigured) {
                OpenCodeClient(
                    baseUrl = config.baseUrl,
                    username = config.username,
                    password = config.password,
                    allowInsecureTls = config.allowInsecureTls,
                )
            } else {
                null
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _permissions = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val permissions: StateFlow<List<PermissionRequest>> = _permissions.asStateFlow()

    private val _questions = MutableStateFlow<List<QuestionRequest>>(emptyList())
    val questions: StateFlow<List<QuestionRequest>> = _questions.asStateFlow()

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _selectedModel = MutableStateFlow<PromptModel?>(null)
    val selectedModel: StateFlow<PromptModel?> = _selectedModel.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent: StateFlow<String?> = _selectedAgent.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    init {
        scope.launch {
            clientFlow.collectLatest { client ->
                if (client == null) {
                    _connection.value = ConnectionState.Disconnected
                    _sessions.update { emptyList() }
                    _permissions.update { emptyList() }
                    _questions.update { emptyList() }
                    return@collectLatest
                }
                refreshConnection(client)
                collectEvents(client)
            }
        }
    }

    /**
     * Keeps the event stream alive across clean server closes and transient
     * failures. Permanent client errors (4xx other than 429) stop the loop to
     * avoid hammering the server with invalid credentials or unknown routes.
     */
    private suspend fun collectEvents(client: OpenCodeClient) {
        while (true) {
            try {
                client.events().collect { envelope -> handleEvent(client, envelope) }
                // Clean close (e.g. server restart): reconnect after a pause.
                delay(RECONNECT_DELAY_MILLIS)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _connection.value = ConnectionState.Error(error.message ?: "Event stream failed")
                val code = (error as? OpenCodeException)?.code
                if (code != null && code in 400..499 && code != 429) return
                delay(RECONNECT_DELAY_MILLIS)
            }
        }
    }

    private suspend fun refreshConnection(client: OpenCodeClient) {
        if (_connection.value !is ConnectionState.Connected) {
            _connection.value = ConnectionState.Connecting
        }
        val health = runCatching { client.health() }
        health.onSuccess {
            serverVersion = it.version
            _connection.value = ConnectionState.Connected(it.version)
        }.onFailure {
            Log.w(TAG, "health check failed", it)
            _connection.value = ConnectionState.Error(it.message ?: "Connection failed")
            return
        }
        // Data loads below are best-effort; their failures must not poison the
        // connection state, which is owned by health checks and the event stream.
        loadSessions(client)
        loadProviders(client)
        loadAgents(client)
        loadPermissions(client)
        loadQuestions(client)
    }

    fun refresh() {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            refreshConnection(client)
            _chat.value.sessionId?.let { loadChat(client, it) }
        }
    }

    suspend fun saveSettings(config: ConnectionSettings) {
        settingsStore.save(config)
    }

    private suspend fun loadSessions(client: OpenCodeClient) {
        runCatching { client.listSessions() }
            .onSuccess { list ->
                _sessions.update { list.sortedByDescending { session -> session.time?.updated ?: 0 } }
            }
            .onFailure { Log.w(TAG, "loadSessions failed", it) }
    }

    private suspend fun loadProviders(client: OpenCodeClient) {
        runCatching { client.listProviders() }
            .onSuccess { result -> _providers.update { result.all } }
            .onFailure { Log.w(TAG, "loadProviders failed", it) }
    }

    private suspend fun loadAgents(client: OpenCodeClient) {
        runCatching { client.listAgents() }
            .onSuccess { list -> _agents.update { list.filter { agent -> agent.hidden != true } } }
            .onFailure { Log.w(TAG, "loadAgents failed", it) }
    }

    private suspend fun loadPermissions(client: OpenCodeClient) {
        runCatching { client.listPermissions() }
            .onSuccess { list -> _permissions.update { list } }
            .onFailure { Log.w(TAG, "loadPermissions failed", it) }
    }

    private suspend fun loadQuestions(client: OpenCodeClient) {
        runCatching { client.listQuestions() }
            .onSuccess { list -> _questions.update { list } }
            .onFailure { Log.w(TAG, "loadQuestions failed", it) }
    }

    // --- Session actions ---

    fun createSession(title: String? = null) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            runCatching { client.createSession(CreateSessionRequest(title = title)) }
                .onSuccess { loadSessions(client) }
                .onFailure {
                    Log.w(TAG, "createSession failed", it)
                    _connection.value = ConnectionState.Error(it.message ?: "Failed to create session")
                }
        }
    }

    fun deleteSession(sessionId: String) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            runCatching { client.deleteSession(sessionId) }
                .onSuccess {
                    _sessions.update { current -> current.filterNot { it.id == sessionId } }
                    if (_chat.value.sessionId == sessionId) _chat.value = ChatState()
                }
                .onFailure { Log.w(TAG, "deleteSession($sessionId) failed", it) }
        }
    }

    // --- Chat ---

    fun openChat(sessionId: String) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            loadChat(client, sessionId)
        }
    }

    fun closeChat() {
        _chat.value = ChatState()
    }

    private suspend fun loadChat(client: OpenCodeClient, sessionId: String) {
        val session = _sessions.value.firstOrNull { it.id == sessionId }
        _chat.value = ChatState(
            sessionId = sessionId,
            title = session?.title.orEmpty(),
            loading = true,
        )
        runCatching { client.getMessages(sessionId) }
            .onSuccess { messages ->
                _chat.update { state -> state.copy(messages = messages.map { it.toUi() }, loading = false) }
            }
            .onFailure { error ->
                Log.w(TAG, "loadChat($sessionId) messages failed", error)
                _chat.update { state -> state.copy(loading = false, error = error.message) }
            }
        runCatching { client.todos(sessionId) }
            .onSuccess { todos -> _chat.update { state -> state.copy(todos = todos) } }
            .onFailure { Log.w(TAG, "loadChat($sessionId) todos failed", it) }
    }

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val client = clientFlow.value ?: return@launch
            val sessionId = _chat.value.sessionId ?: return@launch
            _chat.update { state -> state.copy(busy = true, error = null) }
            val request = PromptRequest(
                model = _selectedModel.value,
                agent = _selectedAgent.value,
                parts = listOf(TextPartInput(type = TEXT_PART_TYPE, text = trimmed)),
            )
            runCatching { client.promptAsync(sessionId, request) }
                .onFailure { error ->
                    Log.w(TAG, "sendPrompt($sessionId) failed", error)
                    _chat.update { state -> state.copy(busy = false, error = error.message ?: "Failed to send prompt") }
                }
        }
    }

    fun abort() {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            val sessionId = _chat.value.sessionId ?: return@launch
            runCatching { client.abort(sessionId) }
                .onFailure { Log.w(TAG, "abort($sessionId) failed", it) }
            _chat.update { state -> state.copy(busy = false) }
        }
    }

    fun selectModel(model: PromptModel?) {
        _selectedModel.value = model
    }

    fun selectAgent(agent: String?) {
        _selectedAgent.value = agent
    }

    // --- Permissions and questions ---

    fun replyPermission(requestId: String, reply: String) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            runCatching { client.replyPermission(requestId, reply) }
                .onSuccess {
                    _permissions.update { current -> current.filterNot { it.id == requestId } }
                }
                .onFailure { Log.w(TAG, "replyPermission($requestId) failed", it) }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            runCatching { client.replyQuestion(requestId, answers) }
                .onSuccess {
                    _questions.update { current -> current.filterNot { it.id == requestId } }
                }
                .onFailure { Log.w(TAG, "replyQuestion($requestId) failed", it) }
        }
    }

    fun rejectQuestion(requestId: String) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            runCatching { client.rejectQuestion(requestId) }
                .onSuccess {
                    _questions.update { current -> current.filterNot { it.id == requestId } }
                }
                .onFailure { Log.w(TAG, "rejectQuestion($requestId) failed", it) }
        }
    }

    // --- Files and VCS ---

    suspend fun listFiles(path: String): List<FileNode> =
        runCatching { clientFlow.value?.listFiles(path) }
            .onFailure { Log.w(TAG, "listFiles($path) failed", it) }
            .getOrNull() ?: emptyList()

    suspend fun readFile(path: String): FileContent? =
        runCatching { clientFlow.value?.readFile(path) }
            .onFailure { Log.w(TAG, "readFile($path) failed", it) }
            .getOrNull()

    suspend fun vcsInfo(): VcsInfo? =
        runCatching { clientFlow.value?.vcsInfo() }
            .onFailure { Log.w(TAG, "vcsInfo failed", it) }
            .getOrNull()

    suspend fun vcsStatus(): List<VcsFileStatus> =
        runCatching { clientFlow.value?.vcsStatus() }
            .onFailure { Log.w(TAG, "vcsStatus failed", it) }
            .getOrNull() ?: emptyList()

    suspend fun vcsDiff(mode: String): List<VcsFileDiff> =
        runCatching { clientFlow.value?.vcsDiff(mode) }
            .onFailure { Log.w(TAG, "vcsDiff($mode) failed", it) }
            .getOrNull() ?: emptyList()

    suspend fun sessionDiff(sessionId: String): List<VcsFileDiff> =
        runCatching { clientFlow.value?.sessionDiff(sessionId) }
            .onFailure { Log.w(TAG, "sessionDiff($sessionId) failed", it) }
            .getOrNull() ?: emptyList()

    // --- Event handling ---

    private suspend fun handleEvent(client: OpenCodeClient, envelope: EventEnvelope) {
        val props = envelope.properties ?: return
        when (envelope.type) {
            "server.connected" -> {
                if (_connection.value !is ConnectionState.Connected) {
                    _connection.value = ConnectionState.Connected(serverVersion)
                }
            }

            "session.created", "session.updated", "session.deleted" -> {
                val session = props.decodeSession("info") ?: return
                _sessions.update { current ->
                    when (envelope.type) {
                        "session.deleted" -> current.filterNot { it.id == session.id }
                        "session.created" -> (current.filterNot { it.id == session.id } + session)
                            .sortedByDescending { it.time?.updated ?: 0 }
                        else -> current.map { if (it.id == session.id) session else it }
                    }
                }
                if (envelope.type == "session.updated") maybeUpdateChatTitle(props)
            }

            "message.updated" -> {
                val info = props.decodeMessage("info") ?: return
                if (sessionIdOf(props) == _chat.value.sessionId) {
                    _chat.update { state -> state.upsertMessage(info) }
                }
            }

            "message.removed" -> {
                val messageId = props.stringOrNull("messageID") ?: return
                if (sessionIdOf(props) == _chat.value.sessionId) {
                    _chat.update { state ->
                        state.copy(messages = state.messages.filterNot { it.info.id == messageId })
                    }
                }
            }

            "message.part.updated" -> {
                val part = props.decodePart("part") ?: return
                if (sessionIdOf(props) == _chat.value.sessionId) {
                    _chat.update { state -> state.upsertPart(part) }
                }
            }

            "message.part.removed" -> {
                val partId = props.stringOrNull("partID") ?: return
                if (sessionIdOf(props) == _chat.value.sessionId) {
                    _chat.update { state ->
                        state.copy(
                            messages = state.messages.map { message ->
                                message.copy(parts = message.parts.filterNot { it.id == partId })
                            },
                        )
                    }
                }
            }

            "message.part.delta" -> {
                if (sessionIdOf(props) != _chat.value.sessionId) return
                val partId = props.stringOrNull("partID") ?: return
                val field = props.stringOrNull("field") ?: "text"
                val delta = props.stringOrNull("delta") ?: return
                _chat.update { state -> state.applyDelta(partId, field, delta) }
            }

            "session.idle" -> {
                if (sessionIdOf(props) == _chat.value.sessionId) {
                    _chat.update { state -> state.copy(busy = false) }
                }
            }

            "session.status" -> {
                if (sessionIdOf(props) != _chat.value.sessionId) return
                val status = props["status"]?.let { runCatching { it.jsonObject.stringOrNull("type") }.getOrNull() }
                val busy = status == "busy" || status == "retry"
                _chat.update { state -> state.copy(busy = busy) }
            }

            "session.error" -> {
                if (sessionIdOf(props) == _chat.value.sessionId) {
                    _chat.update { state -> state.copy(busy = false, error = "Session error") }
                }
            }

            "permission.asked" -> loadPermissions(client)
            "permission.replied" -> {
                val id = props.stringOrNull("requestID")
                if (id != null) _permissions.update { current -> current.filterNot { it.id == id } }
                else loadPermissions(client)
            }

            "question.asked" -> loadQuestions(client)
            "question.replied", "question.rejected" -> {
                val id = props.stringOrNull("requestID")
                if (id != null) _questions.update { current -> current.filterNot { it.id == id } }
                else loadQuestions(client)
            }

            "todo.updated" -> {
                if (sessionIdOf(props) == _chat.value.sessionId) {
                    val todos = props["todos"]?.let {
                        runCatching { AppJson.decodeFromJsonElement(TODO_LIST_SERIALIZER, it) }.getOrNull()
                    } ?: return
                    _chat.update { state -> state.copy(todos = todos) }
                }
            }
        }
    }

    private fun maybeUpdateChatTitle(props: JsonObject) {
        val session = props.decodeSession("info") ?: return
        if (session.id == _chat.value.sessionId) {
            _chat.update { state -> state.copy(title = session.title) }
        }
    }

    private fun sessionIdOf(props: JsonObject): String? = props.stringOrNull("sessionID")

    private companion object {
        const val TAG = "AppRepository"
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val TEXT_PART_TYPE = "text"
        val TODO_LIST_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(Todo.serializer())
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun JsonObject.decodeSession(key: String): Session? =
    this[key]?.let { runCatching { AppJson.decodeFromJsonElement(Session.serializer(), it) }.getOrNull() }

private fun JsonObject.decodeMessage(key: String): Message? =
    this[key]?.let { runCatching { AppJson.decodeFromJsonElement(Message.serializer(), it) }.getOrNull() }

private fun JsonObject.decodePart(key: String): Part? =
    this[key]?.let { runCatching { AppJson.decodeFromJsonElement(Part.serializer(), it) }.getOrNull() }

private fun MessageWithParts.toUi(): ChatMessageUi = ChatMessageUi(info = info, parts = parts)

internal fun ChatState.upsertMessage(info: Message): ChatState {
    val index = messages.indexOfFirst { it.info.id == info.id }
    val updated = if (index >= 0) {
        messages.toMutableList().also { it[index] = it[index].copy(info = info) }
    } else {
        messages + ChatMessageUi(info = info, parts = emptyList())
    }
    return copy(messages = updated)
}

internal fun ChatState.upsertPart(part: Part): ChatState {
    val messageIndex = messages.indexOfFirst { it.info.id == part.messageID }
    if (messageIndex < 0) return this
    val message = messages[messageIndex]
    val partIndex = message.parts.indexOfFirst { it.id == part.id }
    val parts = message.parts.toMutableList()
    if (partIndex >= 0) parts[partIndex] = part else parts.add(part)
    return copy(messages = messages.toMutableList().also { it[messageIndex] = message.copy(parts = parts) })
}

internal fun ChatState.applyDelta(partId: String, field: String, delta: String): ChatState {
    val messageIndex = messages.indexOfFirst { m -> m.parts.any { it.id == partId } }
    if (messageIndex < 0) return this
    val message = messages[messageIndex]
    val partIndex = message.parts.indexOfFirst { it.id == partId }
    if (partIndex < 0) return this
    val part = message.parts[partIndex]
    val updated = when (field) {
        "text", "reasoning" -> part.copy(text = (part.text ?: "") + delta)
        else -> part
    }
    val parts = message.parts.toMutableList().also { it[partIndex] = updated }
    return copy(messages = messages.toMutableList().also { it[messageIndex] = message.copy(parts = parts) })
}
