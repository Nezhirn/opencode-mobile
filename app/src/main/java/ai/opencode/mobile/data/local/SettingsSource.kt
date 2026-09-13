package ai.opencode.mobile.data.local

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over persisted connection settings. It exists so [ai.opencode.mobile.data.AppRepository]
 * can be exercised in JVM tests with a fake source instead of DataStore.
 */
interface SettingsSource {
    val settings: Flow<ConnectionSettings>
    suspend fun save(settings: ConnectionSettings)
}
