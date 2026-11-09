package com.myjournal.server

import com.myjournal.data.TodoOperationJson
import com.myjournal.domain.CommittedTodo
import com.myjournal.domain.SyncScope
import com.myjournal.domain.TodoOperation
import com.myjournal.domain.TodoSyncPage
import java.sql.Connection
import kotlin.uuid.Uuid

internal class TodoStore(private val database: Database) {
    fun push(scope: SyncScope, operation: TodoOperation, resolve: Boolean = false): CommittedTodo = database.transaction {
        val dataset = scope.dataset.sql()
        val epoch = scope.epoch.sql()
        val last = lockEpoch(scope, write = true)
        val canonical = TodoOperationJson.encode(operation)
        val existing = query(
            """SELECT commit_sequence, canonical_payload FROM sync_operations
                WHERE dataset_id = ? AND epoch_id = ? AND id = ?""", dataset, epoch, operation.id.sql(),
        ) { it.getLong(1) to it.getString(2) }.singleOrNull()
        if (existing != null) {
            if (existing.second != canonical) conflict("IDEMPOTENCY_MISMATCH", "Operation identity already exists.")
            return@transaction CommittedTodo(existing.first, operation)
        }
        val record = operation.record.metadata.id.sql()
        if (resolve) {
            val heads = query(
                """SELECT operation_id FROM todo_heads
                    WHERE dataset_id = ? AND epoch_id = ? AND record_id = ?""", dataset, epoch, record,
            ) { it.uuid(1) }.toSet()
            if (heads.size < 2 || heads != operation.parents) conflict("CONFLICT_CHANGED", "The reviewed heads changed.")
        }
        for (parent in operation.parents) {
            val deleted = query(
                """SELECT deleted FROM sync_operations
                    WHERE dataset_id = ? AND epoch_id = ? AND record_id = ? AND id = ?""",
                dataset, epoch, record, parent.sql(),
            ) { it.getBoolean(1) }.singleOrNull()
                ?: conflict("MISSING_DEPENDENCY", "A parent is missing or belongs to another record.")
            if (deleted && !operation.record.deleted) conflict("CONFLICT_CHANGED", "A deleted task cannot be revived.")
        }
        val device = operation.deviceId.sql()
        val updated = execute(
            """INSERT INTO devices(dataset_id, epoch_id, id, last_sequence) VALUES (?, ?, ?, ?)
                ON CONFLICT (dataset_id, epoch_id, id) DO UPDATE SET last_sequence = EXCLUDED.last_sequence
                WHERE devices.last_sequence < EXCLUDED.last_sequence""", dataset, epoch, device, operation.sequence,
        )
        if (updated == 0) conflict("IDEMPOTENCY_MISMATCH", "Device sequence was already used or is stale.")
        val sequence = Math.addExact(last, 1)
        execute(
            """INSERT INTO sync_operations(dataset_id, epoch_id, id, device_id, device_sequence,
                commit_sequence, record_id, deleted, canonical_payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            dataset, epoch, operation.id.sql(), device, operation.sequence, sequence, record, operation.record.deleted, canonical,
        )
        for (parent in operation.parents) {
            execute(
                """DELETE FROM todo_heads WHERE dataset_id = ? AND epoch_id = ?
                    AND record_id = ? AND operation_id = ?""", dataset, epoch, record, parent.sql(),
            )
        }
        val remainingHeads = query(
            "SELECT count(*) FROM todo_heads WHERE dataset_id = ? AND epoch_id = ? AND record_id = ?",
            dataset, epoch, record,
        ) { it.getInt(1) }.single()
        if (remainingHeads >= 128) throw ApiFailure(413, "LIMIT_EXCEEDED", "Resolve existing task conflicts first.")
        execute("INSERT INTO todo_heads VALUES (?, ?, ?, ?)", dataset, epoch, record, operation.id.sql())
        execute(
            """INSERT INTO todo_projections(dataset_id, epoch_id, record_id, operation_id)
                SELECT h.dataset_id, h.epoch_id, h.record_id, h.operation_id
                FROM todo_heads h JOIN sync_operations o ON
                    (o.dataset_id, o.epoch_id, o.id) = (h.dataset_id, h.epoch_id, h.operation_id)
                WHERE h.dataset_id = ? AND h.epoch_id = ? AND h.record_id = ?
                ORDER BY o.deleted DESC, o.id DESC LIMIT 1
                ON CONFLICT (dataset_id, epoch_id, record_id) DO UPDATE SET operation_id = EXCLUDED.operation_id""",
            dataset, epoch, record,
        )
        execute("UPDATE recovery_epochs SET last_commit = ? WHERE dataset_id = ? AND id = ?", sequence, dataset, epoch)
        CommittedTodo(sequence, operation)
    }

    fun pull(scope: SyncScope, after: Long): TodoSyncPage = database.transaction {
        val last = lockEpoch(scope)
        if (after > last) throw ApiFailure(410, "CURSOR_EXPIRED", "The cursor is outside this epoch.")
        val entries = query(
            """SELECT commit_sequence, canonical_payload FROM sync_operations
                WHERE dataset_id = ? AND epoch_id = ? AND commit_sequence > ?
                ORDER BY commit_sequence LIMIT ?""",
            scope.dataset.sql(), scope.epoch.sql(), after, TodoSyncPage.MAX_ENTRIES,
        ) { CommittedTodo(it.getLong(1), TodoOperationJson.decode(it.getString(2))) }
        TodoSyncPage(scope, entries, (entries.lastOrNull()?.sequence ?: after) < last)
    }

    fun todos(scope: SyncScope, after: Uuid?, limit: Int): List<TodoOperation> = database.transaction {
        lockEpoch(scope)
        query(
            """SELECT o.canonical_payload FROM todo_projections p JOIN sync_operations o ON
                (o.dataset_id, o.epoch_id, o.id) = (p.dataset_id, p.epoch_id, p.operation_id)
                WHERE p.dataset_id = ? AND p.epoch_id = ? AND NOT o.deleted
                  AND (CAST(? AS uuid) IS NULL OR p.record_id > ?) ORDER BY p.record_id LIMIT ?""",
            scope.dataset.sql(), scope.epoch.sql(), after?.sql(), after?.sql(), limit + 1,
        ) { TodoOperationJson.decode(it.getString(1)) }
    }

    fun todo(scope: SyncScope, id: Uuid): TodoOperation? = database.transaction {
        lockEpoch(scope)
        query(
            """SELECT o.canonical_payload FROM todo_projections p JOIN sync_operations o ON
                (o.dataset_id, o.epoch_id, o.id) = (p.dataset_id, p.epoch_id, p.operation_id)
                WHERE p.dataset_id = ? AND p.epoch_id = ? AND p.record_id = ? AND NOT o.deleted""",
            scope.dataset.sql(), scope.epoch.sql(), id.sql(),
        ) { TodoOperationJson.decode(it.getString(1)) }.singleOrNull()
    }

    fun conflicts(scope: SyncScope, after: Uuid?, limit: Int): List<Pair<Uuid, List<TodoOperation>>> = database.transaction {
        lockEpoch(scope)
        val ids = query(
            """SELECT record_id FROM todo_heads WHERE dataset_id = ? AND epoch_id = ?
                AND (CAST(? AS uuid) IS NULL OR record_id > ?) GROUP BY record_id
                HAVING count(*) > 1 ORDER BY record_id LIMIT ?""",
            scope.dataset.sql(), scope.epoch.sql(), after?.sql(), after?.sql(), limit + 1,
        ) { it.uuid(1) }
        ids.map { id ->
            id to query(
                """SELECT o.canonical_payload FROM todo_heads h JOIN sync_operations o ON
                    (o.dataset_id, o.epoch_id, o.id) = (h.dataset_id, h.epoch_id, h.operation_id)
                    WHERE h.dataset_id = ? AND h.epoch_id = ? AND h.record_id = ? ORDER BY o.id""",
                scope.dataset.sql(), scope.epoch.sql(), id.sql(),
            ) { TodoOperationJson.decode(it.getString(1)) }
        }
    }

    fun backupStatus(scope: SyncScope): Pair<String, String?> = database.transaction {
        lockEpoch(scope)
        val state = query("SELECT state FROM backup_jobs WHERE dataset_id = ? ORDER BY started_at DESC, id DESC LIMIT 1",
            scope.dataset.sql()) { it.getString(1) }.singleOrNull() ?: "never"
        val verified = query("SELECT max(verified_at) FROM backup_jobs WHERE dataset_id = ?", scope.dataset.sql()) {
            it.getTimestamp(1)?.toInstant()?.toString()
        }.singleOrNull()
        state to verified
    }
}
