package com.myjournal.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class NotebookState(val notebooks: List<Notebook> = emptyList(), val notebook: Notebook? = null,
    val pages: List<NotebookPage> = emptyList(), val pageId: Uuid? = null, val strokes: List<InkStroke> = emptyList(),
    val loading: Boolean = true, val pending: Int = 0, val error: String? = null,
    val canUndo: Boolean = false, val canRedo: Boolean = false) {
    val page get() = pages.firstOrNull { it.metadata.id == pageId }
}

/** Single-dispatcher application-owned editor. Only selected-page vectors load. */
class NotebookEditor(private val repository: NotebookRepository, private val scope: CoroutineScope) {
    private class Draft(var book: Notebook, var pages: List<NotebookPage>, var selected: Uuid?,
        val histories: MutableMap<Uuid, StrokeHistory> = mutableMapOf(), val orders: MutableMap<Uuid, Long> = mutableMapOf())
    private val books = linkedMapOf<Uuid, Notebook>()
    private val drafts = linkedMapOf<Uuid, Draft>()
    private val retained = mutableMapOf<Pair<Uuid, Uuid>, Int>()
    private val loadingPages = mutableSetOf<Pair<Uuid, Uuid>>()
    private val selection = mutableMapOf<Uuid, Uuid>()
    private var active: Uuid? = null
    private var loading = true
    private var readError: String? = null
    private var generation = 0L
    private val mutable = MutableStateFlow(NotebookState())
    val state = mutable.asStateFlow()
    val loadedPageCount get() = drafts.values.sumOf { it.histories.size }
    private val writes = SaveQueue<NotebookEdit>(scope, repository::apply) { trim(); publish() }
    fun open(id: Uuid? = null, preferredPage: Uuid? = null) {
        active = id; readError = null
        val token = ++generation
        loading = true; publish()
        scope.launch {
            try {
                repository.all().forEach { book ->
                    if (!writes.any { it.notebook.metadata.id == book.metadata.id }) books[book.metadata.id] = book
                }
                if (id == null) { if (generation == token) { loading = false; trim(); publish() }; return@launch }
                val draft = drafts[id] ?: run {
                    val book = requireNotNull(books[id]) { "Notebook no longer exists" }
                    Draft(book, repository.pages(id), null).also { drafts[id] = it }
                }
                val selected = listOfNotNull(preferredPage, selection[id], draft.selected).firstOrNull { target -> draft.pages.any { it.metadata.id == target && !it.archived } }
                    ?: draft.pages.firstOrNull { !it.archived }?.metadata?.id
                if (generation != token) { trim(); return@launch }
                draft.selected = selected
                if (selected != null) {
                    selection[id] = selected
                    if (selected !in draft.histories) {
                        val key = id to selected; loadingPages.add(key)
                        try {
                            val strokes = repository.loadStrokes(selected)
                            if (selected !in draft.histories) {
                                draft.histories[selected] = StrokeHistory(strokes)
                                draft.orders[selected] = (strokes.maxOfOrNull { it.order } ?: -1) + 1
                            }
                        } finally { loadingPages.remove(key) }
                    }
                }
                if (generation == token) { loading = false; touch(id); trim(); publish() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == token) { loading = false; readError = "Cannot load notebook. ${failure.message}"; publish() } }
        }
    }
    fun create(name: String, description: String?, template: PaperTemplate) {
        val book = Notebook(newRecordMetadata(), name.trim(), description, template)
        val page = NotebookPage(newRecordMetadata(), book.metadata.id, 0, paper = template)
        val draft = Draft(book, listOf(page), page.metadata.id)
        draft.histories[page.metadata.id] = StrokeHistory(emptyList()); draft.orders[page.metadata.id] = 0
        drafts[book.metadata.id] = draft; books[book.metadata.id] = book; active = book.metadata.id
        ++generation; loading = false; readError = null; selection[book.metadata.id] = page.metadata.id
        writes.add(NotebookEdit(book, draft.pages))
    }
    fun rename(name: String, description: String? = state.value.notebook?.description) {
        val draft = drafts[active] ?: return
        draft.book = draft.book.copy(name = name.trim(), description = description)
        submit(draft)
    }
    fun archiveNotebook(archived: Boolean) {
        val draft = drafts[active] ?: return
        draft.book = draft.book.copy(archived = archived); submit(draft)
    }
    fun selectPage(id: Uuid) { active?.let { open(it, id) } }
    fun addPage(template: PaperTemplate, title: String? = null) {
        val draft = drafts[active] ?: return
        if (loading || readError != null || draft.book.archived) return
        val page = NotebookPage(newRecordMetadata(), draft.book.metadata.id, (draft.pages.maxOfOrNull { it.order } ?: -1) + 1,
            title = title, paper = template)
        draft.pages = draft.pages + page; draft.selected = page.metadata.id
        draft.histories[page.metadata.id] = StrokeHistory(emptyList()); draft.orders[page.metadata.id] = 0
        selection[draft.book.metadata.id] = page.metadata.id; submit(draft)
    }
    fun editPage(id: Uuid, title: String?, paper: PaperTemplate, archived: Boolean) {
        val draft = drafts[active] ?: return
        draft.pages = draft.pages.map { if (it.metadata.id == id) it.copy(title = title, paper = paper, archived = archived,
            metadata = it.metadata.revised(maxOf(Clock.System.now(), it.metadata.updatedAt))) else it }
        submit(draft)
        if (draft.selected == id && archived) open(draft.book.metadata.id)
    }
    fun movePage(id: Uuid, delta: Int) {
        val draft = drafts[active] ?: return
        val pages = draft.pages.sortedBy { it.order }.toMutableList()
        val index = pages.indexOfFirst { it.metadata.id == id }
        if (index < 0 || index + delta !in pages.indices) return
        val moved = pages.removeAt(index); pages.add(index + delta, moved)
        draft.pages = pages.mapIndexed { order, old -> if (old.order == order) old else old.copy(order = order,
            metadata = old.metadata.revised(maxOf(Clock.System.now(), old.metadata.updatedAt))) }
        submit(draft)
    }
    fun addStroke(tool: BrushTool, color: Long, width: Float, points: List<InkPoint>, bookId: Uuid, pageId: Uuid): InkStroke? {
        val draft = drafts[bookId] ?: return null
        val page = draft.pages.first { it.metadata.id == pageId }
        if (draft.book.archived || page.archived) return null
        val order = draft.orders.getValue(pageId); check(order < Long.MAX_VALUE)
        val stroke = InkStroke(newRecordMetadata(), pageId, order, tool, color, width, points.toList())
        draft.orders[pageId] = order + 1
        submitChange(draft, pageId, draft.histories.getValue(pageId).add(stroke)); return stroke
    }
    fun erase(ids: Set<Uuid>, bookId: Uuid, pageId: Uuid) {
        val draft = drafts[bookId] ?: return
        draft.histories[pageId]?.erase(ids)?.let { submitChange(draft, pageId, it) }
    }
    fun undo() = history { it.undo() }
    fun redo() = history { it.redo() }
    private fun history(action: (StrokeHistory) -> StrokeChange?) {
        if (loading || readError != null) return
        val draft = drafts[active] ?: return; val page = draft.selected ?: return
        action(draft.histories.getValue(page))?.let { submitChange(draft, page, it) }
    }
    private fun submitChange(draft: Draft, pageId: Uuid, change: StrokeChange) {
        draft.pages = draft.pages.map { if (it.metadata.id == pageId) it.copy(metadata = it.metadata.revised(maxOf(Clock.System.now(), it.metadata.updatedAt))) else it }
        submit(draft, change.added, change.removed.map { it.metadata.id }.toSet())
    }
    private fun submit(draft: Draft, put: List<InkStroke> = emptyList(), remove: Set<Uuid> = emptySet()) {
        draft.book = draft.book.copy(metadata = draft.book.metadata.revised(maxOf(Clock.System.now(), draft.book.metadata.updatedAt)))
        books[draft.book.metadata.id] = draft.book; touch(draft.book.metadata.id)
        writes.add(NotebookEdit(draft.book, draft.pages, put, remove))
    }
    fun retry() { if (readError != null) open(active); writes.retry() }
    fun retain(book: Uuid, page: Uuid) { val key = book to page; retained[key] = (retained[key] ?: 0) + 1 }
    fun release(book: Uuid, page: Uuid) { val key = book to page; val count = (retained[key] ?: 1) - 1
        if (count <= 0) retained.remove(key) else retained[key] = count; trim() }
    private fun touch(id: Uuid) { drafts.remove(id)?.let { drafts[id] = it } }
    private fun trim() {
        fun protected(book: Uuid, page: Uuid) = active == book && drafts[book]?.selected == page ||
            book to page in retained || book to page in loadingPages || writes.any { edit ->
                edit.notebook.metadata.id == book && (edit.put.any { it.pageId == page } || edit.remove.isNotEmpty()) }
        for ((id, draft) in drafts.toMap()) {
            if (drafts.size > 4 && id != active && !writes.any { it.notebook.metadata.id == id } && draft.pages.none { protected(id, it.metadata.id) }) drafts.remove(id)
        }
        for ((id, draft) in drafts) for (page in draft.histories.keys.toList()) {
            if (loadedPageCount > 3 && !protected(id, page)) { draft.histories.remove(page); draft.orders.remove(page) }
        }
    }
    private fun publish() {
        val draft = drafts[active]; val history = draft?.histories?.get(draft.selected)
        mutable.value = NotebookState(books.values.toList(), if (readError == null) draft?.book else null,
            draft?.pages ?: emptyList(), draft?.selected, history?.current ?: emptyList(), loading, writes.count,
            readError ?: writes.error, history?.canUndo == true, history?.canRedo == true)
    }
}
