package ai.opencode.mobile.data.remote.v2

import ai.opencode.mobile.data.ChatState
import ai.opencode.mobile.data.CompactionState
import ai.opencode.mobile.data.applyDeltas
import ai.opencode.mobile.data.applyMessageUpdate
import ai.opencode.mobile.data.compactionStates
import ai.opencode.mobile.data.decodeMessage
import ai.opencode.mobile.data.decodePart
import ai.opencode.mobile.data.localUserMessage
import ai.opencode.mobile.data.remote.EventEnvelope
import ai.opencode.mobile.data.remote.OpenCodeJson
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.stringOrNull
import ai.opencode.mobile.data.upsertPart
import ai.opencode.mobile.data.withoutMessage
import ai.opencode.mobile.data.withoutPart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v2 event stream, as opencode 2.1.2 sends it, turned into the v1 events the app handles. */
class V2EventTranslatorTest {

    private val directory = "/work/proj"
    private val sessionId = "ses_1"
    private val state = V2State().apply { sessions[sessionId] = Session(id = sessionId, directory = directory, title = "") }
    private val fetched = ArrayList<String>()
    private val translator = V2EventTranslator(directory, state) { id ->
        fetched += id
        if (id == "ses_other") Session(id = id, directory = "/elsewhere") else null
    }

    private fun event(json: String): JsonObject = OpenCodeJson.parseToJsonElement(json) as JsonObject

    private fun translate(vararg events: String): List<EventEnvelope> = runBlocking { events.flatMap { translator.translate(event(it)) } }

    /** Applies the translated events the way ChatController does, without the delta timer. */
    private fun ChatState.apply(envelopes: List<EventEnvelope>): ChatState = envelopes.fold(this) { chat, envelope ->
        val props = envelope.properties ?: JsonObject(emptyMap())
        when (envelope.type) {
            "message.updated" -> chat.applyMessageUpdate(props.decodeMessage("info")!!)
            "message.part.updated" -> chat.upsertPart(props.decodePart("part")!!, createMissingMessage = true)
            "message.part.delta" -> chat.applyDeltas(mapOf("${props.stringOrNull("partID")}|text" to props.stringOrNull("delta").orEmpty()))
            "message.part.removed" -> chat.withoutPart(props.stringOrNull("partID")!!)
            "message.removed" -> chat.withoutMessage(props.stringOrNull("messageID")!!)
            else -> chat
        }
    }

    private val loc = """"location":{"directory":"/work/proj"}"""

