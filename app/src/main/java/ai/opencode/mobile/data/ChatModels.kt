package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.MessageWithParts
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.Todo
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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
    val error: String? = null,
)

internal const val USER_ROLE = "user"
internal const val TEXT_PART_TYPE = "text"

/**
 * Prefix of optimistic message and part ids. Echoes are recognised by it instead
 * of a side queue of pending ids: the queue was shared by every session, so an
 * echo whose server copy arrived after the user left the chat stayed queued and
 * shifted every later match by one, duplicating each following prompt.
 */
internal const val LOCAL_ID_PREFIX = "local-"

internal fun localUserMessage(id: String, text: String): ChatMessageUi = ChatMessageUi(
    info = Message(id = id, role = USER_ROLE),
    parts = listOf(Part(id = "$id-part", messageID = id, type = TEXT_PART_TYPE, text = text)),
)

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
 * Guards against stale loads: results only apply when the chat still belongs to
 * [sessionId]. Prevents a slow previous session from overwriting a newly opened
 * one. Echoes of prompts still in flight are kept, since the snapshot was taken
 * before the server knew about them.
 */
internal fun ChatState.withMessagesIfCurrent(sessionId: String, messages: List<ChatMessageUi>): ChatState {
    if (this.sessionId != sessionId) return this
    val pendingEchoes = this.messages.filter { it.isLocalEcho }
    return copy(messages = messages + pendingEchoes, loading = false, error = null)
}

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
