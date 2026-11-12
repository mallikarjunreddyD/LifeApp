package com.myjournal.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Entity(tableName = "local_expense_records")
data class ExpenseRow(@PrimaryKey val id: String, val kind: String, val payload: String)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM local_expense_records ORDER BY id")
    fun observe(): kotlinx.coroutines.flow.Flow<List<ExpenseRow>>

    @Query("SELECT * FROM local_expense_records ORDER BY id")
    suspend fun all(): List<ExpenseRow>

    @Query("SELECT * FROM local_expense_records WHERE id = 'sync'")
    suspend fun sync(): ExpenseRow?

    @Upsert
    suspend fun save(row: ExpenseRow)

    @Query("DELETE FROM local_expense_records")
    suspend fun clear()
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE local_expense_records (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL)",
        )
    }
}

class RoomExpenseRepository(private val database: FoundationDatabase) : ExpenseRepository {
    private val dao = database.expenses()

    override fun observe() = dao.observe().map(::decode).distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun seed() = write {
        val ledger = capture()
        if (ledger.records.isEmpty() && syncState() == null) expenseDefaults().forEach { dao.save(it.row()) }
    }

    override suspend fun saveExpense(expense: Expense, previousRevision: Long?) = write {
        val ledger = capture()
        val prior = ledger.expenses.find { it.metadata.id == expense.metadata.id }
        checkRevision(prior?.metadata, expense.metadata, previousRevision)
        check(prior?.deleted != true && !expense.deleted) { "Restore this expense before editing it." }
        for ((id, kind, old) in listOf(
            Triple(expense.categoryId, ExpenseLabelKind.CATEGORY, prior?.categoryId),
            Triple(expense.paymentMethodId, ExpenseLabelKind.PAYMENT, prior?.paymentMethodId),
        )) {
            require(ledger.labels.any { it.metadata.id == id && it.kind == kind && (!it.archived || id == old) }) {
                "Choose an available category and payment method."
            }
        }
        ledger.copy(expenses = ledger.expenses.filterNot { it.metadata.id == expense.metadata.id } + expense).validate()
        queue(ExpenseRecord.Item(expense), ExpenseMutation.PUT)
        dao.save(expense.row())
    }

    override suspend fun saveLabel(label: ExpenseLabel, previousRevision: Long?) = write {
        val ledger = capture()
        val prior = ledger.labels.find { it.metadata.id == label.metadata.id }
        checkRevision(prior?.metadata, label.metadata, previousRevision)
        require(prior == null || prior.kind == label.kind)
        if (!label.archived) {
            require(ledger.labels.none { it.metadata.id != label.metadata.id && it.kind == label.kind &&
                expenseNameKey(it.name) == expenseNameKey(label.name) }) { "This name already exists. Check archived items." }
        }
        ledger.copy(labels = ledger.labels.filterNot { it.metadata.id == label.metadata.id } + label).validate()
        queue(ExpenseRecord.Label(label), ExpenseMutation.PUT)
        dao.save(label.row())
    }

    override suspend fun setDeleted(id: Uuid, deleted: Boolean, previousRevision: Long) = write {
        val current = capture().expenses.single { it.metadata.id == id }
        if (current.deleted != deleted) {
            check(current.metadata.revision == previousRevision) { "This expense changed. Reopen it before continuing." }
            val metadata = current.metadata.revised(maxOf(current.metadata.updatedAt, Clock.System.now()))
            val record = current.copy(metadata = metadata, deleted = deleted)
            queue(ExpenseRecord.Item(record), if (deleted) ExpenseMutation.DELETE else ExpenseMutation.RESTORE)
            dao.save(record.row())
        }
    }

