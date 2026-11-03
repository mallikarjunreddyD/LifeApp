package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class JournalDomainTest {
    private val at = Instant.parse("2026-11-03T00:00:00Z")
    private val entryId = Uuid.random()
    private val page = JournalPage(RecordMetadata(Uuid.random(), at, at), entryId, 0)
    private fun stroke(order: Long = 0, tool: BrushTool = BrushTool.PEN) = InkStroke(
        RecordMetadata(Uuid.random(), at, at), page.metadata.id, order, tool, 0x552859A8L, 3.2f,
        listOf(InkPoint(10f, 20f, 0, 0.2f), InkPoint(110f, 120f, 10, 0.9f)))

    @Test fun calendarDatesCrossLeapMonthsAndYearsWithoutTimezoneConversion() {
        assertEquals(LocalDate(2024, 2, 29), LocalDate(2024, 2, 28).plus(1, DateTimeUnit.DAY))
        assertEquals(LocalDate(2024, 3, 1), LocalDate(2024, 2, 29).plus(1, DateTimeUnit.DAY))
        assertEquals(LocalDate(2025, 1, 1), LocalDate(2024, 12, 31).plus(1, DateTimeUnit.DAY))
        assertEquals(LocalDate(2025, 2, 28), LocalDate(2025, 3, 1).minus(1, DateTimeUnit.DAY))
        assertFailsWith<IllegalArgumentException> { LocalDate.parse("2025-02-29") }
    }
    @Test fun pagesHaveStableIdsAndRejectDuplicateOrderOrWrongParent() {
        val entry = JournalEntry(RecordMetadata(entryId, at, at), LocalDate(2026, 11, 3), listOf(page))
        val second = page.copy(metadata = page.metadata.copy(id = Uuid.random()), order = 1)
        assertEquals(listOf(page, second), entry.copy(pages = listOf(page, second)).pages)
        assertEquals(page.metadata.id, page.copy(order = 2).metadata.id)
        assertFailsWith<IllegalArgumentException> { entry.copy(pages = listOf(page, page)) }
        assertFailsWith<IllegalArgumentException> { entry.copy(pages = listOf(page, second.copy(order = 0))) }
        assertFailsWith<IllegalArgumentException> { entry.copy(pages = listOf(page.copy(entryId = Uuid.random()))) }
    }
    @Test fun invalidGeometryAndPointOrderAreRejected() {
        assertFailsWith<IllegalArgumentException> { page.copy(width = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { stroke().copy(width = 0f) }
        assertFailsWith<IllegalArgumentException> { InkPoint(1f, 2f, 0, 1.1f) }
        assertFailsWith<IllegalArgumentException> { stroke().copy(points = listOf(InkPoint(1f, 2f, 10), InkPoint(2f, 3f, 0))) }
        assertFailsWith<IllegalArgumentException> { stroke().copy(formatVersion = 2) }
    }
    @Test fun transformsRoundTripAcrossZoomDensityAndOrientation() {
        for (scale in listOf(0.5f, 1f, 2.75f, 4f)) {
            val transform = PageTransform(scale, -45f, 136f)
            val screen = transform.toScreen(350.25f, 901.75f)
            val logical = transform.toPage(screen.first, screen.second)
            assertEquals(350.25f, logical.first, 0.001f)
            assertEquals(901.75f, logical.second, 0.001f)
        }
    }
    @Test fun eraserUndoRestoresExactStrokeAndOrderingThenRedoDeletesIt() {
        val a = stroke(); val b = stroke(1, BrushTool.HIGHLIGHTER)
        val history = StrokeHistory(listOf(a, b))
        val removed = history.erase(setOf(a.metadata.id))!!
        assertEquals(listOf(a), removed.removed)
        assertEquals(listOf(b), history.current)
        assertEquals(listOf(a), history.undo()!!.added)
        assertEquals(listOf(a, b), history.current)
        assertEquals(listOf(a), history.redo()!!.removed)
        assertEquals(listOf(b), history.current)
    }
    @Test fun newEditClearsRedoAndAddUndoPreservesIdentity() {
        val a = stroke()
        val history = StrokeHistory(emptyList())
        history.add(a); history.undo(); history.redo()
        assertSame(a, history.current.single())
        history.undo(); history.add(stroke(1))
        assertFalse(history.canRedo)
        assertNull(history.redo())
        assertFailsWith<IllegalArgumentException> { history.add(history.current.single()) }
    }
    @Test fun spatialEraserFindsSegmentsRatherThanOnlySamplePoints() {
        val stroke = stroke()
        val index = StrokeHitIndex(listOf(stroke))
        assertEquals(setOf(stroke.metadata.id), index.hit(60f, 70f, 2f))
        assertEquals(emptySet(), index.hit(800f, 900f, 16f))
    }
}
