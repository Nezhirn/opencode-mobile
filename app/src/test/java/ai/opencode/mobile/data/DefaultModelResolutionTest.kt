package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Model
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.ProviderList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun ignoresCatalogProvidersThatAreNotConfigured() {
        // `all` is the full models.dev catalog; nothing in it is usable until the
        // server reports the provider as connected or declares a default for it.
        val list = ProviderList(
            all = listOf(provider("a", "m1"), provider("b", "m2")),
        )

        assertNull(resolveDefaultModel(list))
        assertTrue(configuredProviders(list).isEmpty())
    }

    @Test
    fun declaredDefaultDoesNotMakeAProviderConfigured() {
        // /provider declares a default for every catalogue provider, so a default
        // alone must never put a provider into the picker.
        val list = ProviderList(
            all = listOf(provider("a", "m1"), provider("b", "m2")),
            default = mapOf("a" to "m1", "b" to "m2"),
        )

        assertTrue(configuredProviders(list).isEmpty())
        assertNull(resolveDefaultModel(list))
    }

    @Test
    fun onlyConnectedProvidersAreOfferedFromTheCatalogue() {
        val list = ProviderList(
            all = listOf(provider("a", "m1"), provider("b", "m2"), provider("c", "m3")),
            default = mapOf("a" to "m1", "b" to "m2", "c" to "m3"),
            connected = listOf("b"),
        )

        assertEquals(listOf("b"), configuredProviders(list).map { it.id })
        assertEquals(PromptModel("b", "m2"), resolveDefaultModel(list))
    }

    @Test
    fun declaredDefaultOutsideTheModelMapIsNotUsed() {
        val list = ProviderList(
            all = listOf(provider("a", "m1")),
            default = mapOf("a" to "gone"),
            connected = listOf("a"),
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

    @Test
    fun offersOnlyMatchesConfiguredModels() {
        val configured = listOf(provider("a", "m1", "m2"))

        assertTrue(configured.offers(PromptModel("a", "m2")))
        assertFalse(configured.offers(PromptModel("a", "m3")))
        assertFalse(configured.offers(PromptModel("b", "m1")))
    }
}