    fun observeSync() = dao.observe().map { rows ->
        rows.firstOrNull { it.id == "sync" }?.let(::state)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun syncState(): ExpenseSyncState? = dao.sync()?.let(::state)

    suspend fun bind(scope: SyncScope, origin: String, backedUp: ExpenseLedger) = write {
        check(syncState() == null) { "Expenses are already connected." }
        val local = capture()
        check(local.canonical() == backedUp.canonical()) { "Expenses changed since backup. Review again." }
        val operations = local.syncRecords().map { record ->
            ExpenseOperation(Uuid.random(), emptySet(), if (record.deleted) ExpenseMutation.DELETE else ExpenseMutation.PUT, record)
        }
        saveState(ExpenseSyncState(scope, origin, pending = operations))
    }

    suspend fun acknowledge(scope: SyncScope, receipt: CommittedExpense) = write {
        val state = requireNotNull(syncState())
        check(state.scope == scope)
        val next = state.acknowledge(receipt)
        if (receipt.sequence <= state.cursor) saveProjection(next) else saveState(next)
    }

    suspend fun replay(scope: SyncScope, cursor: Long, entries: List<CommittedExpense>) = write {
        val state = requireNotNull(syncState())
        check(state.scope == scope && state.cursor == cursor)
        if (entries.isNotEmpty()) saveProjection(state.replay(entries))
    }

    suspend fun rejectResolution(scope: SyncScope, id: Uuid) = write {
        val state = requireNotNull(syncState())
        check(state.scope == scope)
        val rejected = state.pending.single { it.id == id }
        check(rejected.mutation in setOf(ExpenseMutation.RESOLVE, ExpenseMutation.RESTORE))
        check(state.pending.none { id in it.parents })
        saveProjection(state.copy(pending = state.pending - rejected, acknowledged = state.acknowledged - id))
    }

    suspend fun resolve(id: Uuid, selected: Uuid, reviewed: Set<Uuid>) = write {
        val state = requireNotNull(syncState())
        val versions = state.effectiveHeads()[id].orEmpty()
        check(versions.map { it.id }.toSet() == reviewed && versions.size >= 2) { "Expense versions changed." }
        val chosen = versions.single { it.id == selected }
        saveProjection(state.copy(pending = state.pending + ExpenseOperation(Uuid.random(), reviewed,
            ExpenseMutation.RESOLVE, chosen.record.resolvedFrom(versions))))
    }

    private suspend fun queue(record: ExpenseRecord, mutation: ExpenseMutation) {
        syncState()?.let { saveState(it.append(record, mutation)) }
    }

    private suspend fun saveProjection(state: ExpenseSyncState) {
        val ledger = state.project()
        ledger.labels.forEach { dao.save(it.row()) }
        ledger.expenses.forEach { dao.save(it.row()) }
        saveState(state)
    }

    private suspend fun saveState(value: ExpenseSyncState) =
        dao.save(ExpenseRow("sync", "sync", ExpenseSyncJson.state(value).toString()))

    private fun state(row: ExpenseRow) = ExpenseSyncJson.state(Json.parseToJsonElement(row.payload).jsonObject)

    internal suspend fun capture() = decode(dao.all())

    internal suspend fun restore(ledger: ExpenseLedger, backupSync: ExpenseSyncState?) {
        ledger.validate()
        if (syncState() != null) {
            check(capture().canonical() == ledger.canonical()) {
                "This backup changes connected expenses. Current expenses and pending changes were kept."
            }
            return
        }
        dao.clear()
        ledger.labels.forEach { dao.save(it.row()) }
        ledger.expenses.forEach { dao.save(it.row()) }
        backupSync?.let { saveState(it) }
    }

    private suspend fun write(action: suspend () -> Unit) {
        // Initialize Room before taking an outer transaction on old databases.
        dao.all()
        database.useWriterConnection { it.immediateTransaction { action() } }
    }

    private fun checkRevision(prior: RecordMetadata?, next: RecordMetadata, expected: Long?) {
        check(prior?.revision == expected) { "This record changed. Reopen it before saving." }
        require(next.schemaVersion == 1)
        if (prior == null) require(next.revision == 1L)
        else require(prior.revised(next.updatedAt) == next)
    }

    private fun decode(rows: List<ExpenseRow>): ExpenseLedger {
        val expenses = mutableListOf<Expense>()
        val labels = mutableListOf<ExpenseLabel>()
        for (row in rows) {
            if (row.id == "sync") continue
            val json = Json.parseToJsonElement(row.payload).jsonObject
            val id = when (row.kind) {
                "expense" -> json.expense().also(expenses::add).metadata.id
                "label" -> json.expenseLabel().also(labels::add).metadata.id
                else -> error("Unknown expense record kind.")
            }
            check(id.toString() == row.id)
        }
        return ExpenseLedger(expenses, labels).also { it.validate() }
    }

    private fun Expense.row() = ExpenseRow(metadata.id.toString(), "expense", expenseJson().toString())
    private fun ExpenseLabel.row() = ExpenseRow(metadata.id.toString(), "label", expenseJson().toString())
}
