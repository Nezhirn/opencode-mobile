package ai.opencode.mobile.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** Colours the inline markup needs; taken from the theme by the renderer. */
@Immutable
data class MarkdownColors(
    val link: Color,
    val codeBackground: Color,
)

/**
 * A rendered block of a Markdown document, flattened for a simple Column
 * layout. [indent] is the list nesting level, [quoteDepth] the blockquote
 * nesting level.
 */
@Immutable
sealed interface MdBlock {
    val indent: Int
    val quoteDepth: Int

    /** A paragraph; [marker] is set for the first paragraph of a list item ("•", "3."). */
    data class Paragraph(
        val text: AnnotatedString,
        val marker: String? = null,
        override val indent: Int = 0,
        override val quoteDepth: Int = 0,
    ) : MdBlock

    data class Heading(
        val level: Int,
        val text: AnnotatedString,
        override val indent: Int = 0,
        override val quoteDepth: Int = 0,
    ) : MdBlock

    data class Code(
        val language: String,
        val code: String,
        override val indent: Int = 0,
        override val quoteDepth: Int = 0,
    ) : MdBlock

    data class Table(
        val header: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
        override val indent: Int = 0,
        override val quoteDepth: Int = 0,
    ) : MdBlock

    data class Rule(
        override val indent: Int = 0,
        override val quoteDepth: Int = 0,
    ) : MdBlock
}

private val parser: Parser by lazy {
    Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .build()
}

/**
 * Parses assistant Markdown into blocks. Safe on partial input: an unclosed code
 * fence (mid-stream, or cut by a "show more" limit) simply runs to the end of the
 * text, which is how it should look while the rest is still arriving.
 */
fun parseMarkdown(source: String, colors: MarkdownColors): List<MdBlock> {
    if (source.isBlank()) return emptyList()
    val document = parser.parse(source)
    val blocks = ArrayList<MdBlock>()
    BlockCollector(colors, blocks).visitChildren(document, indent = 0, quoteDepth = 0)
    return blocks
}

private class BlockCollector(
    private val colors: MarkdownColors,
    private val out: MutableList<MdBlock>,
) {
    fun visitChildren(parent: Node, indent: Int, quoteDepth: Int) {
        var child = parent.firstChild
        while (child != null) {
            visitBlock(child, indent, quoteDepth, marker = null)
            child = child.next
        }
    }

    private fun visitBlock(node: Node, indent: Int, quoteDepth: Int, marker: String?) {
        when (node) {
            is Paragraph -> out += MdBlock.Paragraph(inline(node), marker, indent, quoteDepth)
            is Heading -> out += MdBlock.Heading(node.level, inline(node), indent, quoteDepth)
            is FencedCodeBlock -> out += MdBlock.Code(
                language = node.info.orEmpty().substringBefore(' '),
                code = node.literal.orEmpty().removeSuffix("\n"),
                indent = indent,
                quoteDepth = quoteDepth,
            )
            is IndentedCodeBlock -> out += MdBlock.Code("", node.literal.orEmpty().removeSuffix("\n"), indent, quoteDepth)
            is HtmlBlock -> out += MdBlock.Paragraph(AnnotatedString(node.literal.orEmpty().trimEnd()), marker, indent, quoteDepth)
            is ThematicBreak -> out += MdBlock.Rule(indent, quoteDepth)
            is BlockQuote -> visitChildren(node, indent, quoteDepth + 1)
            is BulletList -> visitList(node, indent, quoteDepth) { "•" }
            is OrderedList -> {
                val start = node.markerStartNumber ?: 1
                val delimiter = node.markerDelimiter ?: "."
                visitList(node, indent, quoteDepth) { index -> "${start + index}$delimiter" }
            }
            is TableBlock -> out += table(node, indent, quoteDepth)
            else -> visitChildren(node, indent, quoteDepth)
        }
    }

    private inline fun visitList(list: Node, indent: Int, quoteDepth: Int, marker: (Int) -> String) {
        var item = list.firstChild
        var index = 0
        while (item != null) {
            if (item is ListItem) {
                visitItem(item, indent + 1, quoteDepth, marker(index))
                index++
            }
            item = item.next
        }
    }

    /** The marker goes on the item's first block; an empty item still shows it. */
    private fun visitItem(item: ListItem, indent: Int, quoteDepth: Int, marker: String) {
        var child = item.firstChild
        if (child == null) {
            out += MdBlock.Paragraph(AnnotatedString(""), marker, indent, quoteDepth)
            return
        }
        var pendingMarker: String? = marker
        while (child != null) {
            if (pendingMarker != null && child !is Paragraph) {
                // e.g. an item that starts with a code block or a nested list.
                out += MdBlock.Paragraph(AnnotatedString(""), pendingMarker, indent, quoteDepth)
                pendingMarker = null
            }
            visitBlock(child, indent, quoteDepth, marker = pendingMarker)
            pendingMarker = null
            child = child.next
        }
    }

    private fun table(node: TableBlock, indent: Int, quoteDepth: Int): MdBlock.Table {
        val rows = ArrayList<Pair<Boolean, List<AnnotatedString>>>()
        collectRows(node, rows)
        val header = rows.firstOrNull { it.first }?.second.orEmpty()
        val body = rows.filterNot { it.first }.map { it.second }
        return MdBlock.Table(header, body, indent, quoteDepth)
    }

    private fun collectRows(node: Node, rows: MutableList<Pair<Boolean, List<AnnotatedString>>>) {
        var child = node.firstChild
        while (child != null) {
            if (child is TableRow) {
                val cells = ArrayList<AnnotatedString>()
                var header = false
                var cell = child.firstChild
                while (cell != null) {
                    if (cell is TableCell) {
                        header = header || cell.isHeader
                        cells += inline(cell)
                    }
                    cell = cell.next
                }
                rows += header to cells
            } else {
                collectRows(child, rows)
            }
            child = child.next
        }
    }

    private fun inline(node: Node): AnnotatedString = buildAnnotatedString {
        appendInlines(node)
    }

    private fun AnnotatedString.Builder.appendInlines(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            appendInline(child)
            child = child.next
        }
    }

    private fun AnnotatedString.Builder.appendInline(node: Node) {
        when (node) {
            is Text -> append(node.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) {
                append(node.literal)
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(node) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(node) }
            is Link -> appendLink(node.destination) { appendInlines(node) }
            is Image -> appendLink(node.destination) {
                append(IMAGE_PREFIX)
                if (node.firstChild != null) appendInlines(node) else append(node.destination)
            }
            // Chat text is written with single newlines meant as line breaks
            // (as in GitHub comments), not as paragraph reflow.
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is HtmlInline -> append(node.literal)
            else -> appendInlines(node)
        }
    }

    private inline fun AnnotatedString.Builder.appendLink(url: String?, content: AnnotatedString.Builder.() -> Unit) {
        if (url.isNullOrBlank()) {
            content()
            return
        }
        val styles = TextLinkStyles(style = SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
        withLink(LinkAnnotation.Url(url, styles)) { content() }
    }

    private companion object {
        const val IMAGE_PREFIX = "🖼 "
    }
}
