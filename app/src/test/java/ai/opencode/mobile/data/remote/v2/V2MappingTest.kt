package ai.opencode.mobile.data.remote.v2

import ai.opencode.mobile.data.remote.OpenCodeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2 answers, as opencode 2.1.2 sends them, in the v1 shapes the app shows. */
class V2MappingTest {

    private fun items(json: String) = (OpenCodeJson.parseToJsonElement(json) as JsonArray).map { it as JsonObject }

    @Test
    fun theHistoryUsesThePartIdsOfTheLiveEvents() {
        val history = v2MessagesToV1(
            "ses_1",
            items(
                """[
                {"id":"msg_u","time":{"created":1},"text":"Read README.md","type":"user"},
                {"id":"msg_a","time":{"created":2,"completed":3},"type":"assistant","agent":"build","model":{"id":"flint","providerID":"opencode"},
                 "content":[{"type":"reasoning","text":"Let me look."},{"type":"tool","id":"call_1","name":"glob","state":{"status":"completed","input":{"pattern":"**/README.md"},"content":[{"type":"text","text":"/work/proj/README.md"}],"metadata":{"count":1}},"time":{"created":2}},{"type":"text","text":"Done."},{"type":"text","text":"Really."}],
                 "finish":"stop","cost":0,"tokens":{"input":10,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}},
                {"id":"msg_m","time":{"created":4},"type":"model-switched","model":{"id":"x","providerID":"y"}},
                {"id":"msg_i","time":{"created":5},"type":"idle","outcome":"succeeded"}
                ]""",
            ),
        )

        assertEquals(listOf("msg_u", "msg_a"), history.map { it.info.id })
        assertEquals(listOf("msg_u:text"), history[0].parts.map { it.id })
        assertEquals(
            listOf("msg_a:reasoning:0", "msg_a:tool:call_1", "msg_a:text:0", "msg_a:text:1", "msg_a:finish"),
            history[1].parts.map { it.id },
        )
        val tool = history[1].parts[1].state!!
        assertEquals("completed", tool.status)
        assertEquals("/work/proj/README.md", tool.output)
        assertEquals("flint", history[1].info.modelID)
        assertEquals("opencode", history[1].info.providerID)
    }

    @Test
    fun aFailureOnlyTheIdleMarkerRecordsIsShown() {
        val history = v2MessagesToV1(
            "ses_1",
            items(
                """[
                {"id":"msg_u","time":{"created":1},"text":"hi","type":"user"},
                {"id":"msg_i","time":{"created":2},"type":"idle","outcome":"failed","error":{"type":"model.not_found","message":"No such model"}}
                ]""",
            ),
        )
        val failure = history.last()
        assertEquals("msg_i", failure.info.id)
        assertEquals("model.not_found", (failure.info.error!!["name"] as JsonPrimitive).content)

        // When the step already shows the error, the marker adds nothing.
        val shown = v2MessagesToV1(
            "ses_1",
            items(
                """[
                {"id":"msg_u","time":{"created":1},"text":"hi","type":"user"},
                {"id":"msg_a","time":{"created":2},"type":"assistant","agent":"build","model":{"id":"m","providerID":"p"},"content":[],"finish":"error","error":{"type":"provider.internal","message":"down"}},
                {"id":"msg_i","time":{"created":3},"type":"idle","outcome":"failed","error":{"type":"provider.internal","message":"down"}}
                ]""",
            ),
        )
        assertEquals(listOf("msg_u", "msg_a"), shown.map { it.info.id })
    }

    @Test
    fun aCompactionIsAMarkerAndItsSummary() {
        val history = v2MessagesToV1(
            "ses_1",
            items("""[{"id":"msg_c","time":{"created":1},"type":"compaction","status":"completed","reason":"manual","summary":"All done so far."}]"""),
        )
        assertEquals(listOf("msg_c", "msg_c:summary"), history.map { it.info.id })
        assertEquals("compaction", history[0].parts.single().type)
        assertEquals("msg_c", history[1].info.parentID)
        assertEquals("stop", history[1].info.finish)
        assertEquals("All done so far.", history[1].parts.single().text)
    }

    @Test
    fun sessionsProvidersAndFiles() {
        val session = (OpenCodeJson.parseToJsonElement(
            """{"id":"ses_1","projectID":"global","cost":0.5,"tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":10,"updated":20},"title":"Hello","model":{"id":"m","providerID":"p","variant":"high"},"revert":{"messageID":"msg_u"},"location":{"directory":"/work/proj"}}""",
        ) as JsonObject).toV1Session()!!
        assertEquals("/work/proj", session.directory)
        assertEquals("Hello", session.title)
        assertEquals("high", session.model?.variant)
        assertEquals("msg_u", session.revert?.messageID)
        assertEquals(20L, session.time?.updated)

        val providers = v2ConfigProviders(
            items("""[{"id":"opencode","name":"OpenCode Zen","activation":"auto","package":"x"}]"""),
            items(
                """[
                {"id":"flint","modelID":"flint","providerID":"opencode","name":"Flint","variants":[{"id":"low"},{"id":"high"}],"enabled":true,"status":"active","limit":{"context":1000,"output":100}},
                {"id":"off","modelID":"off","providerID":"opencode","name":"Off","variants":[],"enabled":false,"status":"active","limit":{"context":1,"output":1}}
                ]""",
            ),
            OpenCodeJson.parseToJsonElement("""{"id":"flint","providerID":"opencode"}""") as JsonObject,
        )
        val provider = providers.providers.single()
        assertEquals("OpenCode Zen", provider.name)
        assertEquals(setOf("flint"), provider.models.keys)
        assertEquals(setOf("low", "high"), provider.models["flint"]!!.variants.keys)
        assertEquals(1000L, provider.models["flint"]!!.limit?.context)
        assertEquals(mapOf("opencode" to "flint"), providers.default)

        val directory = (OpenCodeJson.parseToJsonElement("""{"path":"src/sub/","type":"directory"}""") as JsonObject).toFileNode("/work/proj")!!
        assertEquals("sub", directory.name)
        assertEquals("src/sub", directory.path)
        assertEquals("/work/proj/src/sub", directory.absolute)
        assertEquals("directory", directory.type)
    }

    @Test
    fun textAndBinaryFiles() {
        assertEquals("fun main() {}\n", V2Backend.textOf("fun main() {}\n".toByteArray()))
        assertNull(V2Backend.textOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0)))
        assertEquals("/home/alice", V2Backend.homeOf("/home/alice/projects/app"))
        assertEquals("/Users/bob", V2Backend.homeOf("/Users/bob"))
        assertTrue(V2Backend.homeOf("/srv/app") == "/srv/app")
    }
}
