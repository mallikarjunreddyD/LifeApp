package com.myjournal.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class WorkoutMonthlyReportTest {
    @Test fun localMonthAndMeasuredDenominatorsAreIndependentOfUtcAndMissingValues() {
        val midnight = Instant.parse("2026-11-01T00:05:00Z")
        val october = WorkoutSession(
            newRecordMetadata(), LocalDate(2026, 10, 31), midnight, midnight, 4320,
            completed = true, durationManuallyEntered = true, averageHeartRateBpm = 136,
        )
        val zero = october.copy(metadata = newRecordMetadata(), date = LocalDate(2026, 10, 1),
            durationSeconds = 0, caloriesKcal = 0, averageHeartRateBpm = null)
        val november = october.copy(metadata = newRecordMetadata(), date = LocalDate(2026, 11, 1))
        val draft = october.copy(metadata = newRecordMetadata(), completed = false)
        val log = WorkoutLog(sessions = listOf(october, zero, november, draft))
        val report = log.monthlyReport(JournalMonth(2026, 10))

        assertEquals(listOf(zero, october), report.sessions)
        assertEquals(4320L, report.totalDurationSeconds)
        assertEquals(2160.0, report.averageDurationSeconds)
        assertEquals(136.0, report.averageHeartRateBpm)
        assertEquals(1, report.heartRates.size)
        assertEquals(0L, report.totalCalories)
        val empty = log.monthlyReport(JournalMonth(2026, 9))
        assertNull(empty.totalCalories)
        assertNull(empty.totalDurationSeconds)
        assertNull(empty.averageHeartRateBpm)
    }
}
