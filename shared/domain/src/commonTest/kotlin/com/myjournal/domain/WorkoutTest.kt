package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WorkoutTest {
    private val start = Instant.parse("2026-11-09T23:45:00Z")
    private val end = Instant.parse("2026-11-10T00:15:00Z")
    private fun metadata(last: Int) = RecordMetadata(Uuid.parse("40000000-0000-4000-8000-${last.toString().padStart(12, '0')}"), start, start)

    @Test fun completedWorkoutKeepsCalendarDateAndExplicitUnitsAcrossUtcMidnight() {
        val exercise = ExerciseDefinition(metadata(1), "Squat", "Legs", "Barbell")
        val first = RecordedSet(metadata(2), metadata(3).id, 1, WorkoutWeight(20.0, WeightUnit.KG), 8, completed = true)
        val second = first.copy(metadata = metadata(4), number = 2, weight = WorkoutWeight(45.0, WeightUnit.LB), restSeconds = 60)
        val entry = WorkoutExerciseEntry(metadata(3), metadata(5).id, exercise.metadata.id, 0, 1, listOf(first, second))
        val workout = WorkoutSession(metadata(5), LocalDate(2026, 11, 9), start, end, 1_500,
            caloriesKcal = 125, completed = true, entries = listOf(entry))
        val archived = exercise.copy(metadata = exercise.metadata.revised(end), name = "Barbell squat", archived = true)
        workout.validateExerciseReferences(setOf(archived.metadata.id))
        assertEquals(exercise.metadata.id, archived.metadata.id)
        assertEquals(LocalDate(2026, 11, 9), workout.date)
        assertEquals(listOf(WeightUnit.KG, WeightUnit.LB), workout.entries.single().sets.map { it.weight?.unit })
        assertEquals(125, workout.caloriesKcal)
        assertFailsWith<IllegalArgumentException> { workout.validateExerciseReferences(emptySet()) }
        assertFailsWith<IllegalArgumentException> { entry.copy(sets = listOf(second, first)) }
        assertFailsWith<IllegalArgumentException> { entry.copy(sets = listOf(first.copy(entryId = metadata(9).id))) }
        assertFailsWith<IllegalArgumentException> { workout.copy(entries = listOf(entry.copy(sessionId = metadata(9).id))) }
        assertFailsWith<IllegalArgumentException> { workout.copy(durationSeconds = 1_801) }
        assertFailsWith<IllegalArgumentException> { workout.copy(caloriesKcal = -1) }
        assertFailsWith<IllegalArgumentException> { workout.copy(endedAt = null) }
    }

    @Test fun rejectsBlankExercisesInvalidWeightsAndEmptyCompletedSets() {
        assertFailsWith<IllegalArgumentException> { ExerciseDefinition(metadata(1), " ") }
        for (weight in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { WorkoutWeight(weight, WeightUnit.KG) }
        }
        assertFailsWith<IllegalArgumentException> { RecordedSet(metadata(1), metadata(2).id, 1, null, -1) }
        assertFailsWith<IllegalArgumentException> { RecordedSet(metadata(1), metadata(2).id, 1, null, 0, completed = true) }
    }

    @Test fun endpointAcceptsOnlyAnUnambiguousHttpsOrigin() {
        assertEquals("https://device.example.ts.net", ServerEndpoint.parse("https://DEVICE.example.ts.net:443/").origin)
        assertEquals("https://192.168.1.12:8443", ServerEndpoint.parse("https://192.168.1.12:8443").origin)
        for (bad in listOf("http://example.test", "https://user@example.test", "https://example.test/api",
            "https://example.test?x=y", "https://example.test#x", "https://example.test:0",
            "https://0x7f.0.0.1", "https://example.test\\@other.test", "https://example.test ")) {
            assertFailsWith<IllegalArgumentException> { ServerEndpoint.parse(bad) }
        }
    }
}
