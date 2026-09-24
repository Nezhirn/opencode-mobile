package ai.opencode.mobile.ui.sessions

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ConnectionState
import ai.opencode.mobile.data.remote.Session
import android.app.Activity
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: SessionsViewModel = viewModel(factory = SessionsViewModel.Factory)
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val questions by viewModel.questions.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val creating by viewModel.creatingSession.collectAsStateWithLifecycle()
    val deleting by viewModel.deletingSessions.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(sessions, search) {
        if (search.isBlank()) sessions
        else sessions.filter { it.title.contains(search, ignoreCase = true) }
    }

    LaunchedEffect(Unit) {
        viewModel.navigation.collect { sessionId -> onOpenSession(sessionId) }
    }
    val activity = LocalContext.current as? Activity
    DisposableEffect(viewModel) {
        onDispose {
            // Leaving for another screen cancels "open the new session"; a
            // rotation only recreates this screen and must not.
            if (activity?.isChangingConfigurations != true) viewModel.onScreenLeft()
        }
    }

    // A session that disappeared meanwhile (deleted elsewhere) just closes the dialog.
    val pendingDelete = pendingDeleteId?.let { id -> sessions.firstOrNull { it.id == id } }
    if (pendingDelete != null) {
        DeleteSessionDialog(
            title = pendingDelete.displayTitle,
            onConfirm = {
                pendingDeleteId = null
                viewModel.deleteSession(pendingDelete.id)
            },
            onDismiss = { pendingDeleteId = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions_title)) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sessions_refresh))
                    }
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.sessions_open_files))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.sessions_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (creating) {
                FloatingActionButton(onClick = {}) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                FloatingActionButton(onClick = { viewModel.createSession() }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sessions_new))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val state = connection) {
                is ConnectionState.Error -> Banner(
                    text = state.message,
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

            sessionError?.let { message ->
                Banner(
                    text = message,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    onDismiss = viewModel::clearSessionError,
                )
            }

            if (permissions.isNotEmpty() || questions.isNotEmpty()) {
                Banner(
                    text = buildString {
                        if (permissions.isNotEmpty()) {
                            append(
                                pluralStringResource(
                                    R.plurals.sessions_pending_permissions,
                                    permissions.size,
                                    permissions.size,
                                ),
                            )
                        }
                        if (permissions.isNotEmpty() && questions.isNotEmpty()) append(" · ")
                        if (questions.isNotEmpty()) {
                            append(
                                pluralStringResource(
                                    R.plurals.sessions_pending_questions,
                                    questions.size,
                                    questions.size,
                                ),
                            )
                        }
                    },
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            OutlinedTextField(
                value = search,
                onValueChange = viewModel::onSearchChange,
                placeholder = { Text(stringResource(R.string.sessions_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.sessions_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            deleting = session.id in deleting,
                            onClick = { onOpenSession(session.id) },
                            onDelete = { pendingDeleteId = session.id },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

private val Session.displayTitle: String get() = title.ifBlank { slug.ifBlank { id } }

@Composable
private fun DeleteSessionDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_delete_title)) },
        text = { Text(stringResource(R.string.sessions_delete_message, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.sessions_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SessionRow(session: Session, deleting: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val updated = session.time?.updated ?: 0
            val relative = remember(updated, session.directory) {
                if (updated > 0) {
                    DateUtils.getRelativeTimeSpanString(updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                } else {
                    session.directory
                }
            }
            Text(
                text = relative,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (deleting) {
            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.sessions_delete))
            }
        }
    }
}

@Composable
private fun Banner(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                color = content,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_dismiss), tint = content)
                }
            }
        }
    }
}
