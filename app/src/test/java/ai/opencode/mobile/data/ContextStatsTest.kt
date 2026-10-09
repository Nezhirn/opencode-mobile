package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.Model
import ai.opencode.mobile.data.remote.ModelLimit
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextStatsTest {

    private fun tokens(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private val providers = listOf(
        Provider(
            id = "openrouter",
            name = "OpenRouter",
            models = mapOf("m" to Model(id = "m", providerID = "openrouter", name = "Model M", limit = ModelLimit(context = 1000, output = 100))),
        ),
    )

    private fun reply(id: String, json: String?, cost: Double = 0.0, created: Long = 0) = ChatMessageUi(
        info = Message(
            id = id,
            role = "assistant",
            providerID = "openrouter",
            modelID = "m",
            tokens = json?.let(::tokens),
            cost = cost,
            time = SessionTime(created = created),
        ),
        parts = listOf(Part(id = "$id-p", type = "text", text = "x".repeat(400))),
    )

    private fun prompt(id: String, text: String, system: String? = null) = ChatMessageUi(
        info = Message(id = id, role = "user", system = system),
        parts = listOf(Part(id = "$id-p", type = "text", text = text)),
    )

    @Test
    fun usageComesFromTheLatestReplyWithTokens() {
        val messages = listOf(
            prompt("u1", "hi"),
            reply("a1", """{"input":100,"output":10,"reasoning":0,"cache":{"read":0,"write":0}}""", cost = 0.5),
            prompt("u2", "more"),
            reply("a2", """{"input":300,"output":50,"reasoning":25,"cache":{"read":100,"write":25}}""", cost = 0.25, created = 9),
            reply("a3", """{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}"""),
        )

        val stats = contextStats(messages, session = null, providers = providers)

        assertEquals(500L, stats.usage?.total)
        assertEquals(1000L, stats.limit)
        assertEquals(50, stats.usagePercent)
        assertEquals("OpenRouter", stats.provider)
        assertEquals("Model M", stats.model)
        assertEquals(5, stats.messages)
        assertEquals(2, stats.userMessages)
        assertEquals(3, stats.assistantMessages)
        assertEquals(0.75, stats.totalCost, 1e-9)
        assertEquals(9L, stats.lastActivity)
        assertEquals(50, contextUsagePercent(messages, providers))
    }

    @Test
    fun sessionCostAndDatesWin() {
        val session = Session(id = "s", cost = 2.0, time = SessionTime(created = 3, updated = 4))

        val stats = contextStats(listOf(prompt("u", "hi")), session, providers)

        assertEquals(2.0, stats.totalCost, 1e-9)
        assertEquals(3L, stats.created)
        assertNull(stats.usage)
        assertNull(stats.usagePercent)
        assertTrue(stats.breakdown.isEmpty())
    }

    @Test
    fun unknownModelLeavesLimitOpen() {
        val stats = contextStats(
            listOf(reply("a", """{"input":10,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}""")),
            session = null,
            providers = emptyList(),
        )

        assertNull(stats.limit)
        assertNull(stats.usagePercent)
        assertEquals("openrouter", stats.provider)
    }

    @Test
    fun breakdownEstimatesByCharactersAndKeepsTheRestAsOther() {
        // system 40 chars = 10 tokens, user 80 = 20, assistant 400 = 100; input 1000.
        val messages = listOf(prompt("u", "y".repeat(80)), reply("a", null))

        val breakdown = contextBreakdown(messages, systemPrompt = "s".repeat(40), input = 1000)

        assertEquals(
            listOf(
                ContextCategory.SYSTEM to 10L,
                ContextCategory.USER to 20L,
                ContextCategory.ASSISTANT to 100L,
                ContextCategory.OTHER to 870L,
            ),
            breakdown.map { it.category to it.tokens },
        )
        assertEquals(87.0, breakdown.last().percent, 1e-9)
    }

    @Test
    fun breakdownIsScaledDownWhenTheEstimateExceedsTheInput() {
        val messages = listOf(prompt("u", "y".repeat(400)), reply("a", null))

        val breakdown = contextBreakdown(messages, systemPrompt = null, input = 100)

        assertEquals(100L, breakdown.sumOf { it.tokens })
        assertEquals(listOf(ContextCategory.USER, ContextCategory.ASSISTANT), breakdown.map { it.category })
    }
}
