package ai.opencode.mobile.data

import ai.opencode.mobile.R
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.OpenCodeClient
import ai.opencode.mobile.data.remote.OpenCodeException
import ai.opencode.mobile.data.remote.Project
import ai.opencode.mobile.data.remote.Session
import ai.opencode.mobile.data.remote.SessionLocation
import ai.opencode.mobile.data.remote.SessionSummary
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/**
 * A working directory the user can open: a git project (its worktree) or a
 * plain directory of opencode's `global` project.
 */
@Immutable
data class ProjectUi(
    val directory: String,
    val name: String,
    val color: String? = null,
    val sessionCount: Int = 0,
    val lastUpdated: Long = 0,
    val isGit: Boolean = false,
)

/** What the server reported; null until the first load. */
internal data class RemoteProjects(
    val projects: List<Project>,
    val sessions: List<SessionSummary>,
)

/**
 * The project list of the connected server. opencode keeps git repositories as
 * projects of their own and everything else in one `global` project, whose
 * sessions are told apart by directory only — the web client lists each such
 * directory as a project, and so does this. Directories added by hand are kept
 * locally until they get a session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ProjectStore(
    private val scope: CoroutineScope,
    private val settings: SettingsSource,
) {
    private val server = MutableStateFlow<String?>(null)
    private val remote = MutableStateFlow<RemoteProjects?>(null)

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    private val local = server.flatMapLatest { url ->
        if (url == null) {
            flowOf(LocalProjects())
        } else {
            combine(settings.savedProjects(url), settings.hiddenProjects(url)) { saved, hidden -> LocalProjects(saved, hidden) }
        }
    }

    val projects: StateFlow<List<ProjectUi>> = combine(remote, local) { remote, local ->
        mergeProjects(remote?.projects.orEmpty(), remote?.sessions.orEmpty(), local.saved, local.hidden)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** False until the list of the current server has been read (or failed to be). */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun bindServer(url: String?) {
        if (server.value == url) return
        server.value = url
        remote.value = null
        _loaded.value = false
        _error.value = null
    }

    /**
     * Reads projects and sessions of every project. `/api/session` spans all of
     * them; servers without it fall back to `/session`, which only covers the
     * project of the default instance. [isCurrent] drops a late answer of a
     * server the app has left.
     */
    suspend fun load(client: OpenCodeClient, isCurrent: () -> Boolean) {
        val projects = catchingNonCancellation { client.listProjects() }
            .onFailure { Log.w(TAG, "listProjects failed", it) }
        val sessions = catchingNonCancellation { client.listAllSessions() }
            .let { result ->
                // Old servers answer an unknown route with 404, or with the web
                // client's HTML page (200, then not JSON).
                val error = result.exceptionOrNull()
                val unsupported = (error is OpenCodeException && error.code == 404) ||
                    error is SerializationException
                if (!unsupported) result else catchingNonCancellation { client.listSessions().map(::summaryOf) }
            }
            .onFailure { Log.w(TAG, "listing sessions of all projects failed", it) }
        if (!isCurrent()) return
        val failure = projects.exceptionOrNull() ?: sessions.exceptionOrNull()
        _error.value = failure?.toUiText(R.string.error_load_projects)
        // A half that failed keeps what was read before: replacing it with
        // nothing made git projects vanish, or every session count drop to 0.
        val previous = remote.value
        if (projects.isSuccess || sessions.isSuccess || previous == null) {
            remote.value = RemoteProjects(
                projects = projects.getOrNull() ?: previous?.projects.orEmpty(),
                sessions = sessions.getOrNull() ?: previous?.sessions.orEmpty(),
            )
        }
        _loaded.value = true
    }

    /** Puts [directory] on the list (again, when it was removed). */
    fun add(directory: String) = editLocal { saved, hidden -> (saved + directory) to (hidden - directory) }

    /** Takes [directory] off the list; its sessions stay on the server. */
    fun hide(directory: String) = editLocal { saved, hidden -> (saved - directory) to (hidden + directory) }

    fun clearError() {
        _error.value = null
    }

    /** Serialises [editLocal]: two quick edits read the same sets and one was lost. */
    private val localEdits = Mutex()

    private fun editLocal(change: (Set<String>, Set<String>) -> Pair<Set<String>, Set<String>>) {
        val url = server.value ?: return
        scope.launch {
            localEdits.withLock {
                val (saved, hidden) = change(settings.savedProjects(url).first(), settings.hiddenProjects(url).first())
                settings.saveSavedProjects(url, saved)
                settings.saveHiddenProjects(url, hidden)
            }
        }
    }

    private data class LocalProjects(
        val saved: Set<String> = emptySet(),
        val hidden: Set<String> = emptySet(),
    )

    private companion object {
        const val TAG = "ProjectStore"
    }
}

internal const val GLOBAL_PROJECT_ID = "global"

private fun summaryOf(session: Session) = SessionSummary(
    id = session.id,
    projectID = session.projectID,
    parentID = session.parentID,
    title = session.title,
    time = session.time,
    location = SessionLocation(session.directory),
)

/** [directory] without a trailing slash (the root stays "/"). */
internal fun normalizeDirectory(directory: String): String {
    val trimmed = directory.trim()
    return if (trimmed.length > 1) trimmed.trimEnd('/').ifEmpty { "/" } else trimmed
}

/** The last path segment, or the path itself for the root. */
internal fun directoryName(directory: String): String =
    normalizeDirectory(directory).substringAfterLast('/').ifEmpty { directory }

/**
 * Builds the project list: git projects by their worktree (all their sessions,
 * whatever subdirectory they run in), directories of `global` sessions, and
 * directories added by hand. [hidden] ones are left out. Most recently active
 * first; projects without a session after them, by name.
 */
internal fun mergeProjects(
    projects: List<Project>,
    sessions: List<SessionSummary>,
    saved: Set<String>,
    hidden: Set<String>,
): List<ProjectUi> {
    val gitProjects = projects
        .filter { it.id != GLOBAL_PROJECT_ID && it.id.isNotBlank() && it.worktree.isNotBlank() && it.worktree != "/" }
        .associateBy { it.id }
    val byDirectory = LinkedHashMap<String, ProjectUi>()
    gitProjects.values.forEach { project ->
        val directory = normalizeDirectory(project.worktree)
        byDirectory[directory] = ProjectUi(
            directory = directory,
            name = project.name?.takeIf { it.isNotBlank() } ?: directoryName(directory),
            color = project.icon?.color,
            lastUpdated = 0,
            isGit = true,
        )
    }
    sessions
        .filter { it.parentID == null && it.id.isNotBlank() }
        .forEach { session ->
            val directory = gitProjects[session.projectID]?.worktree
                ?: session.location?.directory?.takeIf { it.isNotBlank() }
                ?: session.directory?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val key = normalizeDirectory(directory)
            val current = byDirectory[key] ?: ProjectUi(directory = key, name = directoryName(key))
            byDirectory[key] = current.copy(
                sessionCount = current.sessionCount + 1,
                lastUpdated = maxOf(current.lastUpdated, session.time?.updated ?: 0),
            )
        }
    saved.forEach { directory ->
        val key = normalizeDirectory(directory)
        if (key.isNotEmpty() && key !in byDirectory) byDirectory[key] = ProjectUi(directory = key, name = directoryName(key))
    }
    val hiddenKeys = hidden.mapTo(HashSet(), ::normalizeDirectory)
    return byDirectory.values
        .filter { it.directory !in hiddenKeys }
        .sortedWith(compareByDescending<ProjectUi> { it.lastUpdated }.thenBy { it.name.lowercase() })
}
