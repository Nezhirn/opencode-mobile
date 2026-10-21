package ai.opencode.mobile.data.remote.v2

import ai.opencode.mobile.data.remote.EventEnvelope
import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionTime
import ai.opencode.mobile.data.remote.Todo
import ai.opencode.mobile.data.remote.ToolState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/** What a v2 client keeps about the server across calls and events. */
internal class V2State {
    /** Sessions as last read or changed by events, by id. */
    val sessions = ConcurrentHashMap<String, Session>()

    /** Forms asked of the user, by id: a reply has to name each field by key. */
    val forms = ConcurrentHashMap<String, JsonObject>()
}

/**
 * Turns the v2 event stream into the v1 events the app handles.
 *
 * v2 streams fine-grained steps (a step started, a text fragment, a tool call
 * finished) where v1 sent whole messages and parts; this keeps what it needs to
 * send a complete message or part for each of them. Called from the single
 * event collector only, so its own state needs no locking.
 *
 * With a [directory] only that project's events go through: the v2 stream
 * carries every project of the server, the v1 one only the instance's.
 */
internal class V2EventTranslator(
    private val directory: String?,
    private val state: V2State,
    /** Reads a session the stream speaks of but the client does not know yet; null when it cannot. */
    private val fetchSession: suspend (String) -> Session?,
) {
    /** Assistant messages of steps seen, by id: their updates carry the whole message. */
    private val assistants = HashMap<String, Message>()

    /** Tool parts, by part id: each change of the call carries the whole part. */
    private val tools = HashMap<String, Part>()

    /** The running compaction of each session: the marker its summary hangs off. */
    private val compactions = HashMap<String, String>()

    suspend fun translate(event: JsonObject): List<EventEnvelope> {
        val type = event.str("type") ?: return emptyList()
        val data = event.obj("data") ?: JsonObject(emptyMap())
        if (!concernsProject(event, data)) return emptyList()
        val sessionId = data.str("sessionID").orEmpty()
        val eventId = event.str("id").orEmpty()
        val created = event.long("created") ?: System.currentTimeMillis()
        return when (type) {
            "server.connected" -> listOf(envelope("server.connected"))

            "session.created" -> listOfNotNull(sessionOf(sessionId, refresh = true)?.let { sessionEnvelope("session.created", it) })
            "session.deleted" -> {
                state.sessions.remove(sessionId)
                listOf(sessionEnvelope("session.deleted", Session(id = sessionId)))
            }
            // Archived sessions are not listed, as on the web.
            "session.archived" -> {
                val session = state.sessions[sessionId] ?: Session(id = sessionId)
                listOf(sessionEnvelope("session.deleted", session))
            }
            "session.unarchived" -> listOfNotNull(sessionOf(sessionId, refresh = true)?.let { sessionEnvelope("session.updated", it) })
            "session.renamed" -> updateSession(sessionId) { it.copy(title = data.str("title") ?: it.title) }
            "session.agent.selected" -> updateSession(sessionId) { it.copy(agent = data.str("agent") ?: it.agent) }
            "session.model.selected" -> updateSession(sessionId) { it.copy(model = data.obj("model")?.toSessionModel() ?: it.model) }
            "session.usage.updated" -> updateSession(sessionId) {
                it.copy(cost = data.double("cost") ?: it.cost, tokens = data.obj("tokens") ?: it.tokens)
            }
            "session.revert.staged" -> updateSession(sessionId) { it.copy(revert = data.obj("revert")?.toV1Revert()) }
            "session.revert.cleared", "session.revert.committed" -> updateSession(sessionId) { it.copy(revert = null) }

            "session.inbox.enqueued" -> enqueued(sessionId, data)
            "session.inbox.cancelled" -> listOf(
                envelope("message.removed") {
                    put("sessionID", sessionId)
                    put("messageID", data.str("inboxID").orEmpty())
                },
            )

            "session.step.started" -> stepStarted(sessionId, data)
            "session.step.ended" -> stepEnded(sessionId, data)
            "session.step.failed" -> stepFailed(sessionId, data)
            "session.retry.scheduled" -> {
                val messageId = data.str("assistantMessageID") ?: return emptyList()
                listOf(partUpdated(retryPart(sessionId, messageId, data)))
            }

            "session.text.started" -> fragmentStarted(sessionId, data, "text")
            "session.text.delta" -> fragmentDelta(sessionId, data, "text")
            "session.text.ended" -> fragmentEnded(sessionId, data, "text")
            "session.reasoning.started" -> fragmentStarted(sessionId, data, "reasoning")
            "session.reasoning.delta" -> fragmentDelta(sessionId, data, "reasoning")
            "session.reasoning.ended" -> fragmentEnded(sessionId, data, "reasoning")

            "session.tool.input.started" -> toolStarted(sessionId, data)
            "session.tool.input.ended" -> updateTool(sessionId, data) { it.copy(raw = data.str("text")) }
            "session.tool.called" -> updateTool(sessionId, data) { it.copy(status = "running", input = data.obj("input"), raw = null) }
            "session.tool.progress" -> updateTool(sessionId, data) {
                val metadata = data.obj("metadata")
                it.copy(status = "running", metadata = metadata, title = metadata?.str("title") ?: it.title)
            }
            "session.tool.success" -> toolSucceeded(sessionId, data)
            "session.tool.failed" -> updateTool(sessionId, data) {
                val metadata = data.obj("metadata") ?: it.metadata
                it.copy(
                    status = "error",
                    error = data.obj("error")?.str("message"),
                    output = data.arr("content")?.contentText() ?: it.output,
                    metadata = metadata,
                    title = metadata?.str("title") ?: it.title,
                )
            }

            "session.shell.started", "session.shell.ended" -> shell(sessionId, data, eventId, type == "session.shell.ended")

            "session.execution.started" -> listOf(status(sessionId, "busy"))
            "session.execution.succeeded", "session.execution.interrupted" -> idle(sessionId)
            "session.execution.failed" -> idle(sessionId) + listOfNotNull(
                data.obj("error")?.let { error ->
                    envelope("session.error") {
                        put("sessionID", sessionId)
                        put("error", error.toV1Error())
                    }
                },
            )

            "session.compaction.started" -> compactionStarted(sessionId, data, eventId, created)
            "session.compaction.delta" -> {
                val marker = compactions[sessionId] ?: return emptyList()
                listOf(delta(sessionId, V2Ids.summary(marker), V2Ids.summaryText(marker), data.str("text").orEmpty()))
            }
            "session.compaction.ended" -> compactionEnded(sessionId, data, eventId, created)
            "session.compaction.failed" -> compactionFailed(sessionId, data, eventId, created)

            "permission.asked" -> listOfNotNull(
                data.toV1Permission()?.let { request -> EventEnvelope(type = "permission.asked", properties = encode(PermissionRequest.serializer(), request) as JsonObject) },
            )
            "permission.replied" -> listOf(
                envelope("permission.replied") {
                    put("sessionID", sessionId)
                    put("requestID", data.str("requestID").orEmpty())
                },
            )

            "form.created" -> {
                val form = data.obj("form") ?: return emptyList()
                val question = form.toV1Question() ?: return emptyList()
                state.forms[question.id] = form
                listOf(EventEnvelope(type = "question.asked", properties = encode(QuestionRequest.serializer(), question) as JsonObject))
            }
            "form.replied", "form.cancelled" -> {
                val id = data.str("id") ?: data.str("formID") ?: return emptyList()
                state.forms.remove(id)
                listOf(
                    envelope("question.replied") {
                        put("sessionID", sessionId)
                        put("requestID", id)
                    },
                )
            }

            "mcp.status.changed", "mcp.resources.changed" -> listOf(envelope("mcp.tools.changed"))

            else -> emptyList()
        }
    }

    /** Whether [event] belongs to the project this client works in. */
    private suspend fun concernsProject(event: JsonObject, data: JsonObject): Boolean {
        val directory = directory ?: return true
        event.obj("location")?.str("directory")?.let { return samePath(it, directory) }
        // Session events without a location belong to the session's project.
        val sessionId = data.str("sessionID") ?: data.obj("form")?.str("sessionID") ?: return true
        val session = sessionOf(sessionId) ?: return true
        return session.directory.isBlank() || samePath(session.directory, directory)
    }

    private suspend fun sessionOf(id: String, refresh: Boolean = false): Session? {
        if (id.isBlank()) return null
        if (!refresh) state.sessions[id]?.let { return it }
        return fetchSession(id)?.also { state.sessions[id] = it } ?: state.sessions[id]
    }

    private suspend fun updateSession(id: String, change: (Session) -> Session): List<EventEnvelope> {
        val session = sessionOf(id) ?: return emptyList()
        val updated = change(session).let { it.copy(time = (it.time ?: SessionTime()).copy(updated = System.currentTimeMillis())) }
        state.sessions[id] = updated
        return listOf(sessionEnvelope("session.updated", updated))
    }

    private fun enqueued(sessionId: String, data: JsonObject): List<EventEnvelope> {
        val item = data.obj("item") ?: return emptyList()
        val id = data.str("inboxID") ?: return emptyList()
        if (item.str("type") != V2_USER) return emptyList()
        val payload = item.obj("payload") ?: JsonObject(emptyMap())
        // The inbox payload is the prompt as sent: its id is the message's.
        val message = JsonObject(payload + mapOf("id" to JsonPrimitive(id))).userMessage(sessionId, id)
        val info = message.info.copy(time = SessionTime(created = System.currentTimeMillis()))
        return listOf(messageUpdated(info)) + message.parts.map(::partUpdated)
    }

    private fun stepStarted(sessionId: String, data: JsonObject): List<EventEnvelope> {
        val id = data.str("assistantMessageID") ?: return emptyList()
        val model = data.obj("model")
        val agent = data.str("agent")
        val previous = assistants[id]
        // A step that starts again is a retry: what it failed with is over.
        val info = (previous ?: Message(id = id, sessionID = sessionId, role = V2_ASSISTANT, time = SessionTime(created = data.long("started") ?: 0)))
            .copy(agent = agent, mode = agent, modelID = model?.str("id"), providerID = model?.str("providerID"), error = null, finish = null)
        assistants[id] = info
        val cleared = if (previous != null) {
            listOf(partRemoved(sessionId, id, V2Ids.retry(id)), partRemoved(sessionId, id, V2Ids.finish(id)))
        } else {
            emptyList()
        }
        return listOf(messageUpdated(info)) + cleared
    }

    private fun stepEnded(sessionId: String, data: JsonObject): List<EventEnvelope> {
        val id = data.str("assistantMessageID") ?: return emptyList()
        val info = assistantOf(sessionId, id).copy(finish = data.str("finish"), cost = data.double("cost"), tokens = data.obj("tokens"))
        assistants[id] = info
        return listOf(
            messageUpdated(info),
            partRemoved(sessionId, id, V2Ids.retry(id)),
            partUpdated(finishPart(sessionId, id, info.finish, info.cost, info.tokens)),
        )
    }

    private fun stepFailed(sessionId: String, data: JsonObject): List<EventEnvelope> {
        val id = data.str("assistantMessageID") ?: return emptyList()
        val info = assistantOf(sessionId, id).copy(
            finish = data.str("finish") ?: "error",
            error = data.obj("error")?.toV1Error(),
            cost = data.double("cost"),
            tokens = data.obj("tokens"),
        )
        assistants[id] = info
        return listOf(messageUpdated(info), partRemoved(sessionId, id, V2Ids.retry(id)))
    }

    private fun assistantOf(sessionId: String, id: String): Message =
        assistants[id] ?: Message(id = id, sessionID = sessionId, role = V2_ASSISTANT, time = SessionTime(created = System.currentTimeMillis()))

    private fun fragmentId(data: JsonObject, kind: String): Pair<String, String>? {
        val messageId = data.str("assistantMessageID") ?: return null
        val ordinal = data.long("ordinal")?.toInt() ?: 0
        val partId = if (kind == "text") V2Ids.text(messageId, ordinal) else V2Ids.reasoning(messageId, ordinal)
        return messageId to partId
    }

    private fun fragmentStarted(sessionId: String, data: JsonObject, kind: String): List<EventEnvelope> {
        val (messageId, partId) = fragmentId(data, kind) ?: return emptyList()
        return listOf(partUpdated(textPart(sessionId, messageId, partId, "", type = kind)))
    }

    private fun fragmentDelta(sessionId: String, data: JsonObject, kind: String): List<EventEnvelope> {
        val (messageId, partId) = fragmentId(data, kind) ?: return emptyList()
        return listOf(delta(sessionId, messageId, partId, data.str("delta").orEmpty()))
    }

    private fun fragmentEnded(sessionId: String, data: JsonObject, kind: String): List<EventEnvelope> {
        val (messageId, partId) = fragmentId(data, kind) ?: return emptyList()
        return listOf(partUpdated(textPart(sessionId, messageId, partId, data.str("text").orEmpty(), type = kind)))
    }

    private fun toolStarted(sessionId: String, data: JsonObject): List<EventEnvelope> {
        val messageId = data.str("assistantMessageID") ?: return emptyList()
        val callId = data.str("id") ?: return emptyList()
        val part = Part(
            id = V2Ids.tool(messageId, callId),
            sessionID = sessionId,
            messageID = messageId,
            type = "tool",
            callID = callId,
            tool = data.str("name").orEmpty(),
            state = ToolState(status = "pending"),
        )
        tools[part.id] = part
        return listOf(partUpdated(part))
    }

    private fun updateTool(sessionId: String, data: JsonObject, change: (ToolState) -> ToolState): List<EventEnvelope> {
        val messageId = data.str("assistantMessageID") ?: return emptyList()
        val callId = data.str("id") ?: return emptyList()
        val id = V2Ids.tool(messageId, callId)
        val part = tools[id] ?: Part(id = id, sessionID = sessionId, messageID = messageId, type = "tool", callID = callId, tool = "")
        val updated = part.copy(state = change(part.state ?: ToolState(status = "pending")))
        tools[id] = updated
        return listOf(partUpdated(updated))
    }

    private fun toolSucceeded(sessionId: String, data: JsonObject): List<EventEnvelope> {
        val updates = updateTool(sessionId, data) {
            val metadata = data.obj("metadata") ?: it.metadata
            it.copy(status = "completed", output = data.arr("content")?.contentText(), metadata = metadata, title = metadata?.str("title") ?: it.title)
        }
        val messageId = data.str("assistantMessageID").orEmpty()
        val part = tools[V2Ids.tool(messageId, data.str("id").orEmpty())]
        // The todo list is what the todo tool last wrote.
        val todos = if (part?.tool == "todowrite") part.state?.input?.todosOf() else null
        return updates + listOfNotNull(
            todos?.let {
                envelope("todo.updated") {
                    put("sessionID", sessionId)
                    put("todos", encode(ListSerializer(Todo.serializer()), it))
                }
            },
        )
    }

    private fun shell(sessionId: String, data: JsonObject, eventId: String, ended: Boolean): List<EventEnvelope> {
        val shell = data.obj("shell") ?: return emptyList()
        // The history keeps the command under the message of its start event.
        val shellId = shell.str("id") ?: return emptyList()
        val messageId = shellMessages.getOrPut(shellId) { V2Ids.fromEvent(eventId) }
        val item = buildJsonObject {
            put("id", messageId)
            put("type", "shell")
            put("shellID", shellId)
            put("command", shell.str("command").orEmpty())
            shell["exit"]?.let { put("exit", it) }
            data.obj("output")?.let { put("output", it) }
            if (ended) putJsonObject("time") { put("completed", System.currentTimeMillis()) }
        }
        val message = item.shellMessage(sessionId, messageId)
        if (ended) shellMessages.remove(shellId)
        return listOf(messageUpdated(message.info)) + message.parts.map(::partUpdated)
    }

    private val shellMessages = HashMap<String, String>()

    private fun idle(sessionId: String): List<EventEnvelope> = listOf(
        status(sessionId, "idle"),
        envelope("session.idle") { put("sessionID", sessionId) },
    )

    private fun compactionStarted(sessionId: String, data: JsonObject, eventId: String, created: Long): List<EventEnvelope> {
        val marker = data.str("inputID") ?: V2Ids.fromEvent(eventId)
        compactions[sessionId] = marker
        val item = buildJsonObject {
            put("id", marker)
            put("type", "compaction")
            put("status", "running")
            put("reason", data.str("reason") ?: "manual")
            put("summary", "")
            putJsonObject("time") { put("created", created) }
        }
        return compactionEnvelopes(sessionId, item, marker, withEmptyText = true)
    }

    private fun compactionEnded(sessionId: String, data: JsonObject, eventId: String, created: Long): List<EventEnvelope> {
        val marker = compactions.remove(sessionId) ?: V2Ids.fromEvent(eventId)
        val item = buildJsonObject {
            put("id", marker)
            put("type", "compaction")
            put("status", "completed")
            put("reason", data.str("reason") ?: "manual")
            put("summary", data.str("text").orEmpty())
            data.obj("model")?.let { put("model", it) }
            data["cost"]?.let { put("cost", it) }
            data.obj("tokens")?.let { put("tokens", it) }
            putJsonObject("time") { put("created", created) }
        }
        return compactionEnvelopes(sessionId, item, marker, withEmptyText = false) +
            envelope("session.compacted") { put("sessionID", sessionId) }
    }

    private fun compactionFailed(sessionId: String, data: JsonObject, eventId: String, created: Long): List<EventEnvelope> {
        val marker = compactions.remove(sessionId) ?: data.str("inputID") ?: V2Ids.fromEvent(eventId)
        val item = buildJsonObject {
            put("id", marker)
            put("type", "compaction")
            put("status", "failed")
            put("reason", data.str("reason") ?: "manual")
            put("error", data.obj("error") ?: buildJsonObject { put("type", "compaction.failed") })
            putJsonObject("time") { put("created", created) }
        }
        return compactionEnvelopes(sessionId, item, marker, withEmptyText = false)
    }

    private fun compactionEnvelopes(sessionId: String, item: JsonObject, marker: String, withEmptyText: Boolean): List<EventEnvelope> {
        val (markerMessage, summary) = item.compactionMessages(sessionId, marker)
        val summaryParts = summary.parts.ifEmpty {
            if (withEmptyText) listOf(textPart(sessionId, summary.info.id, V2Ids.summaryText(marker), "")) else emptyList()
        }
        return listOf(messageUpdated(markerMessage.info)) + markerMessage.parts.map(::partUpdated) +
            messageUpdated(summary.info) + summaryParts.map(::partUpdated)
    }

    // --- Envelopes ---

    private fun envelope(type: String, build: JsonObjectBuilder.() -> Unit = {}) =
        EventEnvelope(type = type, properties = buildJsonObject(build))

    private fun sessionEnvelope(type: String, session: Session) = envelope(type) { put("info", encode(Session.serializer(), session)) }

    private fun messageUpdated(info: Message) = envelope("message.updated") {
        put("sessionID", info.sessionID)
        put("info", encode(Message.serializer(), info))
    }

    private fun partUpdated(part: Part) = envelope("message.part.updated") {
        put("sessionID", part.sessionID)
        put("part", encode(Part.serializer(), part))
    }

    private fun partRemoved(sessionId: String, messageId: String, partId: String) = envelope("message.part.removed") {
        put("sessionID", sessionId)
        put("messageID", messageId)
        put("partID", partId)
    }

    private fun delta(sessionId: String, messageId: String, partId: String, text: String) = envelope("message.part.delta") {
        put("sessionID", sessionId)
        put("messageID", messageId)
        put("partID", partId)
        put("field", "text")
        put("delta", text)
    }

    private fun status(sessionId: String, type: String) = envelope("session.status") {
        put("sessionID", sessionId)
        putJsonObject("status") { put("type", type) }
    }
}
