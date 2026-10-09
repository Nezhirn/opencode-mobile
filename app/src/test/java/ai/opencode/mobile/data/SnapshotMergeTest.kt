package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionRevert
import ai.opencode.mobile.data.remote.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merging a chat snapshot with what events changed while it loaded. */
class SnapshotMergeTest {

    private fun message(id: String, role: String = "assistant", vararg parts: Part) =
        ChatMessageUi(info = Message(id = id, sessionID = "ses_1", role = role), parts = parts.toList())

    private fun tool(id: String, messageId: String, status: String) =
        Part(id = id, messageID = messageId, type = "tool", state = ToolState(status = status))

    private fun text(id: String, messageId: String, text: String) =
        Part(id = id, messageID = messageId, type = "text", text = text)

    @Test
    fun toolThatCompletedDuringTheLoadStaysCompleted() {
        val live = ChatState(sessionId = "ses_1", messages = listOf(message("a1", parts = arrayOf(tool("t1", "a1", "completed")))))
        val snapshot = listOf(message("a1", parts = arrayOf(tool("t1", "a1", "running"))))
        val changes = LiveChanges().apply { parts += "t1" }

        val merged = live.withMessagesIfCurrent("ses_1", snapshot, knownBefore = setOf("a1"), changes = changes)

        assertEquals("completed", merged.messages.single().parts.single().state?.status)
    }

    @Test
    fun withoutChangesTheSnapshotPartWins() {
        val live = ChatState(sessionId = "ses_1", messages = listOf(message("a1", parts = arrayOf(tool("t1", "a1", "running")))))
        val snapshot = listOf(message("a1", parts = arrayOf(tool("t1", "a1", "completed"))))

        val merged = live.withMessagesIfCurrent("ses_1", snapshot, knownBefore = setOf("a1"), changes = LiveChanges())

        assertEquals("completed", merged.messages.single().parts.single().state?.status)
    }

    @Test
    fun messageInfoUpdatedDuringTheLoadKeepsTheLiveCopy() {
        val finished = Message(id = "a1", sessionID = "ses_1", role = "assistant", finish = "stop")
        val live = ChatState(sessionId = "ses_1", messages = listOf(ChatMessageUi(finished, emptyList())))
        val snapshot = listOf(message("a1"))
        val changes = LiveChanges().apply { messages += "a1" }

        val merged = live.withMessagesIfCurrent("ses_1", snapshot, knownBefore = setOf("a1"), changes = changes)

        assertEquals("stop", merged.messages.single().info.finish)
    }

    @Test
    fun messagesAndPartsRemovedDuringTheLoadDoNotComeBack() {
        val live = ChatState(sessionId = "ses_1", messages = listOf(message("a1", parts = arrayOf(text("p1", "a1", "kept")))))
        val snapshot = listOf(
            message("a1", parts = arrayOf(text("p1", "a1", "kept"), text("p2", "a1", "gone"))),
            message("a2"),
        )
        val changes = LiveChanges().apply {
            removedMessages += "a2"
            removedParts += "p2"
        }

        val merged = live.withMessagesIfCurrent("ses_1", snapshot, knownBefore = setOf("a1", "a2"), changes = changes)

        assertEquals(listOf("a1"), merged.messages.map { it.info.id })
        assertEquals(listOf("p1"), merged.messages.single().parts.map { it.id })
    }

    @Test
    fun anErrorShownBeforeTheSnapshotStays() {
        val live = ChatState(sessionId = "ses_1", error = UiText.Raw("send failed"), messages = listOf(message("a1")))

        val merged = live.withMessagesIfCurrent("ses_1", listOf(message("a1")), knownBefore = setOf("a1"))

        assertEquals(UiText.Raw("send failed"), merged.error)
    }

    @Test
    fun aFailedPromptPutsTheRollbackBack() {
        val point = message("u2", "user", text("u2p1", "u2", "edit me"), text("u2p2", "u2", "second"))
        val before = listOf(message("u1", "user"), message("a1"), point, message("a2"))
        val rolledBack = ChatState(sessionId = "ses_1", messages = before, revert = SessionRevert(messageID = "u2", partID = "u2p2"))
        val hidden = rolledBack.revertedMessageIds()
        // What committedRevert left, minus the failed prompt's echo.
        val failed = rolledBack.committedRevert()

        val restored = failed.withRestoredRevert(rolledBack.revert, before)

        assertEquals(setOf("a2"), hidden)
        assertEquals(before.map { it.info.id }, restored.messages.map { it.info.id })
        assertEquals("u2", restored.revert?.messageID)
        // The message at the point comes back whole, not cut at the part.
        assertEquals(2, restored.messages.first { it.info.id == "u2" }.parts.size)
        // Cut at the part again on screen, everything after it hidden.
        assertEquals(listOf("u1", "a1", "u2"), restored.visibleMessages.map { it.info.id })
        assertEquals(1, restored.visibleMessages.last().parts.size)
    }

    @Test
    fun sessionEventsDuringAListLoadWinOverTheList() {
        val snapshot = listOf(Session(id = "s1", title = "old"), Session(id = "s2"), Session(id = "s2"), Session(id = ""))
        val merged = mergeSessionSnapshot(
            snapshot,
            changed = mapOf("s1" to Session(id = "s1", title = "new"), "s3" to Session(id = "s3")),
            deleted = setOf("s2"),
        )

        assertEquals(listOf("s1", "s3"), merged.map { it.id })
        assertEquals("new", merged.first().title)
    }

    @Test
    fun sessionTreeResetReplacesTheLinks() {
        val tree = SessionTree()
        tree.record(Session(id = "child", parentID = "root"))

        tree.reset(listOf(Session(id = "child", parentID = "root"), Session(id = "other")))

        assertTrue(tree.matches("child", "root"))
        assertFalse(tree.matches("other", "root"))
    }
}
