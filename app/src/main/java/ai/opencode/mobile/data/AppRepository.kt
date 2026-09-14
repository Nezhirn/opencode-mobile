package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
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
import ai.opencode.mobile.data.remote.SessionModel
import ai.opencode.mobile.data.remote.TextPartInput
import ai.opencode.mobile.data.remote.Todo
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.data.remote.VcsInfo
import android.util.Log
import androidx.compose.runtime.Immutable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeout
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

    /**
     * Pending streaming text per "partId|field", flushed on a short interval.
     * Deltas are produced on the event collector and drained on a timer, i.e.
     * from two different threads, so every access goes through [deltaLock];
     * StringBuilder is not thread safe and a lost append cannot be recovered.
     */
    private val deltaLock = Any()
    private val deltaBuffers = LinkedHashMap<String, StringBuilder>()
    private var deltaFlushJob: Job? = null

    /**
     * Ids of locally echoed user messages, oldest first, each dropped when the
     * server echoes the corresponding message. A single slot lost the first send
     * when two prompts went out back to back: the second overwrote the slot and
     * the first server echo then removed the wrong bubble.
     */
    private val pendingLocalMessageIds = ConcurrentLinkedQueue<String>()

    /** Makes locally echoed ids unique even for two sends within the same millisecond. */
    private val localMessageCounter = AtomicLong()

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

    /**
     * Providers the opencode server reports as actually configured. Catalog-only
     * providers are never exposed: offering them would let the user pick a model
     * the server cannot run.
     */
    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    /** False until /provider has been read at least once. */
    private val _providersLoaded = MutableStateFlow(false)
    val providersLoaded: StateFlow<Boolean> = _providersLoaded.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _selectedModel = MutableStateFlow<PromptModel?>(null)
    val selectedModel: StateFlow<PromptModel?> = _selectedModel.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent: StateFlow<String?> = _selectedAgent.asStateFlow()

    /** Set when a previously chosen model is no longer configured on the server. */
    private val _modelNotice = MutableStateFlow<String?>(null)
    val modelNotice: StateFlow<String?> = _modelNotice.asStateFlow()

    /**
     * The user's stored pick, restored on start and kept in sync with the picker.
     * Read-modify-write happens from the UI thread, the restore coroutine and the
     * connection coroutine, so every access goes through [selectionLock].
     */
    private val selectionLock = Any()
    private var storedSelection = ModelSelection()

    /** True once the user picked explicitly; restoring must not overwrite that. */
    private var userPickedModel = false

    /** Completes once the stored pick has been read (or failed to read). */
    private val selectionRestored = CompletableDeferred<Unit>()

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
            // Restore the explicit pick before providers load; it is validated
            // against the configured list in reconcileSelection(), which waits on
            // selectionRestored so a cold start cannot discard the stored choice.
            try {
                // Bounded: a DataStore read that never emits would otherwise hang
                // loadProviders() on selectionRestored.await() forever, leaving the
                // app with no models at all.
                val restored = runCatching {
                    withTimeout(SELECTION_RESTORE_TIMEOUT_MILLIS) { settingsStore.modelSelection.first() }
                }.getOrNull()
                if (restored != null) {
                    synchronized(selectionLock) {
                        // A pick made while this read was in flight wins; otherwise
                        // the disk value would overwrite it and the next agent
                        // change would persist the stale model back.
                        if (!userPickedModel) storedSelection = restored
                    }
                    if (restored.hasModel && _selectedModel.value == null) {
                        _selectedModel.value = PromptModel(restored.providerId, restored.modelId)
                    }
                    if (restored.agent.isNotBlank() && _selectedAgent.value == null) {
                        _selectedAgent.value = restored.agent
                    }
                }
            } finally {
                selectionRestored.complete(Unit)
            }
        }
        scope.launch {
            combine(clientFlow, retryTick) { client, _ -> client }
                .collectLatest { client ->
                    if (client == null) {
                        _connection.value = ConnectionState.Disconnected
                        _sessions.update { emptyList() }
                        _permissions.update { emptyList() }
                        _questions.update { emptyList() }
                        clearConfiguredProviders()
                        return@collectLatest
                    }
                    // Also reset for a *new* client: pointing the app at another
                    // server must not validate models against the previous one's
                    // catalogue while the new list is still loading.
                    clearConfiguredProviders()
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
                client.events().collect { envelope ->
                    // A delivered event proves the stream is healthy: clear a
                    // stale error banner and reset the backoff, which otherwise
                    // stayed at 30s for the rest of the process after one blip.
                    attempt = 0
                    if (_connection.value !is ConnectionState.Connected) {
                        _connection.value = ConnectionState.Connected(serverVersion)
                    }
                    handleEvent(client, envelope)
                }
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
        synchronized(deltaLock) {
            deltaBuffers.getOrPut("$partId|$field") { StringBuilder() }.append(delta)
            // Scheduling inside the lock keeps the check-then-launch atomic, so a
            // burst of deltas cannot spawn a flush job per event.
            if (deltaFlushJob?.isActive != true) {
                deltaFlushJob = scope.launch {
                    delay(DELTA_FLUSH_INTERVAL_MILLIS)
                    flushDeltas()
                }
            }
        }
    }

    private fun clearDeltas() {
        synchronized(deltaLock) { deltaBuffers.clear() }
    }

    /**
     * Applies buffered streaming deltas in one state update. Streaming can emit
     * thousands of tokens per second; coalescing them keeps state updates (and
     * the resulting recompositions) bounded.
     */
    private fun flushDeltas() {
        val pending: Map<String, String> = synchronized(deltaLock) {
            // Released together with the buffer. Leaving it set until the timer
            // coroutine actually finished left a window in which a fresh delta
            // saw "a flush is already scheduled" and was never flushed — losing
            // the tail of a response when no further event followed.
            deltaFlushJob = null
            if (deltaBuffers.isEmpty()) return
            val drained = LinkedHashMap<String, String>(deltaBuffers.size)
            deltaBuffers.forEach { (key, buffer) -> if (buffer.isNotEmpty()) drained[key] = buffer.toString() }
            deltaBuffers.clear()
            drained
        }
        if (pending.isEmpty()) return
        _chat.update { state -> state.applyDeltas(pending) }
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
        _chat.value.sessionId?.let { openChat(it, force = true) }
    }

    suspend fun saveSettings(config: ConnectionSettings) {
        settingsStore.save(config)
    }

    private suspend fun loadSessions(client: OpenCodeClient) {
        runCatching { client.listSessions() }
            .onSuccess { list ->
                sessionParentById.clear()
                list.forEach { session ->
                    session.parentID?.let { parentId -> sessionParentById[session.id] = parentId }
                }
                // Subagent sessions belong to their parent chat, not to the list.
                // Blank/duplicate ids would also break the keyed LazyColumn.
                _sessions.value = list
                    .filter { it.parentID == null && it.id.isNotBlank() }
                    .distinctBy { it.id }
                    .sortedByDescending { session -> session.time?.updated ?: 0 }
            }
            .onFailure { Log.w(TAG, "loadSessions failed", it) }
    }

    private suspend fun loadProviders(client: OpenCodeClient) {
        runCatching { client.listProviders() }
            .onSuccess { result ->
                // Never reconcile against a selection that has not been read yet;
                // otherwise the first load would replace the stored pick with the
                // server default and then persist that replacement.
                selectionRestored.await()
                val configured = configuredProviders(result)
                _providers.value = configured
                // An empty result while the catalogue itself is non-empty means
                // this server does not report `connected`/`default` in a shape we
                // understand. Treating that as "nothing is configured" would wipe
                // the stored pick and block every send with no way back, so the
                // list is simply left unvalidated instead.
                val signalReadable = configured.isNotEmpty() || result.all.isEmpty()
                _providersLoaded.value = signalReadable
                if (signalReadable) {
                    reconcileSelection(result, configured)
                } else {
                    Log.w(TAG, "/provider returned ${result.all.size} providers but none configured")
                }
            }
            .onFailure { Log.w(TAG, "loadProviders failed", it) }
    }

    private fun clearConfiguredProviders() {
        _providers.value = emptyList()
        _providersLoaded.value = false
    }

    /**
     * Keeps [selectedModel] pointing at a model opencode is actually configured
     * for. A pick that is still valid is left untouched; one that is not (server
     * reconfigured, provider disconnected) is replaced by the server-declared
     * default and reported, so the model a prompt is sent with is never silently
     * different from the one that was chosen.
     */
    private fun reconcileSelection(providers: ProviderList, configured: List<Provider>) {
        val current = _selectedModel.value
        if (current != null && configured.offers(current)) {
            _modelNotice.value = null
            return
        }
        val fallback = resolveDefaultModel(providers)
        _selectedModel.value = fallback
        _modelNotice.value = when {
            current == null -> null
            fallback != null -> "${current.providerID}/${current.modelID} is not configured in opencode; " +
                "using ${fallback.providerID}/${fallback.modelID}."
            else -> "${current.providerID}/${current.modelID} is not configured in opencode."
        }
        // Only forget the stored pick when a usable replacement exists. Erasing it
        // because the only configured provider happens to be down right now would
        // lose the choice permanently.
        if (current != null && fallback != null) {
            updateStoredSelection { it.copy(providerId = "", modelId = "") }
        }
    }

    fun clearModelNotice() {
        _modelNotice.value = null
    }

    /** Serialises read-modify-write on the stored selection and persists the result. */
    private fun updateStoredSelection(transform: (ModelSelection) -> ModelSelection) {
        val next = synchronized(selectionLock) {
            transform(storedSelection).also { storedSelection = it }
        }
        scope.launch {
            runCatching { settingsStore.saveModelSelection(next) }
                .onFailure { Log.w(TAG, "saveModelSelection failed", it) }
        }
    }

    private suspend fun loadAgents(client: OpenCodeClient) {
        runCatching { client.listAgents() }
            .onSuccess { list ->
                // Name is the LazyColumn key in the picker, so blanks and
                // duplicates have to go.
                _agents.value = list
                    .filter { agent -> agent.hidden != true && agent.name.isNotBlank() }
                    .distinctBy { agent -> agent.name }
            }
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

    fun openChat(sessionId: String, force: Boolean = false) {
        // Re-entering the same chat (returning from the files screen, a
        // recomposition) must not wipe the state: doing so dropped buffered
        // deltas, reset scroll position and cleared `busy`, which made the Stop
        // button vanish mid-run. Refresh passes force to reload deliberately.
        if (!force && _chat.value.sessionId == sessionId && !_chat.value.loading) return
        val client = clientFlow.value ?: return
        // Cancel any in-flight load so a slower previous session cannot land in
        // the newly opened chat.
        loadChatJob?.cancel()
        clearDeltas()
        val session = _sessions.value.firstOrNull { it.id == sessionId }
        _chat.value = ChatState(
            sessionId = sessionId,
            title = session?.title.orEmpty(),
            loading = true,
        )
        session?.model?.let { adoptSessionModel(it) }
        loadChatJob = scope.launch { loadChat(client, sessionId) }
    }

    /**
     * Seeds the selection from the model the session last ran with, but only when
     * nothing is selected yet. Every prompt carries an explicit model, so the
     * current selection — not the session's history — decides what runs;
     * overwriting a deliberate pick just because a session was reopened would
     * make the displayed model differ from the one that gets used. Models that
     * are not configured are ignored.
     */
    private fun adoptSessionModel(model: SessionModel) {
        if (_selectedModel.value != null) return
        if (model.providerID.isBlank() || model.id.isBlank()) return
        val candidate = PromptModel(providerID = model.providerID, modelID = model.id)
        if (_providers.value.offers(candidate)) _selectedModel.value = candidate
    }

    /**
     * Clears the open chat. When [sessionId] is given the state is only cleared
     * if it still belongs to that session, so a ViewModel being destroyed cannot
     * wipe a chat that was opened afterwards.
     */
    fun closeChat(sessionId: String? = null) {
        if (sessionId != null && _chat.value.sessionId != sessionId) return
        loadChatJob?.cancel()
        clearDeltas()
        _chat.value = ChatState()
    }

    private suspend fun loadChat(client: OpenCodeClient, sessionId: String) {
        runCatching { client.getMessages(sessionId) }
            .onSuccess { messages ->
                // Duplicate or blank ids would crash the LazyColumn that keys on
                // them; the incremental paths dedupe, this bulk load did not.
                val ui = messages
                    .filter { it.info.id.isNotBlank() }
                    .distinctBy { it.info.id }
                    .map { it.toUi() }
                _chat.update { state -> state.withMessagesIfCurrent(sessionId, ui) }
            }
            .onFailure { error ->
                // Cancellation is not a failure: Refresh cancels this load and
                // immediately opens the same session again, so the id guard would
                // not stop a "Job was cancelled" banner from landing in the fresh
                // state.
                if (error is CancellationException) throw error
                Log.w(TAG, "loadChat($sessionId) messages failed", error)
                _chat.update { state -> state.withLoadErrorIfCurrent(sessionId, error.message) }
            }
        runCatching { client.todos(sessionId) }
            .onSuccess { todos ->
                _chat.update { state -> state.withTodosIfCurrent(sessionId, todos) }
            }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "loadChat($sessionId) todos failed", it)
            }
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
                _chat.updateIfCurrent(sessionId) { state ->
                    state.copy(
                        error = "No model selected. Connect a provider in opencode (/connect), " +
                            "then pick a model here.",
                    )
                }
                return@launch
            }
            // Enforced only against a list we actually managed to read. A failed
            // or unreadable /provider response must not block a setup that was
            // working a minute ago.
            val configured = _providers.value
            if (_providersLoaded.value && configured.isNotEmpty() && !configured.offers(model)) {
                _chat.updateIfCurrent(sessionId) { state ->
                    state.copy(
                        error = "${model.providerID}/${model.modelID} is not configured in opencode. " +
                            "Pick a configured model.",
                    )
                }
                return@launch
            }
            val localId = "local-${System.currentTimeMillis()}-${localMessageCounter.incrementAndGet()}"
            pendingLocalMessageIds.add(localId)
            // Every write is guarded by the session: switching chats right after
            // sending must not drop this message (or a "busy" flag) into the chat
            // the user moved to.
            _chat.updateIfCurrent(sessionId) { state ->
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
                    if (error is CancellationException) throw error
                    Log.w(TAG, "sendPrompt($sessionId) failed", error)
                    pendingLocalMessageIds.remove(localId)
                    _chat.updateIfCurrent(sessionId) { state ->
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

    /** Records an explicit pick so it survives a restart. */
    fun selectModel(model: PromptModel?) {
        _selectedModel.value = model
        _modelNotice.value = null
        synchronized(selectionLock) { userPickedModel = true }
        updateStoredSelection {
            it.copy(
                providerId = model?.providerID.orEmpty(),
                modelId = model?.modelID.orEmpty(),
            )
        }
    }

    fun selectAgent(agent: String?) {
        _selectedAgent.value = agent
        updateStoredSelection { it.copy(agent = agent.orEmpty()) }
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
        // Some events (notably server.connected) may arrive without properties;
        // fall back to an empty object so they are not dropped wholesale.
        val props = envelope.properties ?: JsonObject(emptyMap())
        // Only events for the open chat count as progress for the busy watchdog.
        // Counting every event meant a second session (or another client) kept
        // the timer alive forever, so a run whose session.idle was lost during a
        // stream reconnect stayed "busy" for good.
        if (appliesToCurrentChat(props)) lastChatEventAt = System.currentTimeMillis()
        when (envelope.type) {
            "server.connected" -> {
                if (_connection.value !is ConnectionState.Connected) {
                    _connection.value = ConnectionState.Connected(serverVersion)
                }
            }

            "session.created", "session.updated", "session.deleted" -> {
                val session = props.decodeSession("info") ?: return
                // Subagent sessions are created under a parent and must not show
                // up as entries in the session list; their parent link is still
                // recorded below so their events can be attributed.
                val listed = session.parentID == null
                _sessions.update { current ->
                    when {
                        envelope.type == "session.deleted" -> current.filterNot { it.id == session.id }
                        !listed -> current.filterNot { it.id == session.id }
                        envelope.type == "session.created" -> (current.filterNot { it.id == session.id } + session)
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
                if (!appliesToCurrentChat(props)) return
                // The envelope of message.updated carries no top-level sessionID,
                // so appliesToCurrentChat() alone lets every session through. The
                // message itself names its session: without this check a subagent
                // run (or another client) injected empty ghost bubbles here, and a
                // foreign user message consumed the pending local echo, making the
                // user's own text disappear.
                if (info.sessionID.isNotBlank() && info.sessionID != _chat.value.sessionId) return
                // Replace the optimistic local echo with the server's copy in a
                // single update, so the list never renders without either one.
                val pending = if (info.role == USER_ROLE) pendingLocalMessageIds.poll() else null
                _chat.update { state ->
                    val base = if (pending == null) {
                        state
                    } else {
                        state.copy(messages = state.messages.filterNot { it.info.id == pending })
                    }
                    base.upsertMessage(info)
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
                    // A part that belongs to this exact session but arrives before
                    // its message.updated would otherwise be dropped for good.
                    // Parts from subagent sessions keep being ignored: they belong
                    // to a child session, not to the messages on screen.
                    val ownSession = part.sessionID.isBlank() || part.sessionID == _chat.value.sessionId
                    _chat.update { state -> state.upsertPart(part, createMissingMessage = ownSession) }
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
                        // Unattributable, but not something to swallow. It goes to
                        // the dismissible sessions banner rather than the
                        // connection state: the stream is alive, and a connection
                        // error that nothing ever clears made that banner
                        // untrustworthy.
                        _sessionError.value = message
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
        const val SELECTION_RESTORE_TIMEOUT_MILLIS = 5_000L
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
 * Providers opencode is actually configured for, in the order the server reports
 * them. `all` is the full models.dev catalog, so it must never be used as a
 * fallback: everything in it is selectable but most of it is not runnable.
 * Servers that do not populate `connected` still declare a default model per
 * configured provider, which is used as a secondary signal.
 */
internal fun configuredProviders(providers: ProviderList): List<Provider> {
    val byId = providers.all.associateBy { it.id }
    val ids = providers.connected.ifEmpty { providers.default.keys.toList() }
    return ids.distinct()
        .mapNotNull { byId[it] }
        .filter { it.modelIds().isNotEmpty() }
}

/** Model ids of a provider, taken from the map key and falling back to the model itself. */
internal fun Provider.modelIds(): List<String> =
    models.entries
        .mapNotNull { (key, model) -> key.ifBlank { model.id }.takeIf { it.isNotBlank() } }
        .distinct()

/** True when [model] is offered by one of these providers. */
internal fun List<Provider>.offers(model: PromptModel): Boolean =
    any { it.id == model.providerID && it.modelIds().contains(model.modelID) }

/**
 * Picks a default model from the providers opencode is configured for, so a new
 * session can send a prompt without opening the picker first. The server's
 * `default` entry for a configured provider wins; otherwise the first model of
 * the first configured provider is used. Returns null when nothing is
 * configured — sending is then blocked with an explicit message rather than
 * failing server-side.
 */
internal fun resolveDefaultModel(providers: ProviderList): PromptModel? {
    val configured = configuredProviders(providers)
    configured.forEach { provider ->
        val declared = providers.default[provider.id]?.takeIf { it.isNotBlank() } ?: return@forEach
        if (provider.modelIds().contains(declared)) return PromptModel(provider.id, declared)
    }
    configured.forEach { provider ->
        provider.modelIds().firstOrNull()?.let { return PromptModel(provider.id, it) }
    }
    return null
}

/**
 * Applies [transform] only while the open chat still belongs to [sessionId], so a
 * write queued before the user switched sessions cannot land in the new one.
 */
private inline fun MutableStateFlow<ChatState>.updateIfCurrent(
    sessionId: String,
    crossinline transform: (ChatState) -> ChatState,
) = update { state -> if (state.sessionId != sessionId) state else transform(state) }

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

/**
 * Inserts or replaces [part] on its message. When the message is not on screen
 * yet and [createMissingMessage] is set, a stub is appended so a part that
 * arrives before its `message.updated` is not lost; the stub is filled in when
 * that event lands.
 */
internal fun ChatState.upsertPart(part: Part, createMissingMessage: Boolean = false): ChatState {
    val messageIndex = messages.indexOfFirst { it.info.id == part.messageID }
    if (messageIndex < 0) {
        if (!createMissingMessage || part.messageID.isBlank()) return this
        val stub = ChatMessageUi(
            info = Message(id = part.messageID, sessionID = part.sessionID),
            parts = listOf(part),
        )
        return copy(messages = messages + stub)
    }
    val message = messages[messageIndex]
    val partIndex = message.parts.indexOfFirst { it.id == part.id }
    val parts = message.parts.toMutableList()
    if (partIndex >= 0) parts[partIndex] = part else parts.add(part)
    return copy(messages = messages.toMutableList().also { it[messageIndex] = message.copy(parts = parts) })
}

/**
 * Guards against stale loads: results only apply when the chat still belongs to
 * [sessionId]. Prevents a slow previous session from overwriting a newly opened
 * one.
 */
internal fun ChatState.withMessagesIfCurrent(sessionId: String, messages: List<ChatMessageUi>): ChatState =
    if (this.sessionId != sessionId) this else copy(messages = messages, loading = false, error = null)

internal fun ChatState.withLoadErrorIfCurrent(sessionId: String, message: String?): ChatState =
    if (this.sessionId != sessionId) this else copy(loading = false, error = message)

internal fun ChatState.withTodosIfCurrent(sessionId: String, todos: List<Todo>): ChatState =
    if (this.sessionId != sessionId) this else copy(todos = todos)

internal fun ChatState.applyDelta(partId: String, field: String, delta: String): ChatState =
    applyDeltas(mapOf("$partId|$field" to delta))

/**
 * Applies a whole batch of buffered deltas in a single pass over the message
 * list. Doing it per delta meant rescanning every message and copying the list
 * once per key, which is the dominant cost while a long chat is streaming.
 * Keys are "partId|field"; messages without a touched part keep their identity
 * so Compose can skip them.
 */
internal fun ChatState.applyDeltas(pending: Map<String, String>): ChatState {
    if (pending.isEmpty()) return this
    val byPart = HashMap<String, StringBuilder>(pending.size)
    pending.forEach { (key, delta) ->
        val separator = key.indexOf('|')
        if (separator <= 0 || delta.isEmpty()) return@forEach
        val field = key.substring(separator + 1)
        if (field != "text" && field != "reasoning") return@forEach
        byPart.getOrPut(key.substring(0, separator)) { StringBuilder() }.append(delta)
    }
    if (byPart.isEmpty()) return this

    var changed = false
    val updatedMessages = messages.map { message ->
        if (message.parts.none { byPart.containsKey(it.id) }) return@map message
        changed = true
        message.copy(
            parts = message.parts.map { part ->
                val delta = byPart[part.id]
                if (delta == null) part else part.copy(text = (part.text ?: "") + delta)
            },
        )
    }
    return if (changed) copy(messages = updatedMessages) else this
}
