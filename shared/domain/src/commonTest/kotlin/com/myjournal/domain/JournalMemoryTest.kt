package com.myjournal.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class JournalMemoryTest {
    private val start = LocalDate(2026, 11, 1)
    private val points = listOf(InkPoint(100f, 100f, 0), InkPoint(200f, 200f, 10))
    private class Store : JournalEntryRepository {
        val entries = mutableMapOf<LocalDate, JournalEntry>()
        val strokes = mutableMapOf<Uuid, MutableMap<Uuid, InkStroke>>()
        val loads = mutableListOf<Uuid>()
        var fail = false
        override fun observe(date: LocalDate) = flowOf(entries[date])
        override suspend fun find(date: LocalDate) = entries[date]
        override suspend fun loadStrokes(pageId: Uuid): List<InkStroke> {
            loads.add(pageId); return strokes[pageId]?.values?.sortedBy { it.order } ?: emptyList()
        }
        override suspend fun apply(edit: PageEdit) {
            if (fail) error("disk unavailable")
            val old = entries[edit.date]
            val pages = old?.pages?.toMutableList() ?: mutableListOf()
            if (pages.none { it.metadata.id == edit.page.metadata.id }) pages.add(edit.page)
            entries[edit.date] = JournalEntry(old?.metadata ?: edit.page.metadata.copy(id = edit.page.entryId), edit.date, pages)
            val ink = strokes.getOrPut(edit.page.metadata.id) { mutableMapOf() }
            edit.remove.forEach(ink::remove); edit.put.forEach { ink[it.metadata.id] = it }
        }
    }
    @Test fun manySavedDatesAreUnloadedAndReloadFromRepositoryWithoutLosingIds() = runTest {
        val store = Store(); val editor = JournalEditor(store, backgroundScope, start, 3, 2)
        editor.open(start); runCurrent()
        val original = editor.addStroke(BrushTool.PEN, 0xFF000000, 3f, points)!!; runCurrent()
        for (day in 1..20) { editor.open(start.plus(day, DateTimeUnit.DAY)); runCurrent() }
        assertTrue(editor.loadedDateCount <= 3)
        assertTrue(editor.loadedPageCount <= 2)
        editor.open(start); runCurrent()
        assertEquals(listOf(original), editor.state.value.strokes)
        assertTrue(original.pageId in store.loads)
    }
    @Test fun unrelatedPagesAreNotLoadedAndSelectionSurvivesEviction() = runTest {
        val store = Store(); val entry = newRecordMetadata()
        val pages = (0..4).map { JournalPage(newRecordMetadata(), entry.id, it) }
        store.entries[start] = JournalEntry(entry, start, pages)
        val editor = JournalEditor(store, backgroundScope, start, 2, 1)
        editor.open(start, pages[3].metadata.id); runCurrent()
        assertEquals(listOf(pages[3].metadata.id), store.loads)
        editor.selectPage(pages[1].metadata.id); runCurrent()
        assertEquals(1, editor.loadedPageCount)
        for (day in 1..5) { editor.open(start.plus(day, DateTimeUnit.DAY)); runCurrent() }
        editor.open(start); runCurrent()
        assertEquals(pages[1].metadata.id, editor.state.value.pageId)
        assertEquals(pages[1].metadata.id, store.loads.last())
    }
    @Test fun failedWritesPinTheirOriginalDateAndPageUntilRetryCommits() = runTest {
        val store = Store(); val editor = JournalEditor(store, backgroundScope, start, 1, 1)
        editor.open(start); runCurrent()
        store.fail = true
        val original = editor.addStroke(BrushTool.PEN, 0xFF000000, 3f, points)!!; runCurrent()
        for (day in 1..10) { editor.open(start.plus(day, DateTimeUnit.DAY)); runCurrent() }
        assertEquals(1, editor.state.value.pending)
        assertNotNull(editor.state.value.error)
        editor.open(start); runCurrent()
        assertEquals(listOf(original), editor.state.value.strokes)
        store.fail = false; editor.retry(); runCurrent()
        assertEquals(listOf(original), store.strokes.getValue(original.pageId).values.toList())
        editor.open(start.plus(20, DateTimeUnit.DAY)); runCurrent()
        assertEquals(1, editor.loadedDateCount)
        editor.open(start); runCurrent()
        assertEquals(listOf(original), editor.state.value.strokes)
    }
    @Test fun boundNativeSurfacesProtectAnUnfinishedGestureDuringRapidNavigation() = runTest {
        val store = Store(); val editor = JournalEditor(store, backgroundScope, start, 1, 1)
        editor.open(start); runCurrent()
        val page = editor.state.value.pageId!!
        editor.retainSurface(start, page)
        for (day in 1..6) { editor.open(start.plus(day, DateTimeUnit.DAY)); runCurrent() }
        val late = editor.addStroke(BrushTool.PEN, 0xFF000000, 3f, points, start, page)
        assertNotNull(late)
        editor.releaseSurface(start, page); runCurrent()
        assertEquals(start.plus(6, DateTimeUnit.DAY), editor.state.value.date)
        editor.open(start); runCurrent()
        assertEquals(listOf(late), editor.state.value.strokes)
    }
}
