package ua.acclorite.book_story.ui.reader

import android.media.MediaPlayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.data.storage.room.ReaderMarksDatabase
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import ua.acclorite.book_story.data.storage.room.DictionaryEntry
import ua.acclorite.book_story.data.storage.room.lookupReaderWord

@Composable
internal fun ReaderNoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = { OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }) },
        confirmButton = { TextButton(onClick = { onSave(note); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun ReaderDictionaryCard(
    word: String,
    saved: Boolean = false,
    onSave: (() -> Unit)? = null,
    onHighlight: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onExternalDictionary: (() -> Unit)? = null,
    expandedInitially: Boolean = false
) {
    val context = LocalContext.current
    val dao = remember(context) { ReaderMarksDatabase.get(context).marks }
    var player by remember(word) { mutableStateOf<MediaPlayer?>(null) }
    var audioFailed by remember(word) { mutableStateOf(false) }
    DisposableEffect(word) {
        onDispose { player?.release() }
    }
    var entry by remember(word) { mutableStateOf<DictionaryEntry?>(null) }
    var loading by remember(word) { mutableStateOf(true) }
    var expanded by remember(word) { mutableStateOf(expandedInitially) }
    LaunchedEffect(dao, word) {
        entry = lookupReaderWord(dao, word)
        loading = false
    }
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(word, style = MaterialTheme.typography.titleMedium)
        DictionaryDetails(entry, loading, expanded)
        if (audioFailed) Text("Audio unavailable")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            entry?.audio?.let { audio ->
                TextButton(enabled = player == null, onClick = {
                    audioFailed = false
                    val playback = MediaPlayer()
                    player = playback
                    fun finish(failed: Boolean) {
                        if (player === playback) {
                            audioFailed = failed
                            player = null
                            playback.release()
                        }
                    }
                    try {
                        playback.setOnPreparedListener { it.start() }
                        playback.setOnCompletionListener { finish(false) }
                        playback.setOnErrorListener { _, _, _ -> finish(true); true }
                        playback.setDataSource(audio)
                        playback.prepareAsync()
                    } catch (_: Exception) {
                        finish(true)
                    }
                }) { Text("Audio") }
            }
            onSave?.let { TextButton(onClick = it) { Text(if (saved) "♥ Saved" else "♡ Save") } }
            onHighlight?.let { TextButton(onClick = it) { Text("Highlight") } }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "Details") }
            if (expanded) onExternalDictionary?.let { TextButton(onClick = it) { Text("Dictionary app") } }
        }
    }
}

@Composable
internal fun SavedWordDefinition(word: String) {
    val context = LocalContext.current
    val dao = remember(context) { ReaderMarksDatabase.get(context).marks }
    val result by produceState<Pair<DictionaryEntry?, Boolean>>(null to true, dao, word) {
        value = null to true
        value = lookupReaderWord(dao, word) to false
    }
    DictionaryDetails(result.first, result.second, expanded = true)
}

@Composable
private fun DictionaryDetails(entry: DictionaryEntry?, loading: Boolean, expanded: Boolean) {
    if (entry == null) {
        Text(if (loading) "Looking up word…" else "Definition unavailable")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val subtitle = listOfNotNull(entry.partOfSpeech, entry.phonetic).joinToString(" · ")
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        entry.definition?.let { Text(it, maxLines = if (expanded) 8 else 3, overflow = TextOverflow.Ellipsis) }
        entry.examples?.let {
            val examples = if (expanded) it else it.lineSequence().first()
            Text("Example: $examples", style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) 10 else 2, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            entry.synonyms?.let { Text("Synonyms: $it", style = MaterialTheme.typography.bodyMedium) }
            entry.antonyms?.let { Text("Antonyms: $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
