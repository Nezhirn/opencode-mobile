package ai.opencode.mobile.data.local

import ai.opencode.mobile.data.remote.OpenCodeClient
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

// A corrupt file (interrupted write, full disk) is replaced instead of failing
// every read: the reads run in the repository's scope, and an exception there
// crashed the app on every start.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "opencode_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class ConnectionSettings(
    val baseUrl: String = "",
    val username: String = "opencode",
    val password: String = "",
    val allowInsecureTls: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** Redacts the password so accidental logging cannot leak it. */
    override fun toString(): String =
        "ConnectionSettings(baseUrl=$baseUrl, username=$username, password=***, allowInsecureTls=$allowInsecureTls)"
}

class SettingsStore(private val context: Context) : SettingsSource {

    private val cipher = SecretCipher()

    /** Preferences, or empty ones when the file cannot be read (see [dataStore]). */
    private val data: Flow<Preferences> = context.dataStore.data.catch { error ->
        if (error !is IOException) throw error
        Log.w(TAG, "settings could not be read", error)
        emit(emptyPreferences())
    }

    override val settings: Flow<ConnectionSettings> = data.map { prefs ->
        ConnectionSettings(
            baseUrl = prefs[KEY_BASE_URL].orEmpty(),
            username = prefs[KEY_USERNAME] ?: DEFAULT_USERNAME,
            password = readPassword(prefs[KEY_PASSWORD]),
            allowInsecureTls = prefs[KEY_INSECURE_TLS] ?: false,
        )
    }

    /**
     * Decrypts the stored password. Values that fail to decrypt are treated as
     * legacy plaintext (written before encryption was introduced) and returned
     * as-is; they get encrypted on the next save. A versioned value that fails to
     * decrypt (the Keystore key is gone) is ciphertext, not a password: it reads
     * as empty so the field asks for re-entry instead of offering the ciphertext,
     * which the next save would have stored as the "password".
     */
    private fun readPassword(stored: String?): String {
        val raw = stored.orEmpty()
        if (raw.isEmpty()) return ""
        cipher.decrypt(raw)?.let { return it }
        if (raw.startsWith(SecretCipher.PREFIX)) {
            Log.w(TAG, "Stored password could not be decrypted; re-entry required")
            return ""
        }
        return raw
    }

    override suspend fun save(settings: ConnectionSettings) {
        // DataStore runs edit {} in the caller's context, which is the main
        // thread for a ViewModel: Keystore work (key generation on first use)
        // must happen before, off it.
        val password = withContext(Dispatchers.Default) { cipher.encrypt(settings.password) }
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = settings.baseUrl
            prefs[KEY_USERNAME] = settings.username
            prefs[KEY_PASSWORD] = password
            prefs[KEY_INSECURE_TLS] = settings.allowInsecureTls
        }
    }

    /**
     * The pick is kept per server (model ids of one server mean nothing on
     * another; a global pick was erased by every switch). Picks stored before
     * that, under the global keys, are the fallback.
     */
    override val modelSelection: Flow<ModelSelection> = data.map { prefs ->
        val keys = selectionKeys(prefs)
        if (keys.any { prefs[it] != null }) {
            ModelSelection(
                providerId = prefs[keys[0]].orEmpty(),
                modelId = prefs[keys[1]].orEmpty(),
                agent = prefs[keys[2]].orEmpty(),
            )
        } else {
            ModelSelection(
                providerId = prefs[KEY_MODEL_PROVIDER].orEmpty(),
                modelId = prefs[KEY_MODEL_ID].orEmpty(),
                agent = prefs[KEY_AGENT].orEmpty(),
            )
        }
    }

    override suspend fun saveModelSelection(selection: ModelSelection) {
        context.dataStore.edit { prefs ->
            val keys = selectionKeys(prefs)
            prefs[keys[0]] = selection.providerId
            prefs[keys[1]] = selection.modelId
            prefs[keys[2]] = selection.agent
        }
    }

    /** Provider, model and agent keys of the server the settings point at. */
    private fun selectionKeys(prefs: Preferences): List<Preferences.Key<String>> {
        val server = OpenCodeClient.normalizeBaseUrl(prefs[KEY_BASE_URL].orEmpty())
        return listOf(KEY_MODEL_PROVIDER, KEY_MODEL_ID, KEY_AGENT).map { stringPreferencesKey("${it.name}@$server") }
    }

    override fun enabledModels(serverUrl: String): Flow<Set<String>?> =
        data.map { prefs -> prefs[enabledModelsKey(serverUrl)] }

    override suspend fun saveEnabledModels(serverUrl: String, models: Set<String>?) {
        context.dataStore.edit { prefs ->
            val key = enabledModelsKey(serverUrl)
            // Absent means "not customised"; an empty set is a real choice.
            if (models == null) prefs.remove(key) else prefs[key] = models
        }
    }

    private fun enabledModelsKey(serverUrl: String) = stringSetPreferencesKey("enabled_models:$serverUrl")

    override val modelVariants: Flow<Map<String, String>> = data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (key, value) ->
            val modelKey = key.name.removePrefix(VARIANT_PREFIX)
            if (modelKey != key.name && value is String && value.isNotBlank()) modelKey to value else null
        }.toMap()
    }

    override suspend fun saveModelVariant(modelKey: String, variant: String?) {
        context.dataStore.edit { prefs ->
            val key = stringPreferencesKey(VARIANT_PREFIX + modelKey)
            if (variant.isNullOrBlank()) prefs.remove(key) else prefs[key] = variant
        }
    }

    private companion object {
        const val TAG = "SettingsStore"
        const val DEFAULT_USERNAME = "opencode"
        const val VARIANT_PREFIX = "variant:"
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_PASSWORD = stringPreferencesKey("password")
        val KEY_INSECURE_TLS = booleanPreferencesKey("allow_insecure_tls")
        val KEY_MODEL_PROVIDER = stringPreferencesKey("selected_provider_id")
        val KEY_MODEL_ID = stringPreferencesKey("selected_model_id")
        val KEY_AGENT = stringPreferencesKey("selected_agent")
    }
}
