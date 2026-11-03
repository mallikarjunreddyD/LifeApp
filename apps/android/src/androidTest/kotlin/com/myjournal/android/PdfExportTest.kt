package com.myjournal.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.myjournal.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Rasterize the generated PDF to prove actual ink, colours, geometry and page order. */
class PdfExportTest {
    private fun stroke(surface: InkSurface, color: Long, tool: BrushTool=BrushTool.PEN, x: Float=200f,y: Float=300f) =
        InkStroke(newRecordMetadata(),surface.id,0,tool,color,20f,listOf(InkPoint(x,y,0,1f),InkPoint(x+150,y,20,1f)))
    private fun render(file: File, check: (PdfRenderer)->Unit) {
        val renderer=PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY))
        try { check(renderer) } finally { renderer.close() }
    }
    private fun image(renderer: PdfRenderer,index: Int): Bitmap {
        val page=renderer.openPage(index)
        try {
            val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return bitmap
        } finally {page.close()}
    }
    @Test fun journalAndNotebookPdfsContainVisiblePenHighlighterTemplatesAndOrderedPages() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<JournalApplication>()
        val file=File.createTempFile("lifeapp-pdf", ".pdf",app.cacheDir)
        val book=Notebook(newRecordMetadata(),"Research")
        val a=NotebookPage(newRecordMetadata(),book.metadata.id,1,paper=PaperTemplate.GRID)
        val b=NotebookPage(newRecordMetadata(),book.metadata.id,0,paper=PaperTemplate.DOTTED)
        val red=stroke(b.surface,0xFFFF0000)
        val blue=stroke(a.surface,0xFF0000FF)
        val marker=stroke(b.surface,0x5500FF00,BrushTool.HIGHLIGHTER,y=400f).copy(order=1)
        val data=JournalSnapshot(notebooks=listOf(book),notebookPages=listOf(a,b),strokes=listOf(red,blue,marker))
        try {
            PdfExport.write(file,data.notebookPdf(book.metadata.id))
            assertTrue(file.length()>1000)
            render(file) { renderer ->
                assertEquals(2,renderer.pageCount)
                val first=image(renderer,0);val second=image(renderer,1)
                assertEquals(1000,first.width);assertEquals(1462,first.height)
                val pen=first.getPixel(250,348);assertTrue(Color.red(pen)>220 && Color.green(pen)<60 && Color.blue(pen)<60)
                val other=second.getPixel(250,348);assertTrue(Color.blue(other)>220 && Color.red(other)<60)
                val highlighter=first.getPixel(250,448)
                assertTrue(Color.green(highlighter)>220);assertTrue(Color.red(highlighter) in 100..220);assertTrue(Color.blue(highlighter) in 100..220)
                // Template marks are visible away from vectors and title.
                assertNotEquals(first.getPixel(48,96),first.getPixel(60,108))
                assertNotEquals(second.getPixel(72,120),second.getPixel(70,118))
                first.recycle();second.recycle()
            }
            val entryMeta=newRecordMetadata();val journalPage=JournalPage(newRecordMetadata(),entryMeta.id,0)
            val journal=JournalSnapshot(journals=listOf(JournalEntry(entryMeta,kotlinx.datetime.LocalDate(2026,11,3),listOf(journalPage))),
                strokes=listOf(stroke(journalPage.inkSurface(),0xFF000000)))
            PdfExport.write(file,journal.journalPdf(kotlinx.datetime.LocalDate(2026,11,3),kotlinx.datetime.LocalDate(2026,11,3)))
            render(file){renderer->val bitmap=image(renderer,0);assertTrue(Color.red(bitmap.getPixel(250,348))<40);bitmap.recycle()}
        } finally {file.delete()}
    }
    @Test fun monthlyPlannerPdfKeepsCellGeometryAndInkAtCanonicalCoordinates() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<JournalApplication>();val file=File.createTempFile("lifeapp-calendar", ".pdf",app.cacheDir)
        val planner=MonthlyPlanner(newRecordMetadata(),CalendarLayout(JournalMonth(2026,11)))
        val cell=planner.layout.cell(12)
        val ink=stroke(planner.surface,0xFFFF0000,x=cell.left+110,y=cell.top+80).copy(points=listOf(InkPoint(cell.left+110,cell.top+80,0,1f),InkPoint(cell.left+170,cell.top+80,10,1f)))
        try {
            PdfExport.write(file,JournalSnapshot(planners=listOf(planner),strokes=listOf(ink)).plannerPdf(planner.month))
            render(file){renderer->val bitmap=image(renderer,0)
                assertEquals(1400,bitmap.width);assertEquals(planner.layout.height.toInt()+48,bitmap.height)
                val pixel=bitmap.getPixel((cell.left+130).toInt(),(cell.top+128).toInt())
                assertTrue(Color.red(pixel)>220 && Color.green(pixel)<60)
                assertNotEquals(bitmap.getPixel(200,200),bitmap.getPixel(205,200));bitmap.recycle()
            }
        } finally {file.delete()}
    }
}
