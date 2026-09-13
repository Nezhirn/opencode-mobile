package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.EventEnvelope
import ai.opencode.mobile.data.remote.Message
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.OpenCodeJson
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event-handling table for [AppRepository]. Uses a fake settings source so the
 * repository runs without network; the client is never actually called by the
 * branches under test.
 */
class AppRepositoryEventsTest {

    private class FakeSettingsSource : SettingsSource {
        private val state = MutableStateFlow(ConnectionSettings())
        override val settings: Flow<ConnectionSettings> = state
        override suspend fun save(settings: ConnectionSettings) {
            state.value = settings
        }
    }

    private val client = OpenCodeClient("http://127.0.0.1:1")

    private fun repository() = AppRepository(FakeSettingsSource())

    private fun event(type: String, props: JsonObject? = null) =
        EventEnvelope(id = "e", type = type, properties = props)

    private fun sessionInfo(id: String, parentID: String? = null) =
        OpenCodeJson.encodeToJsonElement(
            Session.serializer(),
            Session(id = id, parentID = parentID),
        ).jsonObject

    @Test
    fun sessionErrorWithMatchingSessionShowsServerMessage() = runBlocking {
        val repository = repository()
        repository.setChatForTest(ChatState(sessionId = "ses_1", busy = true))

        repository.handleEvent(
            client,
            event(
                "session.error",
                buildJsonObject {
                    put("sessionID", "ses_1")
                    putJsonObject("error") {
                        put("name", "ProviderAuthError")
                        putJsonObject("data") { put("message", "bad key") }
                    }
                },
            ),
        )

        val chat = repository.chat.value
        assertFalse(chat.busy)
        assertEquals("ProviderAuthError: bad key", chat.error)
    }

    @Test
    fun messageAbortedErrorIsNotShownAsError() = runBlocking {
        val repository = repository()
        repository.setChatForTest(ChatState(sessionId = "ses_1", busy = true))

        repository.handleEvent(
            client,
            event(
                "session.error",
                buildJsonObject {
                    put("sessionID", "ses_1")
                    putJsonObject("error") { put("name", "MessageAbortedError") }
                },
            ),
        )

        assertFalse(repository.chat.value.busy)
        assertNull(repository.chat.value.error)
    }

    @Test
    fun sessionErrorWithoutSessionIdAppliesToOpenChat() = runBlocking {
        val repository = repository()
        repository.setChatForTest(ChatState(sessionId = "ses_1", busy = true))

        repository.handleEvent(
            client,
            event(
                "session.error",
                buildJsonObject { putJsonObject("error") { put("name", "UnknownError") } },
            ),
        )

        assertEquals("UnknownError", repository.chat.value.error)
    }

    @Test
    fun sessionErrorWithoutSessionIdAndNoChatGoesToConnection() = runBlocking {
        val repository = repository()

        repository.handleEvent(
            client,
            event(
                "session.error",
                buildJsonObject { putJsonObject("error") { put("name", "UnknownError") } },
            ),
        )

        assertTrue(repository.connection.value is ConnectionState.Error)
    }

    @Test
    fun sessionStatusBusyUpdatesChat() = runBlocking {
        val repository = repository()
        repository.setChatForTest(ChatState(sessionId = "ses_1"))

        repository.handleEvent(
            client,
            event(
                "session.status",
                buildJsonObject {
                    put("sessionID", "ses_1")
                    putJsonObject("status") { put("type", "busy") }
                },
            ),
        )

        assertTrue(repository.chat.value.busy)
    }

    @Test
    fun childSessionMatchesParentChat() = runBlocking {
        val repository = repository()
        repository.setChatForTest(ChatState(sessionId = "ses_parent"))

        repository.handleEvent(
            client,
            event(
                "session.created",
                buildJsonObject { put("info", sessionInfo("ses_child", parentID = "ses_parent")) },
            ),
        )

        assertTrue(repository.sessionMatches("ses_child", "ses_parent"))
        assertFalse(repository.sessionMatches("ses_child", "ses_other"))
    }

    @Test
    fun streamedDeltaIsAppended() = runBlocking {
        val repository = repository()
        repository.setChatForTest(
            ChatState(
                sessionId = "ses_1",
                messages = listOf(
                    ChatMessageUi(
                        info = Message(id = "msg_1", sessionID = "ses_1", role = "assistant"),
                        parts = listOf(Part(id = "prt_1", messageID = "msg_1", type = "text", text = "hel")),
                    ),
                ),
            ),
        )

        repository.handleEvent(
            client,
            event(
                "message.part.delta",
                buildJsonObject {
                    put("sessionID", "ses_1")
                    put("partID", "prt_1")
                    put("field", "text")
                    put("delta", "lo")
                },
            ),
        )

        delay(150)
        assertEquals("hello", repository.chat.value.messages.single().parts.single().text)
    }
}
