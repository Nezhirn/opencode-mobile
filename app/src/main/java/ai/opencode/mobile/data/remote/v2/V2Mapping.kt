package ai.opencode.mobile.data.remote.v2

import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.ConfigProviders
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.McpStatus
import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.MessageWithParts
import ai.opencode.mobile.data.remote.Model
import ai.opencode.mobile.data.remote.ModelLimit
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.Project
import ai.opencode.mobile.data.remote.ProjectIcon
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.QuestionInfo
import ai.opencode.mobile.data.remote.QuestionOption
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionModel
import ai.opencode.mobile.data.remote.SessionRevert
import ai.opencode.mobile.data.remote.SessionTime
import ai.opencode.mobile.data.remote.Todo
import ai.opencode.mobile.data.remote.ToolState
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.data.remote.VcsInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * opencode v2 shapes mapped onto the ones the app is built on (the v1 API).
 *
 * v2 keeps a session as a list of typed items: a user prompt, an assistant step
 * with its text, reasoning and tool calls as content, a compaction, markers.
 * The app shows messages made of parts; every item becomes such a message, and
 * the parts get ids derived from the item (text and reasoning by their ordinal
 * among their kind, tools by call id), so the history and the live events of
 * the same item always agree on them.
 */

internal object V2Ids {
    fun userText(messageId: String) = "$messageId:text"
    fun userFile(messageId: String, index: Int) = "$messageId:file:$index"
    fun text(messageId: String, ordinal: Int) = "$messageId:text:$ordinal"
    fun reasoning(messageId: String, ordinal: Int) = "$messageId:reasoning:$ordinal"
    fun tool(messageId: String, callId: String) = "$messageId:tool:$callId"
    fun finish(messageId: String) = "$messageId:finish"
    fun retry(messageId: String) = "$messageId:retry"
    fun compaction(messageId: String) = "$messageId:compaction"
    fun summary(messageId: String) = "$messageId:summary"
    fun summaryText(messageId: String) = "$messageId:summary:text"
    fun shell(messageId: String) = "$messageId:shell"

    /** The message the server records for an event, e.g. the idle marker of `session.execution.*`. */
    fun fromEvent(eventId: String) = "msg_" + eventId.removePrefix("evt_")
}

internal const val V2_USER = "user"
internal const val V2_ASSISTANT = "assistant"

// --- Sessions ---

internal fun JsonObject.toV1Session(): Session? {
    val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
    val time = obj("time")
    val created = time?.long("created") ?: 0
    return Session(
        id = id,
        projectID = str("projectID").orEmpty(),
        directory = obj("location")?.str("directory").orEmpty(),
        parentID = str("parentID"),
        title = str("title").orEmpty(),
        agent = str("agent"),
        model = obj("model")?.toSessionModel(),
        cost = double("cost"),
        tokens = obj("tokens"),
        time = SessionTime(created = created, updated = time?.long("updated") ?: created, archived = time?.double("archived")),
        revert = obj("revert")?.toV1Revert(),
    )
}

internal fun JsonObject.toSessionModel(): SessionModel? {
    val id = str("id") ?: return null
    return SessionModel(id = id, providerID = str("providerID").orEmpty(), variant = str("variant"))
}

internal fun JsonObject.toV1Revert(): SessionRevert? {
    val messageId = str("messageID")?.takeIf { it.isNotBlank() } ?: return null
    return SessionRevert(messageID = messageId, partID = str("partID"), snapshot = str("snapshot"))
}

/** A v2 error (`{type, message}`) in the v1 shape (`{name, data: {message}}`) the app formats. */
internal fun JsonObject.toV1Error(): JsonObject {
    val type = str("type").orEmpty()
    val aborted = type.contains("abort", ignoreCase = true) || type.contains("interrupt", ignoreCase = true)
    return buildJsonObject {
        put("name", if (aborted) "MessageAbortedError" else type.ifBlank { "Error" })
        putJsonObject("data") { str("message")?.let { put("message", it) } }
    }
}

// --- Messages ---

