package ai.opencode.mobile.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Project scoping and the rollback endpoints. */
class OpenCodeClientProjectsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(directory: String? = null) = OpenCodeClient(server.url("/").toString(), directory = directory)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun instanceRequestsCarryTheProjectDirectory() = runBlocking {
        server.enqueue(json("[]"))

        client("/home/me/app").listSessions()

        assertEquals("/session?directory=%2Fhome%2Fme%2Fapp", server.takeRequest().path)
    }

    @Test
    fun noDirectoryWithoutAProject() = runBlocking {
        server.enqueue(json("[]"))

        client().listSessions()

        assertEquals("/session", server.takeRequest().path)
    }

    @Test
    fun globalAndApiRoutesAreNotScoped() = runBlocking {
        server.enqueue(json("""{"healthy":true}"""))
        server.enqueue(json("""{"data":[]}"""))
        val client = client("/home/me/app")

        client.health()
        client.listAllSessions()

        assertEquals("/global/health", server.takeRequest().path)
        assertEquals("/api/session?limit=5000&order=desc", server.takeRequest().path)
    }

    @Test
    fun explicitDirectoryWinsOverTheProject() = runBlocking {
        server.enqueue(json("[]"))

        client("/home/me/app").listFilesIn("/home/me", "")

        val path = server.takeRequest().path.orEmpty()
        assertEquals("/file?path=&directory=%2Fhome%2Fme", path)
    }

    @Test
    fun findDirectoriesSearchesFoldersUnderTheRoot() = runBlocking {
        server.enqueue(json("""["code/app/"]"""))

        val found = client("/home/me/app").findDirectories("app", "/home/me")

        assertEquals(listOf("code/app/"), found)
        val path = server.takeRequest().requestUrl!!
        assertEquals("app", path.queryParameter("query"))
        assertEquals("true", path.queryParameter("dirs"))
        assertEquals("/home/me", path.queryParameter("directory"))
        assertEquals(1, path.queryParameterValues("directory").size)
    }

    @Test
    fun revertPostsTheMessageAndReturnsTheSession() = runBlocking {
        server.enqueue(json("""{"id":"ses_1","revert":{"messageID":"msg_2","snapshot":"abc"}}"""))

        val session = client("/p").revert("ses_1", "msg_2")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/ses_1/revert?directory=%2Fp", request.path)
        assertEquals("""{"messageID":"msg_2"}""", request.body.readUtf8())
        assertEquals("msg_2", session.revert?.messageID)
    }

    @Test
    fun unrevertPostsAnEmptyBody() = runBlocking {
        server.enqueue(json("""{"id":"ses_1"}"""))

        val session = client().unrevert("ses_1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/ses_1/unrevert", request.path)
        assertEquals(0L, request.bodySize)
        assertEquals(null, session.revert)
    }

    @Test
    fun projectsAndAllSessionsParse() = runBlocking {
        // Shapes of opencode 1.18.35.
        server.enqueue(
            json(
                """[{"id":"global","worktree":"/","time":{"created":1,"updated":2},"sandboxes":[]},
                {"id":"5c25","worktree":"/home/me/app","vcs":"git","icon":{"color":"mint"},"time":{"created":1,"updated":2},"sandboxes":[]}]""",
            ),
        )
        server.enqueue(
            json(
                """{"data":[{"id":"ses_1","projectID":"global","title":"Hi","time":{"created":1,"updated":5},
                "location":{"directory":"/home/me"},"subpath":"home/me"}],"cursor":null}""",
            ),
        )
        val client = client()

        val projects = client.listProjects()
        val sessions = client.listAllSessions()

        assertEquals("mint", projects[1].icon?.color)
        assertEquals("git", projects[1].vcs)
        assertEquals("/home/me", sessions.single().location?.directory)
        assertTrue(sessions.single().parentID == null)
    }
}
