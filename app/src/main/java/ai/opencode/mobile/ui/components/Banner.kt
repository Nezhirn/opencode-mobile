package ai.opencode.mobile.ui.components

import ai.opencode.mobile.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** A full-width status strip (connection state, errors, pending requests). */
@Composable
internal fun Banner(
    text: String,
    container: Color,
    content: Color,
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