/** The history as messages with parts, oldest first; markers with nothing to show are left out. */
internal fun v2MessagesToV1(sessionId: String, items: List<JsonObject>): List<MessageWithParts> {
    val result = ArrayList<MessageWithParts>(items.size)
    // An idle marker shows its error only when no step of the turn did.
    var turnErrorShown = false
    for (item in items) {
        val id = item.str("id") ?: continue
        when (item.str("type")) {
            V2_USER -> {
                turnErrorShown = false
                result += item.userMessage(sessionId, id)
            }
            V2_ASSISTANT -> {
                val message = item.assistantMessage(sessionId, id)
                if (message.info.error != null) turnErrorShown = true
                result += message
            }
            "compaction" -> result += item.compactionMessages(sessionId, id)
            "shell" -> result += item.shellMessage(sessionId, id)
            "idle" -> {
                val error = item.obj("error")
                if (error != null && item.str("outcome") == "failed" && !turnErrorShown) {
                    result += MessageWithParts(
                        info = Message(id = id, sessionID = sessionId, role = V2_ASSISTANT, time = item.createdTime(), error = error.toV1Error()),
                    )
                }
                turnErrorShown = false
            }
            // Agent and model switches, instruction updates, skills: nothing a phone needs to show.
            else -> Unit
        }
    }
    return result
}

private fun JsonObject.createdTime(): SessionTime = SessionTime(created = obj("time")?.long("created") ?: 0)

internal fun JsonObject.userMessage(sessionId: String, id: String): MessageWithParts {
    val parts = ArrayList<Part>()
    val text = str("text").orEmpty()
    if (text.isNotEmpty()) {
        parts += Part(id = V2Ids.userText(id), sessionID = sessionId, messageID = id, type = "text", text = text)
    }
    arr("files")?.objects()?.forEachIndexed { index, file -> parts += file.toFilePart(sessionId, id, index) }
    return MessageWithParts(Message(id = id, sessionID = sessionId, role = V2_USER, time = createdTime()), parts)
}

/** A file of a prompt: kept by reference when the server has one, inline otherwise. */
private fun JsonObject.toFilePart(sessionId: String, messageId: String, index: Int): Part {
    val mime = str("mime") ?: "application/octet-stream"
    val source = obj("source")
    val url = source?.str("uri")?.takeIf { source.str("type") == "uri" }
        ?: str("uri")
        ?: str("data")?.let { "data:$mime;base64,$it" }
        ?: ""
    return Part(
        id = V2Ids.userFile(messageId, index),
        sessionID = sessionId,
        messageID = messageId,
        type = "file",
        mime = mime,
        filename = str("name") ?: url.substringAfterLast('/').takeIf { !url.startsWith("data:") },
        url = url,
    )
}

internal fun JsonObject.assistantInfo(sessionId: String, id: String): Message {
    val model = obj("model")
    val agent = str("agent")
    return Message(
        id = id,
        sessionID = sessionId,
        role = V2_ASSISTANT,
        time = createdTime(),
        agent = agent,
        mode = agent,
        modelID = model?.str("id"),
        providerID = model?.str("providerID"),
        cost = double("cost"),
        tokens = obj("tokens"),
        error = obj("error")?.toV1Error(),
        finish = str("finish"),
    )
}

internal fun JsonObject.assistantMessage(sessionId: String, id: String): MessageWithParts {
    val info = assistantInfo(sessionId, id)
    val parts = ArrayList<Part>()
    var texts = 0
    var reasonings = 0
    arr("content")?.objects()?.forEach { content ->
        when (content.str("type")) {
            "text" -> parts += textPart(sessionId, id, V2Ids.text(id, texts++), content.str("text").orEmpty())
            "reasoning" -> parts += textPart(sessionId, id, V2Ids.reasoning(id, reasonings++), content.str("text").orEmpty(), type = "reasoning")
            "tool" -> content.toToolPart(sessionId, id)?.let(parts::add)
        }
    }
    obj("retry")?.let { retry -> parts += retryPart(sessionId, id, retry) }
    if (obj("tokens") != null && (info.finish != null || obj("time")?.long("completed") != null)) {
        parts += finishPart(sessionId, id, info.finish, double("cost"), obj("tokens"))
    }
    return MessageWithParts(info, parts)
}

