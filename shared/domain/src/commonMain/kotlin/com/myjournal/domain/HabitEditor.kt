package com.myjournal.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class HabitState(val month: JournalMonth, val ledger: MonthlyHabitLedger? = null,
    val previous: MonthlyHabitLedger? = null, val loading: Boolean = true, val pending: Int = 0, val error: String? = null)

class HabitEditor(private val repository: MonthlyHabitRepository, private val scope: CoroutineScope, initialMonth: JournalMonth) {
    private val ledgers = linkedMapOf<JournalMonth, MonthlyHabitLedger>()
    private var active = initialMonth
    private var previous: MonthlyHabitLedger? = null
    private var loading = true
    private var readError: String? = null
    private var generation = 0L
    private val mutable = MutableStateFlow(HabitState(initialMonth))
    val state = mutable.asStateFlow()
    private val writes = SaveQueue<HabitLedgerEdit>(scope, repository::apply) { trim(); publish() }
    fun open(month: JournalMonth) {
        active = month; readError = null; previous = null
        val token = ++generation
        ledgers[month]?.let { loading = false; touch(month); publish(); return }
        loading = true; publish()
        scope.launch {
            try {
                val stored = repository.find(month)
                if (stored != null) ledgers.getOrPut(month) { stored }
                val old = if (stored == null) repository.previousConfigured(month) else null
                val cached = ledgers.values.filter { it.month < month }.maxByOrNull { it.month }
                if (token == generation) {
                    val nearest = listOfNotNull(old?.month, cached?.month).maxOrNull()
                    previous = cached?.takeIf { it.month == nearest } ?: old
                    loading = false; publish()
                }
                trim()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) { loading = false; readError = "Cannot load habits. ${failure.message}"; publish() }
            }
        }
    }
    fun createBlank() {
        if (loading || readError != null || active in ledgers) return
        val ledger = MonthlyHabitLedger(newRecordMetadata(), active)
        ledgers[active] = ledger
        writes.add(HabitLedgerEdit(ledger, emptyList()))
    }
    fun copyPrevious() {
        if (loading || readError != null || active in ledgers) return
        val source = previous ?: return
        val metadata = newRecordMetadata()
        val habits = source.activeHabits.mapIndexed { index, old ->
            old.copy(metadata = newRecordMetadata(), ledgerId = metadata.id, order = index,
                target = old.target?.coerceAtMost(active.days), archived = false)
        }
        val ledger = MonthlyHabitLedger(metadata, active, habits)
        ledgers[active] = ledger
        writes.add(HabitLedgerEdit(ledger, habits))
    }
    fun saveHabit(id: Uuid?, name: String, category: String?, color: Long?, target: Int?) {
        val ledger = ledgers[active] ?: return
        require(target == null || target in 1..active.days)
        val old = ledger.habits.firstOrNull { it.metadata.id == id }
        val at = maxOf(Clock.System.now(), old?.metadata?.updatedAt ?: ledger.metadata.updatedAt)
        val definition = HabitDefinition(old?.metadata?.revised(at) ?: newRecordMetadata(), ledger.metadata.id,
            name.trim(), old?.order ?: ledger.habits.size, category?.trim()?.takeIf { it.isNotEmpty() }, color, target, old?.archived ?: false)
        val habits = if (old == null) ledger.habits + definition else ledger.habits.map { if (it.metadata.id == id) definition else it }
        configure(ledger, habits)
    }
    fun archive(id: Uuid, archived: Boolean = true) {
        val ledger = ledgers[active] ?: return
        configure(ledger, ledger.habits.map { if (it.metadata.id == id)
            it.copy(archived = archived, metadata = it.metadata.revised(maxOf(Clock.System.now(), it.metadata.updatedAt))) else it })
    }
    fun move(id: Uuid, delta: Int) {
        val ledger = ledgers[active] ?: return
        val activeHabits = ledger.activeHabits.toMutableList()
        val index = activeHabits.indexOfFirst { it.metadata.id == id }
        if (index < 0 || index + delta !in activeHabits.indices) return
        val moved = activeHabits.removeAt(index); activeHabits.add(index + delta, moved)
        val ordered = activeHabits + ledger.habits.filter { it.archived }.sortedBy { it.order }
        configure(ledger, ordered.mapIndexed { order, old -> if (order == old.order) old else
            old.copy(order = order, metadata = old.metadata.revised(maxOf(Clock.System.now(), old.metadata.updatedAt))) })
    }
    fun toggle(id: Uuid, date: LocalDate) {
        val month = JournalMonth.from(date)
        val ledger = ledgers[month] ?: return
        if (ledger.habits.none { it.metadata.id == id && !it.archived }) return
        val old = ledger.completions.firstOrNull { it.habitId == id && it.date == date }
        val at = maxOf(Clock.System.now(), old?.metadata?.updatedAt ?: ledger.metadata.updatedAt)
        val completion = HabitCompletion(old?.metadata?.revised(at) ?: newRecordMetadata(), id, date, !(old?.checked ?: false))
        val completions = ledger.completions.filterNot { it.habitId == id && it.date == date } + completion
        val updated = ledger.copy(metadata = ledger.metadata.revised(maxOf(at, ledger.metadata.updatedAt)), completions = completions)
        ledgers[month] = updated
        writes.add(HabitLedgerEdit(updated, completions = listOf(completion)))
    }
    fun retry() { if (readError != null) open(active); writes.retry() }
    private fun configure(ledger: MonthlyHabitLedger, definitions: List<HabitDefinition>) {
        val updated = ledger.copy(metadata = ledger.metadata.revised(maxOf(Clock.System.now(), ledger.metadata.updatedAt)), habits = definitions)
        ledgers[active] = updated
        writes.add(HabitLedgerEdit(updated, definitions))
    }
    private fun touch(month: JournalMonth) { ledgers.remove(month)?.let { ledgers[month] = it } }
    private fun trim() {
        for (month in ledgers.keys.toList()) {
            if (ledgers.size <= 6) break
            if (month != active && !writes.any { it.ledger.month == month }) ledgers.remove(month)
        }
    }
    private fun publish() { mutable.value = HabitState(active, if (readError == null) ledgers[active] else null,
        previous, loading, writes.count, readError ?: writes.error) }
}
