package ai.opencode.mobile.ui.files

import ai.opencode.mobile.R
import ai.opencode.mobile.data.remote.FileContent
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.ui.components.TruncatedText
import ai.opencode.mobile.ui.components.chunkedForLayout
import ai.opencode.mobile.ui.components.safePrefix
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    sessionId: String?,
    onBack: () -> Unit,
) {
    val viewModel: FilesViewModel = viewModel(factory = FilesViewModel.Factory)
    val sessionDiff by viewModel.sessionDiff.collectAsStateWithLifecycle()
    val vcsInfo by viewModel.vcsInfo.collectAsStateWithLifecycle()
    val vcsStatus by viewModel.vcsStatus.collectAsStateWithLifecycle()
    val vcsDiff by viewModel.vcsDiff.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val openedFile by viewModel.openedFile.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) {
        if (sessionId != null) viewModel.load(sessionId) else viewModel.openDirectory("")
    }

    var tab by rememberSaveable { mutableIntStateOf(if (sessionId != null) 0 else 1) }
    val tabs = listOf(
        stringResource(R.string.files_tab_changes),
        stringResource(R.string.files_tab_files),
        stringResource(R.string.files_tab_vcs),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.files_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        when (tab) {
                            0 -> viewModel.refreshSessionDiff()
                            2 -> viewModel.loadVcs()
                            else -> viewModel.openDirectory(currentPath)
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sessions_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            actionError?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(12.dp),
                        )
                        IconButton(onClick = { viewModel.clearActionError() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_dismiss),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> DiffList(
                        diffs = sessionDiff,
                        loading = loading,
                        emptyText = stringResource(R.string.files_no_changes),
                    )

                    1 -> FileBrowser(
                        list = files,
                        currentPath = currentPath,
                        onOpenDirectory = viewModel::openDirectory,
                        onOpenFile = viewModel::openFile,
                    )

                    else -> VcsView(
                        branch = vcsInfo?.branch,
                        status = vcsStatus,
                        diffs = vcsDiff,
                    )
                }
            }
        }
    }

    openedFile?.let { file ->
        FileViewerDialog(file = file, onClose = viewModel::closeFile)
    }
}

@Composable
private fun DiffList(diffs: List<VcsFileDiff>, loading: Boolean, emptyText: String) {
    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (diffs.isEmpty()) {
        EmptyState(emptyText)
        return
    }
    // Keyed by path alone: an index in the key reset every row's expanded state
    // below the first insertion whenever a refresh reordered the list. Paths are
    // deduplicated because a duplicate key crashes the LazyColumn.
    val unique = remember(diffs) { diffs.distinctBy { it.file } }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(unique, key = { diff -> diff.file }) { diff ->
            DiffItem(diff)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun DiffItem(diff: VcsFileDiff) {
    var expanded by rememberSaveable(diff.file) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diff.file,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${diff.status ?: "modified"} · +${diff.additions} −${diff.deletions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded && !diff.patch.isNullOrBlank()) {
            PatchText(patch = diff.patch, stateKey = diff.file)
        }
    }
}

@Composable
private fun PatchText(patch: String, stateKey: Any?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 420.dp),
    ) {
        TruncatedText(
            text = patch,
            stateKey = stateKey,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
        )
    }
}

@Composable
private fun FileBrowser(
    list: List<FileNode>,
    currentPath: String,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (currentPath.isNotBlank()) {
                IconButton(onClick = { onOpenDirectory(currentPath.substringBeforeLast('/', "")) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.files_up))
                }
            }
            Text(
                text = "/" + currentPath.ifBlank { "" },
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (list.isEmpty()) {
            EmptyState(stringResource(R.string.files_empty_dir))
        } else {
            val unique = remember(list) { list.distinctBy { it.path } }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(unique, key = { node -> node.path }) { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (node.type == "directory") onOpenDirectory(node.path) else onOpenFile(node.path)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (node.type == "directory") Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (node.ignored) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun VcsView(branch: String?, status: List<VcsFileStatus>, diffs: List<VcsFileDiff>) {
    // Remembered: rebuilding the list on every recomposition handed the
    // LazyColumn a new list identity each time and forced a full re-layout.
    val display = remember(diffs, status) {
        if (diffs.isNotEmpty()) {
            diffs
        } else {
            status.map { VcsFileDiff(it.file, null, it.additions, it.deletions, it.status) }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (branch != null) {
            Text(
                text = stringResource(R.string.files_on_branch, branch),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (display.isEmpty()) {
            EmptyState(stringResource(R.string.files_clean_tree))
        } else {
            DiffList(diffs = display, loading = false, emptyText = stringResource(R.string.files_clean_tree))
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileViewerDialog(file: FileContent, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize(0.95f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.files_file),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.files_close))
                    }
                }
                HorizontalDivider()
                val raw = if (file.type == "binary") stringResource(R.string.files_binary) else file.content
                val capped = remember(raw) { raw.safePrefix(MAX_FILE_CHARS) }
                // A LazyColumn over bounded chunks, not one Text in a
                // verticalScroll: the old version measured the whole file on the
                // main thread, which hung the app on anything sizeable.
                val chunks = remember(capped) { capped.chunkedForLayout() }
                val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                // Selection spans only the chunks currently composed, which is
                // enough to copy a snippet from what is on screen.
                SelectionContainer {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        if (raw.length > MAX_FILE_CHARS) {
                            item(key = "truncated") {
                                Text(
                                    text = stringResource(R.string.files_too_large, MAX_FILE_CHARS),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                        }
                        items(chunks.size, key = { index -> index }) { index ->
                            Text(text = chunks[index], style = mono)
                        }
                    }
                }
            }
        }
    }
}

/** Hard ceiling on what the viewer will lay out, regardless of file size. */
private const val MAX_FILE_CHARS = 200_000
