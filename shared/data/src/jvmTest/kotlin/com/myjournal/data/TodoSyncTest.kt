package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant

class TodoSyncTest {
    private val deadline = LocalDate(2026, 11, 12)
    private val fixedInstant = Instant.parse("2026-11-09T10:00:00Z")
    private val clock = object : Clock { override fun now() = fixedInstant }

    private fun open(file: File, driver: SQLiteDriver = BundledSQLiteDriver()) =
        Room.databaseBuilder<FoundationDatabase>(file.path)
            .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()

    private class FakeCloud : TodoCloud {
        val operations = linkedMapOf<kotlin.uuid.Uuid, String>()
        var loseAcknowledgment = false
        var failRead = false

        override suspend fun put(operation: TodoOperation) {
            val payload = TodoOperationJson.encode(operation)
            val previous = operations.put(operation.id, payload)
            check(previous == null || previous == payload)
            if (loseAcknowledgment) {
                loseAcknowledgment = false
                error("Connection dropped after commit")
            }
        }

        override suspend fun read(): List<TodoOperation> {
            check(!failRead) { "Download interrupted" }
            return operations.values.reversed().map(TodoOperationJson::decode)
        }
    }

    @Test fun isolatedClientsConvergeAndRetainConcurrentVersionsAndTombstones() = runTest {
        val directory = Files.createTempDirectory("todo-sync").toFile()
        val leftDb = open(File(directory, "left.db"))
        val rightDb = open(File(directory, "right.db"))
        try {
            val left = RoomTodoRepository(leftDb, clock)
            val right = RoomTodoRepository(rightDb, clock)
            val cloud = FakeCloud()
            val pushLeft = TodoSyncCoordinator(left, cloud)
            val pushRight = TodoSyncCoordinator(right, cloud)
            val first = left.add("One", deadline)
            pushLeft.synchronize()
            pushRight.synchronize()
            assertEquals(left.observe().first(), right.observe().first())

            val second = right.add("Two", deadline)
            left.setCompleted(first, true)
            pushRight.synchronize()
            pushLeft.synchronize()
            pushRight.synchronize()
            assertEquals(setOf(first, second), right.observe().first().map { it.metadata.id }.toSet())
            assertEquals(fixedInstant, right.observe().first().first { it.metadata.id == first }.completedAt)

            left.edit(first, "Left edit", deadline)
            right.edit(first, "Right edit", deadline)
            pushLeft.synchronize()
            pushRight.synchronize()
            pushLeft.synchronize()
            val conflict = left.conflicts(first)
            assertEquals(setOf("Left edit", "Right edit"), conflict.map { it.record.title }.toSet())
            val ids = conflict.map { it.id }.toSet()
            left.resolve(first, conflict.single { it.record.title == "Right edit" }.id, ids)
            pushLeft.synchronize()
            pushRight.synchronize()
            assertTrue(right.conflicts(first).isEmpty())

            left.delete(first)
            right.edit(first, "Offline edit", deadline)
            pushLeft.synchronize()
            pushRight.synchronize()
            pushLeft.synchronize()
            assertEquals(listOf(second), left.observe().first().map { it.metadata.id })
            assertEquals(listOf(second), right.observe().first().map { it.metadata.id })
            assertEquals(setOf(false, true), right.conflicts(first).map { it.record.deleted }.toSet())
            val deletion = right.conflicts(first)
            assertFailsWith<IllegalArgumentException> {
                right.resolve(first, deletion.single { !it.record.deleted }.id, deletion.map { it.id }.toSet())
            }
        } finally {
            leftDb.close()
            rightDb.close()
            directory.deleteRecursively()
        }
    }

