package ai.opencode.mobile.data.remote.v2

import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.ConfigProviders
import ai.opencode.mobile.data.remote.CreateSessionRequest
import ai.opencode.mobile.data.remote.FileContent
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.Health
import ai.opencode.mobile.data.remote.McpStatus
import ai.opencode.mobile.data.remote.MessageWithParts
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.OpenCodeException
import ai.opencode.mobile.data.remote.OpenCodeJson
import ai.opencode.mobile.data.remote.PathInfo
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.Project
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.PromptRequest
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionStatusInfo
import ai.opencode.mobile.data.remote.SessionSummary
import ai.opencode.mobile.data.remote.Todo
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.data.remote.VcsInfo
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The client's calls against an opencode 2.x server (the v2 API under `/api`),
 * answering in the v1 shapes the app is built on. See [V2Mapping] for how the
 * data maps and [V2EventTranslator] for the event stream.
 *
 * The project is a query parameter of each call (`location[directory]`);
 * sessions carry their own and need none.
 */
class V2Backend internal constructor(private val http: OpenCodeClient) {
    private val state = V2State()

    /** One translator per stream: it keeps what the events of that stream built. */
    internal fun eventTranslator(): V2EventTranslator =
        V2EventTranslator(http.directory, state) { id -> runCatching { getSession(id) }.getOrNull() }

    // --- Requests ---

    private fun location(root: String? = http.directory): Map<String, String?> = mapOf(LOCATION_QUERY to root)

    private suspend fun get(
        path: String,
        query: Map<String, String?> = emptyMap(),
        maxBytes: Long = OpenCodeClient.MAX_BODY_BYTES,
    ): JsonElement = http.execute(http.newRequest("GET", path, query), JsonElement.serializer(), maxBytes)

    /** A call answered with `{data}` (or with nothing): its data, null when empty. */
    private suspend fun send(method: String, path: String, body: JsonObject? = null, query: Map<String, String?> = emptyMap()): JsonElement? {
        val request = http.newRequest(method, path, query, body = body?.let(http::jsonBody))
        val response = http.fetch(request)
        if (!response.isSuccessful) throw OpenCodeException(response.code, http.extractError(response.text, response.message))
        if (response.text.isBlank()) return null
        return runCatching { OpenCodeJson.parseToJsonElement(response.text) }.getOrNull()?.data()
    }

    private fun JsonElement.data(): JsonElement = (this as? JsonObject)?.get("data") ?: this

    private fun JsonElement.items(): List<JsonObject> = (data() as? JsonArray)?.objects().orEmpty()

    /** Every page of a list answered with `{data, cursor: {next}}`, up to [maxPages]. */
    private suspend fun pages(path: String, query: Map<String, String?>, maxPages: Int, maxBytes: Long = OpenCodeClient.MAX_BODY_BYTES): List<JsonObject> {
        val result = ArrayList<JsonObject>()
        var cursor: String? = null
        repeat(maxPages) {
            // A cursor carries the order and filters of its list: the server refuses them again next to it.
            val pageQuery = cursor?.let { mapOf("limit" to query["limit"], "cursor" to it) } ?: query
            val page = get(path, pageQuery, maxBytes) as? JsonObject ?: return result
            val items = page.arr("data")?.objects().orEmpty()
            result += items
            cursor = page.obj("cursor")?.str("next")
            if (items.isEmpty() || cursor == null) return result
        }
        return result
    }

    // --- Server ---

    suspend fun health(): Health {
        val info = get("/api/info") as? JsonObject
        return Health(healthy = true, version = info?.str("version"))
    }

    suspend fun pathInfo(): PathInfo {
        val info = get("/api/location", location()) as? JsonObject
        val directory = info?.str("directory").orEmpty()
        return PathInfo(home = homeOf(directory), directory = directory, worktree = info?.obj("project")?.str("canonical").orEmpty())
    }

    // --- Projects and sessions ---

    suspend fun listProjects(): List<Project> = get("/api/project").items().mapNotNull { it.toV1Project() }

    suspend fun listAllSessions(): List<SessionSummary> =
        pages("/api/session", mapOf("limit" to "$PAGE_LIMIT", "order" to "desc"), maxPages = ALL_SESSIONS_PAGES)
            .filter { it.obj("time")?.get("archived") == null }
            .mapNotNull { it.decodeOrNull(SessionSummary.serializer()) }

