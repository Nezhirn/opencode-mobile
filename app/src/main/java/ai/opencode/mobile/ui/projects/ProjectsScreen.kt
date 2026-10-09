package ai.opencode.mobile.ui.projects

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ConnectionState
import ai.opencode.mobile.data.ProjectUi
import ai.opencode.mobile.ui.asString
import ai.opencode.mobile.ui.components.Banner
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The start screen: the projects (working directories) of the server, like the
 * web client's sidebar. A project opens its own session list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory)
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val loaded by viewModel.projectsLoaded.collectAsStateWithLifecycle()
    val error by viewModel.projectsError.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val openProject by rememberUpdatedState(onOpenProject)

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.navigation.collect { openProject() }
    }

    if (showPicker) {
        val picker by viewModel.picker.collectAsStateWithLifecycle()
        DirectoryPickerSheet(
            state = picker,
            onOpen = viewModel::openPicker,
            onQueryChange = viewModel::onQueryChange,
            onBrowse = viewModel::browse,
            onUp = viewModel::browseUp,
            onPick = { directory ->
                showPicker = false
                viewModel.pick(directory)
            },
            onDismiss = { showPicker = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects_title)) },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sessions_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.sessions_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showPicker = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.projects_add))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val state = connection) {
                is ConnectionState.Error -> Banner(
                    text = state.message.asString(),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )

                ConnectionState.Connecting -> Banner(
                    text = stringResource(R.string.connect_connecting),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ConnectionState.Disconnected -> Banner(
                    text = stringResource(R.string.connect_not_connected),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is ConnectionState.Connected -> Unit
            }
            error?.let { message ->
                Banner(
                    text = message.asString(),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    onDismiss = viewModel::clearError,
                )
            }

            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        loaded -> Text(
                            stringResource(R.string.projects_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                        connection !is ConnectionState.Error -> CircularProgressIndicator()
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(projects, key = { it.directory }) { project ->
                        ProjectRow(
                            project = project,
                            onClick = { viewModel.open(project.directory) },
                            onHide = { viewModel.hide(project.directory) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(project: ProjectUi, onClick: () -> Unit, onHide: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectAvatar(project)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = project.directory,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val sessions = pluralStringResource(R.plurals.projects_sessions, project.sessionCount, project.sessionCount)
                val updated = project.lastUpdated
                val relative = remember(updated) {
                    if (updated > 0) {
                        DateUtils.getRelativeTimeSpanString(updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    } else {
                        null
                    }
                }
                Text(
                    text = listOfNotNull(sessions, relative).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.projects_hide)) },
                onClick = {
                    menu = false
                    onHide()
                },
            )
        }
    }
}

@Composable
private fun ProjectAvatar(project: ProjectUi) {
    val color = projectColor(project)
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(color.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = project.name.firstOrNull()?.uppercase() ?: "?",
            color = color,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The project's colour from the web client's palette, or a stable pick by name. */
private fun projectColor(project: ProjectUi): Color =
    PROJECT_COLORS[project.color] ?: PROJECT_COLORS.values.elementAt(Math.floorMod(project.directory.hashCode(), PROJECT_COLORS.size))

private val PROJECT_COLORS = linkedMapOf(
    "pink" to Color(0xFFE879A6),
    "mint" to Color(0xFF34C3A0),
    "orange" to Color(0xFFF08A3C),
    "purple" to Color(0xFFA27CF0),
    "cyan" to Color(0xFF3CB6E0),
    "lime" to Color(0xFF9BC53D),
)
