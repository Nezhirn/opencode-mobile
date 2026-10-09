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
        val request = PromptRequest(parts = listOf(PromptPart(type = "text", text = "hi")))
        val body = json.encodeToString(PromptRequest.serializer(), request)

        assertTrue("body must contain the part type: $body", body.contains("\"type\":\"text\""))
        assertTrue("body must contain the text: $body", body.contains("\"text\":\"hi\""))
    }

    @Test
    fun absentOptionalFieldsAreOmitted() {
        val request = PromptRequest(parts = listOf(PromptPart(type = "text", text = "hi")))
        val body = json.encodeToString(PromptRequest.serializer(), request)

        assertFalse("model must be omitted when null: $body", body.contains("\"model\""))
        assertFalse("agent must be omitted when null: $body", body.contains("\"agent\""))
    }

    @Test
    fun serializesModelAndAgentWhenSelected() {
        val request = PromptRequest(
            model = PromptModel(providerID = "anthropic", modelID = "claude"),
            agent = "build",
            parts = listOf(PromptPart(type = "text", text = "hi")),
        )
        val body = json.encodeToString(PromptRequest.serializer(), request)

        assertTrue("providerID missing: $body", body.contains("\"providerID\":\"anthropic\""))
        assertTrue("modelID missing: $body", body.contains("\"modelID\":\"claude\""))
        assertTrue("agent missing: $body", body.contains("\"agent\":\"build\""))
    }

    @Test
    fun serializesVariantOnlyWhenPicked() {
        val parts = listOf(PromptPart(type = "text", text = "hi"))
        val withVariant = json.encodeToString(PromptRequest.serializer(), PromptRequest(variant = "high", parts = parts))
        val without = json.encodeToString(PromptRequest.serializer(), PromptRequest(parts = parts))

        assertTrue("variant missing: $withVariant", withVariant.contains("\"variant\":\"high\""))
        assertFalse("variant must be omitted when null: $without", without.contains("\"variant\""))
    }

    @Test
    fun filePartCarriesOnlyItsFields() {
        val request = PromptRequest(
            parts = listOf(
                PromptPart(type = "text", text = "look"),
                PromptPart(type = "file", mime = "image/png", url = "data:image/png;base64,AA==", filename = "a.png"),
            ),
        )

        val encoded = json.encodeToString(PromptRequest.serializer(), request)

        assertTrue(encoded.contains("""{"type":"file","mime":"image/png","url":"data:image/png;base64,AA==","filename":"a.png"}"""))
        assertTrue(encoded.contains("""{"type":"text","text":"look"}"""))
    }
}
