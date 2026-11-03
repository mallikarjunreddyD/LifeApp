package com.myjournal.android

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
        rule.runOnIdle { editor.addStroke(BrushTool.PEN, 0xFF000000, 3f,
            listOf(InkPoint(100f, 100f, 0, 0.4f), InkPoint(200f, 200f, 20, 0.8f))) }
        rule.waitUntil(15_000) { editor.state.value.pending == 0 }
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
        rule.runOnIdle { rule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(60_000) {
            rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(15_000) { !editor.state.value.loading }
        assertEquals(date, editor.state.value.date)
        assertEquals(first, editor.state.value.pageId)
        assertEquals(original, editor.state.value.strokes)
    }
}
