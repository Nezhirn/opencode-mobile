package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ChatMessageUi
import ai.opencode.mobile.data.userText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The web client's "rolled back messages" dock above the prompt: how many
 * messages the rollback hides, and a Restore button for each of them.
 */
@Composable
internal fun RevertDock(
    messages: List<ChatMessageUi>,
    reverting: Boolean,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pluralStringResource(R.plurals.revert_summary, messages.size, messages.size),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (!expanded) {
                        Text(
                            text = stringResource(R.string.revert_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (reverting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.revert_collapse else R.string.revert_expand),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
                ) {
                    messages.forEach { message ->
                        key(message.info.id) {
                            val text = remember(message.parts) {
                                message.userText().lineSequence().firstOrNull { it.isNotBlank() }
                                    ?: message.parts.firstNotNullOfOrNull { it.filename }
                                    ?: ""
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onRestore(message.info.id) }, enabled = !reverting) {
                                    Text(stringResource(R.string.revert_restore))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
