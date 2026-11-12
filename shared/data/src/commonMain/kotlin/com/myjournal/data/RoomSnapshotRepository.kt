package com.myjournal.data

import androidx.room.*
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

class RoomSnapshotRepository(private val database: FoundationDatabase) : SnapshotRepository {
    private val dao = database.snapshot()
    override suspend fun capture() = withContext(Dispatchers.Default) {
        // Finish Room initialization/migration before beginning the consistent read
        // transaction; opening an old database from an outer read can stall upgrade.
        dao.metadata()
        database.useReaderConnection { connection -> connection.deferredTransaction {
            readSnapshot()
        } }
    }
    private suspend fun readSnapshot(): JournalSnapshot {
        val pages = dao.pages().map { JournalPage(notebookMeta(it.id,it.createdAt,it.updatedAt,it.revision,it.schemaVersion), Uuid.parse(it.entryId),it.pageOrder,it.width,it.height,PaperTemplate.valueOf(it.paper),it.archived) }
        val entries = dao.entries().map { JournalEntry(notebookMeta(it.id,it.createdAt,it.updatedAt,it.revision,it.schemaVersion), LocalDate.parse(it.date),pages.filter { p -> p.entryId.toString() == it.id }.sortedBy { p -> p.order }) }
        require(entries.sumOf { it.pages.size } == pages.size) { "Orphan journal pages in database" }
        val planners = dao.planners().map { MonthlyPlanner(notebookMeta(it.id,it.createdAt,it.updatedAt,it.revision,it.schemaVersion),CalendarLayout(JournalMonth.parse(it.month),it.layoutVersion,it.rows,it.width,it.headerHeight,it.cellHeight)) }
        val definitions = dao.habits().map { HabitDefinition(notebookMeta(it.id,it.createdAt,it.updatedAt,it.revision,it.schemaVersion),Uuid.parse(it.ledgerId),it.name,it.displayOrder,it.category,it.colorArgb,it.target,it.archived) }
        val completions = dao.completions().map { HabitCompletion(notebookMeta(it.id,it.createdAt,it.updatedAt,it.revision,it.schemaVersion),Uuid.parse(it.habitId),LocalDate.parse(it.date),it.checked) }
        val ledgers = dao.ledgers().map { row ->
            val habits = definitions.filter { it.ledgerId.toString() == row.id }.sortedBy { it.order }
            MonthlyHabitLedger(notebookMeta(row.id,row.createdAt,row.updatedAt,row.revision,row.schemaVersion), JournalMonth.parse(row.month),habits,
                completions.filter { c -> habits.any { it.metadata.id == c.habitId } }.sortedWith(compareBy({it.date},{it.habitId.toString()})))
        }
        require(ledgers.sumOf { it.habits.size } == definitions.size && ledgers.sumOf { it.completions.size } == completions.size) { "Orphan habit data in database" }
        val ink = dao.journalInk().map { InkStroke(notebookMeta(it.id,it.createdAt,it.updatedAt,it.revision,it.schemaVersion),Uuid.parse(it.pageId),it.strokeOrder,BrushTool.valueOf(it.tool),it.colorArgb,it.width,StrokePointCodec.decode(it.points),it.formatVersion) } +
            dao.plannerInk().map { row ->
                val stroke = InkStroke(notebookMeta(row.id,row.createdAt,row.updatedAt,row.revision,row.schemaVersion),Uuid.parse(row.surfaceId),row.strokeOrder,BrushTool.valueOf(row.tool),row.colorArgb,row.width,StrokePointCodec.decode(row.points),row.formatVersion)
                require(planners.single { it.metadata.id == stroke.pageId }.layout.dateAt(stroke.points.first().x,stroke.points.first().y)?.day == row.cellDay)
                stroke
            } + dao.bookInk().map { it.model() }
        val expenses = RoomExpenseRepository(database)
        return JournalSnapshot(dao.metadata().map { notebookMeta(it.id,it.createdAt,it.updatedAt,it.revision,it.schemaVersion) },entries,planners,ledgers,
            dao.books().map { it.model() },dao.bookPages().map { it.model() },ink.sortedBy { it.metadata.id.toString() },
            database.todos().all().map(TodoRow::model), RoomWorkoutRepository(database).capture(), expenses.capture(), expenses.syncState()).also { it.validate() }
    }

