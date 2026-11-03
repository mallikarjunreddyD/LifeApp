package com.myjournal.data

import androidx.room.*

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM record_metadata ORDER BY id") suspend fun metadata(): List<MetadataRow>
    @Insert suspend fun insertMetadataRow(rows: List<MetadataRow>)
    @Query("DELETE FROM record_metadata") suspend fun clearMetadataRow()
    @Query("SELECT * FROM journal_entries ORDER BY id") suspend fun entries(): List<EntryRow>
    @Insert suspend fun insertEntryRow(rows: List<EntryRow>)
    @Query("DELETE FROM journal_entries") suspend fun clearEntryRow()
    @Query("SELECT * FROM journal_pages ORDER BY id") suspend fun pages(): List<PageRow>
    @Insert suspend fun insertPageRow(rows: List<PageRow>)
    @Query("DELETE FROM journal_pages") suspend fun clearPageRow()
    @Query("SELECT * FROM ink_strokes ORDER BY id") suspend fun journalInk(): List<StrokeRow>
    @Insert suspend fun insertStrokeRow(rows: List<StrokeRow>)
    @Query("DELETE FROM ink_strokes") suspend fun clearStrokeRow()
    @Query("SELECT * FROM planner_pages ORDER BY id") suspend fun planners(): List<PlannerRow>
    @Insert suspend fun insertPlannerRow(rows: List<PlannerRow>)
    @Query("DELETE FROM planner_pages") suspend fun clearPlannerRow()
    @Query("SELECT * FROM planner_ink_strokes ORDER BY id") suspend fun plannerInk(): List<PlannerStrokeRow>
    @Insert suspend fun insertPlannerStrokeRow(rows: List<PlannerStrokeRow>)
    @Query("DELETE FROM planner_ink_strokes") suspend fun clearPlannerStrokeRow()
    @Query("SELECT * FROM habit_ledgers ORDER BY id") suspend fun ledgers(): List<LedgerRow>
    @Insert suspend fun insertLedgerRow(rows: List<LedgerRow>)
    @Query("DELETE FROM habit_ledgers") suspend fun clearLedgerRow()
    @Query("SELECT * FROM habit_definitions ORDER BY id") suspend fun habits(): List<HabitRow>
    @Insert suspend fun insertHabitRow(rows: List<HabitRow>)
    @Query("DELETE FROM habit_definitions") suspend fun clearHabitRow()
    @Query("SELECT * FROM habit_completions ORDER BY id") suspend fun completions(): List<CompletionRow>
    @Insert suspend fun insertCompletionRow(rows: List<CompletionRow>)
    @Query("DELETE FROM habit_completions") suspend fun clearCompletionRow()
    @Query("SELECT * FROM notebooks ORDER BY id") suspend fun books(): List<NotebookRow>
    @Insert suspend fun insertNotebookRow(rows: List<NotebookRow>)
    @Query("DELETE FROM notebooks") suspend fun clearNotebookRow()
    @Query("SELECT * FROM notebook_pages ORDER BY id") suspend fun bookPages(): List<NotebookPageRow>
    @Insert suspend fun insertNotebookPageRow(rows: List<NotebookPageRow>)
    @Query("DELETE FROM notebook_pages") suspend fun clearNotebookPageRow()
    @Query("SELECT * FROM notebook_ink_strokes ORDER BY id") suspend fun bookInk(): List<NotebookStrokeRow>
    @Insert suspend fun insertNotebookStrokeRow(rows: List<NotebookStrokeRow>)
    @Query("DELETE FROM notebook_ink_strokes") suspend fun clearNotebookStrokeRow()
}
