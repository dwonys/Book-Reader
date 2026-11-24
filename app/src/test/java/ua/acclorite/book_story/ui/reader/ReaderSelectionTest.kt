package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.Node
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.domain.model.book.NodeId
import ua.acclorite.book_story.domain.model.book.NodePosition
import ua.acclorite.book_story.ui.reader.model.ReaderLinkUrl
import ua.acclorite.book_story.ui.reader.model.ReaderNode

class ReaderSelectionTest {
    @Test fun distinguishesUnicodeWordFromMultiWordSelection() {
        assertTrue(isReaderWord("café"))
        assertTrue(isReaderWord("κόσμος"))
        assertFalse(isReaderWord("two words"))
        assertFalse(isReaderWord("—"))
    }

    @Test fun extractsMultiParagraphRangeWithExclusiveEnd() {
        val nodes = listOf(paragraph(3, "First paragraph"), paragraph(4, "Second paragraph"))
        val range = ReaderRange(ReaderAnchor(3, 6), ReaderAnchor(4, 6))
        assertEquals("paragraph\nSecond", selectedReaderText(nodes, range))
    }

    @Test fun ordersNodesBeforeOffsets() {
        assertTrue(ReaderAnchor(3, 100) < ReaderAnchor(4, 0))
        assertTrue(ReaderAnchor(4, 0) < ReaderAnchor(4, 2))
    }

    @Test fun capturesOriginalSentenceAroundWord() {
        val nodes = listOf(paragraph(2, "First sentence. Café is open. Another sentence."))
        assertEquals("Café is open.", readerSentence(nodes, ReaderAnchor(2, 16)))
    }

    @Test fun sourceAnchorsSurviveDisplayedLinkSuffixAndPageSplit() {
        val original = paragraph(0, "Link continues")
        val link = ReaderLinkUrl(0, 4, "https://example.com")
        val linked = original.copy(
            text = AnnotatedString("Link${link.suffix} continues"), linkUrls = listOf(link)
        )
        assertEquals(4, linked.toSourceOffset(4, boundary = true))
        val start = 5
        val pageStart = linked.toDisplayOffset(start)
        assertEquals(start, linked.toSourceOffset(pageStart, boundary = true))
        assertEquals(start + 2, linked.toSourceOffset(pageStart + 2, boundary = true))
        val range = ReaderRange(ReaderAnchor(0, start), ReaderAnchor(0, 14))
        assertEquals("continues", selectedReaderText(listOf(original), range))
        assertEquals("continues", selectedReaderText(listOf(linked), range))
    }

    private fun paragraph(id: Int, text: String) = ReaderNode.Paragraph(
        Node(NodeId(id), NodePosition(text.length, 0), NodeContent.Paragraph(text, emptyList())),
        AnnotatedString(text)
    )
}