    suspend fun addEditableRecords(records: List<EditableImportRecord>) = applyEditableRecords(records, emptyMap())

    /** Compare reviewed local copies and update only the affected aggregates in one writer transaction. */
    suspend fun applyEditableRecords(records: List<EditableImportRecord>, expected: Map<EditableImportKey, JournalSnapshot>) =
        withContext(Dispatchers.Default) {
            require(records.map { it.key }.distinct().size == records.size)
            require(expected.keys.all { key -> records.any { it.key == key } })
            require(records.all { it.snapshot.editableImportRecords().single().let { copy ->
                copy.key == it.key && copy.snapshot.canonical() == it.snapshot.canonical()
            } })
            val incoming = records.map { it.snapshot }
            dao.metadata()
            database.useWriterConnection { connection -> connection.immediateTransaction {
                val existing = readSnapshot()
                val byKey = existing.editableImportRecords().associateBy { it.key }
                for (record in records) {
                    require(byKey[record.key]?.snapshot?.canonical() == expected[record.key]?.canonical()) {
                        "Android records changed since the review."
                    }
                }
                val remaining = byKey.filterKeys { key -> key !in expected }.values.map { it.snapshot }
                fun List<JournalSnapshot>.combine() = JournalSnapshot(
                    journals = flatMap { it.journals }, planners = flatMap { it.planners }, habits = flatMap { it.habits },
                    notebooks = flatMap { it.notebooks }, notebookPages = flatMap { it.notebookPages }, strokes = flatMap { it.strokes },
                )
                (remaining + incoming).combine().copy(metadata = existing.metadata, todos = existing.todos,
                    workoutLog = existing.workoutLog, expenseLedger = existing.expenseLedger, expenseSync = existing.expenseSync).validate()

                for (old in expected.values) {
                    old.journals.forEach { entry ->
                        entry.pages.forEach { dao.deleteJournalInk(it.metadata.id.toString()) }
                        dao.deleteJournalPages(entry.metadata.id.toString())
                        dao.deleteJournal(entry.metadata.id.toString())
                    }
                    old.planners.forEach {
                        dao.deletePlannerInk(it.metadata.id.toString())
                        dao.deletePlanner(it.metadata.id.toString())
                    }
                    old.habits.forEach { ledger ->
                        ledger.habits.forEach { dao.deleteHabitCompletions(it.metadata.id.toString()) }
                        dao.deleteHabits(ledger.metadata.id.toString())
                        dao.deleteHabitLedger(ledger.metadata.id.toString())
                    }
                    old.notebookPages.forEach { dao.deleteNotebookInk(it.metadata.id.toString()) }
                    old.notebooks.forEach {
                        dao.deleteNotebookPages(it.metadata.id.toString())
                        dao.deleteNotebook(it.metadata.id.toString())
                    }
                }
                insertRecords(incoming.combine())
            } }
        }

    override suspend fun replace(snapshot: JournalSnapshot) = withContext(Dispatchers.Default) {
        snapshot.validate()
        database.useWriterConnection { connection -> connection.immediateTransaction {
            RoomTodoRepository(database).replaceFromBackup(snapshot.todos)
            RoomWorkoutRepository(database).restore(snapshot.workoutLog)
            RoomExpenseRepository(database).restore(snapshot.expenseLedger, snapshot.expenseSync)
            dao.clearNotebookStrokeRow()
            dao.clearNotebookPageRow()
            dao.clearNotebookRow()
            dao.clearCompletionRow()
            dao.clearHabitRow()
            dao.clearLedgerRow()
            dao.clearPlannerStrokeRow()
            dao.clearPlannerRow()
            dao.clearStrokeRow()
            dao.clearPageRow()
            dao.clearEntryRow()
            dao.clearMetadataRow()

            insertRecords(snapshot)
        } }
    }

