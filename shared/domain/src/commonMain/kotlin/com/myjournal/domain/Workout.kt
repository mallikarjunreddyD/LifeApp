package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class ExerciseDefinition(
    val metadata: RecordMetadata,
    val name: String,
    val category: String? = null,
    val equipment: String? = null,
    val archived: Boolean = false,
    val description: String? = null,
) {
    init {
        require(description == null || description.length <= 4000)
        require(name.isNotBlank() && name == name.trim() && name.length <= 120)
        require(listOfNotNull(category, equipment).all { it.isNotBlank() && it.length <= 120 })
    }
}

enum class WeightUnit { KG, LB }

data class WorkoutWeight(val value: Double, val unit: WeightUnit) {
    init { require(value.isFinite() && value >= 0) }
}

data class RecordedSet(
    val metadata: RecordMetadata,
    val entryId: Uuid,
    val number: Int,
    val weight: WorkoutWeight?,
    val repetitions: Int?,
    val durationSeconds: Int? = null,
    val restSeconds: Int? = null,
    val completed: Boolean = false,
) {
    init {
        require(number > 0)
        require(repetitions == null || repetitions >= 0)
        require(durationSeconds == null || durationSeconds > 0)
        require(restSeconds == null || restSeconds >= 0)
        require(!completed || (repetitions ?: 0) > 0 || durationSeconds != null)
    }
}

data class WorkoutExerciseEntry(
    val metadata: RecordMetadata,
    val sessionId: Uuid,
    val exerciseId: Uuid,
    val order: Int,
    val round: Int? = null,
    val sets: List<RecordedSet> = emptyList(),
    val remarks: String? = null,
) {
    init {
        require(remarks == null || remarks.length <= 4000)
        require(order >= 0 && (round == null || round > 0))
        require(sets.all { it.entryId == metadata.id })
        require(sets.map { it.number } == (1..sets.size).toList())
        require(sets.map { it.metadata.id }.toSet().size == sets.size)
    }
}

data class WorkoutSession(
    val metadata: RecordMetadata,
    val date: LocalDate,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val durationSeconds: Int?,
    val caloriesKcal: Int? = null,
    val notes: String? = null,
    val completed: Boolean = false,
    val entries: List<WorkoutExerciseEntry> = emptyList(),
    val averageHeartRateBpm: Int? = null,
    val durationManuallyEntered: Boolean = false,
    val logged: Boolean = false,
    val routineId: Uuid? = null,
    val routineName: String? = null,
    val deleted: Boolean = false,
) {
    init {
        require(date.year in 1..9999)
        require(endedAt == null || (startedAt != null && endedAt >= startedAt))
        require(durationSeconds == null || durationSeconds >= 0)
        if (startedAt != null && endedAt != null) {
            require(durationSeconds != null && (durationManuallyEntered || durationSeconds.toLong() <= (endedAt - startedAt).inWholeSeconds))
        }
        require(!completed || logged || endedAt != null)
        require((routineId == null) == (routineName == null))
        require(routineName == null || routineName.isNotBlank() && routineName.length <= 120)
        require(!durationManuallyEntered || durationSeconds != null)
        require(averageHeartRateBpm == null || averageHeartRateBpm in 20..250) {
            "Enter an average heart rate between 20 and 250 BPM."
        }
        require(caloriesKcal == null || caloriesKcal >= 0)
        require(notes == null || notes.length <= 4_000)
        require(entries.all { it.sessionId == metadata.id })
        require(entries.map { it.order } == entries.indices.toList())
        val ids = listOf(metadata.id) + entries.map { it.metadata.id } +
            entries.flatMap { entry -> entry.sets.map { it.metadata.id } }
        require(ids.toSet().size == ids.size)
    }

    fun validateExerciseReferences(exercises: Set<Uuid>) {
        require(entries.all { it.exerciseId in exercises })
    }
}

/** A template is independent of recorded sessions and contains no measured sets or calories. */
data class WorkoutRoutine(
    val metadata: RecordMetadata,
    val name: String,
    val exerciseOrder: List<Uuid>,
    val plannedSetCounts: List<Int?> = emptyList(),
) {
    init {
        require(name.isNotBlank() && name.length <= 120 && exerciseOrder.isNotEmpty())
        require(plannedSetCounts.isEmpty() || plannedSetCounts.size == exerciseOrder.size)
        require(plannedSetCounts.filterNotNull().all { it in 1..100 })
    }
}

data class WorkoutLog(
    val exercises: List<ExerciseDefinition> = emptyList(),
    val routines: List<WorkoutRoutine> = emptyList(),
    val sessions: List<WorkoutSession> = emptyList(),
) {
    val records get() = exercises.map { it.metadata } + routines.map { it.metadata } + sessions.flatMap { session ->
        listOf(session.metadata) + session.entries.flatMap { entry ->
            listOf(entry.metadata) + entry.sets.map { it.metadata }
        }
    }

    fun validate() {
        val ids = exercises.map { it.metadata.id }.toSet()
        require(records.map { it.id }.toSet().size == records.size) { "Duplicate workout identity" }
        sessions.forEach { it.validateExerciseReferences(ids) }
        require(routines.all { routine -> routine.exerciseOrder.all { it in ids } }) { "Unknown routine exercise" }
    }

    fun canonical() = WorkoutLog(
        exercises.sortedBy { it.metadata.id.toString() },
        routines.sortedBy { it.metadata.id.toString() },
        sessions.sortedBy { it.metadata.id.toString() },
    )
}

data class WorkoutDraft(val session: WorkoutSession, val previousRevision: Long?)

interface WorkoutRepository {
    fun observe(): kotlinx.coroutines.flow.Flow<WorkoutLog>
    suspend fun saveExercise(exercise: ExerciseDefinition)
    suspend fun saveRoutine(routine: WorkoutRoutine)
    suspend fun saveSession(session: WorkoutSession, previousRevision: Long?)
    suspend fun loadDraft(): WorkoutDraft?
    suspend fun saveDraft(draft: WorkoutDraft?)
    suspend fun spreadsheetHistory(): Boolean
    suspend fun setSpreadsheetHistory(enabled: Boolean)
    suspend fun setSessionDeleted(id: Uuid, deleted: Boolean)
}
