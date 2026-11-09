package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class Todo(
    val metadata: RecordMetadata,
    val title: String,
    val deadline: LocalDate,
    val completedAt: Instant? = null,
    val deleted: Boolean = false,
) {
    init {
        require(title == title.trim() && title.isNotBlank() && title.length <= MAX_TITLE_LENGTH)
        require(deadline.year in 1..9999)
        require(metadata.schemaVersion == 1)
        require(completedAt == null || completedAt in metadata.createdAt..metadata.updatedAt)
    }

    companion object {
        const val MAX_TITLE_LENGTH = 200
    }
}

enum class TodoGroup { OVERDUE, TODAY, UPCOMING, COMPLETED }

fun groupTodos(items: List<Todo>, today: LocalDate): Map<TodoGroup, List<Todo>> =
    items.filterNot(Todo::deleted)
        .sortedWith(compareBy({ it.deadline }, { it.metadata.createdAt }, { it.metadata.id.toString() }))
        .groupBy {
            when {
                it.completedAt != null -> TodoGroup.COMPLETED
                it.deadline < today -> TodoGroup.OVERDUE
                it.deadline == today -> TodoGroup.TODAY
                else -> TodoGroup.UPCOMING
            }
        }

interface TodoRepository {
    fun observe(): Flow<List<Todo>>
    suspend fun add(title: String, deadline: LocalDate): Uuid
    suspend fun edit(id: Uuid, title: String, deadline: LocalDate)
    suspend fun setCompleted(id: Uuid, completed: Boolean)
    suspend fun delete(id: Uuid)
}

data class TodoOperation(
    val id: Uuid,
    val deviceId: Uuid,
    val sequence: Long,
    val parents: Set<Uuid>,
    val record: Todo,
) {
    init {
        require(sequence > 0 && id !in parents && parents.size <= 128)
    }
}

/** Append-only operation transport. A successful put acknowledges exactly this operation. */
interface TodoCloud {
    suspend fun put(operation: TodoOperation)
    suspend fun read(): List<TodoOperation>
}

interface TodoChangeJournal {
    suspend fun pending(): List<TodoOperation>
    suspend fun acknowledge(operation: TodoOperation)
    suspend fun receive(operations: List<TodoOperation>)
}
