package ai.opencode.mobile.ui.markdown

import ai.opencode.mobile.ui.components.LAYOUT_CHUNK_CHARS
import ai.opencode.mobile.ui.components.chunkEnds
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
    /** Tint for `*asterisk*` emphasis: roleplay replies mark actions with it. */
    val emphasis: Color,
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
    // commonmark walks the tree recursively (its extensions' post-processors
    // included): input nested thousands deep (">>>>…", "- - - …") overflows
    // the stack inside the library. Such input is shown as plain text.
    return try {
        val document = parser.parse(source)
        ArrayList<MdBlock>().also { blocks -> BlockCollector(colors, blocks).visitChildren(document, indent = 0, quoteDepth = 0) }
    } catch (overflow: StackOverflowError) {
        plainBlocks(source)
    }
}

/** [source] as unformatted paragraphs of bounded size. */
private fun plainBlocks(source: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    var start = 0
    source.chunkEnds().forEach { end ->
        val visibleEnd = if (end > start && source[end - 1] == '\n') end - 1 else end
        blocks += MdBlock.Paragraph(AnnotatedString(source.substring(start, visibleEnd)))
        start = end
    }
    return blocks
}

private class BlockCollector(
    private val colors: MarkdownColors,
    private val out: MutableList<MdBlock>,
) {
    fun visitChildren(parent: Node, indent: Int, quoteDepth: Int) {
        // Iterative over siblings; recursion only follows nesting, which
        // visitBlock() caps at MAX_NESTING.
        var child = parent.firstChild
        while (child != null) {
            visitBlock(child, indent, quoteDepth, marker = null)
            child = child.next
        }
    }

    private fun visitBlock(node: Node, indent: Int, quoteDepth: Int, marker: String?) {
        when (node) {
            is Paragraph -> addParagraph(inline(node), marker, indent, quoteDepth)
            is Heading -> out += MdBlock.Heading(node.level, inline(node), indent, quoteDepth)
            is FencedCodeBlock -> out += MdBlock.Code(
                language = node.info.orEmpty().substringBefore(' '),
                code = node.literal.orEmpty().removeSuffix("\n"),
                indent = indent,
                quoteDepth = quoteDepth,
            )
            is IndentedCodeBlock -> out += MdBlock.Code("", node.literal.orEmpty().removeSuffix("\n"), indent, quoteDepth)
            is HtmlBlock -> addParagraph(AnnotatedString(node.literal.orEmpty().trimEnd()), marker, indent, quoteDepth)
            is ThematicBreak -> out += MdBlock.Rule(indent, quoteDepth)
            // Nesting is capped: ">>>>…" or "- - - - …" thousands deep recursed
            // once per level on the main thread (risking a StackOverflowError)
            // and indented the text down to zero width. Deeper content is shown
            // at the cap level instead.
            is BlockQuote ->
                if (quoteDepth >= MAX_NESTING) visitFlattened(node, indent, quoteDepth) else visitChildren(node, indent, quoteDepth + 1)
            is BulletList, is OrderedList -> if (indent >= MAX_NESTING) visitFlattened(node, indent, quoteDepth) else visitListBlock(node, indent, quoteDepth)
            is TableBlock -> out += table(node, indent, quoteDepth)
            else -> visitChildren(node, indent, quoteDepth)
        }
    }

    private fun visitListBlock(node: Node, indent: Int, quoteDepth: Int) {
        when (node) {
            is BulletList -> visitList(node, indent, quoteDepth) { "•" }
            is OrderedList -> {
                val start = node.markerStartNumber ?: 1
                val delimiter = node.markerDelimiter ?: "."
                visitList(node, indent, quoteDepth) { index -> "${start + index}$delimiter" }
            }
        }
    }

    /** Emits the leaf blocks under [root] without further nesting, iteratively. */
    private fun visitFlattened(root: Node, indent: Int, quoteDepth: Int) {
        val stack = ArrayDeque<Node>()
        fun pushChildren(parent: Node) {
            val children = ArrayList<Node>()
            var child = parent.firstChild
            while (child != null) {
                children += child
                child = child.next
            }
            for (index in children.indices.reversed()) stack.addLast(children[index])
        }
        pushChildren(root)
        while (stack.isNotEmpty()) {
            when (val node = stack.removeLast()) {
                is BlockQuote, is BulletList, is OrderedList, is ListItem -> pushChildren(node)
                else -> visitBlock(node, indent, quoteDepth, marker = null)
            }
        }
    }

    /**
     * A paragraph longer than a layout chunk is split at line breaks into
     * several blocks: one Text over a whole pasted log was laid out again on
     * every streamed token and hung the UI. The marker stays on the first part.
     */
    private fun addParagraph(text: AnnotatedString, marker: String?, indent: Int, quoteDepth: Int) {
        if (text.length <= LAYOUT_CHUNK_CHARS) {
            out += MdBlock.Paragraph(text, marker, indent, quoteDepth)
            return
        }
        var start = 0
        text.text.chunkEnds().forEachIndexed { index, end ->
            val visibleEnd = if (end > start && text[end - 1] == '\n') end - 1 else end
            out += MdBlock.Paragraph(text.subSequence(start, visibleEnd), marker.takeIf { index == 0 }, indent, quoteDepth)
            start = end
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
                        cells += inline(cell).capped(MAX_CELL_CHARS)
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
        appendInlines(node, depth = 0)
    }

    private fun AnnotatedString.Builder.appendInlines(parent: Node, depth: Int) {
        var child = parent.firstChild
        while (child != null) {
            appendInline(child, depth + 1)
            child = child.next
        }
    }

    private fun AnnotatedString.Builder.appendInline(node: Node, depth: Int) {
        // Emphasis can nest as deep as the input has asterisks; past the cap the
        // rest is plain text, collected without recursion.
        if (depth > MAX_INLINE_DEPTH) {
            appendPlain(node)
            return
        }
        when (node) {
            is Text -> append(node.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) {
                append(node.literal)
            }
            is Emphasis -> {
                // `_underscore_` stays plain italic; only `*asterisks*` get the tint.
                val tint = if (node.openingDelimiter == "*") colors.emphasis else Color.Unspecified
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = tint)) { appendInlines(node, depth) }
            }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(node, depth) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(node, depth) }
            is Link -> appendLink(node.destination) { appendInlines(node, depth) }
            is Image -> appendLink(node.destination) {
                append(IMAGE_PREFIX)
                if (node.firstChild != null) appendInlines(node, depth) else append(node.destination)
            }
            // Chat text is written with single newlines meant as line breaks
            // (as in GitHub comments), not as paragraph reflow.
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is HtmlInline -> append(node.literal)
            else -> appendInlines(node, depth)
        }
    }

    private fun AnnotatedString.Builder.appendPlain(root: Node) {
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            when (val node = stack.removeLast()) {
                is Text -> append(node.literal)
                is Code -> append(node.literal)
                is HtmlInline -> append(node.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                else -> {
                    val children = ArrayList<Node>()
                    var child = node.firstChild
                    while (child != null) {
                        children += child
                        child = child.next
                    }
                    for (index in children.indices.reversed()) stack.addLast(children[index])
                }
            }
        }
    }

    private inline fun AnnotatedString.Builder.appendLink(url: String?, content: AnnotatedString.Builder.() -> Unit) {
        // Only links another app can safely open become clickable. A file://
        // link (agents write them for local paths) crashed the app on tap: the
        // platform throws FileUriExposedException, which Compose does not catch.
        if (url.isNullOrBlank() || !isOpenableLink(url)) {
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

/** Deepest list or quote nesting rendered as such. */
private const val MAX_NESTING = 8

/** Deepest inline style nesting rendered as such. */
private const val MAX_INLINE_DEPTH = 32

/**
 * Longest table cell shown. Cells do not wrap; a single huge one (base64,
 * minified JSON) was wider than layout constraints can represent.
 */
private const val MAX_CELL_CHARS = 500

private val OPENABLE_LINK_SCHEMES = setOf("http", "https", "mailto")

internal fun isOpenableLink(url: String): Boolean =
    url.substringBefore(':', missingDelimiterValue = "").lowercase() in OPENABLE_LINK_SCHEMES

private fun AnnotatedString.capped(max: Int): AnnotatedString =
    if (length <= max) this else buildAnnotatedString {
        append(this@capped.subSequence(0, max))
        append('…')
    }
