package com.myjournal.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Entity(tableName = "local_workout_records")
data class WorkoutRow(@PrimaryKey val id: String, val kind: String, val payload: String)

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM local_workout_records ORDER BY id")
    fun observe(): kotlinx.coroutines.flow.Flow<List<WorkoutRow>>

    @Query("SELECT * FROM local_workout_records ORDER BY id")
    suspend fun all(): List<WorkoutRow>

    @Upsert
    suspend fun save(row: WorkoutRow)

    @Query("DELETE FROM local_workout_records")
    suspend fun clear()
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE local_workout_records (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL)",
        )
    }
}

class RoomWorkoutRepository(private val database: FoundationDatabase) : WorkoutRepository {
    private val dao = database.workouts()

    override fun observe() = dao.observe().map(::decodeRows)

    override suspend fun saveExercise(exercise: ExerciseDefinition) = write {
        val log = capture()
        validateRevision(log.exercises.find { it.metadata.id == exercise.metadata.id }?.metadata, exercise.metadata)
        save(log, exercise.row(), log.copy(exercises = log.exercises.filterNot { it.metadata.id == exercise.metadata.id } + exercise))
    }

    override suspend fun saveRoutine(routine: WorkoutRoutine) = write {
        val log = capture()
        validateRevision(log.routines.find { it.metadata.id == routine.metadata.id }?.metadata, routine.metadata)
        save(log, routine.row(), log.copy(routines = log.routines.filterNot { it.metadata.id == routine.metadata.id } + routine))
    }

    override suspend fun saveSession(session: WorkoutSession, previousRevision: Long?) = write {
        val log = capture()
        val existing = log.sessions.find { it.metadata.id == session.metadata.id }
        check(existing?.metadata?.revision == previousRevision) { "This workout changed. Reopen it before saving." }
        validateRevision(existing?.metadata, session.metadata)
        save(log, session.row(), log.copy(sessions = log.sessions.filterNot { it.metadata.id == session.metadata.id } + session))
    }

    private suspend fun save(before: WorkoutLog, row: WorkoutRow, after: WorkoutLog) {
        require(before.records.none { it.id.toString() == row.id } || before.rootKind(row.id) == row.kind) {
            "Workout identity already belongs to another record"
        }
        after.validate()
        dao.save(row)
    }

    internal suspend fun capture() = decodeRows(dao.all())

    internal suspend fun restore(log: WorkoutLog) {
        log.validate()
        dao.clear()
        for (exercise in log.exercises) dao.save(exercise.row())
        for (routine in log.routines) dao.save(routine.row())
        for (session in log.sessions) dao.save(session.row())
    }

    private suspend fun write(block: suspend () -> Unit) = database.useWriterConnection { connection ->
        connection.immediateTransaction { block() }
    }

    private fun decodeRows(rows: List<WorkoutRow>): WorkoutLog {
        val exercises = mutableListOf<ExerciseDefinition>()
        val routines = mutableListOf<WorkoutRoutine>()
        val sessions = mutableListOf<WorkoutSession>()
        for (row in rows) {
            val value = Json.parseToJsonElement(row.payload).jsonObject
            val id = when (row.kind) {
                "exercise" -> WorkoutJson.exercise(value).also(exercises::add).metadata.id
                "routine" -> WorkoutJson.routine(value).also(routines::add).metadata.id
                "session" -> WorkoutJson.session(value).also(sessions::add).metadata.id
                else -> error("Unknown workout record type")
            }
            check(id.toString() == row.id) { "Workout identity mismatch" }
        }
        return WorkoutLog(exercises, routines, sessions).also { it.validate() }
    }

    private fun validateRevision(old: RecordMetadata?, revised: RecordMetadata) {
        require(revised.schemaVersion == 1)
        if (old == null) require(revised.revision == 1L)
        else check(old.revised(revised.updatedAt) == revised) { "Workout record changed before this save." }
    }

    private fun WorkoutLog.rootKind(id: String) = when {
        exercises.any { it.metadata.id.toString() == id } -> "exercise"
        routines.any { it.metadata.id.toString() == id } -> "routine"
        sessions.any { it.metadata.id.toString() == id } -> "session"
        else -> null
    }

    private fun ExerciseDefinition.row() = WorkoutRow(metadata.id.toString(), "exercise", workoutJson().toString())
    private fun WorkoutRoutine.row() = WorkoutRow(metadata.id.toString(), "routine", workoutJson().toString())
    private fun WorkoutSession.row() = WorkoutRow(metadata.id.toString(), "session", workoutJson().toString())
}
