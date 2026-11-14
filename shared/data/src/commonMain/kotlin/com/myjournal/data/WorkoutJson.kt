package com.myjournal.data

import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.*

fun ExerciseDefinition.workoutJson() = buildJsonObject {
    put("metadata", metadata.json())
    put("name", name)
    put("category", category)
    put("equipment", equipment)
    put("archived", archived)
    description?.let { put("description", it) }
}

fun WorkoutSession.workoutJson() = buildJsonObject {
    put("metadata", metadata.json())
    put("date", date.toString())
    put("startedAt", startedAt?.toString())
    put("endedAt", endedAt?.toString())
    put("durationSeconds", durationSeconds)
    averageHeartRateBpm?.let { put("averageHeartRateBpm", it) }
    if (durationManuallyEntered) put("durationManuallyEntered", true)
    put("caloriesKcal", caloriesKcal)
    put("notes", notes)
    put("completed", completed)
    if (logged) put("logged", true)
    if (deleted) put("deleted", true)
    routineId?.let { put("routineId", it.toString()) }
    routineName?.let { put("routineName", it) }
    put("entries", JsonArray(entries.map(WorkoutExerciseEntry::json)))
}

internal fun WorkoutExerciseEntry.json() = buildJsonObject {
    put("metadata", metadata.json())
    put("sessionId", sessionId.toString())
    put("exerciseId", exerciseId.toString())
    put("order", order)
    put("round", round)
    remarks?.let { put("remarks", it) }
    put("sets", JsonArray(sets.map(RecordedSet::json)))
}

internal fun RecordedSet.json() = buildJsonObject {
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

fun WorkoutRoutine.workoutJson() = buildJsonObject {
    put("metadata", metadata.json())
    put("name", name)
    put("exerciseOrder", JsonArray(exerciseOrder.map { JsonPrimitive(it.toString()) }))
    if (archived) put("archived", true)
    if (plannedSetCounts.isNotEmpty()) put("plannedSetCounts", JsonArray(plannedSetCounts.map(::JsonPrimitive)))
}

object WorkoutJson {
    fun exercise(value: JsonObject) = with(value) {
        ExerciseDefinition(metadata(), text("name")!!, text("category"), text("equipment"), flag("archived"), optionalText("description"))
    }

    fun routine(value: JsonObject) = with(value) {
        WorkoutRoutine(
            metadata(), text("name")!!, getValue("exerciseOrder").jsonArray.map { Uuid.parse(it.jsonPrimitive.content) },
            get("plannedSetCounts")?.jsonArray?.map { item -> if (item == JsonNull) null else item.jsonPrimitive.int }.orEmpty(),
            get("archived")?.jsonPrimitive?.boolean ?: false,
        )
    }

    fun session(value: JsonObject) = with(value) {
        WorkoutSession(
            metadata(), LocalDate.parse(text("date")!!), text("startedAt")?.let(Instant::parse),
            text("endedAt")?.let(Instant::parse), number("durationSeconds"), number("caloriesKcal"),
            text("notes"), flag("completed"), getValue("entries").jsonArray.map { entry(it.jsonObject) },
            averageHeartRateBpm = get("averageHeartRateBpm")?.jsonPrimitive?.int,
            durationManuallyEntered = get("durationManuallyEntered")?.jsonPrimitive?.boolean ?: false,
            logged = get("logged")?.jsonPrimitive?.boolean ?: false,
            deleted = get("deleted")?.jsonPrimitive?.boolean ?: false,
            routineId = optionalText("routineId")?.let(Uuid::parse),
            routineName = optionalText("routineName"),
        )
    }

    private fun entry(value: JsonObject) = with(value) {
        WorkoutExerciseEntry(
            metadata(), Uuid.parse(text("sessionId")!!), Uuid.parse(text("exerciseId")!!),
            number("order")!!, number("round"), getValue("sets").jsonArray.map { set(it.jsonObject) },
            optionalText("remarks"),
        )
    }

    private fun set(value: JsonObject) = with(value) {
        val weight = getValue("weight").takeUnless { it == JsonNull }?.jsonObject?.let {
            WorkoutWeight(it.getValue("value").jsonPrimitive.double, WeightUnit.valueOf(it.text("unit")!!))
        }
        RecordedSet(
            metadata(), Uuid.parse(text("entryId")!!), number("number")!!, weight,
            number("repetitions"), number("durationSeconds"), number("restSeconds"), flag("completed"),
        )
    }

    private fun JsonObject.metadata() = with(getValue("metadata").jsonObject) {
        RecordMetadata(
            Uuid.parse(text("id")!!), Instant.parse(text("createdAt")!!), Instant.parse(text("updatedAt")!!),
            number("schemaVersion")!!, text("revision")!!.toLong(),
        ).also { require(it.schemaVersion == 1) }
    }

    private fun JsonObject.optionalText(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.contentOrNull
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.let { if (it == JsonNull) null else it.int }
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
}

fun WorkoutLog.workoutJson() = buildJsonObject {
    put("exercises", JsonArray(exercises.map { it.workoutJson() }))
    put("routines", JsonArray(routines.map { it.workoutJson() }))
    put("sessions", JsonArray(sessions.map { it.workoutJson() }))
}

fun decodeWorkoutLog(value: JsonObject) = WorkoutLog(
    value.getValue("exercises").jsonArray.map { WorkoutJson.exercise(it.jsonObject) },
    value.getValue("routines").jsonArray.map { WorkoutJson.routine(it.jsonObject) },
    value.getValue("sessions").jsonArray.map { WorkoutJson.session(it.jsonObject) },
).also { it.validate() }
