package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.Attachment
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
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
    attachments: List<Attachment>,
    thumbnails: Map<String, ImageBitmap>,
    onAttachFromDevice: () -> Unit,
    onAttachFromProject: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Picked files still being read; Send waits for them. */
    pendingAttachments: Int = 0,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            if (variants.isNotEmpty()) {
                VariantChip(variants = variants, selected = selectedVariant, onSelect = onSelectVariant)
            }
            if (attachments.isNotEmpty() || pendingAttachments > 0) {
                AttachmentRow(attachments, thumbnails, pendingAttachments, onRemoveAttachment)
            }
            PromptRow(
                text = text,
                onTextChange = onTextChange,
                canSend = pendingAttachments == 0 && (text.isNotBlank() || attachments.isNotEmpty()),
                busy = busy,
                onSend = onSend,
                onStop = onStop,
                onAttachFromDevice = onAttachFromDevice,
                onAttachFromProject = onAttachFromProject,
                focusRequester = focusRequester,
            )
        }
    }
}

@Composable
private fun AttachmentRow(
    attachments: List<Attachment>,
    thumbnails: Map<String, ImageBitmap>,
    pending: Int,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            key(attachment.id) {
                InputChip(
                    selected = false,
                    onClick = { onRemove(attachment.id) },
                    label = {
                        Text(
                            text = attachment.filename.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    },
                    avatar = {
                        val preview = thumbnails[attachment.id]
                        if (preview != null) {
                            Image(
                                bitmap = preview,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(InputChipDefaults.AvatarSize).clip(RoundedCornerShape(4.dp)),
                            )
                        } else {
                            Icon(
                                attachmentIcon(attachment.mime),
                                contentDescription = null,
                                modifier = Modifier.size(InputChipDefaults.AvatarSize),
                            )
                        }
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.attach_remove),
                            modifier = Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        if (pending > 0) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.attach_reading)) },
                leadingIcon = {
                    CircularProgressIndicator(modifier = Modifier.size(AssistChipDefaults.IconSize), strokeWidth = 2.dp)
                },
            )
        }
    }
}

/** Icon for an attached file by its MIME type. */
internal fun attachmentIcon(mime: String?): ImageVector = when {
    mime == null -> Icons.Filled.AttachFile
    mime.startsWith("image/") -> Icons.Filled.Image
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    mime == "application/x-directory" -> Icons.Filled.Folder
    else -> Icons.Filled.Description
}

@Composable
private fun PromptRow(
    text: String,
    onTextChange: (String) -> Unit,
    canSend: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachFromDevice: () -> Unit,
    onAttachFromProject: () -> Unit,
    focusRequester: FocusRequester,
) {
    // The field takes the whole width; Attach sits above Send in a column on
    // the right, so the text gets the room the left-hand button used to take.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            modifier = Modifier.weight(1f).heightIn(max = 160.dp).focusRequester(focusRequester),
            // Two lines tall at rest: as tall as the button column beside it.
            minLines = 2,
            maxLines = 6,
            // A chat message, not a code field: start sentences with a capital.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
            ),
        )
        Spacer(Modifier.width(4.dp))
        // 40dp buttons (the visible size of an IconButton anyway) keep the
        // column as tall as a two-line field instead of 96dp.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides PROMPT_BUTTON_SIZE) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AttachButton(onAttachFromDevice, onAttachFromProject)
                if (busy) {
                    IconButton(onClick = onStop, modifier = Modifier.size(PROMPT_BUTTON_SIZE)) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop), tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(PROMPT_BUTTON_SIZE)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                    }
                }
            }
        }
    }
}

private val PROMPT_BUTTON_SIZE = 40.dp

@Composable
private fun AttachButton(onFromDevice: () -> Unit, onFromProject: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(PROMPT_BUTTON_SIZE)) {
            Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach_file))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attach_from_device)) },
                leadingIcon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null) },
                onClick = {
                    expanded = false
                    onFromDevice()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attach_from_project)) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                onClick = {
                    expanded = false
                    onFromProject()
                },
            )
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
