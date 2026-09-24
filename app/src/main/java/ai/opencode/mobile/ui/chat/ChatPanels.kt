@file:OptIn(ExperimentalLayoutApi::class)

package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.QuestionRequest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pending permission requests. [replying] holds the ids whose answer is in
 * flight; their buttons are disabled so a second tap cannot send a second answer.
 * [maxHeight] comes from the space actually left on screen (it shrinks while the
 * keyboard is open), so the message list above always keeps some room.
 */
@Composable
internal fun PermissionPanel(
    permissions: List<PermissionRequest>,
    replying: Set<String>,
    maxHeight: Dp,
    onReply: (String, String) -> Unit,
) {
    // Capped and scrollable: as an unbounded sibling of the weighted message
    // list this squeezed the list to zero height and pushed the input bar off
    // screen, leaving no way to answer or type.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        permissions.forEach { request ->
            key(request.id) {
                PermissionCard(request, enabled = request.id !in replying, onReply = onReply)
            }
        }
    }
}

@Composable
private fun PermissionCard(request: PermissionRequest, enabled: Boolean, onReply: (String, String) -> Unit) {
    val patterns = remember(request.patterns) { request.patterns.joinToString("\n") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.chat_permission, request.permission),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            if (patterns.isNotEmpty()) {
                Text(
                    text = patterns,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Wraps instead of squeezing: in one Row the last button was left
            // a few pixels and "Reject" rendered one letter per line.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(enabled = enabled, onClick = { onReply(request.id, "once") }) {
                    Text(stringResource(R.string.chat_allow_once))
                }
                OutlinedButton(enabled = enabled, onClick = { onReply(request.id, "always") }) {
                    Text(stringResource(R.string.chat_always))
                }
                TextButton(enabled = enabled, onClick = { onReply(request.id, "reject") }) {
                    Text(stringResource(R.string.chat_reject))
                }
            }
        }
    }
}

@Composable
internal fun QuestionPanel(
    questions: List<QuestionRequest>,
    replying: Set<String>,
    maxHeight: Dp,
    onReply: (String, List<List<String>>) -> Unit,
    onReject: (String) -> Unit,
) {
    // See PermissionPanel: a multi-question card is easily taller than the
    // screen and must not push the input bar out of the layout.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        questions.forEach { request ->
            key(request.id) {
                QuestionCard(
                    request = request,
                    enabled = request.id !in replying,
                    onReply = onReply,
                    onReject = onReject,
                )
            }
        }
    }
}

@Composable
private fun QuestionCard(
    request: QuestionRequest,
    enabled: Boolean,
    onReply: (String, List<List<String>>) -> Unit,
    onReject: (String) -> Unit,
) {
    val selections = remember(request.id) {
        mutableStateOf(List(request.questions.size) { emptySet<String>() })
    }
    val customs = remember(request.id) {
        mutableStateOf(List(request.questions.size) { "" })
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(modifier = Modifier.padding(12.dp)) {
            request.questions.forEachIndexed { qIndex, question ->
                Text(
                    text = question.header.ifBlank { stringResource(R.string.chat_question) },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    question.options.forEach { option ->
                        val selected = selections.value[qIndex].contains(option.label)
                        FilterChip(
                            selected = selected,
                            enabled = enabled,
                            onClick = {
                                val current = selections.value.toMutableList()
                                val set = current[qIndex].toMutableSet()
                                if (question.multiple == true) {
                                    if (selected) set.remove(option.label) else set.add(option.label)
                                } else {
                                    set.clear()
                                    if (!selected) set.add(option.label)
                                }
                                current[qIndex] = set
                                selections.value = current
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                if (question.custom == true) {
                    OutlinedTextField(
                        value = customs.value[qIndex],
                        onValueChange = { value ->
                            val current = customs.value.toMutableList()
                            current[qIndex] = value
                            customs.value = current
                        },
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        placeholder = { Text(stringResource(R.string.chat_custom_answer)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                if (qIndex != request.questions.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    enabled = enabled,
                    onClick = {
                        val answers = request.questions.indices.map { index ->
                            val selected = selections.value[index].toMutableList()
                            customs.value[index].takeIf { it.isNotBlank() }?.let { selected.add(it) }
                            selected.toList()
                        }
                        onReply(request.id, answers)
                    },
                ) { Text(stringResource(R.string.chat_send)) }
                TextButton(enabled = enabled, onClick = { onReject(request.id) }) {
                    Text(stringResource(R.string.chat_reject))
                }
            }
        }
    }
}
