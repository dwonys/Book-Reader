/*
 * Book's Story — free and open-source Material You eBook reader.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import java.text.BreakIterator
import ua.acclorite.book_story.data.storage.room.HighlightEntity
import ua.acclorite.book_story.data.storage.room.ReaderMarksDatabase
import ua.acclorite.book_story.data.storage.room.VocabularyEntity
import ua.acclorite.book_story.data.storage.room.dictionaryKey
import ua.acclorite.book_story.domain.model.book.Book
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.reader.model.ReaderNode
import kotlin.math.roundToInt

internal data class ReaderAnchor(val nodeId: Int, val offset: Int) : Comparable<ReaderAnchor> {
    override fun compareTo(other: ReaderAnchor): Int =
        compareValuesBy(this, other, ReaderAnchor::nodeId, ReaderAnchor::offset)
}

internal data class ReaderRange(val start: ReaderAnchor, val end: ReaderAnchor)

private fun HighlightEntity.readerRange() = ReaderRange(
    ReaderAnchor(startNode, startOffset), ReaderAnchor(endNode, endOffset)
)

private class TextFragment(val node: ReaderNode, val displayStart: Int) {
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var coordinates: LayoutCoordinates? = null
    var bounds by mutableStateOf(Rect.Zero)
    var origin by mutableStateOf(Offset.Zero)

    fun anchor(offset: Int) = ReaderAnchor(node.node.id.id, node.toSourceOffset(displayStart + offset, boundary = true))

    fun offset(anchor: ReaderAnchor, start: Boolean): Int =
        node.toDisplayOffset(anchor.offset, includeFollowingUrl = start) - displayStart

    fun containsHighlightAt(highlight: HighlightEntity, point: Offset): Boolean {
        val result = layout ?: return false
        val saved = range(highlight.readerRange(), result.layoutInput.text.length) ?: return false
        val offset = result.getOffsetForPosition(point)
        return sequenceOf(offset, offset - 1).any {
            it in saved && result.getBoundingBox(it).contains(point)
        }
    }

    fun range(selection: ReaderRange, length: Int): IntRange? {
        if (node.node.id.id !in selection.start.nodeId..selection.end.nodeId) return null
        val start = if (selection.start.nodeId == node.node.id.id) offset(selection.start, start = true) else 0
        val end = if (selection.end.nodeId == node.node.id.id) offset(selection.end, start = false) else length
        return start.coerceIn(0, length) until end.coerceIn(0, length)
    }
}

private class ReaderSelection {
    val fragments = mutableStateMapOf<TextFragment, Unit>()
    var range by mutableStateOf<ReaderRange?>(null)
    var highlights by mutableStateOf(emptyList<HighlightEntity>())
    var dragPoint by mutableStateOf<Offset?>(null)
    var draggingStart = false
    var highlightToRemove by mutableStateOf<HighlightEntity?>(null)

    fun handle(anchor: ReaderAnchor, start: Boolean, lineTop: Boolean = false): Offset? {
        for (fragment in fragments.keys) {
            if (fragment.node.node.id.id != anchor.nodeId || fragment.bounds.isEmpty) continue
            val layout = fragment.layout ?: continue
            val offset = fragment.offset(anchor, start)
            val length = layout.layoutInput.text.length
            val character = if (start) offset else offset - 1
            if (character !in 0 until length) continue
            val line = layout.getLineForOffset(character)
            val rtl = layout.getBidiRunDirection(character) ==
                androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
            val box = layout.getBoundingBox(character)
            val edge = if (start == rtl) box.right else box.left
            val y = if (lineTop) layout.getLineTop(line) else layout.getLineBottom(line)
            val position = fragment.origin + Offset(edge, y)
            if (position.y in fragment.bounds.top..fragment.bounds.bottom) return position
        }
        return null
    }

    fun drag(point: Offset) {
        val selection = range ?: return
        val fragment = fragments.keys
            .filter { !it.bounds.isEmpty && it.layout != null && it.coordinates?.isAttached == true }
            .minByOrNull {
                val bounds = it.bounds
                val nearest = Offset(point.x.coerceIn(bounds.left, bounds.right),
                    point.y.coerceIn(bounds.top, bounds.bottom))
                (point - nearest).getDistanceSquared()
            } ?: return
        val layout = fragment.layout ?: return
        val local = point - fragment.origin
        val anchor = fragment.anchor(layout.getOffsetForPosition(local))
        if (draggingStart && anchor < selection.end) range = selection.copy(start = anchor)
        if (!draggingStart && anchor > selection.start) range = selection.copy(end = anchor)
    }
}

private val LocalReaderSelection = staticCompositionLocalOf<ReaderSelection?> { null }

@Composable
internal fun ReaderSelectableText(
    node: ReaderNode,
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    displayStart: Int = 0,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val selection = LocalReaderSelection.current
    if (selection == null || text.isEmpty()) {
        StyledText(text, modifier, style, overflow = overflow)
        return
    }
    val fragment = remember(selection, node, displayStart, text) { TextFragment(node, displayStart) }
    DisposableEffect(fragment) {
        selection.fragments[fragment] = Unit
        onDispose { selection.fragments.remove(fragment) }
    }
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.27f)
    StyledText(
        text = text,
        style = style,
        overflow = overflow,
        onTextLayout = { fragment.layout = it },
        modifier = modifier
            .onGloballyPositioned {
                fragment.coordinates = it
                fragment.bounds = it.boundsInRoot()
                fragment.origin = it.positionInRoot()
            }
            .drawBehind {
                val layout = fragment.layout ?: return@drawBehind
                for (highlight in selection.highlights) {
                    val saved = fragment.range(highlight.readerRange(), text.length) ?: continue
                    if (!saved.isEmpty()) {
                        drawPath(layout.getPathForRange(saved.first, saved.last + 1),
                            Color(highlight.color).copy(alpha = 0.35f))
                    }
                }
                val range = selection.range?.let { fragment.range(it, text.length) } ?: return@drawBehind
                if (!range.isEmpty()) drawPath(layout.getPathForRange(range.first, range.last + 1), color)
            }
            .semantics {
                customActions = selection.highlights.filter {
                    fragment.range(it.readerRange(), text.length)?.isEmpty() == false
                }.map { highlight ->
                    CustomAccessibilityAction("Remove highlight: ${highlight.quote.take(40)}") {
                        selection.highlightToRemove = highlight
                        true
                    }
                }
                onLongClick(label = "Select first word") {
                    val first = text.text.indexOfFirst(Char::isLetterOrDigit)
                    first >= 0 && selectReaderWord(selection, fragment, text.text, first)
                }
            }
            .pointerInput(fragment) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val movedOrReleased = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id }
                            if (change == null ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) break
                            if (!change.pressed) {
                                if (selection.range == null) {
                                    val mark = selection.highlights.asReversed().firstOrNull {
                                        fragment.containsHighlightAt(it, down.position) &&
                                            fragment.containsHighlightAt(it, change.position)
                                    }
                                    if (mark != null) {
                                        change.consume()
                                        selection.highlightToRemove = mark
                                    }
                                }
                                break
                            }
                        }
                        true
                    }
                    val layout = fragment.layout
                    if (movedOrReleased == null && layout != null &&
                        selectReaderWord(selection, fragment, text.text, layout.getOffsetForPosition(down.position))
                    ) {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
    )
}

internal fun isReaderWord(text: String): Boolean {
    if (text.none(Char::isLetterOrDigit)) return false
    val iterator = BreakIterator.getWordInstance().apply { setText(text) }
    return iterator.next() == text.length
}

private fun selectReaderWord(
    selection: ReaderSelection,
    fragment: TextFragment,
    text: String,
    offset: Int
): Boolean {
    val layout = fragment.layout ?: return false
    val word = layout.getWordBoundary(offset.coerceIn(text.indices))
    if (word.start >= word.end || text.substring(word.start, word.end).none(Char::isLetterOrDigit)) {
        return false
    }
    val start = fragment.anchor(word.start)
    val end = fragment.anchor(word.end)
    if (start >= end) return false
    selection.range = ReaderRange(start, end)
    return true
}

private fun ReaderNode.sourceText(): String? = when (val content = node.content) {
    is NodeContent.TextBlock -> content.text
    is NodeContent.Header -> content.title
    else -> null
}

internal fun selectedReaderText(nodes: List<ReaderNode>, range: ReaderRange): String =
    nodes.asSequence().filter { it.node.id.id in range.start.nodeId..range.end.nodeId }
        .mapNotNull { node ->
            val text = node.sourceText() ?: return@mapNotNull null
            val start = if (node.node.id.id == range.start.nodeId) range.start.offset else 0
            val end = if (node.node.id.id == range.end.nodeId) range.end.offset else text.length
            text.substring(start.coerceIn(0, text.length), end.coerceIn(start, text.length))
        }.joinToString("\n")

@Suppress("DEPRECATION")
@Composable
internal fun ReaderSelectionContainer(
    book: Book,
    nodes: List<ReaderNode>,
    onCopyRequested: () -> Unit,
    onShareRequested: (String) -> Unit,
    onWebSearchRequested: (String) -> Unit,
    onTranslateRequested: (String) -> Unit,
    onDictionaryRequested: (String) -> Unit,
    scrollBy: (suspend (Float) -> Float)? = null,
    content: @Composable (Boolean) -> Unit
) {
    val selection = remember(book.uuid) { ReaderSelection() }
    val context = LocalContext.current
    val dao = remember(context) { ReaderMarksDatabase.get(context).marks }
    val scope = rememberCoroutineScope()
    LaunchedEffect(dao, book.uuid, selection) {
        dao.highlights(book.uuid).collect { selection.highlights = it }
    }
    var root by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val edge = with(density) { 52.dp.toPx() }
    val cardGap = with(density) { 44.dp.toPx() }
    val dictionaryGap = with(density) { 16.dp.roundToPx() }
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    val dragging = selection.dragPoint != null
    LaunchedEffect(selection, dragging, scrollBy, edge) {
        if (dragging && scrollBy != null) {
            while (true) {
                withFrameNanos { }
                val point = selection.dragPoint ?: break
                val localY = point.y - root.y
                val distance = when {
                    localY < edge -> localY - edge
                    localY > viewport.height - edge -> localY - (viewport.height - edge)
                    else -> 0f
                }
                if (distance != 0f && scrollBy((distance / 6).coerceIn(-18f, 18f)) != 0f) {
                    selection.drag(point - Offset(0f, 1f))
                }
            }
        }
    }
    val range = selection.range
    val clipboard = LocalClipboardManager.current

    selection.highlightToRemove?.let { highlight ->
        AlertDialog(
            onDismissRequest = { selection.highlightToRemove = null },
            title = { Text("Remove highlight?") },
            text = { Text(highlight.quote, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    selection.highlightToRemove = null
                    scope.launch { dao.deleteHighlight(highlight.id) }
                }) { Text("Remove highlight") }
            },
            dismissButton = {
                TextButton(onClick = { selection.highlightToRemove = null }) { Text("Cancel") }
            }
        )
    }

    Box(Modifier.fillMaxSize()
        .onSizeChanged { viewport = it }
        .onGloballyPositioned { root = it.positionInRoot() }) {
        CompositionLocalProvider(LocalReaderSelection provides selection) {
            Box(Modifier.fillMaxSize().then(
                if (range != null) Modifier.noRippleClickable { selection.range = null } else Modifier
            )) {
                content(range == null)
            }
        }
        if (range != null) {
            SelectionHandle(selection, true, root)
            SelectionHandle(selection, false, root)
            val text = remember(nodes, range) { selectedReaderText(nodes, range) }
            if (selection.dragPoint == null && text.isNotBlank()) {
                val chapter = nodes.lastOrNull {
                    it.node.id.id <= range.start.nodeId && it is ReaderNode.Header
                }?.sourceText()
                val author = book.author.asString(context)
                var editNote by remember(range) { mutableStateOf(false) }
                fun saveHighlight(note: String) {
                    scope.launch {
                        dao.insert(HighlightEntity(
                            bookUuid = book.uuid, bookId = book.id, bookTitle = book.title,
                            author = author, chapter = chapter, startNode = range.start.nodeId,
                            startOffset = range.start.offset, endNode = range.end.nodeId,
                            endOffset = range.end.offset, quote = text, color = 0xFFFFC107.toInt(),
                            note = note
                        ))
                        selection.range = null
                    }
                }
                val singleWord = range.start.nodeId == range.end.nodeId && isReaderWord(text)
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .then(if (singleWord) Modifier.padding(horizontal = 20.dp) else Modifier)
                        .onSizeChanged { cardSize = it }
                        .absoluteOffset {
                            val startY = selection.handle(range.start, true)?.y?.minus(root.y)
                            val endY = selection.handle(range.end, false)?.y?.minus(root.y)
                            val bottom = (viewport.height - cardSize.height).coerceAtLeast(0)
                            val top = when {
                                singleWord -> {
                                    val lineTop = selection.handle(range.start, true, lineTop = true)
                                        ?.y?.minus(root.y)?.roundToInt()
                                    val minTop = (safeTop - root.y.roundToInt()).coerceAtLeast(0) + dictionaryGap
                                    val maxTop = (bottom - safeBottom - dictionaryGap).coerceAtLeast(minTop)
                                    val above = (lineTop ?: minTop) - cardSize.height - dictionaryGap
                                    val below = endY?.roundToInt()?.plus(cardGap.roundToInt())
                                    when {
                                        above >= minTop -> above.coerceAtMost(maxTop)
                                        below != null && below <= maxTop -> below.coerceAtLeast(minTop)
                                        else -> minTop
                                    }
                                }
                                endY != null && endY + cardGap <= bottom -> bottom
                                startY != null && startY > cardSize.height + cardGap -> 0
                                else -> bottom
                            }
                            IntOffset(0, top - bottom)
                        },
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp
                ) {
                    if (singleWord) {
                        val saved by remember(dao, book.uuid, range.start) {
                            dao.vocabulary(book.uuid, range.start.nodeId, range.start.offset)
                        }.collectAsState(initial = null)
                        ReaderDictionaryCard(
                            word = text,
                            modifier = Modifier
                                .heightIn(max = with(density) { (viewport.height * 0.55f).toDp().coerceAtLeast(160.dp) })
                                .verticalScroll(rememberScrollState()),
                            saved = saved != null,
                            onSave = {
                                val existing = saved
                                scope.launch {
                                    if (existing != null) dao.deleteWord(existing.id)
                                    else dao.insert(VocabularyEntity(
                                        word = text, lookupKey = dictionaryKey(text),
                                        context = readerSentence(nodes, range.start),
                                        bookUuid = book.uuid, bookId = book.id, bookTitle = book.title,
                                        author = author, chapter = chapter,
                                        startNode = range.start.nodeId, startOffset = range.start.offset
                                    ))
                                }
                            },
                            onHighlight = { saveHighlight("") },
                            onExternalDictionary = { onDictionaryRequested(text) }
                        )
                    } else {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { saveHighlight("") }) { Text("Highlight") }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(text))
                                onCopyRequested()
                                selection.range = null
                            }) { Text("Copy") }
                            TextButton(onClick = { onTranslateRequested(text) }) { Text("Translate") }
                            TextButton(onClick = { editNote = true }) { Text("Note") }
                            TextButton(onClick = { onShareRequested(text) }) { Text("Share") }
                            TextButton(onClick = { onWebSearchRequested(text) }) { Text("Search") }
                        }
                    }
                }
                if (editNote) ReaderNoteDialog("", { editNote = false }) { saveHighlight(it) }
            }
        }
    }
}

@Composable
private fun BoxScope.SelectionHandle(selection: ReaderSelection, start: Boolean, root: Offset) {
    val range = selection.range ?: return
    val anchor = if (start) range.start else range.end
    val position = selection.handle(anchor, start)
        ?: selection.dragPoint?.takeIf { selection.draggingStart == start }
        ?: return
    val targetRadius = with(LocalDensity.current) { 22.dp.toPx() }
    val horizontalShift = with(LocalDensity.current) { if (start) -6.dp.toPx() else 6.dp.toPx() }
    Box(
        modifier = Modifier
            .absoluteOffset {
                IntOffset((position.x - root.x - targetRadius + horizontalShift).roundToInt(),
                    (position.y - root.y - targetRadius / 2).roundToInt())
            }
            .size(44.dp)
            .semantics { contentDescription = if (start) "Selection start" else "Selection end" }
            .pointerInput(selection, start) {
                detectDragGestures(
                    onDragStart = {
                        val current = selection.range
                        if (current != null) {
                            selection.draggingStart = start
                            selection.dragPoint = selection.handle(if (start) current.start else current.end, start)
                        }
                    },
                    onDragCancel = { selection.dragPoint = null },
                    onDragEnd = { selection.dragPoint = null }
                ) { change, delta ->
                    change.consume()
                    val point = (selection.dragPoint ?: return@detectDragGestures) + delta
                    selection.dragPoint = point
                    selection.drag(point - Offset(0f, 1f))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(13.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}


internal fun readerSentence(nodes: List<ReaderNode>, anchor: ReaderAnchor): String {
    val text = nodes.firstOrNull { it.node.id.id == anchor.nodeId }?.sourceText().orEmpty()
    if (text.isEmpty()) return text
    val offset = anchor.offset.coerceIn(text.indices)
    val iterator = BreakIterator.getSentenceInstance().apply { setText(text) }
    val start = iterator.preceding(offset + 1).coerceAtLeast(0)
    val end = iterator.following(offset).takeIf { it != BreakIterator.DONE } ?: text.length
    return text.substring(start, end).trim()
}
