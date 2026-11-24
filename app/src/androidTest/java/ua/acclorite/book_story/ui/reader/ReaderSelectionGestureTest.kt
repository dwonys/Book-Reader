package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Rule
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.model.book.Node
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.domain.model.book.NodeId
import ua.acclorite.book_story.domain.model.book.NodePosition
import ua.acclorite.book_story.ui.reader.model.ReaderNode

class ReaderSelectionGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressShowsDictionaryAndHandles() {
        val word = "Café"
        val paragraph = ReaderNode.Paragraph(
            Node(NodeId(0), NodePosition(word.length, 0), NodeContent.Paragraph(word, emptyList())),
            AnnotatedString(word)
        )
        compose.setContent {
            MaterialTheme {
                ReaderSelectionContainer(
                    book = Book.default.copy(uuid = "gesture-test"),
                    nodes = listOf(paragraph),
                    onCopyRequested = {},
                    onShareRequested = {},
                    onWebSearchRequested = {},
                    onTranslateRequested = {},
                    onDictionaryRequested = {}
                ) {
                    Column(Modifier.padding(top = 64.dp)) {
                        ReaderSelectableText(
                            paragraph, paragraph.text, TextStyle(fontSize = 24.sp),
                            Modifier.fillMaxWidth().testTag("paragraph")
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("paragraph").performTouchInput {
            longClick(Offset(8f, center.y))
        }
        compose.onNodeWithContentDescription("Selection start").assertExists()
        compose.onNodeWithContentDescription("Selection end").assertExists()
        compose.onNodeWithText("Details").assertExists()
    }
}
