package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.CreateSessionRequest
import ai.opencode.mobile.data.remote.EventEnvelope
import ai.opencode.mobile.data.remote.FileContent
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.OpenCodeException
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.data.remote.VcsInfo
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of app state. Owns the connection and the event stream, the
 * session list and the pending permission/question requests, and routes chat and
 * model-selection concerns to [ChatController] and [SelectionStore].
 */
class AppRepository(private val settingsStore: SettingsSource) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var serverVersion: String? = null

    private val sessionTree = SessionTree()

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
     * Permission/question ids whose answer is on its way. The card stays until
     * the server confirms, so without this a second tap sent a second answer.
     */
    private val _replying = MutableStateFlow<Set<String>>(emptySet())
    val replying: StateFlow<Set<String>> = _replying.asStateFlow()

    private val _creatingSession = MutableStateFlow(false)
    val creatingSession: StateFlow<Boolean> = _creatingSession.asStateFlow()

    private val _deletingSessions = MutableStateFlow<Set<String>>(emptySet())
    val deletingSessions: StateFlow<Set<String>> = _deletingSessions.asStateFlow()

    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    /** Transient error from a user action (files/VCS) that UI should surface. */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /** Incremented to force the event stream to restart (see [refresh]). */
    private val retryTick = MutableStateFlow(0)

    private val selection = SelectionStore(scope, settingsStore)

    private val chatController = ChatController(
        scope = scope,
        clientProvider = { clientFlow.value },
        sessionTree = sessionTree,
        selection = selection,
        sessionById = { id -> _sessions.value.firstOrNull { it.id == id } },
        awaitingUserInput = { sessionId ->
            _permissions.value.any { sessionTree.matches(it.sessionID, sessionId) } ||
                _questions.value.any { sessionTree.matches(it.sessionID, sessionId) }
        },
        onUnattributedError = { message -> _sessionError.value = message },
    )

    val chat: StateFlow<ChatState> = chatController.state
    val providers: StateFlow<List<Provider>> = selection.providers
    val providersLoaded: StateFlow<Boolean> = selection.providersLoaded
    val agents: StateFlow<List<Agent>> = selection.agents
    val selectedModel: StateFlow<PromptModel?> = selection.selectedModel
    val selectedAgent: StateFlow<String?> = selection.selectedAgent

    /** Set when a previously chosen model or agent is no longer available. */
    val modelNotice: StateFlow<String?> = selection.notice

    /** Models shown in the picker; null while every configured model is shown. */
    val enabledModels: StateFlow<Set<String>?> = selection.enabledModels

    init {
        scope.launch {
            // Avoid a flash of the connect screen before DataStore has loaded.
            settingsStore.settings.first()
            _settingsLoaded.value = true
        }
        selection.restore()
        scope.launch {
            combine(clientFlow, retryTick) { client, _ -> client }
                .collectLatest { client ->
                    selection.bindServer(client?.baseUrl)
                    if (client == null) {
                        _connection.value = ConnectionState.Disconnected
                        _sessions.value = emptyList()
                        _permissions.value = emptyList()
                        _questions.value = emptyList()
                        selection.clearProviders()
                        return@collectLatest
                    }
                    // Also reset for a *new* client: pointing the app at another
                    // server must not validate models against the previous one's
                    // catalogue while the new list is still loading.
                    selection.clearProviders()
                    refreshConnection(client)
                    collectEvents(client)
                }
        }
    }

    // --- Connection and event stream ---

    /**
     * Keeps the event stream alive across clean server closes and transient
     * failures, with exponential backoff. Permanent client errors (4xx other than
     * 429) stop the loop to avoid hammering the server, but [refresh] restarts it
     * so fixing credentials or a route recovers without an app restart.
     *
     * Every reconnect after the first is followed by a resync: events emitted
     * while the stream was down are not replayed, and without reloading, the chat
     * kept a stale busy flag, partial text and permission cards that were never
     * shown (the run then waited for an answer nobody could give).
     */
    private suspend fun collectEvents(client: OpenCodeClient) {
        var attempt = 0
        var streamedBefore = false
        while (true) {
            try {
                val health = StreamHealth()
                coroutineScope {
                    val staleWatch = launch { health.watch() }
                    client.events().collect { envelope ->
                        health.onEvent(envelope.type)
                        if (health.markOpened()) {
                            if (streamedBefore) scope.launch { resync(client) }
                            streamedBefore = true
                        }
                        // A delivered event proves the stream is healthy: clear a
                        // stale error banner and reset the backoff.
                        attempt = 0
                        if (_connection.value !is ConnectionState.Connected) {
                            _connection.value = ConnectionState.Connected(serverVersion)
                        }
                        handleEvent(client, envelope)
                    }
                    staleWatch.cancel()
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

    /**
     * Detects a half-open event stream. The SSE client has no read timeout (the
     * stream is idle by design), so after a network switch or a NAT timeout the
     * socket could stay "connected" forever while nothing arrived. opencode sends
     * `server.heartbeat` every ~10 s; once one has been seen, silence well past
     * that interval fails the stream so the normal reconnect path takes over.
     * Servers without heartbeats are never timed out.
     */
    private class StreamHealth {
        private val lastEventAt = AtomicLong(System.currentTimeMillis())
        private val heartbeats = AtomicBoolean(false)
        private val opened = AtomicBoolean(false)

        fun onEvent(type: String?) {
            lastEventAt.set(System.currentTimeMillis())
            if (type == HEARTBEAT_EVENT) heartbeats.set(true)
        }

        /** True exactly once, for the first event of this stream. */
        fun markOpened(): Boolean = opened.compareAndSet(false, true)

        suspend fun watch() {
            while (true) {
                delay(STREAM_CHECK_INTERVAL_MILLIS)
                val silence = System.currentTimeMillis() - lastEventAt.get()
                if (heartbeats.get() && silence > STREAM_STALE_MILLIS) {
                    throw IOException("Event stream went silent for ${silence / 1000}s")
                }
            }
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 5)
        return (RECONNECT_DELAY_MILLIS * multiplier).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
    }

    fun reconnect() {
        retryTick.update { it + 1 }
    }

    private suspend fun refreshConnection(client: OpenCodeClient) {
        if (_connection.value !is ConnectionState.Connected) {
            _connection.value = ConnectionState.Connecting
        }
        val health = catchingNonCancellation { client.health() }
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

    /** Reloads everything the event stream would have updated while it was down. */
    private suspend fun resync(client: OpenCodeClient) {
        Log.i(TAG, "event stream reconnected; resyncing")
        loadSessions(client)
        loadPermissions(client)
        loadQuestions(client)
        chatController.resync()
    }

    fun refresh() {
        // Restart the event stream as well: a Refresh tap must recover from a
        // stream that stopped on a 4xx without requiring an app restart.
        reconnect()
        chat.value.sessionId?.let { openChat(it, force = true) }
    }

    suspend fun saveSettings(config: ConnectionSettings) {
        settingsStore.save(config)
    }

    private suspend fun loadSessions(client: OpenCodeClient) {
        catchingNonCancellation { client.listSessions() }
            .onSuccess { list ->
                sessionTree.reset(list)
                // Subagent sessions belong to their parent chat, not to the list.
                // Blank/duplicate ids would also break the keyed LazyColumn.
                _sessions.value = list
                    .filter { it.parentID == null && it.id.isNotBlank() }
                    .distinctBy { it.id }
                    .sortedByDescending { session -> session.time?.updated ?: 0 }
            }
            .onFailure { Log.w(TAG, "loadSessions failed", it) }
    }

    /**
     * The picker must list only what opencode can actually run. `/config/providers`
     * returns exactly that; `/provider` is the whole models.dev catalogue and is
     * only a fallback for servers without the former, filtered by `connected`.
     */
    private suspend fun loadProviders(client: OpenCodeClient) {
        val list = catchingNonCancellation { client.configProviders().asProviderList() }
            .onFailure { Log.w(TAG, "/config/providers failed; falling back to /provider", it) }
            .getOrNull()
            ?: catchingNonCancellation { client.listProviders() }
                .onFailure { Log.w(TAG, "loadProviders failed", it) }
                .getOrNull()
            ?: return
        selection.applyProviders(list)
    }

    private suspend fun loadAgents(client: OpenCodeClient) {
        catchingNonCancellation { client.listAgents() }
            .onSuccess { selection.applyAgents(it) }
            .onFailure { Log.w(TAG, "loadAgents failed", it) }
    }

    private suspend fun loadPermissions(client: OpenCodeClient) {
        catchingNonCancellation { client.listPermissions() }
            .onSuccess { list -> _permissions.value = list }
            .onFailure { Log.w(TAG, "loadPermissions failed", it) }
    }

    private suspend fun loadQuestions(client: OpenCodeClient) {
        catchingNonCancellation { client.listQuestions() }
            .onSuccess { list -> _questions.value = list }
            .onFailure { Log.w(TAG, "loadQuestions failed", it) }
    }

    // --- Session actions ---

    /**
     * Creates a session and returns its id, or null when it could not be created
     * (or a creation is already in flight). Runs in the repository scope so the
     * request is not cancelled together with the screen that asked for it.
     */
    suspend fun createSession(title: String? = null): String? {
        if (!_creatingSession.compareAndSet(expect = false, update = true)) return null
        _sessionError.value = null
        return scope.async {
            try {
                val client = clientFlow.value ?: return@async null
                catchingNonCancellation { client.createSession(CreateSessionRequest(title = title)) }
                    .onSuccess { loadSessions(client) }
                    .onFailure {
                        Log.w(TAG, "createSession failed", it)
                        _sessionError.value = it.message ?: "Failed to create session"
                    }
                    .getOrNull()
                    ?.id
            } finally {
                _creatingSession.value = false
            }
        }.await()
    }

    fun clearSessionError() {
        _sessionError.value = null
    }

    fun deleteSession(sessionId: String) {
        if (!markIn(_deletingSessions, sessionId)) return
        scope.launch {
            try {
                val client = clientFlow.value ?: return@launch
                catchingNonCancellation { client.deleteSession(sessionId) }
                    .onSuccess {
                        _sessionError.value = null
                        _sessions.update { current -> current.filterNot { it.id == sessionId } }
                        sessionTree.remove(sessionId)
                        chatController.closeIfShowing(sessionId)
                    }
                    .onFailure {
                        Log.w(TAG, "deleteSession($sessionId) failed", it)
                        _sessionError.value = it.message ?: "Failed to delete session"
                    }
            } finally {
                _deletingSessions.update { it - sessionId }
            }
        }
    }

    // --- Chat ---

    fun openChat(sessionId: String, force: Boolean = false) = chatController.open(sessionId, force)

    fun closeChat(sessionId: String? = null) = chatController.close(sessionId)

    /** Completes with whether the server accepted the prompt. */
    fun sendPrompt(text: String): Deferred<Boolean> = chatController.sendPrompt(text)

    fun abort() = chatController.abort()

    fun selectModel(model: PromptModel?) = selection.selectModel(model)

    fun selectAgent(agent: String?) = selection.selectAgent(agent)

    fun clearModelNotice() = selection.clearNotice()

    fun setModelEnabled(model: PromptModel, enabled: Boolean) = selection.setModelEnabled(model, enabled)

    fun setProviderEnabled(providerId: String, enabled: Boolean) = selection.setProviderEnabled(providerId, enabled)

    fun showAllModels() = selection.showAllModels()

    /** True when [itemSessionId] is [targetSessionId] or one of its subagent sessions. */
    fun sessionMatches(itemSessionId: String, targetSessionId: String): Boolean =
        sessionTree.matches(itemSessionId, targetSessionId)

    // --- Permissions and questions ---

    fun replyPermission(requestId: String, reply: String) =
        answer(
            requestId = requestId,
            failure = "Failed to reply to permission",
            onAnswered = { _permissions.update { current -> current.filterNot { it.id == requestId } } },
        ) { client -> client.replyPermission(requestId, reply) }

    fun replyQuestion(requestId: String, answers: List<List<String>>) =
        answer(
            requestId = requestId,
            failure = "Failed to reply to question",
            onAnswered = { removeQuestion(requestId) },
        ) { client -> client.replyQuestion(requestId, answers) }

    fun rejectQuestion(requestId: String) =
        answer(
            requestId = requestId,
            failure = "Failed to reject question",
            onAnswered = { removeQuestion(requestId) },
        ) { client -> client.rejectQuestion(requestId) }

    private fun removeQuestion(requestId: String) {
        _questions.update { current -> current.filterNot { it.id == requestId } }
    }

    /** Sends one answer per request: taps while it is in flight are ignored. */
    private fun answer(
        requestId: String,
        failure: String,
        onAnswered: () -> Unit,
        call: suspend (OpenCodeClient) -> Unit,
    ) {
        if (!markIn(_replying, requestId)) return
        scope.launch {
            try {
                val client = clientFlow.value ?: return@launch
                catchingNonCancellation { call(client) }
                    .onSuccess { onAnswered() }
                    .onFailure {
                        Log.w(TAG, "answer($requestId) failed", it)
                        chatController.showError(it.message ?: failure)
                    }
            } finally {
                _replying.update { it - requestId }
            }
        }
    }

    // --- Files and VCS ---

    // Each action clears the previous error first: a failure used to stay on
    // screen above content that had loaded fine since.

    suspend fun listFiles(path: String): List<FileNode> =
        fileAction("Failed to list files") { it.listFiles(path) } ?: emptyList()

    suspend fun readFile(path: String): FileContent? =
        fileAction("Failed to open file") { it.readFile(path) }

    suspend fun vcsInfo(): VcsInfo? = fileAction("Failed to load VCS info") { it.vcsInfo() }

    suspend fun vcsStatus(): List<VcsFileStatus> =
        fileAction("Failed to load VCS status") { it.vcsStatus() } ?: emptyList()

    suspend fun vcsDiff(mode: String): List<VcsFileDiff> =
        fileAction("Failed to load diff") { it.vcsDiff(mode) } ?: emptyList()

    suspend fun sessionDiff(sessionId: String): List<VcsFileDiff> =
        fileAction("Failed to load session changes") { it.sessionDiff(sessionId) } ?: emptyList()

    fun clearActionError() {
        _actionError.value = null
    }

    private suspend fun <T> fileAction(failure: String, call: suspend (OpenCodeClient) -> T): T? {
        _actionError.value = null
        val client = clientFlow.value ?: return null
        return catchingNonCancellation { call(client) }
            .onFailure {
                Log.w(TAG, failure, it)
                _actionError.value = it.message ?: failure
            }
            .getOrNull()
    }

    // --- Event handling ---

    /** Test hook: installs chat state without any network call. */
    internal fun setChatForTest(state: ChatState) = chatController.setForTest(state)

    internal suspend fun handleEvent(client: OpenCodeClient, envelope: EventEnvelope) {
        val type = envelope.type ?: return
        // Some events (notably server.connected) may arrive without properties;
        // fall back to an empty object so they are not dropped wholesale.
        val props = envelope.properties ?: JsonObject(emptyMap())
        when (type) {
            "server.connected" -> {
                if (_connection.value !is ConnectionState.Connected) {
                    _connection.value = ConnectionState.Connected(serverVersion)
                }
            }

            HEARTBEAT_EVENT -> Unit

            "session.created", "session.updated", "session.deleted" -> handleSessionEvent(type, props)

            "permission.asked" -> {
                val request = props.decodePermissionRequest()
                if (request != null) {
                    _permissions.update { current -> current.filterNot { it.id == request.id } + request }
                } else {
                    // Network reloads run off the event collector so a slow
                    // request cannot stall event delivery.
                    scope.launch { loadPermissions(client) }
                }
            }

            "permission.replied" -> {
                val id = props.stringOrNull("requestID")
                if (id != null) {
                    _permissions.update { current -> current.filterNot { it.id == id } }
                } else {
                    scope.launch { loadPermissions(client) }
                }
            }

            "question.asked" -> {
                val request = props.decodeQuestionRequest()
                if (request != null) {
                    _questions.update { current -> current.filterNot { it.id == request.id } + request }
                } else {
                    scope.launch { loadQuestions(client) }
                }
            }

            "question.replied", "question.rejected" -> {
                val id = props.stringOrNull("requestID")
                if (id != null) {
                    _questions.update { current -> current.filterNot { it.id == id } }
                } else {
                    scope.launch { loadQuestions(client) }
                }
            }

            else -> chatController.handleEvent(type, props)
        }
    }

    private fun handleSessionEvent(type: String, props: JsonObject) {
        val session = props.decodeSession("info") ?: return
        // Subagent sessions are created under a parent and must not show up as
        // entries in the session list; their parent link is still recorded so
        // their events can be attributed.
        val listed = session.parentID == null
        _sessions.update { current ->
            when {
                type == "session.deleted" || !listed -> current.filterNot { it.id == session.id }
                type == "session.created" -> (current.filterNot { it.id == session.id } + session)
                    .sortedByDescending { it.time?.updated ?: 0 }
                else -> current.map { if (it.id == session.id) session else it }
            }
        }
        if (type == "session.deleted") sessionTree.remove(session.id) else sessionTree.record(session)
        if (type == "session.updated") chatController.onSessionUpdated(session)
    }

    private companion object {
        const val TAG = "AppRepository"
        const val HEARTBEAT_EVENT = "server.heartbeat"
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
        const val STREAM_CHECK_INTERVAL_MILLIS = 10_000L
        const val STREAM_STALE_MILLIS = 45_000L
    }
}

/** Adds [id] to the set unless present; true when this call added it. */
private fun markIn(set: MutableStateFlow<Set<String>>, id: String): Boolean {
    while (true) {
        val current = set.value
        if (id in current) return false
        if (set.compareAndSet(current, current + id)) return true
    }
}
