package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.myjournal.domain.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import kotlin.test.*

class ExpensePersistenceTest {
    @Test fun versionNineUpgradeAndPortableRestorePreserveRecordsAndTombstones() = runTest {
        val root = Files.createTempDirectory("expense-room").toFile()
        val file = root.resolve("journal.db")
        fun open(file: File) = Room.databaseBuilder<FoundationDatabase>(file.path)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_9_10).build()
        try {
            val workout = ExerciseDefinition(newRecordMetadata(), "Existing exercise")
            open(file).use { RoomWorkoutRepository(it).saveExercise(workout) }
            val identity = Json.parseToJsonElement(
                File("schemas/com.myjournal.data.FoundationDatabase/9.json").readText(),
            ).jsonObject.getValue("database").jsonObject.getValue("identityHash").jsonPrimitive.content
            BundledSQLiteDriver().open(file.path).use { connection ->
                connection.execSQL("DROP TABLE local_expense_records")
                connection.execSQL("UPDATE room_master_table SET identity_hash = '$identity' WHERE id = 42")
                connection.execSQL("PRAGMA user_version = 9")
            }
            val archive = root.resolve("recovery.zip")
            val oldArchive = root.resolve("pre-expense.zip")
            BackupArchive.write(oldArchive, JournalSnapshot(workoutLog = WorkoutLog(exercises = listOf(workout))))
            open(file).use { database ->
                val snapshots = RoomSnapshotRepository(database)
                assertEquals(workout, snapshots.capture().workoutLog.exercises.single())
                val expenses = RoomExpenseRepository(database)
                expenses.seed()
                expenses.seed()
                val defaults = expenses.observe().first().labels
                assertEquals(17, defaults.size)
                val category = ExpenseLabel(newRecordMetadata(), ExpenseLabelKind.CATEGORY, "École")
                expenses.saveLabel(category, null)
                assertFailsWith<IllegalArgumentException> {
                    expenses.saveLabel(ExpenseLabel(newRecordMetadata(), ExpenseLabelKind.CATEGORY, "école"), null)
                }
                val method = defaults.first { it.kind == ExpenseLabelKind.PAYMENT }
                val record = Expense(newRecordMetadata(), LocalDate(2024, 2, 29), "Train", 125075,
                    category.metadata.id, method.metadata.id, "Window seat")
                expenses.saveExpense(record, null)
                expenses.saveExpense(record.copy(metadata = newRecordMetadata()), null)
                val revised = record.copy(metadata = record.metadata.revised(record.metadata.updatedAt), amountMinor = 125076)
                expenses.saveExpense(revised, 1)
                expenses.saveLabel(category.copy(metadata = category.metadata.revised(category.metadata.updatedAt), archived = true), 1)
                expenses.setDeleted(record.metadata.id, true, 2)
                expenses.setDeleted(record.metadata.id, true, 2)
                assertFailsWith<IllegalStateException> { expenses.saveExpense(revised, 1) }
                val snapshot = snapshots.capture()
                BackupArchive.write(archive, snapshot)
                val restored = BackupArchive.read(archive).snapshot
                assertEquals(10, restored.dataSchemaVersion)
                assertEquals(snapshot.canonical(), restored.canonical())
                assertTrue(restored.expenseLedger.expenses.single { it.metadata.id == record.metadata.id }.deleted)
            }
            open(file).use { database ->
                val expenses = RoomExpenseRepository(database)
                val snapshot = RoomSnapshotRepository(database)
                val prior = expenses.observe().first().expenses.single { it.deleted }
                expenses.setDeleted(prior.metadata.id, false, prior.metadata.revision)
                assertEquals(125076L, expenses.observe().first().expenses.single { it.metadata.id == prior.metadata.id }.amountMinor)
                snapshot.replace(BackupArchive.read(oldArchive).snapshot)
                assertTrue(expenses.observe().first().expenses.isEmpty())
                assertEquals(workout, snapshot.capture().workoutLog.exercises.single())
            }
            open(root.resolve("receiver.db")).use { database ->
                val snapshots = RoomSnapshotRepository(database)
                val expected = BackupArchive.read(archive).snapshot
                snapshots.replace(expected)
                assertEquals(expected.canonical(), snapshots.capture().canonical())
            }
        } finally { root.deleteRecursively() }
    }
}
