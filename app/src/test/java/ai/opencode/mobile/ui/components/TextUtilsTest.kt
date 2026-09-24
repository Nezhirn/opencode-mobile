package ai.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    private val emoji = "😀" // one code point, two UTF-16 units

    @Test
    fun safePrefixNeverSplitsASurrogatePair() {
        val text = "ab$emoji"

        assertEquals("ab", text.safePrefix(3))
        assertEquals(text, text.safePrefix(4))
        assertEquals("a", text.safePrefix(1))
    }

    @Test
    fun chunksConcatenateBackToTheOriginal() {
        val text = (1..500).joinToString("\n") { "line $it with some text" }

        val chunks = text.chunkedForLayout(size = 100)

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 100 })
        // Split at line boundaries whenever a newline is available.
        assertTrue(chunks.dropLast(1).all { it.endsWith("\n") })
    }

    @Test
    fun hardCutOfALongLineKeepsSurrogatePairsWhole() {
        val text = "a".repeat(99) + emoji + "b".repeat(50)

        val chunks = text.chunkedForLayout(size = 100)

        assertEquals(text, chunks.joinToString(""))
        chunks.forEach { chunk ->
            assertFalse(Character.isHighSurrogate(chunk.last()))
            assertFalse(Character.isLowSurrogate(chunk.first()))
        }
    }

    @Test
    fun stripAnsiRemovesColourAndOscSequences() {
        val raw = "\u001B[31merror\u001B[0m: \u001B]0;title\u0007done \u001B[1;32mok\u001B[m"

        assertEquals("error: done ok", raw.stripAnsi())
    }

    @Test
    fun stripAnsiLeavesPlainTextUntouched() {
        val plain = "no escapes [31m here"

        assertTrue(plain.stripAnsi() === plain)
    }
}
