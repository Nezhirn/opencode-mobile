package ai.opencode.mobile.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseTest {

    private val colors = MarkdownColors(link = Color.Blue, codeBackground = Color.Gray)

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
}