    suspend fun listSessions(): List<Session> =
        pages("/api/session", mapOf("limit" to "$PAGE_LIMIT", "order" to "desc", "directory" to http.directory), maxPages = SESSIONS_PAGES)
            .filter { it.obj("time")?.get("archived") == null }
            .mapNotNull { it.toV1Session() }
            .onEach { state.sessions[it.id] = it }

    suspend fun getSession(id: String): Session {
        val session = (get("/api/session/$id").data() as? JsonObject)?.toV1Session()
            ?: throw OpenCodeException(404, "Session not found: $id")
        state.sessions[id] = session
        return session
    }

    suspend fun createSession(request: CreateSessionRequest): Session {
        val body = buildJsonObject {
            http.directory?.let { putJsonObject("location") { put("directory", it) } }
            request.title?.let { put("title", it) }
            request.agent?.let { put("agent", it) }
            request.model?.let { model ->
                putJsonObject("model") {
                    put("id", model.id)
                    put("providerID", model.providerID)
                    model.variant?.let { put("variant", it) }
                }
            }
        }
        val session = (send("POST", "/api/session", body) as? JsonObject)?.toV1Session()
            ?: throw OpenCodeException(-1, "Empty response body for /api/session")
        state.sessions[session.id] = session
        return session
    }

    suspend fun deleteSession(id: String) {
        send("DELETE", "/api/session/$id")
        state.sessions.remove(id)
    }

    // --- Messages ---

    /**
     * The history, oldest first: all of it, page by page, or with [limit] only
     * that many of the newest items (fewer messages: markers are not shown).
     */
    suspend fun getMessages(sessionId: String, limit: Int?): List<MessageWithParts> =
        v2MessagesToV1(sessionId, if (limit != null) recentItems(sessionId, limit) else allItems(sessionId))

    /** The newest [limit] items and whether they are the whole history. */
    suspend fun getRecentMessages(sessionId: String, limit: Int): Pair<List<MessageWithParts>, Boolean> {
        val items = recentItems(sessionId, limit)
        return v2MessagesToV1(sessionId, items) to (items.size < limit.coerceAtMost(PAGE_LIMIT))
    }

    private suspend fun recentItems(sessionId: String, limit: Int): List<JsonObject> =
        get(
            "/api/session/$sessionId/message",
            mapOf("order" to "desc", "limit" to "${limit.coerceIn(1, PAGE_LIMIT)}"),
            OpenCodeClient.MAX_HISTORY_BYTES,
        ).items().asReversed()

    private suspend fun allItems(sessionId: String): List<JsonObject> =
        pages(
            "/api/session/$sessionId/message",
            mapOf("order" to "asc", "limit" to "$PAGE_LIMIT"),
            maxPages = Int.MAX_VALUE,
            maxBytes = OpenCodeClient.MAX_HISTORY_BYTES,
        )

    /**
     * Sends a prompt. v2 keeps the model and agent on the session, not on the
     * prompt, so they are switched first when they differ; a rollback in
     * place is committed first, as the web client does.
     */
    suspend fun prompt(sessionId: String, request: PromptRequest) {
        prepare(sessionId, request.model, request.variant, request.agent, commitRevert = true)
        val text = request.parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
        val files = request.parts.filter { it.type == "file" && !it.url.isNullOrBlank() }
        val body = buildJsonObject {
            put("text", text)
            if (files.isNotEmpty()) {
                put(
                    "files",
                    buildJsonArray {
                        files.forEach { file ->
                            add(
                                buildJsonObject {
                                    put("uri", file.url.orEmpty())
                                    file.filename?.let { put("name", it) }
                                },
                            )
                        }
                    },
                )
            }
        }
        send("POST", "/api/session/$sessionId/prompt", body)
    }

    /** Compacts the session with [model]; the server takes it and returns at once. */
    suspend fun compact(sessionId: String, model: PromptModel) {
        prepare(sessionId, model, variant = null, agent = null, commitRevert = false)
        send("POST", "/api/session/$sessionId/compact", JsonObject(emptyMap()))
    }

