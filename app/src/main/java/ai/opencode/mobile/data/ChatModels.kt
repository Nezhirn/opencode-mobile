package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.MessageWithParts
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.SessionRevert
import ai.opencode.mobile.data.remote.Todo
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverName: String?) : ConnectionState
    data class Error(val message: UiText) : ConnectionState
}

@Immutable
data class ChatMessageUi(
    val info: Message,
    val parts: List<Part>,
) {
    /** An optimistic copy of a sent prompt, shown until the server echoes it. */
    val isLocalEcho: Boolean get() = info.id.startsWith(LOCAL_ID_PREFIX)
}

@Immutable
data class ChatState(
    val sessionId: String? = null,
    val title: String = "",
    val messages: List<ChatMessageUi> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: UiText? = null,
    /** Where the session is rolled back to; messages from there on are hidden. */
    val revert: SessionRevert? = null,
    /** A rollback or restore is on its way to the server. */
    val reverting: Boolean = false,
) {
    /** The messages to show: everything before the rollback point. */
    val visibleMessages: List<ChatMessageUi> by lazy(LazyThreadSafetyMode.PUBLICATION) { computeVisibleMessages() }

    /** User messages hidden by the rollback, oldest first; each can be restored. */
    val revertedUserMessages: List<ChatMessageUi> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val index = revertIndex()
        if (index < 0) {
            emptyList()
        } else {
            val first = if (revert?.partID != null) index + 1 else index
            messages.drop(first).filter { it.info.role == USER_ROLE }
        }
    }

    private fun revertIndex(): Int {
        val point = revert ?: return -1
        return messages.indexOfFirst { it.info.id == point.messageID }
    }

    /**
     * Mirrors the server's cleanup: messages after the point go; with a
     * `partID` the message at the point stays, cut before that part.
     */
    private fun computeVisibleMessages(): List<ChatMessageUi> {
        val index = revertIndex()
        if (index < 0) return messages
        val partId = revert?.partID ?: return messages.subList(0, index).toList()
        val message = messages[index]
        val partIndex = message.parts.indexOfFirst { it.id == partId }
        val kept = if (partIndex < 0) message else message.copy(parts = message.parts.take(partIndex))
        return messages.take(index) + kept
    }
}

/**
 * The message to roll back to when [messageId] is restored: the next hidden user
 * message, so [messageId] and its answers come back while later ones stay
 * hidden. Null when it is the last one — then the whole rollback is undone.
 */
internal fun ChatState.restoreTarget(messageId: String): ChatMessageUi? {
    val hidden = revertedUserMessages
    val index = hidden.indexOfFirst { it.info.id == messageId }
    return if (index < 0) null else hidden.getOrNull(index + 1)
}

/**
 * Drops what the rollback hid, as the server does once the next prompt is
 * sent. Applied before the prompt's echo is added, which would otherwise land
 * behind the rollback point and stay hidden.
 */
internal fun ChatState.committedRevert(): ChatState =
    if (revert == null) this else copy(messages = visibleMessages, revert = null)

/**
 * Undoes [committedRevert] after the prompt that committed it failed: the
 * messages it dropped ([before] is the list it started from) come back behind
 * the rollback point [revert], the message at the point with all its parts.
 */
internal fun ChatState.withRestoredRevert(revert: SessionRevert?, before: List<ChatMessageUi>): ChatState {
    if (revert == null) return this
    val currentById = messages.associateBy { it.info.id }
    val beforeIds = before.mapTo(HashSet()) { it.info.id }
    val restored = before.map { original ->
        currentById[original.info.id]?.takeUnless { it.info.id == revert.messageID } ?: original
    }
    return copy(messages = restored + messages.filter { it.info.id !in beforeIds }, revert = revert)
}

/** Ids of the messages [committedRevert] drops. */
internal fun ChatState.revertedMessageIds(): Set<String> {
    if (revert == null) return emptySet()
    val visible = visibleMessages.mapTo(HashSet()) { it.info.id }
    return messages.mapNotNullTo(HashSet()) { message -> message.info.id.takeIf { it !in visible } }
}

