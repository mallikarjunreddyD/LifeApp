package com.myjournal.data

import com.myjournal.domain.WorkoutLog
import com.myjournal.domain.SyncScope
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

data class WorkoutSyncRecord(val kind: String, val value: JsonObject) {
    val id: Uuid get() = Uuid.parse(value.getValue("metadata").jsonObject.getValue("id").jsonPrimitive.content)

    fun canonical(): WorkoutSyncRecord = copy(value = when (kind) {
        "exercise" -> WorkoutJson.exercise(value).workoutJson()
        "routine" -> WorkoutJson.routine(value).workoutJson()
        "session" -> WorkoutJson.session(value).workoutJson()
        else -> error("Invalid workout kind")
    })

    fun json(): JsonObject = buildJsonObject {
        put("kind", kind)
        put("value", value)
    }

    companion object {
        fun parse(value: JsonObject): WorkoutSyncRecord {
            require(value.keys == setOf("kind", "value"))
            return WorkoutSyncRecord(value.getValue("kind").jsonPrimitive.content, value.getValue("value").jsonObject).canonical()
        }
    }
}

fun WorkoutLog.syncRecords(): List<WorkoutSyncRecord> =
    exercises.map { WorkoutSyncRecord("exercise", it.workoutJson()) } +
        routines.map { WorkoutSyncRecord("routine", it.workoutJson()) } +
        sessions.map { WorkoutSyncRecord("session", it.workoutJson()) }

fun List<WorkoutSyncRecord>.workoutLog() = WorkoutLog(
    filter { it.kind == "exercise" }.map { WorkoutJson.exercise(it.value) },
    filter { it.kind == "routine" }.map { WorkoutJson.routine(it.value) },
    filter { it.kind == "session" }.map { WorkoutJson.session(it.value) },
).also { it.validate() }

data class WorkoutSyncVersion(val version: Uuid, val record: WorkoutSyncRecord) {
    fun json() = buildJsonObject {
        put("version", version.toString())
        put("record", record.json())
    }

    companion object {
        fun parse(value: JsonObject): WorkoutSyncVersion = WorkoutSyncVersion(
            Uuid.parse(value.getValue("version").jsonPrimitive.content),
            WorkoutSyncRecord.parse(value.getValue("record").jsonObject),
        )
    }
}

data class WorkoutSyncBinding(val scope: SyncScope, val origin: String, val versions: Map<Uuid, WorkoutSyncVersion> = emptyMap()) {
    internal fun json() = buildJsonObject {
        put("dataset", scope.dataset.toString())
        put("epoch", scope.epoch.toString())
        put("origin", origin)
        put("versions", JsonArray(versions.values.map(WorkoutSyncVersion::json)))
    }

    companion object {
        internal fun parse(value: JsonObject): WorkoutSyncBinding = with(value) {
            val scope = SyncScope(Uuid.parse(getValue("dataset").jsonPrimitive.content), Uuid.parse(getValue("epoch").jsonPrimitive.content))
            WorkoutSyncBinding(scope, getValue("origin").jsonPrimitive.content,
                getValue("versions").jsonArray.map { WorkoutSyncVersion.parse(it.jsonObject) }.associateBy { it.record.id })
        }
    }
}
