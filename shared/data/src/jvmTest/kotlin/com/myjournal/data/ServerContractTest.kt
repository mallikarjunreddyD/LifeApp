package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myjournal.domain.canonical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

class ServerContractTest {
    private fun fixture(name: String) = File("../../contracts/fixtures/$name.json").readText()

    @Test fun operationAndPagedResponsePreserveFullPrecisionAcrossClients() {
        val fixture = fixture("todo-operation")
        val operation = TodoOperationJson.decode(fixture)
        assertEquals(9_007_199_254_740_993L, operation.sequence)
        assertEquals(operation.sequence, operation.record.metadata.revision)
        assertEquals(Json.parseToJsonElement(fixture), Json.parseToJsonElement(TodoOperationJson.encode(operation)))
        val page = TodoServerJson.decodePage(fixture("sync-page"))
        assertEquals(operation, page.entries.single().operation)
        assertEquals(1L, page.entries.single().sequence)
        assertFailsWith<IllegalArgumentException> {
            TodoOperationJson.decode(fixture.replace("\"protocolVersion\": 1", "\"protocolVersion\": 2"))
        }
        assertFailsWith<IllegalArgumentException> {
            TodoOperationJson.decode(fixture.replace("\"payloadVersion\": 1", "\"payloadVersion\": 2"))
        }
        assertFailsWith<IllegalArgumentException> {
            TodoOperationJson.decode(fixture.replace("\"9007199254740993\"", "9007199254740993"))
        }
    }

    @Test fun phaseFourEncryptedArchiveRestoresWithoutChangingEditableVectors() = runTest {
        val archive = File.createTempFile("lifeapp-phase4-unsealed", ".zip")
        try {
            val input = requireNotNull(javaClass.getResourceAsStream("/phase4/schema6-envelope.bin")).use { it.readBytes() }
            val decoded = CloudBackupEnvelope.open(input, "fixture passphrase only".toCharArray())
            archive.writeBytes(decoded)
            val restored = BackupArchive.read(archive).snapshot
            val reference = requireNotNull(javaClass.getResource("/phase3_1/schema5-backup.zip"))
            val old = BackupArchive.read(File(reference.toURI())).snapshot
            assertEquals(old.copy(todos = restored.todos), restored)
            assertEquals("Archived backup task", restored.todos.single().title)
            assertTrue(restored.todos.single().deleted)
            assertTrue(restored.journals.flatMap { it.pages }.any { it.archived })
            val database = Room.inMemoryDatabaseBuilder<FoundationDatabase>().setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val repository = RoomSnapshotRepository(database)
                repository.replace(restored)
                assertEquals(restored.canonical(), repository.capture().canonical())
            } finally {
                database.close()
            }
            old.strokes.zip(restored.strokes).forEach { (before, after) ->
                assertContentEquals(StrokePointCodec.encode(before.points), StrokePointCodec.encode(after.points))
            }
        } finally {
            archive.delete()
        }
    }
}
