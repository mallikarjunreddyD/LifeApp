package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myjournal.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class BackupRoundTripTest {
    private fun database(file: File, driver: SQLiteDriver = BundledSQLiteDriver()) = Room.databaseBuilder<FoundationDatabase>(file.path)
        .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4).build()
    @Test fun completeArchiveRestoresExactEditableStateAcrossRealDatabaseReopening() = runTest {
        val directory=Files.createTempDirectory("lifeapp-roundtrip").toFile()
        val original=backupFixture()
        var source=database(File(directory,"source.db"));var destination=database(File(directory,"restored.db"))
        try {
            RoomSnapshotRepository(source).replace(original)
            val committed=RoomSnapshotRepository(source).capture()
            assertEquals(SnapshotJson.encode(original),SnapshotJson.encode(committed))
            val archive=File(directory,"backup.zip");BackupArchive.write(archive,committed)
            val validated=BackupArchive.read(archive)
            assertEquals(committed.counts(),validated.counts)
            RoomSnapshotRepository(destination).replace(validated.snapshot)
            source.close();destination.close()
            source=database(File(directory,"source.db"));destination=database(File(directory,"restored.db"))
            val restored=RoomSnapshotRepository(destination).capture()
            assertEquals(SnapshotJson.encode(RoomSnapshotRepository(source).capture()),SnapshotJson.encode(restored))
            original.strokes.zip(restored.strokes).forEach { (a,b) -> assertEquals(a,b);assertContentEquals(StrokePointCodec.encode(a.points),StrokePointCodec.encode(b.points)) }
            val book=restored.notebooks.first(); val pages=restored.notebookPages.filter { it.notebookId==book.metadata.id }
            val stroke=restored.strokes.first { it.pageId==pages.first().metadata.id }
            val revised=book.copy(metadata=book.metadata.revised(book.metadata.updatedAt))
            val repo=RoomNotebookRepository(destination)
            repo.apply(NotebookEdit(revised,pages,remove=setOf(stroke.metadata.id)))
            repo.apply(NotebookEdit(revised.copy(metadata=revised.metadata.revised(revised.metadata.updatedAt)),pages,put=listOf(stroke)))
            assertEquals(stroke,repo.loadStrokes(stroke.pageId).first { it.metadata.id==stroke.metadata.id })
        } finally { source.close();destination.close();directory.deleteRecursively() }
    }
    @Test fun structuredJsonAndNativeVectorArchivesPreserveAllPortableRecordFields() {
        val snapshot=backupFixture()
        assertEquals(snapshot,SnapshotJson.decode(SnapshotJson.encode(snapshot)))
        val file=File.createTempFile("lifeapp-vectors", ".zip")
        try {
            BackupArchive.write(file,snapshot,"vectors")
            assertEquals(snapshot,BackupArchive.read(file,"vectors").snapshot)
            assertFailsWith<IllegalArgumentException> { BackupArchive.read(file) }
            val markdown=MarkdownExport.encode(snapshot)
            assertTrue(markdown.contains("not converted to text"));assertTrue(markdown.contains("Research Notes"));assertTrue(markdown.contains("archived"))
        } finally { file.delete() }
    }
    @Test fun failedAndInterruptedReplacementRollBackAllDeletesAndInserts() = runTest {
        val directory=Files.createTempDirectory("lifeapp-rollback").toFile()
        var mode=0
        val bundled=BundledSQLiteDriver()
        val driver=object: SQLiteDriver {
            override fun open(fileName: String): SQLiteConnection {
                val connection=bundled.open(fileName)
                return object: SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement=connection.prepare(sql)
                        return object: SQLiteStatement by statement {
                            override fun step(): Boolean {
                                if(mode!=0 && sql.contains("INSERT",ignoreCase=true) && sql.contains("notebooks",ignoreCase=true)) {
                                    if(mode==1) error("Simulated storage write failure") else throw CancellationException("Interrupted restore")
                                }
                                return statement.step()
                            }
                        }
                    }
                }
            }
        }
        val db=database(File(directory,"app.db"),driver)
        try {
            val repo=RoomSnapshotRepository(db);val original=backupFixture();repo.replace(original)
            val before=SnapshotJson.encode(repo.capture())
            val incoming=backupFixture()
            for(failure in 1..2) {
                mode=failure
                assertFailsWith<Exception> { repo.replace(incoming) }
                mode=0;assertEquals(before,SnapshotJson.encode(repo.capture()))
            }
        } finally { mode=0;db.close();directory.deleteRecursively() }
    }
    @Test fun invalidParentDuplicateIdsAndDuplicateDatesNeverModifyCurrentData() = runTest {
        val directory=Files.createTempDirectory("lifeapp-invalid").toFile();val db=database(File(directory,"app.db"))
        try {
            val repo=RoomSnapshotRepository(db);val original=backupFixture();repo.replace(original)
            val before=SnapshotJson.encode(repo.capture())
            for(invalid in listOf(original.copy(notebooks=emptyList()),original.copy(strokes=original.strokes+original.strokes.first()),
                original.copy(journals=original.journals+original.journals.first()))) {
                assertFailsWith<IllegalArgumentException> { repo.replace(invalid) }
                assertEquals(before,SnapshotJson.encode(repo.capture()))
            }
        } finally { db.close();directory.deleteRecursively() }
    }
}
