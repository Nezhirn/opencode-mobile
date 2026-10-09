package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Opening, remembering and hiding projects. */
class AppRepositoryProjectsTest {

    private class FakeSettings(baseUrl: String, lastProject: String? = null) : SettingsSource {
        override val settings: Flow<ConnectionSettings> = MutableStateFlow(ConnectionSettings(baseUrl = baseUrl))
        override suspend fun save(settings: ConnectionSettings) = Unit
        override val modelSelection: Flow<ModelSelection> = MutableStateFlow(ModelSelection())
        override suspend fun saveModelSelection(selection: ModelSelection) = Unit
        override fun enabledModels(serverUrl: String): Flow<Set<String>?> = MutableStateFlow(null)
        override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) = Unit
        override val modelVariants: Flow<Map<String, String>> = MutableStateFlow(emptyMap())
        override suspend fun saveModelVariant(modelKey: String, variant: String?) = Unit

        val last = MutableStateFlow(if (lastProject == null) emptyMap() else mapOf(baseUrl to lastProject))
        override fun lastProject(serverUrl: String): Flow<String?> = last.map { it[serverUrl] }
        override suspend fun saveLastProject(serverUrl: String, directory: String?) {
            // Writes that take a while are where unordered launches overtook each other.
            delay(if (directory == "/a") 50 else 0)
            last.value = if (directory == null) last.value - serverUrl else last.value + (serverUrl to directory)
        }
    }

    private val url = "http://127.0.0.1:1"

    @Test
    fun theLastProjectIsRestoredBeforeConnecting() = runBlocking {
        val repository = AppRepository(FakeSettings(url, lastProject = "/home/me/app"))

        withTimeout(5_000) { repository.settingsLoaded.first { it } }

        assertEquals("/home/me/app", repository.currentDirectory.first { it != null })
    }

    @Test
    fun theProjectOpenedLastIsTheOneRemembered() = runBlocking {
        val settings = FakeSettings(url)
        val repository = AppRepository(settings)
        withTimeout(5_000) { repository.settingsLoaded.first { it } }
        withTimeout(5_000) { repository.settings.first { it.isConfigured } }

        repository.openProject("/a/")
        repository.openProject("/b")

        withTimeout(5_000) { settings.last.first { it[url] == "/b" } }
        delay(100)
        assertEquals("/b", settings.last.value[url])
        assertEquals("/b", repository.currentDirectory.value)
    }

    @Test
    fun hidingTheOpenProjectLeavesIt() = runBlocking {
        val settings = FakeSettings(url)
        val repository = AppRepository(settings)
        withTimeout(5_000) { repository.settings.first { it.isConfigured } }
        repository.openProject("/b")
        withTimeout(5_000) { settings.last.first { it[url] == "/b" } }

        repository.hideProject("/b/")

        withTimeout(5_000) { settings.last.first { it[url] == null } }
        assertNull(repository.currentDirectory.first { it == null })
    }
}
