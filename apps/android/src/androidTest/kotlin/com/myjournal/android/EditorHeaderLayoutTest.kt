package com.myjournal.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.myjournal.ui.JournalApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EditorHeaderLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pendingSaveStatusDoesNotMoveOrResizeTheWritingSurface() {
        val saving = mutableStateOf(false)
        rule.setContent {
            JournalApp(dailyJournal = { Box(Modifier.fillMaxSize().testTag("writing surface")) },
                globalStatus = { if (saving.value) Text("Saving 4 local changes…") })
        }
        val original = rule.onNodeWithTag("writing surface").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { saving.value = true }
        val pending = rule.onNodeWithTag("writing surface").fetchSemanticsNode().boundsInRoot
        assertEquals("Autosave status must not change the native contact origin or workspace bounds",original,pending)
        rule.runOnIdle { saving.value = false }
        assertEquals(original,rule.onNodeWithTag("writing surface").fetchSemanticsNode().boundsInRoot)
    }
}
