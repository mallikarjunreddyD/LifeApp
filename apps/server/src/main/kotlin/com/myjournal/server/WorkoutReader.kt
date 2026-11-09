package com.myjournal.server

import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.datetime.LocalDate
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Read side only. Workout mutations and replication are introduced in Phase 6D. */
internal class WorkoutReader(private val database: Database) {
    fun exercises(scope: SyncScope, after: Uuid?, limit: Int): List<ExerciseDefinition> = database.transaction {
        lockEpoch(scope)
        query(
            """SELECT * FROM exercises WHERE dataset_id = ? AND (CAST(? AS uuid) IS NULL OR id > ?)
                ORDER BY id LIMIT ?""", scope.dataset.sql(), after?.sql(), after?.sql(), limit + 1,
        ) {
            ExerciseDefinition(it.metadata(), it.getString("name"), it.getString("category"),
                it.getString("equipment"), it.getBoolean("archived"))
        }
    }

    fun workouts(scope: SyncScope, after: Uuid?, limit: Int): List<WorkoutSession> = database.transaction {
        lockEpoch(scope)
        query(
            """SELECT * FROM workout_sessions WHERE dataset_id = ? AND (CAST(? AS uuid) IS NULL OR id > ?)
                ORDER BY id LIMIT ?""", scope.dataset.sql(), after?.sql(), after?.sql(), limit + 1,
        ) { it.workout() }.map { it.copy(entries = entries(scope, it.metadata.id)) }
    }

    fun workout(scope: SyncScope, id: Uuid): WorkoutSession? = database.transaction {
        lockEpoch(scope)
        query("SELECT * FROM workout_sessions WHERE dataset_id = ? AND id = ?", scope.dataset.sql(), id.sql()) {
            it.workout()
        }.singleOrNull()?.let { it.copy(entries = entries(scope, id)) }
    }

    private fun Connection.entries(scope: SyncScope, id: Uuid): List<WorkoutExerciseEntry> =
        query(
            """SELECT * FROM workout_exercise_entries WHERE dataset_id = ? AND session_id = ?
                ORDER BY position LIMIT 201""", scope.dataset.sql(), id.sql(),
        ) {
            WorkoutExerciseEntry(it.metadata(), id, Uuid.parse(it.getString("exercise_id")),
                it.getInt("position"), it.optionalInt("round"))
        }.also { check(it.size <= 200) }.let { entries ->
            var remaining = 1000
            entries.map { entry ->
                val sets = sets(scope, entry.metadata.id, remaining)
                remaining -= sets.size
                entry.copy(sets = sets)
            }
        }

    private fun Connection.sets(scope: SyncScope, id: Uuid, remaining: Int): List<RecordedSet> =
        query(
            """SELECT * FROM recorded_sets WHERE dataset_id = ? AND entry_id = ? ORDER BY number LIMIT 1001""",
            scope.dataset.sql(), id.sql(),
        ) {
            val weight = it.getDouble("weight").takeUnless { _ -> it.wasNull() }
            RecordedSet(it.metadata(), id, it.getInt("number"),
                weight?.let { value -> WorkoutWeight(value, WeightUnit.valueOf(it.getString("weight_unit"))) },
                it.optionalInt("repetitions"), it.optionalInt("duration_seconds"), it.optionalInt("rest_seconds"),
                it.getBoolean("completed"))
        }.also { check(it.size <= remaining) }

    private fun ResultSet.workout() = WorkoutSession(
        metadata(), LocalDate.parse(getString("local_date")),
        getString("started_at")?.let(Instant::parse), getString("ended_at")?.let(Instant::parse),
        optionalInt("duration_seconds"), optionalInt("calories_kcal"), getString("notes"), getBoolean("completed"),
    )

    private fun ResultSet.metadata() = RecordMetadata(
        Uuid.parse(getString("id")), Instant.parse(getString("created_at")), Instant.parse(getString("updated_at")),
        revision = getLong("revision"),
    )

    private fun ResultSet.optionalInt(column: String) = getInt(column).takeUnless { wasNull() }
}
