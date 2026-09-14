package ai.opencode.mobile.ui.components

import ai.opencode.mobile.R
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextStyle

/** Characters rendered before the first "show more". */
const val INITIAL_DISPLAY_CHARS = 4_000

/** Characters added per "show more" tap. */
const val DISPLAY_STEP_CHARS = 8_000

/**
 * Text is laid out on the main thread in one pass per node, and cost grows
 * faster than linearly for a single huge node. Splitting into bounded chunks
 * keeps each measure pass short and lets a LazyColumn skip off-screen ones.
 */
internal const val LAYOUT_CHUNK_CHARS = 2_000

/**
 * Splits at line boundaries near [size] so chunk edges do not change wrapping.
 */
internal fun String.chunkedForLayout(size: Int = LAYOUT_CHUNK_CHARS): List<String> {
    if (length <= size) return listOf(this)
    val result = ArrayList<String>(length / size + 1)
    var start = 0
    while (start < length) {
        val end = (start + size).coerceAtMost(length)
        val cut = if (end == length) {
            end
        } else {
            lastIndexOf('\n', end).takeIf { it > start }?.plus(1) ?: end
        }
        result.add(substring(start, cut))
        start = cut
    }
    return result
}

/**
 * Renders a bounded prefix of [text], revealing more on demand.
 *
 * Previously "show all" handed the entire string to a single [Text]; on a large
 * file or a big tool output that measured megabytes of monospace text on the
 * main thread and hung the app. The reveal is now incremental and the visible
 * part is chunked.
 *
 * [stateKey] identifies the content being shown. It deliberately does not
 * default to [text]: keying on the text itself collapsed an expanded block on
 * every streamed token.
 */
@Composable
fun TruncatedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    stateKey: Any? = Unit,
) {
    var limit by remember(stateKey) { mutableIntStateOf(INITIAL_DISPLAY_CHARS) }
    val shown = if (text.length <= limit) text else text.take(limit)
    val hidden = text.length - shown.length

    Column(modifier = modifier) {
        ChunkedText(text = shown, style = style)
        if (hidden > 0) {
            val step = minOf(hidden, DISPLAY_STEP_CHARS)
            TextButton(onClick = { limit += DISPLAY_STEP_CHARS }) {
                Text(pluralStringResource(R.plurals.files_show_more, step, step))
            }
        }
    }
}

@Composable
private fun ChunkedText(text: String, style: TextStyle) {
    val chunks = remember(text) { text.chunkedForLayout() }
    chunks.forEach { chunk -> Text(text = chunk, style = style) }
}
