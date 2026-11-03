package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MonthRepositoryTest {
    private val month = JournalMonth(2026, 11)
    private fun memory() = Room.inMemoryDatabaseBuilder<FoundationDatabase>().setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun file(path: Path) = Room.databaseBuilder<FoundationDatabase>(path.toString()).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    private fun plan(month: JournalMonth = this.month) = MonthlyPlanner(newRecordMetadata(), CalendarLayout(month))
    private fun ink(plan: MonthlyPlanner, day: Int = 12, order: Long = 0): InkStroke {
        val box = plan.layout.cell(day)
        return InkStroke(newRecordMetadata(), plan.metadata.id, order, BrushTool.HIGHLIGHTER, 0x552859A8, 18f,
            listOf(InkPoint(box.left + 110, box.top + 70, 0, 0.25f), InkPoint(box.left + 140, box.top + 100, 10, 0.8f)))
    }
    private fun ledger(month: JournalMonth = this.month): MonthlyHabitLedger {
        val meta = newRecordMetadata()
        return MonthlyHabitLedger(meta, month, listOf(HabitDefinition(newRecordMetadata(), meta.id, "Exercise", 0),
            HabitDefinition(newRecordMetadata(), meta.id, "Reading", 1, "Mind", 0xFF2859A8, 20)))
    }

    @Test fun plannerReopensEditableStrokesWithMonthAndCellIsolation() = runTest {
        val directory = Files.createTempDirectory("lifeapp-planner-"); val path = directory.resolve("app.db")
        var database = file(path)
        val november = plan(); val december = plan(month.next())
        val a = ink(november); val b = ink(november, 20, 1); val c = ink(december)
        try {
            var repo = RoomPlannerRepository(database)
            assertNull(repo.find(month))
            repo.apply(PlannerEdit(november, listOf(a, b))); repo.apply(PlannerEdit(december, listOf(c)))
            database.close(); database = file(path); repo = RoomPlannerRepository(database)
            assertEquals(november.layout, repo.find(month)!!.layout)
            assertEquals(listOf(a, b), repo.loadStrokes(november.metadata.id))
            assertEquals(listOf(c), repo.loadStrokes(december.metadata.id))
            assertNull(RoomJournalRepository(database).find(month.date(12)))
            repo.apply(PlannerEdit(november, remove = setOf(a.metadata.id)))
            repo.apply(PlannerEdit(november, listOf(a)))
            assertEquals(listOf(a, b), repo.loadStrokes(november.metadata.id))
            val revision = repo.find(month)!!.metadata.revision
            repo.apply(PlannerEdit(november, listOf(a)))
            assertEquals(revision, repo.find(month)!!.metadata.revision)
        } finally { database.close(); directory.toFile().deleteRecursively() }
    }
    @Test fun plannerRejectsCrossCellCrossMonthAndJournalPageOwnership() = runTest {
        val db = memory()
        try {
            val planner = plan(); val stroke = ink(planner)
            val other = planner.layout.cell(20)
            assertFailsWith<IllegalArgumentException> { PlannerEdit(planner, listOf(stroke.copy(points = stroke.points + InkPoint(other.left + 10, other.top + 100, 20)))) }
            assertFailsWith<IllegalArgumentException> { PlannerEdit(plan(month.next()), listOf(stroke)) }
            val page = JournalPage(newRecordMetadata(), Uuid.random(), 0)
            RoomJournalRepository(db).apply(PageEdit(month.date(12), page))
            assertFailsWith<Exception> {
                db.planner().insertStroke(PlannerStrokeRow(Uuid.random().toString(), page.metadata.id.toString(), 12, 0, "PEN", 0xFF000000,
                    3f, StrokePointCodec.encode(stroke.points), stroke.metadata.createdAt.toString(), stroke.metadata.updatedAt.toString(), 1, 1, 1))
            }
            assertNotNull(RoomJournalRepository(db).find(month.date(12)))
        } finally { db.close() }
    }
    @Test fun renameReorderArchiveRestoreAndStatisticsPreserveCompletionIdentity() = runTest {
        val db = memory()
        try {
            val repo = RoomHabitRepository(db); var ledger = ledger()
            repo.apply(HabitLedgerEdit(ledger, ledger.habits))
            val reading = ledger.habits[1]
            val completion = HabitCompletion(newRecordMetadata(), reading.metadata.id, month.date(12), true)
            ledger = ledger.copy(completions = listOf(completion))
            repo.apply(HabitLedgerEdit(ledger, completions = listOf(completion)))
            val definitions = listOf(reading.copy(name = "Read Books", order = 0, target = 15), ledger.habits[0].copy(order = 1))
            ledger = ledger.copy(habits = definitions)
            repo.apply(HabitLedgerEdit(ledger, definitions))
            var loaded = repo.find(month)!!
            assertEquals("Read Books", loaded.activeHabits.first().name)
            assertEquals(completion.metadata.id, loaded.completions.single().metadata.id)
            assertEquals(1, loaded.count(reading.metadata.id))
            assertEquals(100f / 30, loaded.percentage(reading.metadata.id))
            ledger = loaded.copy(habits = loaded.habits.map { if (it.metadata.id == reading.metadata.id) it.copy(archived = true) else it })
            repo.apply(HabitLedgerEdit(ledger, ledger.habits))
            loaded = repo.find(month)!!
            assertEquals(1, loaded.activeHabits.size); assertTrue(loaded.checked(reading.metadata.id, 12))
            ledger = loaded.copy(habits = loaded.habits.map { it.copy(archived = false) })
            repo.apply(HabitLedgerEdit(ledger, ledger.habits))
            assertEquals(2, repo.find(month)!!.activeHabits.size)
            assertEquals(completion.metadata.id, repo.find(month)!!.completions.single().metadata.id)
        } finally { db.close() }
    }
    @Test fun realQueuedRapidTogglesCopyAndDatabaseRestartPreserveIndependentMonths() = runTest {
        val directory = Files.createTempDirectory("lifeapp-habits-"); val path = directory.resolve("app.db")
        var db = file(path)
        try {
            val editor = HabitEditor(RoomHabitRepository(db), backgroundScope, month)
            editor.open(month); editor.state.first { !it.loading }
            editor.createBlank(); editor.saveHabit(null, "Reading", "Mind", 0xFF2859A8, 20)
            editor.state.first { it.pending == 0 || it.error != null }.also { assertNull(it.error) }
            val id = editor.state.value.ledger!!.activeHabits.single().metadata.id
            repeat(21) { editor.toggle(id, month.date(12)) }
            editor.state.first { it.pending == 0 || it.error != null }.also { assertNull(it.error) }
            editor.open(month.next()); editor.state.first { !it.loading }
            assertNull(editor.state.value.ledger)
            editor.copyPrevious(); editor.saveHabit(null, "December only", null, null, null)
            editor.state.first { it.pending == 0 || it.error != null }.also { assertNull(it.error) }
            db.close(); db = file(path)
            val repo = RoomHabitRepository(db)
            val original = repo.find(month)!!; val copy = repo.find(month.next())!!
            assertTrue(original.checked(id, 12)); assertEquals(1, original.completions.size)
            assertEquals(listOf("Reading"), original.activeHabits.map { it.name })
            assertEquals(listOf("Reading", "December only"), copy.activeHabits.map { it.name })
            assertTrue(copy.completions.isEmpty())
            assertNotEquals(id, copy.activeHabits.first().metadata.id)
            assertTrue(original.completions.single().metadata.revision >= 21)
        } finally { db.close(); directory.toFile().deleteRecursively() }
    }
    @Test fun duplicateHabitAndCompletionIdentitiesCannotMoveHistoricalDataAcrossLedgers() = runTest {
        val db = memory()
        try {
            val repo = RoomHabitRepository(db); val first = ledger(); val second = ledger(month.next())
            repo.apply(HabitLedgerEdit(first, first.habits))
            val moved = first.habits[0].copy(ledgerId = second.metadata.id, order = 0)
            val invalid = second.copy(habits = listOf(moved))
            assertFailsWith<IllegalArgumentException> { repo.apply(HabitLedgerEdit(invalid, invalid.habits)) }
            assertNull(repo.find(second.month)) // New ledger creation rolled back with the invalid edit.
            repo.apply(HabitLedgerEdit(second, second.habits))
            val done = HabitCompletion(newRecordMetadata(), first.habits[0].metadata.id, month.date(12), true)
            repo.apply(HabitLedgerEdit(first.copy(completions = listOf(done)), completions = listOf(done)))
            val bad = done.copy(habitId = second.habits[0].metadata.id, date = second.month.date(12))
            assertFailsWith<IllegalArgumentException> { repo.apply(HabitLedgerEdit(second.copy(completions = listOf(bad)), completions = listOf(bad))) }
            val duplicate = done.copy(metadata = newRecordMetadata())
            assertFailsWith<IllegalArgumentException> { repo.apply(HabitLedgerEdit(first.copy(completions = listOf(duplicate)), completions = listOf(duplicate))) }
            assertEquals(done.metadata.id, repo.find(month)!!.completions.single().metadata.id)
            assertTrue(repo.find(second.month)!!.completions.isEmpty())
        } finally { db.close() }
    }
    @Test fun schemaTwoMigrationPreservesExistingJournalIdsRevisionsAndVectorBytes() = runTest {
        val directory = Files.createTempDirectory("lifeapp-schema2-"); val path = directory.resolve("app.db")
        val schema = Files.readString(Path.of("schemas/com.myjournal.data.FoundationDatabase/2.json"))
        val entryId = Uuid.random(); val pageId = Uuid.random(); val strokeId = Uuid.random()
        val at = "2026-11-03T01:02:03.123456789Z"
        val points = listOf(InkPoint(120f, 150f, 0, 0.3f), InkPoint(180f, 190f, 30, 0.7f))
        val bytes = StrokePointCodec.encode(points)
        val hash = Regex("\"identityHash\": \"([^\"]+)\"").find(schema)!!.groupValues[1]
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            var table = ""
            for (line in schema.lines()) {
                Regex("\"tableName\": \"([^\"]+)\"").find(line)?.let { table = it.groupValues[1] }
                Regex("\"createSql\": \"([^\"]+)\"").find(line)?.let {
                    connection.execSQL(it.groupValues[1].replace("${'$'}{TABLE_NAME}", table))
                }
            }
            connection.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            connection.execSQL("INSERT INTO room_master_table VALUES(42, '$hash')")
            connection.execSQL("INSERT INTO journal_entries VALUES('$entryId', '2026-11-12', '$at', '$at', 7, 1)")
            connection.execSQL("INSERT INTO journal_pages VALUES('$pageId', '$entryId', 0, 1000, 1414, 'RULED', '$at', '$at', 5, 1)")
            connection.prepare("INSERT INTO ink_strokes VALUES(?, ?, 0, 'PEN', 4278190080, 3, ?, ?, ?, 3, 1, 1)").use {
                it.bindText(1, strokeId.toString()); it.bindText(2, pageId.toString()); it.bindBlob(3, bytes)
                it.bindText(4, at); it.bindText(5, at); it.step()
            }
            connection.execSQL("PRAGMA user_version=2")
        }
        val db = file(path)
        try {
            val repo = RoomJournalRepository(db)
            val entry = repo.find(month.date(12))!!
            assertEquals(entryId, entry.metadata.id); assertEquals(7L, entry.metadata.revision)
            assertEquals(pageId, entry.pages.single().metadata.id); assertEquals(5L, entry.pages.single().metadata.revision)
            val stroke = repo.loadStrokes(pageId).single()
            assertEquals(strokeId, stroke.metadata.id); assertEquals(3L, stroke.metadata.revision)
            assertEquals(Instant.parse(at), stroke.metadata.createdAt); assertEquals(points, stroke.points)
            assertContentEquals(bytes, db.journal().stroke(strokeId.toString())!!.points)
            RoomPlannerRepository(db).apply(PlannerEdit(plan().also { p -> assertNotEquals(pageId, p.metadata.id) }))
            val ledger = ledger(); RoomHabitRepository(db).apply(HabitLedgerEdit(ledger, ledger.habits))
            assertEquals(points, repo.loadStrokes(pageId).single().points)
        } finally { db.close(); directory.toFile().deleteRecursively() }
    }
}
