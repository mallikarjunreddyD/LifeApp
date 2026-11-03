package com.myjournal.domain

import kotlinx.datetime.LocalDate
import kotlin.test.*

class MonthDomainTest {
    @Test fun correctLengthsMondayAlignmentAndFiveSixRows() {
        assertEquals(28, JournalMonth(2026, 2).days)
        assertEquals(29, JournalMonth(2024, 2).days)
        assertEquals(28, JournalMonth(2100, 2).days)
        assertEquals(29, JournalMonth(2000, 2).days)
        assertEquals(30, JournalMonth(2026, 11).days)
        assertEquals(31, JournalMonth(2026, 12).days)
        assertEquals(6, JournalMonth(2026, 11).mondayOffset)
        assertEquals(0, JournalMonth(2026, 6).mondayOffset)
        assertEquals(6, JournalMonth(2026, 11).weeks)
        assertEquals(5, JournalMonth(2026, 12).weeks)
        assertEquals(5, JournalMonth(2021, 2).weeks) // Intentional minimum of five writing rows.
    }
    @Test fun yearTransitionsAndCanonicalMonthEncoding() {
        assertEquals(JournalMonth(2027, 1), JournalMonth(2026, 12).next())
        assertEquals(JournalMonth(2025, 12), JournalMonth(2026, 1).previous())
        assertEquals("2026-02", JournalMonth(2026, 2).toString())
        assertEquals(JournalMonth(2026, 2), JournalMonth.parse("2026-02"))
    }
    @Test fun everyDateRoundTripsThroughCanonicalCellsAndViewportTransforms() {
        for (month in listOf(JournalMonth(2026, 11), JournalMonth(2026, 12), JournalMonth(2024, 2))) {
            val layout = CalendarLayout(month)
            for (day in 1..month.days) {
                val cell = layout.cell(day)
                assertEquals(month.date(day), layout.dateAt(cell.left + 110, cell.top + 100))
                assertEquals(month.date(day), layout.dateNumberAt(cell.left + 20, cell.top + 20))
                assertNull(layout.dateNumberAt(cell.left + 150, cell.top + 100))
                for (transform in listOf(PageTransform(0.4f, 10f, -20f), PageTransform(2.1f, -700f, -300f))) {
                    val screen = transform.toScreen(cell.left + 20, cell.top + 20)
                    val logical = transform.toPage(screen.first, screen.second)
                    assertEquals(month.date(day), layout.dateNumberAt(logical.first, logical.second))
                }
            }
        }
    }
    @Test fun blankCellsAndUnsupportedLayoutsAreNotReinterpreted() {
        val layout = CalendarLayout(JournalMonth(2026, 11))
        assertNull(layout.dateAt(10f, 100f)) // Monday before Sunday November 1.
        assertNull(layout.dateAt(100f, 20f)) // Weekday heading.
        assertNull(layout.dateAt(Float.NaN, 100f))
        assertFailsWith<IllegalArgumentException> { layout.copy(version = 2) }
        assertFailsWith<IllegalArgumentException> { layout.copy(rows = 5) }
        assertFailsWith<IllegalArgumentException> { layout.copy(width = 1000f) }
    }
    @Test fun typedNavigationPreservesExactOriginMonthAndDate() {
        val month = JournalMonth(2026, 11)
        val origin = AppRoute(AppSection.HABITS, month = month)
        val navigation = AppNavigator(listOf(origin))
        navigation.push(AppRoute(AppSection.JOURNAL, date = month.date(12)))
        assertEquals(LocalDate(2026, 11, 12), navigation.current.date)
        navigation.push(AppRoute(AppSection.HABITS, month = JournalMonth.from(navigation.current.date!!)))
        assertTrue(navigation.back()); assertEquals(month.date(12), navigation.current.date)
        assertTrue(navigation.back()); assertEquals(origin, navigation.current)
        assertFalse(navigation.back())
        val planner = AppRoute(AppSection.PLANNER, month = month)
        assertEquals(planner, AppRoute.decode(planner.encode()))
        navigation.select(planner); navigation.push(AppRoute(AppSection.JOURNAL, date = month.date(20)))
        navigation.back(); assertEquals(planner, navigation.current)
    }
    @Test fun habitValidationAndEntireMonthDenominator() {
        val month = JournalMonth(2026, 11)
        val meta = newRecordMetadata()
        val habit = HabitDefinition(newRecordMetadata(), meta.id, "Read", 0, target = 20)
        val completions = (1..18).map { HabitCompletion(newRecordMetadata(), habit.metadata.id, month.date(it), true) }
        val ledger = MonthlyHabitLedger(meta, month, listOf(habit), completions)
        assertEquals(18, ledger.count(habit.metadata.id))
        assertEquals(60f, ledger.percentage(habit.metadata.id))
        assertFailsWith<IllegalArgumentException> { habit.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { ledger.copy(completions = completions + completions.first()) }
        assertFailsWith<IllegalArgumentException> { ledger.copy(habits = listOf(habit.copy(target = 31))) }
    }
}
