package ua.acclorite.book_story.data.storage.room

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index("bookUuid")])
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUuid: String,
    val bookId: Int,
    val bookTitle: String,
    val author: String,
    val chapter: String?,
    val startNode: Int,
    val startOffset: Int,
    val endNode: Int,
    val endOffset: Int,
    val quote: String,
    val color: Int,
    val note: String,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = created
)

@Entity
data class DictionaryEntry(
    @PrimaryKey val word: String,
    val lemma: String?,
    val phonetic: String?,
    val audio: String?,
    val partOfSpeech: String?,
    val definition: String?,
    val examples: String?,
    val synonyms: String?,
    val antonyms: String?
)

@Entity(indices = [Index(value = ["bookUuid", "startNode", "startOffset"], unique = true)])
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val lookupKey: String,
    val context: String,
    val bookUuid: String,
    val bookId: Int,
    val bookTitle: String,
    val author: String,
    val chapter: String?,
    val startNode: Int,
    val startOffset: Int,
    val created: Long = System.currentTimeMillis()
)

@Dao
interface ReaderMarksDao {
    @Query("SELECT * FROM HighlightEntity ORDER BY created DESC")
    fun highlights(): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM HighlightEntity WHERE bookUuid = :bookUuid")
    fun highlights(bookUuid: String): Flow<List<HighlightEntity>>

    @Insert
    suspend fun insert(highlight: HighlightEntity)

    @Query("UPDATE HighlightEntity SET color = :color, updated = :updated WHERE id = :id")
    suspend fun updateColor(id: Long, color: Int, updated: Long = System.currentTimeMillis())

    @Query("UPDATE HighlightEntity SET note = :note, updated = :updated WHERE id = :id")
    suspend fun updateNote(id: Long, note: String, updated: Long = System.currentTimeMillis())

    @Query("DELETE FROM HighlightEntity WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Query("SELECT * FROM VocabularyEntity ORDER BY created DESC")
    fun vocabulary(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM VocabularyEntity WHERE bookUuid = :bookUuid AND startNode = :node AND startOffset = :offset")
    fun vocabulary(bookUuid: String, node: Int, offset: Int): Flow<VocabularyEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(vocabulary: VocabularyEntity)

    @Query("DELETE FROM VocabularyEntity WHERE id = :id")
    suspend fun deleteWord(id: Long)

    @Query("SELECT * FROM DictionaryEntry WHERE word = :word")
    suspend fun dictionary(word: String): DictionaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(entry: DictionaryEntry)
}

@Database(
    entities = [HighlightEntity::class, VocabularyEntity::class, DictionaryEntry::class],
    version = 1,
    exportSchema = true
)
abstract class ReaderMarksDatabase : RoomDatabase() {
    abstract val marks: ReaderMarksDao

    companion object {
        @Volatile private var instance: ReaderMarksDatabase? = null

        fun get(context: Context): ReaderMarksDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReaderMarksDatabase::class.java,
                "reader-marks.db"
            ).build().also { instance = it }
        }
    }
}
