package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.Part
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionStateTest {

    private fun user(id: String) = ChatMessageUi(
        info = Message(id = id, role = "user"),
        parts = listOf(Part(id = "$id-p", messageID = id, type = "text", text = id)),
    )

    private fun marker(id: String) = ChatMessageUi(
        info = Message(id = id, role = "user"),
        parts = listOf(Part(id = "$id-p", messageID = id, type = "compaction", auto = false)),
    )

    private fun summary(id: String, parent: String, finished: Boolean = true, failed: Boolean = false) = ChatMessageUi(
        info = Message(
            id = id,
            role = "assistant",
            parentID = parent,
            summary = JsonPrimitive(true),
            finish = if (finished) "stop" else null,
            error = if (failed) buildJsonObject { put("name", JsonPrimitive("APIError")) } else null,
        ),
        parts = listOf(Part(id = "$id-p", messageID = id, type = "text", text = "summary")),
    )

    @Test
    fun theCommandIsRecognisedButNotInsideAPrompt() {
        assertTrue(isCompactCommand("/compact"))
        assertTrue(isCompactCommand("  /Compact\n"))
        assertTrue(isCompactCommand("/summarize"))
        assertFalse(isCompactCommand("/compact this please"))
        assertFalse(isCompactCommand("compact"))
    }

    @Test
    fun aFinishedSummaryMeansDone() {
        val messages = listOf(user("u1"), marker("c1"), summary("s1", parent = "c1"))
        assertEquals(mapOf("c1" to CompactionState.Done), messages.compactionStates(busy = true))
    }

    @Test
    fun aStreamingSummaryIsRunningWhileTheChatIsBusy() {
        val messages = listOf(user("u1"), marker("c1"), summary("s1", parent = "c1", finished = false))
        assertEquals(CompactionState.Running, messages.compactionStates(busy = true)["c1"])
        assertEquals(CompactionState.Unfinished, messages.compactionStates(busy = false)["c1"])
    }

    @Test
    fun aFailedOrOvertakenCompactionIsUnfinished() {
        val failed = listOf(marker("c1"), summary("s1", parent = "c1", failed = true))
        assertEquals(CompactionState.Unfinished, failed.compactionStates(busy = false)["c1"])
        // A later prompt runs now, not this compaction.
        val overtaken = listOf(marker("c1"), user("u2"))
        assertEquals(CompactionState.Unfinished, overtaken.compactionStates(busy = true)["c1"])
    }

    @Test
    fun onlyTheMarkerCounts() {
        assertTrue(listOf(user("u1"), summary("s1", parent = "u1")).compactionStates(busy = true).isEmpty())
        assertTrue(summary("s1", parent = "c1").isSummary)
        assertFalse(user("u1").isCompaction)
    }
}
