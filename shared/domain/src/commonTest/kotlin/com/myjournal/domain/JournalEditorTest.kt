package com.myjournal.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class JournalEditorTest {
    private val date = LocalDate(2026, 11, 3)
    private val points = listOf(InkPoint(100f, 100f, 0, 0.5f), InkPoint(200f, 200f, 10, 0.9f))
    private class Store : JournalEntryRepository {
        val edits = mutableListOf<PageEdit>()
        var fail = false
        var failRead = false
        var gate: CompletableDeferred<Unit>? = null
        override fun observe(date: LocalDate) = flowOf<JournalEntry?>(null)
        override suspend fun find(date: LocalDate): JournalEntry? {
            if (failRead) error("read unavailable")
            return null
        }
        override suspend fun loadStrokes(pageId: Uuid) = emptyList<InkStroke>()
        override suspend fun apply(edit: PageEdit) {
            gate?.await()
            if (fail) error("disk full")
            edits.add(edit)
        }
    }
    @Test fun viewingDatesCreatesNoRecordsAndKeepsBlankIdentity() = runTest {
        val store = Store(); val editor = JournalEditor(store, backgroundScope, date)
        editor.open(date); runCurrent()
        val page = editor.state.value.pageId
        editor.open(LocalDate(2026, 11, 4)); runCurrent(); editor.open(date)
        assertEquals(page, editor.state.value.pageId)
        assertTrue(store.edits.isEmpty())
    }
    @Test fun savedStatusWaitsForCommitAndWritesCompletedStrokesOnlyOnce() = runTest {
        val store = Store(); val editor = JournalEditor(store, backgroundScope, date)
        editor.open(date); runCurrent()
        store.gate = CompletableDeferred()
        editor.addStroke(BrushTool.PEN, 0xFF000000, 3f, points); runCurrent()
        assertEquals(1, editor.state.value.pending)
        assertEquals(points, editor.state.value.strokes.single().points)
        assertTrue(store.edits.isEmpty())
        store.gate!!.complete(Unit); runCurrent()
        assertEquals(0, editor.state.value.pending)
        assertEquals(1, store.edits.size)
    }
    @Test fun failedWritesRetainInkAndRetryInOrderAcrossDateChanges() = runTest {
        val store = Store(); store.fail = true
        val editor = JournalEditor(store, backgroundScope, date)
        editor.open(date); runCurrent()
        val originalPage = editor.state.value.pageId!!
        editor.addStroke(BrushTool.PEN, 0xFF000000, 3f, points); runCurrent()
        assertEquals(1, editor.state.value.pending)
        assertNotNull(editor.state.value.error)
        val original = editor.state.value.strokes.single()
        editor.open(LocalDate(2026, 11, 4)); runCurrent()
        // A late native-view handoff remains pinned to the original page and date.
        editor.addStroke(BrushTool.HIGHLIGHTER, 0x55FFFF00, 18f, points, date, originalPage)
        store.fail = false; editor.retry(); runCurrent()
        assertEquals(listOf(date, date), store.edits.map { it.date })
        assertEquals(0, editor.state.value.pending)
        editor.open(date)
        assertEquals(original, editor.state.value.strokes.first())
        assertEquals(2, editor.state.value.strokes.size)
    }
    @Test fun multiplePagesAndHistoryStayAttachedToTheirOwnPage() = runTest {
        val store = Store(); val editor = JournalEditor(store, backgroundScope, date)
        editor.open(date); runCurrent()
        val first = editor.state.value.pageId!!
        editor.addStroke(BrushTool.PEN, 0xFF000000, 3f, points)
        val original = editor.state.value.strokes.single()
        editor.addPage(); val second = editor.state.value.pageId!!
        assertNotEquals(first, second)
        editor.addStroke(BrushTool.HIGHLIGHTER, 0x550000FF, 18f, points)
        editor.selectPage(first); editor.erase(setOf(original.metadata.id)); editor.undo()
        assertEquals(listOf(original), editor.state.value.strokes)
        editor.redo(); assertTrue(editor.state.value.strokes.isEmpty())
        editor.selectPage(second); assertEquals(BrushTool.HIGHLIGHTER, editor.state.value.strokes.single().tool)
        runCurrent()
        assertEquals(0, editor.state.value.pending)
        assertEquals(2, editor.state.value.entry!!.pages.size)
    }
    @Test fun addPageOnUnwrittenDatePersistsBothBlankPagesInOrder() = runTest {
        val store = Store(); val editor = JournalEditor(store, backgroundScope, date)
        editor.open(date); runCurrent(); editor.addPage(); runCurrent()
        assertEquals(listOf(0, 1), store.edits.map { it.page.order })
    }
    @Test fun readFailureDoesNotBecomeAnEditableBlankJournalAndCanRetry() = runTest {
        val store = Store(); store.failRead = true
        val editor = JournalEditor(store, backgroundScope, date)
        editor.open(date); runCurrent()
        assertNull(editor.state.value.entry)
        assertNotNull(editor.state.value.error)
        assertNull(editor.addStroke(BrushTool.PEN, 0xFF000000, 3f, points))
        store.failRead = false; editor.retry(); runCurrent()
        assertNotNull(editor.state.value.entry)
        assertNull(editor.state.value.error)
    }
}
