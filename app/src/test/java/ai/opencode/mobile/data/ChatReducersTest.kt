package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducersTest {

    private fun message(id: String, role: String = "assistant") =
        Message(id = id, sessionID = "ses_1", role = role)

    private fun textPart(id: String, messageId: String, text: String) =
        Part(id = id, sessionID = "ses_1", messageID = messageId, type = "text", text = text)

    @Test
    fun upsertMessageAddsThenReplacesInPlace() {
        val state = ChatState(sessionId = "ses_1")
            .upsertMessage(message("msg_1"))
            .upsertMessage(message("msg_2"))
            .upsertMessage(message("msg_1", role = "assistant"))

        assertEquals(listOf("msg_1", "msg_2"), state.messages.map { it.info.id })
    }

    @Test
    fun upsertPartAppendsAndUpdatesById() {
        var state = ChatState(sessionId = "ses_1").upsertMessage(message("msg_1"))
        state = state.upsertPart(textPart("prt_1", "msg_1", "Hello"))
        state = state.upsertPart(textPart("prt_2", "msg_1", " world"))
        state = state.upsertPart(textPart("prt_1", "msg_1", "Hello!"))

        val parts = state.messages.single().parts
        assertEquals(listOf("prt_1", "prt_2"), parts.map { it.id })
        assertEquals("Hello!", parts.first().text)
    }

    @Test
    fun upsertPartForUnknownMessageIsIgnored() {
        val state = ChatState(sessionId = "ses_1")
            .upsertPart(textPart("prt_1", "msg_missing", "orphan"))

        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun deltaAppendsToExistingPartOnly() {
        var state = ChatState(sessionId = "ses_1").upsertMessage(message("msg_1"))
        state = state.upsertPart(textPart("prt_1", "msg_1", "Hel"))
        state = state.applyDelta("prt_1", "text", "lo")
        state = state.applyDelta("prt_unknown", "text", "ignored")

        assertEquals("Hello", state.messages.single().parts.single().text)
    }
}
