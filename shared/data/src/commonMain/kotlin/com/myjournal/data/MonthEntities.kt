package com.myjournal.data

import androidx.room.*

@Entity(tableName = "planner_pages", indices = [Index(value = ["month"], unique = true)])
data class PlannerRow(@PrimaryKey val id: String, val month: String,
    val layoutVersion: Int, val rows: Int, val width: Float, val headerHeight: Float, val cellHeight: Float,
    val createdAt: String, val updatedAt: String, val revision: Long, val schemaVersion: Int)

@Entity(tableName = "planner_ink_strokes",
    foreignKeys = [ForeignKey(entity = PlannerRow::class, parentColumns = ["id"], childColumns = ["surfaceId"])],
    indices = [Index(value = ["surfaceId", "strokeOrder"], unique = true)])
data class PlannerStrokeRow(@PrimaryKey val id: String, val surfaceId: String, val cellDay: Int,
    val strokeOrder: Long, val tool: String, val colorArgb: Long, val width: Float, val points: ByteArray,
    val createdAt: String, val updatedAt: String, val revision: Long, val schemaVersion: Int, val formatVersion: Int)

@Entity(tableName = "habit_ledgers", indices = [Index(value = ["month"], unique = true)])
data class LedgerRow(@PrimaryKey val id: String, val month: String,
    val createdAt: String, val updatedAt: String, val revision: Long, val schemaVersion: Int)

@Entity(tableName = "habit_definitions",
    foreignKeys = [ForeignKey(entity = LedgerRow::class, parentColumns = ["id"], childColumns = ["ledgerId"])],
    indices = [Index(value = ["ledgerId", "displayOrder"], unique = true)])
data class HabitRow(@PrimaryKey val id: String, val ledgerId: String, val name: String, val displayOrder: Int,
    val category: String?, val colorArgb: Long?, val target: Int?, val archived: Boolean,
    val createdAt: String, val updatedAt: String, val revision: Long, val schemaVersion: Int)

@Entity(tableName = "habit_completions",
    foreignKeys = [ForeignKey(entity = HabitRow::class, parentColumns = ["id"], childColumns = ["habitId"])],
    indices = [Index(value = ["habitId", "date"], unique = true)])
data class CompletionRow(@PrimaryKey val id: String, val habitId: String, val date: String, val checked: Boolean,
    val createdAt: String, val updatedAt: String, val revision: Long, val schemaVersion: Int)

@Dao
interface PlannerDao {
    @Query("SELECT * FROM planner_pages WHERE month = :month") suspend fun find(month: String): PlannerRow?
    @Query("SELECT * FROM planner_pages WHERE id = :id") suspend fun byId(id: String): PlannerRow?
    @Query("SELECT * FROM planner_ink_strokes WHERE surfaceId = :id ORDER BY strokeOrder") suspend fun strokes(id: String): List<PlannerStrokeRow>
    @Query("SELECT * FROM planner_ink_strokes WHERE id = :id") suspend fun stroke(id: String): PlannerStrokeRow?
    @Insert suspend fun insert(row: PlannerRow)
    @Insert suspend fun insertStroke(row: PlannerStrokeRow)
    @Query("DELETE FROM planner_ink_strokes WHERE id = :id AND surfaceId = :surfaceId") suspend fun deleteStroke(id: String, surfaceId: String): Int
    @Query("UPDATE planner_pages SET updatedAt = :at, revision = revision + 1 WHERE id = :id") suspend fun revise(id: String, at: String)
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habit_ledgers WHERE month = :month") suspend fun find(month: String): LedgerRow?
    @Query("SELECT * FROM habit_ledgers WHERE month < :month ORDER BY month DESC LIMIT 1") suspend fun previous(month: String): LedgerRow?
    @Query("SELECT * FROM habit_definitions WHERE ledgerId = :id ORDER BY displayOrder") suspend fun definitions(id: String): List<HabitRow>
    @Query("SELECT * FROM habit_completions WHERE habitId IN (SELECT id FROM habit_definitions WHERE ledgerId = :id) ORDER BY date, habitId")
    suspend fun completions(id: String): List<CompletionRow>
    @Query("SELECT * FROM habit_completions WHERE habitId = :id AND date = :date") suspend fun completion(id: String, date: String): CompletionRow?
    @Insert suspend fun insertLedger(row: LedgerRow)
    @Query("SELECT * FROM habit_definitions WHERE id = :id") suspend fun habitById(id: String): HabitRow?
    @Query("SELECT * FROM habit_completions WHERE id = :id") suspend fun completionById(id: String): CompletionRow?
    @Upsert suspend fun putHabit(row: HabitRow)
    @Upsert suspend fun putCompletion(row: CompletionRow)
    @Query("UPDATE habit_definitions SET displayOrder = -displayOrder - 1 WHERE ledgerId = :id") suspend fun stageOrders(id: String)
    @Query("UPDATE habit_ledgers SET updatedAt = :at, revision = revision + 1 WHERE id = :id") suspend fun revise(id: String, at: String)
}
