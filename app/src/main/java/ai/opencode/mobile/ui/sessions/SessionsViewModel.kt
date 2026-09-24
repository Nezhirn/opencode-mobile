package ai.opencode.mobile.ui.sessions

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class SessionsViewModel(private val repository: AppRepository) : ViewModel() {

    val sessions = repository.sessions
    val connection = repository.connection
    val permissions = repository.permissions
    val questions = repository.questions
    val creatingSession = repository.creatingSession
    val deletingSessions = repository.deletingSessions
    val sessionError = repository.sessionError

    private val _search = MutableStateFlow("")
    val search = _search.asStateFlow()

    /**
     * Session to open after "new session" completes. A Channel, not a StateFlow,
     * so returning to the list does not re-trigger it.
     */
    private val _navigation = Channel<String>(Channel.BUFFERED)
    val navigation: Flow<String> = _navigation.receiveAsFlow()

    /**
     * Cleared when the user leaves the list before the new session exists: the
     * buffered id used to be delivered whenever they came back, pulling them
     * into a chat out of nowhere.
     */
    @Volatile
    private var navigateToCreated = false

    fun onSearchChange(value: String) {
        _search.value = value
    }

    fun refresh() = repository.refresh()

    fun createSession() {
        navigateToCreated = true
        viewModelScope.launch {
            val id = repository.createSession() ?: return@launch
            if (navigateToCreated) {
                navigateToCreated = false
                _navigation.send(id)
            }
        }
    }

    fun onScreenLeft() {
        navigateToCreated = false
    }

    fun clearSessionError() = repository.clearSessionError()

    fun deleteSession(sessionId: String) = repository.deleteSession(sessionId)

    init {
        // Ensure the list is populated when opening the app on a saved profile.
        repository.refresh()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SessionsViewModel(repository()) }
        }
    }
}
