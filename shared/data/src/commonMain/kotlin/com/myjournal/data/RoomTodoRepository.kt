package com.myjournal.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.Todo
import com.myjournal.domain.TodoChangeJournal
import com.myjournal.domain.TodoOperation
import com.myjournal.domain.TodoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

class RoomTodoRepository(
    private val database: FoundationDatabase,
    private val clock: Clock = Clock.System,
) : TodoRepository, TodoChangeJournal {
    private val dao = database.todos()
    override fun observe() = dao.observe().map { rows -> rows.map(TodoRow::model) }

    suspend fun incomplete() = dao.incomplete().map(TodoRow::model)

    override suspend fun add(title: String, deadline: LocalDate): Uuid = write {
        val id = Uuid.random()
        val now = clock.now()
        append(Todo(RecordMetadata(id, now, now), title.trim(), deadline), emptySet())
        id
    }

    override suspend fun edit(id: Uuid, title: String, deadline: LocalDate) =
        modify(id) { old -> old.copy(title = title.trim(), deadline = deadline) }

    override suspend fun setCompleted(id: Uuid, completed: Boolean) = modify(id) { old ->
        old.copy(completedAt = if (completed) old.completedAt ?: old.metadata.updatedAt else null)
    }

    override suspend fun delete(id: Uuid) = modify(id) { it.copy(deleted = true) }

    private suspend fun modify(id: Uuid, transform: (Todo) -> Todo): Unit = write {
        val current = requireNotNull(dao.get(id.toString())).model()
        check(!current.deleted) { "Task has been deleted" }
        val heads = heads(id)
        check(heads.size == 1) { "Resolve the conflicting task versions before editing" }
        val revised = current.copy(metadata = current.metadata.revised(maxOf(clock.now(), current.metadata.updatedAt)))
        val changed = transform(revised)
        if (changed != revised) append(changed, heads.map { it.id }.toSet())
    }

    override suspend fun pending() = dao.pending().map(TodoOperationRow::model)

    override suspend fun acknowledge(operation: TodoOperation): Unit = write {
        val saved = requireNotNull(dao.operation(operation.id.toString()))
        require(saved.model() == operation) { "Acknowledgment does not match operation" }
        dao.acknowledge(saved.id)
    }

    override suspend fun receive(operations: List<TodoOperation>): Unit = write {
        val remaining = linkedMapOf<Uuid, TodoOperation>()
        val known = mutableMapOf<Uuid, TodoOperationRow>()
        for (op in operations) {
            val prior = remaining[op.id]
            val existing = known[op.id] ?: dao.operation(op.id.toString())
            require(prior == null || prior == op) { "Operation identity collision" }
            if (existing == null) {
                remaining[op.id] = op
            } else {
                require(existing.model() == op) { "Operation identity collision" }
                dao.acknowledge(existing.id)
                known[op.id] = existing
            }
        }
        val children = mutableMapOf<Uuid, MutableList<Uuid>>()
        val missing = mutableMapOf<Uuid, Int>()
        val ready = ArrayDeque<Uuid>()
        for (op in remaining.values) {
            var dependencies = 0
            for (parent in op.parents) {
                if (parent in remaining) {
                    children.getOrPut(parent) { mutableListOf() }.add(op.id)
                    dependencies++
                } else if (parent !in known) {
                    known[parent] = requireNotNull(dao.operation(parent.toString())) { "Missing operation dependency" }
                }
            }
            missing[op.id] = dependencies
            if (dependencies == 0) ready.addLast(op.id)
        }
        val affected = mutableSetOf<Uuid>()
        while (ready.isNotEmpty()) {
            val op = remaining.remove(ready.removeFirst()) ?: error("Invalid replay graph")
            val parents = op.parents.map(known::getValue)
            require(parents.all { it.recordId == op.record.metadata.id.toString() }) { "Cross-record parent" }
            require(op.record.deleted || parents.none { it.model().record.deleted }) { "Task resurrection" }
            require(parents.all { it.deviceId != op.deviceId.toString() || it.sequence < op.sequence }) {
                "Invalid device sequence"
            }
            val row = op.row(pending = false)
            dao.insert(row)
            known[op.id] = row
            affected.add(op.record.metadata.id)
            for (child in children[op.id].orEmpty()) {
                val dependencies = missing.getValue(child) - 1
                missing[child] = dependencies
                if (dependencies == 0) ready.addLast(child)
            }
        }
        require(remaining.isEmpty()) { "Cyclic operation dependencies" }
        for (id in affected) {
            val heads = heads(id)
            // Retain every head for resolution. A concurrent delete hides the task.
            val selected = heads.maxWith(compareBy({ it.record.deleted }, { it.id.toString() }))
            dao.save(selected.record.row())
        }
    }

    suspend fun conflicts(id: Uuid): List<TodoOperation> = heads(id).takeIf { it.size > 1 }.orEmpty()

    suspend fun resolve(id: Uuid, selected: Uuid, expectedHeads: Set<Uuid>): Unit = write {
        val heads = heads(id)
        require(heads.size > 1 && heads.map { it.id }.toSet() == expectedHeads) { "Conflict changed" }
        val chosen = heads.single { it.id == selected }.record
        require(chosen.deleted || heads.none { it.record.deleted }) { "A deleted task cannot be resurrected" }
        val revision = heads.maxOf { it.record.metadata.revision }
        val timestamp = maxOf(clock.now(), heads.maxOf { it.record.metadata.updatedAt })
        append(chosen.copy(metadata = chosen.metadata.copy(revision = revision).revised(timestamp)), expectedHeads)
    }

    /** Called only inside the confirmed local replacement transaction. */
    internal suspend fun replaceFromBackup(items: List<Todo>) {
        // Backups contain local records, not replication identities or credentials.
        // This core has no live cloud binding. Start a new journal for restored records.
        dao.clearRecords()
        dao.clearOperations()
        for (item in items) append(item, emptySet())
    }

    private suspend fun append(record: Todo, parents: Set<Uuid>) {
        val device = dao.device() ?: TodoDeviceRow(0, Uuid.random().toString(), 0)
        check(device.sequence < Long.MAX_VALUE)
        val next = device.copy(sequence = device.sequence + 1)
        val op = TodoOperation(Uuid.random(), Uuid.parse(next.id), next.sequence, parents, record)
        dao.saveDevice(next)
        dao.insert(op.row(pending = true))
        dao.save(record.row())
    }

    private suspend fun heads(id: Uuid): List<TodoOperation> {
        val history = dao.history(id.toString())
        val parents = history.flatMap { it.parentIds() }.toSet()
        return history.filter { Uuid.parse(it.id) !in parents }.map(TodoOperationRow::model)
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            database.useWriterConnection { connection -> connection.immediateTransaction { block() } }
        }
}
