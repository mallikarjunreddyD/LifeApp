package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TodoReminderTest {
    private val instant = Instant.parse("2026-11-09T03:29:00Z")
    private val today = LocalDate(2026, 11, 9)

    @Test fun remindersFollowLocalTimesAndChooseTheNextDayAfterTheSlot() {
        val zone = TimeZone.of("Asia/Kolkata")
        val defaults = TodoReminderPreferences()
        assertFalse(defaults.enabled)
        assertEquals(Instant.parse("2026-11-09T03:30:00Z"), nextTodoReminder(instant, zone, defaults.morning))
        assertEquals(Instant.parse("2026-11-09T13:30:00Z"), nextTodoReminder(instant, zone, defaults.evening))
        assertEquals(Instant.parse("2026-11-10T02:30:00Z"), nextTodoReminder(instant, zone, LocalTime(8, 0)))
        assertEquals(Instant.parse("2026-11-09T09:00:00Z"), nextTodoReminder(instant, TimeZone.UTC, defaults.morning))
    }

    @Test fun calendarDeadlinesAndNotificationsIgnoreDeletedAndCompletedTasks() {
        fun task(date: LocalDate, completed: Boolean = false, deleted: Boolean = false) =
            Todo(RecordMetadata(Uuid.random(), instant, instant), "Task", date, instant.takeIf { completed }, deleted)
        val overdue = task(LocalDate(2026, 11, 8))
        val due = task(today)
        val later = task(LocalDate(2026, 11, 10))
        val latest = task(LocalDate(2026, 11, 12))
        val done = task(today, completed = true)
        val deleted = task(today, deleted = true)
        val records = listOf(latest, due, deleted, later, done, overdue)
        val groups = groupTodos(records, today)
        assertEquals(listOf(overdue), groups[TodoGroup.OVERDUE])
        assertEquals(listOf(due), groups[TodoGroup.TODAY])
        assertEquals(listOf(later, latest), groups[TodoGroup.UPCOMING])
        assertEquals(listOf(done), groups[TodoGroup.COMPLETED])
        assertEquals(TodoReminderSummary(4, 1), todoReminderSummary(records, today))
        assertEquals(TodoReminderSummary(1, 0), todoReminderSummary(listOf(later), today))
        assertNull(todoReminderSummary(listOf(done, deleted), today))
    }
}
