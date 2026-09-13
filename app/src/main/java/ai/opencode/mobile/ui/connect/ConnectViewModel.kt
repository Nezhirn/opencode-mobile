package ai.opencode.mobile.ui.connect

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectViewModel(private val repository: AppRepository) : ViewModel() {

    val settings = repository.settings
    val connection = repository.connection

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError = _saveError.asStateFlow()

    fun save(baseUrl: String, username: String, password: String, allowInsecureTls: Boolean) {
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
                _saveError.value = it.message ?: "Failed to save settings"
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
