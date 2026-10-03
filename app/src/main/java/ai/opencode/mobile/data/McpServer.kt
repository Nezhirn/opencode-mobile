package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.McpStatus
import androidx.compose.runtime.Immutable

/** An MCP server as shown in the MCP panel. */
@Immutable
data class McpServer(
    val name: String,
    val status: String,
    val error: String? = null,
) {
    val connected: Boolean get() = status == STATUS_CONNECTED

    companion object {
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISABLED = "disabled"
        const val STATUS_FAILED = "failed"
        const val STATUS_NEEDS_AUTH = "needs_auth"
        const val STATUS_NEEDS_CLIENT_REGISTRATION = "needs_client_registration"
    }
}

/** Blank names would break the keyed list; the order is stable by name. */
internal fun Map<String, McpStatus>.toMcpServers(): List<McpServer> =
    filterKeys { it.isNotBlank() }
        .map { (name, status) -> McpServer(name, status.status, status.error?.takeIf { it.isNotBlank() }) }
        .sortedBy { it.name.lowercase() }
