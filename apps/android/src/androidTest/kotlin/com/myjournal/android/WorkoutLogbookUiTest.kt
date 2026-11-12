package com.myjournal.android

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.myjournal.domain.ExerciseDefinition
import com.myjournal.domain.WorkoutRoutine
import com.myjournal.domain.newRecordMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkoutLogbookUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun recordsRoutineWithDecimalWeightAndRestoresDeletedWorkout() {
        val repository = (rule.activity.application as JournalApplication).workouts
        runBlocking {
            val exercise = ExerciseDefinition(newRecordMetadata(), "Logbook bench press")
            repository.saveExercise(exercise)
            repository.saveRoutine(WorkoutRoutine(newRecordMetadata(), "Logbook push", listOf(exercise.metadata.id), listOf(1)))
        }
        rule.onNodeWithContentDescription("Open Workouts").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Add workout").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Add workout").performClick()
        rule.onNodeWithText("Workout date (YYYY-MM-DD)").performTextReplacement("2026-11-12")
        rule.onNodeWithText("Choose routine").performClick()
        rule.onNodeWithText("Logbook push").performClick()
        scrollTo("Set 1")
        rule.onNodeWithText("Weight").performTextInput("35.5")
        rule.onNodeWithText("Repetitions").performTextInput("7")
        scrollTo("Exercise remarks")
        rule.onNodeWithText("Exercise remarks").performTextInput("Slow descent")
        capture("workout-entry")
        scrollTo("Save workout")
        rule.onNodeWithText("Save workout").performClick()
        rule.waitUntil(15_000) { runBlocking { repository.observe().first().sessions.any { it.routineName == "Logbook push" } } }
        val recorded = runBlocking { repository.observe().first().sessions.single { it.routineName == "Logbook push" } }
        assertEquals("2026-11-12", recorded.date.toString())
        assertEquals(null, recorded.startedAt)
        assertEquals("Slow descent", recorded.entries.single().remarks)
        assertEquals(35.5, recorded.entries.single().sets.single().weight!!.value, 0.0)
        scrollTo("Spreadsheet")
        rule.onNodeWithText("Spreadsheet").performClick()
        scrollTo("Slow descent")
        capture("workout-history")
        scrollTo("Delete workout")
        rule.onNodeWithText("Delete workout").performClick()
        rule.onNode(hasText("Delete workout") and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(15_000) { runBlocking { repository.observe().first().sessions.single { it.metadata.id == recorded.metadata.id }.deleted } }
        scrollTo("Recently deleted")
        rule.onNodeWithText("Recently deleted").performClick()
        scrollTo("Restore workout")
        rule.onNodeWithText("Restore workout").performClick()
        rule.waitUntil(15_000) { runBlocking { repository.observe().first().sessions.none { it.metadata.id == recorded.metadata.id && it.deleted } } }
    }

    private fun scrollTo(text: String) {
        rule.onNodeWithTag("workout-logbook").performScrollToNode(hasText(text))
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }
}
