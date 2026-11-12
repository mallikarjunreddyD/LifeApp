package com.myjournal.android

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExpenseUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun entersPaiseAndInlineLabelsThenDeletesAndRestores() {
        val repository = (rule.activity.application as JournalApplication).expenses
        rule.onNodeWithContentDescription("Open Expenses").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Add Expense").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Add Expense").performClick()
        rule.onNodeWithText("Amount (₹)").performTextReplacement("1,250.75")
        rule.onNodeWithText("Expense name").performTextReplacement("Expense fixture")
        rule.onNodeWithText("Category · Choose").performScrollTo().performClick()
        rule.onNodeWithText("Search Category").performTextReplacement("Fixture gym")
        rule.onNodeWithText("Create \"Fixture gym\"").performScrollTo().performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Category · Fixture gym").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Paid From · Choose").performScrollTo().performClick()
        rule.onNodeWithText("Search Paid From").performTextReplacement("Fixture card")
        rule.onNodeWithText("Create \"Fixture card\"").performScrollTo().performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Paid From · Fixture card").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Save Expense").performScrollTo()
        capture("expense-entry")
        rule.onNodeWithText("Save Expense").performClick()
        rule.waitUntil(15_000) { runBlocking { repository.observe().first().expenses.any { it.name == "Expense fixture" } } }
        val expense = runBlocking { repository.observe().first().expenses.single { it.name == "Expense fixture" } }
        assertEquals(125075L, expense.amountMinor)
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Recent Expenses").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("expense-history").performScrollToNode(hasText("Expense fixture"))
        capture("expense-history")
        rule.onNodeWithTag("expense-history").performScrollToNode(hasText("View Spending Analysis"))
        rule.onNodeWithText("View Spending Analysis").performClick()
        rule.onNodeWithText("Total expenditure").assertIsDisplayed()
        rule.onNodeWithText("₹1,250.75").assertIsDisplayed()
        rule.onNodeWithText("Yearly").performClick()
        rule.onNodeWithText("₹1,250.75").assertIsDisplayed()
        rule.onNodeWithText("Fixture gym").performScrollTo().assertIsDisplayed()
        capture("expense-analytics")
        rule.onNodeWithText("Year (YYYY)").performScrollTo().performTextReplacement("1900")
        rule.onNodeWithText("No expenses recorded for this period.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Back to Expenses").performScrollTo().performClick()
        rule.onNodeWithTag("expense-history").performScrollToNode(hasText("Expense fixture"))
        rule.onNodeWithText("Delete", substring = false).performClick()
        rule.onNodeWithText("Confirm Delete").performClick()
        rule.waitUntil(15_000) { runBlocking { repository.observe().first().expenses.single { it.metadata.id == expense.metadata.id }.deleted } }
        rule.onNodeWithTag("expense-history").performScrollToNode(hasText("Recently Deleted"))
        rule.onNodeWithText("Recently Deleted").performClick()
        rule.onNodeWithTag("expense-history").performScrollToNode(hasText("Restore Expense"))
        rule.onNodeWithText("Restore Expense").performClick()
        rule.waitUntil(15_000) {
            runBlocking { repository.observe().first().expenses.single { it.metadata.id == expense.metadata.id }.let {
                !it.deleted && it.metadata.revision == 3L
            } }
        }
    }

    private fun capture(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("screencap -p /sdcard/Download/$name.png"),
        ).use { it.readBytes() }
    }
}
