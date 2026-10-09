package ai.opencode.mobile.ui.projects

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.normalizeDirectory
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** State of the "add project" folder picker. */
data class DirectoryPickerState(
    /** The server's home directory; paths under it are shown as `~/…`. */
    val home: String? = null,
    /** The folder being browsed (absolute); null until home is known. */
    val path: String? = null,
    val entries: List<FileNode> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val query: String = "",
    /** Server-side matches for [query] under home, absolute. */
    val found: List<String> = emptyList(),
)

class ProjectsViewModel(
    private val repository: AppRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    val projects = repository.projects
    val projectsLoaded = repository.projectsLoaded
    val projectsError = repository.projectsError
    val connection = repository.connection

    private val _picker = MutableStateFlow(DirectoryPickerState())
    val picker: StateFlow<DirectoryPickerState> = _picker.asStateFlow()

    private var browseJob: Job? = null
    private var searchJob: Job? = null

    /** Project to show the sessions of; a Channel so coming back does not re-trigger it. */
    private val _navigation = Channel<Unit>(Channel.BUFFERED)
    val navigation: Flow<Unit> = _navigation.receiveAsFlow()

    init {
        // On a fresh start, go straight to the project used last time; the
        // list stays one "back" away. Only once: not when coming back to the
        // list, nor after the screens were restored from a saved state.
        if (savedState.get<Boolean>(KEY_AUTO_OPENED) != true) {
            savedState[KEY_AUTO_OPENED] = true
            viewModelScope.launch {
                val last = repository.lastProject() ?: return@launch
                // A directory removed on the server would otherwise be reopened
                // on every start, straight into errors. Only a clear "no" counts:
                // offline (or slow) the project opens as before.
                val exists = withTimeoutOrNull(PROJECT_CHECK_TIMEOUT_MILLIS) { repository.projectExists(last) }
                if (exists == false) {
                    repository.forgetLastProject(last)
                    return@launch
                }
                repository.openProject(last)
                _navigation.send(Unit)
            }
        }
    }

    fun refresh() = repository.refreshProjects()

    fun open(directory: String) {
        repository.openProject(directory)
        viewModelScope.launch { _navigation.send(Unit) }
    }

    fun hide(directory: String) = repository.hideProject(directory)

    fun clearError() = repository.clearProjectsError()

    // --- Folder picker ---

    fun openPicker() {
        val current = _picker.value
        _picker.value = DirectoryPickerState(home = current.home, path = current.path)
        val start = current.path
        if (start != null) {
            browse(start)
            return
        }
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _picker.update { it.copy(loading = true, failed = false) }
            val home = repository.homeDirectory() ?: "/"
            _picker.update { it.copy(home = home) }
            list(home)
        }
    }

    fun browse(path: String) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch { list(normalizeDirectory(path)) }
    }

    fun browseUp() {
        val path = _picker.value.path ?: return
        if (path == "/") return
        browse(path.substringBeforeLast('/').ifEmpty { "/" })
    }

    private suspend fun list(path: String) {
        _picker.update { it.copy(loading = true, failed = false) }
        val entries = repository.listDirectories(path)
        _picker.update { state ->
            if (entries == null) {
                // Keep showing the folder that could be read.
                state.copy(loading = false, failed = true, path = state.path ?: path)
            } else {
                state.copy(
                    loading = false,
                    path = path,
                    entries = entries.sortedBy { it.name.lowercase() },
                    query = "",
                    found = emptyList(),
                )
            }
        }
    }

    /**
     * Filters the open folder at once and asks the server for matching folders
     * under home after a short pause. The server search does not reach every
     * directory (large unindexed trees answer nothing), so the local filter is
     * what the user can rely on.
     */
    fun onQueryChange(query: String) {
        _picker.update { it.copy(query = query, found = if (query.isBlank()) emptyList() else it.found) }
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val root = _picker.value.home ?: return@launch
            val found = repository.findDirectories(query.trim(), root)
            _picker.update { state -> if (state.query == query) state.copy(found = found) else state }
        }
    }

    /** Adds the picked folder to the list and opens it. */
    fun pick(directory: String) {
        repository.addProject(directory)
        viewModelScope.launch { _navigation.send(Unit) }
    }

    companion object {
        private const val KEY_AUTO_OPENED = "auto_opened"
        private const val SEARCH_DEBOUNCE_MILLIS = 250L
        private const val PROJECT_CHECK_TIMEOUT_MILLIS = 3_000L

        val Factory = viewModelFactory {
            initializer { ProjectsViewModel(repository(), createSavedStateHandle()) }
        }
    }
}
