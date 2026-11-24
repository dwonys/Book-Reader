package ua.acclorite.book_story.data.storage.room

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal fun dictionaryKey(word: String): String = word.trim().lowercase(Locale.ROOT)

internal suspend fun lookupReaderWord(dao: ReaderMarksDao, word: String): DictionaryEntry? {
    val key = dictionaryKey(word)
    if (key.isBlank()) return null
    dao.dictionary(key)?.let { return it }
    val entry = withContext(Dispatchers.IO) {
        try {
            val connection = URL("https://api.dictionaryapi.dev/api/v2/entries/en/${Uri.encode(key)}")
                .openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                if (connection.responseCode != 200) return@withContext null
                val entries = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                val result = entries.optJSONObject(0) ?: return@withContext null
                val phonetics = result.optJSONArray("phonetics").objects()
                val meanings = result.optJSONArray("meanings").objects()
                val meaning = meanings.firstOrNull() ?: return@withContext null
                val definitions = meaning.optJSONArray("definitions").objects()
                val primary = definitions.firstOrNull()
                DictionaryEntry(
                    word = key,
                    lemma = result.stringOrNull("word"),
                    phonetic = result.stringOrNull("phonetic")
                        ?: phonetics.firstNotNullOfOrNull { it.stringOrNull("text") },
                    audio = phonetics.firstNotNullOfOrNull { it.stringOrNull("audio") }
                        ?.let { if (it.startsWith("//")) "https:$it" else it }
                        ?.takeIf { Uri.parse(it).scheme == "https" },
                    partOfSpeech = meaning.stringOrNull("partOfSpeech"),
                    definition = primary?.stringOrNull("definition"),
                    examples = definitions.mapNotNull { it.stringOrNull("example") }.distinct()
                        .take(3).joinToString("\n").ifBlank { null },
                    synonyms = words(meaning.optJSONArray("synonyms"), primary?.optJSONArray("synonyms")),
                    antonyms = words(meaning.optJSONArray("antonyms"), primary?.optJSONArray("antonyms"))
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
    entry?.let { dao.cache(it) }
    return entry
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun words(vararg arrays: JSONArray?): String? = arrays.flatMap { array ->
    if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }
}.filter(String::isNotBlank).distinct().take(12).joinToString(", ").ifBlank { null }
