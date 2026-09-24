package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
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
        private val selection = MutableStateFlow(ModelSelection())
        override val settings: Flow<ConnectionSettings> = state
        override suspend fun save(settings: ConnectionSettings) {
            state.value = settings
        }

        override val modelSelection: Flow<ModelSelection> = selection
        override suspend fun saveModelSelection(selection: ModelSelection) {
            this.selection.value = selection
        }

        override fun enabledModels(serverUrl: String): Flow<Set<String>?> = MutableStateFlow(null)
        override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) = Unit
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
    fun sessionErrorWithoutSessionIdAndNoChatGoesToSessionBanner() = runBlocking {
        val repository = repository()

        repository.handleEvent(
            client,
            event(
                "session.error",
                buildJsonObject { putJsonObject("error") { put("name", "UnknownError") } },
            ),
        )

        // Routed to the dismissible sessions banner, not to the connection state:
        // the stream is alive, and nothing would ever clear a connection error.
        assertEquals("UnknownError", repository.sessionError.value)
    }

    @Test
    fun messageUpdatedFromAnotherSessionIsIgnored() = runBlocking {
        val repository = repository()
        repository.setChatForTest(ChatState(sessionId = "ses_1"))

        repository.handleEvent(
            client,
            event(
                "message.updated",
                buildJsonObject {
                    put(
                        "info",
                        OpenCodeJson.encodeToJsonElement(
                            Message.serializer(),
                            Message(id = "msg_other", sessionID = "ses_2", role = "assistant"),
                        ),
                    )
                },
            ),
        )

        assertTrue(repository.chat.value.messages.isEmpty())
    }

    @Test
    fun childSessionsStayOutOfTheSessionList() = runBlocking {
        val repository = repository()

        repository.handleEvent(
            client,
            event(
                "session.created",
                buildJsonObject { put("info", sessionInfo("ses_child", parentID = "ses_parent")) },
            ),
        )

        assertTrue(repository.sessions.value.isEmpty())
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
    fun staleLoadDoesNotOverwriteNewerSession() {
        val newSession = ChatState(sessionId = "ses_new", loading = true)
        val message = ChatMessageUi(
            info = Message(id = "msg_1", sessionID = "ses_old", role = "assistant"),
            parts = emptyList(),
        )

        val result = newSession.withMessagesIfCurrent("ses_old", listOf(message))

        assertEquals("ses_new", result.sessionId)
        assertTrue(result.messages.isEmpty())
        assertTrue(result.loading)
    }

    @Test
    fun matchingLoadAppliesMessages() {
        val state = ChatState(sessionId = "ses_1", loading = true)
        val message = ChatMessageUi(
            info = Message(id = "msg_1", sessionID = "ses_1", role = "assistant"),
            parts = emptyList(),
        )

        val result = state.withMessagesIfCurrent("ses_1", listOf(message))

        assertEquals(1, result.messages.size)
        assertFalse(result.loading)
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

    private fun userMessageUpdated(id: String, sessionId: String) = event(
        "message.updated",
        buildJsonObject {
            put(
                "info",
                OpenCodeJson.encodeToJsonElement(
                    Message.serializer(),
                    Message(id = id, sessionID = sessionId, role = "user"),
                ),
            )
        },
    )

    @Test
    fun userMessageFromAnotherSessionLeavesEchoAlone() = runBlocking {
        val repository = repository()
        repository.setChatForTest(
            ChatState(sessionId = "ses_2", messages = listOf(localUserMessage("local-1", "hi"))),
        )

        // Server copy of a prompt sent earlier in ses_1 arrives after the switch.
        repository.handleEvent(client, userMessageUpdated("msg_old", "ses_1"))
        repository.handleEvent(client, userMessageUpdated("msg_new", "ses_2"))

        val messages = repository.chat.value.messages
        assertEquals(listOf("msg_new"), messages.map { it.info.id })
        assertEquals("hi", messages.single().parts.single().text)
    }

    @Test
    fun permissionAskedIsAddedFromPayloadAndRemovedOnReply() = runBlocking {
        val repository = repository()

        repository.handleEvent(
            client,
            event(
                "permission.asked",
                buildJsonObject {
                    put("id", "per_1")
                    put("sessionID", "ses_1")
                    put("permission", "bash")
                },
            ),
        )
        assertEquals(listOf("per_1"), repository.permissions.value.map { it.id })
        assertEquals("bash", repository.permissions.value.single().permission)

        repository.handleEvent(client, event("permission.replied", buildJsonObject { put("requestID", "per_1") }))
        assertTrue(repository.permissions.value.isEmpty())
    }

    @Test
    fun repeatedPermissionAskedDoesNotDuplicate() = runBlocking {
        val repository = repository()
        val asked = event(
            "permission.asked",
            buildJsonObject {
                put("id", "per_1")
                put("sessionID", "ses_1")
            },
        )

        repository.handleEvent(client, asked)
        repository.handleEvent(client, asked)

        assertEquals(1, repository.permissions.value.size)
    }

    @Test
    fun childSessionStatusDoesNotFlipParentBusy() = runBlocking {
        val repository = repository()
        repository.setChatForTest(ChatState(sessionId = "ses_parent", busy = true))
        repository.handleEvent(
            client,
            event("session.created", buildJsonObject { put("info", sessionInfo("ses_child", parentID = "ses_parent")) }),
        )

        repository.handleEvent(
            client,
            event(
                "session.status",
                buildJsonObject {
                    put("sessionID", "ses_child")
                    putJsonObject("status") { put("type", "idle") }
                },
            ),
        )

        assertTrue(repository.chat.value.busy)
    }

    @Test
    fun heartbeatWithoutPropertiesIsIgnored() = runBlocking {
        val repository = repository()
        val before = ChatState(sessionId = "ses_1", busy = true)
        repository.setChatForTest(before)

        repository.handleEvent(client, event("server.heartbeat"))

        assertEquals(before, repository.chat.value)
    }
}
