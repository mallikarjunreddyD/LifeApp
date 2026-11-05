package com.myjournal.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "journal_entries", indices = [Index(value = ["date"], unique = true)])
data class EntryRow(
    @PrimaryKey val id: String,
    val date: String,
    val createdAt: String,
    val updatedAt: String,
    val revision: Long,
    val schemaVersion: Int = 1,
)

@Entity(tableName = "journal_pages",
    foreignKeys = [ForeignKey(entity = EntryRow::class, parentColumns = ["id"], childColumns = ["entryId"])],
    indices = [Index(value = ["entryId", "pageOrder"], unique = true)])
data class PageRow(
    @PrimaryKey val id: String,
    val entryId: String,
    val pageOrder: Int,
    val width: Float,
    val height: Float,
    val paper: String,
    val createdAt: String,
    val updatedAt: String,
    val revision: Long,
    val schemaVersion: Int = 1,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
)

@Entity(tableName = "ink_strokes",
    foreignKeys = [ForeignKey(entity = PageRow::class, parentColumns = ["id"], childColumns = ["pageId"])],
    indices = [Index(value = ["pageId", "strokeOrder"], unique = true)])
data class StrokeRow(
    @PrimaryKey val id: String,
    val pageId: String,
    val strokeOrder: Long,
    val tool: String,
    val colorArgb: Long,
    val width: Float,
    val points: ByteArray,
    val createdAt: String,
    val updatedAt: String,
    val revision: Long,
    val schemaVersion: Int,
    val formatVersion: Int,
)

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal_entries WHERE date = :date")
    suspend fun entry(date: String): EntryRow?
    @Query("SELECT * FROM journal_entries WHERE date = :date")
    fun observeEntry(date: String): Flow<EntryRow?>
    @Query("SELECT * FROM journal_pages WHERE entryId = :id ORDER BY pageOrder")
    suspend fun pages(id: String): List<PageRow>
    @Query("SELECT * FROM journal_pages WHERE id = :id")
    suspend fun page(id: String): PageRow?
    @Query("SELECT * FROM ink_strokes WHERE pageId = :id ORDER BY strokeOrder")
    suspend fun strokes(id: String): List<StrokeRow>
    @Query("SELECT * FROM ink_strokes WHERE id = :id")
    suspend fun stroke(id: String): StrokeRow?
    @Query("UPDATE journal_pages SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)
    @Insert suspend fun insertEntry(row: EntryRow)
    @Insert suspend fun insertPage(row: PageRow)
    @Insert suspend fun insertStroke(row: StrokeRow)
    @Query("DELETE FROM ink_strokes WHERE id = :id AND pageId = :pageId")
    suspend fun deleteStroke(id: String, pageId: String): Int
    @Query("UPDATE journal_entries SET updatedAt = :at, revision = revision + 1 WHERE id = :id")
    suspend fun reviseEntry(id: String, at: String)
    @Query("UPDATE journal_pages SET updatedAt = :at, revision = revision + 1 WHERE id = :id")
    suspend fun revisePage(id: String, at: String)
}
