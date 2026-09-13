package ai.opencode.mobile.data.remote

import ai.opencode.mobile.data.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Parses payload samples captured from a live opencode 1.18.30 server to guard
 * the DTO shapes against server-side changes.
 */
class DtoParsingTest {

    @Test
    fun parsesSessionFromServerEvent() {
        val raw = """
            {"id":"ses_f688e09e3ffepoSmJboq4cnRYo","slug":"playful-meadow","version":"1.18.30",
             "projectID":"global","directory":"/home/andrew","path":"home/andrew","title":"mobile-test",
             "cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
             "time":{"created":1789247223324,"updated":1789247223324}}
        """.trimIndent()

        val session = AppJson.decodeFromString(Session.serializer(), raw)

        assertEquals("ses_f688e09e3ffepoSmJboq4cnRYo", session.id)
        assertEquals("mobile-test", session.title)
        assertEquals("global", session.projectID)
        assertEquals(1789247223324, session.time?.updated)
    }

    @Test
    fun parsesCompletedToolPart() {
        val raw = """
            {"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"tool","callID":"call_1",
             "tool":"bash","state":{"status":"completed","input":{"command":"ls"},"output":"file.txt",
             "title":"Run ls","metadata":{},"time":{"start":1,"end":2}}}
        """.trimIndent()

        val part = AppJson.decodeFromString(Part.serializer(), raw)

        assertEquals("tool", part.type)
        assertEquals("bash", part.tool)
        assertEquals("completed", part.state?.status)
        assertEquals("file.txt", part.state?.output)
    }

    @Test
    fun parsesMessageWithPartsEnvelope() {
        val raw = """
            {"info":{"id":"msg_1","sessionID":"ses_1","role":"user","agent":"build",
             "model":{"providerID":"x","modelID":"y"},"time":{"created":1,"updated":1}},
             "parts":[{"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"text","text":"hi"}]}
        """.trimIndent()

        val message = AppJson.decodeFromString(MessageWithParts.serializer(), raw)

        assertEquals("user", message.info.role)
        assertEquals("build", message.info.agent)
        assertEquals("x", message.info.model?.providerID)
        assertNotNull(message.parts.single().text)
        assertEquals("hi", message.parts.single().text)
    }
}
