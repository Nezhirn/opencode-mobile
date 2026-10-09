package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ContextCategory
import ai.opencode.mobile.data.ContextStats
import ai.opencode.mobile.ui.components.TruncatedText
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

/**
 * Top bar action: a ring filled to the share of the model's context window the
 * latest reply used, as the web client shows next to the session title.
 */
@Composable
internal fun ContextButton(usagePercent: Int?, onClick: () -> Unit) {
    val description = stringResource(R.string.context_view)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
            val fraction = ((usagePercent ?: 0) / 100f).coerceIn(0f, 1f)
            val color = when {
                (usagePercent ?: 0) >= HIGH_USAGE_PERCENT -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(20.dp),
                strokeWidth = 3.dp,
                color = color,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
        }
    }
}

private const val HIGH_USAGE_PERCENT = 80

/** The web client's context panel: usage, token counts, cost and an estimated breakdown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextSheet(stats: ContextStats?, sessionTitle: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.context_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (stats == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            val numbers = NumberFormat.getIntegerInstance()
            val usage = stats.usage
            if (usage != null && stats.limit != null) {
                val percent = stats.usagePercent ?: 0
                Text(
                    text = "${numbers.format(usage.total)} / ${numbers.format(stats.limit)} · $percent%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (percent >= HIGH_USAGE_PERCENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
            } else if (usage == null) {
                Text(
                    text = stringResource(R.string.context_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }

            val dash = "—"
            StatRow(stringResource(R.string.context_session), sessionTitle.ifBlank { dash })
            StatRow(stringResource(R.string.context_messages), numbers.format(stats.messages))
            StatRow(stringResource(R.string.context_provider), stats.provider ?: dash)
            StatRow(stringResource(R.string.context_model), stats.model ?: dash)
            StatRow(stringResource(R.string.context_limit), stats.limit?.let(numbers::format) ?: dash)
            StatRow(stringResource(R.string.context_total_tokens), usage?.total?.let(numbers::format) ?: dash)
            StatRow(stringResource(R.string.context_usage), stats.usagePercent?.let { "$it%" } ?: dash)
            StatRow(stringResource(R.string.context_input_tokens), usage?.input?.let(numbers::format) ?: dash)
            StatRow(stringResource(R.string.context_output_tokens), usage?.output?.let(numbers::format) ?: dash)
            StatRow(stringResource(R.string.context_reasoning_tokens), usage?.reasoning?.let(numbers::format) ?: dash)
            StatRow(
                stringResource(R.string.context_cache_tokens),
                usage?.let { "${numbers.format(it.cacheRead)} / ${numbers.format(it.cacheWrite)}" } ?: dash,
            )
            StatRow(stringResource(R.string.context_user_messages), numbers.format(stats.userMessages))
            StatRow(stringResource(R.string.context_assistant_messages), numbers.format(stats.assistantMessages))
            // Fixed locale: a cost is not a localised number ("$0,0012").
            StatRow(stringResource(R.string.context_total_cost), "$" + String.format(Locale.US, "%.4f", stats.totalCost))
            StatRow(stringResource(R.string.context_created), stats.created?.let { formatTime(it) } ?: dash)
            StatRow(stringResource(R.string.context_last_activity), stats.lastActivity?.let { formatTime(it) } ?: dash)

            if (stats.breakdown.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.context_breakdown), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.context_breakdown_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                ) {
                    stats.breakdown.forEach { segment ->
                        Box(
                            modifier = Modifier
                                .weight(segment.percent.toFloat().coerceAtLeast(MIN_SEGMENT_WEIGHT))
                                .fillMaxHeight()
                                .background(categoryColor(segment.category)),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                stats.breakdown.forEach { segment ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(categoryColor(segment.category)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(categoryLabel(segment.category)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${numbers.format(segment.tokens)} · ${segment.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            stats.systemPrompt?.let { prompt ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                var expanded by rememberSaveable { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.context_system_prompt),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
                if (expanded) {
                    SelectionContainer {
                        TruncatedText(
                            text = prompt,
                            stateKey = "system-prompt",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** Keeps tiny segments visible in the bar. */
private const val MIN_SEGMENT_WEIGHT = 0.5f

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun formatTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun categoryLabel(category: ContextCategory): Int = when (category) {
    ContextCategory.SYSTEM -> R.string.context_breakdown_system
    ContextCategory.USER -> R.string.context_breakdown_user
    ContextCategory.ASSISTANT -> R.string.context_breakdown_assistant
    ContextCategory.TOOL -> R.string.context_breakdown_tool
    ContextCategory.OTHER -> R.string.context_breakdown_other
}

private fun categoryColor(category: ContextCategory): Color = when (category) {
    ContextCategory.SYSTEM -> Color(0xFF8E7CC3)
    ContextCategory.USER -> Color(0xFF3CB6E0)
    ContextCategory.ASSISTANT -> Color(0xFF34C3A0)
    ContextCategory.TOOL -> Color(0xFFF08A3C)
    ContextCategory.OTHER -> Color(0xFF9E9E9E)
}