internal fun textPart(sessionId: String, messageId: String, partId: String, text: String, type: String = "text") =
    Part(id = partId, sessionID = sessionId, messageID = messageId, type = type, text = text)

internal fun retryPart(sessionId: String, messageId: String, retry: JsonObject) = Part(
    id = V2Ids.retry(messageId),
    sessionID = sessionId,
    messageID = messageId,
    type = "retry",
    attempt = retry.long("attempt")?.toInt(),
    error = retry.obj("error")?.toV1Error(),
)

internal fun finishPart(sessionId: String, messageId: String, finish: String?, cost: Double?, tokens: JsonObject?) = Part(
    id = V2Ids.finish(messageId),
    sessionID = sessionId,
    messageID = messageId,
    type = "step-finish",
    reason = finish,
    cost = cost,
    tokens = tokens,
)

internal fun JsonObject.toToolPart(sessionId: String, messageId: String): Part? {
    val callId = str("id") ?: return null
    return Part(
        id = V2Ids.tool(messageId, callId),
        sessionID = sessionId,
        messageID = messageId,
        type = "tool",
        callID = callId,
        tool = str("name").orEmpty(),
        state = obj("state")?.toV1ToolState() ?: ToolState(status = "pending"),
    )
}

/** v2 tool states: `streaming` (input still arriving), `running`, `completed`, `error`. */
internal fun JsonObject.toV1ToolState(): ToolState {
    val metadata = obj("metadata")
    return ToolState(
        status = when (str("status")) {
            "streaming" -> "pending"
            "running" -> "running"
            "completed" -> "completed"
            "error" -> "error"
            else -> "pending"
        },
        input = obj("input"),
        raw = str("input"),
        title = metadata?.str("title"),
        metadata = metadata,
        output = arr("content")?.contentText(),
        error = obj("error")?.str("message"),
    )
}

/** The text a tool returned; files it returned are named. */
internal fun JsonArray.contentText(): String? = objects()
    .mapNotNull { content ->
        when (content.str("type")) {
            "text" -> content.str("text")
            "file" -> "[" + (content.str("name") ?: content.str("uri") ?: "file") + "]"
            else -> null
        }
    }
    .joinToString("\n")
    .takeIf { it.isNotEmpty() }

/**
 * A compaction: a marker the app shows as a divider, and the summary it wrote as
 * the answer to it, so the chat shows it as it does a v1 compaction.
 */
internal fun JsonObject.compactionMessages(sessionId: String, id: String): List<MessageWithParts> {
    val created = createdTime()
    val marker = MessageWithParts(
        info = Message(id = id, sessionID = sessionId, role = V2_USER, time = created),
        parts = listOf(
            Part(id = V2Ids.compaction(id), sessionID = sessionId, messageID = id, type = "compaction", auto = str("reason") == "auto"),
        ),
    )
    val summaryId = V2Ids.summary(id)
    val summary = str("summary").orEmpty()
    val status = str("status")
    val model = obj("model")
    val info = Message(
        id = summaryId,
        sessionID = sessionId,
        role = V2_ASSISTANT,
        time = created,
        parentID = id,
        summary = JsonPrimitive(true),
        mode = "compaction",
        modelID = model?.str("id"),
        providerID = model?.str("providerID"),
        cost = double("cost"),
        tokens = obj("tokens"),
        error = obj("error")?.toV1Error(),
        finish = if (status == "completed") "stop" else null,
    )
    val parts = if (summary.isBlank()) emptyList() else listOf(textPart(sessionId, summaryId, V2Ids.summaryText(id), summary))
    return listOf(marker, MessageWithParts(info, parts))
}

