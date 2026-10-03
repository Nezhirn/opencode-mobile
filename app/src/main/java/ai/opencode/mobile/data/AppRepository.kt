package ai.opencode.mobile.data

import ai.opencode.mobile.R
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
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** False until the session list of the current server has been read (or failed to be). */
    private val _sessionsLoaded = MutableStateFlow(false)
    val sessionsLoaded: StateFlow<Boolean> = _sessionsLoaded.asStateFlow()

    /** Bumped by every permission/question event; see [loadSnapshot]. */
    private val permissionsEpoch = AtomicLong()
    private val questionsEpoch = AtomicLong()

    private val _deletingSessions = MutableStateFlow<Set<String>>(emptySet())
    val deletingSessions: StateFlow<Set<String>> = _deletingSessions.asStateFlow()

    private val _sessionError = MutableStateFlow<UiText?>(null)
    val sessionError: StateFlow<UiText?> = _sessionError.asStateFlow()

    /** Transient error from a user action (files/VCS) that UI should surface. */
    private val _actionError = MutableStateFlow<UiText?>(null)
    val actionError: StateFlow<UiText?> = _actionError.asStateFlow()

    /** MCP servers of the connected opencode; null until the first load. */
    private val _mcpServers = MutableStateFlow<List<McpServer>?>(null)
    val mcpServers: StateFlow<List<McpServer>?> = _mcpServers.asStateFlow()

    /** MCP server names whose connect/disconnect is in flight. */
    private val _mcpToggling = MutableStateFlow<Set<String>>(emptySet())
    val mcpToggling: StateFlow<Set<String>> = _mcpToggling.asStateFlow()

    private val _mcpError = MutableStateFlow<UiText?>(null)
    val mcpError: StateFlow<UiText?> = _mcpError.asStateFlow()

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

    /** Reasoning-effort variants of the selected model; empty when it has none. */
    val availableVariants: StateFlow<List<String>> = selection.availableVariants
    val selectedVariant: StateFlow<String?> = selection.selectedVariant

    /** Set when a previously chosen model or agent is no longer available. */
    val modelNotice: StateFlow<UiText?> = selection.notice

    /** Models shown in the picker; null while every configured model is shown. */
    val enabledModels: StateFlow<Set<String>?> = selection.enabledModels

    /**
     * False once the app has stayed in the background for a while: the event
     * stream is then closed instead of parsing every token for nobody (battery,
     * data), and reopened — with a full sync — when the app comes back.
     */
    private val streamWanted = MutableStateFlow(true)
    private val foregroundLock = Any()
    private var backgroundJob: Job? = null

    init {
        scope.launch {
            // Avoid a flash of the connect screen before DataStore has loaded.
            settingsStore.settings.first()
            _settingsLoaded.value = true
        }
        selection.restore()
        scope.launch {
            var boundUrl: String? = null
            combine(clientFlow, retryTick, streamWanted) { client, _, wanted -> client to wanted }
                .collectLatest { (client, wanted) ->
                    selection.bindServer(client?.baseUrl)
                    if (client?.baseUrl != boundUrl) {
                        // Another server (or none): nothing of the previous one may
                        // stay on screen — its sessions, cards and chat would answer
                        // 404 here — and models must not be validated against its
                        // catalogue while the new list is still loading.
                        boundUrl = client?.baseUrl
                        clearServerData()
                    }
                    if (client == null) {
                        _connection.value = ConnectionState.Disconnected
                        return@collectLatest
                    }
                    // Paused in the background: the data stays, the stream does not.
                    if (!wanted) return@collectLatest
                    // Work started for this client (syncs) lives in this scope, so
                    // switching servers cancels it instead of letting a late answer
                    // of the old server overwrite the new one.
                    coroutineScope { collectEvents(client, this) }
                }
        }
    }

    /** Called by the app as it enters and leaves the foreground. */
    fun setForeground(foreground: Boolean) {
        synchronized(foregroundLock) {
            backgroundJob?.cancel()
            backgroundJob = null
            if (foreground) {
                streamWanted.value = true
            } else {
                // A short trip elsewhere (a link, the share sheet) keeps the stream.
                backgroundJob = scope.launch {
                    delay(BACKGROUND_GRACE_MILLIS)
                    streamWanted.value = false
                }
            }
        }
    }

    private fun clearServerData() {
        _mcpServers.value = null
        _mcpError.value = null
        _sessions.value = emptyList()
        _sessionsLoaded.value = false
        _permissions.value = emptyList()
        _questions.value = emptyList()
        sessionTree.reset(emptyList())
        chatController.close()
        selection.clearProviders()
    }

    // --- Connection and event stream ---

    /**
     * Keeps the event stream alive across clean server closes and transient
     * failures, with exponential backoff. Permanent client errors (4xx other than
     * 429) stop the loop to avoid hammering the server, but [refresh] restarts it
     * so fixing credentials or a route recovers without an app restart.
     *
     * Every time a stream opens — the first one included — everything is
     * (re)loaded from the server. Events are never replayed, so loading before
     * subscribing lost whatever happened in between (a permission asked then was
     * never shown and the run waited for an answer nobody could give), and a
     * failed first health check used to leave the app with no sessions or models
     * for good. Syncs run in [syncScope], next to the stream: a dropped stream
     * does not cancel them, a server switch does.
     */
    private suspend fun collectEvents(client: OpenCodeClient, syncScope: CoroutineScope) {
        var attempt = 0
        var streamedBefore = false
        var syncJob: Job? = null
        if (_connection.value !is ConnectionState.Connected) {
            _connection.value = ConnectionState.Connecting
        }
        while (true) {
            val health = StreamHealth()
            try {
                coroutineScope {
                    val staleWatch = launch { health.watch() }
                    client.events().collect { envelope ->
                        health.onEvent(envelope.type)
                        if (health.markOpened()) {
                            streamedBefore = true
                            _connection.value = ConnectionState.Connected(serverVersion)
                            syncJob?.cancel()
                            syncJob = syncScope.launch { sync(client) }
                        }
                        handleEvent(client, envelope)
                    }
                    staleWatch.cancel()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _connection.value = ConnectionState.Error(
                    when {
                        error is StreamSilentException -> uiText(R.string.error_stream_silent, error.seconds)
                        streamedBefore -> error.toUiText(R.string.error_event_stream)
                        else -> error.toUiText(R.string.error_connection)
                    },
                )
                val code = (error as? OpenCodeException)?.code
                if (code != null && code in 400..499 && code != 429) {
                    Log.w(TAG, "event stream stopped with HTTP $code", error)
                    return
                }
            }
            // Clean close (e.g. server restart) or failure: reconnect after a
            // pause. Only a stream that stayed up for a while resets the backoff;
            // one that drops right after opening would otherwise reconnect, and
            // reload everything, every two seconds forever.
            if (health.stableFor(STABLE_STREAM_MILLIS)) attempt = 0
            attempt += 1
            delay(backoffMillis(attempt))
        }
    }

    /**
     * Detects a half-open event stream. The SSE client has no read timeout (the
     * stream is idle by design), so after a network switch or a NAT timeout the
     * socket could stay "connected" forever while nothing arrived. opencode sends
     * `server.heartbeat` every ~10 s; once one has been seen, silence well past
     * that interval fails the stream so the normal reconnect path takes over.
     * Servers without heartbeats are never timed out once open, but a stream
     * that does not even open in time is.
     */
    private class StreamHealth {
        private val startedAt = System.currentTimeMillis()
        private val lastEventAt = AtomicLong(startedAt)
        private val openedAt = AtomicLong(0)
        private val heartbeats = AtomicBoolean(false)

        fun onEvent(type: String?) {
            lastEventAt.set(System.currentTimeMillis())
            if (type == HEARTBEAT_EVENT) heartbeats.set(true)
        }

        /** True exactly once, for the first event of this stream. */
        fun markOpened(): Boolean = openedAt.compareAndSet(0, System.currentTimeMillis())

        /** Whether the stream opened and stayed up for at least [millis]. */
        fun stableFor(millis: Long): Boolean {
            val opened = openedAt.get()
            return opened != 0L && System.currentTimeMillis() - opened >= millis
        }

        suspend fun watch() {
            while (true) {
                delay(STREAM_CHECK_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                if (openedAt.get() == 0L && now - startedAt > STREAM_OPEN_TIMEOUT_MILLIS) {
                    throw StreamSilentException((now - startedAt) / 1000)
                }
                val silence = now - lastEventAt.get()
                if (heartbeats.get() && silence > STREAM_STALE_MILLIS) {
                    throw StreamSilentException(silence / 1000)
                }
            }
        }
    }

    /** The event stream stopped delivering anything, heartbeats included. */
    private class StreamSilentException(val seconds: Long) : IOException("Event stream went silent for ${seconds}s")

    private fun backoffMillis(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 5)
        return (RECONNECT_DELAY_MILLIS * multiplier).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
    }

    fun reconnect() {
        retryTick.update { it + 1 }
    }

    /**
     * Loads everything the event stream keeps up to date, right after it opened.
     * The loads are best-effort and must not poison the connection state, which
     * is owned by the event stream.
     */
    private suspend fun sync(client: OpenCodeClient) = coroutineScope {
        Log.i(TAG, "event stream open; syncing")
        launch {
            catchingNonCancellation { client.health() }
                .onSuccess { health ->
                    serverVersion = health.version
                    if (_connection.value is ConnectionState.Connected) {
                        _connection.value = ConnectionState.Connected(health.version)
                    }
                }
                .onFailure { Log.w(TAG, "health check failed", it) }
        }
        launch { loadSessions(client) }
        launch { loadProviders(client) }
        launch { loadAgents(client) }
        launch { loadPermissions(client) }
        launch { loadQuestions(client) }
        launch { loadMcp(client) }
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

    /** True while [client] is still the one the app talks to; late answers of another are dropped. */
    private fun isCurrent(client: OpenCodeClient) = clientFlow.value === client

    private suspend fun loadSessions(client: OpenCodeClient) {
        catchingNonCancellation { client.listSessions() }
            .onSuccess { list ->
                if (!isCurrent(client)) return@onSuccess
                sessionTree.reset(list)
                // Subagent sessions belong to their parent chat, not to the list.
                // Blank/duplicate ids would also break the keyed LazyColumn.
                _sessions.value = list
                    .filter { it.parentID == null && it.id.isNotBlank() }
                    .distinctBy { it.id }
                    .sortedByDescending { session -> session.time?.updated ?: 0 }
                _sessionsLoaded.value = true
            }
            .onFailure {
                Log.w(TAG, "loadSessions failed", it)
                if (!isCurrent(client)) return@onFailure
                _sessionError.value = it.toUiText(R.string.error_load_sessions)
                // Leave the loading state: the list is empty because it could not be read.
                _sessionsLoaded.value = true
            }
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
        if (!isCurrent(client)) return
        selection.applyProviders(list)
    }

    private suspend fun loadAgents(client: OpenCodeClient) {
        catchingNonCancellation { client.listAgents() }
            .onSuccess { if (isCurrent(client)) selection.applyAgents(it) }
            .onFailure { Log.w(TAG, "loadAgents failed", it) }
    }

    private suspend fun loadPermissions(client: OpenCodeClient) =
        loadSnapshot(client, permissionsEpoch, "loadPermissions", { it.listPermissions() }) { _permissions.value = it }

    private suspend fun loadQuestions(client: OpenCodeClient) =
        loadSnapshot(client, questionsEpoch, "loadQuestions", { it.listQuestions() }) { _questions.value = it }

    /**
     * Replaces a collection the event stream also maintains. A snapshot taken
     * before an event for that collection was applied would undo it (a card
     * answered from another client came back for good), so such a snapshot is
     * discarded and read again.
     */
    private suspend fun <T> loadSnapshot(
        client: OpenCodeClient,
        epoch: AtomicLong,
        name: String,
        fetch: suspend (OpenCodeClient) -> T,
        apply: (T) -> Unit,
    ) {
        repeat(SNAPSHOT_ATTEMPTS) {
            val before = epoch.get()
            val result = catchingNonCancellation { fetch(client) }
                .onFailure { Log.w(TAG, "$name failed", it) }
                .getOrNull() ?: return
            if (!isCurrent(client)) return
            if (epoch.get() == before) {
                apply(result)
                return
            }
        }
        Log.w(TAG, "$name: kept changing while loading; relying on events")
    }

    private suspend fun loadMcp(client: OpenCodeClient) {
        catchingNonCancellation { client.mcpStatus() }
            .onSuccess { statuses ->
                // A load started for a server the app has since left must not
                // show that server's MCP list.
                if (!isCurrent(client)) return@onSuccess
                _mcpServers.value = statuses.toMcpServers()
            }
            .onFailure {
                Log.w(TAG, "loadMcp failed", it)
                if (!isCurrent(client)) return@onFailure
                _mcpError.value = it.toUiText(R.string.error_mcp_status)
                // Leave the loading state even when nothing could be read.
                _mcpServers.update { current -> current ?: emptyList() }
            }
    }

    // --- MCP servers ---

    /** Re-reads MCP statuses, e.g. when the panel is opened. */
    fun refreshMcp() {
        val client = clientFlow.value ?: return
        scope.launch { loadMcp(client) }
    }

    /**
     * Connects or disconnects an MCP server, like the web client's switch. The
     * change lasts until opencode restarts. The list is reloaded afterwards
     * either way: a connect can "succeed" into the `failed` state.
     */
    fun setMcpEnabled(name: String, enabled: Boolean) {
        if (!markIn(_mcpToggling, name)) return
        _mcpError.value = null
        scope.launch {
            try {
                val client = clientFlow.value ?: return@launch
                catchingNonCancellation { if (enabled) client.mcpConnect(name) else client.mcpDisconnect(name) }
                    .onFailure {
                        Log.w(TAG, "setMcpEnabled($name, $enabled) failed", it)
                        _mcpError.value = it.toUiText(R.string.error_mcp_toggle, name)
                    }
                loadMcp(client)
            } finally {
                _mcpToggling.update { it - name }
            }
        }
    }

    fun clearMcpError() {
        _mcpError.value = null
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
                        _sessionError.value = it.toUiText(R.string.error_create_session)
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
                        _sessionError.value = it.toUiText(R.string.error_delete_session)
                    }
            } finally {
                _deletingSessions.update { it - sessionId }
            }
        }
    }

    // --- Chat ---

    /**
     * Opens the chat. [owner] (the screen showing it) is recorded so that only
     * the latest owner can close it again; see [closeChat].
     */
    fun openChat(sessionId: String, force: Boolean = false, owner: Any? = null) =
        chatController.open(sessionId, force, owner)

    fun closeChat(sessionId: String? = null, owner: Any? = null) = chatController.close(sessionId, owner)

    fun clearChatError() = chatController.clearError()

    /** Completes with whether the server accepted the prompt. */
    fun sendPrompt(text: String): Deferred<Boolean> = chatController.sendPrompt(text)

    fun abort() = chatController.abort()

    fun selectModel(model: PromptModel?) = selection.selectModel(model)

    fun selectAgent(agent: String?) = selection.selectAgent(agent)

    fun selectVariant(variant: String?) = selection.selectVariant(variant)

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
            failure = R.string.error_reply_permission,
            onAnswered = { _permissions.update { current -> current.filterNot { it.id == requestId } } },
        ) { client -> client.replyPermission(requestId, reply) }

    fun replyQuestion(requestId: String, answers: List<List<String>>) =
        answer(
            requestId = requestId,
            failure = R.string.error_reply_question,
            onAnswered = { removeQuestion(requestId) },
        ) { client -> client.replyQuestion(requestId, answers) }

    fun rejectQuestion(requestId: String) =
        answer(
            requestId = requestId,
            failure = R.string.error_reject_question,
            onAnswered = { removeQuestion(requestId) },
        ) { client -> client.rejectQuestion(requestId) }

    private fun removeQuestion(requestId: String) {
        _questions.update { current -> current.filterNot { it.id == requestId } }
    }

    /** Sends one answer per request: taps while it is in flight are ignored. */
    private fun answer(
        requestId: String,
        @StringRes failure: Int,
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
                        chatController.showError(it.toUiText(failure))
                    }
            } finally {
                _replying.update { it - requestId }
            }
        }
    }

    // --- Files and VCS ---

    // Each action clears the previous error first: a failure used to stay on
    // screen above content that had loaded fine since.

    /** Null when the listing could not be read (the error is in [actionError]). */
    suspend fun listFiles(path: String): List<FileNode>? =
        fileAction(R.string.error_list_files) { it.listFiles(path) }

    suspend fun readFile(path: String): FileContent? =
        fileAction(R.string.error_open_file) { it.readFile(path) }

    suspend fun vcsInfo(): VcsInfo? = fileAction(R.string.error_vcs_info) { it.vcsInfo() }

    suspend fun vcsStatus(): List<VcsFileStatus> =
        fileAction(R.string.error_vcs_status) { it.vcsStatus() } ?: emptyList()

    suspend fun vcsDiff(mode: String): List<VcsFileDiff> =
        fileAction(R.string.error_diff) { it.vcsDiff(mode) } ?: emptyList()

    suspend fun sessionDiff(sessionId: String): List<VcsFileDiff> =
        fileAction(R.string.error_session_diff) { it.sessionDiff(sessionId) } ?: emptyList()

    fun clearActionError() {
        _actionError.value = null
    }

    private suspend fun <T> fileAction(@StringRes failure: Int, call: suspend (OpenCodeClient) -> T): T? {
        _actionError.value = null
        val client = clientFlow.value ?: return null
        return catchingNonCancellation { call(client) }
            .onFailure {
                Log.w(TAG, "file action failed", it)
                _actionError.value = it.toUiText(failure)
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

            "mcp.tools.changed" -> scope.launch { loadMcp(client) }

            "permission.asked" -> {
                permissionsEpoch.incrementAndGet()
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
                permissionsEpoch.incrementAndGet()
                val id = props.stringOrNull("requestID")
                if (id != null) {
                    _permissions.update { current -> current.filterNot { it.id == id } }
                } else {
                    scope.launch { loadPermissions(client) }
                }
            }

            "question.asked" -> {
                questionsEpoch.incrementAndGet()
                val request = props.decodeQuestionRequest()
                if (request != null) {
                    _questions.update { current -> current.filterNot { it.id == request.id } + request }
                } else {
                    scope.launch { loadQuestions(client) }
                }
            }

            "question.replied", "question.rejected" -> {
                questionsEpoch.incrementAndGet()
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
                // Activity moves a session to the top, as on a fresh load.
                else -> current.map { if (it.id == session.id) session else it }
                    .sortedByDescending { it.time?.updated ?: 0 }
            }
        }
        if (type == "session.deleted") sessionTree.remove(session.id) else sessionTree.record(session)
        if (type == "session.updated") chatController.onSessionUpdated(session)
    }

    private companion object {
        const val TAG = "AppRepository"
        const val HEARTBEAT_EVENT = "server.heartbeat"
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val STABLE_STREAM_MILLIS = 30_000L
        const val STREAM_OPEN_TIMEOUT_MILLIS = 30_000L
        const val BACKGROUND_GRACE_MILLIS = 30_000L
        const val SNAPSHOT_ATTEMPTS = 3
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
