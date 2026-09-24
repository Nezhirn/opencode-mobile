package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: AppRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    val chat = repository.chat
    val providers = repository.providers
    val providersLoaded = repository.providersLoaded
    val agents = repository.agents
    val selectedModel = repository.selectedModel
    val selectedAgent = repository.selectedAgent
    val modelNotice = repository.modelNotice
    val enabledModels = repository.enabledModels
    val replying = repository.replying

    /** The prompt being typed. Saved so it survives rotation and process death. */
    val draft: StateFlow<String> = savedState.getStateFlow(DRAFT_KEY, "")

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

    fun onDraftChange(text: String) {
        savedState[DRAFT_KEY] = text
    }

    /**
     * Clears the field right away, as a chat should, but gives the text back
     * when the prompt was not accepted (no model, network failure) — unless the
     * user has started typing something else meanwhile.
     */
    fun send() {
        val text = draft.value
        if (text.isBlank()) return
        savedState[DRAFT_KEY] = ""
        viewModelScope.launch {
            val accepted = repository.sendPrompt(text).await()
            if (!accepted && draft.value.isEmpty()) savedState[DRAFT_KEY] = text
        }
    }

    fun abort() = repository.abort()

    fun selectModel(model: PromptModel?) = repository.selectModel(model)

    fun selectAgent(agent: String?) = repository.selectAgent(agent)

    fun clearModelNotice() = repository.clearModelNotice()

    fun setModelEnabled(model: PromptModel, enabled: Boolean) = repository.setModelEnabled(model, enabled)

    fun setProviderEnabled(providerId: String, enabled: Boolean) = repository.setProviderEnabled(providerId, enabled)

    fun showAllModels() = repository.showAllModels()

    fun replyPermission(requestId: String, reply: String) = repository.replyPermission(requestId, reply)

    fun replyQuestion(requestId: String, answers: List<List<String>>) =
        repository.replyQuestion(requestId, answers)

    fun rejectQuestion(requestId: String) = repository.rejectQuestion(requestId)

    companion object {
        private const val DRAFT_KEY = "draft"

        val Factory = viewModelFactory {
            initializer { ChatViewModel(repository(), createSavedStateHandle()) }
        }
    }
}
