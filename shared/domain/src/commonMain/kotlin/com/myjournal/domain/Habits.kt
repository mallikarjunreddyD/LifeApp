package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

data class HabitDefinition(
    val metadata: RecordMetadata,
    val ledgerId: Uuid,
    val name: String,
    val order: Int,
    val category: String? = null,
    val colorArgb: Long? = null,
    val target: Int? = null,
    val archived: Boolean = false,
) {
    init {
        require(name.isNotBlank() && name == name.trim() && name.length <= 80 && order >= 0)
        require(category == null || category.isNotBlank() && category.length <= 80)
        require(colorArgb == null || colorArgb in 0..0xFFFFFFFFL)
        require(target == null || target in 1..31)
    }
}

data class HabitCompletion(
    val metadata: RecordMetadata,
    val habitId: Uuid,
    val date: LocalDate,
    val checked: Boolean,
)

data class MonthlyHabitLedger(
    val metadata: RecordMetadata,
    val month: JournalMonth,
    val habits: List<HabitDefinition> = emptyList(),
    val completions: List<HabitCompletion> = emptyList(),
) {
    init {
        require(habits.all { it.ledgerId == metadata.id && (it.target == null || it.target <= month.days) })
        require(habits.map { it.metadata.id }.distinct().size == habits.size)
        require(habits.map { it.order }.distinct().size == habits.size)
        val ids = habits.map { it.metadata.id }.toSet()
        require(completions.all { it.habitId in ids && JournalMonth.from(it.date) == month })
        require(completions.map { it.habitId to it.date }.distinct().size == completions.size)
        require(completions.map { it.metadata.id }.distinct().size == completions.size)
    }
    val activeHabits get() = habits.filterNot { it.archived }.sortedBy { it.order }
    fun checked(habitId: Uuid, day: Int) = completions.any { it.habitId == habitId && it.date.day == day && it.checked }
    fun count(habitId: Uuid) = completions.count { it.habitId == habitId && it.checked }
    fun percentage(habitId: Uuid) = count(habitId) * 100f / month.days
}

data class HabitLedgerEdit(
    val ledger: MonthlyHabitLedger,
    // null means leave definitions untouched; otherwise this is the full ordered configuration.
    val definitions: List<HabitDefinition>? = null,
    val completions: List<HabitCompletion> = emptyList(),
)

interface MonthlyHabitRepository {
    suspend fun find(month: JournalMonth): MonthlyHabitLedger?
    suspend fun previousConfigured(month: JournalMonth): MonthlyHabitLedger?
    suspend fun apply(edit: HabitLedgerEdit)
}
