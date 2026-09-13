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

    val actionError = repository.actionError

    fun clearActionError() = repository.clearActionError()

    private var sessionId: String? = null

    fun load(sessionId: String) {
        if (this.sessionId == sessionId) return
        this.sessionId = sessionId
        viewModelScope.launch {
            _loading.value = true
            _sessionDiff.value = repository.sessionDiff(sessionId)
            _loading.value = false
        }
        loadVcs()
        openDirectory("")
    }

    fun loadVcs() {
        viewModelScope.launch {
            _vcsInfo.value = repository.vcsInfo()
            _vcsStatus.value = repository.vcsStatus()
            _vcsDiff.value = runCatching { repository.vcsDiff("git") }.getOrDefault(emptyList())
        }
    }

    fun refreshSessionDiff() {
        val id = sessionId ?: return
        viewModelScope.launch {
            _loading.value = true
            _sessionDiff.value = repository.sessionDiff(id)
            _loading.value = false
        }
    }

    fun openDirectory(path: String) {
        viewModelScope.launch {
            _currentPath.value = path
            _files.value = repository.listFiles(path)
        }
    }

    fun openFile(path: String) {
        viewModelScope.launch { _openedFile.value = repository.readFile(path) }
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
