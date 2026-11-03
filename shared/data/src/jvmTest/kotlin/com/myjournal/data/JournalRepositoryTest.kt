package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class JournalRepositoryTest {
    private val at = Instant.parse("2026-11-03T00:00:00.123456789Z")
    private val date = LocalDate(2026, 11, 3)
    private fun metadata() = RecordMetadata(Uuid.random(), at, at)
    private fun page(order: Int = 0, entry: Uuid = Uuid.random()) = JournalPage(metadata(), entry, order)
    private fun stroke(page: JournalPage, order: Long = 0) = InkStroke(metadata(), page.metadata.id,
        order, BrushTool.HIGHLIGHTER, 0x552859A8, 18f,
        listOf(InkPoint(10f, 10f, 0, 0.25f), InkPoint(20f, 40f, 30, 0.75f)))
    private fun memory() = Room.inMemoryDatabaseBuilder<FoundationDatabase>().setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun file(path: Path) = Room.databaseBuilder<FoundationDatabase>(path.toString()).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    @Test fun missingDatesStayAbsentUntilAnEditAndStrokeRoundTrips() = runTest {
        val db = memory()
        try {
            val repo = RoomJournalRepository(db)
            assertNull(repo.find(date)); assertNull(repo.observe(date).first())
            val page = page(); val stroke = stroke(page)
            repo.apply(PageEdit(date, page, listOf(stroke)))
            val entry = repo.find(date)!!
            assertEquals(date, entry.date); assertEquals(page.entryId, entry.metadata.id)
            assertEquals(listOf(stroke), repo.loadStrokes(page.metadata.id))
            assertEquals(1L, entry.metadata.revision)
            repo.apply(PageEdit(date, page, listOf(stroke))) // Safe retry after uncertain delivery.
            assertEquals(1L, repo.find(date)!!.metadata.revision)
        } finally { db.close() }
    }
    @Test fun multiplePagesDeleteAndExactRestoreIncrementRevisions() = runTest {
        val db = memory()
        try {
            val repo = RoomJournalRepository(db)
            val first = page(); val second = page(1, first.entryId)
            val a = stroke(first); val b = stroke(second)
            repo.apply(PageEdit(date, first, listOf(a))); repo.apply(PageEdit(date, second, listOf(b)))
            assertEquals(listOf(first.metadata.id, second.metadata.id), repo.find(date)!!.pages.map { it.metadata.id })
            repo.apply(PageEdit(date, first, remove = setOf(a.metadata.id)))
            assertTrue(repo.loadStrokes(first.metadata.id).isEmpty())
            val revision = repo.find(date)!!.metadata.revision
            repo.apply(PageEdit(date, first, listOf(a)))
            assertEquals(listOf(a), repo.loadStrokes(first.metadata.id))
            assertEquals(listOf(b), repo.loadStrokes(second.metadata.id))
            assertEquals(revision + 1, repo.find(date)!!.metadata.revision)
            assertEquals(3L, repo.find(date)!!.pages.first().metadata.revision)
        } finally { db.close() }
    }
    @Test fun rejectsDuplicateDateAndRollsBackConflictingStrokeReplacement() = runTest {
        val db = memory()
        try {
            val repo = RoomJournalRepository(db); val page = page()
            val a = stroke(page); val b = stroke(page, 1)
            repo.apply(PageEdit(date, page, listOf(a, b)))
            assertFailsWith<IllegalArgumentException> { repo.apply(PageEdit(date, page())) }
            assertFailsWith<IllegalArgumentException> {
                repo.apply(PageEdit(date, page, listOf(a.copy(width = 20f)), setOf(b.metadata.id)))
            }
            assertEquals(listOf(a, b), repo.loadStrokes(page.metadata.id))
            assertEquals(1L, repo.find(date)!!.metadata.revision)
            assertFailsWith<Exception> { repo.apply(PageEdit(date, page(0, page.entryId))) }
            assertEquals(1, repo.find(date)!!.pages.size)
        } finally { db.close() }
    }
    @Test fun restartRestoresEditableVectorsAcrossDatesAndPages() = runTest {
        val path = Files.createTempDirectory("lifeapp-restart-").resolve("journal.db")
        val page = page(); val next = page(1, page.entryId); val stroke = stroke(next)
        var db = file(path)
        try {
            RoomJournalRepository(db).apply(PageEdit(date, page))
            RoomJournalRepository(db).apply(PageEdit(date, next, listOf(stroke)))
            val another = page(); RoomJournalRepository(db).apply(PageEdit(LocalDate(2026, 11, 4), another, listOf(stroke(another))))
            db.close(); db = file(path)
            val repo = RoomJournalRepository(db)
            assertEquals(2, repo.find(date)!!.pages.size)
            assertEquals(listOf(stroke), repo.loadStrokes(next.metadata.id))
            assertNotNull(repo.find(LocalDate(2026, 11, 4)))
            repo.apply(PageEdit(date, next, remove = setOf(stroke.metadata.id)))
            repo.apply(PageEdit(date, next, listOf(stroke)))
            assertEquals(listOf(stroke), repo.loadStrokes(next.metadata.id))
        } finally { db.close(); path.parent.toFile().deleteRecursively() }
    }
    @Test fun migratesActualExportedPhaseZeroSchemaWithoutLosingMetadata() = runTest {
        val path = Files.createTempDirectory("lifeapp-migration-").resolve("journal.db")
        val schema = Files.readString(Path.of("schemas/com.myjournal.data.FoundationDatabase/1.json"))
        val sql = Regex("\"createSql\": \"(.*)\"").find(schema)!!.groupValues[1]
            .replace("${'$'}{TABLE_NAME}", "record_metadata")
        val hash = Regex("\"identityHash\": \"([^\"]+)\"").find(schema)!!.groupValues[1]
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.execSQL(sql)
            connection.execSQL("INSERT INTO record_metadata VALUES ('550e8400-e29b-41d4-a716-446655440000', '$at', '$at', 1, 7)")
            connection.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            connection.execSQL("INSERT INTO room_master_table VALUES(42, '$hash')")
            connection.execSQL("PRAGMA user_version=1")
        }
        val db = file(path)
        try {
            assertNull(RoomJournalRepository(db).observe(date).first())
            assertEquals(7L, db.metadata().observeAll().first().single().revision)
            val repo = RoomJournalRepository(db)
            val page = page(); repo.apply(PageEdit(date, page, listOf(stroke(page))))
            assertEquals(1, repo.find(date)!!.pages.size)
            assertEquals(7L, db.metadata().observeAll().first().single().revision)
        } finally { db.close(); path.parent.toFile().deleteRecursively() }
    }
}
