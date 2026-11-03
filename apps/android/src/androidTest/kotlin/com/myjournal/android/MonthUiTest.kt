package com.myjournal.android

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.myjournal.data.*
import com.myjournal.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic input on an Android emulator, not physical Xiaomi stylus validation. */
class MonthUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as JournalApplication

    @Test fun plannerInkMonthIsolationDateTapBackRotationAndDatabaseReopen() {
        val month = freshMonth(2401)
        openMonth(AppSection.PLANNER, month)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            // A landscape display fits the canonical page, independent of emulator startup orientation.
            if (rule.activity.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                @Suppress("DEPRECATION") val rotation = rule.activity.windowManager.defaultDisplay.rotation
                automation.setRotation((rotation + 1) % 4)
                rule.waitUntil(60_000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            }
            rule.waitForIdle()
            rule.onNodeWithText("Fit calendar").performClick()
            rule.waitForIdle()
            val layout = app.planner.state.value.planner!!.layout
            for (day in listOf(12, 20)) {
                val box = layout.cell(day)
                ink(box.left + 110, box.top + 65)
            }
            rule.waitUntil(20_000) { app.planner.state.value.strokes.size == 2 && app.planner.state.value.pending == 0 }
            val original = app.planner.state.value.strokes.toList()
            rule.onNodeWithText("Undo").performClick()
            rule.waitUntil { app.planner.state.value.strokes.size == 1 }
            rule.onNodeWithText("Redo").performClick()
            rule.waitUntil(20_000) { app.planner.state.value.strokes.size == 2 && app.planner.state.value.pending == 0 }
            rule.onNodeWithText("Next month").performClick()
            rule.waitUntil { app.planner.state.value.month == month.next() && !app.planner.state.value.loading }
            assertTrue(app.planner.state.value.strokes.isEmpty())
            rule.onNodeWithText("Previous month").performClick()
            rule.waitUntil { app.planner.state.value.month == month && !app.planner.state.value.loading }
            assertEquals(original, app.planner.state.value.strokes)
            val box = layout.cell(12)
            finger(box.left + 25, box.top + 30)
            rule.waitUntil { app.editor.state.value.date == month.date(12) && !app.editor.state.value.loading }
            rule.onNodeWithText("View monthly habits").assertExists()
            assertEquals(2, app.planner.state.value.strokes.size)
            val database = openJournalDatabase(rule.activity)
            try { assertNull(runBlocking { RoomJournalRepository(database).find(month.date(12)) }) }
            finally { database.close() }
            back()
            rule.onNodeWithText("Fit calendar").assertExists()
            assertEquals(month, app.planner.state.value.month)
            val orientation = rule.activity.resources.configuration.orientation
            @Suppress("DEPRECATION") val rotation = rule.activity.windowManager.defaultDisplay.rotation
            automation.setRotation((rotation + 1) % 4)
            rule.waitUntil(60_000) { rule.activity.resources.configuration.orientation != orientation }
            rule.activityRule.scenario.recreate()
            rule.waitUntil { !app.planner.state.value.loading }
            assertEquals(original, app.planner.state.value.strokes)
            val reopened = openJournalDatabase(rule.activity)
            try {
                val repository = RoomPlannerRepository(reopened)
                val stored = runBlocking { repository.find(month) }!!
                assertEquals(original, runBlocking { repository.loadStrokes(stored.metadata.id) })
            } finally { reopened.close() }
        } finally { automation.setRotation(UiAutomation.ROTATION_UNFREEZE) }
    }

    @Test fun habitTouchEditingArchivingCopyAndIndependentMonths() {
        val month = freshMonth(2501)
        openMonth(AppSection.HABITS, month)
        rule.onNodeWithText("Create blank ledger").performClick()
        for (name in listOf("Exercise", "Reading", "Meditation", "No Sugar")) addHabit(name)
        val reading = app.habits.state.value.ledger!!.activeHabits.single { it.name == "Reading" }.metadata.id
        rule.onNodeWithContentDescription("Habit days").performScrollToNode(hasContentDescription("Reading, ${month.date(12)}"))
        rule.onNodeWithContentDescription("Reading, ${month.date(12)}").performClick().assertIsOn()
        rule.onNodeWithText("Reading", substring = false).performClick()
        rule.onNodeWithText("Habit name").performTextReplacement("Read Books")
        rule.onNodeWithText("Apply changes").performClick()
        rule.waitUntil { app.habits.state.value.ledger!!.habits.any { it.name == "Read Books" } }
        rule.onNodeWithContentDescription("Read Books, ${month.date(12)}").assertIsOn()
        rule.onNodeWithText("Manage habits").performClick()
        rule.onNodeWithContentDescription("Move left habit Read Books").performClick()
        rule.onNodeWithContentDescription("Archive habit Read Books").performClick()
        rule.onNodeWithContentDescription("Restore habit Read Books").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.waitUntil(20_000) { app.habits.state.value.pending == 0 }
        val november = app.habits.state.value.ledger!!
        assertEquals(reading, november.activeHabits.first().metadata.id)
        assertEquals(1, november.count(reading))
        rule.onNodeWithText("Next month").performClick()
        rule.waitUntil { app.habits.state.value.month == month.next() && !app.habits.state.value.loading }
        rule.onNodeWithText("Copy habits from ${month.title()}").performClick()
        rule.waitUntil { app.habits.state.value.ledger != null }
        assertTrue(app.habits.state.value.ledger!!.completions.isEmpty())
        assertEquals(november.activeHabits.map { it.name }, app.habits.state.value.ledger!!.activeHabits.map { it.name })
        addHabit("December only")
        rule.onNodeWithText("Previous month").performClick()
        rule.waitUntil(20_000) { app.habits.state.value.month == month && !app.habits.state.value.loading && app.habits.state.value.pending == 0 }
        assertEquals(november, app.habits.state.value.ledger)
        val reopened = openJournalDatabase(rule.activity)
        try {
            val stored = runBlocking { RoomHabitRepository(reopened).find(month) }!!
            assertEquals(november.metadata.id, stored.metadata.id)
            assertEquals(november.habits.map { it.metadata.id to it.name }, stored.habits.map { it.metadata.id to it.name })
            assertEquals(november.completions.map { Triple(it.metadata.id, it.habitId, it.checked) },
                stored.completions.map { Triple(it.metadata.id, it.habitId, it.checked) })
        }
        finally { reopened.close() }
    }

    @Test fun linkedJournalNavigationRestoresLedgerScrollAndSavedInk() {
        val month = freshMonth(2601)
        openMonth(AppSection.HABITS, month)
        rule.onNodeWithText("Create blank ledger").performClick()
        rule.runOnIdle { repeat(10) { app.habits.saveHabit(null, "Habit $it", null, null, null) } }
        rule.waitUntil { app.habits.state.value.ledger?.habits?.size == 10 }
        rule.onNodeWithContentDescription("Habit headers").performTouchInput { swipeLeft() }
        rule.onNodeWithContentDescription("Habit days").performScrollToNode(hasContentDescription("Open journal ${month.date(12)}"))
        val before = horizontalOffset()
        assertTrue(before > 0f)
        rule.onNodeWithContentDescription("Open journal ${month.date(12)}").performClick()
        rule.waitUntil { app.editor.state.value.date == month.date(12) && !app.editor.state.value.loading }
        ink(200f, 200f)
        rule.waitUntil(20_000) { app.editor.state.value.strokes.size == 1 && app.editor.state.value.pending == 0 }
        val stroke = app.editor.state.value.strokes.single()
        back()
        rule.onNodeWithContentDescription("Open journal ${month.date(12)}").assertIsDisplayed()
        assertEquals(month, app.habits.state.value.month)
        assertEquals(before, horizontalOffset(), 1f)
        rule.onNodeWithContentDescription("Open journal ${month.date(12)}").performClick()
        rule.waitUntil { app.editor.state.value.date == month.date(12) && !app.editor.state.value.loading }
        assertEquals(stroke, app.editor.state.value.strokes.single())
        rule.onNodeWithText("View monthly habits").performClick()
        rule.waitUntil { app.habits.state.value.month == month && !app.habits.state.value.loading }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithContentDescription("Open journal ${month.date(12)}").assertIsDisplayed()
        assertEquals(before, horizontalOffset(), 1f)
        val reopened = openJournalDatabase(rule.activity)
        try {
            val repository = RoomJournalRepository(reopened)
            val entry = runBlocking { repository.find(month.date(12)) }!!
            assertEquals(listOf(stroke), runBlocking { repository.loadStrokes(entry.pages.first().metadata.id) })
        } finally { reopened.close() }
    }

    private fun horizontalOffset() = rule.onNodeWithContentDescription("Habit headers").fetchSemanticsNode()
        .config[SemanticsProperties.HorizontalScrollAxisRange].value()
    private fun addHabit(name: String) {
        rule.onNodeWithText("Add habit").performClick()
        rule.onNodeWithText("Habit name").performTextInput(name)
        rule.onNodeWithText("Add to month").performClick()
        rule.waitUntil { app.habits.state.value.ledger!!.habits.any { it.name == name } }
    }
    private fun openMonth(section: AppSection, month: JournalMonth) {
        rule.onNodeWithContentDescription(if (section == AppSection.PLANNER) "Open Monthly Planner" else "Open Habit Tracker").performClick()
        rule.waitUntil { if (section == AppSection.PLANNER) !app.planner.state.value.loading else !app.habits.state.value.loading }
        val current = if (section == AppSection.PLANNER) app.planner.state.value.month else app.habits.state.value.month
        rule.onNodeWithText(current.title()).performClick()
        rule.onNodeWithText("Year", substring = false).performTextReplacement(month.year.toString())
        rule.onNodeWithText("Month (1–12)").performTextReplacement(month.month.toString())
        rule.onNodeWithText("Open month").performClick()
        rule.waitUntil(20_000) { if (section == AppSection.PLANNER) app.planner.state.value.month == month && !app.planner.state.value.loading
            else app.habits.state.value.month == month && !app.habits.state.value.loading }
    }
    private fun freshMonth(start: Int): JournalMonth {
        val database = openJournalDatabase(rule.activity)
        try { return runBlocking {
            var year = start
            while (true) {
                val month = JournalMonth(year++, 11)
                if (RoomPlannerRepository(database).find(month) == null && RoomHabitRepository(database).find(month) == null &&
                    RoomHabitRepository(database).find(month.next()) == null && RoomJournalRepository(database).find(month.date(12)) == null) return@runBlocking month
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } } finally { database.close() }
    }
    private fun back() { rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun ink(x: Float, y: Float) = input(x, y, true)
    private fun finger(x: Float, y: Float) = input(x, y, false)
    private fun input(x: Float, y: Float, stylus: Boolean) {
        rule.runOnIdle {
            val canvas = requireNotNull(findCanvas(rule.activity.window.decorView))
            val pointer = MotionEvent.PointerProperties().apply { id = 3; toolType = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER }
            val started = SystemClock.uptimeMillis()
            val actions = if (stylus) listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP) else listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)
            actions.forEachIndexed { index, action ->
                val position = canvas.viewPosition(x + if (stylus) index * 5 else 0, y + if (stylus) index * 3 else 0)
                val point = MotionEvent.PointerCoords().apply { this.x = position.first; this.y = position.second; pressure = 0.6f; size = 0.01f }
                val event = MotionEvent.obtain(started, started + index * 10, action, 1, arrayOf(pointer), arrayOf(point),
                    0, 0, 1f, 1f, 0, 0, if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { canvas.onTouchEvent(event) } finally { event.recycle() }
            }
        }
    }
    private fun findCanvas(view: View): JournalCanvas? {
        if (view is JournalCanvas) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }
}
