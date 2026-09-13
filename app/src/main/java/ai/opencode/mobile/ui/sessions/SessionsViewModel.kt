package ai.opencode.mobile.ui.sessions

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionsViewModel(private val repository: AppRepository) : ViewModel() {

    val sessions = repository.sessions
    val connection = repository.connection
    val permissions = repository.permissions
    val questions = repository.questions

    private val _search = MutableStateFlow("")
    val search = _search.asStateFlow()

    fun onSearchChange(value: String) {
        _search.value = value
    }

    fun refresh() = repository.refresh()

    fun createSession() = repository.createSession()

    fun deleteSession(sessionId: String) = repository.deleteSession(sessionId)

    init {
        // Ensure the list is populated when opening the app on a saved profile.
        viewModelScope.launch { repository.refresh() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SessionsViewModel(repository()) }
        }
    }
}
