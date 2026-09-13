package ai.opencode.mobile.ui.components

import ai.opencode.mobile.R
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextStyle

/**
 * Maximum number of characters rendered at once. Very large tool outputs, file
 * contents and patches are measured on the main thread; capping the initial size
 * keeps layout cheap while still allowing the user to reveal everything.
 */
const val MAX_DISPLAY_CHARS = 10_000

@Composable
fun TruncatedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    val isTruncated = !expanded && text.length > MAX_DISPLAY_CHARS

    Column(modifier = modifier) {
        Text(
            text = if (isTruncated) text.take(MAX_DISPLAY_CHARS) else text,
            style = style,
        )
        if (isTruncated) {
            TextButton(onClick = { expanded = true }) {
                Text(pluralStringResource(R.plurals.files_show_all, text.length, text.length))
            }
        }
    }
}
