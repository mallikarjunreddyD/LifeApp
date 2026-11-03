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
    private val maxLoadedDates: Int = 4,
    private val maxLoadedPages: Int = 3,
) {
    private class Draft(var entry: JournalEntry, val histories: MutableMap<Uuid, StrokeHistory>, var selected: Uuid) {
        val submittedPages = mutableSetOf<Uuid>()
        var submitted = false
        var accessed = 0L
        val pageAccess = mutableMapOf<Uuid, Long>()
        val nextOrders = histories.mapValues { (_, h) -> (h.current.maxOfOrNull { it.order } ?: -1) + 1 }.toMutableMap()
    }
    private val drafts = mutableMapOf<LocalDate, Draft>()
    private var accessClock = 0L
    private val selectedPages = linkedMapOf<LocalDate, Uuid>()
    private val retained = mutableMapOf<Pair<LocalDate, Uuid>, Int>()
    private val loads = mutableSetOf<Pair<LocalDate, Uuid>>()
    val loadedDateCount get() = drafts.size
    val loadedPageCount get() = drafts.values.sumOf { it.histories.size }
    fun retainSurface(date: LocalDate, id: Uuid) {
        val key = date to id
        retained[key] = (retained[key] ?: 0) + 1
    }
    fun releaseSurface(date: LocalDate, id: Uuid) {
        val key = date to id
        val count = (retained[key] ?: 1) - 1
        if (count <= 0) retained.remove(key) else retained[key] = count
        trim()
    }
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
        require(maxLoadedDates >= 1 && maxLoadedPages >= 1)
        scope.launch {
            for (ignored in signal) {
                if (writeError != null) continue
                while (pending.isNotEmpty()) {
                    try {
                        repository.apply(pending.first())
                        pending.removeFirst()
                        trim()
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
            activate(date, cached, preferredPage ?: selectedPages[date])
            return
        }
        loading = true
        publish()
        scope.launch {
            try {
                val stored = repository.find(date)
                val entry = stored ?: newEntry(date)
                val selected = (preferredPage ?: selectedPages[date])?.takeIf { id -> entry.pages.any { it.metadata.id == id } }
                    ?: entry.pages.first().metadata.id
                val draft = drafts.getOrPut(date) {
                    Draft(entry, mutableMapOf(), selected).also {
                        it.submitted = stored != null
                        if (stored != null) it.submittedPages.addAll(entry.pages.map { p -> p.metadata.id })
                        else it.histories[selected] = StrokeHistory(emptyList())
                    }
                }
                if (request == token) activate(date, draft, selected)
                else trim()
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

    private fun activate(date: LocalDate, draft: Draft, preferred: Uuid?) {
        if (preferred != null && draft.entry.pages.any { it.metadata.id == preferred }) draft.selected = preferred
        draft.accessed = ++accessClock
        val id = draft.selected
        selectedPages.remove(date); selectedPages[date] = id
        while (selectedPages.size > 128) selectedPages.remove(selectedPages.keys.first())
        draft.pageAccess[id] = ++accessClock
        if (id in draft.histories) {
            loading = false; trim(); publish(); return
        }
        loading = true
        publish()
        val key = date to id
        if (!loads.add(key)) return
        scope.launch {
            try {
                val history = StrokeHistory(repository.loadStrokes(id))
                draft.histories[id] = history
                draft.nextOrders[id] = (history.current.maxOfOrNull { it.order } ?: -1) + 1
                if (activeDate == date && draft.selected == id) { loading = false; readError = null; publish() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (activeDate == date && draft.selected == id) {
                    loading = false; readError = "Cannot load page. ${failure.message ?: "Storage failed"}"; publish()
                }
            } finally { loads.remove(key); trim() }
        }
    }

    fun selectPage(id: Uuid) {
        val draft = drafts[activeDate] ?: return
        require(draft.entry.pages.any { it.metadata.id == id })
        ++request; readError = null
        activate(activeDate, draft, id)
    }

    private fun trim() {
        fun protected(date: LocalDate, id: Uuid) = date == activeDate && drafts[date]?.selected == id ||
            retained.containsKey(date to id) || (date to id) in loads ||
            pending.any { it.date == date && it.page.metadata.id == id }
        for ((date, draft) in drafts.entries.sortedBy { it.value.accessed }) {
            if (drafts.size <= maxLoadedDates) break
            if (date != activeDate && draft.entry.pages.none { protected(date, it.metadata.id) }) drafts.remove(date)
        }
        val candidates = drafts.flatMap { (date, draft) -> draft.histories.keys.map { Triple(date, it, draft.pageAccess[it] ?: 0L) } }
            .sortedBy { it.third }
        for ((date, id) in candidates.map { it.first to it.second }) {
            if (loadedPageCount <= maxLoadedPages) break
            if (!protected(date, id)) { drafts[date]?.histories?.remove(id); drafts[date]?.nextOrders?.remove(id) }
        }
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
        selectedPages[activeDate] = page.metadata.id
        draft.pageAccess[page.metadata.id] = ++accessClock
        submit(page, null)
    }

    fun addStroke(tool: BrushTool, colorArgb: Long, width: Float, points: List<InkPoint>,
        date: LocalDate = activeDate, pageId: Uuid? = null): InkStroke? {
        val draft = drafts[date] ?: return null
        val page = draft.entry.pages.first { it.metadata.id == (pageId ?: draft.selected) }
        val order = draft.nextOrders[page.metadata.id] ?: 0L
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
        val entryMetadata = if (draft.submitted) draft.entry.metadata.revised(at) else draft.entry.metadata.copy(updatedAt = at)
        val pageMetadata = if (page.metadata.id in draft.submittedPages) page.metadata.revised(at) else page.metadata.copy(updatedAt = at)
        draft.entry = draft.entry.copy(metadata = entryMetadata,
            pages = draft.entry.pages.map { if (it.metadata.id == page.metadata.id) it.copy(metadata = pageMetadata) else it })
        draft.accessed = ++accessClock
        draft.pageAccess[page.metadata.id] = ++accessClock
        draft.submitted = true
        draft.submittedPages.add(page.metadata.id)
        pending.addLast(PageEdit(date, page, change?.added ?: emptyList(),
            change?.removed?.map { it.metadata.id }?.toSet() ?: emptySet()))
        signal.trySend(Unit)
        trim()
        publish()
    }
    private fun publish() {
        val draft = drafts[activeDate]
        val history = draft?.histories?.get(draft.selected)
        mutableState.value = EditorState(activeDate, if (readError == null) draft?.entry else null, draft?.selected,
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
