package com.myjournal.domain

data class WorkoutMonthlyReport(val month: JournalMonth, val sessions: List<WorkoutSession>) {
    val durations get() = sessions.mapNotNull(WorkoutSession::durationSeconds)
    val heartRates get() = sessions.mapNotNull(WorkoutSession::averageHeartRateBpm)
    val calories get() = sessions.mapNotNull(WorkoutSession::caloriesKcal)
    val totalDurationSeconds get() = durations.takeIf { it.isNotEmpty() }?.sumOf(Int::toLong)
    val averageDurationSeconds get() = durations.takeIf { it.isNotEmpty() }?.average()
    val averageHeartRateBpm get() = heartRates.takeIf { it.isNotEmpty() }?.average()
    val totalCalories get() = calories.takeIf { it.isNotEmpty() }?.sumOf(Int::toLong)
}

fun WorkoutLog.monthlyReport(month: JournalMonth) = WorkoutMonthlyReport(
    month,
    sessions.filter { it.completed && JournalMonth.from(it.date) == month }
        .sortedWith(compareBy({ it.date }, { it.startedAt }, { it.metadata.id.toString() })),
)
