package ai.opencode.mobile.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Data transfer objects mirroring the opencode server OpenAPI schema (v1 API).
 * Unknown fields are ignored by the Json configuration, and rarely used nested
 * objects are kept as JsonObject to stay resilient across server versions.
 */

@Serializable
data class Session(
    val id: String = "",
    val slug: String = "",
    @SerialName("projectID") val projectID: String = "",
    @SerialName("workspaceID") val workspaceID: String? = null,
    val directory: String = "",
    val path: String? = null,
    @SerialName("parentID") val parentID: String? = null,
    val title: String = "",
    val agent: String? = null,
    val model: SessionModel? = null,
    val cost: Double? = null,
    val tokens: JsonObject? = null,
    val version: String = "",
    val time: SessionTime? = null,
    val revert: JsonObject? = null,
)

@Serializable
data class SessionTime(
    val created: Long = 0,
    val updated: Long = 0,
    val compacting: Long? = null,
    val archived: Double? = null,
)

@Serializable
data class SessionModel(
    val id: String = "",
    @SerialName("providerID") val providerID: String = "",
    val variant: String? = null,
)

@Serializable
data class MessageModel(
    @SerialName("modelID") val modelID: String? = null,
    @SerialName("providerID") val providerID: String? = null,
)

@Serializable
data class Message(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val role: String = "",
    val time: SessionTime? = null,
    val agent: String? = null,
    val model: MessageModel? = null,
    val system: String? = null,
    @SerialName("parentID") val parentID: String? = null,
    @SerialName("modelID") val modelID: String? = null,
    @SerialName("providerID") val providerID: String? = null,
    val mode: String? = null,
    val path: JsonObject? = null,
    val summary: JsonElement? = null,
    val cost: Double? = null,
    val tokens: JsonObject? = null,
    val error: JsonObject? = null,
    val finish: String? = null,
)

@Serializable
data class Part(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    @SerialName("messageID") val messageID: String = "",
    val type: String = "",
    // text / reasoning
    val text: String? = null,
    val synthetic: Boolean? = null,
    val ignored: Boolean? = null,
    // tool
    @SerialName("callID") val callID: String? = null,
    val tool: String? = null,
    val state: ToolState? = null,
    // file
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
    val source: JsonObject? = null,
    // patch
    val hash: String? = null,
    val files: List<String>? = null,
    // snapshot / step-start
    val snapshot: String? = null,
    // step-finish
    val reason: String? = null,
    val cost: Double? = null,
    val tokens: JsonObject? = null,
    // retry
    val attempt: Int? = null,
    val error: JsonObject? = null,
    // compaction
    val auto: Boolean? = null,
    val overflow: Boolean? = null,
    @SerialName("tail_start_id") val tailStartId: String? = null,
    // subtask
    val prompt: String? = null,
    val description: String? = null,
    val model: JsonObject? = null,
    val command: String? = null,
    // agent
    val name: String? = null,
    val metadata: JsonObject? = null,
    val time: JsonObject? = null,
)

@Serializable
data class ToolState(
    val status: String = "",
    val input: JsonObject? = null,
    val raw: String? = null,
    val title: String? = null,
    val metadata: JsonObject? = null,
    val output: String? = null,
    val error: String? = null,
    val time: JsonObject? = null,
    val attachments: List<Part>? = null,
)

@Serializable
data class MessageWithParts(
    val info: Message = Message(),
    val parts: List<Part> = emptyList(),
)

// --- Requests ---

@Serializable
data class PromptModel(
    @SerialName("providerID") val providerID: String,
    @SerialName("modelID") val modelID: String,
)

@Serializable
data class TextPartInput(
    val type: String,
    val text: String,
)

@Serializable
data class PromptRequest(
    @SerialName("messageID") val messageID: String? = null,
    val model: PromptModel? = null,
    val agent: String? = null,
    @SerialName("noReply") val noReply: Boolean? = null,
    val system: String? = null,
    val parts: List<TextPartInput> = emptyList(),
)

@Serializable
data class PromptResponse(
    val info: Message = Message(),
    val parts: List<Part> = emptyList(),
)

@Serializable
data class CreateSessionRequest(
    val title: String? = null,
    val agent: String? = null,
    val model: SessionModel? = null,
)

@Serializable
data class PermissionRequest(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val permission: String = "",
    val patterns: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val always: List<String> = emptyList(),
    val tool: JsonObject? = null,
)

@Serializable
data class PermissionReplyRequest(
    val reply: String,
    val message: String? = null,
)

@Serializable
data class QuestionOption(
    val label: String = "",
    val description: String = "",
)

@Serializable
data class QuestionInfo(
    val question: String = "",
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multiple: Boolean? = null,
    val custom: Boolean? = null,
)

@Serializable
data class QuestionRequest(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val questions: List<QuestionInfo> = emptyList(),
    val tool: JsonObject? = null,
)

@Serializable
data class QuestionReplyRequest(
    val answers: List<List<String>> = emptyList(),
)

@Serializable
data class Provider(
    val id: String = "",
    val name: String = "",
    val source: String = "",
    val env: List<String> = emptyList(),
    val models: Map<String, Model> = emptyMap(),
)

@Serializable
data class Model(
    val id: String = "",
    @SerialName("providerID") val providerID: String = "",
    val name: String = "",
    val family: String? = null,
    val status: String? = null,
    val release_date: String? = null,
)

@Serializable
data class ProviderList(
    val all: List<Provider> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class Agent(
    val name: String = "",
    val description: String? = null,
    val mode: String = "all",
    val hidden: Boolean? = null,
)

@Serializable
data class FileNode(
    val name: String = "",
    val path: String = "",
    val absolute: String = "",
    val type: String = "file",
    val ignored: Boolean = false,
)

@Serializable
data class FileContent(
    val type: String = "text",
    val content: String = "",
    val diff: String? = null,
    val encoding: String? = null,
    @SerialName("mimeType") val mimeType: String? = null,
)

@Serializable
data class VcsInfo(
    val branch: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
)

@Serializable
data class VcsFileStatus(
    val file: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "modified",
)

@Serializable
data class VcsFileDiff(
    val file: String = "",
    val patch: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null,
)

@Serializable
data class Todo(
    val content: String = "",
    val status: String = "pending",
    val priority: String = "medium",
)

@Serializable
data class Health(
    val healthy: Boolean? = null,
    val version: String? = null,
)

@Serializable
data class EventEnvelope(
    val id: String? = null,
    val type: String? = null,
    val properties: JsonObject? = null,
)
