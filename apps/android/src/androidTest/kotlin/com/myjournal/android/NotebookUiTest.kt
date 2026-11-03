package com.myjournal.android

import android.os.SystemClock
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.myjournal.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotebookUiTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as JournalApplication
    @Test fun createWriteChangeTemplateReorderArchiveRestoreAndRecreate() {
        rule.onNodeWithContentDescription("Open Notebook").performClick()
        rule.onNodeWithText("Create notebook").performClick()
        rule.onNodeWithText("Notebook name").performTextInput("Research Notes ${SystemClock.uptimeMillis()}")
        rule.onNodeWithText("Create",substring=false).performClick()
        rule.waitUntil(20_000){app.notebooks.state.value.page!=null && app.notebooks.state.value.pending==0}
        val book=app.notebooks.state.value.notebook!!.metadata.id
        val page=app.notebooks.state.value.pageId!!
        rule.runOnIdle {
            val canvas=requireNotNull(findCanvas(rule.activity.window.decorView));val started=SystemClock.uptimeMillis()
            val pointer=MotionEvent.PointerProperties().apply{id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).forEachIndexed { index,action ->
                val xy=canvas.viewPosition(200f+index*10,200f+index*5)
                val point=MotionEvent.PointerCoords().apply{x=xy.first;y=xy.second;pressure=0.7f;size=0.01f}
                val event=MotionEvent.obtain(started,started+index*10,action,1,arrayOf(pointer),arrayOf(point),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                try{canvas.onTouchEvent(event)}finally{event.recycle()}
            }
        }
        rule.waitUntil(20_000){app.notebooks.state.value.strokes.size==1 && app.notebooks.state.value.pending==0}
        val stroke=app.notebooks.state.value.strokes.single()
        rule.onNodeWithText("Add notebook page").performScrollTo().performClick()
        rule.onNodeWithText("Dotted",substring=false).performClick()
        rule.onNodeWithText("Add page to notebook").performClick()
        rule.waitUntil(20_000){app.notebooks.state.value.pages.size==2 && app.notebooks.state.value.pending==0}
        rule.onNodeWithText("Manage pages").performScrollTo().performClick()
        rule.onNodeWithText("Move page 2 earlier").performScrollTo().performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Next notebook page").performScrollTo().performClick()
        rule.waitUntil{app.notebooks.state.value.pageId==page && !app.notebooks.state.value.loading}
        assertEquals(stroke,app.notebooks.state.value.strokes.single())
        rule.onNodeWithText("Manage pages").performScrollTo().performClick()
        rule.onNodeWithText("Edit 2").performScrollTo().performClick()
        rule.onNodeWithText("Grid",substring=false).performClick()
        rule.onNodeWithText("Apply page changes").performClick()
        rule.waitUntil(20_000){app.notebooks.state.value.page?.paper==PaperTemplate.GRID && app.notebooks.state.value.pending==0}
        rule.activityRule.scenario.recreate();rule.waitUntil{!app.notebooks.state.value.loading}
        assertEquals(book,app.notebooks.state.value.notebook!!.metadata.id);assertEquals(page,app.notebooks.state.value.pageId)
        assertEquals(stroke,app.notebooks.state.value.strokes.single())
        rule.onNodeWithText("Archive notebook").performScrollTo().performClick()
        rule.onNodeWithText("Restore notebook").performScrollTo().performClick()
        rule.waitUntil(20_000){!app.notebooks.state.value.notebook!!.archived && app.notebooks.state.value.pending==0}
        assertEquals(stroke,app.notebooks.state.value.strokes.single())
    }
    private fun findCanvas(view: View): JournalCanvas? {
        if(view is JournalCanvas)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findCanvas(view.getChildAt(i))?.let{return it}
        return null
    }
}