    private suspend fun prepare(sessionId: String, model: PromptModel?, variant: String?, agent: String?, commitRevert: Boolean) {
        val session = getSession(sessionId)
        if (commitRevert && session.revert != null) send("POST", "/api/session/$sessionId/revert/commit")
        if (model != null) {
            val current = session.model
            val changed = current == null || current.id != model.modelID || current.providerID != model.providerID ||
                (variant != null && current.variant != variant)
            if (changed) {
                val body = buildJsonObject {
                    putJsonObject("model") {
                        put("id", model.modelID)
                        put("providerID", model.providerID)
                        variant?.let { put("variant", it) }
                    }
                }
                send("POST", "/api/session/$sessionId/model", body)
            }
        }
        if (agent != null && agent != session.agent) {
            send("POST", "/api/session/$sessionId/agent", buildJsonObject { put("agent", agent) })
        }
    }

    suspend fun sessionStatus(): Map<String, SessionStatusInfo> {
        val active = get("/api/session/active").data() as? JsonObject ?: return emptyMap()
        return active.keys.associateWith { SessionStatusInfo(type = "busy") }
    }

    suspend fun abort(sessionId: String) {
        send("POST", "/api/session/$sessionId/interrupt")
    }

    suspend fun revert(sessionId: String, messageId: String): Session {
        send("POST", "/api/session/$sessionId/revert/stage", buildJsonObject { put("messageID", messageId) })
        return getSession(sessionId)
    }

    suspend fun unrevert(sessionId: String): Session {
        send("DELETE", "/api/session/$sessionId/revert")
        return getSession(sessionId)
    }

    /** The list a `todowrite` call last set in the session; empty when none did. */
    suspend fun todos(sessionId: String): List<Todo> {
        val items = recentItems(sessionId, TODO_SCAN_ITEMS)
        for (item in items.asReversed()) {
            if (item.str("type") != V2_ASSISTANT) continue
            val tool = item.arr("content")?.objects()?.lastOrNull { it.str("type") == "tool" && it.str("name") == "todowrite" }
            tool?.obj("state")?.obj("input")?.todosOf()?.let { return it }
        }
        return emptyList()
    }

    // --- Requests to the user ---

    suspend fun listPermissions(): List<PermissionRequest> =
        get("/api/permission/request", location()).items().mapNotNull { it.toV1Permission() }

    suspend fun replyPermission(requestId: String, reply: String, message: String?, sessionId: String?) {
        val session = sessionId ?: listPermissions().firstOrNull { it.id == requestId }?.sessionID
            ?: throw OpenCodeException(404, "Permission request not found: $requestId")
        val body = buildJsonObject {
            put("decision", reply)
            message?.let { put("message", it) }
        }
        send("POST", "/api/session/$session/permission/$requestId/reply", body)
    }

    suspend fun listQuestions(): List<QuestionRequest> =
        get("/api/form", location()).items().mapNotNull { form ->
            form.toV1Question()?.also { state.forms[it.id] = form }
        }

    suspend fun replyQuestion(requestId: String, answers: List<List<String>>, sessionId: String?) {
        val form = formOf(requestId, sessionId)
        val session = sessionId ?: form.str("sessionID") ?: throw OpenCodeException(404, "Form not found: $requestId")
        send("POST", "/api/session/$session/form/$requestId/reply", buildJsonObject { put("answer", form.formAnswer(answers)) })
        state.forms.remove(requestId)
    }

    suspend fun rejectQuestion(requestId: String, sessionId: String?) {
        val session = sessionId ?: formOf(requestId, null).str("sessionID") ?: throw OpenCodeException(404, "Form not found: $requestId")
        send("DELETE", "/api/session/$session/form/$requestId")
        state.forms.remove(requestId)
    }

    private suspend fun formOf(id: String, sessionId: String?): JsonObject {
        state.forms[id]?.let { return it }
        if (sessionId != null) {
            (get("/api/session/$sessionId/form/$id").data() as? JsonObject)?.let { return it }
        }
        listQuestions()
        return state.forms[id] ?: throw OpenCodeException(404, "Form not found: $id")
    }

    // --- Catalogue ---

    suspend fun configProviders(): ConfigProviders = coroutineScope {
        val providers = async { get("/api/provider", location()).items() }
        val models = async { get("/api/model", location()).items() }
        val default = async { runCatching { get("/api/model/default", location()).data() as? JsonObject }.getOrNull() }
        v2ConfigProviders(providers.await(), models.await(), default.await())
    }

    suspend fun listAgents(): List<Agent> = get("/api/agent", location()).items().mapNotNull { it.toV1Agent() }

    // --- Files ---

