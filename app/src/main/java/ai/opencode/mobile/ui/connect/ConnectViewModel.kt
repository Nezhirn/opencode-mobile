package ai.opencode.mobile.ui.connect

import ai.opencode.mobile.R
import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.UiText
import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.toUiText
import ai.opencode.mobile.data.uiText
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What is typed into the connection form. */
data class ConnectForm(
    val baseUrl: String = "",
    val username: String = "opencode",
    val password: String = "",
    val allowInsecureTls: Boolean = false,
) {
    override fun toString(): String = "ConnectForm(baseUrl=$baseUrl, username=$username, password=***)"
}

class ConnectViewModel(private val repository: AppRepository) : ViewModel() {

    val connection = repository.connection

    /**
     * The form lives here rather than in composition state: switching the app
     * language recreates the activity, and the password (deliberately not put
     * into saved instance state) was silently cleared, so Connect then stored an
     * empty one. The ViewModel survives the recreation.
     */
    private val _form = MutableStateFlow(ConnectForm())
    val form = _form.asStateFlow()
    private var edited = false

    init {
        viewModelScope.launch {
            repository.settingsLoaded.first { it }
            val stored = repository.settings.value
            if (!edited && stored.baseUrl.isNotEmpty()) {
                _form.value = ConnectForm(stored.baseUrl, stored.username, stored.password, stored.allowInsecureTls)
            }
        }
    }

    fun updateForm(transform: (ConnectForm) -> ConnectForm) {
        edited = true
        _form.update(transform)
    }

    private val _saveError = MutableStateFlow<UiText?>(null)
    val saveError = _saveError.asStateFlow()

    fun save() {
        val (baseUrl, username, password, allowInsecureTls) = _form.value
        // Caught here with a clear message instead of as an obscure connection
        // error later (spaces in the host, a stray scheme, an empty field).
        if (OpenCodeClient.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()?.host.isNullOrBlank()) {
            _saveError.value = uiText(R.string.error_server_url_invalid)
            return
        }
        viewModelScope.launch {
            _saveError.value = null
            runCatching {
                repository.saveSettings(
                    ConnectionSettings(
                        baseUrl = baseUrl.trim(),
                        username = username.trim().ifEmpty { "opencode" },
                        password = password,
                        allowInsecureTls = allowInsecureTls,
                    ),
                )
            }.onSuccess {
                // Saving identical settings emits nothing new (StateFlow dedupes),
                // so explicitly restart the connection attempt; otherwise Connect
                // looks like a no-op.
                repository.reconnect()
            }.onFailure {
                _saveError.value = it.toUiText(R.string.error_save_settings)
            }
        }
    }

    fun refresh() = repository.refresh()

    companion object {
        val Factory = viewModelFactory {
            initializer { ConnectViewModel(repository()) }
        }
    }
}
