package com.myjournal.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
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

    override fun observe() = dao.observe().map(::decode).flowOn(Dispatchers.Default)

    override suspend fun seed() = write {
        val ledger = capture()
        if (ledger.records.isEmpty()) expenseDefaults().forEach { dao.save(it.row()) }
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
        dao.save(label.row())
    }

    override suspend fun setDeleted(id: Uuid, deleted: Boolean, previousRevision: Long) = write {
        val current = capture().expenses.single { it.metadata.id == id }
        if (current.deleted != deleted) {
            check(current.metadata.revision == previousRevision) { "This expense changed. Reopen it before continuing." }
            val metadata = current.metadata.revised(maxOf(current.metadata.updatedAt, Clock.System.now()))
            dao.save(current.copy(metadata = metadata, deleted = deleted).row())
        }
    }

    internal suspend fun capture() = decode(dao.all())

    internal suspend fun restore(ledger: ExpenseLedger) {
        ledger.validate()
        dao.clear()
        ledger.labels.forEach { dao.save(it.row()) }
        ledger.expenses.forEach { dao.save(it.row()) }
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
