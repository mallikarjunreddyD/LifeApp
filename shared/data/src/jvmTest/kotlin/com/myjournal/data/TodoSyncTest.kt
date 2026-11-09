package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build()

    private class FakeServer : TodoSyncTransport {
        override val scope = SyncScope(
            kotlin.uuid.Uuid.parse("50000000-0000-4000-8000-000000000001"),
            kotlin.uuid.Uuid.parse("50000000-0000-4000-8000-000000000002"),
        )
        val operations = linkedMapOf<kotlin.uuid.Uuid, Pair<Long, String>>()
        var loseAcknowledgment = false
        var failRead = false
        var pageSize = TodoSyncPage.MAX_ENTRIES

        override suspend fun submit(operation: TodoOperation): TodoAcknowledgment {
            val payload = TodoOperationJson.encode(operation)
            val previous = operations[operation.id]
            check(previous == null || previous.second == payload)
            check(operation.parents.all { it in operations })
            val stored = previous ?: (operations.size.toLong() + 1 to payload).also { operations[operation.id] = it }
            if (loseAcknowledgment) {
                loseAcknowledgment = false
                error("Connection dropped after commit")
            }
            return TodoAcknowledgment(scope, CommittedTodo(stored.first, TodoOperationJson.decode(stored.second)))
        }

        override suspend fun pull(after: SyncCheckpoint): TodoSyncPage {
            check(!failRead) { "Download interrupted" }
            require(after.scope == scope && after.sequence <= operations.size)
            val entries = operations.values.drop(after.sequence.toInt()).take(pageSize)
                .map { CommittedTodo(it.first, TodoOperationJson.decode(it.second)) }
            return TodoSyncPage(scope, entries, after.sequence + entries.size < operations.size)
        }
    }

    @Test fun isolatedClientsConvergeAndRetainConcurrentVersionsAndTombstones() = runTest {
        val directory = Files.createTempDirectory("todo-sync").toFile()
        val leftDb = open(File(directory, "left.db"))
        val rightDb = open(File(directory, "right.db"))
        try {
            val left = RoomTodoRepository(leftDb, clock)
            val right = RoomTodoRepository(rightDb, clock)
            val cloud = FakeServer()
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

    @Test fun firstBindingChecksBackupAndRemoteHistoryBeforePersistingOrigin() = runTest {
        val directory = Files.createTempDirectory("todo-binding").toFile()
        val file = File(directory, "device.db")
        val cloud = FakeServer()
        val endpoint = ServerEndpoint.parse("https://journal.example")
        var db = open(file)
        try {
            val repository = RoomTodoRepository(db, clock)
            repository.add("First offline task", deadline)
            val backup = repository.localTasks()
            repository.add("Saved after backup", deadline)
            assertFailsWith<IllegalArgumentException> {
                repository.bindEndpoint(cloud.scope, endpoint, backup, remoteHasHistory = false)
            }
            assertFailsWith<IllegalArgumentException> {
                repository.bindEndpoint(cloud.scope, endpoint, repository.localTasks(), remoteHasHistory = true)
            }
            assertNull(repository.checkpoint())
            assertNull(repository.boundOrigin())

            repository.bindEndpoint(cloud.scope, endpoint, repository.localTasks(), remoteHasHistory = false)
            TodoSyncCoordinator(repository, cloud).synchronize()
            db.close()
            db = open(file)
            val reopened = RoomTodoRepository(db, clock)
            assertEquals(endpoint.origin, reopened.boundOrigin())
            assertEquals(SyncCheckpoint(cloud.scope, 2), reopened.checkpoint())
            assertTrue(reopened.pending().isEmpty())
        } finally {
            db.close()
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
            val cloud = FakeServer().apply { loseAcknowledgment = true }
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
            db.close()
            db = open(file)
            val acknowledged = RoomTodoRepository(db, clock)
            assertTrue(acknowledged.pending().isEmpty())
            assertEquals(3L, acknowledged.bind(cloud.scope).sequence)
            TodoSyncCoordinator(acknowledged, cloud).synchronize()
            assertEquals(3, cloud.operations.size)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test fun pageFailureRollsBackOperationsAndCheckpointAndRestoreIsBlockedAfterBinding() = runTest {
        val directory = Files.createTempDirectory("todo-checkpoint").toFile()
        val file = File(directory, "receiver.db")
        val authorDb = open(File(directory, "author.db"))
        var db = open(file)
        try {
            val author = RoomTodoRepository(authorDb, clock)
            val id = author.add("First", deadline)
            author.edit(id, "Revised", deadline)
            val (root, edit) = author.pending()
            val server = FakeServer()
            val repo = RoomTodoRepository(db, clock)
            val initial = repo.bind(server.scope)
            val valid = CommittedTodo(1, root)
            val missing = edit.copy(parents = setOf(kotlin.uuid.Uuid.random()))
            val invalid = TodoSyncPage(server.scope, listOf(valid, CommittedTodo(2, missing)), false)
            assertFailsWith<IllegalArgumentException> { repo.applyPage(initial, invalid) }
            assertFailsWith<IllegalArgumentException> {
                repo.applyPage(initial, TodoSyncPage(server.scope, listOf(CommittedTodo(2, edit), valid), false))
            }
            val wrongRecord = edit.copy(record = edit.record.copy(
                metadata = edit.record.metadata.copy(id = kotlin.uuid.Uuid.random()),
            ))
            assertFailsWith<IllegalArgumentException> {
                repo.applyPage(initial, TodoSyncPage(server.scope, listOf(valid, CommittedTodo(2, wrongRecord)), false))
            }
            assertEquals(initial, repo.bind(server.scope))
            assertTrue(repo.observe().first().isEmpty())
            author.pending().forEach { server.submit(it) }
            server.pageSize = 1
            val first = TodoSyncCoordinator(repo, server).synchronize()
            assertTrue(first.moreAvailable)
            assertEquals(1L, first.checkpoint.sequence)
            assertFailsWith<IllegalArgumentException> { repo.applyPage(initial, server.pull(initial)) }
            db.close()
            db = open(file)
            val reopened = RoomTodoRepository(db, clock)
            assertEquals(first.checkpoint, reopened.bind(server.scope))
            assertFailsWith<IllegalArgumentException> { reopened.bind(server.scope.copy(epoch = kotlin.uuid.Uuid.random())) }
            TodoSyncCoordinator(reopened, server).synchronize()
            assertEquals("Revised", reopened.observe().first().single().title)
            assertEquals(2L, reopened.bind(server.scope).sequence)
            assertFailsWith<IllegalStateException> { RoomSnapshotRepository(db).replace(JournalSnapshot()) }
            assertEquals("Revised", reopened.observe().first().single().title)
        } finally {
            db.close()
            authorDb.close()
            directory.deleteRecursively()
        }
    }

    @Test fun schemaSixMigrationPreservesTodoRecordsAndPendingOperationIdentity() = runTest {
        val directory = Files.createTempDirectory("todo-schema-six").toFile()
        val file = File(directory, "device.db")
        val db = open(file)
        val expected = try {
            val repository = RoomTodoRepository(db, clock)
            repository.add("Existing task", deadline)
            repository.pending().single()
        } finally { db.close() }
        val oldSchema = kotlinx.serialization.json.Json.parseToJsonElement(
            File("schemas/com.myjournal.data.FoundationDatabase/6.json").readText(),
        ) as kotlinx.serialization.json.JsonObject
        val identity = ((oldSchema.getValue("database") as kotlinx.serialization.json.JsonObject)
            .getValue("identityHash") as kotlinx.serialization.json.JsonPrimitive).content
        BundledSQLiteDriver().open(file.path).use { connection ->
            connection.execSQL("DROP TABLE todo_sync_checkpoint")
            connection.execSQL("DROP TABLE local_workout_records")
            connection.execSQL("ALTER TABLE todo_operations DROP COLUMN rejected")
            connection.prepare("UPDATE room_master_table SET identity_hash = ? WHERE id = 42").use {
                it.bindText(1, identity)
                it.step()
            }
            connection.execSQL("PRAGMA user_version = 6")
        }
        val migrated = open(file)
        try {
            val repository = RoomTodoRepository(migrated, clock)
            assertEquals(expected, repository.pending().single())
            assertEquals(expected.record, repository.observe().first().single())
            assertEquals(0L, repository.bind(FakeServer().scope).sequence)
        } finally {
            migrated.close()
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
