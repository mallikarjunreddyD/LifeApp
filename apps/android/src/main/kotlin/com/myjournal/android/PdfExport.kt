package com.myjournal.android

import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/** PDF-safe paths, not the hardware/native Ink renderer or screen captures. */
object PdfExport {
    suspend fun write(file: File, pages: List<ExportPage>) = withContext(Dispatchers.Default) {
        require(pages.isNotEmpty()) { "No saved pages match this selection" }
        require(pages.size <= 500) { "Export at most 500 pages per PDF" }
        try {
            val pdf = PdfDocument()
            try {
                pages.forEachIndexed { index, source ->
                    coroutineContext.ensureActive()
                    require(source.surface.width <= 10000 && source.surface.height <= 10000)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(ceil(source.surface.width).toInt(),ceil(source.surface.height).toInt() + 48,index + 1).create())
                    try {
                    val canvas = page.canvas
                    canvas.drawColor(Color.rgb(255,252,245))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    paint.color = Color.rgb(48,47,43); paint.textSize = 22f
                    canvas.drawText(source.title.take(200),24f,32f,paint)
                    canvas.save(); canvas.translate(0f,48f); canvas.clipRect(0f,0f,source.surface.width,source.surface.height)
                    PaperDrawing.draw(canvas,paint,source.surface)
                    for (stroke in source.strokes.sortedBy { it.order }) {
                        coroutineContext.ensureActive()
                        canvas.save()
                        source.surface.calendar?.let { layout ->
                            val date = requireNotNull(layout.dateAt(stroke.points.first().x,stroke.points.first().y))
                            val cell = layout.cell(date.day); canvas.clipRect(cell.left,cell.top,cell.right,cell.bottom)
                        }
                        drawStroke(canvas,paint,stroke)
                        canvas.restore()
                    }
                    canvas.restore()
                    } finally { pdf.finishPage(page) }
                }
                FileOutputStream(file).use { pdf.writeTo(it); it.fd.sync() }
            } finally { pdf.close() }
            require(file.length() > 0)
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
    private fun drawStroke(canvas: Canvas, paint: Paint, stroke: InkStroke) {
        paint.color = stroke.colorArgb.toInt(); paint.style = Paint.Style.FILL
        if (stroke.tool == BrushTool.HIGHLIGHTER) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke.width
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            val path = Path(); val first = stroke.points.first(); path.moveTo(first.x,first.y)
            stroke.points.drop(1).forEach { path.lineTo(it.x,it.y) }
            if (stroke.points.size == 1) { paint.style = Paint.Style.FILL; canvas.drawCircle(first.x,first.y,stroke.width / 2,paint) }
            else canvas.drawPath(path,paint)
            paint.style = Paint.Style.FILL; return
        }
        // A single filled pressure envelope avoids per-segment alpha accumulation.
        val path = Path()
        fun radius(point: InkPoint) = stroke.width / 2 * (0.25f + 0.75f * (point.pressure ?: 1f))
        stroke.points.forEach { path.addCircle(it.x,it.y,radius(it),Path.Direction.CW) }
        stroke.points.zipWithNext().forEach { (a,b) ->
            val length = hypot(b.x-a.x,b.y-a.y)
            if (length > 0f) {
                val nx = -(b.y-a.y)/length; val ny=(b.x-a.x)/length
                val ra=radius(a); val rb=radius(b)
                path.moveTo(a.x+nx*ra,a.y+ny*ra); path.lineTo(a.x-nx*ra,a.y-ny*ra)
                path.lineTo(b.x-nx*rb,b.y-ny*rb); path.lineTo(b.x+nx*rb,b.y+ny*rb); path.close()
            }
        }
        canvas.drawPath(path,paint)
    }
}
