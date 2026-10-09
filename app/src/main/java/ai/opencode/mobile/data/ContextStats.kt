package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.Model
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.Session
import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class ContextCategory { SYSTEM, USER, ASSISTANT, TOOL, OTHER }

@Immutable
data class ContextSegment(
    val category: ContextCategory,
    val tokens: Long,
    /** Share of the input tokens, 0–100. */
    val percent: Double,
)

/** Token counts of one model reply, as opencode reports them. */
@Immutable
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    val total: Long get() = input + output + reasoning + cacheRead + cacheWrite
}

/**
 * What the web client's context panel shows for a session. Token figures are
 * those of the latest reply that reported any: that is what the model was last
 * sent, i.e. how full its context window is.
 */
@Immutable
data class ContextStats(
    val provider: String? = null,
    val model: String? = null,
    val limit: Long? = null,
    val usage: TokenUsage? = null,
    /** total / limit in percent; null without a known limit or usage. */
    val usagePercent: Int? = null,
    val messages: Int = 0,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val totalCost: Double = 0.0,
    val created: Long? = null,
    val lastActivity: Long? = null,
    val breakdown: List<ContextSegment> = emptyList(),
    val systemPrompt: String? = null,
)

private const val ASSISTANT_ROLE = "assistant"

/** Rough characters per token, as the web client estimates. */
private const val CHARS_PER_TOKEN = 4.0

/** Weight of each tool input argument in characters, as the web client counts it. */
private const val TOOL_INPUT_KEY_CHARS = 16

internal fun JsonObject.number(key: String): Long =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0

internal fun Message.tokenUsage(): TokenUsage? {
    val tokens = tokens ?: return null
    val cache = tokens["cache"] as? JsonObject
    return TokenUsage(
        input = tokens.number("input"),
        output = tokens.number("output"),
        reasoning = tokens.number("reasoning"),
        cacheRead = cache?.number("read") ?: 0,
        cacheWrite = cache?.number("write") ?: 0,
    )
}

/** The latest model reply that reported token usage. */
private fun List<ChatMessageUi>.lastReplyWithUsage(): Pair<Message, TokenUsage>? {
    for (index in indices.reversed()) {
        val info = this[index].info
        if (info.role != ASSISTANT_ROLE) continue
        val usage = info.tokenUsage() ?: continue
        if (usage.total > 0) return info to usage
    }
    return null
}

private fun List<Provider>.findModel(providerId: String?, modelId: String?): Pair<Provider?, Model?> {
    val provider = firstOrNull { it.id == providerId } ?: return null to null
    val model = provider.models[modelId] ?: provider.models.values.firstOrNull { it.id == modelId }
    return provider to model
}

/** Context window use of the latest reply in percent, or null when unknown. Cheap enough per update. */
internal fun contextUsagePercent(messages: List<ChatMessageUi>, providers: List<Provider>): Int? {
    val (reply, usage) = messages.lastReplyWithUsage() ?: return null
    val limit = providers.findModel(reply.providerID, reply.modelID).second?.limit?.context?.takeIf { it > 0 } ?: return null
    return (usage.total * 100.0 / limit).roundToInt()
}

