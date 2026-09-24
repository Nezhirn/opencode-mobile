package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.OpenCodeJson
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionErrorInfo
import ai.opencode.mobile.data.remote.Todo
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Lenient readers for SSE event payloads. Every decode is wrapped so a payload of
 * an unexpected shape drops that one event instead of failing the stream.
 */

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun <T> JsonElement.decodeOrNull(deserializer: DeserializationStrategy<T>): T? =
    runCatching { OpenCodeJson.decodeFromJsonElement(deserializer, this) }.getOrNull()

internal fun JsonObject.decodeSession(key: String): Session? = this[key]?.decodeOrNull(Session.serializer())

internal fun JsonObject.decodeMessage(key: String): Message? = this[key]?.decodeOrNull(Message.serializer())

internal fun JsonObject.decodePart(key: String): Part? = this[key]?.decodeOrNull(Part.serializer())

internal fun JsonObject.decodeSessionError(): SessionErrorInfo? =
    this["error"]?.decodeOrNull(SessionErrorInfo.serializer())

internal fun JsonObject.decodeTodos(): List<Todo>? = this["todos"]?.decodeOrNull(ListSerializer(Todo.serializer()))

/** `permission.asked` carries the request itself as its properties. */
internal fun JsonObject.decodePermissionRequest(): PermissionRequest? =
    decodeOrNull(PermissionRequest.serializer())?.takeIf { it.id.isNotBlank() }

/** `question.asked` carries the request itself as its properties. */
internal fun JsonObject.decodeQuestionRequest(): QuestionRequest? =
    decodeOrNull(QuestionRequest.serializer())?.takeIf { it.id.isNotBlank() }

/** Reads `status.type` of a `session.status` payload. */
internal fun JsonObject.statusType(): String? = (this["status"] as? JsonObject)?.stringOrNull("type")

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

/** True for the session status types during which a run is in progress. */
internal fun isBusyStatus(type: String?): Boolean = type == "busy" || type == "retry"