/**
 * Ids of messages and parts that events changed or removed while a snapshot of
 * the chat was loading. Written by the event collector, read by the loader.
 */
internal class LiveChanges {
    /** Messages whose `info` an event replaced. */
    val messages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Parts a full `message.part.updated` replaced. */
    val parts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val removedMessages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val removedParts: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

internal const val USER_ROLE = "user"
internal const val TEXT_PART_TYPE = "text"

/**
 * Prefix of optimistic message and part ids. Echoes are recognised by it instead
 * of a side queue of pending ids: the queue was shared by every session, so an
 * echo whose server copy arrived after the user left the chat stayed queued and
 * shifted every later match by one, duplicating each following prompt.
 */
internal const val LOCAL_ID_PREFIX = "local-"

internal fun localUserMessage(id: String, text: String, attachments: List<Attachment> = emptyList()): ChatMessageUi {
    val textParts = if (text.isEmpty() && attachments.isNotEmpty()) {
        emptyList()
    } else {
        listOf(Part(id = "$id-part", messageID = id, type = TEXT_PART_TYPE, text = text))
    }
    val fileParts = attachments.mapIndexed { index, attachment ->
        Part(
            id = "$id-file-$index",
            messageID = id,
            type = FILE_PART_TYPE,
            mime = attachment.mime,
            filename = attachment.filename,
            url = attachment.url,
        )
    }
    return ChatMessageUi(info = Message(id = id, role = USER_ROLE), parts = textParts + fileParts)
}

/** What the user typed in [this] message, without synthetic parts. */
internal fun ChatMessageUi.userText(): String =
    parts.filter { it.type == TEXT_PART_TYPE && it.synthetic != true }.joinToString("\n") { it.text.orEmpty() }.trim()

/** The files sent with [this] message. */
internal fun ChatMessageUi.attachments(): List<Attachment> = parts.mapNotNull { it.toAttachment() }

internal fun MessageWithParts.toUi(): ChatMessageUi = ChatMessageUi(info = info, parts = parts)

/**
 * Applies [transform] only while the open chat still belongs to [sessionId], so a
 * write queued before the user switched sessions cannot land in the new one.
 */
internal inline fun MutableStateFlow<ChatState>.updateIfCurrent(
    sessionId: String,
    crossinline transform: (ChatState) -> ChatState,
) = update { state -> if (state.sessionId != sessionId) state else transform(state) }

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
 * Applies a `message.updated`. The first server copy of a user message takes the
 * place of the oldest local echo, keeping its position and its placeholder text
 * until the real parts arrive, so the bubble neither jumps nor flashes empty.
 * Later updates of an already known message (the server re-sends user messages,
 * e.g. when a summary is attached) never consume an echo.
 */
internal fun ChatState.applyMessageUpdate(info: Message): ChatState {
    if (messages.any { it.info.id == info.id }) return upsertMessage(info)
    if (info.role == USER_ROLE) {
        val echoIndex = messages.indexOfFirst { it.isLocalEcho }
        if (echoIndex >= 0) {
            val echo = messages[echoIndex]
            val replaced = echo.copy(info = info, parts = echo.parts.map { it.copy(messageID = info.id) })
            return copy(messages = messages.toMutableList().also { it[echoIndex] = replaced })
        }
    }
    return upsertMessage(info)
}

/** Drops an echo whose prompt the server never accepted. */
internal fun ChatState.withoutMessage(messageId: String): ChatState =
    copy(messages = messages.filterNot { it.info.id == messageId })

/**
 * Inserts or replaces [part] on its message. When the message is not on screen
 * yet and [createMissingMessage] is set, a stub is appended so a part that
 * arrives before its `message.updated` is not lost; the stub is filled in when
 * that event lands. The first real part of a message replaces the placeholder
 * parts carried over from a local echo.
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
    val parts = message.parts
        .filterNot { it.id.startsWith(LOCAL_ID_PREFIX) && !part.id.startsWith(LOCAL_ID_PREFIX) }
        .toMutableList()
    val partIndex = parts.indexOfFirst { it.id == part.id }
    if (partIndex >= 0) parts[partIndex] = part else parts.add(part)
    return copy(messages = messages.toMutableList().also { it[messageIndex] = message.copy(parts = parts) })
}

internal fun ChatState.withoutPart(partId: String): ChatState {
    if (messages.none { message -> message.parts.any { it.id == partId } }) return this
    return copy(
        messages = messages.map { message ->
            if (message.parts.none { it.id == partId }) message
            else message.copy(parts = message.parts.filterNot { it.id == partId })
        },
    )
}

/**
 * Applies a loaded snapshot of the chat. Guards against stale loads: results
 * only apply when the chat still belongs to [sessionId], so a slow previous
 * session cannot overwrite a newly opened one.
 *
 * The snapshot is merged into, not swapped for, the live state, because events
 * kept arriving while it loaded:
 * - messages that appeared meanwhile (not in [knownBefore], not in the snapshot)
 *   are kept — e.g. a prompt sent during the load whose echo the server already
 *   confirmed; dropping them made the prompt vanish from the screen;
 * - a part whose live text is longer than the snapshot's keeps the live text,
 *   so tokens streamed after the snapshot was taken are not cut out;
 * - messages and parts that [changes] lists keep their live version: a tool
 *   that completed while the snapshot loaded stayed "running" for good, since
 *   no later event repeats it; removed ones do not come back;
 * - an echo is kept only while the snapshot has no matching user message: when
 *   its `message.updated` was lost in a stream gap, keeping it showed the
 *   prompt twice, and every later prompt then consumed the wrong echo.
 * Messages that were known before the load and are missing from the snapshot
 * were removed on the server and go.
 */
internal fun ChatState.withMessagesIfCurrent(
    sessionId: String,
    snapshot: List<ChatMessageUi>,
    knownBefore: Set<String> = emptySet(),
    changes: LiveChanges? = null,
): ChatState {
    if (this.sessionId != sessionId) return this
    val messages = if (changes == null || changes.removedMessages.isEmpty()) {
        snapshot
    } else {
        snapshot.filterNot { it.info.id in changes.removedMessages }
    }
    val liveById = this.messages.associateBy { it.info.id }
    val snapshotIds = messages.mapTo(HashSet()) { it.info.id }
    val merged = messages.map { message -> liveById[message.info.id]?.let { message.mergedWith(it, changes) } ?: message }
    // User texts the server has that the live state does not know by id: each
    // can account for one echo.
    val unmatchedTexts = messages
        .filter { it.info.role == USER_ROLE && it.info.id !in liveById }
        .mapTo(ArrayList()) { it.userText() }
    val extras = this.messages.filter { message ->
        when {
            message.info.id in snapshotIds -> false
            message.isLocalEcho -> !unmatchedTexts.remove(message.userText())
            else -> message.info.id !in knownBefore
        }
    }
    // The error stays: a failed send or a session error must not vanish just
    // because the chat was read back afterwards.
    return copy(messages = merged + extras, loading = false)
}

/** The snapshot copy of a message, keeping what is live and ahead of it. */
private fun ChatMessageUi.mergedWith(live: ChatMessageUi, changes: LiveChanges?): ChatMessageUi {
    val info = if (changes != null && info.id in changes.messages) live.info else info
    if (live.parts.isEmpty()) return copy(info = info)
    val liveParts = live.parts.associateBy { it.id }
    val parts = parts.mapNotNull { part ->
        if (changes != null && part.id in changes.removedParts) return@mapNotNull null
        val livePart = liveParts[part.id]
        when {
            livePart == null -> part
            changes != null && part.id in changes.parts -> livePart
            (livePart.text?.length ?: 0) > (part.text?.length ?: 0) -> livePart
            else -> part
        }
    }
    val known = parts.mapTo(HashSet()) { it.id }
    val newer = live.parts.filter { it.id !in known && !it.id.startsWith(LOCAL_ID_PREFIX) }
    return copy(info = info, parts = if (newer.isEmpty()) parts else parts + newer)
}

internal fun ChatState.withLoadErrorIfCurrent(sessionId: String, message: UiText): ChatState =
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
 * so Compose can skip them. A delta for a part that is not on screen yet is
 * dropped: the part's final `message.part.updated` carries its full text.
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
