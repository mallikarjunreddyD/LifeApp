package com.myjournal.android

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.myjournal.domain.CalendarLayout
import java.text.DateFormatSymbols

internal object CalendarPaper {
    fun draw(canvas: Canvas, paint: Paint, layout: CalendarLayout) {
        paint.color = Color.rgb(195, 205, 209); paint.strokeWidth = 1f
        for (column in 0..7) canvas.drawLine(column * layout.cellWidth, layout.headerHeight,
            column * layout.cellWidth, layout.height, paint)
        for (row in 0..layout.rows) {
            val y = layout.headerHeight + row * layout.cellHeight
            canvas.drawLine(0f, y, layout.width, y, paint)
        }
        paint.color = Color.rgb(48, 47, 43); paint.textSize = 30f
        val weekdays = DateFormatSymbols().shortWeekdays
        val indices = listOf(2, 3, 4, 5, 6, 7, 1)
        indices.forEachIndexed { column, day -> canvas.drawText(weekdays[day], column * layout.cellWidth + 14, 45f, paint) }
        paint.typeface = Typeface.DEFAULT_BOLD
        for (day in 1..layout.month.days) {
            val rect = layout.cell(day)
            canvas.drawText(day.toString(), rect.left + 14, rect.top + 34, paint)
        }
        paint.typeface = Typeface.DEFAULT
    }
}