    @Test
    fun aRunBecomesMessagesWithPartsAndAStatus() {
        val events = translate(
            """{"id":"evt_1","type":"session.inbox.enqueued",$loc,"data":{"inboxID":"msg_u","sessionID":"ses_1","item":{"type":"user","payload":{"text":"Read README.md"},"delivery":"steer"}}}""",
            """{"id":"evt_2","type":"session.execution.started","data":{"sessionID":"ses_1"}}""",
            """{"id":"evt_3","type":"session.step.started",$loc,"data":{"sessionID":"ses_1","agent":"build","model":{"id":"flint","providerID":"opencode"},"assistantMessageID":"msg_a","started":1}}""",
            """{"id":"evt_4","type":"session.retry.scheduled",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","attempt":2,"at":2,"error":{"type":"provider.internal","message":"Upstream request failed"}}}""",
            """{"id":"evt_5","type":"session.step.started",$loc,"data":{"sessionID":"ses_1","agent":"build","model":{"id":"flint","providerID":"opencode"},"assistantMessageID":"msg_a","started":3}}""",
            """{"id":"evt_6","type":"session.reasoning.started",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0}}""",
            """{"id":"evt_7","type":"session.reasoning.delta",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"delta":"Let me "}}""",
            """{"id":"evt_8","type":"session.reasoning.delta",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"delta":"look."}}""",
            """{"id":"evt_9","type":"session.tool.input.started",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","id":"call_1","name":"glob"}}""",
            """{"id":"evt_10","type":"session.reasoning.ended",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"text":"Let me look."}}""",
            """{"id":"evt_11","type":"session.tool.called",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","id":"call_1","input":{"pattern":"**/README.md"},"executed":false}}""",
            """{"id":"evt_12","type":"session.tool.success",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","id":"call_1","content":[{"type":"text","text":"/work/proj/README.md"}],"metadata":{"count":1},"executed":false}}""",
            """{"id":"evt_13","type":"session.step.ended",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_a","finish":"tool-calls","cost":0,"tokens":{"input":4761,"output":49,"reasoning":0,"cache":{"read":256,"write":0}}}}""",
            """{"id":"evt_14","type":"session.step.started",$loc,"data":{"sessionID":"ses_1","agent":"build","model":{"id":"flint","providerID":"opencode"},"assistantMessageID":"msg_b","started":4}}""",
            """{"id":"evt_15","type":"session.text.started",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_b","ordinal":0}}""",
            """{"id":"evt_16","type":"session.text.delta",$loc,"data":{"sessionID":"ses_1","assistantMessageID":"msg_b","ordinal":0,"delta":"It is a test."}}""",
            """{"id":"evt_17","type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}""",
        )
        val chat = ChatState(sessionId = sessionId, messages = listOf(localUserMessage("local-1", "Read README.md"))).apply(events)

        // The echo became the server's prompt; one message per step after it.
        assertEquals(listOf("msg_u", "msg_a", "msg_b"), chat.messages.map { it.info.id })
        val step = chat.messages[1]
        assertEquals("assistant", step.info.role)
        assertEquals("flint", step.info.modelID)
        assertEquals("tool-calls", step.info.finish)
        assertEquals(listOf("reasoning", "tool", "step-finish"), step.parts.map { it.type })
        assertEquals("Let me look.", step.parts[0].text)
        val tool = step.parts[1]
        assertEquals("glob", tool.tool)
        assertEquals("completed", tool.state?.status)
        assertEquals("/work/proj/README.md", tool.state?.output)
        assertTrue("the retry notice goes once the step runs", step.parts.none { it.type == "retry" })
        assertEquals("It is a test.", chat.messages[2].parts.single().text)

        assertEquals(listOf("busy", "idle"), events.filter { it.type == "session.status" }.map { (it.properties!!["status"] as JsonObject).stringOrNull("type") })
        assertTrue(events.any { it.type == "session.idle" })
    }

    @Test
    fun eventsOfAnotherProjectAreDropped() {
        val events = translate(
            """{"id":"evt_1","type":"session.step.started","location":{"directory":"/elsewhere"},"data":{"sessionID":"ses_x","assistantMessageID":"msg_a","agent":"build","model":{"id":"m","providerID":"p"},"started":1}}""",
            // No location: the session decides; it is read once to find out.
            """{"id":"evt_2","type":"session.execution.started","data":{"sessionID":"ses_other"}}""",
            """{"id":"evt_3","type":"session.execution.started","data":{"sessionID":"ses_other"}}""",
            """{"id":"evt_4","type":"session.execution.started","data":{"sessionID":"ses_1"}}""",
        )

        assertEquals(1, events.size)
        assertEquals(sessionId, events.single().properties!!.stringOrNull("sessionID"))
        assertEquals(listOf("ses_other"), fetched)
    }

    @Test
    fun sessionChangesUpdateTheListedSession() {
        val renamed = translate("""{"id":"evt_1","type":"session.renamed",$loc,"data":{"sessionID":"ses_1","title":"Fix the build"}}""").single()
        assertEquals("session.updated", renamed.type)
        assertEquals("Fix the build", (renamed.properties!!["info"] as JsonObject).stringOrNull("title"))

        val staged = translate("""{"id":"evt_2","type":"session.revert.staged",$loc,"data":{"sessionID":"ses_1","revert":{"messageID":"msg_u"}}}""").single()
        assertEquals("msg_u", state.sessions[sessionId]?.revert?.messageID)
        assertEquals("session.updated", staged.type)
        translate("""{"id":"evt_3","type":"session.revert.cleared",$loc,"data":{"sessionID":"ses_1"}}""")
        assertNull(state.sessions[sessionId]?.revert)
        assertEquals("Fix the build", state.sessions[sessionId]?.title)
    }

