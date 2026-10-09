package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.SessionRevert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rolled back chats as the web client shows them. */
class RevertTest {

    private fun message(id: String, role: String, vararg parts: String) = ChatMessageUi(
        info = Message(id = id, sessionID = "ses_1", role = role),
        parts = parts.map { Part(id = it, messageID = id, type = "text", text = "text of $it") },
    )

    private val chat = listOf(
        message("u1", "user", "p1"),
        message("a1", "assistant", "p2", "p3"),
        message("u2", "user", "p4"),
        message("a2", "assistant", "p5"),
        message("u3", "user", "p6"),
        message("a3", "assistant", "p7"),
    )

    private fun state(revert: SessionRevert?) = ChatState(sessionId = "ses_1", messages = chat, revert = revert)

    @Test
    fun withoutRollbackEverythingIsVisible() {
        val state = state(null)

        assertEquals(chat, state.visibleMessages)
        assertTrue(state.revertedUserMessages.isEmpty())
    }

    @Test
    fun rollbackHidesTheMessageAndAllAfterIt() {
        val state = state(SessionRevert(messageID = "u2"))

        assertEquals(listOf("u1", "a1"), state.visibleMessages.map { it.info.id })
        assertEquals(listOf("u2", "u3"), state.revertedUserMessages.map { it.info.id })
    }

    @Test
    fun rollbackToAPartKeepsTheMessageUpToThatPart() {
        val state = state(SessionRevert(messageID = "a1", partID = "p3"))

        assertEquals(listOf("u1", "a1"), state.visibleMessages.map { it.info.id })
        assertEquals(listOf("p2"), state.visibleMessages.last().parts.map { it.id })
        assertEquals(listOf("u2", "u3"), state.revertedUserMessages.map { it.info.id })
    }

    @Test
    fun unknownRollbackPointHidesNothing() {
        assertEquals(chat, state(SessionRevert(messageID = "gone")).visibleMessages)
    }

    @Test
    fun restoringAMessageRollsBackToTheNextOneOrUndoesTheRollback() {
        val state = state(SessionRevert(messageID = "u2"))

        assertEquals("u3", state.restoreTarget("u2")?.info?.id)
        assertNull(state.restoreTarget("u3"))
    }

    @Test
    fun sendingCommitsTheRollbackSoTheEchoIsVisible() {
        val committed = state(SessionRevert(messageID = "u2")).committedRevert()
        val sent = committed.copy(messages = committed.messages + localUserMessage("local-1", "again"))

        assertNull(sent.revert)
        assertEquals(listOf("u1", "a1", "local-1"), sent.visibleMessages.map { it.info.id })
    }

    @Test
    fun echoCarriesTheAttachments() {
        val image = Attachment(id = "x", filename = "a.png", mime = "image/png", url = "data:image/png;base64,AA==")

        val echo = localUserMessage("local-1", "", listOf(image))

        assertEquals(listOf("file"), echo.parts.map { it.type })
        assertEquals(listOf(image.copy(id = "local-1-file-0")), echo.attachments())
        assertEquals("", echo.userText())
    }

    @Test
    fun editDraftSkipsSyntheticText() {
        val sent = ChatMessageUi(
            info = Message(id = "u", role = "user"),
            parts = listOf(
                Part(id = "t", type = "text", text = "fix this"),
                Part(id = "s", type = "text", text = "Called the Read tool", synthetic = true),
                Part(id = "f", type = "file", mime = "text/plain", url = "file:///p/a.kt", filename = "a.kt"),
            ),
        )

        assertEquals("fix this", sent.userText())
        assertEquals(listOf("file:///p/a.kt"), sent.attachments().map { it.url })
    }
}
