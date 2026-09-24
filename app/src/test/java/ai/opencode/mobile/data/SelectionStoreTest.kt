package ai.opencode.mobile.data

import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.Model
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.ProviderList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionStoreTest {

    private class FakeSettingsSource : SettingsSource {
        override val settings: Flow<ConnectionSettings> = MutableStateFlow(ConnectionSettings())
        override suspend fun save(settings: ConnectionSettings) = Unit
        val selection = MutableStateFlow(ModelSelection())
        override val modelSelection: Flow<ModelSelection> = selection
        override suspend fun saveModelSelection(selection: ModelSelection) {
            this.selection.value = selection
        }

        val enabled = MutableStateFlow<Map<String, Set<String>?>>(emptyMap())
        override fun enabledModels(serverUrl: String): Flow<Set<String>?> = enabled.map { it[serverUrl] }
        override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) {
            enabled.value = enabled.value + (serverUrl to models)
        }
    }

    private val settings = FakeSettingsSource()

    private fun store() = SelectionStore(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), settings)

    private fun provider(id: String, vararg models: String) =
        Provider(id = id, name = id, models = models.associateWith { Model(id = it, name = it) })

    /** A store connected to [url] with openrouter (3 models) and runware (1). */
    private fun connectedStore(url: String = "http://a") = store().apply {
        restore()
        bindServer(url)
        runBlocking {
            applyProviders(
                ProviderList(
                    all = listOf(provider("openrouter", "x/a", "x/b", "x/c"), provider("runware", "glm")),
                    connected = listOf("openrouter", "runware"),
                ),
            )
        }
    }

    @Test
    fun agentMissingFromServerIsClearedWithNotice() {
        val store = store()
        store.selectAgent("reviewer")

        store.applyAgents(listOf(Agent(name = "build"), Agent(name = "plan")))

        assertNull(store.selectedAgent.value)
        assertTrue(store.notice.value.orEmpty().contains("reviewer"))
    }

    @Test
    fun availableAgentIsKept() {
        val store = store()
        store.selectAgent("plan")

        store.applyAgents(listOf(Agent(name = "build"), Agent(name = "plan")))

        assertEquals("plan", store.selectedAgent.value)
        assertNull(store.notice.value)
    }

    @Test
    fun hiddenAgentsAreNotOffered() {
        val store = store()

        store.applyAgents(listOf(Agent(name = "build"), Agent(name = "title", hidden = true)))

        assertEquals(listOf("build"), store.agents.value.map { it.name })
    }

    @Test
    fun sendingWithoutModelIsRejected() {
        assertTrue(store().checkSendable() is SendSelection.Rejected)
    }

    @Test
    fun sendingCarriesModelAndAgent() {
        val store = store()
        store.selectModel(PromptModel("anthropic", "claude"))
        store.selectAgent("plan")

        val ready = store.checkSendable() as SendSelection.Ready

        assertEquals(PromptModel("anthropic", "claude"), ready.model)
        assertEquals("plan", ready.agent)
    }

    @Test
    fun everythingIsVisibleUntilCustomised() {
        assertNull(connectedStore().enabledModels.value)
        assertTrue(isModelVisible("openrouter/x/a", null, null))
    }

    @Test
    fun hidingOneModelKeepsTheRestVisible() {
        val store = connectedStore()

        store.setModelEnabled(PromptModel("openrouter", "x/b"), false)

        assertEquals(setOf("openrouter/x/a", "openrouter/x/c", "runware/glm"), store.enabledModels.value)
        assertEquals(store.enabledModels.value, settings.enabled.value["http://a"])
    }

    @Test
    fun hidingAProviderAndEnablingOneModelBack() {
        val store = connectedStore()

        store.setProviderEnabled("openrouter", false)
        store.setModelEnabled(PromptModel("openrouter", "x/c"), true)

        assertEquals(setOf("runware/glm", "openrouter/x/c"), store.enabledModels.value)
    }

    @Test
    fun selectedModelStaysVisibleEvenWhenHidden() {
        assertTrue(isModelVisible("openrouter/x/a", enabled = emptySet(), selectedKey = "openrouter/x/a"))
        assertFalse(isModelVisible("openrouter/x/b", enabled = emptySet(), selectedKey = "openrouter/x/a"))
    }

    @Test
    fun showAllForgetsTheCustomisation() {
        val store = connectedStore()
        store.setProviderEnabled("openrouter", false)

        store.showAllModels()

        assertNull(store.enabledModels.value)
        assertNull(settings.enabled.value["http://a"])
    }

    @Test
    fun visibilityIsKeptPerServer() {
        connectedStore("http://a").setProviderEnabled("openrouter", false)

        val other = connectedStore("http://b")

        assertNull(other.enabledModels.value)
        assertEquals(setOf("runware/glm"), connectedStore("http://a").enabledModels.value)
    }
}
