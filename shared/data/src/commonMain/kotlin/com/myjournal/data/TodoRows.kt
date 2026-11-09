package com.myjournal.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.myjournal.domain.SyncCheckpoint
import com.myjournal.domain.SyncScope
import com.myjournal.domain.Todo
import com.myjournal.domain.TodoOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(tableName = "todos", indices = [Index("deleted", "completedAt", "deadline")])
data class TodoRow(
    @PrimaryKey val id: String,
    val title: String,
    val deadline: String,
    val completedAt: String?,
    val deleted: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val revision: Long,
    val schemaVersion: Int,
)

@Entity(tableName = "todo_operations", indices = [
    Index("recordId"), Index("deviceId", "sequence", unique = true), Index("pending"),
])
data class TodoOperationRow(
    @PrimaryKey val id: String,
    val deviceId: String,
    val sequence: Long,
    val recordId: String,
    val parents: String,
    val payload: String,
    val pending: Boolean,
)

@Entity(tableName = "todo_sync_device")
data class TodoDeviceRow(@PrimaryKey val slot: Int, val id: String, val sequence: Long)

@Entity(tableName = "todo_sync_checkpoint")
data class TodoCheckpointRow(
    @PrimaryKey val slot: Int,
    val dataset: String,
    val epoch: String,
    val sequence: Long,
) {
    internal fun model() = SyncCheckpoint(SyncScope(Uuid.parse(dataset), Uuid.parse(epoch)), sequence)
}

internal fun SyncCheckpoint.row() = TodoCheckpointRow(0, scope.dataset.toString(), scope.epoch.toString(), sequence)

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE deleted = 0 ORDER BY deadline, createdAt, id")
    fun observe(): Flow<List<TodoRow>>

    @Query("SELECT * FROM todos WHERE deleted = 0 AND completedAt IS NULL ORDER BY deadline")
    suspend fun incomplete(): List<TodoRow>

    @Query("SELECT * FROM todos ORDER BY id")
    suspend fun all(): List<TodoRow>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: String): TodoRow?

    @Upsert suspend fun save(row: TodoRow)
    @Query("DELETE FROM todos") suspend fun clearRecords()
    @Query("DELETE FROM todo_operations") suspend fun clearOperations()

    @Query("SELECT * FROM todo_operations WHERE id = :id")
    suspend fun operation(id: String): TodoOperationRow?

    @Query("SELECT * FROM todo_operations WHERE recordId = :id")
    suspend fun history(id: String): List<TodoOperationRow>

    @Query("SELECT * FROM todo_operations WHERE pending = 1 ORDER BY sequence")
    suspend fun pending(): List<TodoOperationRow>

    @Query("UPDATE todo_operations SET pending = 0 WHERE id = :id")
    suspend fun acknowledge(id: String)

    @Insert suspend fun insert(row: TodoOperationRow)
    @Query("SELECT * FROM todo_sync_device WHERE slot = 0")
    suspend fun device(): TodoDeviceRow?

    @Upsert suspend fun saveDevice(row: TodoDeviceRow)

    @Query("SELECT * FROM todo_sync_checkpoint WHERE slot = 0")
    suspend fun checkpoint(): TodoCheckpointRow?

    @Upsert suspend fun saveCheckpoint(row: TodoCheckpointRow)
}

internal fun Todo.row() = with(metadata) {
    TodoRow(id.toString(), title, deadline.toString(), completedAt?.toString(), deleted,
        createdAt.toString(), updatedAt.toString(), revision, schemaVersion)
}

internal fun TodoRow.model() = Todo(
    notebookMeta(id, createdAt, updatedAt, revision, schemaVersion), title,
    LocalDate.parse(deadline), completedAt?.let(Instant::parse), deleted,
)

internal fun TodoOperation.row(pending: Boolean) = TodoOperationRow(
    id.toString(), deviceId.toString(), sequence, record.metadata.id.toString(),
    parents.map(Uuid::toString).sorted().joinToString(","), encodeTodo(record).toString(), pending,
)

internal fun TodoOperationRow.parentIds(): Set<Uuid> =
    if (parents.isEmpty()) emptySet() else parents.split(',').map(Uuid::parse).toSet()

internal fun TodoOperationRow.model() = TodoOperation(
    Uuid.parse(id), Uuid.parse(deviceId), sequence, parentIds(), decodeTodoPayload(payload),
)
