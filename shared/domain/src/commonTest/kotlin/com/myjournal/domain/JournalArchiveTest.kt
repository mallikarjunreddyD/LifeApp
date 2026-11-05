package com.myjournal.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class JournalArchiveTest {
    private val date = LocalDate(2026,11,5)
    private class Store: JournalEntryRepository {
        val entries = mutableMapOf<LocalDate,JournalEntry>()
        val ink = mutableMapOf<Uuid,List<InkStroke>>()
        val edits = mutableListOf<PageEdit>()
        var fail = false
        override fun observe(date: LocalDate) = flowOf(entries[date])
        override suspend fun find(date: LocalDate) = entries[date]
        override suspend fun loadStrokes(pageId: Uuid) = ink[pageId] ?: emptyList()
        override suspend fun apply(edit: PageEdit) {
            if (fail) error("disk full")
            edits.add(edit)
            val old = entries[edit.date]
            val previous = old?.pages?.firstOrNull { it.metadata.id == edit.page.metadata.id }
            val page = edit.page.copy(archived = edit.setArchived ?: previous?.archived ?: false)
            val pages = (old?.pages ?: emptyList()).filterNot { it.metadata.id == page.metadata.id } + page
            entries[edit.date] = JournalEntry(old?.metadata ?: newRecordMetadata().copy(id=page.entryId),edit.date,pages.sortedBy{it.order})
            ink[page.metadata.id] = (ink[page.metadata.id] ?: emptyList()).filterNot { it.metadata.id in edit.remove } + edit.put
        }
    }
    private fun stroke(editor: JournalEditor, id: Uuid? = null) = editor.addStroke(BrushTool.PEN,0xFF2859A8,3f,
        listOf(InkPoint(200f,300f,0,0.4f),InkPoint(240f,350f,10,0.8f)),date,id)!!
    @Test fun lastPageCannotBeDeletedAndViewingStillCreatesNoStoredRecord() = runTest {
        val store=Store();val editor=JournalEditor(store,backgroundScope,date)
        editor.open(date);runCurrent()
        assertFalse(editor.state.value.canDeletePage)
        assertFalse(editor.setPageArchived(editor.state.value.pageId!!,true))
        assertTrue(store.entries.isEmpty());assertEquals(1,editor.state.value.activePages.size)
    }
    @Test fun archiveRestoreAndAppendKeepOriginalIdsOrdersAndExactVectors() = runTest {
        val store=Store();val editor=JournalEditor(store,backgroundScope,date)
        editor.open(date);runCurrent();val first=editor.state.value.pageId!!;val original=stroke(editor);runCurrent()
        editor.addPage();runCurrent();val second=editor.state.value.pageId!!
        editor.addPage();runCurrent();val third=editor.state.value.pageId!!
        editor.selectPage(first);runCurrent()
        assertTrue(editor.setPageArchived(first,true));runCurrent()
        assertEquals(second,editor.state.value.pageId);assertEquals(listOf(second,third),editor.state.value.activePages.map{it.metadata.id})
        assertEquals(first,editor.state.value.archivedPages.single().metadata.id)
        assertEquals(listOf(original),store.ink[first])
        editor.addPage();runCurrent();val added=editor.state.value.page!!;assertEquals(3,added.order)
        assertTrue(editor.setPageArchived(first,false));runCurrent();editor.selectPage(first);runCurrent()
        assertEquals(original,editor.state.value.strokes.single());assertEquals(0,editor.state.value.page!!.order)
        assertEquals(original.metadata,editor.state.value.strokes.single().metadata)
        assertEquals(4,editor.state.value.activePages.size)
        assertFailsWith<IllegalArgumentException>{JournalEntry(newRecordMetadata().copy(id=added.entryId),date,listOf(added.copy(archived=true)))}
    }
    @Test fun failedArchiveAndLateStrokeKeepOriginalOwnershipAcrossNavigationAndRetry() = runTest {
        val store=Store();val editor=JournalEditor(store,backgroundScope,date,maxLoadedDates=1,maxLoadedPages=1)
        editor.open(date);runCurrent();val first=editor.state.value.pageId!!;val original=stroke(editor);runCurrent()
        editor.addPage();runCurrent();val second=editor.state.value.pageId!!
        editor.selectPage(first);runCurrent();editor.retainSurface(date,first)
        store.fail=true;assertTrue(editor.setPageArchived(first,true));runCurrent()
        assertEquals(second,editor.state.value.pageId);assertNotNull(editor.state.value.error)
        repeat(8){editor.open(LocalDate(2026,12,it+1));runCurrent()}
        val late=stroke(editor,first);runCurrent();editor.releaseSurface(date,first)
        store.fail=false;editor.retry();runCurrent()
        assertEquals(0,editor.state.value.pending)
        assertTrue(store.entries.getValue(date).pages.single{it.metadata.id==first}.archived)
        assertEquals(listOf(original,late),store.ink[first])
        editor.open(date,first);runCurrent();assertEquals(second,editor.state.value.pageId)
        editor.setPageArchived(first,false);runCurrent();editor.selectPage(first);runCurrent()
        assertEquals(listOf(original,late),editor.state.value.strokes)
    }
    @Test fun deletingAndRestoringManyPagesAlwaysKeepsOneActivePage() = runTest {
        val store=Store();val editor=JournalEditor(store,backgroundScope,date)
        editor.open(date);runCurrent();repeat(4){editor.addPage();runCurrent()}
        val ids=editor.state.value.activePages.map{it.metadata.id}
        for(id in ids.dropLast(1)){assertTrue(editor.setPageArchived(id,true));runCurrent()}
        assertEquals(ids.last(),editor.state.value.pageId);assertFalse(editor.setPageArchived(ids.last(),true))
        for(id in ids.dropLast(1)){editor.setPageArchived(id,false);runCurrent()}
        assertEquals(ids,editor.state.value.activePages.map{it.metadata.id})
    }
}
