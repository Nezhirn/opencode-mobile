package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.data.fileUrlOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AttachmentReadTest {

    @Test
    fun aStreamOfUnknownSizeIsCutAtTheLimit() {
        assertNull(readLimited(ByteArrayInputStream(ByteArray(1025)), maxBytes = 1024))
        assertEquals(1024, readLimited(ByteArrayInputStream(ByteArray(1024)), maxBytes = 1024)?.size)
    }

    @Test
    fun anEndlessStreamStopsRightAfterTheLimit() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { served += it }
        }

        assertNull(readLimited(endless, maxBytes = 1_000_000))
        // Read in 64 KB steps: one past the limit at most.
        assertEquals(true, served <= 1_000_000 + 64 * 1024)
    }

    @Test
    fun sampleSizeBringsTheLongerSideDownToTheLimit() {
        assertEquals(1, sampleSizeFor(2048, 1000, 2048))
        assertEquals(2, sampleSizeFor(4000, 3000, 2048))
        // 108 MP: 12000 x 9000 -> 1500 x 1125.
        assertEquals(8, sampleSizeFor(12_000, 9_000, 2048))
    }

    @Test
    fun projectFileUrlsArePercentEncoded() {
        assertEquals("file:///home/me/app/notes%232.md", fileUrlOf("/home/me/app/notes#2.md"))
        assertEquals("file:///home/me/100%25%20done%3F.txt", fileUrlOf("/home/me/100% done?.txt"))
        assertEquals("file:///home/%D0%B4/a.kt", fileUrlOf("/home/д/a.kt"))
        assertEquals("file:///C:/proj/src/a%20b.kt", fileUrlOf("C:\\proj\\src/a b.kt"))
    }
}
