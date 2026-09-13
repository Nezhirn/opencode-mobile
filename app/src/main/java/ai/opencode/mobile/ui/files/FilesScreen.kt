package ai.opencode.mobile.ui.files

import ai.opencode.mobile.data.remote.FileContent
import ai.opencode.mobile.data.remote.FileNode
import ai.opencode.mobile.data.remote.VcsFileDiff
import ai.opencode.mobile.data.remote.VcsFileStatus
import ai.opencode.mobile.ui.components.TruncatedText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

    var tab by remember { mutableIntStateOf(if (sessionId != null) 0 else 1) }
    val tabs = listOf("Changes", "Files", "VCS")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files & Changes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
                                contentDescription = "Dismiss",
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
                        emptyText = "No changes in this session",
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

    if (openedFile != null) {
        FileViewerDialog(file = openedFile!!, onClose = viewModel::closeFile)
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(diffs, key = { index, diff -> "$index:${diff.file}" }) { _, diff ->
            DiffItem(diff)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun DiffItem(diff: VcsFileDiff) {
    var expanded by remember { mutableStateOf(false) }
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
            PatchText(diff.patch)
        }
    }
}

@Composable
private fun PatchText(patch: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 420.dp),
    ) {
        TruncatedText(
            text = patch,
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
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
            EmptyState("Empty directory")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { index, node -> "$index:${node.path}" }) { _, node ->
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
    val display = if (diffs.isNotEmpty()) {
        diffs.map { VcsFileDiff(it.file, it.patch, it.additions, it.deletions, it.status) }
    } else {
        status.map { VcsFileDiff(it.file, null, it.additions, it.deletions, it.status) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (branch != null) {
            Text(
                text = "On branch $branch",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (display.isEmpty()) {
            EmptyState("Working tree clean")
        } else {
            DiffList(diffs = display, loading = false, emptyText = "Working tree clean")
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
                        text = "File",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()
                val text = if (file.type == "binary") "(binary file)" else file.content
                TruncatedText(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
