package ai.opencode.mobile.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the prompt wire format. The server rejects a text part without an
 * explicit `type` field, and the client runs with `encodeDefaults = false`,
 * so `type` must not rely on a default value.
 */
class PromptRequestSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun textPartAlwaysSerializesItsType() {
        val request = PromptRequest(parts = listOf(TextPartInput(type = "text", text = "hi")))
        val body = json.encodeToString(PromptRequest.serializer(), request)

        assertTrue("body must contain the part type: $body", body.contains("\"type\":\"text\""))
        assertTrue("body must contain the text: $body", body.contains("\"text\":\"hi\""))
    }

    @Test
    fun absentOptionalFieldsAreOmitted() {
        val request = PromptRequest(parts = listOf(TextPartInput(type = "text", text = "hi")))
        val body = json.encodeToString(PromptRequest.serializer(), request)

        assertFalse("model must be omitted when null: $body", body.contains("\"model\""))
        assertFalse("agent must be omitted when null: $body", body.contains("\"agent\""))
    }

    @Test
    fun serializesModelAndAgentWhenSelected() {
        val request = PromptRequest(
            model = PromptModel(providerID = "anthropic", modelID = "claude"),
            agent = "build",
            parts = listOf(TextPartInput(type = "text", text = "hi")),
        )
        val body = json.encodeToString(PromptRequest.serializer(), request)

        assertTrue("providerID missing: $body", body.contains("\"providerID\":\"anthropic\""))
        assertTrue("modelID missing: $body", body.contains("\"modelID\":\"claude\""))
        assertTrue("agent missing: $body", body.contains("\"agent\":\"build\""))
    }
}
