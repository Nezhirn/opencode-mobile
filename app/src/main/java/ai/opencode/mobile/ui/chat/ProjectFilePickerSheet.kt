package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import androidx.compose.foundation.clickable
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

/** One row of the picker: a folder to open or a file to attach, relative to the project root. */
private data class PickerEntry(val path: String, val directory: Boolean)

/**
 * Picks a file of the open project to attach, like an @-mention in the web
 * client: browse the tree or search by name. The file is sent by path, so its
 * size does not matter here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectFilePickerSheet(
    state: FilePickerState,
    onOpen: () -> Unit,
    onQueryChange: (String) -> Unit,
    onBrowse: (String) -> Unit,
    onUp: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onOpen() }
    val entries = remember(state.entries, state.found, state.query) {
        val query = state.query.trim()
        if (query.isEmpty()) {
            state.entries.map { PickerEntry(it.path.trimEnd('/'), it.type == "directory") }
        } else {
            val local = state.entries
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { PickerEntry(it.path.trimEnd('/'), it.type == "directory") }
            (local + state.found.map { PickerEntry(it.trimEnd('/'), it.endsWith("/")) }).distinctBy { it.path }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.attach_project_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.attach_search_files)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                IconButton(onClick = onUp, enabled = state.path.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.files_up))
                }
                Text(
                    text = "/" + state.path.trim('/'),
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
                .heightIn(max = 420.dp)
                .padding(bottom = 16.dp),
        ) {
            if (entries.isEmpty() && !state.loading) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.attach_no_files),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(entries, key = { it.path }) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (entry.directory) onBrowse(entry.path) else onPick(entry.path) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        if (entry.directory) Icons.Filled.Folder else attachmentIcon(projectFileMime(entry.path)),
                        contentDescription = null,
                        tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (state.query.isBlank()) entry.path.substringAfterLast('/') else entry.path,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