    /** [path] under [root], or under the open project when null. */
    suspend fun listFiles(path: String, root: String?): List<FileNode> {
        val base = root ?: http.directory
        val relative = path.trim('/').takeIf { it.isNotEmpty() && it != "." }
        return get("/api/fs/list", location(base) + ("path" to relative)).items().mapNotNull { it.toFileNode(base) }
    }

    suspend fun findDirectories(query: String, root: String): List<String> =
        get("/api/fs/find", location(root) + mapOf("query" to query, "type" to "directory", "limit" to "$FIND_LIMIT"))
            .items()
            .mapNotNull { it.str("path")?.takeIf { path -> path.isNotBlank() && path != "/" } }

    suspend fun findFiles(query: String): List<String> =
        get("/api/fs/find", location() + mapOf("query" to query, "type" to "file", "limit" to "$FIND_LIMIT"))
            .items()
            .mapNotNull { it.str("path") }

    /** A file of the project: text when it reads as UTF-8 text, otherwise its bytes in base64. */
    suspend fun readFile(path: String): FileContent {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { OpenCodeClient.pathSegment(it) }
        val response = http.fetch(
            http.newRequest("GET", "/api/fs/read/$segments", location()),
            maxBodyBytes = OpenCodeClient.MAX_FILE_BYTES,
            raw = true,
        )
        if (!response.isSuccessful) throw OpenCodeException(response.code, response.message.ifBlank { "HTTP ${response.code}" })
        val bytes = response.bytes ?: ByteArray(0)
        val mime = response.contentType?.substringBefore(';')?.trim()
        val text = textOf(bytes)
        return if (text != null) {
            FileContent(type = "text", content = text, mimeType = mime)
        } else {
            FileContent(type = "binary", content = Base64.getEncoder().encodeToString(bytes), encoding = "base64", mimeType = mime)
        }
    }

    // --- VCS ---

    suspend fun vcsInfo(): VcsInfo = (get("/api/vcs", location()).data() as? JsonObject)?.toVcsInfo() ?: VcsInfo()

    suspend fun vcsStatus(): List<VcsFileStatus> = get("/api/vcs/status", location()).items().map { it.toVcsStatus() }

    /** v1's `git` mode (the working tree against HEAD) is v2's `working`. */
    suspend fun vcsDiff(mode: String): List<VcsFileDiff> =
        get(
            "/api/vcs/diff",
            location() + ("mode" to if (mode == "git") "working" else mode),
            OpenCodeClient.MAX_DIFF_BYTES,
        ).items().map { it.toVcsDiff() }

    suspend fun sessionDiff(sessionId: String): List<VcsFileDiff> =
        get("/api/session/$sessionId/diff", maxBytes = OpenCodeClient.MAX_DIFF_BYTES).items().map { it.toVcsDiff() }

    // --- MCP ---

    suspend fun mcpStatus(): Map<String, McpStatus> =
        get("/api/mcp", location()).items().associate { (it.str("name").orEmpty()) to it.toMcpStatus() }.filterKeys { it.isNotBlank() }

    suspend fun mcpConnect(name: String, connect: Boolean): Boolean {
        val action = if (connect) "connect" else "disconnect"
        send("POST", "/api/experimental/mcp/${OpenCodeClient.pathSegment(name)}/$action", query = location())
        return true
    }

    companion object {
        private const val LOCATION_QUERY = "location[directory]"

        /** The most a v2 list answers at once. */
        private const val PAGE_LIMIT = 200
        private const val ALL_SESSIONS_PAGES = 25
        private const val SESSIONS_PAGES = 10
        private const val FIND_LIMIT = 50
        private const val TODO_SCAN_ITEMS = 30

        /** A home directory guessed from a path in it: v2 does not say which it is. */
        internal fun homeOf(directory: String): String {
            val patterns = listOf(Regex("^/home/[^/]+"), Regex("^/Users/[^/]+"), Regex("^/root(?=/|$)"), Regex("^[A-Za-z]:\\\\Users\\\\[^\\\\]+"))
            patterns.forEach { pattern -> pattern.find(directory)?.let { return it.value } }
            return directory
        }

        /** [bytes] as text when they are UTF-8 without NUL bytes, as text files are. */
        internal fun textOf(bytes: ByteArray): String? {
            if (bytes.any { it == 0.toByte() }) return null
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                null
            }
        }
    }
}
