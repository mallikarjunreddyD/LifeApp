package com.myjournal.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Entity(tableName = "local_workout_records")
data class WorkoutRow(@PrimaryKey val id: String, val kind: String, val payload: String)

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM local_workout_records ORDER BY id")
    fun observe(): kotlinx.coroutines.flow.Flow<List<WorkoutRow>>

    @Query("SELECT * FROM local_workout_records ORDER BY id")
    suspend fun all(): List<WorkoutRow>

    @Query("SELECT * FROM local_workout_records WHERE id = :id")
    suspend fun find(id: String): WorkoutRow?

    @Query("DELETE FROM local_workout_records WHERE id = :id")
    suspend fun remove(id: String)

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
        check(existing?.deleted != true && !session.deleted) { "Restore this workout before editing it." }
        validateRevision(existing?.metadata, session.metadata)
        save(log, session.row(), log.copy(sessions = log.sessions.filterNot { it.metadata.id == session.metadata.id } + session))
    }

    override suspend fun loadDraft(): WorkoutDraft? {
        val row = dao.find("draft") ?: return null
        val session = WorkoutJson.session(Json.parseToJsonElement(row.payload).jsonObject)
        val revision = row.kind.removePrefix("draft:").toLongOrNull()
        val saved = dao.find(session.metadata.id.toString())
        val savedRevision = saved?.let { WorkoutJson.session(Json.parseToJsonElement(it.payload).jsonObject).metadata.revision }
        return if (savedRevision == revision) WorkoutDraft(session, revision) else null
    }

    override suspend fun saveDraft(draft: WorkoutDraft?) {
        if (draft == null) dao.remove("draft")
        else dao.save(WorkoutRow("draft", "draft:${draft.previousRevision ?: ""}", draft.session.workoutJson().toString()))
    }

    override suspend fun spreadsheetHistory() = dao.find("history-view")?.payload == "table"

    override suspend fun setSpreadsheetHistory(enabled: Boolean) {
        dao.save(WorkoutRow("history-view", "preference", if (enabled) "table" else "cards"))
    }

    override suspend fun setSessionDeleted(id: Uuid, deleted: Boolean) = write {
        val session = capture().sessions.single { it.metadata.id == id }
        if (session.deleted != deleted) {
            val metadata = session.metadata.revised(maxOf(session.metadata.updatedAt, Clock.System.now()))
            dao.save(session.copy(metadata = metadata, deleted = deleted).row())
        }
    }

    private suspend fun save(before: WorkoutLog, row: WorkoutRow, after: WorkoutLog) {
        require(before.records.none { it.id.toString() == row.id } || before.rootKind(row.id) == row.kind) {
            "Workout identity already belongs to another record"
        }
        after.validate()
        dao.save(row)
    }

    internal suspend fun capture() = decodeRows(dao.all())

    suspend fun syncBinding(): WorkoutSyncBinding? = dao.find("workout-sync")?.let {
        WorkoutSyncBinding.parse(Json.parseToJsonElement(it.payload).jsonObject)
    }

    suspend fun bindSync(scope: SyncScope, origin: String, reviewed: WorkoutLog) = write {
        check(syncBinding() == null) { "Workouts are already connected." }
        check(capture().canonical() == reviewed.canonical()) { "Workouts changed. Review again." }
        saveBinding(WorkoutSyncBinding(scope, origin))
    }

    suspend fun syncRecords() = capture().syncRecords()

    suspend fun applySyncVersion(binding: WorkoutSyncBinding, received: WorkoutSyncVersion, expected: WorkoutSyncRecord?, download: Boolean): Boolean = write {
        val current = requireNotNull(syncBinding())
        val id = received.record.id
        check(current.scope == binding.scope && current.origin == binding.origin &&
            current.versions[id] == binding.versions[id]) { "Workout synchronization changed. Retry." }
        if (download) {
            val draft = dao.find("draft")?.let { WorkoutJson.session(Json.parseToJsonElement(it.payload).jsonObject) }
            if (draft?.metadata?.id == id) return@write false
            val records = capture().syncRecords()
            if (records.singleOrNull { it.id == id } != expected) return@write false
            (records.filterNot { it.id == id } + received.record).workoutLog()
            dao.save(WorkoutRow(id.toString(), received.record.kind, received.record.value.toString()))
        }
        saveBinding(current.copy(versions = current.versions + (id to received)))
        true
    }

    private suspend fun saveBinding(binding: WorkoutSyncBinding) =
        dao.save(WorkoutRow("workout-sync", "sync", binding.json().toString()))

    internal suspend fun restore(log: WorkoutLog) {
        log.validate()
        if (syncBinding() != null) {
            check(capture().canonical() == log.canonical()) {
                "This backup changes connected workouts. Current workouts and pending changes were kept."
            }
            return
        }
        dao.clear()
        for (exercise in log.exercises) dao.save(exercise.row())
        for (routine in log.routines) dao.save(routine.row())
        for (session in log.sessions) dao.save(session.row())
    }

    private suspend fun <T> write(block: suspend () -> T) = database.useWriterConnection { connection ->
        connection.immediateTransaction { block() }
    }

    private fun decodeRows(rows: List<WorkoutRow>): WorkoutLog {
        val exercises = mutableListOf<ExerciseDefinition>()
        val routines = mutableListOf<WorkoutRoutine>()
        val sessions = mutableListOf<WorkoutSession>()
        for (row in rows) {
            if (row.id in setOf("draft", "history-view", "workout-sync")) continue
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
