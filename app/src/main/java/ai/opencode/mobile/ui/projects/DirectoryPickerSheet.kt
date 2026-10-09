package ai.opencode.mobile.ui.projects

import ai.opencode.mobile.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One row of the picker: a folder to open, shown relative to home. */
private data class FolderEntry(val path: String, val label: String)

/**
 * "Add project", as in the web client: browse the server's folders from home
 * (or search them) and pick one as a project. opencode has no API to create a
 * folder, so only existing ones can be added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectoryPickerSheet(
    state: DirectoryPickerState,
    onOpen: () -> Unit,
    onQueryChange: (String) -> Unit,
    onBrowse: (String) -> Unit,
    onUp: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onOpen() }
    val home = state.home
    val folders = remember(state.entries, state.found, state.query, home) {
        val query = state.query.trim()
        val local = state.entries
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .map { node -> node.absolute.ifBlank { joinPath(state.path, node.name) } }
        (local + state.found).distinct().map { FolderEntry(it, displayPath(it, home)) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.projects_add), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.projects_add_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.projects_search_folders)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                IconButton(onClick = onUp, enabled = state.path != null && state.path != "/") {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.files_up))
                }
                Text(
                    text = state.path?.let { displayPath(it, home) } ?: "…",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (state.failed) {
                Text(
                    text = stringResource(R.string.projects_folder_unreadable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
        ) {
            if (folders.isEmpty() && !state.loading) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.projects_no_folders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(folders, key = { it.path }) { folder ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBrowse(folder.path) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = folder.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Button(onClick = { state.path?.let(onPick) }, enabled = state.path != null && !state.loading) {
                Text(stringResource(R.string.projects_pick_folder))
            }
        }
    }
}

private fun joinPath(parent: String?, name: String): String =
    if (parent == null || parent == "/") "/$name" else "$parent/$name"

/** `~/…` for paths under [home]. */
private fun displayPath(path: String, home: String?): String = when {
    home.isNullOrBlank() || home == "/" -> path
    path == home -> "~"
    path.startsWith("$home/") -> "~" + path.removePrefix(home)
    else -> path
}
