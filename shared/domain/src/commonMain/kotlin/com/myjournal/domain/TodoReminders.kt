package com.myjournal.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class TodoReminderPreferences(
    val enabled: Boolean = false,
    val morning: LocalTime = LocalTime(9, 0),
    val evening: LocalTime = LocalTime(19, 0),
)

fun nextTodoReminder(now: Instant, zone: TimeZone, time: LocalTime): Instant {
    val date = now.toLocalDateTime(zone).date
    val candidate = LocalDateTime(date, time).toInstant(zone)
    return if (candidate > now) candidate else LocalDateTime(date + DatePeriod(days = 1), time).toInstant(zone)
}

data class TodoReminderSummary(val pending: Int, val overdue: Int)

fun todoReminderSummary(items: List<Todo>, date: LocalDate): TodoReminderSummary? {
    val pending = items.filter { !it.deleted && it.completedAt == null }
    return if (pending.isEmpty()) null else TodoReminderSummary(pending.size, pending.count { it.deadline < date })
}