    private suspend fun insertRecords(snapshot: JournalSnapshot) {
        dao.insertMetadataRow(snapshot.metadata.map { MetadataRow(it.id.toString(),it.createdAt.toString(),it.updatedAt.toString(),it.schemaVersion,it.revision) })
        dao.insertEntryRow(snapshot.journals.map { EntryRow(it.metadata.id.toString(),it.date.toString(),it.metadata.createdAt.toString(),it.metadata.updatedAt.toString(),it.metadata.revision,it.metadata.schemaVersion) })
        val journalPages = snapshot.journals.flatMap { it.pages }
        dao.insertPageRow(journalPages.map { PageRow(it.metadata.id.toString(),it.entryId.toString(),it.order,it.width,it.height,it.paper.name,it.metadata.createdAt.toString(),it.metadata.updatedAt.toString(),it.metadata.revision,it.metadata.schemaVersion,it.archived) })
        val journalIds = journalPages.map { it.metadata.id }.toSet()
        dao.insertStrokeRow(snapshot.strokes.filter { it.pageId in journalIds }.map { StrokeRow(it.metadata.id.toString(),it.pageId.toString(),it.order,it.tool.name,it.colorArgb,it.width,StrokePointCodec.encode(it.points),it.metadata.createdAt.toString(),it.metadata.updatedAt.toString(),it.metadata.revision,it.metadata.schemaVersion,it.formatVersion) })
        dao.insertPlannerRow(snapshot.planners.map { p -> val l=p.layout; val m=p.metadata
            PlannerRow(m.id.toString(),p.month.toString(),l.version,l.rows,l.width,l.headerHeight,l.cellHeight,m.createdAt.toString(),m.updatedAt.toString(),m.revision,m.schemaVersion) })
        val plannerIds = snapshot.planners.associateBy { it.metadata.id }
        dao.insertPlannerStrokeRow(snapshot.strokes.filter { it.pageId in plannerIds }.map { s -> val m=s.metadata
            PlannerStrokeRow(m.id.toString(),s.pageId.toString(),plannerIds.getValue(s.pageId).layout.dateAt(s.points.first().x,s.points.first().y)!!.day,s.order,s.tool.name,s.colorArgb,s.width,StrokePointCodec.encode(s.points),m.createdAt.toString(),m.updatedAt.toString(),m.revision,m.schemaVersion,s.formatVersion) })
        dao.insertLedgerRow(snapshot.habits.map { val m=it.metadata; LedgerRow(m.id.toString(),it.month.toString(),m.createdAt.toString(),m.updatedAt.toString(),m.revision,m.schemaVersion) })
        dao.insertHabitRow(snapshot.habits.flatMap { it.habits }.map { val m=it.metadata; HabitRow(m.id.toString(),it.ledgerId.toString(),it.name,it.order,it.category,it.colorArgb,it.target,it.archived,m.createdAt.toString(),m.updatedAt.toString(),m.revision,m.schemaVersion) })
        dao.insertCompletionRow(snapshot.habits.flatMap { it.completions }.map { val m=it.metadata; CompletionRow(m.id.toString(),it.habitId.toString(),it.date.toString(),it.checked,m.createdAt.toString(),m.updatedAt.toString(),m.revision,m.schemaVersion) })
        dao.insertNotebookRow(snapshot.notebooks.map { it.row() })
        dao.insertNotebookPageRow(snapshot.notebookPages.map { it.row() })
        val bookIds = snapshot.notebookPages.map { it.metadata.id }.toSet()
        dao.insertNotebookStrokeRow(snapshot.strokes.filter { it.pageId in bookIds }.map { it.notebookRow() })
    }
}
