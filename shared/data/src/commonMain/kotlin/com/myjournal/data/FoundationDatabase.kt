package com.myjournal.data

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Phase 0 metadata table is retained unchanged for migration compatibility. */
@Entity(tableName = "record_metadata")
data class MetadataRow(
    @PrimaryKey val id: String,
    val createdAt: String,
    val updatedAt: String,
    val schemaVersion: Int,
    val revision: Long,
)

@Dao
interface MetadataDao {
    @Query("SELECT * FROM record_metadata ORDER BY id")
    fun observeAll(): Flow<List<MetadataRow>>

    @Upsert
    suspend fun save(row: MetadataRow)
}

@Database(entities = [MetadataRow::class, EntryRow::class, PageRow::class, StrokeRow::class, PlannerRow::class, PlannerStrokeRow::class, LedgerRow::class, HabitRow::class, CompletionRow::class, NotebookRow::class, NotebookPageRow::class, NotebookStrokeRow::class], version = 5, exportSchema = true)
@ConstructedBy(FoundationDatabaseConstructor::class)
abstract class FoundationDatabase : RoomDatabase() {
    abstract fun metadata(): MetadataDao
    abstract fun journal(): JournalDao
    abstract fun planner(): PlannerDao
    abstract fun snapshot(): SnapshotDao
    abstract fun notebooks(): NotebookDao
    abstract fun habits(): HabitDao
}

@Suppress("KotlinNoActualForExpect")
expect object FoundationDatabaseConstructor : RoomDatabaseConstructor<FoundationDatabase> {
    override fun initialize(): FoundationDatabase
}
