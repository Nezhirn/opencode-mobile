package ai.opencode.mobile.ui.components

import ai.opencode.mobile.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle

/** Characters rendered before the first "show more". */
const val INITIAL_DISPLAY_CHARS = 4_000

/** Characters added per "show more" tap. */
const val DISPLAY_STEP_CHARS = 8_000

/**
 * Most text a reveal will ever lay out. "Show all" is capped here: rendering
 * megabytes of text on the main thread is what hung the app in the first place.
 */
const val MAX_REVEAL_CHARS = 200_000

/**
 * Text is laid out on the main thread in one pass per node, and cost grows
 * faster than linearly for a single huge node. Splitting into bounded chunks
 * keeps each measure pass short and lets a LazyColumn skip off-screen ones.
 */
internal const val LAYOUT_CHUNK_CHARS = 2_000

/**
 * The first [max] characters, never ending in the middle of a surrogate pair
 * (which would render as a replacement glyph).
 */
internal fun String.safePrefix(max: Int): String {
    if (length <= max) return this
    var end = max.coerceAtLeast(0)
    if (end > 0 && Character.isHighSurrogate(this[end - 1])) end -= 1
    return substring(0, end)
}

/**
 * Splits at line boundaries near [size] so chunk edges do not change wrapping.
 * A line longer than [size] is cut hard, but never inside a surrogate pair. The
 * boundary newline stays at the end of the earlier chunk, so the chunks
 * concatenate back to the original text (which is what a copy across them
 * yields).
 */
internal fun String.chunkedForLayout(size: Int = LAYOUT_CHUNK_CHARS): List<String> {
    if (length <= size) return listOf(this)
    val result = ArrayList<String>(length / size + 1)
    var start = 0
    while (start < length) {
        val end = (start + size).coerceAtMost(length)
        var cut = if (end == length) {
            end
        } else {
            lastIndexOf('\n', end - 1).takeIf { it > start }?.plus(1) ?: end
        }
        if (cut < length && cut - start > 1 && Character.isHighSurrogate(this[cut - 1])) cut -= 1
        result.add(substring(start, cut))
        start = cut
    }
    return result
}

/**
 * Shows a bounded prefix of [text] through [content], with controls to reveal
 * more, all (up to [MAX_REVEAL_CHARS]) or collapse again.
 *
 * [stateKey] identifies the content being shown. It deliberately does not
 * default to [text]: keying on the text itself collapsed an expanded block on
 * every streamed token.
 */
@Composable
fun RevealableText(
    text: String,
    modifier: Modifier = Modifier,
    stateKey: Any? = Unit,
    initialChars: Int = INITIAL_DISPLAY_CHARS,
    stepChars: Int = DISPLAY_STEP_CHARS,
    content: @Composable (shown: String) -> Unit,
) {
    var limit by remember(stateKey) { mutableIntStateOf(initialChars) }
    val shown = remember(text, limit) { text.safePrefix(limit) }
    val hidden = text.length - shown.length

    Column(modifier = modifier) {
        content(shown)
        val canCollapse = limit > initialChars && text.length > initialChars
        val canReveal = hidden > 0 && limit < MAX_REVEAL_CHARS
        if (canReveal || canCollapse) {
            Row {
                if (canReveal) {
                    val step = minOf(hidden, stepChars)
                    TextButton(onClick = { limit = (limit + stepChars).coerceAtMost(MAX_REVEAL_CHARS) }) {
                        Text(pluralStringResource(R.plurals.files_show_more, step, step))
                    }
                    if (hidden > step && text.length <= MAX_REVEAL_CHARS) {
                        TextButton(onClick = { limit = MAX_REVEAL_CHARS }) {
                            Text(stringResource(R.string.text_show_all))
                        }
                    }
                }
                if (canCollapse) {
                    TextButton(onClick = { limit = initialChars }) {
                        Text(stringResource(R.string.text_show_less))
                    }
                }
            }
        }
    }
}

/**
 * Renders a bounded prefix of plain [text], revealing more on demand.
 *
 * Previously "show all" handed the entire string to a single [Text]; on a large
 * file or a big tool output that measured megabytes of monospace text on the
 * main thread and hung the app. The reveal is incremental and capped, and the
 * visible part is chunked.
 */
@Composable
fun TruncatedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    stateKey: Any? = Unit,
) {
    RevealableText(text = text, modifier = modifier, stateKey = stateKey) { shown ->
        ChunkedText(text = shown, style = style)
    }
}

@Composable
private fun ChunkedText(text: String, style: TextStyle) {
    val chunks = remember(text) { text.chunkedForLayout() }
    chunks.forEach { chunk -> Text(text = chunk, style = style) }
}
