package com.myjournal.server

import com.myjournal.data.ExpenseSyncJson
import com.myjournal.domain.*
import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

internal class ExpenseStore(private val database: Database) {
    fun push(scope: SyncScope, operation: ExpenseOperation): CommittedExpense = database.transaction {
        lockEpoch(scope, write = true)
        val dataset = scope.dataset.sql()
        val record = operation.record
        val key = record.metadata.id.sql()
        val canonical = ExpenseSyncJson.operation(operation).toString()
        val receipt = query("SELECT sequence, payload FROM expense_operations WHERE dataset_id = ? AND id = ?",
            dataset, operation.id.sql()) { it.getLong(1) to it.getString(2) }.singleOrNull()
        if (receipt != null) {
            if (receipt.second != canonical) conflict("IDEMPOTENCY_MISMATCH", "Expense operation identity already exists.")
            return@transaction CommittedExpense(receipt.first, operation)
        }
        val current = heads(scope, record.metadata.id)
        val parents = operation.parents.map { id ->
            query("SELECT payload FROM expense_operations WHERE dataset_id = ? AND record_id = ? AND id = ?",
                dataset, key, id.sql()) { decode(it.getString(1)) }.singleOrNull()
                ?: conflict("MISSING_DEPENDENCY", "A parent expense version is missing.")
        }
        val existing = (current + parents).firstOrNull()?.record
        if (existing != null) {
            require(existing::class == record::class && existing.metadata.createdAt == record.metadata.createdAt)
            if (existing is ExpenseRecord.Label && record is ExpenseRecord.Label) require(existing.value.kind == record.value.kind)
        }
        if (parents.isNotEmpty()) {
            require(record.metadata.revision - 1 == parents.maxOf { it.record.metadata.revision })
            require(record.metadata.updatedAt >= parents.maxOf { it.record.metadata.updatedAt })
        }
        when (operation.mutation) {
            ExpenseMutation.PUT -> require(parents.none { it.record.deleted })
            ExpenseMutation.DELETE -> require(parents.none { it.record.deleted })
            ExpenseMutation.RESTORE -> {
                if (current.map { it.id }.toSet() != operation.parents || parents.single().record.deleted.not()) {
                    conflict("CONFLICT_CHANGED", "Review the current expense versions before restoring.")
                }
            }
            ExpenseMutation.RESOLVE -> {
                if (current.map { it.id }.toSet() != operation.parents) {
                    conflict("CONFLICT_CHANGED", "The reviewed expense versions changed.")
                }
                require(parents.any { it.record == record.withMetadata(it.record.metadata) }) { "Select an existing expense version." }
            }
        }
        if (record is ExpenseRecord.Item) {
            val references = listOf(record.value.categoryId to ExpenseLabelKind.CATEGORY,
                record.value.paymentMethodId to ExpenseLabelKind.PAYMENT)
            require(references.all { (id, kind) ->
                heads(scope, id).any { (it.record as? ExpenseRecord.Label)?.value?.kind == kind }
            }) { "Expense references must exist in this dataset." }
        }
        if (current.count { it.id !in operation.parents } >= 32) throw ApiFailure(413, "LIMIT_EXCEEDED", "Resolve competing expenses first.")
        val sequence = Math.addExact(last(scope), 1)
        execute("INSERT INTO expense_operations VALUES (?, ?, ?, ?, ?, ?)", dataset, operation.id.sql(), sequence,
            key, if (record is ExpenseRecord.Item) "expense" else "label", canonical)
        for (id in operation.parents) execute(
            "DELETE FROM expense_heads WHERE dataset_id = ? AND record_id = ? AND operation_id = ?", dataset, key, id.sql(),
        )
        execute("INSERT INTO expense_heads VALUES (?, ?, ?)", dataset, key, operation.id.sql())
        CommittedExpense(sequence, operation)
    }

    fun pull(scope: SyncScope, after: Long): Pair<List<CommittedExpense>, Boolean> = database.transaction {
        lockEpoch(scope)
        val last = last(scope)
        if (after > last) throw ApiFailure(410, "CURSOR_EXPIRED", "The expense cursor changed.")
        val entries = query(
            "SELECT sequence, payload FROM expense_operations WHERE dataset_id = ? AND sequence > ? ORDER BY sequence LIMIT 32",
            scope.dataset.sql(), after,
        ) { CommittedExpense(it.getLong(1), decode(it.getString(2))) }
        entries to ((entries.lastOrNull()?.sequence ?: after) < last)
    }

    fun inventory(scope: SyncScope): Long = database.transaction {
        lockEpoch(scope)
        query("SELECT count(DISTINCT record_id) FROM expense_heads WHERE dataset_id = ?", scope.dataset.sql()) { it.getLong(1) }.single()
    }

    private fun Connection.last(scope: SyncScope) = query(
        "SELECT coalesce(max(sequence), 0) FROM expense_operations WHERE dataset_id = ?", scope.dataset.sql(),
    ) { it.getLong(1) }.single()

    private fun Connection.heads(scope: SyncScope, id: Uuid) = query(
        """SELECT o.payload FROM expense_heads h JOIN expense_operations o
           ON o.dataset_id = h.dataset_id AND o.id = h.operation_id
           WHERE h.dataset_id = ? AND h.record_id = ?""", scope.dataset.sql(), id.sql(),
    ) { decode(it.getString(1)) }

    private fun decode(payload: String) = ExpenseSyncJson.operation(Json.parseToJsonElement(payload).jsonObject)
}
