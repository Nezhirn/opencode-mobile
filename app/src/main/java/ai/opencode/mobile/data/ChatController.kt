package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.PromptRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.TextPartInput
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the open chat: its state, the streaming delta buffer, optimistic echoes
 * and the busy watchdog. Every write that follows a suspension point is scoped
 * to the session it was started for ([updateIfCurrent]), so a response that
 * lands after the user switched chats cannot leak into the new one.
 */
internal class ChatController(
    private val scope: CoroutineScope,
    private val clientProvider: () -> OpenCodeClient?,
    private val sessionTree: SessionTree,
    private val selection: SelectionStore,
    private val sessionById: (String) -> Session?,
    /** True while the run in this session waits for a permission or question answer. */
    private val awaitingUserInput: (String) -> Boolean,
    /** Receives `session.error` messages that cannot be attributed to any chat. */
    private val onUnattributedError: (String) -> Unit,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val currentSessionId: String? get() = _state.value.sessionId

    private var loadJob: Job? = null

    /**
     * Incremented by every event or action that decides the busy flag. A status
     * snapshot requested before such a change is stale and must not override it
     * (e.g. `GET /session/status` answering "busy" after `session.idle` arrived).
     */
    private val statusEpoch = AtomicLong()

    @Volatile
    private var lastActivityAt = 0L
    private val watchdogLock = Any()
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

    /** Makes local echo ids unique even for two sends within the same millisecond. */
    private val localMessageCounter = AtomicLong()

    fun open(sessionId: String, force: Boolean = false) {
        // Re-entering the same chat (returning from the files screen, a
        // recomposition) must not wipe the state: doing so dropped buffered
        // deltas, reset scroll position and cleared `busy`, which made the Stop
        // button vanish mid-run. Refresh passes force to reload deliberately.
        val current = _state.value
        if (!force && current.sessionId == sessionId && !current.loading) return
        val client = clientProvider() ?: return
        // Cancel any in-flight load so a slower previous session cannot land in
        // the newly opened chat.
        loadJob?.cancel()
        clearDeltas()
        val session = sessionById(sessionId)
        _state.value = ChatState(
            sessionId = sessionId,
            title = session?.title.orEmpty(),
            loading = true,
        )
        session?.model?.let(selection::adoptSessionModel)
        loadJob = scope.launch { load(client, sessionId) }
    }

    /**
     * Clears the open chat. When [sessionId] is given the state is only cleared
     * if it still belongs to that session, so a ViewModel being destroyed cannot
     * wipe a chat that was opened afterwards.
     */
    fun close(sessionId: String? = null) {
        if (sessionId != null && currentSessionId != sessionId) return
        loadJob?.cancel()
        clearDeltas()
        _state.value = ChatState()
    }

    /** Clears the chat if it shows [sessionId], atomically (the session was deleted). */
    fun closeIfShowing(sessionId: String) {
        var closed = false
        _state.update { state ->
            closed = state.sessionId == sessionId
            if (closed) ChatState() else state
        }
        if (closed) {
            loadJob?.cancel()
            clearDeltas()
        }
    }

    /**
     * Reloads the open chat in place, without the loading state, after the event
     * stream had a gap: whatever happened meanwhile (final text, the run going
     * idle) was never delivered and would otherwise stay missing for good.
     */
    fun resync() {
        val sessionId = currentSessionId ?: return
        val client = clientProvider() ?: return
        if (loadJob?.isActive == true) return
        loadJob = scope.launch { load(client, sessionId) }
    }

    private suspend fun load(client: OpenCodeClient, sessionId: String) {
        val epoch = statusEpoch.get()
        catchingNonCancellation { client.getMessages(sessionId) }
            .onSuccess { messages ->
                // Duplicate or blank ids would crash the LazyColumn that keys on
                // them; the incremental paths dedupe, this bulk load did not.
                val ui = messages
                    .filter { it.info.id.isNotBlank() }
                    .distinctBy { it.info.id }
                    .map { it.toUi() }
                // Buffered deltas belong to the state being replaced; flushing them
                // first keeps them from being appended to the fresh snapshot.
                flushDeltas()
                _state.update { state -> state.withMessagesIfCurrent(sessionId, ui) }
            }
            .onFailure { error ->
                Log.w(TAG, "load($sessionId) messages failed", error)
                _state.update { state -> state.withLoadErrorIfCurrent(sessionId, error.message) }
            }
        catchingNonCancellation { client.todos(sessionId) }
            .onSuccess { todos -> _state.update { state -> state.withTodosIfCurrent(sessionId, todos) } }
            .onFailure { Log.w(TAG, "load($sessionId) todos failed", it) }
        // The busy flag is not part of the message list: without this a chat
        // reopened mid-run showed Send instead of Stop until the next status
        // change, which may be minutes away.
        catchingNonCancellation { client.sessionStatus() }
            .onSuccess { statuses ->
                if (statusEpoch.get() == epoch) applyBusy(sessionId, isBusyStatus(statuses[sessionId]?.type))
            }
            .onFailure { Log.w(TAG, "load($sessionId) status failed", it) }
    }

    private fun applyBusy(sessionId: String, busy: Boolean) {
        _state.updateIfCurrent(sessionId) { state -> state.copy(busy = busy) }
        if (busy) armBusyWatchdog()
    }

    /**
     * Sends a prompt and completes with whether the server accepted it, so the
     * caller can give the text back to the user when it was not sent.
     */
    fun sendPrompt(text: String): Deferred<Boolean> {
        val trimmed = text.trim()
        val sessionId = currentSessionId
        val client = clientProvider()
        if (trimmed.isEmpty() || sessionId == null || client == null) return CompletableDeferred(false)
        return scope.async {
            val ready = when (val check = selection.checkSendable()) {
                is SendSelection.Rejected -> {
                    _state.updateIfCurrent(sessionId) { state -> state.copy(error = check.message) }
                    return@async false
                }
                is SendSelection.Ready -> check
            }
            val localId = "$LOCAL_ID_PREFIX${System.currentTimeMillis()}-${localMessageCounter.incrementAndGet()}"
            statusEpoch.incrementAndGet()
            _state.updateIfCurrent(sessionId) { state ->
                state.copy(
                    busy = true,
                    error = null,
                    messages = state.messages + localUserMessage(localId, trimmed),
                )
            }
            armBusyWatchdog()
            val request = PromptRequest(
                model = ready.model,
                agent = ready.agent,
                parts = listOf(TextPartInput(type = TEXT_PART_TYPE, text = trimmed)),
            )
            catchingNonCancellation { client.promptAsync(sessionId, request) }
                .onFailure { error ->
                    Log.w(TAG, "sendPrompt($sessionId) failed", error)
                    _state.updateIfCurrent(sessionId) { state ->
                        state.withoutMessage(localId).copy(
                            busy = false,
                            error = error.message ?: "Failed to send prompt",
                        )
                    }
                }
                .isSuccess
        }
    }

    fun abort() {
        val sessionId = currentSessionId ?: return
        val client = clientProvider() ?: return
        scope.launch {
            catchingNonCancellation { client.abort(sessionId) }
                .onSuccess {
                    statusEpoch.incrementAndGet()
                    _state.updateIfCurrent(sessionId) { state -> state.copy(busy = false) }
                }
                .onFailure { error ->
                    // The run is still going: keep Stop available instead of
                    // pretending it ended.
                    Log.w(TAG, "abort($sessionId) failed", error)
                    _state.updateIfCurrent(sessionId) { state ->
                        state.copy(error = error.message ?: "Failed to abort")
                    }
                }
        }
    }

    fun showError(message: String) {
        _state.update { state -> state.copy(error = message) }
    }

    fun onSessionUpdated(session: Session) {
        _state.updateIfCurrent(session.id) { state -> state.copy(title = session.title) }
    }

    /** Test hook: installs chat state without any network call. */
    fun setForTest(state: ChatState) {
        _state.value = state
    }

    // --- Events ---

    /**
     * Applies a chat event. Only events that concern the open chat count as
     * activity for the busy watchdog; heartbeats and other sessions' traffic must
     * not keep a stalled run looking alive.
     */
    fun handleEvent(type: String, props: JsonObject) {
        when (type) {
            "message.updated" -> {
                val info = props.decodeMessage("info") ?: return
                if (!appliesToCurrentChat(props)) return
                // The envelope of message.updated carries no top-level sessionID,
                // so appliesToCurrentChat() alone lets every session through. The
                // message itself names its session: without this check a subagent
                // run (or another client) injected empty ghost bubbles here.
                if (info.sessionID.isNotBlank() && info.sessionID != currentSessionId) return
                noteActivity()
                _state.update { state -> state.applyMessageUpdate(info) }
            }

            "message.removed" -> {
                val messageId = props.stringOrNull("messageID") ?: return
                if (!appliesToCurrentChat(props)) return
                noteActivity()
                _state.update { state -> state.withoutMessage(messageId) }
            }

            "message.part.updated" -> {
                val part = props.decodePart("part") ?: return
                if (!appliesToCurrentChat(props)) return
                noteActivity()
                // A full part supersedes any buffered deltas: apply them first so
                // nothing is silently dropped.
                flushDeltas()
                // A part that belongs to this exact session but arrives before its
                // message.updated would otherwise be dropped for good. Parts from
                // subagent sessions keep being ignored: they belong to a child
                // session, not to the messages on screen.
                val ownSession = part.sessionID.isBlank() || part.sessionID == currentSessionId
                _state.update { state -> state.upsertPart(part, createMissingMessage = ownSession) }
            }

            "message.part.removed" -> {
                val partId = props.stringOrNull("partID") ?: return
                if (!appliesToCurrentChat(props)) return
                noteActivity()
                _state.update { state -> state.withoutPart(partId) }
            }

            "message.part.delta" -> {
                if (!appliesToCurrentChat(props)) return
                val partId = props.stringOrNull("partID") ?: return
                val field = props.stringOrNull("field") ?: "text"
                val delta = props.stringOrNull("delta") ?: return
                noteActivity()
                enqueueDelta(partId, field, delta)
            }

            "session.idle" -> {
                val sessionId = props.stringOrNull("sessionID") ?: return
                if (sessionId != currentSessionId) return
                statusEpoch.incrementAndGet()
                flushDeltas()
                _state.updateIfCurrent(sessionId) { state -> state.copy(busy = false) }
            }

            "session.status" -> {
                // Strict equality, not appliesToCurrentChat(): the root session
                // stays busy while its subagents run, so child statuses must not
                // flip the flag.
                val sessionId = props.stringOrNull("sessionID") ?: return
                if (sessionId != currentSessionId) return
                statusEpoch.incrementAndGet()
                noteActivity()
                val busy = isBusyStatus(props.statusType())
                if (!busy) flushDeltas()
                applyBusy(sessionId, busy)
            }

            "session.error" -> handleSessionError(props)

            "todo.updated" -> {
                if (!appliesToCurrentChat(props)) return
                val todos = props.decodeTodos() ?: return
                _state.update { state -> state.copy(todos = todos) }
            }
        }
    }

    private fun handleSessionError(props: JsonObject) {
        flushDeltas()
        val error = props.decodeSessionError()
        val applies = appliesToCurrentChat(props)
        if (error?.name == MESSAGE_ABORTED_ERROR) {
            // Abort is a normal cancellation, not a failure to show.
            if (applies) {
                statusEpoch.incrementAndGet()
                _state.update { state -> state.copy(busy = false) }
            }
            return
        }
        val message = formatSessionError(error, props["error"])
        Log.w(TAG, "session.error: $message")
        if (applies) {
            statusEpoch.incrementAndGet()
            _state.update { state -> state.copy(busy = false, error = message) }
        } else if (props.stringOrNull("sessionID") == null) {
            // Unattributable, but not something to swallow.
            onUnattributedError(message)
        }
    }

    /**
     * The `sessionID` of several events is optional. Without it an event cannot
     * be attributed precisely, so it applies to whichever chat is currently open.
     */
    private fun appliesToCurrentChat(props: JsonObject): Boolean {
        val target = currentSessionId ?: return false
        val sessionId = props.stringOrNull("sessionID") ?: return true
        return sessionTree.matches(sessionId, target)
    }

    // --- Busy watchdog ---

    private fun noteActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Guards against a run that stalls without ever emitting session.idle or
     * session.status (a known prompt_async issue on some server versions). After
     * a long silence the server is asked before anything changes: a single tool
     * call can legitimately run for minutes without emitting events, and a run
     * waiting for a permission or question answer is not stalled at all.
     */
    private fun armBusyWatchdog() {
        noteActivity()
        synchronized(watchdogLock) {
            if (busyWatchdog?.isActive == true) return
            busyWatchdog = scope.launch { watchBusy() }
        }
    }

    private suspend fun watchBusy() {
        while (true) {
            delay(WATCHDOG_INTERVAL_MILLIS)
            val state = _state.value
            val sessionId = state.sessionId ?: return
            if (!state.busy) return
            if (System.currentTimeMillis() - lastActivityAt < SILENCE_BEFORE_CHECK_MILLIS) continue
            if (awaitingUserInput(sessionId)) continue
            val epoch = statusEpoch.get()
            val statuses = clientProvider()?.let { client -> catchingNonCancellation { client.sessionStatus() }.getOrNull() }
            if (statusEpoch.get() != epoch) continue
            when {
                statuses == null -> {
                    Log.w(TAG, "busy watchdog: no events and no status for $sessionId")
                    _state.updateIfCurrent(sessionId) { current ->
                        current.copy(busy = false, error = "No response from the server; the run may have stalled.")
                    }
                    return
                }
                isBusyStatus(statuses[sessionId]?.type) -> noteActivity()
                else -> {
                    // The idle event was lost; the run is over.
                    _state.updateIfCurrent(sessionId) { current -> current.copy(busy = false) }
                    return
                }
            }
        }
    }

    // --- Streaming deltas ---

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
        _state.update { state -> state.applyDeltas(pending) }
    }

    private companion object {
        const val TAG = "ChatController"
        const val DELTA_FLUSH_INTERVAL_MILLIS = 50L
        const val WATCHDOG_INTERVAL_MILLIS = 30_000L
        const val SILENCE_BEFORE_CHECK_MILLIS = 120_000L
        const val MESSAGE_ABORTED_ERROR = "MessageAbortedError"
    }
}

/**
 * [runCatching] that does not swallow coroutine cancellation: a cancelled load
 * must stop, not be reported as a failure (Refresh cancels a load and opens the
 * same session again, so the id guard would not stop a "Job was cancelled"
 * banner from landing in the fresh state).
 */
internal inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }
