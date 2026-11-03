package com.myjournal.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Synthetic Android events exercise adapter behavior; these are not hardware stylus tests. */
@RunWith(AndroidJUnit4::class)
class JournalCanvasInputTest {
    private fun page(): JournalPage {
        val at = Clock.System.now()
        return JournalPage(RecordMetadata(Uuid.random(), at, at), Uuid.random(), 0)
    }
    private fun event(action: Int, tool: Int, time: Long, x: Float = 100f, y: Float = 100f, flags: Int = 0): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 7; toolType = tool }
        val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.7f; size = 0.01f }
        return MotionEvent.obtain(time, time, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, flags)
    }
    private fun send(view: JournalCanvas, action: Int, tool: Int, time: Long, x: Float = 100f, y: Float = 100f, flags: Int = 0) {
        val event = event(action, tool, time, x, y, flags)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
    @Test fun fingersNeverCreateInkButStylusProducesEditablePressurePoints() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val page = page(); val completed = mutableListOf<InkStroke>()
            val view = JournalCanvas(activity)
            activity.setContentView(view); view.layout(0, 0, 400, 400)
            view.bind(page, emptyList(), EditorTool.PEN, 0xFF000000, 3f, Viewport(), {}, { tool, color, width, points ->
                InkStroke(page.metadata.copy(id = Uuid.random()), page.metadata.id, 0, tool, color, width, points).also(completed::add)
            }, {})
            val start = SystemClock.uptimeMillis()
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, start)
            send(view, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_FINGER, start + 10, 110f, 110f)
            send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER, start + 20)
            assertTrue(completed.isEmpty())
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, start + 30)
            send(view, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_STYLUS, start + 40, 110f, 110f)
            send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, start + 50, 115f, 115f)
            assertEquals(1, completed.size)
            assertTrue(completed.single().points.size >= 3)
            assertEquals(0.7f, completed.single().points.first().pressure!!, 0.0001f)
        } }
    }
    @Test fun canceledMotionSetsAndCanceledPointerUpDoNotPersistInk() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val page = page(); var count = 0
            val view = JournalCanvas(activity)
            activity.setContentView(view); view.layout(0, 0, 400, 400)
            view.bind(page, emptyList(), EditorTool.PEN, 0xFF000000, 3f, Viewport(), {}, { _, _, _, _ -> count++; null }, {})
            val start = SystemClock.uptimeMillis()
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, start)
            send(view, MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_STYLUS, start + 10)
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, start + 20)
            send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, start + 30, flags = MotionEvent.FLAG_CANCELED)
            assertEquals(0, count)
        } }
    }
    @Test fun zoomTransformsInputBackToLogicalCoordinatesAndHighlighterProperties() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val page = page(); var completed: InkStroke? = null
            val view = JournalCanvas(activity)
            activity.setContentView(view); view.layout(0, 0, 400, 400)
            view.bind(page, emptyList(), EditorTool.HIGHLIGHTER, 0xFF2859A8, 18f, Viewport(2f, 500f, 707f), {}, { tool, color, width, points ->
                InkStroke(page.metadata.copy(id = Uuid.random()), page.metadata.id, 0, tool, color, width, points).also { completed = it }
            }, {})
            val start = SystemClock.uptimeMillis()
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, start, 200f, 200f)
            send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, start + 10, 210f, 210f)
            val stroke = completed!!
            assertEquals(500f, stroke.points.first().x, 0.01f)
            assertEquals(707f, stroke.points.first().y, 0.01f)
            assertEquals(BrushTool.HIGHLIGHTER, stroke.tool)
            assertEquals(0x552859A8L, stroke.colorArgb)
            assertEquals(18f, stroke.width)
        } }
    }
    @Test fun wholeStrokeEraserCommitsOnlyOnReleaseAndCancellationKeepsOriginal() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val page = page()
            val original = InkStroke(page.metadata.copy(id = Uuid.random()), page.metadata.id, 0, BrushTool.PEN,
                0xFF000000, 3f, listOf(InkPoint(240f, 250f, 0), InkPoint(260f, 250f, 10)))
            val erased = mutableListOf<Set<Uuid>>()
            val view = JournalCanvas(activity)
            activity.setContentView(view); view.layout(0, 0, 400, 400)
            view.bind(page, listOf(original), EditorTool.ERASER, 0xFF000000, 3f, Viewport(), {}, { _, _, _, _ -> fail("Eraser created ink"); null }, { erased.add(it) })
            val start = SystemClock.uptimeMillis()
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, start, 100f, 100f)
            assertTrue(erased.isEmpty())
            send(view, MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_STYLUS, start + 10)
            assertTrue(erased.isEmpty())
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_ERASER, start + 20, 100f, 100f)
            send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_ERASER, start + 30, 100f, 100f)
            assertEquals(listOf(setOf(original.metadata.id)), erased)
        } }
    }
}
