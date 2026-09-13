package ai.opencode.mobile.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "opencode_settings")

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

    override val settings: Flow<ConnectionSettings> = context.dataStore.data.map { prefs ->
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
     * decrypt is reported so the user knows to re-enter it.
     */
    private fun readPassword(stored: String?): String {
        val raw = stored.orEmpty()
        if (raw.isEmpty()) return ""
        cipher.decrypt(raw)?.let { return it }
        if (raw.startsWith(SecretCipher.PREFIX)) {
            Log.w(TAG, "Stored password could not be decrypted; re-entry required")
        }
        return raw
    }

    override suspend fun save(settings: ConnectionSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = settings.baseUrl
            prefs[KEY_USERNAME] = settings.username
            prefs[KEY_PASSWORD] = cipher.encrypt(settings.password)
            prefs[KEY_INSECURE_TLS] = settings.allowInsecureTls
        }
    }

    private companion object {
        const val TAG = "SettingsStore"
        const val DEFAULT_USERNAME = "opencode"
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_PASSWORD = stringPreferencesKey("password")
        val KEY_INSECURE_TLS = booleanPreferencesKey("allow_insecure_tls")
    }
}