/** A shell command the user ran, shown as the command card a bash tool call gets. */
internal fun JsonObject.shellMessage(sessionId: String, id: String): MessageWithParts {
    val completed = obj("time")?.long("completed") != null || this["exit"] != null
    val exit = long("exit")
    val state = ToolState(
        status = when {
            !completed -> "running"
            exit != null && exit != 0L -> "error"
            else -> "completed"
        },
        input = buildJsonObject { put("command", str("command").orEmpty()) },
        output = obj("output")?.str("output"),
        error = exit?.takeIf { it != 0L }?.let { "exit $it" },
    )
    val part = Part(
        id = V2Ids.shell(id),
        sessionID = sessionId,
        messageID = id,
        type = "tool",
        callID = str("shellID") ?: id,
        tool = "bash",
        state = state,
    )
    return MessageWithParts(Message(id = id, sessionID = sessionId, role = V2_ASSISTANT, time = createdTime()), listOf(part))
}

// --- Catalogue ---

/** Providers with the models they offer, as `GET /config/providers` lists them. */
internal fun v2ConfigProviders(providers: List<JsonObject>, models: List<JsonObject>, default: JsonObject?): ConfigProviders {
    val names = providers.associate { (it.str("id").orEmpty()) to (it.str("name") ?: it.str("id").orEmpty()) }
    val byProvider = models
        .filter { it.bool("enabled") != false }
        .groupBy { it.str("providerID").orEmpty() }
        .filterKeys { it.isNotBlank() }
    val list = byProvider.map { (providerId, entries) ->
        Provider(
            id = providerId,
            name = names[providerId] ?: providerId,
            models = entries.mapNotNull { it.toV1Model() }.associateBy { it.id },
        )
    }.sortedBy { it.name.lowercase() }
    val defaults = buildMap {
        val providerId = default?.str("providerID")
        val modelId = default?.str("id")
        if (providerId != null && modelId != null) put(providerId, modelId)
    }
    return ConfigProviders(providers = list, default = defaults)
}

private fun JsonObject.toV1Model(): Model? {
    val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
    val limit = obj("limit")
    return Model(
        id = id,
        providerID = str("providerID").orEmpty(),
        name = str("name") ?: id,
        family = str("family"),
        status = str("status"),
        variants = arr("variants")?.objects()?.mapNotNull { it.str("id") }?.associateWith { JsonObject(emptyMap()) }.orEmpty(),
        limit = limit?.let { ModelLimit(context = it.long("context") ?: 0, output = it.long("output") ?: 0) },
    )
}

/** The id is what a prompt names the agent by; v2 keeps a display name apart. */
internal fun JsonObject.toV1Agent(): Agent? {
    val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
    return Agent(name = id, description = str("description"), mode = str("mode") ?: "all", hidden = bool("hidden"))
}

internal fun JsonObject.toV1Project(): Project? {
    val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
    val time = obj("time")
    val icon = obj("icon")
    return Project(
        id = id,
        worktree = str("canonical").orEmpty(),
        vcs = str("vcs"),
        name = str("name"),
        icon = icon?.let { ProjectIcon(url = it.str("url"), color = it.str("color")) },
        time = time?.let { SessionTime(created = it.long("created") ?: 0, updated = it.long("updated") ?: 0) },
    )
}

// --- Requests to the user ---

internal fun JsonObject.toV1Permission(): PermissionRequest? {
    val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
    val source = obj("source")
    return PermissionRequest(
        id = id,
        sessionID = str("sessionID").orEmpty(),
        permission = str("action").orEmpty(),
        patterns = arr("resources")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
        metadata = obj("metadata"),
        always = arr("save")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
        tool = source?.let { buildJsonObject { it.str("messageID")?.let { id -> put("messageID", id) }; it.str("id")?.let { id -> put("callID", id) } } },
    )
}

/** The fields of a form the user is asked to fill in, in order; hidden ones are not asked. */
internal fun JsonObject.formFields(): List<JsonObject> = arr("fields")?.objects().orEmpty().filter { it.bool("hidden") != true }

/**
 * A form (how v2 asks the user, the question tool included) as the questions
 * the app shows: one per field, its options as answers to pick.
 */