    @Test
    fun aCompactionShowsAsAMarkerWithItsSummary() {
        val started = translate(
            """{"id":"evt_c","type":"session.compaction.started",$loc,"data":{"sessionID":"ses_1","reason":"manual","inputID":"msg_c"}}""",
            """{"id":"evt_d","type":"session.compaction.delta",$loc,"data":{"sessionID":"ses_1","text":"Summary so "}}""",
        )
        var chat = ChatState(sessionId = sessionId, busy = true).apply(started)
        assertEquals(CompactionState.Running, chat.messages.compactionStates(busy = true)["msg_c"])
        assertEquals("Summary so ", chat.messages.last().parts.single().text)

        val ended = translate("""{"id":"evt_e","type":"session.compaction.ended",$loc,"data":{"sessionID":"ses_1","reason":"manual","text":"Summary so far."}}""")
        chat = chat.apply(ended)
        assertEquals(CompactionState.Done, chat.messages.compactionStates(busy = false)["msg_c"])
        assertEquals("Summary so far.", chat.messages.last().parts.single().text)
        assertTrue("the chat is read back once it is done", ended.any { it.type == "session.compacted" })
    }

    @Test
    fun permissionsAndFormsBecomeRequestsToAnswer() {
        val events = translate(
            """{"id":"evt_1","type":"permission.asked",$loc,"data":{"id":"per_1","sessionID":"ses_1","action":"read","resources":["/work/proj/.env"],"save":["*.env"],"source":{"type":"tool","messageID":"msg_a","id":"call_1"}}}""",
            """{"id":"evt_2","type":"form.created",$loc,"data":{"form":{"id":"frm_1","sessionID":"ses_1","title":"Questions","metadata":{"kind":"question"},"fields":[{"key":"q0","title":"Color","description":"Which color?","type":"string","options":[{"value":"Red","label":"Red"},{"value":"Blue","label":"Blue"}],"custom":true},{"key":"q1","title":"Sizes","description":"Which sizes?","type":"multiselect","options":[{"value":"s","label":"Small"},{"value":"l","label":"Large"}]}]}}}""",
        )

        val permission = events.first { it.type == "permission.asked" }.properties!!
        assertEquals("per_1", permission.stringOrNull("id"))
        assertEquals("read", permission.stringOrNull("permission"))
        assertEquals("call_1", (permission["tool"] as JsonObject).stringOrNull("callID"))

        val question = events.first { it.type == "question.asked" }.properties!!
        assertEquals("frm_1", question.stringOrNull("id"))
        val form = state.forms["frm_1"]
        assertNotNull(form)
        val answer = form!!.formAnswer(listOf(listOf("Blue"), listOf("Small", "Large")))
        assertEquals("""{"q0":"Blue","q1":["s","l"]}""", answer.toString())

        val replied = translate("""{"id":"evt_3","type":"form.replied",$loc,"data":{"sessionID":"ses_1","id":"frm_1"}}""").single()
        assertEquals("question.replied", replied.type)
        assertEquals("frm_1", replied.properties!!.stringOrNull("requestID"))
        assertFalse(state.forms.containsKey("frm_1"))
    }

    @Test
    fun aFailedRunReportsItsError() {
        val events = translate(
            """{"id":"evt_1","type":"session.execution.failed","data":{"sessionID":"ses_1","error":{"type":"provider.internal","message":"Endpoint is unavailable."}}}""",
        )
        val error = events.single { it.type == "session.error" }.properties!!["error"] as JsonObject
        assertEquals("provider.internal", error.stringOrNull("name"))
        assertEquals("Endpoint is unavailable.", (error["data"] as JsonObject).stringOrNull("message"))
    }
}