/** Computes [ContextStats] for [messages] (the visible ones: a rollback hides the rest). */
internal fun contextStats(
    messages: List<ChatMessageUi>,
    session: Session?,
    providers: List<Provider>,
): ContextStats {
    val reply = messages.lastReplyWithUsage()
    val (provider, model) = providers.findModel(reply?.first?.providerID, reply?.first?.modelID)
    val limit = model?.limit?.context?.takeIf { it > 0 }
    val usage = reply?.second
    val systemPrompt = messages
        .lastOrNull { it.info.role == USER_ROLE && !it.info.system.isNullOrBlank() }
        ?.info?.system?.trim()
    val assistantCost = messages.sumOf { if (it.info.role == ASSISTANT_ROLE) it.info.cost ?: 0.0 else 0.0 }
    return ContextStats(
        provider = provider?.name?.takeIf { it.isNotBlank() } ?: reply?.first?.providerID,
        model = model?.name?.takeIf { it.isNotBlank() } ?: reply?.first?.modelID,
        limit = limit,
        usage = usage,
        usagePercent = if (usage != null && limit != null) (usage.total * 100.0 / limit).roundToInt() else null,
        messages = messages.size,
        userMessages = messages.count { it.info.role == USER_ROLE },
        assistantMessages = messages.count { it.info.role == ASSISTANT_ROLE },
        totalCost = session?.cost?.takeIf { it > 0 } ?: assistantCost,
        created = session?.time?.created?.takeIf { it > 0 } ?: messages.firstOrNull()?.info?.time?.created,
        lastActivity = messages.mapNotNull { it.info.time?.created?.takeIf { time -> time > 0 } }.maxOrNull()
            ?: session?.time?.updated,
        breakdown = contextBreakdown(messages, systemPrompt, usage?.input ?: 0),
        systemPrompt = systemPrompt,
    )
}

/**
 * Splits the [input] tokens of the latest reply by origin. opencode does not
 * report this, so it is estimated from the text like the web client does:
 * characters / 4 per category, scaled down when the estimate exceeds the real
 * count, the remainder (tool definitions, formatting) shown as "other".
 */
internal fun contextBreakdown(messages: List<ChatMessageUi>, systemPrompt: String?, input: Long): List<ContextSegment> {
    if (input <= 0) return emptyList()
    var user = 0L
    var assistant = 0L
    var tool = 0L
    messages.forEach { message ->
        when (message.info.role) {
            USER_ROLE -> message.parts.forEach { user += it.userChars() }
            ASSISTANT_ROLE -> message.parts.forEach { part ->
                when (part.type) {
                    TEXT_PART_TYPE, "reasoning" -> assistant += part.text?.length ?: 0
                    "tool" -> tool += part.toolChars()
                }
            }
        }
    }
    val estimated = linkedMapOf(
        ContextCategory.SYSTEM to tokensOf(systemPrompt?.length?.toLong() ?: 0),
        ContextCategory.USER to tokensOf(user),
        ContextCategory.ASSISTANT to tokensOf(assistant),
        ContextCategory.TOOL to tokensOf(tool),
    )
    val sum = estimated.values.sum()
    val scaled = if (sum <= input) {
        estimated
    } else {
        val ratio = input.toDouble() / sum
        estimated.mapValuesTo(LinkedHashMap()) { (_, tokens) -> floor(tokens * ratio).toLong() }
    }
    val all = LinkedHashMap(scaled).apply { put(ContextCategory.OTHER, input - scaled.values.sum()) }
    return all
        .filter { (_, tokens) -> tokens > 0 }
        .map { (category, tokens) ->
            ContextSegment(category, tokens, (tokens * 1000.0 / input).roundToInt() / 10.0)
        }
}

private fun tokensOf(chars: Long): Long = ceil(chars / CHARS_PER_TOKEN).toLong()

private fun Part.userChars(): Long = when (type) {
    TEXT_PART_TYPE -> text?.length?.toLong() ?: 0
    FILE_PART_TYPE -> ((source?.get("text") as? JsonObject)?.get("value") as? JsonPrimitive)?.content?.length?.toLong() ?: 0
    "agent" -> (source?.get("value") as? JsonPrimitive)?.content?.length?.toLong() ?: 0
    else -> 0
}

private fun Part.toolChars(): Long {
    val state = state ?: return 0
    val inputChars = (state.input?.size ?: 0).toLong() * TOOL_INPUT_KEY_CHARS
    val body = when (state.status) {
        "pending" -> state.raw
        "completed" -> state.output
        "error" -> state.error
        else -> null
    }
    return inputChars + (body?.length ?: 0)
}
