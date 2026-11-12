package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlin.uuid.Uuid

/** Portable records; never a Room/SQLite file dump. Includes archived and unchecked data. */
data class JournalSnapshot(val metadata: List<RecordMetadata> = emptyList(), val journals: List<JournalEntry> = emptyList(),
    val planners: List<MonthlyPlanner> = emptyList(), val habits: List<MonthlyHabitLedger> = emptyList(),
    val notebooks: List<Notebook> = emptyList(), val notebookPages: List<NotebookPage> = emptyList(),
    val strokes: List<InkStroke> = emptyList(), val todos: List<Todo> = emptyList(), val workoutLog: WorkoutLog = WorkoutLog(),
    val expenseLedger: ExpenseLedger = ExpenseLedger()) {
    val records get() = metadata + journals.flatMap { listOf(it.metadata) + it.pages.map { p -> p.metadata } } +
        planners.map { it.metadata } + habits.flatMap { listOf(it.metadata) + it.habits.map { h -> h.metadata } + it.completions.map { c -> c.metadata } } +
        notebooks.map { it.metadata } + notebookPages.map { it.metadata } + strokes.map { it.metadata } + todos.map { it.metadata } + workoutLog.records + expenseLedger.records
    val dataSchemaVersion get() = when {
        expenseLedger.records.isNotEmpty() -> 10
        workoutLog.exercises.any { it.description != null } || workoutLog.sessions.any { session ->
            session.logged || session.deleted || session.routineId != null || session.entries.any { it.remarks != null }
        } -> 9
        workoutLog.sessions.any { it.averageHeartRateBpm != null || it.durationManuallyEntered } -> 8
        workoutLog.records.isNotEmpty() -> 7
        else -> 6
    }
    fun counts() = linkedMapOf("metadata" to metadata.size, "journalEntries" to journals.size,
        "journalPages" to journals.sumOf { it.pages.size }, "planners" to planners.size, "habitLedgers" to habits.size,
        "habitDefinitions" to habits.sumOf { it.habits.size }, "habitCompletions" to habits.sumOf { it.completions.size },
        "notebooks" to notebooks.size, "notebookPages" to notebookPages.size, "strokes" to strokes.size, "todos" to todos.size).apply {
        if (dataSchemaVersion >= 10) {
            put("expenses", expenseLedger.expenses.size)
            put("expenseCategories", expenseLedger.labels.count { it.kind == ExpenseLabelKind.CATEGORY })
            put("paymentMethods", expenseLedger.labels.count { it.kind == ExpenseLabelKind.PAYMENT })
        }
        if (dataSchemaVersion >= 7) {
            put("exerciseDefinitions", workoutLog.exercises.size)
            put("workoutRoutines", workoutLog.routines.size)
            put("workoutSessions", workoutLog.sessions.size)
            put("workoutEntries", workoutLog.sessions.sumOf { it.entries.size })
            put("workoutSets", workoutLog.sessions.sumOf { session -> session.entries.sumOf { it.sets.size } })
        }
    }
    fun validate() {
        workoutLog.validate()
        expenseLedger.validate()
        require(records.size <= 50_000) { "Backup exceeds 50,000 records" }
        require(strokes.sumOf { it.points.size.toLong() } <= 2_000_000) { "Backup exceeds 2,000,000 points" }
        val legacyIds = metadata.map { it.id }.toSet()
        require(records.filter { it.id !in legacyIds }.all { it.schemaVersion == 1 }) { "Unsupported record schema version" }
        require(records.map { it.id }.distinct().size == records.size) { "Duplicate record identity" }
        require(journals.map { it.date }.distinct().size == journals.size) { "Duplicate journal date" }
        require(planners.map { it.month }.distinct().size == planners.size)
        require(habits.map { it.month }.distinct().size == habits.size)
        require(notebookPages.all { page -> notebooks.any { it.metadata.id == page.notebookId } }) { "Orphan notebook page" }
        require(notebookPages.groupBy { it.notebookId }.values.all { pages -> pages.map { it.order }.distinct().size == pages.size })
        val surfaces = journals.flatMap { it.pages }.associate { it.metadata.id to it.inkSurface() } +
            planners.associate { it.metadata.id to it.surface } + notebookPages.associate { it.metadata.id to it.surface }
        require(strokes.groupBy { it.pageId }.values.all { list -> list.map { it.order }.distinct().size == list.size })
        strokes.forEach { stroke ->
            val surface = requireNotNull(surfaces[stroke.pageId]) { "Orphan stroke" }
            require(stroke.points.all { it.x in 0f..surface.width && it.y in 0f..surface.height }) { "Ink outside page" }
            surface.calendar?.let { layout ->
                val planner = planners.single { it.metadata.id == surface.id }
                PlannerEdit(planner, listOf(stroke))
                require(layout.version == 1)
            }
        }
    }
    val populated get() = records.isNotEmpty()
}

interface SnapshotRepository {
    suspend fun capture(): JournalSnapshot
    /** All replacement writes are one transaction. Caller must quiesce editor queues. */
    suspend fun replace(snapshot: JournalSnapshot)
}

data class ExportPage(val title: String, val surface: InkSurface, val strokes: List<InkStroke>)
fun JournalSnapshot.journalPdf(from: kotlinx.datetime.LocalDate, through: kotlinx.datetime.LocalDate): List<ExportPage> {
    require(from <= through)
    return journals.filter { it.date in from..through }.sortedBy { it.date }.flatMap { entry ->
        entry.pages.sortedBy { it.order }.map { page -> ExportPage("${entry.date} · Page ${page.order + 1}${if (page.archived) " (archived)" else ""}", page.inkSurface(), strokes.filter { it.pageId == page.metadata.id }.sortedBy { it.order }) }
    }
}
fun JournalSnapshot.plannerPdf(month: JournalMonth) = planners.filter { it.month == month }.map {
    ExportPage(it.month.toString(), it.surface, strokes.filter { s -> s.pageId == it.metadata.id }.sortedBy { s -> s.order })
}
fun JournalSnapshot.notebookPdf(id: Uuid, pageId: Uuid? = null): List<ExportPage> {
    val book = notebooks.single { it.metadata.id == id }
    return notebookPages.filter { it.notebookId == id && (pageId == null || it.metadata.id == pageId) }.sortedBy { it.order }.map {
        ExportPage("${book.name} · ${it.title ?: "Page ${it.order + 1}"}${if (it.archived) " (archived)" else ""}",
            it.surface, strokes.filter { s -> s.pageId == it.metadata.id }.sortedBy { s -> s.order })
    }
}

/** Stable wire ordering; semantic identities never depend on list/column position. */
fun JournalSnapshot.canonical() = copy(metadata=metadata.sortedBy { it.id.toString() },
    journals=journals.sortedBy { it.date }.map { it.copy(pages=it.pages.sortedBy { p -> p.order }) },
    planners=planners.sortedBy { it.month }, habits=habits.sortedBy { it.month }.map { ledger ->
        ledger.copy(habits=ledger.habits.sortedBy { it.order },completions=ledger.completions.sortedWith(compareBy({it.date},{it.habitId.toString()})))
    }, notebooks=notebooks.sortedBy { it.metadata.id.toString() },notebookPages=notebookPages.sortedBy { it.metadata.id.toString() },
    strokes=strokes.sortedBy { it.metadata.id.toString() }, todos=todos.sortedBy { it.metadata.id.toString() },
    workoutLog=workoutLog.canonical(), expenseLedger=expenseLedger.canonical())
