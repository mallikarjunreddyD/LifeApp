package com.myjournal.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class TodoUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun taskCanBeSavedCompletedAndDeletedThroughAndroidUi() {
        val title = "Test task ${Uuid.random().toString().take(8)}"
        rule.onNodeWithContentDescription("Open To-do").performClick()
        rule.onNodeWithText("Add task").performClick()
        rule.onNodeWithText("Title").performTextInput(title)
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Complete $title").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Completed").fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(15_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Delete $title").performClick()
        rule.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
    }
}