internal fun JsonObject.toV1Question(): QuestionRequest? {
    val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
    val fields = formFields()
    val formTitle = str("title").orEmpty()
    val questions = fields.map { field ->
        val options = field.arr("options")?.objects()?.map { option ->
            QuestionOption(label = option.str("label") ?: option.str("value").orEmpty(), description = option.str("description").orEmpty())
        } ?: if (field.str("type") == "boolean") listOf(QuestionOption("true"), QuestionOption("false")) else emptyList()
        QuestionInfo(
            question = field.str("description") ?: field.str("title") ?: formTitle,
            header = field.str("title") ?: formTitle.takeIf { fields.size == 1 }.orEmpty(),
            options = options,
            multiple = field.str("type") == "multiselect",
            custom = field.bool("custom") ?: options.isEmpty(),
        )
    }
    if (questions.isEmpty()) return null
    return QuestionRequest(id = id, sessionID = str("sessionID").orEmpty(), questions = questions)
}

/**
 * The answers the app collected (one list per question, in the order of
 * [toV1Question]) as a form reply: values typed as each field expects, by key.
 * Option labels are sent as the option's value.
 */
internal fun JsonObject.formAnswer(answers: List<List<String>>): JsonObject = buildJsonObject {
    formFields().forEachIndexed { index, field ->
        val key = field.str("key") ?: return@forEachIndexed
        val picked = answers.getOrNull(index).orEmpty().map { answer ->
            field.arr("options")?.objects()?.firstOrNull { it.str("label") == answer }?.str("value") ?: answer
        }
        if (picked.isEmpty()) return@forEachIndexed
        val value: JsonElement = when (field.str("type")) {
            "multiselect" -> JsonArray(picked.map(::JsonPrimitive))
            "number", "integer" -> picked.first().toDoubleOrNull()?.let { number ->
                if (field.str("type") == "integer") JsonPrimitive(number.toLong()) else JsonPrimitive(number)
            } ?: JsonPrimitive(picked.first())
            "boolean" -> JsonPrimitive(picked.first().equals("true", ignoreCase = true))
            else -> JsonPrimitive(picked.first())
        }
        put(key, value)
    }
}

// --- Files, VCS, MCP ---

/** An entry of `/api/fs/list` or `/api/fs/find`: [root] is the directory its path is relative to. */
internal fun JsonObject.toFileNode(root: String?): FileNode? {
    val raw = str("path") ?: return null
    val directory = str("type") == "directory"
    val path = raw.trimEnd('/')
    if (path.isEmpty()) return null
    val absolute = root?.let { if (it.endsWith("/")) it + path else "$it/$path" }.orEmpty()
    return FileNode(name = path.substringAfterLast('/'), path = path, absolute = absolute, type = if (directory) "directory" else "file")
}

internal fun JsonObject.toVcsInfo(): VcsInfo {
    val branch = obj("branch")
    return VcsInfo(branch = branch?.str("current"), defaultBranch = branch?.str("default"))
}

internal fun JsonObject.toVcsStatus(): VcsFileStatus = VcsFileStatus(
    file = str("file").orEmpty(),
    additions = long("additions")?.toInt() ?: 0,
    deletions = long("deletions")?.toInt() ?: 0,
    status = str("status") ?: "modified",
)

internal fun JsonObject.toVcsDiff(): VcsFileDiff = VcsFileDiff(
    file = str("file").orEmpty(),
    patch = str("patch"),
    additions = long("additions")?.toInt() ?: 0,
    deletions = long("deletions")?.toInt() ?: 0,
    status = str("status"),
)

internal fun JsonObject.toMcpStatus(): McpStatus {
    val status = obj("status")
    return McpStatus(status = status?.str("status") ?: str("status").orEmpty(), error = status?.str("error"))
}

/** The list a `todowrite` call set, from its input. */
internal fun JsonObject.todosOf(): List<Todo>? = arr("todos")?.objects()?.map { todo ->
    Todo(content = todo.str("content").orEmpty(), status = todo.str("status") ?: "pending", priority = todo.str("priority") ?: "medium")
}
