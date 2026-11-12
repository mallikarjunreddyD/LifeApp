package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myjournal.domain.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*

class RoomWorkoutLogbookTest {
    @Test fun deletionAndRestoreKeepIdentityAndExcludeTombstonesFromReports() = runTest {
        val database = Room.inMemoryDatabaseBuilder<FoundationDatabase>()
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        val archive = File.createTempFile("lifeapp-logbook", ".zip")
        try {
            val repository = RoomWorkoutRepository(database)
            val exercise = ExerciseDefinition(newRecordMetadata(), "Bench press", equipment = "Barbell")
            repository.saveExercise(exercise)
            val routine = WorkoutRoutine(newRecordMetadata(), "Push", listOf(exercise.metadata.id), listOf(2))
            repository.saveRoutine(routine)
            val metadata = newRecordMetadata()
            val entry = WorkoutExerciseEntry(newRecordMetadata(), metadata.id, exercise.metadata.id, 0, remarks = "Slow descent")
            val set = RecordedSet(newRecordMetadata(), entry.metadata.id, 1, WorkoutWeight(35.5, WeightUnit.KG), 7)
            val workout = WorkoutSession(metadata, LocalDate(2026, 11, 12), null, null, 4320,
                entries = listOf(entry.copy(sets = listOf(set))), averageHeartRateBpm = 136,
                durationManuallyEntered = true, logged = true, routineId = routine.metadata.id, routineName = routine.name)
            val other = workout.copy(metadata = newRecordMetadata(), entries = emptyList())
            repository.saveDraft(WorkoutDraft(workout, null))
            assertEquals(workout, repository.loadDraft()?.session)
            assertTrue(repository.observe().first().sessions.isEmpty())
            repository.saveSession(workout, null)
            repository.saveSession(other, null)
            repository.saveRoutine(routine.copy(metadata = routine.metadata.revised(routine.metadata.updatedAt), name = "Renamed push"))
            repository.saveExercise(exercise.copy(metadata = exercise.metadata.revised(exercise.metadata.updatedAt), archived = true))
            repository.setSessionDeleted(metadata.id, true)
            repository.setSessionDeleted(metadata.id, true)
            val log = repository.observe().first()
            assertEquals(listOf(other), log.monthlyReport(JournalMonth(2026, 11)).sessions)
            val deleted = log.sessions.single { it.metadata.id == metadata.id }
            assertTrue(deleted.deleted)
            assertEquals(2L, deleted.metadata.revision)
            assertEquals("Push", deleted.routineName)
            assertEquals(listOf(set), deleted.entries.single().sets)
            assertFailsWith<IllegalStateException> {
                repository.saveSession(workout.copy(metadata = metadata.revised(metadata.updatedAt)), 1)
            }
            val snapshot = JournalSnapshot(workoutLog = log)
            BackupArchive.write(archive, snapshot)
            val restored = BackupArchive.read(archive).snapshot
            assertEquals(9, restored.dataSchemaVersion)
            assertEquals(log.canonical(), restored.workoutLog.canonical())
            repository.setSessionDeleted(metadata.id, false)
            val restoredSession = repository.observe().first().sessions.single { it.metadata.id == metadata.id }
            assertFalse(restoredSession.deleted)
            assertEquals(3L, restoredSession.metadata.revision)
            assertEquals("Slow descent", restoredSession.entries.single().remarks)
        } finally {
            database.close()
            archive.delete()
        }
    }
}
