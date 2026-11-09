package com.myjournal.server

import com.myjournal.core.RecordMetadata
import com.myjournal.domain.ExerciseDefinition
import com.myjournal.domain.RecordedSet
import com.myjournal.domain.WorkoutExerciseEntry
import com.myjournal.domain.WorkoutSession
import kotlinx.serialization.json.*

internal fun ExerciseDefinition.json() = buildJsonObject {
    put("metadata", metadata.json())
    put("name", name)
    put("category", category)
    put("equipment", equipment)
    put("archived", archived)
}

internal fun WorkoutSession.json() = buildJsonObject {
    put("metadata", metadata.json())
    put("date", date.toString())
    put("startedAt", startedAt?.toString())
    put("endedAt", endedAt?.toString())
    put("durationSeconds", durationSeconds)
    put("caloriesKcal", caloriesKcal)
    put("notes", notes)
    put("completed", completed)
    put("entries", JsonArray(entries.map(WorkoutExerciseEntry::json)))
}

private fun WorkoutExerciseEntry.json() = buildJsonObject {
    put("metadata", metadata.json())
    put("sessionId", sessionId.toString())
    put("exerciseId", exerciseId.toString())
    put("order", order)
    put("round", round)
    put("sets", JsonArray(sets.map(RecordedSet::json)))
}

private fun RecordedSet.json() = buildJsonObject {
    put("metadata", metadata.json())
    put("entryId", entryId.toString())
    put("number", number)
    put("weight", weight?.let { value -> buildJsonObject {
        put("value", value.value)
        put("unit", value.unit.name)
    } } ?: JsonNull)
    put("repetitions", repetitions)
    put("durationSeconds", durationSeconds)
    put("restSeconds", restSeconds)
    put("completed", completed)
}

private fun RecordMetadata.json() = buildJsonObject {
    put("id", id.toString())
    put("createdAt", createdAt.toString())
    put("updatedAt", updatedAt.toString())
    put("schemaVersion", schemaVersion)
    put("revision", revision.toString())
}
