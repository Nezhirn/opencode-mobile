package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.Attachment
import ai.opencode.mobile.data.ContextStats
import ai.opencode.mobile.data.EditDraft
import ai.opencode.mobile.data.UiText
import ai.opencode.mobile.data.contextStats
import ai.opencode.mobile.data.contextUsagePercent
import ai.opencode.mobile.data.fileUrlOf
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.uiText
import ai.opencode.mobile.ui.repository
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** State of the "attach a project file" picker. */
data class FilePickerState(
    /** Folder being browsed, relative to the project root ("" is the root). */
    val path: String = "",
    val entries: List<FileNode> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val query: String = "",
    /** Search matches, relative to the project root. */
    val found: List<String> = emptyList(),
)

class ChatViewModel(
    private val repository: AppRepository,
    private val savedState: SavedStateHandle,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    val chat = repository.chat
    val providers = repository.providers
    val providersLoaded = repository.providersLoaded
    val agents = repository.agents
    val selectedModel = repository.selectedModel
    val selectedAgent = repository.selectedAgent
    val availableVariants = repository.availableVariants
    val selectedVariant = repository.selectedVariant
    val modelNotice = repository.modelNotice
    val enabledModels = repository.enabledModels
    val replying = repository.replying

    val mcpServers = repository.mcpServers
    val mcpToggling = repository.mcpToggling
    val mcpError = repository.mcpError

    fun refreshMcp() = repository.refreshMcp()

    fun setMcpEnabled(name: String, enabled: Boolean) = repository.setMcpEnabled(name, enabled)

    fun clearMcpError() = repository.clearMcpError()

    /**
     * The prompt being typed. Mirrored into saved state so it survives process
     * death — but only while it is small: saved state travels through a Binder
     * transaction of about 1 MB, and a pasted multi-megabyte log made going to
     * the background crash the app (TransactionTooLargeException).
     */
    private val _draft = MutableStateFlow(savedState[DRAFT_KEY] ?: "")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private fun setDraft(text: String) {
        _draft.value = text
        savedState[DRAFT_KEY] = if (text.length <= MAX_SAVED_DRAFT_CHARS) text else ""
    }

    private val sessionId = MutableStateFlow<String?>(null)

    /**
     * Files to send with the prompt. Memory only: they can be megabytes, far
     * too much for saved state.
     */
    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    /** Previews of image attachments by attachment id. */
    private val _thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ImageBitmap>> = _thumbnails.asStateFlow()

    /** Why a file could not be attached; shown until dismissed. */
    private val _attachError = MutableStateFlow<UiText?>(null)
    val attachError: StateFlow<UiText?> = _attachError.asStateFlow()

    private val attachmentCounter = AtomicLong()

    /** Expanded cards of the chat; memory only, see [ExpandedItems]. */
    val expandedItems = ExpandedItems()

    /**
     * Files picked on the phone that are still being read. Sending waits for
     * them: the prompt used to leave without the file, which then turned up in
     * the field and went out with the next, unrelated prompt.
     */
    private val _pendingAttachments = MutableStateFlow(0)
    val pendingAttachments: StateFlow<Int> = _pendingAttachments.asStateFlow()

    /** One file is read at a time: two picks at once doubled the memory peak. */
    private val readLock = Mutex()

    /** Asks the screen to focus the prompt field (after "edit"). */
    private val _focusPrompt = Channel<Unit>(Channel.CONFLATED)
    val focusPrompt: Flow<Unit> = _focusPrompt.receiveAsFlow()

    /** The open session as listed (cost, dates for the context panel). */
    private val session = combine(repository.sessions, sessionId) { sessions, id -> sessions.firstOrNull { it.id == id } }

    /** Context window use in percent for the top bar; null when unknown. */
    val contextUsage: StateFlow<Int?> = combine(repository.chat, repository.providers) { chat, providers ->
        contextUsagePercent(chat.visibleMessages, providers)
    }
        .conflate()
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The context panel's figures. Only computed while the panel collects them:
     * the breakdown walks the text of the whole chat, too much for every token.
     */
    val contextStats: StateFlow<ContextStats?> = combine(repository.chat, session, repository.providers) { chat, session, providers ->
        contextStats(chat.visibleMessages, session, providers)
    }
        .conflate()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

    val permissions = combine(repository.permissions, sessionId) { list, id ->
        if (id == null) emptyList() else list.filter { repository.sessionMatches(it.sessionID, id) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val questions = combine(repository.questions, sessionId) { list, id ->
        if (id == null) emptyList() else list.filter { repository.sessionMatches(it.sessionID, id) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun open(id: String) {
        sessionId.value = id
        repository.openChat(id, owner = this)
    }

    override fun onCleared() {
        // Release the shared chat state when leaving the screen so events stop
        // being processed for a session that is no longer visible. Guarded by
        // owner, so this cannot clear a chat that a newer screen opened since —
        // the same session reopened quickly included.
        repository.closeChat(sessionId.value, owner = this)
        super.onCleared()
    }

    fun onDraftChange(text: String) = setDraft(text)

    fun clearError() = repository.clearChatError()

    /**
     * Clears the field right away, as a chat should, but gives the text back
     * when the prompt was not accepted (no model, network failure) — unless the
     * user has started typing something else meanwhile.
     */
    fun send() {
        if (_pendingAttachments.value > 0) return
        val text = draft.value
        val files = _attachments.value
        if (text.isBlank() && files.isEmpty()) return
        setDraft("")
        _attachments.value = emptyList()
        viewModelScope.launch {
            val accepted = repository.sendPrompt(text, files).await()
            if (accepted) {
                dropThumbnailsExcept(_attachments.value)
                return@launch
            }
            // Nothing the user wrote is lost: what was typed since goes after
            // the prompt that came back, files picked since stay.
            val typed = draft.value
            setDraft(if (typed.isBlank()) text else text.trimEnd() + "\n\n" + typed)
            _attachments.update { current -> files + current.filter { added -> files.none { it.url == added.url } } }
        }
    }

    // --- Attachments ---

    private fun nextAttachmentId() = "attachment-${attachmentCounter.incrementAndGet()}"

    /** Files picked on the phone; each is read off the main thread. */
    fun addDeviceFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _attachError.value = null
        _pendingAttachments.update { it + uris.size }
        viewModelScope.launch {
            val failures = ArrayList<UiText>()
            try {
                readLock.withLock {
                    uris.forEach { uri ->
                        try {
                            when (val result = readDeviceFile(contentResolver, uri, nextAttachmentId())) {
                                is DeviceFileResult.Ok -> addAttachment(result.attachment, result.thumbnail)?.let(failures::add)
                                is DeviceFileResult.Failed -> failures += result.message
                            }
                        } finally {
                            _pendingAttachments.update { (it - 1).coerceAtLeast(0) }
                        }
                    }
                }
            } finally {
                // Every file that failed is named, not just the last one.
                _attachError.value = when (failures.size) {
                    0 -> null
                    1 -> failures.single()
                    else -> UiText.Lines(failures)
                }
            }
        }
    }

    /** A project file, sent by path: the server reads it itself. */
    fun addProjectFile(relativePath: String) {
        val root = repository.currentDirectory.value ?: return
        val path = relativePath.trimStart('/').trimEnd('/')
        if (path.isEmpty()) return
        val separator = if (root.contains('\\') && !root.contains('/')) "\\" else "/"
        val absolute = if (root == "/") "/$path" else root.trimEnd('/', '\\') + separator + path
        addAttachment(
            Attachment(
                id = nextAttachmentId(),
                filename = path,
                mime = projectFileMime(path),
                url = fileUrlOf(absolute),
            ),
            thumbnail = null,
        )?.let { _attachError.value = it }
    }

    /** Adds [attachment] unless it is already there; the reason when it does not fit. */
    private fun addAttachment(attachment: Attachment, thumbnail: ImageBitmap?): UiText? {
        var refused: UiText? = null
        _attachments.update { current ->
            refused = null
            when {
                // The same file twice is a mistake, not a request.
                current.any { it.url == attachment.url } -> current
                current.sumOf { it.sizeBytes } + attachment.sizeBytes > MAX_TOTAL_ATTACHMENT_BYTES -> {
                    refused = uiText(R.string.error_attachments_total, MAX_TOTAL_ATTACHMENTS_MB)
                    current
                }
                else -> current + attachment
            }
        }
        if (refused == null && thumbnail != null && _attachments.value.any { it.id == attachment.id }) {
            _thumbnails.update { it + (attachment.id to thumbnail) }
        }
        return refused
    }

    fun removeAttachment(id: String) {
        _attachments.update { list -> list.filterNot { it.id == id } }
        _thumbnails.update { it - id }
    }

    fun clearAttachError() {
        _attachError.value = null
    }

    private fun dropThumbnailsExcept(kept: List<Attachment>) {
        val ids = kept.mapTo(HashSet()) { it.id }
        _thumbnails.update { map -> map.filterKeys { it in ids } }
    }

    // --- Project file picker ---

    private val _filePicker = MutableStateFlow(FilePickerState())
    val filePicker: StateFlow<FilePickerState> = _filePicker.asStateFlow()
    private var browseJob: Job? = null
    private var searchJob: Job? = null

    fun openFilePicker() {
        _filePicker.update { it.copy(query = "", found = emptyList()) }
        browseFiles(_filePicker.value.path)
    }

    fun browseFiles(path: String) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _filePicker.update { it.copy(loading = true, failed = false) }
            val entries = repository.listFiles(path)
            repository.clearActionError()
            _filePicker.update { state ->
                if (entries == null) {
                    state.copy(loading = false, failed = true)
                } else {
                    state.copy(
                        loading = false,
                        path = path,
                        entries = entries.sortedWith(compareBy<FileNode> { it.type != "directory" }.thenBy { it.name.lowercase() }),
                        query = "",
                        found = emptyList(),
                    )
                }
            }
        }
    }

    fun browseFilesUp() {
        val path = _filePicker.value.path.trimEnd('/')
        if (path.isEmpty()) return
        browseFiles(path.substringBeforeLast('/', ""))
    }

    fun onFileQueryChange(query: String) {
        _filePicker.update { it.copy(query = query, found = if (query.isBlank()) emptyList() else it.found) }
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val found = repository.findFiles(query.trim())
            _filePicker.update { state -> if (state.query == query) state.copy(found = found) else state }
        }
    }

    // --- Edit and rollback ---

    /** True when there is something in the prompt that putting a message into it would replace. */
    fun promptHasContent(): Boolean = draft.value.isNotBlank() || _attachments.value.isNotEmpty()

    /** Whether restoring [messageId] puts another message into the prompt (or just undoes the rollback). */
    fun restoreFillsPrompt(messageId: String): Boolean = repository.restoreFillsPrompt(messageId)

    /**
     * Rolls back to before [messageId] and puts the message into the prompt to
     * edit, replacing what is there: the screen asks first when that is
     * something ([promptHasContent]).
     */
    fun edit(messageId: String) {
        viewModelScope.launch {
            val draft = repository.revertTo(messageId).await() ?: return@launch
            applyDraft(draft)
            _focusPrompt.trySend(Unit)
        }
    }

    /** Brings a rolled back message back; the prompt follows the web client's rules. */
    fun restore(messageId: String) {
        viewModelScope.launch {
            val draft = repository.restoreMessage(messageId).await() ?: return@launch
            applyDraft(draft)
        }
    }

    /**
     * Puts [draft] into the prompt. An empty one (the whole rollback undone)
     * leaves the prompt alone: it used to clear whatever was being typed.
     */
    private suspend fun applyDraft(draft: EditDraft) {
        if (draft.text.isBlank() && draft.attachments.isEmpty()) return
        // Previews first, so the files and their previews change together.
        val previews = withContext(Dispatchers.Default) {
            draft.attachments.mapNotNull { attachment -> thumbnailOf(attachment)?.let { attachment.id to it } }.toMap()
        }
        setDraft(draft.text)
        _attachments.value = draft.attachments
        _thumbnails.value = previews
    }

    fun abort() = repository.abort()

    fun selectModel(model: PromptModel?) = repository.selectModel(model)

    fun selectAgent(agent: String?) = repository.selectAgent(agent)

    fun selectVariant(variant: String?) = repository.selectVariant(variant)

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
        private const val MAX_SAVED_DRAFT_CHARS = 50_000
        private const val SEARCH_DEBOUNCE_MILLIS = 250L

        val Factory = viewModelFactory {
            initializer {
                ChatViewModel(repository(), createSavedStateHandle(), checkNotNull(this[APPLICATION_KEY]).contentResolver)
            }
        }
    }
}
