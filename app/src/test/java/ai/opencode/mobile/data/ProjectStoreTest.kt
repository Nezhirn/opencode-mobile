package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.Project
import ai.opencode.mobile.data.remote.ProjectIcon
import ai.opencode.mobile.data.remote.SessionLocation
import ai.opencode.mobile.data.remote.SessionSummary
import ai.opencode.mobile.data.remote.SessionTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectStoreTest {

    private fun session(id: String, project: String, directory: String, updated: Long, parent: String? = null) =
        SessionSummary(
            id = id,
            projectID = project,
            parentID = parent,
            time = SessionTime(created = 1, updated = updated),
            location = SessionLocation(directory),
        )

    private val git = Project(id = "abc", worktree = "/home/me/app", vcs = "git", icon = ProjectIcon(color = "mint"))
    private val global = Project(id = GLOBAL_PROJECT_ID, worktree = "/")

    @Test
    fun gitProjectsCollectAllTheirSessionsWhateverTheSubdirectory() {
        val result = mergeProjects(
            projects = listOf(global, git),
            sessions = listOf(
                session("s1", "abc", "/home/me/app", 10),
                session("s2", "abc", "/home/me/app/server", 30),
            ),
            saved = emptySet(),
            hidden = emptySet(),
        )

        val project = result.single()
        assertEquals("/home/me/app", project.directory)
        assertEquals("app", project.name)
        assertEquals(2, project.sessionCount)
        assertEquals(30, project.lastUpdated)
        assertEquals("mint", project.color)
        assertTrue(project.isGit)
    }

    @Test
    fun globalSessionsAreGroupedByDirectoryNewestFirst() {
        val result = mergeProjects(
            projects = listOf(global),
            sessions = listOf(
                session("s1", GLOBAL_PROJECT_ID, "/home/me", 10),
                session("s2", GLOBAL_PROJECT_ID, "/home/me/bot", 50),
                session("s3", GLOBAL_PROJECT_ID, "/home/me/", 20),
                session("child", GLOBAL_PROJECT_ID, "/home/me/other", 99, parent = "s1"),
            ),
            saved = emptySet(),
            hidden = emptySet(),
        )

        assertEquals(listOf("/home/me/bot", "/home/me"), result.map { it.directory })
        assertEquals(2, result[1].sessionCount)
        assertFalse(result[0].isGit)
    }

    @Test
    fun savedDirectoriesAppearWithoutSessionsAndHiddenOnesDisappear() {
        val result = mergeProjects(
            projects = listOf(global, git),
            sessions = listOf(session("s1", GLOBAL_PROJECT_ID, "/home/me/bot", 5)),
            saved = setOf("/srv/new/"),
            hidden = setOf("/home/me/app"),
        )

        assertEquals(listOf("/home/me/bot", "/srv/new"), result.map { it.directory })
        assertEquals(0, result[1].sessionCount)
        assertEquals("new", result[1].name)
    }

    @Test
    fun serversWithoutTheApiRoutesFallBackToTheSessionList() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path.orEmpty().substringBefore('?')) {
                "/project" -> MockResponse().setBody("""[{"id":"global","worktree":"/"}]""")
                "/session" -> MockResponse().setBody(
                    """[{"id":"s1","projectID":"global","directory":"/home/me/bot","time":{"created":1,"updated":7}}]""",
                )
                else -> MockResponse().setResponseCode(404).setBody("Not Found")
            }
        }
        server.start()
        try {
            val store = ProjectStore(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), FakeSettings())
            store.bindServer(server.url("/").toString())

            store.load(OpenCodeClient(server.url("/").toString())) { true }

            val projects = withTimeout(5_000) { store.projects.first { it.isNotEmpty() } }
            assertEquals("/home/me/bot", projects.single().directory)
            assertTrue(store.loaded.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun directoryHelpers() {
        assertEquals("/", normalizeDirectory("/"))
        assertEquals("/a/b", normalizeDirectory("/a/b//"))
        assertEquals("b", directoryName("/a/b/"))
        assertEquals("/", directoryName("/"))
    }

    private class FakeSettings : SettingsSource {
        override val settings: Flow<ConnectionSettings> = MutableStateFlow(ConnectionSettings())
        override suspend fun save(settings: ConnectionSettings) = Unit
        override val modelSelection: Flow<ModelSelection> = MutableStateFlow(ModelSelection())
        override suspend fun saveModelSelection(selection: ModelSelection) = Unit
        override fun enabledModels(serverUrl: String): Flow<Set<String>?> = MutableStateFlow(null)
        override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) = Unit
        override val modelVariants: Flow<Map<String, String>> = MutableStateFlow(emptyMap())
        override suspend fun saveModelVariant(modelKey: String, variant: String?) = Unit
    }
}
