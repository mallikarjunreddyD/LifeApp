package com.myjournal.data

import androidx.room.*

@Entity(tableName = "notebooks")
data class NotebookRow(@PrimaryKey val id: String, val name: String, val description: String?,
    val defaultTemplate: String, val archived: Boolean, val createdAt: String, val updatedAt: String, val revision: Long, val schemaVersion: Int)

@Entity(tableName = "notebook_pages",
    foreignKeys = [ForeignKey(entity = NotebookRow::class, parentColumns = ["id"], childColumns = ["notebookId"])],
    indices = [Index(value = ["notebookId", "pageOrder"], unique = true)])
data class NotebookPageRow(@PrimaryKey val id: String, val notebookId: String, val pageOrder: Int,
    val title: String?, val paper: String, val width: Float, val height: Float, val archived: Boolean, val createdAt: String, val updatedAt: String, val revision: Long, val schemaVersion: Int)

@Entity(tableName = "notebook_ink_strokes",
    foreignKeys = [ForeignKey(entity = NotebookPageRow::class, parentColumns = ["id"], childColumns = ["pageId"])],
    indices = [Index(value = ["pageId", "strokeOrder"], unique = true)])
data class NotebookStrokeRow(@PrimaryKey val id: String, val pageId: String, val strokeOrder: Long,
    val tool: String, val colorArgb: Long, val width: Float, val points: ByteArray,
    val createdAt: String, val updatedAt: String, val revision: Long, val schemaVersion: Int, val formatVersion: Int)

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY createdAt,id") suspend fun all(): List<NotebookRow>
    @Query("SELECT * FROM notebooks WHERE id = :id") suspend fun byId(id: String): NotebookRow?
    @Query("SELECT * FROM notebook_pages WHERE notebookId = :id ORDER BY pageOrder") suspend fun pages(id: String): List<NotebookPageRow>
    @Query("SELECT * FROM notebook_pages WHERE id = :id") suspend fun page(id: String): NotebookPageRow?
    @Query("SELECT * FROM notebook_ink_strokes WHERE pageId = :id ORDER BY strokeOrder") suspend fun strokes(id: String): List<NotebookStrokeRow>
    @Query("SELECT * FROM notebook_ink_strokes WHERE id = :id") suspend fun stroke(id: String): NotebookStrokeRow?
    @Upsert suspend fun put(row: NotebookRow)
    @Upsert suspend fun putPage(row: NotebookPageRow)
    @Insert suspend fun insertStroke(row: NotebookStrokeRow)
    @Query("UPDATE notebook_pages SET pageOrder = -pageOrder - 1 WHERE notebookId = :id") suspend fun stageOrders(id: String)
    @Query("DELETE FROM notebook_ink_strokes WHERE id = :id AND pageId IN (SELECT id FROM notebook_pages WHERE notebookId = :owner)")
    suspend fun deleteStroke(id: String, owner: String): Int
}
