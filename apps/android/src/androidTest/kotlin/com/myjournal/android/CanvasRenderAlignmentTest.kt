package com.myjournal.android

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.myjournal.data.*
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.uuid.Uuid

/** Actual hardware-window pixels, including native Ink layers; never Canvas(bitmap) substitutes. */
class CanvasRenderAlignmentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private class Surface(val page: InkSurface, val viewport: Viewport) {
        lateinit var view: JournalCanvas
        val strokes = mutableListOf<InkStroke>()
        var tool = EditorTool.PEN
        var date: LocalDate? = null
        var nextOrder = 0L
        fun bind() = view.bindSurface(page,strokes.toList(),tool,0xFFFF0000,6f,viewport,{},
            { brush,color,width,points -> InkStroke(newRecordMetadata(),page.id,nextOrder++,brush,color,width,points).also { strokes.add(it) } },
            { ids -> strokes.removeAll { it.metadata.id in ids } }, onDateTap = { date=it })
    }
    private fun waitFor(message: String, test: ()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+15_000
        while(SystemClock.uptimeMillis()<deadline) { if(test())return;SystemClock.sleep(20) }
        fail(message)
    }
    private fun mount(scenario: ActivityScenario<MainActivity>, surface: Surface) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            JournalCanvas.debugAlignmentEnabled=false
            val width=min(1400,activity.window.decorView.width-120)
            val height=min(900,activity.window.decorView.height-200)
            assertTrue(width>400 && height>300)
            val host=FrameLayout(activity)
            val view=JournalCanvas(activity);surface.view=view
            host.addView(view,FrameLayout.LayoutParams(width,height).apply { leftMargin=37;topMargin=53 })
            activity.setContentView(host);surface.bind()
        }
        waitFor("Native child views were not measured to the editor bounds") {
            var ready=false
            scenario.onActivity { ready=surface.view.width>0 && surface.view.height>0 && surface.view.getChildAt(0).width==surface.view.width && surface.view.getChildAt(1).height==surface.view.height }
            ready
        }
    }
    /** Independent expected physical coordinate, rather than using the adapter's mapping helper. */
    private fun expected(surface: Surface, logical: PointF): PointF {
        val view=surface.view;val page=surface.page;val viewport=surface.viewport
        val scale=(if(viewport.fitPage) min(view.width/page.width,view.height/page.height) else view.width/page.width)*viewport.zoom
        val tx=if(viewport.fitPage && page.width*scale<view.width) (view.width-page.width*scale)/2 else
            (view.width/2f-viewport.centerX*scale).coerceIn(min(0f,view.width-page.width*scale),0f)
        val ty=if(viewport.fitPage && page.height*scale<view.height) (view.height-page.height*scale)/2 else
            (view.height/2f-viewport.centerY*scale).coerceIn(min(0f,view.height-page.height*scale),0f)
        return PointF(logical.x*scale+tx,logical.y*scale+ty)
    }
    private fun event(view: JournalCanvas, action: Int, point: PointF, time: Long, finger: Boolean=false) {
        val properties=MotionEvent.PointerProperties().apply { id=7;toolType=if(finger) MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_STYLUS }
        val coordinates=MotionEvent.PointerCoords().apply { x=point.x;y=point.y;pressure=1f;size=0.01f }
        val event=MotionEvent.obtain(time,time,action,1,arrayOf(properties),arrayOf(coordinates),0,0,1f,1f,0,0,
            if(finger) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS,0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
    private fun begin(scenario: ActivityScenario<MainActivity>, surface: Surface, points: List<PointF>): Long {
        val time=SystemClock.uptimeMillis()
        scenario.onActivity {
            event(surface.view,MotionEvent.ACTION_DOWN,points.first(),time)
            points.drop(1).forEachIndexed { i,point -> event(surface.view,MotionEvent.ACTION_MOVE,point,time+(i+1)*16) }
        }
        return time
    }
    private fun finish(scenario: ActivityScenario<MainActivity>, surface: Surface, point: PointF, time: Long) {
        scenario.onActivity { event(surface.view,MotionEvent.ACTION_UP,point,time+100);surface.bind() }
    }
    private fun capture(scenario: ActivityScenario<MainActivity>, view: View): Bitmap {
        val result=CountDownLatch(1);var status=-1
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        scenario.onActivity { activity ->
            view.invalidate()
            view.postOnAnimation { view.postOnAnimation {
                val location=IntArray(2);view.getLocationInWindow(location)
                PixelCopy.request(activity.window,Rect(location[0],location[1],location[0]+view.width,location[1]+view.height),bitmap,
                    { status=it;result.countDown() },Handler(Looper.getMainLooper()))
            } }
        }
        assertTrue("PixelCopy did not complete",result.await(10,TimeUnit.SECONDS))
        assertEquals("PixelCopy failed",PixelCopy.SUCCESS,status)
        return bitmap
    }
    private fun red(color: Int) = Color.red(color)>170 && Color.green(color)<110 && Color.blue(color)<110
    private fun assertInkAt(bitmap: Bitmap, point: PointF, stage: String) {
        var xSum=0f;var ySum=0f;var count=0
        for(y in (point.y.toInt()-20).coerceAtLeast(0)..(point.y.toInt()+20).coerceAtMost(bitmap.height-1))
            for(x in (point.x.toInt()-20).coerceAtLeast(0)..(point.x.toInt()+20).coerceAtMost(bitmap.width-1))
                if(red(bitmap.getPixel(x,y))) { xSum+=x;ySum+=y;count++ }
        assertTrue("$stage: no rendered ink near physical touch $point",count>0)
        assertTrue("$stage: x displacement ${xSum/count-point.x}px",abs(xSum/count-point.x)<=5f)
        assertTrue("$stage: y displacement ${ySum/count-point.y}px",abs(ySum/count-point.y)<=5f)
    }
    private fun assertNoInk(bitmap: Bitmap, point: PointF) {
        for(y in point.y.toInt()-3..point.y.toInt()+3) for(x in point.x.toInt()-3..point.x.toInt()+3)
            assertFalse("Unexpected ink at $x,$y",red(bitmap.getPixel(x,y)))
    }
    @Test fun wetDryAndPersistedJournalPixelsAlignAtCornersAndCenterForAllZooms() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val context=instrumentation.targetContext
            val db=Room.inMemoryDatabaseBuilder<FoundationDatabase>(context).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                var index=0
                for(zoom in listOf(1f,1.5f,2f)) for(logical in listOf(PointF(40f,40f),PointF(960f,40f),PointF(40f,1374f),PointF(960f,1374f),PointF(500f,707f))) {
                    val entry=newRecordMetadata();val page=JournalPage(newRecordMetadata(),entry.id,0,paper=PaperTemplate.BLANK)
                    val viewport=Viewport(zoom,logical.x,logical.y)
                    val surface=Surface(page.inkSurface(),viewport);mount(scenario,surface)
                    val point=expected(surface,logical)
                    val time=begin(scenario,surface,listOf(point,PointF(point.x+1,point.y+1)))
                    capture(scenario,surface.view).let { assertInkAt(it,point,"wet zoom=$zoom logical=$logical");it.recycle() }
                    finish(scenario,surface,point,time)
                    capture(scenario,surface.view).let { assertInkAt(it,point,"dry zoom=$zoom logical=$logical");it.recycle() }
                    val date=LocalDate(2301,1,++index)
                    val saved=surface.strokes.toList()
                    assertEquals(logical.x,saved.single().points.first().x,0.05f)
                    assertEquals(logical.y,saved.single().points.first().y,0.05f)
                    runBlocking { RoomJournalRepository(db).apply(PageEdit(date,page,saved)) }
                    val restored=Surface(page.inkSurface(),viewport)
                    restored.strokes.addAll(runBlocking { RoomJournalRepository(db).loadStrokes(page.metadata.id) })
                    mount(scenario,restored)
                    waitFor("Restored native geometry was not reconstructed") { var ready=false;scenario.onActivity{ready=restored.view.nativeInkReady};ready }
                    capture(scenario,restored.view).let { assertInkAt(it,point,"Room reload zoom=$zoom logical=$logical");it.recycle() }
                    assertEquals(saved,restored.strokes)
                    if(zoom==1f && logical.x==40f && logical.y==40f) {
                        capture(scenario,restored.view).let {
                            assertTrue("Paper must reach the actual writing width",Color.red(it.getPixel(it.width-4,100))>245)
                            it.recycle()
                        }
                    }
                }
            } finally { db.close() }
        }
    }
    @Test fun calendarCellClippingDateTapsAndAllNotebookTemplatesUseTheSamePixelMapping() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val layout=CalendarLayout(JournalMonth(2026,11));val planner=MonthlyPlanner(newRecordMetadata(),layout)
            val cell=layout.cell(12)
            val surface=Surface(planner.surface,Viewport(1.5f,cell.left+100,cell.top+60));mount(scenario,surface)
            val logical=PointF(cell.left+130,cell.top+80);val point=expected(surface,logical)
            val time=begin(scenario,surface,listOf(point,PointF(point.x+1,point.y+1)))
            capture(scenario,surface.view).let { assertInkAt(it,point,"calendar wet");it.recycle() }
            finish(scenario,surface,point,time)
            PlannerEdit(planner,surface.strokes)
            val crossEnd=expected(surface,PointF(cell.right+40,cell.top+80))
            val crossing=begin(scenario,surface,listOf(point,crossEnd));finish(scenario,surface,crossEnd,crossing)
            assertTrue(surface.strokes.all { it.points.all { p->cell.contains(p.x,p.y) } })
            capture(scenario,surface.view).let { assertNoInk(it,expected(surface,PointF(cell.right+12,cell.top+80)));it.recycle() }
            val number=expected(surface,PointF(cell.left+25,cell.top+30));val count=surface.strokes.size
            scenario.onActivity { event(surface.view,MotionEvent.ACTION_DOWN,number,time+200,true);event(surface.view,MotionEvent.ACTION_UP,number,time+220,true) }
            assertEquals(layout.month.date(12),surface.date);assertEquals(count,surface.strokes.size)
            for(template in PaperTemplate.entries) {
                val book=Notebook(newRecordMetadata(),"Pixel test",defaultTemplate=template)
                val page=NotebookPage(newRecordMetadata(),book.metadata.id,0,paper=template)
                val notebook=Surface(page.surface,Viewport(2f,620f,830f));mount(scenario,notebook)
                notebook.tool=EditorTool.HIGHLIGHTER;scenario.onActivity{notebook.bind()}
                val center=expected(notebook,PointF(620f,830f))
                val started=begin(scenario,notebook,listOf(center,PointF(center.x+3,center.y+2)));finish(scenario,notebook,center,started)
                // Highlighter is translucent red. Compare against adjacent paper, independent of its template.
                capture(scenario,notebook.view).let { bitmap ->
                    val pixel=bitmap.getPixel(center.x.toInt(),center.y.toInt())
                    assertTrue("Highlighter missing for $template",Color.red(pixel)-Color.green(pixel)>30)
                    bitmap.recycle()
                }
                assertEquals(BrushTool.HIGHLIGHTER,notebook.strokes.single().tool)
                assertEquals(0x55FF0000L,notebook.strokes.single().colorArgb)
            }
        }
    }
    @Suppress("DEPRECATION")
    @Test fun actualDisplayRotationKeepsDiagonalInkAndEraserAtPhysicalTouchCoordinates() {
        val automation=instrumentation.uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                for(rotation in listOf(0,1)) {
                    assertTrue(automation.setRotation(rotation))
                    waitFor("Display did not rotate") { var current=-1;scenario.onActivity { current=it.windowManager.defaultDisplay.rotation };current==rotation }
                    val page=JournalPage(newRecordMetadata(),Uuid.random(),0,paper=PaperTemplate.BLANK)
                    val surface=Surface(page.inkSurface(),Viewport(1.5f,500f,707f));mount(scenario,surface)
                    val center=PointF(surface.view.width/2f,surface.view.height/2f)
                    val diagonal=listOf(PointF(center.x-80,center.y-80),center,PointF(center.x+80,center.y+80))
                    val start=begin(scenario,surface,diagonal)
                    capture(scenario,surface.view).let { assertInkAt(it,center,"rotated wet diagonal $rotation");it.recycle() }
                    finish(scenario,surface,diagonal.last(),start)
                    capture(scenario,surface.view).let { assertInkAt(it,center,"rotated dry diagonal $rotation");it.recycle() }
                    val stroke=surface.strokes.single()
                    scenario.onActivity { surface.tool=EditorTool.ERASER;surface.bind() }
                    val erase=begin(scenario,surface,listOf(center));finish(scenario,surface,center,erase)
                    assertTrue("Eraser must hit the stroke at its visible position",surface.strokes.isEmpty())
                    capture(scenario,surface.view).let { assertNoInk(it,center);it.recycle() }
                    assertTrue(stroke.points.size>=3)
                }
            } finally { automation.setRotation(UiAutomation.ROTATION_UNFREEZE) }
        }
    }
}
