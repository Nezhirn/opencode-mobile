package ai.opencode.mobile.ui.mcp

import ai.opencode.mobile.R
import ai.opencode.mobile.data.McpServer
import ai.opencode.mobile.data.UiText
import ai.opencode.mobile.ui.asString
import ai.opencode.mobile.ui.theme.LocalGnomeAccents
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Top bar action opening the MCP panel; the dot shows that a server is connected. */
@Composable
internal fun McpButton(servers: List<McpServer>?, onClick: () -> Unit) {
    val anyConnected = servers?.any { it.connected } == true
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (anyConnected) Badge(containerColor = LocalGnomeAccents.current.success)
            },
        ) {
            Icon(Icons.Filled.Dns, contentDescription = stringResource(R.string.mcp_title))
        }
    }
}

/**
 * The web client's MCP tab: every server opencode knows with its status and a
 * switch that connects or disconnects it. Statuses are re-read on open, since
 * they change on the server without an event for every transition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpSheet(
    servers: List<McpServer>?,
    toggling: Set<String>,
    error: UiText?,
    onOpen: () -> Unit,
    onToggle: (name: String, enabled: Boolean) -> Unit,
    onDismissError: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onOpen() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.mcp_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.mcp_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            error?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.asString(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_dismiss)) }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            when {
                servers == null -> item(key = "mcp-loading") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.mcp_loading), style = MaterialTheme.typography.bodySmall)
                    }
                }

                servers.isEmpty() -> item(key = "mcp-empty") {
                    Text(
                        text = stringResource(R.string.mcp_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }

                else -> items(servers, key = { it.name }) { server ->
                    McpRow(
                        server = server,
                        busy = server.name in toggling,
                        onToggle = { enabled -> onToggle(server.name, enabled) },
                    )
                }
            }
        }
    }
}

@Composable
private fun McpRow(server: McpServer, busy: Boolean, onToggle: (Boolean) -> Unit) {
    val failed = server.status in FAILURE_STATUSES
    val dotColor = when {
        server.connected -> LocalGnomeAccents.current.success
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(server.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(statusLabel(server.status), server.error).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (busy) {
            Box(modifier = Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            // On means "connected", as in the web client: a failed server shows
            // off, and switching it on retries the connection.
            Switch(checked = server.connected, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    McpServer.STATUS_CONNECTED -> stringResource(R.string.mcp_status_connected)
    McpServer.STATUS_DISABLED -> stringResource(R.string.mcp_status_disabled)
    McpServer.STATUS_FAILED -> stringResource(R.string.mcp_status_failed)
    McpServer.STATUS_NEEDS_AUTH -> stringResource(R.string.mcp_status_needs_auth)
    McpServer.STATUS_NEEDS_CLIENT_REGISTRATION -> stringResource(R.string.mcp_status_needs_client_registration)
    else -> status
}

private val FAILURE_STATUSES = setOf(
    McpServer.STATUS_FAILED,
    McpServer.STATUS_NEEDS_AUTH,
    McpServer.STATUS_NEEDS_CLIENT_REGISTRATION,
)
