package ai.opencode.mobile.ui.files

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.remote.FileContent
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.data.remote.VcsInfo
import ai.opencode.mobile.ui.repository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FilesViewModel(private val repository: AppRepository) : ViewModel() {

    private val _vcsInfo = MutableStateFlow<VcsInfo?>(null)
    val vcsInfo = _vcsInfo.asStateFlow()

    private val _vcsStatus = MutableStateFlow<List<VcsFileStatus>>(emptyList())
    val vcsStatus = _vcsStatus.asStateFlow()

    private val _vcsDiff = MutableStateFlow<List<VcsFileDiff>>(emptyList())
    val vcsDiff = _vcsDiff.asStateFlow()

    private val _sessionDiff = MutableStateFlow<List<VcsFileDiff>>(emptyList())
    val sessionDiff = _sessionDiff.asStateFlow()

    private val _files = MutableStateFlow<List<FileNode>>(emptyList())
    val files = _files.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath = _currentPath.asStateFlow()

    private val _openedFile = MutableStateFlow<FileContent?>(null)
    val openedFile = _openedFile.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    /** True while a directory listing is on its way. */
    private val _browsing = MutableStateFlow(false)
    val browsing = _browsing.asStateFlow()

    /** True while the VCS tab is loading. */
    private val _vcsLoading = MutableStateFlow(false)
    val vcsLoading = _vcsLoading.asStateFlow()

    val actionError = repository.actionError

    fun clearActionError() = repository.clearActionError()

    private var sessionId: String? = null
    private var initialized = false

    // One job per concern. Without them a slow request could land after a newer
    // one (stale file list for the wrong directory) or a repeated tap could run
    // the same load several times over.
    private var diffJob: Job? = null
    private var vcsJob: Job? = null
    private var browseJob: Job? = null
    private var openFileJob: Job? = null

    /**
     * Loads the screen once per ViewModel. Without a session there is no diff
     * to show, but the VCS tab still needs loading (it used to claim a clean
     * tree), and an activity recreation must not reset the browsed directory.
     */
    fun load(sessionId: String?) {
        if (initialized && this.sessionId == sessionId) return
        initialized = true
        this.sessionId = sessionId
        refreshSessionDiff()
        loadVcs()
        openDirectory("")
    }

    fun loadVcs() {
        if (vcsJob?.isActive == true) return
        vcsJob = viewModelScope.launch {
            _vcsLoading.value = true
            try {
                _vcsInfo.value = repository.vcsInfo()
                _vcsStatus.value = repository.vcsStatus()
                _vcsDiff.value = repository.vcsDiff("git")
            } finally {
                _vcsLoading.value = false
            }
        }
    }

    fun refreshSessionDiff() {
        val id = sessionId ?: return
        if (diffJob?.isActive == true) return
        diffJob = viewModelScope.launch {
            _loading.value = true
            try {
                _sessionDiff.value = repository.sessionDiff(id)
            } finally {
                _loading.value = false
            }
        }
    }

    /** Latest tap wins: an older listing must never repaint a directory the user left. */
    fun openDirectory(path: String) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _browsing.value = true
            try {
                // On failure stay where we were: showing the old path with an
                // empty list read as "this directory is empty".
                val nodes = repository.listFiles(path) ?: return@launch
                _currentPath.value = path
                _files.value = nodes
            } finally {
                _browsing.value = false
            }
        }
    }

    fun openFile(path: String) {
        openFileJob?.cancel()
        openFileJob = viewModelScope.launch { _openedFile.value = repository.readFile(path) }
    }

    fun closeFile() {
        _openedFile.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { FilesViewModel(repository()) }
        }
    }
}
