package com.myjournal.data

import androidx.room.*
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun meta(id: String, created: String, updated: String, revision: Long, schema: Int) =
    RecordMetadata(Uuid.parse(id), Instant.parse(created), Instant.parse(updated), schema, revision)

private fun PlannerRow.model() = MonthlyPlanner(meta(id, createdAt, updatedAt, revision, schemaVersion),
    CalendarLayout(JournalMonth.parse(month), layoutVersion, rows, width, headerHeight, cellHeight))
private fun PlannerStrokeRow.model() = InkStroke(meta(id, createdAt, updatedAt, revision, schemaVersion),
    Uuid.parse(surfaceId), strokeOrder, BrushTool.valueOf(tool), colorArgb, width, StrokePointCodec.decode(points), formatVersion)

class RoomPlannerRepository(private val database: FoundationDatabase) : MonthlyPlannerRepository {
    private val dao = database.planner()
    override suspend fun find(month: JournalMonth) = withContext(Dispatchers.Default) { dao.find(month.toString())?.model() }
    override suspend fun loadStrokes(surfaceId: Uuid): List<InkStroke> = withContext(Dispatchers.Default) {
        database.useReaderConnection { connection -> connection.deferredTransaction {
            val planner = dao.byId(surfaceId.toString())?.model() ?: return@deferredTransaction emptyList()
            dao.strokes(surfaceId.toString()).map { row ->
                val stroke = row.model()
                require(planner.layout.dateAt(stroke.points.first().x, stroke.points.first().y)?.day == row.cellDay)
                PlannerEdit(planner, listOf(stroke))
                stroke
            }
        } }
    }
    override suspend fun apply(edit: PlannerEdit) = withContext(Dispatchers.Default) {
        val planner = edit.planner
        val encoded = edit.put.map { stroke -> PlannerStrokeRow(stroke.metadata.id.toString(), planner.metadata.id.toString(),
            planner.layout.dateAt(stroke.points.first().x, stroke.points.first().y)!!.day, stroke.order, stroke.tool.name,
            stroke.colorArgb, stroke.width, StrokePointCodec.encode(stroke.points), stroke.metadata.createdAt.toString(),
            stroke.metadata.updatedAt.toString(), stroke.metadata.revision, stroke.metadata.schemaVersion, stroke.formatVersion) }
        database.useWriterConnection { connection -> connection.immediateTransaction {
            val old = dao.find(planner.month.toString())
            val id = planner.metadata.id.toString()
            if (old == null) {
                require(edit.remove.isEmpty())
                val layout = planner.layout
                dao.insert(PlannerRow(id, planner.month.toString(), layout.version, layout.rows, layout.width, layout.headerHeight, layout.cellHeight,
                    planner.metadata.createdAt.toString(), planner.metadata.updatedAt.toString(), 1, planner.metadata.schemaVersion))
            } else require(old.id == id && old.model().layout == planner.layout) { "Planner identity or saved geometry changed" }
            var changed = false
            edit.remove.forEach { target ->
                val previous = dao.stroke(target.toString())
                require(previous == null || previous.surfaceId == id)
                if (dao.deleteStroke(target.toString(), id) > 0) changed = true
            }
            encoded.forEach { row ->
                val previous = dao.stroke(row.id)
                if (previous == null) { dao.insertStroke(row); changed = true }
                else require(previous.model() == row.model() && previous.cellDay == row.cellDay) { "Stroke identity cannot be overwritten" }
            }
            if (changed && old != null) {
                check(old.revision < Long.MAX_VALUE)
                dao.revise(id, maxOf(Clock.System.now(), Instant.parse(old.updatedAt), planner.metadata.updatedAt).toString())
            }
        } }
    }
}

private fun HabitRow.model() = HabitDefinition(meta(id, createdAt, updatedAt, revision, schemaVersion),
    Uuid.parse(ledgerId), name, displayOrder, category, colorArgb, target, archived)
private fun CompletionRow.model() = HabitCompletion(meta(id, createdAt, updatedAt, revision, schemaVersion),
    Uuid.parse(habitId), LocalDate.parse(date), checked)
private fun sameFields(row: HabitRow, habit: HabitDefinition) = row.name == habit.name && row.displayOrder == habit.order &&
    row.category == habit.category && row.colorArgb == habit.colorArgb && row.target == habit.target && row.archived == habit.archived

