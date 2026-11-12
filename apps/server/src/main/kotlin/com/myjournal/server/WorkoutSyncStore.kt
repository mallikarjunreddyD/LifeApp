package com.myjournal.server

import com.myjournal.data.WorkoutSyncRecord
import com.myjournal.data.WorkoutSyncVersion
import com.myjournal.data.workoutLog
import com.myjournal.domain.SyncScope
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

internal class WorkoutSyncStore(private val database: Database) {
    fun list(scope: SyncScope, after: Uuid?, limit: Int): List<WorkoutSyncVersion> = database.transaction {
        lockEpoch(scope)
        query(
            """SELECT h.version, v.kind, v.payload FROM workout_sync_heads h JOIN workout_sync_versions v
               ON v.dataset_id = h.dataset_id AND v.version = h.version
               WHERE h.dataset_id = ? AND h.record_id > coalesce(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
               ORDER BY h.record_id LIMIT ?""",
            scope.dataset.sql(), after?.sql(), limit + 1,
        ) { WorkoutSyncVersion(it.uuid(1), decode(it.getString(2), it.getString(3))) }
    }

    fun head(scope: SyncScope, id: Uuid): WorkoutSyncVersion = database.transaction {
        lockEpoch(scope)
        query(
            """SELECT h.version, v.kind, v.payload FROM workout_sync_heads h JOIN workout_sync_versions v
               ON v.dataset_id = h.dataset_id AND v.version = h.version
               WHERE h.dataset_id = ? AND h.record_id = ?""",
            scope.dataset.sql(), id.sql(),
        ) { WorkoutSyncVersion(it.uuid(1), decode(it.getString(2), it.getString(3))) }.singleOrNull()
            ?: throw ApiFailure(404, "WORKOUT_NOT_FOUND", "The workout is unavailable.")
    }

    fun push(scope: SyncScope, requested: WorkoutSyncVersion, parent: Uuid?): WorkoutSyncVersion = database.transaction {
        lockEpoch(scope, write = true)
        val dataset = scope.dataset.sql()
        val record = requested.record
        require(record.id != Uuid.NIL)
        val priorRequest = query(
            "SELECT parent, kind, payload FROM workout_sync_versions WHERE dataset_id = ? AND version = ?",
            dataset, requested.version.sql(),
        ) {
            it.getObject(1)?.let { id -> Uuid.parse(id.toString()) } to decode(it.getString(2), it.getString(3))
        }.singleOrNull()
        if (priorRequest != null) {
            if (priorRequest.first != parent || priorRequest.second != record) {
                conflict("IDEMPOTENCY_MISMATCH", "Workout operation identity already exists.")
            }
            return@transaction requested
        }
        val heads = query(
            """SELECT h.version, v.kind, v.payload FROM workout_sync_heads h JOIN workout_sync_versions v
               ON v.dataset_id = h.dataset_id AND v.version = h.version WHERE h.dataset_id = ?""",
            dataset,
        ) { WorkoutSyncVersion(it.uuid(1), decode(it.getString(2), it.getString(3))) }
        val current = heads.singleOrNull { it.record.id == record.id }
        if (current?.record == record) return@transaction current
        if (current?.version != parent) conflict("WORKOUT_CHANGED", "A workout changed on another device. Both copies were kept.")
        require(current == null || current.record.kind == record.kind)
        val metadata = record.value.getValue("metadata").jsonObject
        if (current != null) {
            val before = current.record.value.getValue("metadata").jsonObject
            require(before.getValue("createdAt") == metadata.getValue("createdAt"))
            require(before.getValue("revision").jsonPrimitive.content.toLong() < metadata.getValue("revision").jsonPrimitive.content.toLong())
        } else if (heads.size >= 5_000) {
            throw ApiFailure(413, "LIMIT_EXCEEDED", "This workout library reached its synchronization limit.")
        }
        (heads.map { it.record }.filterNot { it.id == record.id } + record).workoutLog()
        execute("INSERT INTO workout_sync_versions VALUES (?, ?, ?, ?, ?, ?)", dataset, requested.version.sql(),
            record.id.sql(), parent?.sql(), record.kind, record.value.toString())
        execute(
            """INSERT INTO workout_sync_heads VALUES (?, ?, ?) ON CONFLICT (dataset_id, record_id)
               DO UPDATE SET version = excluded.version""",
            dataset, record.id.sql(), requested.version.sql(),
        )
        requested
    }

    private fun decode(kind: String, payload: String) =
        WorkoutSyncRecord(kind, Json.parseToJsonElement(payload).jsonObject)
}