    @Test fun outboxSurvivesReopenAndLostAcknowledgmentWithoutLogicalDuplicates() = runTest {
        val directory = Files.createTempDirectory("todo-retry").toFile()
        val file = File(directory, "device.db")
        var db = open(file)
        try {
            val first = RoomTodoRepository(db, clock)
            assertFailsWith<IllegalArgumentException> { first.add("  ", deadline) }
            val id = first.add("Retry safely", deadline)
            first.setCompleted(id, true)
            first.setCompleted(id, false)
            val initial = first.pending()
            db.close()
            db = open(file)
            val repository = RoomTodoRepository(db, clock)
            assertEquals(initial, repository.pending())
            assertNull(repository.observe().first().single().completedAt)
            assertEquals(3L, repository.observe().first().single().metadata.revision)
            val cloud = FakeCloud().apply { loseAcknowledgment = true }
            val coordinator = TodoSyncCoordinator(repository, cloud)
            assertFailsWith<IllegalStateException> { coordinator.synchronize() }
            assertEquals(initial, repository.pending())
            cloud.failRead = true
            assertFailsWith<IllegalStateException> { coordinator.synchronize() }
            assertEquals(initial, repository.pending())
            cloud.failRead = false
            coordinator.synchronize()
            assertTrue(repository.pending().isEmpty())
            assertEquals(initial.map { it.id }.toSet(), cloud.operations.keys)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test fun failedRecordWriteRollsBackOutboxAndDeviceSequence() = runTest {
        val directory = Files.createTempDirectory("todo-rollback").toFile()
        var fail = true
        val bundled = BundledSQLiteDriver()
        val driver = object : SQLiteDriver {
            override fun open(fileName: String): SQLiteConnection {
                val source = bundled.open(fileName)
                return object : SQLiteConnection by source {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = source.prepare(sql)
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean {
                                if (fail && sql.startsWith("INSERT", ignoreCase = true) &&
                                    (sql.contains("`todos`") || sql.contains("\"todos\""))) error("Injected task save failure")
                                return statement.step()
                            }
                        }
                    }
                }
            }
        }
        val db = open(File(directory, "device.db"), driver)
        try {
            val repository = RoomTodoRepository(db, clock)
            assertFailsWith<IllegalStateException> { repository.add("Retry me", deadline) }
            assertTrue(repository.observe().first().isEmpty())
            assertTrue(repository.pending().isEmpty())
            fail = false
            repository.add("Retry me", deadline)
            assertEquals(1L, repository.pending().single().sequence)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test fun currentBackupRetainsActiveAndDeletedTasks() = runTest {
        val directory = Files.createTempDirectory("todo-archive").toFile()
        val db = open(File(directory, "device.db"))
        try {
            val tasks = RoomTodoRepository(db, clock)
            tasks.add("Current task", deadline)
            tasks.delete(tasks.add("Deleted task", deadline))
            val snapshots = RoomSnapshotRepository(db)
            val original = snapshots.capture()
            val archive = File(directory, "backup.zip")
            BackupArchive.write(archive, original)
            val decoded = BackupArchive.read(archive).snapshot
            assertEquals(original, decoded)
            assertEquals(1, decoded.todos.count { it.deleted })
            val legacy = requireNotNull(javaClass.getResource("/phase3/schema4-records.json")).readText()
            val old = SnapshotJson.decode(legacy)
            assertTrue(old.todos.isEmpty())
            snapshots.replace(old)
            assertTrue(tasks.observe().first().isEmpty())
            assertEquals(old.canonical(), snapshots.capture().canonical())
            val schemaFive = File(directory, "phase3_1.zip")
            requireNotNull(javaClass.getResourceAsStream("/phase3_1/schema5-backup.zip")).use { input ->
                schemaFive.outputStream().use { input.copyTo(it) }
            }
            val previous = BackupArchive.read(schemaFive).snapshot
            assertTrue(previous.todos.isEmpty())
            assertTrue(previous.journals.flatMap { it.pages }.any { it.archived })
            snapshots.replace(previous)
            assertEquals(previous.canonical(), snapshots.capture().canonical())
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}
