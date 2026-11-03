package com.myjournal.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class PlannerState(val month: JournalMonth, val planner: MonthlyPlanner? = null,
    val strokes: List<InkStroke> = emptyList(), val loading: Boolean = true,
    val pending: Int = 0, val error: String? = null, val canUndo: Boolean = false, val canRedo: Boolean = false)

class PlannerEditor(private val repository: MonthlyPlannerRepository, private val scope: CoroutineScope, initialMonth: JournalMonth) {
    private class Draft(var planner: MonthlyPlanner, val history: StrokeHistory, var accessed: Long, var submitted: Boolean) {
        var nextOrder = (history.current.maxOfOrNull { it.order } ?: -1) + 1
    }
    private val drafts = mutableMapOf<JournalMonth, Draft>()
    private val retained = mutableMapOf<JournalMonth, Int>()
    private var clock = 0L
    private var active = initialMonth
    private var loading = true
    private var readError: String? = null
    private var generation = 0L
    private val mutable = MutableStateFlow(PlannerState(initialMonth))
    val state = mutable.asStateFlow()
    private val writes = SaveQueue<PlannerEdit>(scope, repository::apply) { trim(); publish() }
    fun retain(month: JournalMonth) { retained[month] = (retained[month] ?: 0) + 1 }
    fun release(month: JournalMonth) { if ((retained[month] ?: 1) <= 1) retained.remove(month) else retained[month] = retained.getValue(month) - 1; trim() }
    fun open(month: JournalMonth) {
        active = month; readError = null
        val token = ++generation
        drafts[month]?.let { it.accessed = ++clock; loading = false; trim(); publish(); return }
        loading = true; publish()
        scope.launch {
            try {
                val stored = repository.find(month)
                val planner = stored ?: MonthlyPlanner(newRecordMetadata(), CalendarLayout(month))
                val strokes = repository.loadStrokes(planner.metadata.id)
                drafts.getOrPut(month) { Draft(planner, StrokeHistory(strokes), ++clock, stored != null) }
                if (generation == token) { loading = false; publish() }
                trim()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == token) { loading = false; readError = "Cannot load planner. ${failure.message}"; publish() }
            }
        }
    }
    fun addStroke(tool: BrushTool, color: Long, width: Float, points: List<InkPoint>, month: JournalMonth = active): InkStroke? {
        val draft = drafts[month] ?: return null
        val stroke = InkStroke(newRecordMetadata(), draft.planner.metadata.id, draft.nextOrder++, tool, color, width, points.toList())
        // Validate cell ownership before changing visible history.
        PlannerEdit(draft.planner, listOf(stroke))
        submit(month, draft.history.add(stroke))
        return stroke
    }
    fun erase(ids: Set<Uuid>, month: JournalMonth = active) { drafts[month]?.history?.erase(ids)?.let { submit(month, it) } }
    fun undo() { drafts[active]?.history?.undo()?.let { submit(active, it) } }
    fun redo() { drafts[active]?.history?.redo()?.let { submit(active, it) } }
    fun retry() { if (readError != null) open(active); writes.retry() }
    private fun submit(month: JournalMonth, change: StrokeChange) {
        val draft = drafts.getValue(month)
        draft.accessed = ++clock
        val at = maxOf(Clock.System.now(), draft.planner.metadata.updatedAt)
        draft.planner = draft.planner.copy(metadata = if (draft.submitted) draft.planner.metadata.revised(at) else draft.planner.metadata.copy(updatedAt = at))
        draft.submitted = true
        writes.add(PlannerEdit(draft.planner, change.added, change.removed.map { it.metadata.id }.toSet()))
    }
    private fun trim() {
        for ((month, _) in drafts.entries.sortedBy { it.value.accessed }) {
            if (drafts.size <= 4) break
            if (month != active && month !in retained && !writes.any { it.planner.month == month }) drafts.remove(month)
        }
    }
    private fun publish() {
        val draft = drafts[active]
        mutable.value = PlannerState(active, if (readError == null) draft?.planner else null,
            draft?.history?.current ?: emptyList(), loading, writes.count, readError ?: writes.error,
            draft?.history?.canUndo == true, draft?.history?.canRedo == true)
    }
}
