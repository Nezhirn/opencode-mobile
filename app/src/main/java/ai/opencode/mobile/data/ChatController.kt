package ai.opencode.mobile.data

import ai.opencode.mobile.R
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.PromptPart
import ai.opencode.mobile.data.remote.PromptRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionRevert
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val onUnattributedError: (UiText) -> Unit,
    /** Receives the session as the server answered a rollback with, for the session list. */
    private val onSessionChanged: (Session) -> Unit = {},
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val currentSessionId: String? get() = _state.value.sessionId

    /** The load in flight, if any; guarded by [loadLock]. */
    private var loadJob: Job? = null
    private val loadLock = Any()

    /** A resync asked for while a load was in flight; it runs once that load is done. */
    private var resyncPending = false

    /**
     * What events changed while the message snapshot loads. The snapshot was
     * taken before them, so for these messages and parts the live state is
     * newer and must win over it; see [withMessagesIfCurrent].
     */
    @Volatile
    private var loadChanges: LiveChanges? = null

    /**
     * The screen that opened the chat last. Leaving a chat and reopening it
     * quickly creates a new screen before the old one is destroyed; the old one
     * must not then close the chat the new one shows.
     */
    @Volatile
    private var owner: Any? = null

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

    fun open(sessionId: String, force: Boolean = false, owner: Any? = null) {
        if (owner != null) this.owner = owner
        // Re-entering the same chat (returning from the files screen, a
        // recomposition) must not wipe the state: doing so dropped buffered
        // deltas, reset scroll position and cleared `busy`, which made the Stop
        // button vanish mid-run. Refresh passes force to reload deliberately.
        val current = _state.value
        if (!force && current.sessionId == sessionId && !current.loading) return
        val client = clientProvider() ?: return
        clearDeltas()
        val session = sessionById(sessionId)
        synchronized(loadLock) {
            // Cancel any in-flight load so a slower previous session cannot land
            // in the newly opened chat.
            cancelLoad()
            _state.value = ChatState(
                sessionId = sessionId,
                title = session?.title.orEmpty(),
                loading = true,
                revert = session?.revert?.takeIf { it.messageID.isNotBlank() },
            )
            launchLoad(client, sessionId)
        }
        session?.model?.let(selection::adoptSessionModel)
    }

    /**
     * Clears the open chat. When [sessionId] is given the state is only cleared
     * if it still belongs to that session, so a ViewModel being destroyed cannot
     * wipe a chat that was opened afterwards.
     */
    fun close(sessionId: String? = null, owner: Any? = null) {
        if (sessionId != null && currentSessionId != sessionId) return
        if (owner != null && owner !== this.owner) return
        this.owner = null
        synchronized(loadLock) { cancelLoad() }
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
            synchronized(loadLock) { cancelLoad() }
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
        synchronized(loadLock) {
            // A load already in flight may have read the history before the
            // stream reopened, missing what happened in the gap: run another
            // one after it instead of dropping this request.
            if (loadJob != null) {
                resyncPending = true
                return
            }
            launchLoad(client, sessionId)
        }
    }

    /** Called under [loadLock]. */
    private fun cancelLoad() {
        loadJob?.cancel()
        loadJob = null
        resyncPending = false
        loadChanges = null
    }

    /** Starts loading [sessionId], then again for every resync asked for meanwhile. Called under [loadLock]. */
    private fun launchLoad(client: OpenCodeClient, sessionId: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                var current: OpenCodeClient = client
                while (true) {
                    load(current, sessionId)
                    val again = synchronized(loadLock) {
                        val again = resyncPending && currentSessionId == sessionId
                        resyncPending = false
                        if (!again && loadJob === self) loadJob = null
                        again
                    }
                    if (!again) break
                    current = clientProvider() ?: break
                }
            } finally {
                synchronized(loadLock) { if (loadJob === self) loadJob = null }
            }
        }
        loadJob = job
        job.start()
    }

    private suspend fun load(client: OpenCodeClient, sessionId: String) {
        val epoch = statusEpoch.get()
        // What was on screen before the request: anything else found in the
        // state afterwards arrived through events while the snapshot loaded.
        val knownBefore = _state.value.takeIf { it.sessionId == sessionId }
            ?.messages?.mapTo(HashSet()) { it.info.id }
            .orEmpty()
        val changes = LiveChanges()
        loadChanges = changes
        catchingNonCancellation { client.getMessages(sessionId) }
            .onSuccess { messages ->
                // Duplicate or blank ids would crash the LazyColumn that keys on
                // them; the incremental paths dedupe, this bulk load did not.
                val ui = messages
                    .filter { it.info.id.isNotBlank() }
                    .distinctBy { it.info.id }
                    .map { it.toUi() }
                // Buffered deltas belong to the state being merged; flushing them
                // first keeps them from being appended to the fresh snapshot.
                synchronized(deltaLock) {
                    flushDeltas()
                    _state.update { state -> state.withMessagesIfCurrent(sessionId, ui, knownBefore, changes) }
                }
            }
            .onFailure { error ->
                Log.w(TAG, "load($sessionId) messages failed", error)
                _state.update { state -> state.withLoadErrorIfCurrent(sessionId, error.toUiText(R.string.error_load_messages)) }
            }
        if (loadChanges === changes) loadChanges = null
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
    fun sendPrompt(text: String, attachments: List<Attachment> = emptyList()): Deferred<Boolean> {
        val trimmed = text.trim()
        val sessionId = currentSessionId
        val client = clientProvider()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || sessionId == null || client == null) {
            return CompletableDeferred(false)
        }
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
            // A prompt sent while rolled back replaces what was rolled back: the
            // server deletes it before running the prompt.
            var previousRevert: SessionRevert? = null
            var before: List<ChatMessageUi> = emptyList()
            var hidden: Set<String> = emptySet()
            _state.updateIfCurrent(sessionId) { state ->
                previousRevert = state.revert
                before = state.messages
                hidden = state.revertedMessageIds()
                val committed = state.committedRevert()
                committed.copy(
                    busy = true,
                    error = null,
                    messages = committed.messages + localUserMessage(localId, trimmed, attachments),
                )
            }
            val wasReverted = previousRevert != null
            // A snapshot loading now still has them; they are gone all the same.
            loadChanges?.removedMessages?.addAll(hidden)
            armBusyWatchdog()
            val request = PromptRequest(
                model = ready.model,
                agent = ready.agent,
                variant = ready.variant,
                parts = buildList {
                    if (trimmed.isNotEmpty()) add(PromptPart(type = TEXT_PART_TYPE, text = trimmed))
                    attachments.forEach { add(it.toPromptPart()) }
                },
            )
            catchingNonCancellation { client.promptAsync(sessionId, request) }
                .onFailure { error ->
                    Log.w(TAG, "sendPrompt($sessionId) failed", error)
                    loadChanges?.removedMessages?.removeAll(hidden)
                    _state.updateIfCurrent(sessionId) { state ->
                        val failed = state.withoutMessage(localId).copy(
                            busy = false,
                            error = error.toUiText(R.string.error_send_prompt),
                        )
                        // Most likely the server never got as far as dropping the
                        // rolled back messages: show them as rolled back again
                        // rather than as live ones the next prompt would delete.
                        if (wasReverted && failed.revert == null) failed.withRestoredRevert(previousRevert, before) else failed
                    }
                    // Whether the server did drop them before failing is unknown:
                    // read back what it has.
                    if (wasReverted && currentSessionId == sessionId) {
                        resync()
                        refreshRevert(client, sessionId)
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
                        state.copy(error = error.toUiText(R.string.error_abort))
                    }
                }
        }
    }

    fun showError(message: UiText) {
        _state.update { state -> state.copy(error = message) }
    }

    fun clearError() {
        _state.update { state -> state.copy(error = null) }
    }

    /**
     * The session as listed or announced. While a rollback is on its way the
     * revert in such a copy predates it and is ignored: the rollback's own answer
     * settles it.
     */
    fun onSessionUpdated(session: Session) {
        _state.updateIfCurrent(session.id) { state ->
            if (state.reverting) {
                state.copy(title = session.title)
            } else {
                state.copy(title = session.title, revert = session.revert?.takeIf { it.messageID.isNotBlank() })
            }
        }
    }

    /** Reads the session's rollback state back from the server. */
    private fun refreshRevert(client: OpenCodeClient, sessionId: String) {
        scope.launch {
            catchingNonCancellation { client.getSession(sessionId) }
                .onSuccess { session ->
                    onSessionUpdated(session)
                    onSessionChanged(session)
                }
                .onFailure { Log.w(TAG, "getSession($sessionId) failed", it) }
        }
    }

    /**
     * "Edit" on a sent message, as in the web client: rolls the session back to
     * just before [messageId] (stopping a run first, which the server requires)
     * and completes with the message's text and files to put back into the
     * prompt — or null when the rollback failed. The messages stay hidden, not
     * deleted, until the next prompt; [restore] brings them back.
     */
    fun revertTo(messageId: String): Deferred<EditDraft?> {
        val sessionId = currentSessionId
        val client = clientProvider()
        val state = _state.value
        val message = state.visibleMessages.firstOrNull { it.info.id == messageId }
        if (sessionId == null || client == null || message == null || message.isLocalEcho ||
            message.info.role != USER_ROLE || state.reverting
        ) {
            return CompletableDeferred(null)
        }
        val draft = EditDraft(message.userText(), message.attachments())
        return changeRevert(
            sessionId = sessionId,
            optimistic = SessionRevert(messageID = messageId),
            failure = R.string.error_revert,
        ) { client.revert(sessionId, messageId) }.let { result ->
            scope.async { if (result.await()) draft else null }
        }
    }

    /**
     * Brings the rolled back [messageId] back with its answers. Later hidden
     * messages stay hidden, the next of them becoming the one being edited (its
     * text is returned for the prompt); restoring the last one undoes the
     * rollback and returns an empty draft. Null when the server refused.
     */
    fun restore(messageId: String): Deferred<EditDraft?> {
        val sessionId = currentSessionId
        val client = clientProvider()
        val state = _state.value
        if (sessionId == null || client == null || state.revert == null || state.reverting ||
            state.revertedUserMessages.none { it.info.id == messageId }
        ) {
            return CompletableDeferred(null)
        }
        val target = state.restoreTarget(messageId)
        val draft = target?.let { EditDraft(it.userText(), it.attachments()) } ?: EditDraft("", emptyList())
        return changeRevert(
            sessionId = sessionId,
            optimistic = target?.let { SessionRevert(messageID = it.info.id) },
            failure = R.string.error_unrevert,
        ) {
            if (target != null) client.revert(sessionId, target.info.id) else client.unrevert(sessionId)
        }.let { result ->
            scope.async { if (result.await()) draft else null }
        }
    }

    /**
     * Shows [optimistic] at once, runs [request] and settles on the session the
     * server answers with; on failure the previous rollback state comes back.
     */
    private fun changeRevert(
        sessionId: String,
        optimistic: SessionRevert?,
        @StringRes failure: Int,
        request: suspend () -> Session,
    ): Deferred<Boolean> {
        val client = clientProvider() ?: return CompletableDeferred(false)
        val previous = _state.value.revert
        val wasBusy = _state.value.busy
        _state.updateIfCurrent(sessionId) { state -> state.copy(revert = optimistic, reverting = true, error = null) }
        return scope.async {
            if (wasBusy) {
                // Stop stays available unless the run really was stopped: a
                // failed abort leaves it running, and the rollback is then
                // refused as well.
                catchingNonCancellation { client.abort(sessionId) }
                    .onSuccess {
                        statusEpoch.incrementAndGet()
                        _state.updateIfCurrent(sessionId) { state -> state.copy(busy = false) }
                    }
                    .onFailure { Log.w(TAG, "abort before revert failed", it) }
            }
            var result = catchingNonCancellation { request() }
            if (result.isFailure && wasBusy) {
                // The run may need a moment to wind down after the abort.
                delay(REVERT_RETRY_DELAY_MILLIS)
                result = catchingNonCancellation { request() }
            }
            result
                .onSuccess { session ->
                    _state.updateIfCurrent(sessionId) { state ->
                        state.copy(revert = session.revert?.takeIf { it.messageID.isNotBlank() }, reverting = false)
                    }
                    onSessionChanged(session)
                }
                .onFailure { error ->
                    Log.w(TAG, "changing the rollback of $sessionId failed", error)
                    _state.updateIfCurrent(sessionId) { state ->
                        state.copy(revert = previous, reverting = false, error = error.toUiText(failure))
                    }
                }
                .isSuccess
        }
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
                val target = targetOf(props) ?: return
                // The envelope of message.updated carries no top-level sessionID,
                // so targetOf() alone lets every session through. The message
                // itself names its session: without this check a subagent run (or
                // another client) injected empty ghost bubbles here.
                if (info.sessionID.isNotBlank() && info.sessionID != target) return
                noteActivity()
                loadChanges?.messages?.add(info.id)
                _state.updateIfCurrent(target) { state -> state.applyMessageUpdate(info) }
            }

            "message.removed" -> {
                val messageId = props.stringOrNull("messageID") ?: return
                val target = targetOf(props) ?: return
                noteActivity()
                loadChanges?.removedMessages?.add(messageId)
                _state.updateIfCurrent(target) { state -> state.withoutMessage(messageId) }
            }

            "message.part.updated" -> {
                val part = props.decodePart("part") ?: return
                val target = targetOf(props) ?: return
                noteActivity()
                // A part that belongs to this exact session but arrives before its
                // message.updated would otherwise be dropped for good. Parts from
                // subagent sessions keep being ignored: they belong to a child
                // session, not to the messages on screen.
                val ownSession = part.sessionID.isBlank() || part.sessionID == target
                // A full part supersedes any buffered deltas: apply them first so
                // nothing is silently dropped. Under the delta lock, so a timer
                // flush that already drained the buffer cannot append the same
                // deltas again on top of this full text.
                synchronized(deltaLock) {
                    flushDeltas()
                    loadChanges?.parts?.add(part.id)
                    _state.updateIfCurrent(target) { state -> state.upsertPart(part, createMissingMessage = ownSession) }
                }
            }

            "message.part.removed" -> {
                val partId = props.stringOrNull("partID") ?: return
                val target = targetOf(props) ?: return
                noteActivity()
                loadChanges?.removedParts?.add(partId)
                _state.updateIfCurrent(target) { state -> state.withoutPart(partId) }
            }

            "message.part.delta" -> {
                targetOf(props) ?: return
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
                // Strict equality, not targetOf(): the root session
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
                // Strict: a subagent's own todo list must not replace the chat's.
                val sessionId = props.stringOrNull("sessionID") ?: currentSessionId ?: return
                if (sessionId != currentSessionId) return
                val todos = props.decodeTodos() ?: return
                _state.updateIfCurrent(sessionId) { state -> state.copy(todos = todos) }
            }
        }
    }

    private fun handleSessionError(props: JsonObject) {
        flushDeltas()
        val error = props.decodeSessionError()
        val target = targetOf(props)
        // Only an error of the chat's own run ends it. A failed subagent is
        // reported to its parent as a tool result and the parent keeps running:
        // dropping busy here replaced Stop with Send in the middle of the run.
        val sessionId = props.stringOrNull("sessionID")
        val ownRun = target != null && (sessionId == null || sessionId == target)
        if (error?.name == MESSAGE_ABORTED_ERROR) {
            // Abort is a normal cancellation, not a failure to show.
            if (ownRun) {
                statusEpoch.incrementAndGet()
                _state.updateIfCurrent(target!!) { state -> state.copy(busy = false) }
            }
            return
        }
        val message = formatSessionError(error, props["error"])
        Log.w(TAG, "session.error: $message")
        if (target != null) {
            if (ownRun) statusEpoch.incrementAndGet()
            _state.updateIfCurrent(target) { state -> state.copy(busy = state.busy && !ownRun, error = message) }
        } else if (sessionId == null) {
            // Unattributable, but not something to swallow.
            onUnattributedError(message)
        }
    }

    /**
     * The `sessionID` of several events is optional. Without it an event cannot
     * be attributed precisely, so it applies to whichever chat is currently open.
     * Returns that chat's id, or null when the event is not for it. Writes are
     * then scoped to it, so a chat opened in between is never touched.
     */
    private fun targetOf(props: JsonObject): String? {
        val target = currentSessionId ?: return null
        val sessionId = props.stringOrNull("sessionID") ?: return target
        return target.takeIf { sessionTree.matches(sessionId, target) }
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
                        current.copy(busy = false, error = uiText(R.string.error_run_stalled))
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
        // Drained and applied under one lock: applying after releasing it let a
        // full message.part.updated land in between, and the drained deltas were
        // then appended a second time on top of the complete text.
        synchronized(deltaLock) {
            // Released together with the buffer. Leaving it set until the timer
            // coroutine actually finished left a window in which a fresh delta
            // saw "a flush is already scheduled" and was never flushed — losing
            // the tail of a response when no further event followed.
            deltaFlushJob = null
            if (deltaBuffers.isEmpty()) return
            val pending = LinkedHashMap<String, String>(deltaBuffers.size)
            deltaBuffers.forEach { (key, buffer) -> if (buffer.isNotEmpty()) pending[key] = buffer.toString() }
            deltaBuffers.clear()
            if (pending.isEmpty()) return
            _state.update { state -> state.applyDeltas(pending) }
        }
    }

    private companion object {
        const val TAG = "ChatController"
        const val REVERT_RETRY_DELAY_MILLIS = 500L
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
