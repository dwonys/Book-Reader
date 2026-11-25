package ua.acclorite.book_story.presentation.words

import android.content.Intent
import android.os.Parcelable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import ua.acclorite.book_story.data.storage.room.ReaderMarksDatabase
import ua.acclorite.book_story.presentation.navigator.Screen
import ua.acclorite.book_story.presentation.reader.ReaderScreen
import ua.acclorite.book_story.ui.navigator.LocalNavigator
import ua.acclorite.book_story.ui.navigator.NavigatorIconButton
import ua.acclorite.book_story.ui.reader.SavedWordDefinition
import ua.acclorite.book_story.ui.reader.ReaderNoteDialog
import java.text.DateFormat
import java.util.Date

@Parcelize
object WordsScreen : Screen, Parcelable {
    @Suppress("DEPRECATION")
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val clipboard = LocalClipboardManager.current
        val dao = remember(context) { ReaderMarksDatabase.get(context).marks }
        val vocabulary by remember(dao) { dao.vocabulary() }.collectAsState(emptyList())
        val highlights by remember(dao) { dao.highlights() }.collectAsState(emptyList())
        val scope = rememberCoroutineScope()
        var tab by rememberSaveable { mutableStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var book by rememberSaveable { mutableStateOf<String?>(null) }
        var filter by remember { mutableStateOf(false) }
        var alternateOrder by rememberSaveable { mutableStateOf(false) }
        val books = if (tab == 0) vocabulary.map { it.bookUuid to it.bookTitle }
            else highlights.map { it.bookUuid to it.bookTitle }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Words", style = MaterialTheme.typography.headlineMedium)
                NavigatorIconButton()
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Vocabulary", "Highlights").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = {
                        tab = index
                        book = null
                        alternateOrder = false
                    }, text = { Text(title) })
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(if (tab == 0) "Search vocabulary" else "Search highlights") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Row {
                Column {
                    TextButton(onClick = { filter = true }) {
                        Text(books.firstOrNull { it.first == book }?.second ?: "All books")
                    }
                    DropdownMenu(expanded = filter, onDismissRequest = { filter = false }) {
                        DropdownMenuItem(text = { Text("All books") }, onClick = { book = null; filter = false })
                        books.distinctBy { it.first }.sortedBy { it.second }.forEach { (uuid, title) ->
                            DropdownMenuItem(text = { Text(title) }, onClick = { book = uuid; filter = false })
                        }
                    }
                }
                TextButton(onClick = { alternateOrder = !alternateOrder }) {
                    Text(if (!alternateOrder) "Newest first" else if (tab == 0) "Alphabetical" else "Oldest first")
                }
            }
            if (tab == 0) {
                val entries = vocabulary.filter {
                    (book == null || book == it.bookUuid) &&
                        it.word.contains(query, true)
                }.let { if (alternateOrder) it.sortedBy { word -> word.word.lowercase() } else it }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.isEmpty()) item { Text("No saved words") }
                    items(entries, key = { it.id }) { word ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(word.word, style = MaterialTheme.typography.titleMedium)
                                SavedWordDefinition(word.lookupKey)
                                MarkSource(word.bookTitle, word.author, word.chapter, word.created)
                                Row {
                                    TextButton(onClick = {
                                        navigator.push(ReaderScreen(word.bookId, word.startNode, word.startOffset, word.bookUuid))
                                    }) { Text("Open passage") }
                                    TextButton(onClick = { scope.launch { dao.deleteWord(word.id) } }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            } else {
                val entries = highlights.filter {
                    (book == null || book == it.bookUuid) &&
                        (it.quote.contains(query, true) || it.note.contains(query, true))
                }.let { if (alternateOrder) it.asReversed() else it }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.isEmpty()) item { Text("No saved highlights") }
                    items(entries, key = { it.id }) { mark ->
                        var editing by remember { mutableStateOf(false) }
                        var choosingColor by remember { mutableStateOf(false) }
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(mark.quote, style = MaterialTheme.typography.bodyLarge)
                                if (mark.note.isNotBlank()) Text(mark.note)
                                MarkSource(mark.bookTitle, mark.author, mark.chapter, mark.created)
                                Row {
                                    TextButton(onClick = {
                                        navigator.push(ReaderScreen(mark.bookId, mark.startNode, mark.startOffset, mark.bookUuid))
                                    }) { Text("Open passage") }
                                    TextButton(onClick = { editing = true }) { Text("Edit note") }
                                    Column {
                                        TextButton(onClick = { choosingColor = true }) {
                                            Text("●", color = Color(mark.color))
                                            Text(" Color")
                                        }
                                        DropdownMenu(expanded = choosingColor, onDismissRequest = { choosingColor = false }) {
                                            listOf("Yellow" to 0xFFFFC107, "Green" to 0xFF66BB6A,
                                                "Blue" to 0xFF42A5F5, "Pink" to 0xFFEC407A).forEach { (label, color) ->
                                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                                    choosingColor = false
                                                    scope.launch { dao.updateColor(mark.id, color.toInt()) }
                                                })
                                            }
                                        }
                                    }
                                }
                                Row {
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(mark.quote)) }) { Text("Copy") }
                                    TextButton(onClick = {
                                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, mark.quote)
                                        context.startActivity(Intent.createChooser(intent, null))
                                    }) { Text("Share") }
                                    TextButton(onClick = { scope.launch { dao.deleteHighlight(mark.id) } }) { Text("Delete") }
                                }
                            }
                        }
                        if (editing) ReaderNoteDialog(mark.note, { editing = false }) { note ->
                            scope.launch { dao.updateNote(mark.id, note) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkSource(title: String, author: String, chapter: String?, created: Long) {
    Text(
        listOfNotNull(title, author.takeIf(String::isNotBlank), chapter,
            DateFormat.getDateInstance().format(Date(created))).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall
    )
}
