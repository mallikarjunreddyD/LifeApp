package com.myjournal.domain

import kotlin.time.Instant

/** Merge checkbox-only branches when they changed disjoint habit/date cells. */
fun mergeIndependentHabitCompletions(
    base: MonthlyHabitLedger, current: MonthlyHabitLedger, incoming: MonthlyHabitLedger, now: Instant,
): MonthlyHabitLedger? {
    if (listOf(current, incoming).any {
            it.metadata.id != base.metadata.id || it.metadata.createdAt != base.metadata.createdAt ||
                it.month != base.month || it.habits != base.habits
        }) return null

    fun MonthlyHabitLedger.cells() = completions.associateBy { it.habitId to it.date }
    val original = base.cells()
    val latest = current.cells()
    val proposed = incoming.cells()
    val latestChanges = latest.filter { (key, value) -> original[key] != value }
    val proposedChanges = proposed.filter { (key, value) -> original[key] != value }
    if (!latest.keys.containsAll(original.keys) || !proposed.keys.containsAll(original.keys) ||
        latestChanges.isEmpty() || proposedChanges.isEmpty() ||
        proposedChanges.any { (key, value) -> key in latestChanges && latestChanges[key] != value }
    ) return null

    val merged = (latest + proposedChanges).values.toList()
    if (merged.map { it.metadata.id }.distinct().size != merged.size) return null
    val newest = if (current.metadata.revision >= incoming.metadata.revision) current.metadata else incoming.metadata
    if (newest.revision == Long.MAX_VALUE) return null
    return current.copy(
        metadata = newest.revised(maxOf(now, current.metadata.updatedAt, incoming.metadata.updatedAt)),
        completions = merged,
    )
}
