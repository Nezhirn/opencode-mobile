package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.Todo
import ai.opencode.mobile.ui.components.TruncatedText
import ai.opencode.mobile.ui.components.stripAnsi
import ai.opencode.mobile.ui.markdown.MarkdownText
import ai.opencode.mobile.ui.theme.LocalGnomeAccents
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** Renders one part of an assistant message. Callers key it by part id. */
@Composable
internal fun AssistantPart(part: Part) {
    when (part.type) {
        "text" -> {
            // Synthetic/ignored parts are context the server injected, not
            // something the assistant said; empty ones would only add a gap.
            if (part.synthetic == true || part.ignored == true || part.text.isNullOrBlank()) return
            MarkdownText(text = part.text, stateKey = part.id)
        }

        "reasoning" -> if (!part.text.isNullOrBlank()) ReasoningBlock(part.text, stateKey = part.id)

        "tool" -> ToolCard(part)

        "step-start" -> StepDivider(label = stringResource(R.string.chat_step))

        "step-finish" -> StepFinish(part)

        "file" -> FileChip(part.filename ?: part.url.orEmpty())

        "patch" -> PatchChip(part.files.orEmpty())

        "retry" -> Text(
            text = stringResource(R.string.chat_retrying, part.attempt ?: 1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        "compaction" -> StepDivider(label = stringResource(R.string.chat_context_compacted))

        "subtask" -> Text(
            text = stringResource(R.string.chat_subtask, part.description.orEmpty()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        "snapshot" -> Unit

        "agent" -> part.name?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                text = stringResource(R.string.chat_agent, name),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Never swallow a part silently: show its type so new server part kinds
        // are at least visible instead of disappearing.
        else -> {
            if (part.type.isNotBlank()) {
                Text(
                    text = part.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReasoningBlock(text: String, stateKey: Any) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                Text(
                    text = stringResource(R.string.chat_thinking),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Laid out only while expanded, and bounded like any other long
            // text: a reasoning trace can be as long as the answer itself.
            if (expanded) {
                MarkdownText(
                    text = text,
                    stateKey = stateKey,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCard(part: Part) {
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    val state = part.state
    val status = state?.status ?: "pending"
    val accents = LocalGnomeAccents.current
    val (statusColor, statusLabel) = when (status) {
        "completed" -> accents.success to "done"
        "error" -> MaterialTheme.colorScheme.error to "error"
        "running" -> MaterialTheme.colorScheme.primary to "running"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to status
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = part.tool.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = state?.title ?: statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status == "running") {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                state?.input?.let { input ->
                    // Keyed on the input itself: an unchanged part hands over the
                    // same instance, so the key check is an identity comparison and
                    // the costly toString() runs only when the input really changed
                    // (keying on the status left it stale while arguments streamed).
                    MonoBlock(
                        title = "input",
                        text = remember(input) { input.toString() },
                        stateKey = "${part.id}-input",
                    )
                }
                state?.output?.let { output ->
                    MonoBlock(title = "output", text = output, stateKey = "${part.id}-output")
                }
                state?.error?.let { error ->
                    MonoBlock(title = "error", text = error, stateKey = "${part.id}-error")
                }
            }
        }
    }
}

@Composable
private fun MonoBlock(title: String, text: String, stateKey: Any? = Unit) {
    val clean = remember(text) { text.stripAnsi() }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            // Theme-derived, so the block does not turn into a black slab in the
            // light scheme the way a hardcoded translucent black did.
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        ) {
            SelectionContainer {
                TruncatedText(
                    text = clean,
                    stateKey = stateKey,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun StepDivider(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text(
            text = "  $label  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

@Composable
private fun StepFinish(part: Part) {
    val tokens = part.tokens
    val info = buildString {
        // Safe cast, not `.jsonPrimitive`: that accessor throws when the server
        // sends an object (opencode already does for `tokens.cache`), and the
        // crash repeated on every recomposition because the part is kept in
        // repository state.
        tokens?.tokenCount("input")?.let { append("in $it ") }
        tokens?.tokenCount("output")?.let { append("out $it ") }
        // Fixed locale: a cost is not a localised number ("$0,0012").
        part.cost?.takeIf { it > 0 }?.let { append("$" + String.format(Locale.US, "%.4f", it)) }
    }.trim()
    if (info.isNotEmpty()) {
        Text(
            text = info,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

private fun JsonObject.tokenCount(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

@Composable
internal fun FileChip(name: String) {
    // Not clickable: this is a label for an attached file, not an action.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PatchChip(files: List<String>) {
    if (files.isEmpty()) return
    val names = remember(files) { files.joinToString(", ") { it.substringAfterLast('/') } }
    Text(
        text = stringResource(R.string.chat_changed_files, names),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun TodoStrip(todos: List<Todo>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        todos.forEach { todo ->
            val color = when (todo.status) {
                "completed" -> MaterialTheme.colorScheme.primaryContainer
                "in_progress" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(color = color, shape = RoundedCornerShape(50)) {
                Text(
                    text = todo.content,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
        }
    }
}
