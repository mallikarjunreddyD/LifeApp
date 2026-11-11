package com.myjournal.data

import com.myjournal.domain.WorkoutLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

enum class WorkoutRecordKind { EXERCISE, ROUTINE, SESSION, ENTRY, SET }

data class WorkoutRecord(val kind: WorkoutRecordKind, val payload: JsonObject) {
    val id: Uuid get() = Uuid.parse(metadata.getValue("id").jsonPrimitive.content)
    internal val metadata get() = payload.getValue("metadata").jsonObject

    internal fun parent(field: String) =
        payload[field]?.jsonPrimitive?.contentOrNull?.let(Uuid::parse)
}

/** Local record changes. The transport will attach operation IDs, causal heads and dataset scope. */
data class WorkoutRecordDelta(val puts: List<WorkoutRecord>, val removals: List<WorkoutRecord>)

/** Maps the existing workout representation to individually addressable records. */
object WorkoutRecordProjection {
    fun flatten(log: WorkoutLog): List<WorkoutRecord> {
        log.validate()
        val ordered = log.canonical()
        return buildList {
            ordered.exercises.forEach { add(WorkoutRecord(WorkoutRecordKind.EXERCISE, it.workoutJson())) }
            ordered.routines.forEach { add(WorkoutRecord(WorkoutRecordKind.ROUTINE, it.workoutJson())) }
            for (session in ordered.sessions) {
                add(WorkoutRecord(WorkoutRecordKind.SESSION, JsonObject(session.workoutJson() - "entries")))
                for (entry in session.entries) {
                    add(WorkoutRecord(WorkoutRecordKind.ENTRY, JsonObject(entry.json() - "sets")))
                    entry.sets.forEach { add(WorkoutRecord(WorkoutRecordKind.SET, it.json())) }
                }
            }
        }
    }

    fun assemble(records: Collection<WorkoutRecord>): WorkoutLog {
        require(records.map(WorkoutRecord::id).toSet().size == records.size) { "Duplicate workout identity" }
        val byKind = records.groupBy(WorkoutRecord::kind)
        val entries = byKind[WorkoutRecordKind.ENTRY].orEmpty().groupBy { it.parent("sessionId") }
        val sets = byKind[WorkoutRecordKind.SET].orEmpty().groupBy { it.parent("entryId") }
        val sessions = byKind[WorkoutRecordKind.SESSION].orEmpty().map { session ->
            val children = entries[session.id].orEmpty().sortedBy { it.payload.getValue("order").jsonPrimitive.int }
            val projected = children.map { entry ->
                val rows = sets[entry.id].orEmpty().sortedBy { it.payload.getValue("number").jsonPrimitive.int }
                JsonObject(entry.payload + ("sets" to JsonArray(rows.map(WorkoutRecord::payload))))
            }
            WorkoutJson.session(JsonObject(session.payload + ("entries" to JsonArray(projected))))
        }
        val result = WorkoutLog(
            byKind[WorkoutRecordKind.EXERCISE].orEmpty().map { WorkoutJson.exercise(it.payload) },
            byKind[WorkoutRecordKind.ROUTINE].orEmpty().map { WorkoutJson.routine(it.payload) },
            sessions,
        ).canonical()
        require(flatten(result).toSet() == records.toSet()) { "Invalid, orphaned or noncanonical workout record" }
        return result
    }

    fun diff(before: WorkoutLog, after: WorkoutLog): WorkoutRecordDelta {
        val previous = flatten(before)
        val next = flatten(after)
        val previousById = previous.associateBy(WorkoutRecord::id)
        val nextIds = next.map(WorkoutRecord::id).toSet()
        val puts = next.filter { record ->
            val old = previousById[record.id] ?: return@filter true
            require(old.kind == record.kind &&
                old.parent("sessionId") == record.parent("sessionId") &&
                old.parent("entryId") == record.parent("entryId") &&
                old.parent("exerciseId") == record.parent("exerciseId") &&
                old.metadata["createdAt"] == record.metadata["createdAt"]) {
                "Workout identity and ownership must remain stable"
            }
            // Local aggregate edits may increment parent metadata even when only a child changed.
            old.payload - "metadata" != record.payload - "metadata"
        }
        return WorkoutRecordDelta(puts, previous.asReversed().filter { it.id !in nextIds })
    }
}
