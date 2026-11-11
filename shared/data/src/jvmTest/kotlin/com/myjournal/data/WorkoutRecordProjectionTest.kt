package com.myjournal.data

import com.myjournal.domain.ExerciseDefinition
import com.myjournal.domain.RecordedSet
import com.myjournal.domain.WorkoutExerciseEntry
import com.myjournal.domain.WorkoutLog
import com.myjournal.domain.WorkoutRoutine
import com.myjournal.domain.WorkoutSession
import com.myjournal.domain.newRecordMetadata
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkoutRecordProjectionTest {
    private val exercise = ExerciseDefinition(newRecordMetadata(), "Pull Ups")
    private val routine = WorkoutRoutine(newRecordMetadata(), "Pull", listOf(exercise.metadata.id))
    private val session = newRecordMetadata()
    private val entry = newRecordMetadata()
    private val sets = (1..2).map { RecordedSet(newRecordMetadata(), entry.id, it, null, 8, completed = true) }
    private val workout = WorkoutSession(
        session, LocalDate(2026, 11, 9), session.createdAt, session.createdAt, 4320,
        completed = true, averageHeartRateBpm = 136, durationManuallyEntered = true,
        entries = listOf(WorkoutExerciseEntry(entry, session.id, exercise.metadata.id, 0, sets = sets)),
    )
    private val log = WorkoutLog(listOf(exercise), listOf(routine), listOf(workout))

    @Test fun initialProjectionRoundTripsExistingIdentitiesAndMeasurements() {
        val records = WorkoutRecordProjection.flatten(log)
        assertEquals(log, WorkoutRecordProjection.assemble(records.reversed()))
        assertFailsWith<IllegalArgumentException> {
            WorkoutRecordProjection.assemble(records.filterNot { it.id == entry.id })
        }
    }

    @Test fun independentSetEditsDoNotReplaceTheSessionOrEachOther() {
        val first = WorkoutRecordProjection.diff(log, changedSet(0, 10))
        val second = WorkoutRecordProjection.diff(log, changedSet(1, 12))
        assertEquals(listOf(sets[0].metadata.id), first.puts.map { it.id })
        assertEquals(listOf(sets[1].metadata.id), second.puts.map { it.id })
        val records = WorkoutRecordProjection.flatten(log).associateBy { it.id }.toMutableMap()
        for (record in first.puts + second.puts) records[record.id] = record
        val merged = WorkoutRecordProjection.assemble(records.values)
        assertEquals(listOf(10, 12), merged.sessions.single().entries.single().sets.map { it.repetitions })
        assertEquals(136, merged.sessions.single().averageHeartRateBpm)
        assertEquals(session, merged.sessions.single().metadata)
    }

    @Test fun deletionListsDescendantsBeforeTheirParents() {
        val delta = WorkoutRecordProjection.diff(log, log.copy(sessions = emptyList()))
        assertEquals(emptyList(), delta.puts)
        assertEquals(listOf(sets[1].metadata.id, sets[0].metadata.id, entry.id, session.id), delta.removals.map { it.id })
    }

    private fun changedSet(index: Int, repetitions: Int): WorkoutLog {
        val updated = sets.mapIndexed { position, set ->
            if (position == index) set.copy(metadata = set.metadata.revised(set.metadata.updatedAt), repetitions = repetitions)
            else set
        }
        val parent = workout.entries.single().copy(metadata = entry.revised(entry.updatedAt), sets = updated)
        return log.copy(sessions = listOf(workout.copy(metadata = session.revised(session.updatedAt), entries = listOf(parent))))
    }
}
