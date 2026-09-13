package ai.opencode.mobile.ui.connect

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.local.ConnectionSettings
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch

class ConnectViewModel(private val repository: AppRepository) : ViewModel() {

    val settings = repository.settings
    val connection = repository.connection

    fun save(baseUrl: String, username: String, password: String, allowInsecureTls: Boolean) {
        viewModelScope.launch {
            repository.saveSettings(
                ConnectionSettings(
                    baseUrl = baseUrl.trim(),
                    username = username.trim().ifEmpty { "opencode" },
                    password = password,
                    allowInsecureTls = allowInsecureTls,
                ),
            )
        }
    }

    fun refresh() = repository.refresh()

    companion object {
        val Factory = viewModelFactory {
            initializer { ConnectViewModel(repository()) }
        }
    }
}
