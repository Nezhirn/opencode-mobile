package ai.opencode.mobile.ui.markdown

import ai.opencode.mobile.ui.components.RevealableText
import ai.opencode.mobile.ui.components.chunkedForLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Characters of Markdown shown before "show more". Higher than for plain tool
 * output: this is the answer itself, and it is rendered as many small blocks,
 * not one huge text node, so a long reply stays cheap to lay out.
 */
private const val MARKDOWN_INITIAL_CHARS = 30_000
private const val MARKDOWN_STEP_CHARS = 30_000

private val LIST_INDENT = 20.dp

/**
 * Renders assistant Markdown: paragraphs with inline formatting and clickable
 * links, headings, lists, quotes, code blocks (monospace, horizontally
 * scrollable) and tables. The source is truncated before parsing, so streaming a
 * very long reply never parses or lays out more than the visible prefix.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    stateKey: Any? = Unit,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    RevealableText(
        text = text,
        modifier = modifier,
        stateKey = stateKey,
        initialChars = MARKDOWN_INITIAL_CHARS,
        stepChars = MARKDOWN_STEP_CHARS,
    ) { shown ->
        val colors = MarkdownColors(
            link = MaterialTheme.colorScheme.primary,
            codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        val blocks = remember(shown, colors) { parseMarkdown(shown, colors) }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                blocks.forEach { block -> MarkdownBlock(block, style, color) }
            }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, style: TextStyle, color: Color) {
    // A list marker sits in the gutter of its own level.
    val levels = if (block is MdBlock.Paragraph && block.marker != null) block.indent - 1 else block.indent
    val modifier = Modifier.padding(start = LIST_INDENT * levels.coerceAtLeast(0))
    if (block.quoteDepth == 0) {
        Box(modifier) { BlockContent(block, style, color) }
    } else {
        Row(modifier.height(IntrinsicSize.Min)) {
            repeat(block.quoteDepth) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(Modifier.width(8.dp))
            }
            BlockContent(block, style, color.takeOrElse(MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

private fun Color.takeOrElse(fallback: Color): Color = if (this == Color.Unspecified) fallback else this

@Composable
private fun BlockContent(block: MdBlock, style: TextStyle, color: Color) {
    when (block) {
        is MdBlock.Paragraph -> {
            val marker = block.marker
            if (marker == null) {
                Text(block.text, style = style, color = color)
            } else {
                Row {
                    Text(marker, style = style, color = color, modifier = Modifier.width(LIST_INDENT))
                    Text(block.text, style = style, color = color)
                }
            }
        }

        is MdBlock.Heading -> Text(
            text = block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(top = 4.dp),
        )

        is MdBlock.Code -> CodeBlock(block)

        is MdBlock.Table -> TableBlockView(block, style, color)

        is MdBlock.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    // Chunked like other large text so a long listing is never one huge node.
    val chunks = remember(block.code) { block.code.chunkedForLayout() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (block.language.isNotBlank()) {
                Text(
                    text = block.language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // Code keeps its lines: wrapping would break indentation, so long
            // lines scroll horizontally instead.
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                chunks.forEach { chunk -> Text(chunk, style = mono, softWrap = false) }
            }
        }
    }
}

/**
 * Column-major layout: each column is as wide as its widest cell. Cells do not
 * wrap, so every row has the same height in every column and the grid lines up;
 * a wide table scrolls horizontally.
 */
@Composable
private fun TableBlockView(block: MdBlock.Table, style: TextStyle, color: Color) {
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
            for (column in 0 until columns) {
                // Intrinsic width: inside a horizontal scroll the divider would
                // otherwise get unbounded constraints and collapse to nothing.
                Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                    if (block.header.isNotEmpty()) {
                        TableCellText(block.header.getOrNull(column), style.copy(fontWeight = FontWeight.SemiBold), color)
                        HorizontalDivider()
                    }
                    block.rows.forEach { row -> TableCellText(row.getOrNull(column), style, color) }
                }
            }
        }
    }
}

@Composable
private fun TableCellText(text: AnnotatedString?, style: TextStyle, color: Color) {
    Text(
        text = text ?: AnnotatedString(""),
        style = style,
        color = color,
        softWrap = false,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
