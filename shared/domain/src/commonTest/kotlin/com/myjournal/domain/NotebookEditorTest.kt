package com.myjournal.domain

import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotebookEditorTest {
    private class Store: NotebookRepository {
        val books=linkedMapOf<Uuid,Notebook>();val allPages=mutableMapOf<Uuid,List<NotebookPage>>()
        val ink=mutableMapOf<Uuid,List<InkStroke>>();val loaded=mutableListOf<Uuid>();var fail=false
        override suspend fun all()=books.values.toList()
        override suspend fun pages(notebookId: Uuid)=allPages[notebookId] ?: emptyList()
        override suspend fun loadStrokes(pageId: Uuid): List<InkStroke> { loaded.add(pageId);return ink[pageId] ?: emptyList() }
        override suspend fun apply(edit: NotebookEdit) {
            if(fail) error("disk full")
            books[edit.notebook.metadata.id]=edit.notebook;allPages[edit.notebook.metadata.id]=edit.pages
            edit.pages.forEach { page -> ink[page.metadata.id]=(ink[page.metadata.id] ?: emptyList()).filterNot { it.metadata.id in edit.remove } }
            edit.put.forEach { stroke -> ink[stroke.pageId]=(ink[stroke.pageId] ?: emptyList()).filterNot { it.metadata.id==stroke.metadata.id }+stroke }
        }
    }
    private fun write(editor: NotebookEditor): InkStroke {
        val state=editor.state.value
        return editor.addStroke(BrushTool.PEN,0xFF2859A8,4f,listOf(InkPoint(120f,180f,0,0.3f),InkPoint(140f,200f,12,0.8f)),state.notebook!!.metadata.id,state.pageId!!)!!
    }
    @Test fun createRenameTemplatesPageOrderArchiveAndRestorePreserveEditableInk() = runTest {
        val store=Store();val editor=NotebookEditor(store,backgroundScope)
        editor.open();runCurrent();assertTrue(store.books.isEmpty())
        editor.create("Research Notes","Description",PaperTemplate.RULED);runCurrent()
        val id=editor.state.value.notebook!!.metadata.id;val page=editor.state.value.page!!;val stroke=write(editor);runCurrent()
        editor.rename("Research");editor.editPage(page.metadata.id,"First",PaperTemplate.GRID,false);runCurrent()
        assertEquals(listOf(stroke),editor.state.value.strokes)
        editor.addPage(PaperTemplate.DOTTED,"Second");runCurrent()
        val second=editor.state.value.pageId!!
        editor.movePage(second,-1);runCurrent();assertEquals(second,editor.state.value.pages.minBy { it.order }.metadata.id)
        editor.selectPage(page.metadata.id);runCurrent()
        assertEquals(listOf(stroke),editor.state.value.strokes)
        editor.editPage(page.metadata.id,"First",PaperTemplate.GRID,true);runCurrent()
        assertEquals(second,editor.state.value.pageId)
        editor.editPage(page.metadata.id,"First",PaperTemplate.BLANK,false);editor.selectPage(page.metadata.id);runCurrent()
        editor.erase(setOf(stroke.metadata.id),id,page.metadata.id);editor.undo();runCurrent()
        assertEquals(listOf(stroke),editor.state.value.strokes)
        editor.redo();runCurrent();assertTrue(editor.state.value.strokes.isEmpty())
        editor.undo();editor.archiveNotebook(true);runCurrent();assertTrue(editor.state.value.notebook!!.archived)
        editor.archiveNotebook(false);runCurrent();assertEquals(listOf(stroke),editor.state.value.strokes)
        assertEquals(id,editor.state.value.notebook!!.metadata.id)
    }
    @Test fun manySavedPagesAreLazyAndEvictedVectorsReloadWithoutChangingIdentity() = runTest {
        val store=Store();val editor=NotebookEditor(store,backgroundScope)
        editor.create("Large",null,PaperTemplate.RULED);runCurrent()
        val book=editor.state.value.notebook!!.metadata.id
        val originalPage=editor.state.value.pageId!!;val original=write(editor);runCurrent()
        repeat(15) { editor.addPage(PaperTemplate.DOTTED);write(editor);runCurrent() }
        assertTrue(editor.loadedPageCount<=3)
        editor.selectPage(originalPage);runCurrent()
        assertEquals(listOf(original),editor.state.value.strokes)
        assertEquals(originalPage,store.loaded.last())
        editor.open();runCurrent();editor.open(book);runCurrent();assertEquals(originalPage,editor.state.value.pageId)
    }
    @Test fun failedAndNativeBoundWritesStayAssociatedWithOriginalNotebook() = runTest {
        val store=Store();val editor=NotebookEditor(store,backgroundScope)
        editor.create("A",null,PaperTemplate.RULED);runCurrent()
        val a=editor.state.value.notebook!!.metadata.id;val page=editor.state.value.pageId!!
        editor.retain(a,page);store.fail=true
        val original=write(editor);runCurrent();assertNotNull(editor.state.value.error)
        repeat(8) { editor.create("B$it",null,PaperTemplate.BLANK);runCurrent() }
        editor.addStroke(BrushTool.PEN,0xFF000000,2f,listOf(InkPoint(200f,200f,0)),a,page)
        editor.release(a,page);store.fail=false;editor.retry();runCurrent()
        assertEquals(0,editor.state.value.pending)
        editor.open(a,page);runCurrent();assertEquals(original,editor.state.value.strokes.first());assertEquals(2,editor.state.value.strokes.size)
    }
    @Test fun notebookAndPageValidationRejectInvalidNamesGeometryAndOwnership() {
        assertFailsWith<IllegalArgumentException>{Notebook(newRecordMetadata()," ")}
        assertFailsWith<IllegalArgumentException>{NotebookPage(newRecordMetadata(),Uuid.random(),0,width=Float.NaN)}
        val book=Notebook(newRecordMetadata(),"Name")
        assertFailsWith<IllegalArgumentException>{NotebookEdit(book,listOf(NotebookPage(newRecordMetadata(),Uuid.random(),0)))}
    }
}
