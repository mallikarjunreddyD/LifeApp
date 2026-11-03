package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

enum class BrushTool { PEN, HIGHLIGHTER }
enum class PaperTemplate { RULED, BLANK, DOTTED, GRID }

data class InkPoint(val x: Float, val y: Float, val elapsedMillis: Long, val pressure: Float? = null) {
    init {
        require(x.isFinite() && y.isFinite())
        require(elapsedMillis >= 0)
        require(pressure == null || pressure.isFinite() && pressure in 0f..1f)
    }
}

data class InkStroke(
    val metadata: RecordMetadata,
    val pageId: Uuid,
    val order: Long,
    val tool: BrushTool,
    val colorArgb: Long,
    val width: Float,
    val points: List<InkPoint>,
    val formatVersion: Int = 1,
) {
    init {
        require(order >= 0 && formatVersion == 1)
        require(colorArgb in 0L..0xFFFFFFFFL)
        require(width.isFinite() && width in 0.1f..100f)
        require(points.isNotEmpty() && points.size <= 1_000_000)
        require(points.zipWithNext().all { (a, b) -> b.elapsedMillis > a.elapsedMillis })
    }
}

data class JournalPage(
    val metadata: RecordMetadata,
    val entryId: Uuid,
    val order: Int,
    val width: Float = 1000f,
    val height: Float = 1414f,
    val paper: PaperTemplate = PaperTemplate.RULED,
) {
    init {
        require(order >= 0)
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
    }
}

data class JournalEntry(val metadata: RecordMetadata, val date: LocalDate, val pages: List<JournalPage>) {
    init {
        require(pages.isNotEmpty())
        require(pages.all { it.entryId == metadata.id })
        require(pages.map { it.metadata.id }.distinct().size == pages.size)
        require(pages.map { it.order }.distinct().size == pages.size)
    }
}

/** A single atomic edit. Replays are idempotent; stored stroke IDs cannot be overwritten. */
data class PageEdit(
    val date: LocalDate,
    val page: JournalPage,
    val put: List<InkStroke> = emptyList(),
    val remove: Set<Uuid> = emptySet(),
) {
    init {
        require(put.all { it.pageId == page.metadata.id })
        require(put.map { it.metadata.id }.distinct().size == put.size)
        require(put.none { it.metadata.id in remove })
    }
}

interface JournalEntryRepository {
    fun observe(date: LocalDate): Flow<JournalEntry?>
    suspend fun find(date: LocalDate): JournalEntry?
    suspend fun loadStrokes(pageId: Uuid): List<InkStroke>
    suspend fun apply(edit: PageEdit)
}

/** Uniform scale only: logical page geometry never changes with density or rotation. */
data class PageTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    init { require(scale.isFinite() && scale > 0 && offsetX.isFinite() && offsetY.isFinite()) }
    fun toPage(x: Float, y: Float) = Pair((x - offsetX) / scale, (y - offsetY) / scale)
    fun toScreen(x: Float, y: Float) = Pair(x * scale + offsetX, y * scale + offsetY)
}
