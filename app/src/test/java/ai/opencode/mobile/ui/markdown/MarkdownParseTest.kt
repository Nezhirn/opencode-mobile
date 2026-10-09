package ai.opencode.mobile.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseTest {

    private val colors = MarkdownColors(link = Color.Blue, codeBackground = Color.Gray, emphasis = Color.Cyan)

    private fun parse(source: String) = parseMarkdown(source, colors)

    @Test
    fun fencedCodeBlockKeepsLanguageAndLiteral() {
        val blocks = parse("Intro\n\n```kotlin\nfun f() {\n    return\n}\n```\n")

        val code = blocks[1] as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("fun f() {\n    return\n}", code.code)
    }

    @Test
    fun unclosedFenceRunsToTheEndWhileStreaming() {
        val blocks = parse("```py\nprint(1)\nprint(2")

        val code = blocks.single() as MdBlock.Code
        assertEquals("print(1)\nprint(2", code.code)
    }

    @Test
    fun inlineMarkupIsStyledNotShownRaw() {
        val paragraph = parse("Use **bold**, *it* and `code`.").single() as MdBlock.Paragraph

        assertEquals("Use bold, it and code.", paragraph.text.text)
        val styles = paragraph.text.spanStyles
        assertTrue(styles.any { it.item.fontWeight == FontWeight.Bold && paragraph.text.text.substring(it.start, it.end) == "bold" })
        assertTrue(styles.any { it.item.fontFamily == FontFamily.Monospace && paragraph.text.text.substring(it.start, it.end) == "code" })
    }

    @Test
    fun linksAreClickableAnnotations() {
        val paragraph = parse("See [docs](https://opencode.ai/docs) or https://example.com").single() as MdBlock.Paragraph

        val urls = paragraph.text.getLinkAnnotations(0, paragraph.text.length)
            .map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://opencode.ai/docs", "https://example.com"), urls)
    }

    @Test
    fun listsCarryMarkersAndNesting() {
        val blocks = parse("- one\n- two\n  1. inner\n\n3. three\n4. four\n").map { it as MdBlock.Paragraph }

        assertEquals(listOf("•", "•", "1.", "3.", "4."), blocks.map { it.marker })
        assertEquals(listOf(1, 1, 2, 1, 1), blocks.map { it.indent })
        assertEquals("inner", blocks[2].text.text)
    }

    @Test
    fun softLineBreakIsKeptAsNewline() {
        val paragraph = parse("line one\nline two").single() as MdBlock.Paragraph

        assertEquals("line one\nline two", paragraph.text.text)
    }

    @Test
    fun tableIsParsedIntoHeaderAndRows() {
        val table = parse("| a | b |\n|---|---|\n| 1 | **2** |\n").single() as MdBlock.Table

        assertEquals(listOf("a", "b"), table.header.map { it.text })
        assertEquals(listOf(listOf("1", "2")), table.rows.map { row -> row.map { it.text } })
    }

    @Test
    fun headingsAndQuotes() {
        val blocks = parse("# Title\n\n> quoted\n")

        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        val quote = blocks[1] as MdBlock.Paragraph
        assertEquals(1, quote.quoteDepth)
        assertEquals("quoted", quote.text.text)
    }

    @Test
    fun blankInputHasNoBlocks() {
        assertTrue(parse("  \n").isEmpty())
    }

    @Test
    fun onlyWebAndMailLinksAreClickable() {
        val paragraph = parse("[web](https://a.dev) [local](file:///etc/hosts) [rel](docs/x.md)").single() as MdBlock.Paragraph

        val urls = paragraph.text.getLinkAnnotations(0, paragraph.text.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://a.dev"), urls)
        assertEquals("web local rel", paragraph.text.text)
    }

    @Test
    fun deepQuoteNestingIsCapped() {
        val paragraph = parse(">".repeat(30) + " deep").single() as MdBlock.Paragraph

        assertEquals("deep", paragraph.text.text)
        assertTrue(paragraph.quoteDepth <= 8)
    }

    @Test
    fun nestingTooDeepForTheParserFallsBackToPlainText() {
        val source = ">".repeat(20_000) + " deep"

        val blocks = parse(source)

        // One line without breaks: cut hard into bounded pieces, nothing lost.
        assertEquals(source, blocks.joinToString("") { (it as MdBlock.Paragraph).text.text })
        assertTrue(blocks.size > 1)
    }

    @Test
    fun asteriskEmphasisIsTintedButUnderscoreIsNot() {
        val paragraph = parse("*waves* and _whispers_").single() as MdBlock.Paragraph

        val tinted = paragraph.text.spanStyles.filter { it.item.color == Color.Cyan }
        assertEquals(listOf("waves"), tinted.map { paragraph.text.text.substring(it.start, it.end) })
    }

    @Test
    fun deepEmphasisNestingKeepsTheText() {
        val source = "*".repeat(5_000) + "x" + "*".repeat(5_000)

        val paragraph = parse(source).single() as MdBlock.Paragraph

        assertTrue(paragraph.text.text.contains("x"))
    }

    @Test
    fun longParagraphIsSplitIntoBoundedBlocks() {
        val source = (1..2_000).joinToString("\n") { "log line $it" }

        val blocks = parse(source)

        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it is MdBlock.Paragraph && it.text.length <= 2_000 && !it.text.text.endsWith("\n") })
        assertEquals(source, blocks.joinToString("\n") { (it as MdBlock.Paragraph).text.text })
    }

    @Test
    fun hugeTableCellIsCapped() {
        val table = parse("| a |\n|---|\n| ${"x".repeat(20_000)} |").single() as MdBlock.Table

        assertTrue(table.rows.single().single().length <= 501)
    }
}
