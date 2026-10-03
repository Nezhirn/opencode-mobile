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

    private fun textPart(messageId: String, id: String, text: String) =
        Part(id = id, sessionID = "ses_1", messageID = messageId, type = "text", text = text)

    @Test
    fun snapshotDropsEchoTheServerAlreadyHas() {
        // message.updated for the prompt was lost in a stream gap; the resync
        // snapshot contains it under its real id.
        val known = ChatMessageUi(userMessage("msg_old"), listOf(textPart("msg_old", "prt_old", "first")))
        val live = state(known, localUserMessage("local-1", "second"))
        val snapshot = listOf(
            known,
            ChatMessageUi(userMessage("msg_new"), listOf(textPart("msg_new", "prt_new", "second"))),
        )

        val result = live.withMessagesIfCurrent("ses_1", snapshot, knownBefore = setOf("msg_old", "local-1"))

        assertEquals(listOf("msg_old", "msg_new"), result.messages.map { it.info.id })
    }

    @Test
    fun snapshotKeepsMessagesThatArrivedWhileItLoaded() {
        // Sent while the chat was loading: the echo already became msg_u.
        val confirmed = ChatMessageUi(userMessage("msg_u"), listOf(textPart("msg_u", "prt_u", "hi")))
        val live = state(confirmed)

        val result = live.withMessagesIfCurrent("ses_1", listOf(ChatMessageUi(userMessage("msg_old"), emptyList())))

        assertEquals(listOf("msg_old", "msg_u"), result.messages.map { it.info.id })
    }

    @Test
    fun snapshotDropsMessagesRemovedOnTheServer() {
        val gone = ChatMessageUi(userMessage("msg_gone"), emptyList())
        val live = state(gone)

        val result = live.withMessagesIfCurrent("ses_1", emptyList(), knownBefore = setOf("msg_gone"))

        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun liveTextThatIsAheadOfTheSnapshotIsKept() {
        val streaming = ChatMessageUi(
            Message(id = "msg_a", sessionID = "ses_1", role = "assistant"),
            listOf(textPart("msg_a", "prt_a", "Hello, wor")),
        )
        val snapshot = listOf(streaming.copy(parts = listOf(textPart("msg_a", "prt_a", "Hello"))))

        val result = state(streaming).withMessagesIfCurrent("ses_1", snapshot, knownBefore = setOf("msg_a"))

        assertEquals("Hello, wor", result.messages.single().parts.single().text)
    }
}
