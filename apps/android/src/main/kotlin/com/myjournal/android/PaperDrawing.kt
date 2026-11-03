package com.myjournal.android

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.myjournal.domain.*

/** Programmatic paper shared by native editing and PDF export. */
object PaperDrawing {
    fun draw(canvas: Canvas, paint: Paint, surface: InkSurface) {
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(255,252,245)
        canvas.drawRect(0f,0f,surface.width,surface.height,paint)
        surface.calendar?.let { CalendarPaper.draw(canvas,paint,it); return }
        paint.color = Color.rgb(208,218,224); paint.strokeWidth = 0.8f
        when (surface.paper) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED -> {
                var y = 96f
                while (y < surface.height - 40) { canvas.drawLine(48f,y,surface.width - 48,y,paint); y += 32 }
                paint.color = Color.rgb(220,191,181)
                canvas.drawLine(84f,48f,84f,surface.height - 40,paint)
            }
            PaperTemplate.GRID -> {
                var x = 40f; while (x < surface.width - 40) { canvas.drawLine(x,40f,x,surface.height - 40,paint); x += 32 }
                var y = 40f; while (y < surface.height - 40) { canvas.drawLine(40f,y,surface.width - 40,y,paint); y += 32 }
            }
            PaperTemplate.DOTTED -> {
                var y = 48f
                while (y < surface.height - 40) {
                    var x = 48f; while (x < surface.width - 40) { canvas.drawCircle(x,y,1.2f,paint); x += 32 }; y += 32
                }
            }
        }
    }
}
