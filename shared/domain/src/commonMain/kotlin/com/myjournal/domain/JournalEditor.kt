package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class EditorState(
    val date: LocalDate,
    val entry: JournalEntry? = null,
    val pageId: Uuid? = null,
    val strokes: List<InkStroke> = emptyList(),
    val loading: Boolean = true,
    val pending: Int = 0,
    val error: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val page get() = entry?.pages?.firstOrNull { it.metadata.id == pageId }
}

/** Call on one dispatcher (Android main). The application-owned scope outlives activities.
 * Repository I/O/encoding is dispatched by the repository, never by pointer callbacks.
 */
class JournalEditor(
    private val repository: JournalEntryRepository,
    private val scope: CoroutineScope,
    initialDate: LocalDate,
) {
    private class Draft(var entry: JournalEntry, val histories: MutableMap<Uuid, StrokeHistory>, var selected: Uuid) {
        val submittedPages = mutableSetOf<Uuid>()
        var submitted = false
        val nextOrders = histories.mapValues { (_, h) -> (h.current.maxOfOrNull { it.order } ?: -1) + 1 }.toMutableMap()
    }
    private val drafts = mutableMapOf<LocalDate, Draft>()
    private val pending = ArrayDeque<PageEdit>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var activeDate = initialDate
    private var loading = true
    private var readError: String? = null
    private var writeError: String? = null
    private var request = 0L
    private val mutableState = MutableStateFlow(EditorState(initialDate))
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            for (ignored in signal) {
                if (writeError != null) continue
                while (pending.isNotEmpty()) {
                    try {
                        repository.apply(pending.first())
                        pending.removeFirst()
                        publish()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        writeError = "Changes are not saved. ${failure.message ?: "Storage failed"}"
                        publish()
                        break
                    }
                }
            }
        }
    }

    fun open(date: LocalDate, preferredPage: Uuid? = null) {
        val token = ++request
        activeDate = date
        readError = null
        val cached = drafts[date]
        if (cached != null) {
            if (preferredPage != null && preferredPage in cached.histories) cached.selected = preferredPage
            loading = false
            publish()
            return
        }
        loading = true
        publish()
        scope.launch {
            try {
                val stored = repository.find(date)
                val entry = stored ?: newEntry(date)
                val histories = mutableMapOf<Uuid, StrokeHistory>()
                for (page in entry.pages) histories[page.metadata.id] = StrokeHistory(repository.loadStrokes(page.metadata.id))
                val selected = preferredPage?.takeIf { it in histories } ?: entry.pages.first().metadata.id
                val loaded = Draft(entry, histories, selected).also { draft ->
                    draft.submitted = stored != null
                    if (stored != null) draft.submittedPages.addAll(histories.keys)
                }
                drafts.getOrPut(date) { loaded }
                if (request == token) { loading = false; publish() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (request == token) {
                    loading = false
                    readError = "Cannot load journal. ${failure.message ?: "Storage failed"}"
                    publish()
                }
            }
        }
    }

    fun selectPage(id: Uuid) {
        val draft = drafts[activeDate] ?: return
        require(id in draft.histories)
        draft.selected = id
        publish()
    }

    fun addPage() {
        if (loading || readError != null) return
        val draft = drafts[activeDate] ?: return
        // Viewing a date creates nothing; explicitly adding a page persists the initial blank too.
        if (!draft.submitted) submit(draft.entry.pages.first(), null)
        val page = JournalPage(newMetadata(), draft.entry.metadata.id, draft.entry.pages.size)
        draft.entry = draft.entry.copy(pages = draft.entry.pages + page)
        draft.histories[page.metadata.id] = StrokeHistory(emptyList())
        draft.nextOrders[page.metadata.id] = 0
        draft.selected = page.metadata.id
        submit(page, null)
    }

    fun addStroke(tool: BrushTool, colorArgb: Long, width: Float, points: List<InkPoint>,
        date: LocalDate = activeDate, pageId: Uuid? = null): InkStroke? {
        val draft = drafts[date] ?: return null
        val page = draft.entry.pages.first { it.metadata.id == (pageId ?: draft.selected) }
        val order = draft.nextOrders.getValue(page.metadata.id)
        check(order < Long.MAX_VALUE)
        val stroke = InkStroke(newMetadata(), page.metadata.id, order, tool, colorArgb, width, points.toList())
        val change = draft.histories.getValue(page.metadata.id).add(stroke)
        draft.nextOrders[page.metadata.id] = order + 1
        submit(page, change, date)
        return stroke
    }

    fun erase(ids: Set<Uuid>, date: LocalDate = activeDate, pageId: Uuid? = null) {
        val draft = drafts[date] ?: return
        val page = draft.entry.pages.first { it.metadata.id == (pageId ?: draft.selected) }
        draft.histories.getValue(page.metadata.id).erase(ids)?.let { submit(page, it, date) }
    }
    fun undo() = editHistory { it.undo() }
    fun redo() = editHistory { it.redo() }
    fun retry() {
        if (readError != null) open(activeDate)
        writeError = null
        signal.trySend(Unit)
        publish()
    }

    private fun editHistory(action: (StrokeHistory) -> StrokeChange?) {
        if (loading || readError != null) return
        val draft = drafts[activeDate] ?: return
        val page = draft.entry.pages.first { it.metadata.id == draft.selected }
        action(draft.histories.getValue(page.metadata.id))?.let { submit(page, it) }
    }
    private fun submit(page: JournalPage, change: StrokeChange?, date: LocalDate = activeDate) {
        val draft = drafts.getValue(date)
        val at = maxOf(Clock.System.now(), draft.entry.metadata.updatedAt, page.metadata.updatedAt)
        val entryMetadata = if (draft.submitted) draft.entry.metadata.revised(at) else draft.entry.metadata
        val pageMetadata = if (page.metadata.id in draft.submittedPages) page.metadata.revised(at) else page.metadata
        draft.entry = draft.entry.copy(metadata = entryMetadata,
            pages = draft.entry.pages.map { if (it.metadata.id == page.metadata.id) it.copy(metadata = pageMetadata) else it })
        draft.submitted = true
        draft.submittedPages.add(page.metadata.id)
        pending.addLast(PageEdit(date, page, change?.added ?: emptyList(),
            change?.removed?.map { it.metadata.id }?.toSet() ?: emptySet()))
        signal.trySend(Unit)
        publish()
    }
    private fun publish() {
        val draft = drafts[activeDate]
        val history = draft?.histories?.get(draft.selected)
        mutableState.value = EditorState(activeDate, draft?.entry, draft?.selected,
            history?.current ?: emptyList(), loading, pending.size, readError ?: writeError,
            history?.canUndo == true, history?.canRedo == true)
    }
    private fun newMetadata(): RecordMetadata {
        val at = Clock.System.now()
        return RecordMetadata(Uuid.random(), at, at)
    }
    private fun newEntry(date: LocalDate): JournalEntry {
        val metadata = newMetadata()
        return JournalEntry(metadata, date, listOf(JournalPage(metadata.copy(id = Uuid.random()), metadata.id, 0)))
    }
}
