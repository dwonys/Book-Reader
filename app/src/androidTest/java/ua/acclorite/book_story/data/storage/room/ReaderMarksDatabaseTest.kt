package ua.acclorite.book_story.data.storage.room

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderMarksDatabaseTest {
    @Test fun marksAndDictionaryCacheSurviveReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "reader-marks-test.db"
        context.deleteDatabase(databaseName)
        try {
            Room.databaseBuilder(context, ReaderMarksDatabase::class.java, databaseName).build().use { db ->
                val dao = db.marks
                dao.insert(HighlightEntity(
                    bookUuid = "book", bookId = 8, bookTitle = "Title", author = "Author",
                    chapter = "Chapter", startNode = 3, startOffset = 5, endNode = 4,
                    endOffset = 6, quote = "Saved quote", color = 0xFFFFC107.toInt(), note = "Note"
                ))
                dao.insert(VocabularyEntity(
                    word = "quote", lookupKey = "quote", context = "Saved quote.", bookUuid = "book",
                    bookId = 8, bookTitle = "Title", author = "Author", chapter = "Chapter",
                    startNode = 4, startOffset = 1
                ))
                dao.cache(DictionaryEntry("quote", "quote", null, null, "noun", "A quotation", null, null, null))
            }
            Room.databaseBuilder(context, ReaderMarksDatabase::class.java, databaseName).build().use { db ->
                val mark = db.marks.highlights("book").first().single()
                assertEquals(3, mark.startNode)
                assertEquals(6, mark.endOffset)
                assertEquals("Note", mark.note)
                val word = db.marks.vocabulary("book", 4, 1).first()
                assertNotNull(word)
                assertEquals("Saved quote.", word?.context)
                assertEquals("A quotation", db.marks.dictionary("quote")?.definition)
                db.marks.deleteWord(requireNotNull(word).id)
                assertNull(db.marks.vocabulary("book", 4, 1).first())
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
