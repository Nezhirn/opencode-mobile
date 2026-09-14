package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ChatViewModel(private val repository: AppRepository) : ViewModel() {

    val chat = repository.chat
    val providers = repository.providers
    val providersLoaded = repository.providersLoaded
    val agents = repository.agents
    val selectedModel = repository.selectedModel
    val selectedAgent = repository.selectedAgent
    val modelNotice = repository.modelNotice

    private val sessionId = MutableStateFlow<String?>(null)

    val permissions = combine(repository.permissions, sessionId) { list, id ->
        if (id == null) emptyList() else list.filter { repository.sessionMatches(it.sessionID, id) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val questions = combine(repository.questions, sessionId) { list, id ->
        if (id == null) emptyList() else list.filter { repository.sessionMatches(it.sessionID, id) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun open(id: String) {
        sessionId.value = id
        repository.openChat(id)
    }

    override fun onCleared() {
        // Release the shared chat state when leaving the screen so events stop
        // being processed for a session that is no longer visible. Guard by id so
        // this cannot clear a chat that was opened after this one.
        repository.closeChat(sessionId.value)
        super.onCleared()
    }

    fun send(text: String) = repository.sendPrompt(text)

    fun abort() = repository.abort()

    fun selectModel(model: PromptModel?) = repository.selectModel(model)

    fun selectAgent(agent: String?) = repository.selectAgent(agent)

    fun clearModelNotice() = repository.clearModelNotice()

    fun replyPermission(requestId: String, reply: String) = repository.replyPermission(requestId, reply)

    fun replyQuestion(requestId: String, answers: List<List<String>>) =
        repository.replyQuestion(requestId, answers)

    fun rejectQuestion(requestId: String) = repository.rejectQuestion(requestId)

    companion object {
        val Factory = viewModelFactory {
            initializer { ChatViewModel(repository()) }
        }
    }
}
