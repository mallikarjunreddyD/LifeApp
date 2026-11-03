package com.myjournal.android

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.myjournal.domain.*
import kotlinx.datetime.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DailyJournalUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun dateNavigationToolsPageNavigationAndRecreationPreserveEditableInk() {
        val editor = (rule.activity.application as JournalApplication).editor
        val date = LocalDate(2200, 2, 28)
        rule.runOnIdle { editor.open(date) }
        rule.waitUntil(15_000) { !editor.state.value.loading }
        rule.onNodeWithText("Eraser").performClick().assertIsSelected()
        rule.onNodeWithText("Pen").performClick().assertIsSelected()
        val previousCount = editor.state.value.strokes.size
        rule.runOnIdle {
            val canvas = requireNotNull(findCanvas(rule.activity.window.decorView))
            val started = SystemClock.uptimeMillis()
            val pointer = MotionEvent.PointerProperties().apply { id = 3; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
                val point = MotionEvent.PointerCoords().apply {
                    x = canvas.width * 0.35f + index * 10f
                    y = canvas.height * 0.2f + index * 10f
                    pressure = 0.4f + index * 0.2f
                    size = 0.01f
                }
                val event = MotionEvent.obtain(started, started + index * 10, action, 1,
                    arrayOf(pointer), arrayOf(point), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { canvas.onTouchEvent(event) } finally { event.recycle() }
            }
        }
        rule.waitUntil(15_000) {
            editor.state.value.strokes.size == previousCount + 1 && editor.state.value.pending == 0
        }
        val original = editor.state.value.strokes.toList()
        val first = editor.state.value.pageId
        rule.onNodeWithText("Add page").performScrollTo().performClick()
        rule.waitUntil(15_000) { editor.state.value.entry!!.pages.size >= 2 && editor.state.value.pending == 0 }
        val second = editor.state.value.pageId
        assertNotEquals(first, second)
        rule.onNodeWithText("Previous page").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(first, editor.state.value.pageId)
        assertEquals(original, editor.state.value.strokes)
        rule.onNodeWithText("Next day").performClick()
        rule.waitUntil(15_000) { !editor.state.value.loading && editor.state.value.date != date }
        assertEquals(LocalDate(2200, 3, 1), editor.state.value.date)
        rule.onNodeWithText("Previous day").performClick()
        rule.waitUntil(15_000) { !editor.state.value.loading && editor.state.value.date == date }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val orientation = rule.activity.resources.configuration.orientation
        @Suppress("DEPRECATION")
        val rotation = rule.activity.windowManager.defaultDisplay.rotation
        try {
            // Display rotation, rather than an app orientation request that Android 16+
            // can ignore on large screens, guarantees an actual configuration change.
            assertTrue(automation.setRotation((rotation + 1) % 4))
            rule.waitUntil(60_000) {
                rule.activity.resources.configuration.orientation != orientation && !editor.state.value.loading
            }
            rule.activityRule.scenario.recreate()
            rule.waitUntil(15_000) { !editor.state.value.loading }
            assertEquals(date, editor.state.value.date)
            assertEquals(first, editor.state.value.pageId)
            assertEquals(original, editor.state.value.strokes)
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun findCanvas(view: View): JournalCanvas? {
        if (view is JournalCanvas) return view
        if (view is ViewGroup) for (i in 0 until view.childCount)
            findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }
}
