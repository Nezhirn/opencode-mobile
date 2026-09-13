package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.CreateSessionRequest
import ai.opencode.mobile.data.remote.EventEnvelope
import ai.opencode.mobile.data.remote.FileContent
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.MessageWithParts
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.OpenCodeException
import ai.opencode.mobile.data.remote.OpenCodeJson
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.PromptRequest
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.ProviderList
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionErrorInfo
import ai.opencode.mobile.data.remote.TextPartInput
import ai.opencode.mobile.data.remote.Todo
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.data.remote.VcsInfo
import android.util.Log
import androidx.compose.runtime.Immutable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverName: String?) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

@Immutable
data class ChatMessageUi(
    val info: Message,
    val parts: List<Part>,
)

@Immutable
data class ChatState(
    val sessionId: String? = null,
    val title: String = "",
    val messages: List<ChatMessageUi> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class AppRepository(private val settingsStore: SettingsSource) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var loadChatJob: Job? = null

    @Volatile
    private var lastChatEventAt: Long = 0L
    private var busyWatchdog: Job? = null

    /** Pending streaming text per "partId|field", flushed on a short interval. */
    private val deltaBuffers = ConcurrentHashMap<String, StringBuilder>()
    private var deltaFlushJob: Job? = null

    /** Id of the locally echoed user message, replaced when the server echoes it. */
    @Volatile
    private var pendingLocalMessageId: String? = null

    @Volatile
    private var serverVersion: String? = null

    /**
     * Child session id -> parent session id, used to attribute events emitted by
     * subagent sessions to the chat the user is looking at.
     */
    private val sessionParentById = ConcurrentHashMap<String, String>()

    val settings: StateFlow<ConnectionSettings> = settingsStore.settings
        .stateIn(scope, SharingStarted.Eagerly, ConnectionSettings())

    /** False until the persisted settings have been read from disk. */
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

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

    private val _creatingSession = MutableStateFlow(false)
    val creatingSession: StateFlow<Boolean> = _creatingSession.asStateFlow()

    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    /** Transient error from a user action (files/VCS) that UI should surface. */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /**
     * One-shot navigation requests (session id to open). A Channel is used instead
     * of a StateFlow so returning to the sessions list does not re-trigger it.
     */
    private val _navigation = Channel<String>(Channel.BUFFERED)
    val navigation: Flow<String> = _navigation.receiveAsFlow()

    /** Incremented to force the event stream to restart (see [refresh]). */
    private val retryTick = MutableStateFlow(0)

    init {
        scope.launch {
            // Avoid a flash of the connect screen before DataStore has loaded.
            settingsStore.settings.first()
            _settingsLoaded.value = true
        }
        scope.launch {
            combine(clientFlow, retryTick) { client, _ -> client }
                .collectLatest { client ->
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
     * failures, with exponential backoff. Permanent client errors (4xx other than
     * 429) stop the loop to avoid hammering the server, but [refresh] restarts it
     * so fixing credentials or a route recovers without an app restart.
     */
    private suspend fun collectEvents(client: OpenCodeClient) {
        var attempt = 0
        while (true) {
            try {
                client.events().collect { envelope -> handleEvent(client, envelope) }
                // Clean close (e.g. server restart): reconnect after a pause.
                attempt = 0
                delay(RECONNECT_DELAY_MILLIS)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _connection.value = ConnectionState.Error(error.message ?: "Event stream failed")
                val code = (error as? OpenCodeException)?.code
                if (code != null && code in 400..499 && code != 429) {
                    Log.w(TAG, "event stream stopped with HTTP $code", error)
                    return
                }
                attempt += 1
                delay(backoffMillis(attempt))
            }
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 5)
        return (RECONNECT_DELAY_MILLIS * multiplier).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
    }

    /**
     * Guards against a run that stalls without ever emitting session.idle or
     * session.status (a known prompt_async issue on some server versions). Any
     * event resets the timer, so long but active runs are not interrupted.
     */
    private fun armBusyWatchdog() {
        lastChatEventAt = System.currentTimeMillis()
        if (busyWatchdog?.isActive == true) return
        busyWatchdog = scope.launch {
            while (true) {
                delay(BUSY_TIMEOUT_MILLIS / 2)
                if (!_chat.value.busy) return@launch
                if (System.currentTimeMillis() - lastChatEventAt >= BUSY_TIMEOUT_MILLIS) {
                    Log.w(TAG, "busy watchdog: no events for $BUSY_TIMEOUT_MILLIS ms")
                    _chat.update { state ->
                        state.copy(
                            busy = false,
                            error = "No response from the server; the run may have stalled.",
                        )
                    }
                    return@launch
                }
            }
        }
    }

    private fun enqueueDelta(partId: String, field: String, delta: String) {
        deltaBuffers.computeIfAbsent("$partId|$field") { StringBuilder() }.append(delta)
        if (deltaFlushJob?.isActive != true) {
            deltaFlushJob = scope.launch {
                delay(DELTA_FLUSH_INTERVAL_MILLIS)
                flushDeltas()
            }
        }
    }

    /**
     * Applies buffered streaming deltas in one state update. Streaming can emit
     * thousands of tokens per second; coalescing them keeps state updates (and
     * the resulting recompositions) bounded.
     */
    private fun flushDeltas() {
        if (deltaBuffers.isEmpty()) return
        val snapshot = HashMap(deltaBuffers)
        deltaBuffers.clear()
        _chat.update { state ->
            var updated = state
            snapshot.forEach { (key, buffer) ->
                if (buffer.isNotEmpty()) {
                    val separator = key.indexOf('|')
                    val partId = key.substring(0, separator)
                    val field = key.substring(separator + 1)
                    updated = updated.applyDelta(partId, field, buffer.toString())
                }
            }
            updated
        }
    }

    fun reconnect() {
        retryTick.update { it + 1 }
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
        // Restart the event stream as well: a Refresh tap must recover from a
        // stream that stopped on a 4xx without requiring an app restart.
        reconnect()
        _chat.value.sessionId?.let { openChat(it) }
    }

    suspend fun saveSettings(config: ConnectionSettings) {
        settingsStore.save(config)
    }

    private suspend fun loadSessions(client: OpenCodeClient) {
        runCatching { client.listSessions() }
            .onSuccess { list ->
                _sessions.update { list.sortedByDescending { session -> session.time?.updated ?: 0 } }
                sessionParentById.clear()
                list.forEach { session ->
                    session.parentID?.let { parentId -> sessionParentById[session.id] = parentId }
                }
            }
            .onFailure { Log.w(TAG, "loadSessions failed", it) }
    }

    private suspend fun loadProviders(client: OpenCodeClient) {
        runCatching { client.listProviders() }
            .onSuccess { result ->
                // Only providers that are actually configured/authenticated on the
                // server should be offered; `all` also contains unused catalog
                // providers. If the server reports none, fall back to everything.
                val connected = result.connected.toSet()
                _providers.update {
                    if (connected.isEmpty()) result.all
                    else result.all.filter { provider -> provider.id in connected }
                }
                ensureSelectedModel(result)
            }
            .onFailure { Log.w(TAG, "loadProviders failed", it) }
    }

    /**
     * Resolves a default model once, so newly created sessions can send prompts
     * without the user opening the model picker first. An explicit user choice is
     * never overwritten.
     */
    private fun ensureSelectedModel(providers: ProviderList) {
        if (_selectedModel.value != null) return
        _selectedModel.value = resolveDefaultModel(providers)
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
        // Ignore re-entrant taps while a create request is in flight.
        if (_creatingSession.value) return
        _creatingSession.value = true
        _sessionError.value = null
        scope.launch {
            val client = clientFlow.value
            if (client == null) {
                _creatingSession.value = false
                return@launch
            }
            runCatching { client.createSession(CreateSessionRequest(title = title)) }
                .onSuccess { session ->
                    loadSessions(client)
                    _navigation.send(session.id)
                }
                .onFailure {
                    Log.w(TAG, "createSession failed", it)
                    _sessionError.value = it.message ?: "Failed to create session"
                }
            _creatingSession.value = false
        }
    }

    fun clearSessionError() {
        _sessionError.value = null
    }

    fun clearActionError() {
        _actionError.value = null
    }

    private fun reportActionError(message: String?) {
        _actionError.value = message ?: "Action failed"
    }

    fun deleteSession(sessionId: String) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            runCatching { client.deleteSession(sessionId) }
                .onSuccess {
                    _sessions.update { current -> current.filterNot { it.id == sessionId } }
                    if (_chat.value.sessionId == sessionId) _chat.value = ChatState()
                }
                .onFailure {
                    Log.w(TAG, "deleteSession($sessionId) failed", it)
                    _sessionError.value = it.message ?: "Failed to delete session"
                }
        }
    }

    // --- Chat ---

    fun openChat(sessionId: String) {
        val client = clientFlow.value ?: return
        // Cancel any in-flight load so a slower previous session cannot land in
        // the newly opened chat.
        loadChatJob?.cancel()
        deltaBuffers.clear()
        _chat.value = ChatState(
            sessionId = sessionId,
            title = _sessions.value.firstOrNull { it.id == sessionId }?.title.orEmpty(),
            loading = true,
        )
        loadChatJob = scope.launch { loadChat(client, sessionId) }
    }

    /**
     * Clears the open chat. When [sessionId] is given the state is only cleared
     * if it still belongs to that session, so a ViewModel being destroyed cannot
     * wipe a chat that was opened afterwards.
     */
    fun closeChat(sessionId: String? = null) {
        if (sessionId != null && _chat.value.sessionId != sessionId) return
        loadChatJob?.cancel()
        deltaBuffers.clear()
        _chat.value = ChatState()
    }

    private suspend fun loadChat(client: OpenCodeClient, sessionId: String) {
        runCatching { client.getMessages(sessionId) }
            .onSuccess { messages ->
                _chat.update { state ->
                    if (state.sessionId != sessionId) state
                    else state.copy(messages = messages.map { it.toUi() }, loading = false)
                }
            }
            .onFailure { error ->
                Log.w(TAG, "loadChat($sessionId) messages failed", error)
                _chat.update { state ->
                    if (state.sessionId != sessionId) state
                    else state.copy(loading = false, error = error.message)
                }
            }
        runCatching { client.todos(sessionId) }
            .onSuccess { todos ->
                _chat.update { state ->
                    if (state.sessionId != sessionId) state else state.copy(todos = todos)
                }
            }
            .onFailure { Log.w(TAG, "loadChat($sessionId) todos failed", it) }
    }

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val client = clientFlow.value ?: return@launch
            val sessionId = _chat.value.sessionId ?: return@launch
            val model = _selectedModel.value
            if (model == null || model.providerID.isBlank() || model.modelID.isBlank()) {
                // Sending without a valid model is rejected by the server, so
                // surface a clear message instead of a cryptic session.error.
                _chat.update { state ->
                    state.copy(error = "No model available. Pick a model or configure a provider.")
                }
                return@launch
            }
            val localId = "local-${System.currentTimeMillis()}"
            pendingLocalMessageId = localId
            _chat.update { state ->
                state.copy(
                    busy = true,
                    error = null,
                    messages = state.messages + localUserMessage(localId, trimmed),
                )
            }
            armBusyWatchdog()
            val request = PromptRequest(
                model = model,
                agent = _selectedAgent.value,
                parts = listOf(TextPartInput(type = TEXT_PART_TYPE, text = trimmed)),
            )
            runCatching { client.promptAsync(sessionId, request) }
                .onFailure { error ->
                    Log.w(TAG, "sendPrompt($sessionId) failed", error)
                    pendingLocalMessageId = null
                    _chat.update { state ->
                        state.copy(
                            busy = false,
                            error = error.message ?: "Failed to send prompt",
                            messages = state.messages.filterNot { it.info.id == localId },
                        )
                    }
                }
        }
    }

    fun abort() {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            val sessionId = _chat.value.sessionId ?: return@launch
            runCatching { client.abort(sessionId) }
                .onSuccess { _chat.update { state -> state.copy(busy = false) } }
                .onFailure { error ->
                    Log.w(TAG, "abort($sessionId) failed", error)
                    _chat.update { state ->
                        state.copy(busy = false, error = error.message ?: "Failed to abort")
                    }
                }
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
                .onFailure {
                    Log.w(TAG, "replyPermission($requestId) failed", it)
                    _chat.update { state ->
                        state.copy(error = it.message ?: "Failed to reply to permission")
                    }
                }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            runCatching { client.replyQuestion(requestId, answers) }
                .onSuccess {
                    _questions.update { current -> current.filterNot { it.id == requestId } }
                }
                .onFailure {
                    Log.w(TAG, "replyQuestion($requestId) failed", it)
                    _chat.update { state ->
                        state.copy(error = it.message ?: "Failed to reply to question")
                    }
                }
        }
    }

    fun rejectQuestion(requestId: String) {
        scope.launch {
            val client = clientFlow.value ?: return@launch
            runCatching { client.rejectQuestion(requestId) }
                .onSuccess {
                    _questions.update { current -> current.filterNot { it.id == requestId } }
                }
                .onFailure {
                    Log.w(TAG, "rejectQuestion($requestId) failed", it)
                    _chat.update { state ->
                        state.copy(error = it.message ?: "Failed to reject question")
                    }
                }
        }
    }

    // --- Files and VCS ---

    suspend fun listFiles(path: String): List<FileNode> =
        runCatching { clientFlow.value?.listFiles(path) }
            .onFailure {
                Log.w(TAG, "listFiles($path) failed", it)
                reportActionError(it.message ?: "Failed to list files")
            }
            .getOrNull() ?: emptyList()

    suspend fun readFile(path: String): FileContent? =
        runCatching { clientFlow.value?.readFile(path) }
            .onFailure {
                Log.w(TAG, "readFile($path) failed", it)
                reportActionError(it.message ?: "Failed to open file")
            }
            .getOrNull()

    suspend fun vcsInfo(): VcsInfo? =
        runCatching { clientFlow.value?.vcsInfo() }
            .onFailure {
                Log.w(TAG, "vcsInfo failed", it)
                reportActionError(it.message ?: "Failed to load VCS info")
            }
            .getOrNull()

    suspend fun vcsStatus(): List<VcsFileStatus> =
        runCatching { clientFlow.value?.vcsStatus() }
            .onFailure {
                Log.w(TAG, "vcsStatus failed", it)
                reportActionError(it.message ?: "Failed to load VCS status")
            }
            .getOrNull() ?: emptyList()

    suspend fun vcsDiff(mode: String): List<VcsFileDiff> =
        runCatching { clientFlow.value?.vcsDiff(mode) }
            .onFailure {
                Log.w(TAG, "vcsDiff($mode) failed", it)
                reportActionError(it.message ?: "Failed to load diff")
            }
            .getOrNull() ?: emptyList()

    suspend fun sessionDiff(sessionId: String): List<VcsFileDiff> =
        runCatching { clientFlow.value?.sessionDiff(sessionId) }
            .onFailure {
                Log.w(TAG, "sessionDiff($sessionId) failed", it)
                reportActionError(it.message ?: "Failed to load session changes")
            }
            .getOrNull() ?: emptyList()

    // --- Event handling ---

    /** Test hook: installs chat state without any network call. */
    internal fun setChatForTest(state: ChatState) {
        _chat.value = state
    }

    internal suspend fun handleEvent(client: OpenCodeClient, envelope: EventEnvelope) {
        // Any event counts as progress for the busy watchdog.
        lastChatEventAt = System.currentTimeMillis()
        // Some events (notably server.connected) may arrive without properties;
        // fall back to an empty object so they are not dropped wholesale.
        val props = envelope.properties ?: JsonObject(emptyMap())
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
                when (envelope.type) {
                    "session.deleted" -> sessionParentById.remove(session.id)
                    else -> session.parentID
                        ?.let { parentId -> sessionParentById[session.id] = parentId }
                        ?: sessionParentById.remove(session.id)
                }
                if (envelope.type == "session.updated") maybeUpdateChatTitle(props)
            }

            "message.updated" -> {
                val info = props.decodeMessage("info") ?: return
                if (appliesToCurrentChat(props)) {
                    // Replace the optimistic local echo with the server's copy.
                    val pending = pendingLocalMessageId
                    if (pending != null && info.role == USER_ROLE) {
                        pendingLocalMessageId = null
                        _chat.update { state ->
                            state.copy(messages = state.messages.filterNot { it.info.id == pending })
                        }
                    }
                    _chat.update { state -> state.upsertMessage(info) }
                }
            }

            "message.removed" -> {
                val messageId = props.stringOrNull("messageID") ?: return
                if (appliesToCurrentChat(props)) {
                    _chat.update { state ->
                        state.copy(messages = state.messages.filterNot { it.info.id == messageId })
                    }
                }
            }

            "message.part.updated" -> {
                val part = props.decodePart("part") ?: return
                if (appliesToCurrentChat(props)) {
                    // A full part supersedes any buffered deltas: apply them first
                    // so nothing is silently dropped.
                    flushDeltas()
                    _chat.update { state -> state.upsertPart(part) }
                }
            }

            "message.part.removed" -> {
                val partId = props.stringOrNull("partID") ?: return
                if (appliesToCurrentChat(props)) {
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
                if (!appliesToCurrentChat(props)) return
                val partId = props.stringOrNull("partID") ?: return
                val field = props.stringOrNull("field") ?: "text"
                val delta = props.stringOrNull("delta") ?: return
                enqueueDelta(partId, field, delta)
            }

            "session.idle" -> {
                if (sessionIdOf(props) == _chat.value.sessionId) {
                    flushDeltas()
                    _chat.update { state -> state.copy(busy = false) }
                }
            }

            "session.status" -> {
                if (sessionIdOf(props) != _chat.value.sessionId) return
                val status = props["status"]?.let { runCatching { it.jsonObject.stringOrNull("type") }.getOrNull() }
                val busy = status == "busy" || status == "retry"
                if (!busy) flushDeltas()
                _chat.update { state -> state.copy(busy = busy) }
                if (busy) armBusyWatchdog()
            }

            "session.error" -> {
                flushDeltas()
                val error = props.decodeSessionError()
                if (error?.name == MESSAGE_ABORTED_ERROR) {
                    if (appliesToCurrentChat(props)) {
                        // Abort is a normal cancellation, not a failure to show.
                        _chat.update { state -> state.copy(busy = false) }
                    }
                } else {
                    val message = formatSessionError(error, props["error"])
                    Log.w(TAG, "session.error: $message")
                    if (appliesToCurrentChat(props)) {
                        _chat.update { state -> state.copy(busy = false, error = message) }
                    } else if (sessionIdOf(props) == null) {
                        // Events without sessionID cannot be attributed; do not
                        // swallow them, surface on the global connection banner.
                        _connection.value = ConnectionState.Error(message)
                    }
                }
            }

            // Network reloads run off the event collector so a slow request cannot
            // stall event delivery (which would overflow the stream buffer).
            "permission.asked" -> scope.launch { loadPermissions(client) }
            "permission.replied" -> {
                val id = props.stringOrNull("requestID")
                if (id != null) _permissions.update { current -> current.filterNot { it.id == id } }
                else scope.launch { loadPermissions(client) }
            }

            "question.asked" -> scope.launch { loadQuestions(client) }
            "question.replied", "question.rejected" -> {
                val id = props.stringOrNull("requestID")
                if (id != null) _questions.update { current -> current.filterNot { it.id == id } }
                else scope.launch { loadQuestions(client) }
            }

            "todo.updated" -> {
                if (appliesToCurrentChat(props)) {
                    val todos = props["todos"]?.let {
                        runCatching { OpenCodeJson.decodeFromJsonElement(TODO_LIST_SERIALIZER, it) }.getOrNull()
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

    /**
     * True when [itemSessionId] is the target session or one of its descendants.
     * Subagent sessions run under their own id but belong to the open chat.
     */
    fun sessionMatches(itemSessionId: String, targetSessionId: String): Boolean =
        itemSessionId == targetSessionId || rootSessionId(itemSessionId) == targetSessionId

    private fun rootSessionId(sessionId: String): String {
        var current = sessionId
        val visited = HashSet<String>()
        while (visited.add(current)) {
            val parent = sessionParentById[current] ?: return current
            current = parent
        }
        return current
    }

    /**
     * The `sessionID` of `session.error` is optional. Without it the event cannot
     * be attributed precisely, so it applies to whichever chat is currently open.
     */
    private fun appliesToCurrentChat(props: JsonObject): Boolean {
        val targetSessionId = _chat.value.sessionId ?: return false
        val sessionId = sessionIdOf(props) ?: return true
        return sessionMatches(sessionId, targetSessionId)
    }

    private companion object {
        const val TAG = "AppRepository"
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
        const val BUSY_TIMEOUT_MILLIS = 300_000L
        const val DELTA_FLUSH_INTERVAL_MILLIS = 50L
        const val TEXT_PART_TYPE = "text"
        const val USER_ROLE = "user"
        const val MESSAGE_ABORTED_ERROR = "MessageAbortedError"
        val TODO_LIST_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(Todo.serializer())
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun JsonObject.decodeSession(key: String): Session? =
    this[key]?.let { runCatching { OpenCodeJson.decodeFromJsonElement(Session.serializer(), it) }.getOrNull() }

private fun JsonObject.decodeMessage(key: String): Message? =
    this[key]?.let { runCatching { OpenCodeJson.decodeFromJsonElement(Message.serializer(), it) }.getOrNull() }

private fun JsonObject.decodePart(key: String): Part? =
    this[key]?.let { runCatching { OpenCodeJson.decodeFromJsonElement(Part.serializer(), it) }.getOrNull() }

private fun JsonObject.decodeSessionError(): SessionErrorInfo? =
    this["error"]?.let {
        runCatching { OpenCodeJson.decodeFromJsonElement(SessionErrorInfo.serializer(), it) }.getOrNull()
    }

/**
 * Builds a human readable message from a `session.error` payload. The server
 * sends a discriminated [SessionErrorInfo.name] plus a data object that usually
 * contains `message`; when neither is present the raw JSON is shown instead of
 * swallowing the cause.
 */
internal fun formatSessionError(info: SessionErrorInfo?, raw: JsonElement?): String {
    val name = info?.name
    val serverMessage = info?.data?.stringOrNull("message")
    return when {
        !serverMessage.isNullOrBlank() && !name.isNullOrBlank() -> "$name: $serverMessage"
        !serverMessage.isNullOrBlank() -> serverMessage
        !name.isNullOrBlank() -> name
        raw != null -> raw.toString()
        else -> "Session error"
    }
}

/**
 * Picks a usable default model from the provider list. The server-declared
 * `default` map wins; otherwise the first model of a connected (or, failing
 * that, any) provider is used. Returns null when no model is available.
 */
internal fun resolveDefaultModel(providers: ProviderList): PromptModel? {
    providers.default.entries.firstOrNull { (providerId, modelId) ->
        providerId.isNotBlank() && modelId.isNotBlank()
    }?.let { (providerId, modelId) -> return PromptModel(providerId, modelId) }

    val providersById = providers.all.associateBy { it.id }
    val candidates = providers.connected.ifEmpty { providers.all.map { it.id } }
    candidates.forEach { providerId ->
        val modelId = providersById[providerId]?.firstModelId() ?: return@forEach
        return PromptModel(providerId, modelId)
    }

    providers.all.forEach { provider ->
        provider.firstModelId()?.let { return PromptModel(provider.id, it) }
    }
    return null
}

/** First non-blank model id of a provider, from the map key or the model itself. */
private fun Provider.firstModelId(): String? =
    models.keys.firstOrNull { it.isNotBlank() }
        ?: models.values.firstOrNull { it.id.isNotBlank() }?.id

private fun MessageWithParts.toUi(): ChatMessageUi = ChatMessageUi(info = info, parts = parts)

private fun localUserMessage(id: String, text: String): ChatMessageUi = ChatMessageUi(
    info = Message(id = id, role = "user"),
    parts = listOf(Part(id = "$id-part", messageID = id, type = "text", text = text)),
)

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
