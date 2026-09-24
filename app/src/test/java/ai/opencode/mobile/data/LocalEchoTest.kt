package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optimistic echoes of sent prompts are matched by their id prefix instead of a
 * global queue, which leaked between sessions and duplicated every later prompt.
 */
class LocalEchoTest {

    private fun userMessage(id: String) = Message(id = id, sessionID = "ses_1", role = "user")

    private fun state(vararg messages: ChatMessageUi) = ChatState(sessionId = "ses_1", messages = messages.toList())

    @Test
    fun firstServerCopyReplacesEchoInPlaceKeepingItsText() {
        val assistant = ChatMessageUi(Message(id = "msg_a", sessionID = "ses_1", role = "assistant"), emptyList())
        val result = state(assistant, localUserMessage("local-1", "hello"))
            .applyMessageUpdate(userMessage("msg_u"))

        assertEquals(listOf("msg_a", "msg_u"), result.messages.map { it.info.id })
        val replaced = result.messages.last()
        assertFalse(replaced.isLocalEcho)
        assertEquals("hello", replaced.parts.single().text)
        assertEquals("msg_u", replaced.parts.single().messageID)
    }

    @Test
    fun repeatedUpdateOfKnownMessageDoesNotConsumeAnotherEcho() {
        val known = ChatMessageUi(userMessage("msg_u"), emptyList())
        val result = state(known, localUserMessage("local-2", "second"))
            .applyMessageUpdate(userMessage("msg_u"))

        assertEquals(listOf("msg_u", "local-2"), result.messages.map { it.info.id })
    }

    @Test
    fun echoesAreConsumedOldestFirst() {
        val result = state(localUserMessage("local-1", "one"), localUserMessage("local-2", "two"))
            .applyMessageUpdate(userMessage("msg_1"))

        assertEquals(listOf("msg_1", "local-2"), result.messages.map { it.info.id })
        assertEquals("one", result.messages.first().parts.single().text)
    }

    @Test
    fun assistantMessageNeverConsumesAnEcho() {
        val result = state(localUserMessage("local-1", "one"))
            .applyMessageUpdate(Message(id = "msg_a", sessionID = "ses_1", role = "assistant"))

        assertEquals(listOf("local-1", "msg_a"), result.messages.map { it.info.id })
    }

    @Test
    fun firstRealPartReplacesPlaceholderParts() {
        val replaced = state(localUserMessage("local-1", "hello")).applyMessageUpdate(userMessage("msg_u"))

        val result = replaced.upsertPart(
            Part(id = "prt_1", sessionID = "ses_1", messageID = "msg_u", type = "text", text = "hello"),
        )

        assertEquals(listOf("prt_1"), result.messages.single().parts.map { it.id })
    }

    @Test
    fun snapshotKeepsEchoesOfPromptsInFlight() {
        val loaded = listOf(ChatMessageUi(userMessage("msg_old"), emptyList()))
        val result = state(localUserMessage("local-1", "pending")).withMessagesIfCurrent("ses_1", loaded)

        assertEquals(listOf("msg_old", "local-1"), result.messages.map { it.info.id })
    }

    @Test
    fun failedSendRemovesOnlyItsEcho() {
        val result = state(localUserMessage("local-1", "one"), localUserMessage("local-2", "two"))
            .withoutMessage("local-2")

        assertEquals(listOf("local-1"), result.messages.map { it.info.id })
        assertTrue(result.messages.single().isLocalEcho)
    }
}
