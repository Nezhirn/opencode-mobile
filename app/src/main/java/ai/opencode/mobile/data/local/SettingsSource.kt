package ai.opencode.mobile.data.local

import kotlinx.coroutines.flow.Flow

/**
 * The model and agent the user picked explicitly. Blank ids mean "not chosen";
 * a value is only ever written from a deliberate pick, never from a server
 * default, so restoring it cannot silently change what a prompt is sent with.
 */
data class ModelSelection(
    val providerId: String = "",
    val modelId: String = "",
    val agent: String = "",
) {
    val hasModel: Boolean get() = providerId.isNotBlank() && modelId.isNotBlank()
}

/**
 * Abstraction over persisted settings. It exists so [ai.opencode.mobile.data.AppRepository]
 * can be exercised in JVM tests with a fake source instead of DataStore.
 */
interface SettingsSource {
    val settings: Flow<ConnectionSettings>
    suspend fun save(settings: ConnectionSettings)

    val modelSelection: Flow<ModelSelection>
    suspend fun saveModelSelection(selection: ModelSelection)

    /**
     * Models shown in the picker for the server at [serverUrl], as
     * "providerId/modelId" keys; null until the user customises the list (then
     * every configured model is shown). Kept per server: model ids of one server
     * mean nothing on another.
     */
    fun enabledModels(serverUrl: String): Flow<Set<String>?>
    suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?)
}
