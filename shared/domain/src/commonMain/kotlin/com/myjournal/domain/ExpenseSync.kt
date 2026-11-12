package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlin.uuid.Uuid
import kotlin.time.Clock

sealed interface ExpenseRecord {
    val metadata: RecordMetadata
    val deleted: Boolean get() = false

    data class Item(val value: Expense) : ExpenseRecord {
        override val metadata get() = value.metadata
        override val deleted get() = value.deleted
    }

    data class Label(val value: ExpenseLabel) : ExpenseRecord {
        override val metadata get() = value.metadata
    }
}

enum class ExpenseMutation { PUT, DELETE, RESTORE, RESOLVE }

data class ExpenseOperation(
    val id: Uuid,
    val parents: Set<Uuid>,
    val mutation: ExpenseMutation,
    val record: ExpenseRecord,
) {
    init {
        require(parents.size <= 32 && id !in parents && record.metadata.schemaVersion == 1)
        require(mutation != ExpenseMutation.RESOLVE || parents.size >= 2)
        require(mutation != ExpenseMutation.DELETE || record.deleted)
        require(mutation != ExpenseMutation.RESTORE || record is ExpenseRecord.Item && !record.deleted && parents.size == 1)
        require(mutation != ExpenseMutation.PUT || !record.deleted)
    }
}

data class CommittedExpense(val sequence: Long, val operation: ExpenseOperation) {
    init { require(sequence > 0) }
}

data class ExpenseSyncState(
    val scope: SyncScope,
    val origin: String,
    val cursor: Long = 0,
    val heads: Map<Uuid, List<ExpenseOperation>> = emptyMap(),
    val pending: List<ExpenseOperation> = emptyList(),
    val acknowledged: Set<Uuid> = emptySet(),
) {
    fun effectiveHeads(): Map<Uuid, List<ExpenseOperation>> = heads.toMutableMap().apply {
        for (operation in pending) applyExpenseOperation(this, operation)
    }

    fun project(): ExpenseLedger {
        val records = effectiveHeads().values.map { versions ->
            versions.maxWith(compareBy<ExpenseOperation>({ it.record.deleted }, { it.id.toString() })).record
        }
        return ExpenseLedger(
            records.filterIsInstance<ExpenseRecord.Item>().map { it.value },
            records.filterIsInstance<ExpenseRecord.Label>().map { it.value },
        ).also { it.validate() }
    }

    fun append(record: ExpenseRecord, mutation: ExpenseMutation): ExpenseSyncState {
        val versions = effectiveHeads()[record.metadata.id].orEmpty()
        require(versions.none { head -> pending.any { it.id == head.id && it.mutation in setOf(ExpenseMutation.RESOLVE, ExpenseMutation.RESTORE) } }) {
            "Wait for the expense review to finish before editing again."
        }
        require(versions.map { it.record }.distinct().size <= 1) { "Review competing expense versions before editing." }
        return copy(pending = pending + ExpenseOperation(Uuid.random(), versions.map { it.id }.toSet(), mutation, record))
    }

    fun acknowledge(receipt: CommittedExpense): ExpenseSyncState {
        require(pending.singleOrNull { it.id == receipt.operation.id } == receipt.operation)
        return if (receipt.sequence <= cursor) copy(pending = pending.filterNot { it.id == receipt.operation.id })
        else copy(acknowledged = acknowledged + receipt.operation.id)
    }

    fun replay(entries: List<CommittedExpense>): ExpenseSyncState {
        var next = this
        for (entry in entries) {
            require(entry.sequence == next.cursor + 1) { "Expense feed has a gap." }
            val operation = entry.operation
            val queued = next.pending.find { it.id == operation.id }
            require(queued == null || queued == operation) { "Expense operation identity changed." }
            next = next.copy(cursor = entry.sequence, heads = next.heads.toMutableMap().apply { applyExpenseOperation(this, operation) },
                pending = next.pending.filterNot { it.id == operation.id && it.id in next.acknowledged },
                acknowledged = next.acknowledged - operation.id)
        }
        return next
    }
}

private fun applyExpenseOperation(heads: MutableMap<Uuid, List<ExpenseOperation>>, operation: ExpenseOperation) {
    val key = operation.record.metadata.id
    val current = heads[key].orEmpty()
    if (current.none { it.id == operation.id }) heads[key] = current.filterNot { it.id in operation.parents } + operation
}

fun ExpenseLedger.syncRecords(): List<ExpenseRecord> =
    labels.map { ExpenseRecord.Label(it) } + expenses.map { ExpenseRecord.Item(it) }

fun ExpenseRecord.withMetadata(metadata: RecordMetadata): ExpenseRecord = when (this) {
    is ExpenseRecord.Item -> ExpenseRecord.Item(value.copy(metadata = metadata))
    is ExpenseRecord.Label -> ExpenseRecord.Label(value.copy(metadata = metadata))
}

fun ExpenseRecord.resolvedFrom(parents: List<ExpenseOperation>): ExpenseRecord {
    val revision = parents.maxOf { it.record.metadata.revision }
    val updated = parents.maxOf { it.record.metadata.updatedAt }
    return withMetadata(metadata.copy(revision = revision, updatedAt = updated).revised(maxOf(updated, Clock.System.now())))
}
