package com.myjournal.data

import androidx.room.*
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun notebookMeta(id: String, created: String, updated: String, revision: Long, schema: Int) =
    RecordMetadata(Uuid.parse(id), Instant.parse(created), Instant.parse(updated), schema, revision)
internal fun NotebookRow.model() = Notebook(notebookMeta(id, createdAt, updatedAt, revision, schemaVersion), name, description, PaperTemplate.valueOf(defaultTemplate), archived)
internal fun NotebookPageRow.model() = NotebookPage(notebookMeta(id, createdAt, updatedAt, revision, schemaVersion), Uuid.parse(notebookId), pageOrder, title, PaperTemplate.valueOf(paper), width, height, archived)
internal fun NotebookStrokeRow.model() = InkStroke(notebookMeta(id, createdAt, updatedAt, revision, schemaVersion), Uuid.parse(pageId), strokeOrder, BrushTool.valueOf(tool), colorArgb, width, StrokePointCodec.decode(points), formatVersion)
internal fun Notebook.row() = NotebookRow(metadata.id.toString(), name, description, defaultTemplate.name, archived, metadata.createdAt.toString(), metadata.updatedAt.toString(), metadata.revision, metadata.schemaVersion)
internal fun NotebookPage.row() = NotebookPageRow(metadata.id.toString(), notebookId.toString(), order, title, paper.name, width, height, archived, metadata.createdAt.toString(), metadata.updatedAt.toString(), metadata.revision, metadata.schemaVersion)
internal fun InkStroke.notebookRow() = NotebookStrokeRow(metadata.id.toString(), pageId.toString(), order, tool.name, colorArgb, width, StrokePointCodec.encode(points), metadata.createdAt.toString(), metadata.updatedAt.toString(), metadata.revision, metadata.schemaVersion, formatVersion)

class RoomNotebookRepository(private val database: FoundationDatabase) : NotebookRepository {
    private val dao = database.notebooks()
    override suspend fun all() = withContext(Dispatchers.Default) { dao.all().map { it.model() } }
    override suspend fun pages(notebookId: Uuid) = withContext(Dispatchers.Default) { dao.pages(notebookId.toString()).map { it.model() } }
    override suspend fun loadStrokes(pageId: Uuid) = withContext(Dispatchers.Default) { dao.strokes(pageId.toString()).map { it.model() } }
    override suspend fun apply(edit: NotebookEdit) = withContext(Dispatchers.Default) {
        val encoded = edit.put.map { it.notebookRow() }
        database.useWriterConnection { connection -> connection.immediateTransaction {
            val id = edit.notebook.metadata.id.toString()
            val old = dao.byId(id)?.model()
            require(old == null || old.metadata.createdAt == edit.notebook.metadata.createdAt)
            require(old == null || old == edit.notebook || edit.notebook.metadata.revision > old.metadata.revision)
            val previous = dao.pages(id).associateBy { it.id }
            require(previous.keys.all { oldId -> edit.pages.any { it.metadata.id.toString() == oldId } }) { "Archive pages instead of deleting ink" }
            edit.pages.forEach { page -> previous[page.metadata.id.toString()]?.let { row ->
                val oldPage = row.model()
                require(oldPage == page || page.metadata.revision > oldPage.metadata.revision) { "Stale page edit" }
                require(page.metadata.updatedAt >= oldPage.metadata.updatedAt)
                require(page.width == oldPage.width && page.height == oldPage.height) { "Page dimensions cannot be reinterpreted" }
            } }
            dao.put(edit.notebook.row())
            dao.stageOrders(id)
            edit.pages.forEach { page ->
                val global = dao.page(page.metadata.id.toString())
                require(global == null || global.notebookId == page.notebookId.toString() && global.createdAt == page.metadata.createdAt.toString())
                dao.putPage(page.row())
            }
            edit.remove.forEach { target ->
                val stroke = dao.stroke(target.toString())
                require(stroke == null || edit.pages.any { it.metadata.id.toString() == stroke.pageId })
                dao.deleteStroke(target.toString(), id)
            }
            encoded.forEach { row ->
                val previousStroke = dao.stroke(row.id)
                if (previousStroke == null) dao.insertStroke(row)
                else require(previousStroke.model() == row.model()) { "Immutable stroke identity conflict" }
            }
        } }
    }
}
