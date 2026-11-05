package com.myjournal.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.room.useReaderConnection
import androidx.room.deferredTransaction
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RoomJournalRepository(private val database: FoundationDatabase) : JournalEntryRepository {
    private val dao = database.journal()
    override fun observe(date: LocalDate): Flow<JournalEntry?> = flow {
        // Finish schema initialization/migration before starting invalidation observers.
        find(date)
        emitAll(dao.observeEntry(date.toString()).map { find(date) })
    }
    override suspend fun find(date: LocalDate): JournalEntry? = withContext(Dispatchers.Default) {
        database.useReaderConnection { connection ->
            connection.deferredTransaction {
                dao.entry(date.toString())?.let { row ->
                    JournalEntry(metadata(row.id, row.createdAt, row.updatedAt, row.revision, row.schemaVersion), date,
                        dao.pages(row.id).map { it.toDomain() })
                }
            }
        }
    }
    override suspend fun loadStrokes(pageId: Uuid): List<InkStroke> = withContext(Dispatchers.Default) {
        dao.strokes(pageId.toString()).map { it.toDomain() }
    }
    override suspend fun apply(edit: PageEdit) = withContext(Dispatchers.Default) {
        // Serialization happens off the UI thread, once per completed stroke.
        val put = edit.put.map { it.toRow() }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                var entry = dao.entry(edit.date.toString())
                val newEntry = entry == null
                val page = edit.page
                val entryId = page.entryId.toString()
                val pageId = page.metadata.id.toString()
                val existing = dao.page(pageId)
                val at = listOf(Clock.System.now(), page.metadata.updatedAt,
                    entry?.let { Instant.parse(it.updatedAt) } ?: page.metadata.createdAt,
                    existing?.let { Instant.parse(it.updatedAt) } ?: page.metadata.createdAt).max().toString()
                require(edit.put.all { stroke -> stroke.points.all { it.x in 0f..page.width && it.y in 0f..page.height } })
                if (entry == null) {
                    require(edit.remove.isEmpty()) { "Cannot erase a missing journal" }
                    entry = EntryRow(entryId, edit.date.toString(), page.metadata.createdAt.toString(), at, 1)
                    dao.insertEntry(entry)
                }
                require(entry.id == entryId) { "Another journal already exists for this date; reload before editing" }
                var changed = false
                if (existing == null) {
                    require(!page.archived && edit.setArchived != true) { "A new journal page must be active" }
                    require(page.order == (dao.pages(entryId).maxOfOrNull { it.pageOrder } ?: -1) + 1) { "Pages must be appended in order" }
                    dao.insertPage(PageRow(pageId, entryId, page.order, page.width, page.height, page.paper.name,
                        page.metadata.createdAt.toString(), at, 1))
                    changed = true
                } else {
                    require(existing.entryId == entryId && existing.pageOrder == page.order &&
                        existing.width == page.width && existing.height == page.height && existing.paper == page.paper.name)
                }
                // Only an explicit archival command changes visibility. A late, correctly
                // targeted gesture must not restore a page using a stale page descriptor.
                edit.setArchived?.let { archived ->
                    require(existing != null) { "Archive only persisted pages" }
                    if (existing.archived != archived) {
                        require(!archived || dao.pages(entryId).count { !it.archived } > 1) { "Keep at least one active page" }
                        dao.setArchived(pageId, archived)
                        changed = true
                    }
                }
                edit.remove.forEach {
                    val previous = dao.stroke(it.toString())
                    require(previous == null || previous.pageId == pageId) { "Stroke belongs to another page" }
                    if (dao.deleteStroke(it.toString(), pageId) > 0) changed = true
                }
                put.forEach { row ->
                    val previous = dao.stroke(row.id)
                    if (previous == null) { dao.insertStroke(row); changed = true }
                    else require(previous.toDomain() == row.toDomain()) { "A stroke ID cannot be overwritten" }
                }
                if (changed) {
                    check(entry.revision < Long.MAX_VALUE && (existing?.revision ?: 0) < Long.MAX_VALUE)
                    if (!newEntry) dao.reviseEntry(entryId, at)
                    if (existing != null) dao.revisePage(pageId, at)
                }
            }
        }
    }
}

private fun metadata(id: String, created: String, updated: String, revision: Long, schema: Int) =
    RecordMetadata(Uuid.parse(id), Instant.parse(created), Instant.parse(updated), schema, revision)

private fun PageRow.toDomain() = JournalPage(metadata(id, createdAt, updatedAt, revision, schemaVersion),
    Uuid.parse(entryId), pageOrder, width, height, PaperTemplate.valueOf(paper), archived)

private fun StrokeRow.toDomain() = InkStroke(metadata(id, createdAt, updatedAt, revision, schemaVersion),
    Uuid.parse(pageId), strokeOrder, BrushTool.valueOf(tool), colorArgb, width, StrokePointCodec.decode(points), formatVersion)

private fun InkStroke.toRow() = StrokeRow(metadata.id.toString(), pageId.toString(), order, tool.name,
    colorArgb, width, StrokePointCodec.encode(points), metadata.createdAt.toString(), metadata.updatedAt.toString(),
    metadata.revision, metadata.schemaVersion, formatVersion)
