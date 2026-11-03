package com.myjournal.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MonthEditorTest {
    private val month = JournalMonth(2026, 11)
    private class Habits : MonthlyHabitRepository {
        val ledgers = mutableMapOf<JournalMonth, MonthlyHabitLedger>()
        val edits = mutableListOf<HabitLedgerEdit>()
        var fail = false
        override suspend fun find(month: JournalMonth) = ledgers[month]
        override suspend fun previousConfigured(month: JournalMonth) = ledgers.values.filter { it.month < month }.maxByOrNull { it.month }
        override suspend fun apply(edit: HabitLedgerEdit) {
            if (fail) error("storage full")
            edits.add(edit); ledgers[edit.ledger.month] = edit.ledger
        }
    }
    @Test fun copyIsExplicitIndependentAndNeverCopiesCompletions() = runTest {
        val store = Habits(); val editor = HabitEditor(store, backgroundScope, month)
        editor.open(month); runCurrent(); assertTrue(store.ledgers.isEmpty())
        editor.createBlank(); editor.saveHabit(null, "Reading", "Mind", 0xFF2859A8, 20); runCurrent()
        val original = editor.state.value.ledger!!.activeHabits.single()
        editor.toggle(original.metadata.id, month.date(12)); runCurrent()
        editor.open(month.next()); runCurrent()
        assertNull(editor.state.value.ledger)
        assertFalse(month.next() in store.ledgers)
        editor.copyPrevious(); runCurrent()
        val copied = editor.state.value.ledger!!
        assertEquals(listOf("Reading"), copied.activeHabits.map { it.name })
        assertTrue(copied.completions.isEmpty())
        assertNotEquals(original.metadata.id, copied.activeHabits.single().metadata.id)
        editor.saveHabit(copied.activeHabits.single().metadata.id, "Read Books", "Mind", null, 15); runCurrent()
        editor.open(month); runCurrent()
        assertEquals("Reading", editor.state.value.ledger!!.activeHabits.single().name)
        assertTrue(editor.state.value.ledger!!.checked(original.metadata.id, 12))
    }
    @Test fun rapidTogglesUseCurrentOptimisticStateAndRetryIsOrdered() = runTest {
        val store = Habits(); val editor = HabitEditor(store, backgroundScope, month)
        editor.open(month); runCurrent(); editor.createBlank(); editor.saveHabit(null, "Exercise", null, null, null); runCurrent()
        val id = editor.state.value.ledger!!.activeHabits.single().metadata.id
        store.fail = true
        repeat(21) { editor.toggle(id, month.date(12)) }; runCurrent()
        assertTrue(editor.state.value.ledger!!.checked(id, 12))
        assertEquals(1, editor.state.value.ledger!!.completions.size)
        assertEquals(21, editor.state.value.pending)
        assertNotNull(editor.state.value.error)
        editor.open(month.next()); runCurrent(); store.fail = false; editor.retry(); runCurrent()
        assertEquals(0, editor.state.value.pending)
        assertTrue(store.ledgers.getValue(month).checked(id, 12))
        assertEquals(1, store.ledgers.getValue(month).completions.size)
    }
    @Test fun renameReorderArchiveAndRestoreKeepHistoryBoundToStableIds() = runTest {
        val store = Habits(); val editor = HabitEditor(store, backgroundScope, month)
        editor.open(month); runCurrent(); editor.createBlank()
        editor.saveHabit(null, "Exercise", null, null, null); editor.saveHabit(null, "Reading", null, null, null); runCurrent()
        val a = editor.state.value.ledger!!.activeHabits[0]; val b = editor.state.value.ledger!!.activeHabits[1]
        editor.toggle(b.metadata.id, month.date(12))
        editor.saveHabit(b.metadata.id, "Read Books", "Mind", 0xFF2859A8, 10)
        editor.move(b.metadata.id, -1); editor.archive(b.metadata.id); runCurrent()
        assertEquals(listOf(a.metadata.id), editor.state.value.ledger!!.activeHabits.map { it.metadata.id })
        assertEquals(1, editor.state.value.ledger!!.count(b.metadata.id))
        editor.archive(b.metadata.id, false); runCurrent()
        assertEquals("Read Books", editor.state.value.ledger!!.activeHabits.first().name)
        assertTrue(editor.state.value.ledger!!.checked(b.metadata.id, 12))
    }
    @Test fun plannerFailureKeepsExactInkAndOriginalMonthAcrossNavigation() = runTest {
        val edits = mutableListOf<PlannerEdit>(); var fail = true
        val repository = object : MonthlyPlannerRepository {
            override suspend fun find(month: JournalMonth): MonthlyPlanner? = null
            override suspend fun loadStrokes(surfaceId: Uuid) = emptyList<InkStroke>()
            override suspend fun apply(edit: PlannerEdit) { if (fail) error("disk full"); edits.add(edit) }
        }
        val editor = PlannerEditor(repository, backgroundScope, month)
        editor.open(month); runCurrent()
        val rect = CalendarLayout(month).cell(12)
        val stroke = editor.addStroke(BrushTool.PEN, 0xFF000000, 3f, listOf(InkPoint(rect.left + 30, rect.top + 60, 0)))!!
        runCurrent(); assertNotNull(editor.state.value.error)
        editor.open(month.next()); runCurrent()
        fail = false; editor.retry(); runCurrent()
        assertEquals(month, edits.single().planner.month)
        editor.open(month); runCurrent(); editor.erase(setOf(stroke.metadata.id)); editor.undo(); runCurrent()
        assertEquals(listOf(stroke), editor.state.value.strokes)
        assertEquals(0, editor.state.value.pending)
    }
}
