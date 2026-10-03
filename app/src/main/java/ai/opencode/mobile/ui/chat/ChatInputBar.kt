package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Prompt field with Send, or Stop while a run is in progress. Stateless: the
 * draft lives in the ViewModel, which can give it back if sending fails. Models
 * with variants get a reasoning-effort chip above the field, as in the web client.
 */
@Composable
internal fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    variants: List<String>,
    selectedVariant: String?,
    onSelectVariant: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            if (variants.isNotEmpty()) {
                VariantChip(variants = variants, selected = selectedVariant, onSelect = onSelectVariant)
            }
            PromptRow(text, onTextChange, busy, onSend, onStop)
        }
    }
}

@Composable
private fun PromptRow(
    text: String,
    onTextChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            modifier = Modifier.weight(1f).heightIn(max = 160.dp),
            maxLines = 6,
            // A chat message, not a code field: start sentences with a capital.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
            ),
        )
        Spacer(Modifier.width(8.dp))
        if (busy) {
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop), tint = MaterialTheme.colorScheme.error)
            }
        } else {
            IconButton(onClick = onSend, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}

@Composable
private fun VariantChip(variants: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(selected?.let(::variantLabel) ?: stringResource(R.string.variant_default)) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = stringResource(R.string.variant_title),
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf<String?>(null) + variants).forEach { variant ->
                DropdownMenuItem(
                    text = { Text(variant?.let(::variantLabel) ?: stringResource(R.string.variant_default)) },
                    trailingIcon = {
                        if (variant == selected) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSelect(variant)
                    },
                )
            }
        }
    }
}

/** "xhigh" -> "Xhigh", as the web client shows variant names. */
internal fun variantLabel(name: String): String = name.replaceFirstChar { it.titlecase(Locale.ROOT) }