class RoomHabitRepository(private val database: FoundationDatabase) : MonthlyHabitRepository {
    private val dao = database.habits()
    private suspend fun model(row: LedgerRow) = MonthlyHabitLedger(meta(row.id, row.createdAt, row.updatedAt, row.revision, row.schemaVersion),
        JournalMonth.parse(row.month), dao.definitions(row.id).map { it.model() }, dao.completions(row.id).map { it.model() })
    override suspend fun find(month: JournalMonth): MonthlyHabitLedger? = withContext(Dispatchers.Default) {
        database.useReaderConnection { connection -> connection.deferredTransaction { dao.find(month.toString())?.let { model(it) } } }
    }
    override suspend fun previousConfigured(month: JournalMonth): MonthlyHabitLedger? = withContext(Dispatchers.Default) {
        database.useReaderConnection { connection -> connection.deferredTransaction { dao.previous(month.toString())?.let { model(it) } } }
    }
    override suspend fun apply(edit: HabitLedgerEdit) = withContext(Dispatchers.Default) {
        val ledger = edit.ledger
        val id = ledger.metadata.id.toString()
        require(edit.definitions == null || edit.definitions == ledger.habits)
        require(edit.completions.map { it.habitId to it.date }.distinct().size == edit.completions.size)
        database.useWriterConnection { connection -> connection.immediateTransaction {
            val old = dao.find(ledger.month.toString())
            if (old == null) dao.insertLedger(LedgerRow(id, ledger.month.toString(), ledger.metadata.createdAt.toString(),
                ledger.metadata.updatedAt.toString(), 1, ledger.metadata.schemaVersion))
            else require(old.id == id) { "A different ledger already exists for this month" }
            val at = maxOf(Clock.System.now(), old?.let { Instant.parse(it.updatedAt) } ?: ledger.metadata.createdAt, ledger.metadata.updatedAt).toString()
            val existing = dao.definitions(id).associateBy { it.id }
            var changed = false
            edit.definitions?.let { definitions ->
                val ids = definitions.map { it.metadata.id.toString() }.toSet()
                require(existing.keys.all { it in ids }) { "Archive habits instead of dropping historical definitions" }
                if (definitions.any { existing[it.metadata.id.toString()]?.let { row -> !sameFields(row, it) } ?: true }) {
                    dao.stageOrders(id)
                    definitions.forEach { habit ->
                        require(habit.ledgerId == ledger.metadata.id && (habit.target?.let { it <= ledger.month.days } ?: true))
                        val global = dao.habitById(habit.metadata.id.toString())
                        require(global == null || global.ledgerId == id) { "Habit identity belongs to another ledger" }
                        val previous = existing[habit.metadata.id.toString()]
                        val different = previous == null || !sameFields(previous, habit)
                        if (different && previous != null) check(previous.revision < Long.MAX_VALUE)
                        dao.putHabit(HabitRow(habit.metadata.id.toString(), id, habit.name, habit.order, habit.category, habit.colorArgb,
                            habit.target, habit.archived, previous?.createdAt ?: habit.metadata.createdAt.toString(),
                            if (different) at else previous!!.updatedAt, if (previous == null) 1 else previous.revision + if (different) 1 else 0,
                            habit.metadata.schemaVersion))
                    }
                    changed = true
                }
            }
            val definitions = dao.definitions(id).associateBy { it.id }
            edit.completions.forEach { completion ->
                require(JournalMonth.from(completion.date) == ledger.month)
                val habit = requireNotNull(definitions[completion.habitId.toString()])
                require(!habit.archived) { "Archived habit cannot be toggled" }
                val global = dao.completionById(completion.metadata.id.toString())
                require(global == null || global.habitId == habit.id && global.date == completion.date.toString())
                val previous = dao.completion(habit.id, completion.date.toString())
                require(previous == null || previous.id == completion.metadata.id.toString()) { "Completion identity conflict" }
                if (previous == null || previous.checked != completion.checked) {
                    if (previous != null) check(previous.revision < Long.MAX_VALUE)
                    dao.putCompletion(CompletionRow(completion.metadata.id.toString(), habit.id, completion.date.toString(), completion.checked,
                        previous?.createdAt ?: completion.metadata.createdAt.toString(), at, (previous?.revision ?: 0) + 1, completion.metadata.schemaVersion))
                    changed = true
                }
            }
            if (changed && old != null) { check(old.revision < Long.MAX_VALUE); dao.revise(id, at) }
        } }
    }
}
