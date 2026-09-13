package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Model
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.ProviderList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultModelResolutionTest {

    private fun provider(id: String, vararg models: String) = Provider(
        id = id,
        name = id,
        models = models.associateWith { Model(id = it, name = it) },
    )

    @Test
    fun usesServerDeclaredDefault() {
        val list = ProviderList(
            all = listOf(provider("anthropic", "claude")),
            default = mapOf("anthropic" to "claude"),
            connected = listOf("anthropic"),
        )

        assertEquals(PromptModel("anthropic", "claude"), resolveDefaultModel(list))
    }

    @Test
    fun fallsBackToConnectedProvider() {
        val list = ProviderList(
            all = listOf(provider("a", "m1"), provider("b", "m2")),
            connected = listOf("b"),
        )

        assertEquals(PromptModel("b", "m2"), resolveDefaultModel(list))
    }

    @Test
    fun fallsBackToAnyProviderWithModels() {
        val list = ProviderList(
            all = listOf(provider("a", "m1"), provider("b", "m2")),
        )

        assertEquals(PromptModel("a", "m1"), resolveDefaultModel(list))
    }

    @Test
    fun returnsNullWhenNoModelsExist() {
        assertNull(resolveDefaultModel(ProviderList()))
    }

    @Test
    fun skipsProvidersWithBlankModelIds() {
        val list = ProviderList(
            all = listOf(provider("subconscious", "")),
            connected = listOf("subconscious"),
        )

        assertNull(resolveDefaultModel(list))
    }

    @Test
    fun fallsBackToModelIdWhenMapKeyIsBlank() {
        val provider = Provider(
            id = "p",
            name = "p",
            models = mapOf("" to Model(id = "m1", name = "m1")),
        )

        assertEquals(
            PromptModel("p", "m1"),
            resolveDefaultModel(ProviderList(all = listOf(provider), connected = listOf("p"))),
        )
    }
}
