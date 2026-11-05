package com.myjournal.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.myjournal.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JournalPageArchiveUiTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as JournalApplication
    @Test fun confirmedDeletionFinishesGesturePreservesInkAndRestoresPageAfterRecreation() {
        val date=unusedDate()
        val editor=app.editor
        rule.runOnIdle { editor.open(date) }
        rule.waitUntil(20_000){!editor.state.value.loading}
        rule.onNodeWithText("Delete page").assertIsNotEnabled()
        rule.onNodeWithText("Delete disabled: keep at least one active page.").assertExists()
        val first=editor.state.value.pageId
        rule.onNodeWithText("Add page").performScrollTo().performClick()
        rule.waitUntil(20_000){editor.state.value.activePages.size==2 && editor.state.value.pending==0}
        val second=editor.state.value.pageId!!
        // Leave a real native gesture unfinished, then use the toolbar action.
        rule.runOnIdle {
            val view=requireNotNull(findCanvas(rule.activity.window.decorView));val started=SystemClock.uptimeMillis()
            val pointer=MotionEvent.PointerProperties().apply { id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS }
            for((index,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE).withIndex()) {
                val coordinates=MotionEvent.PointerCoords().apply { x=view.width*0.4f+index*10;y=view.height*0.3f+index*10;pressure=0.8f;size=0.01f }
                val event=MotionEvent.obtain(started,started+index*16,action,1,arrayOf(pointer),arrayOf(coordinates),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                try {view.onTouchEvent(event)} finally {event.recycle()}
            }
        }
        rule.onNodeWithText("Delete page").performScrollTo().performClick()
        rule.onNodeWithText("Delete this page?").assertExists()
        rule.onNodeWithText("Cancel deletion").performClick()
        rule.waitUntil(20_000){editor.state.value.strokes.size==1 && editor.state.value.pending==0}
        assertEquals(second,editor.state.value.pageId);assertEquals(2,editor.state.value.activePages.size)
        val stroke=editor.state.value.strokes.single()
        rule.onNodeWithText("Delete page").performClick()
        rule.onNodeWithText("Move to archive").performClick()
        rule.waitUntil(20_000){editor.state.value.activePages.size==1 && editor.state.value.pending==0}
        assertEquals(first,editor.state.value.pageId)
        rule.onNodeWithText("Delete page").assertIsNotEnabled()
        rule.onNodeWithText("Page 1 of 1").assertExists()
        val database=openJournalDatabase(rule.activity)
        try {
            val stored=runBlocking { RoomJournalRepository(database).find(date) }!!
            assertEquals(second,stored.archivedPages.single().metadata.id)
            assertEquals(listOf(stroke),runBlocking {RoomJournalRepository(database).loadStrokes(second)})
        } finally {database.close()}
        rule.activityRule.scenario.recreate()
        rule.waitUntil{!editor.state.value.loading}
        rule.onNodeWithText("Restore pages").performScrollTo().performClick()
        rule.onNodeWithText("Restore page 2").performClick()
        rule.onNodeWithText("Done restoring").performClick()
        rule.waitUntil(20_000){editor.state.value.activePages.size==2 && editor.state.value.pending==0}
        rule.onNodeWithText("Next page").performScrollTo().performClick()
        rule.waitUntil{editor.state.value.pageId==second && !editor.state.value.loading}
        assertEquals(stroke,editor.state.value.strokes.single())
        rule.onNodeWithText("Fit width").performScrollTo().performClick()
        var canvas: JournalCanvas?=null
        rule.runOnIdle { canvas=findCanvas(rule.activity.window.decorView) }
        val view=requireNotNull(canvas)
        assertEquals(view.width,view.getChildAt(0).width)
        assertEquals(view.width,view.getChildAt(1).width)
        assertEquals(1f,view.currentViewport.zoom,0.001f)
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        val complete=CountDownLatch(1);var status=-1
        rule.runOnIdle {
            view.postOnAnimation { view.postOnAnimation {
                val location=IntArray(2);view.getLocationInWindow(location)
                PixelCopy.request(rule.activity.window,Rect(location[0],location[1],location[0]+view.width,location[1]+view.height),bitmap,
                    { status=it;complete.countDown() },Handler(Looper.getMainLooper()))
            } }
        }
        assertTrue(complete.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status)
        assertTrue("Actual Compose editor paper must fill its width",Color.red(bitmap.getPixel(view.width-4,minOf(100,view.height-4)))>245)
        bitmap.recycle()
    }
    private fun unusedDate(): LocalDate {
        val db=openJournalDatabase(rule.activity)
        try{return runBlocking {
            var year=2701
            while(true){val date=LocalDate(year++,11,5);if(RoomJournalRepository(db).find(date)==null)return@runBlocking date}
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }}finally{db.close()}
    }
    private fun findCanvas(view: View): JournalCanvas? {
        if(view is JournalCanvas)return view
        if(view is ViewGroup)for(index in 0 until view.childCount)findCanvas(view.getChildAt(index))?.let{return it}
        return null
    }
}
