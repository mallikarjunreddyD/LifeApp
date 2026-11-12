package com.myjournal.android

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.myjournal.domain.JournalMonth
import com.myjournal.domain.WorkoutLog
import com.myjournal.domain.monthlyReport
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object WorkoutPdfExport {
    suspend fun write(file: File, log: WorkoutLog, month: JournalMonth, compact: Boolean) = withContext(Dispatchers.Default) {
        val report = log.monthlyReport(month)
        val names = log.exercises.associate { it.metadata.id to it.name }
        val width = if (compact) 842 else 595
        val height = if (compact) 595 else 842
        val margin = 36
        val contentWidth = width - margin * 2
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = if (compact) 10f else 11f
        }
        val heading = TextPaint(paint).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 18f
        }
        val pdf = PdfDocument()
        var page: PdfDocument.Page? = null
        var number = 0
        var y = 0
        fun nextPage(): PdfDocument.Page {
            page?.let(pdf::finishPage)
            page = null
            require(number < 500) { "Export at most 500 pages per PDF" }
            val fresh = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, ++number).create())
            page = fresh
            fresh.canvas.drawText("LifeApp Workout Report", margin.toFloat(), 45f, heading)
            fresh.canvas.drawText("$month  ·  Page $number", margin.toFloat(), 63f, paint)
            y = 80
            return fresh
        }
        fun paragraph(value: String) {
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, contentWidth).build()
            var first = 0
            while (first < layout.lineCount) {
                val target = page ?: nextPage()
                val room = height - margin - y
                val top = layout.getLineTop(first)
                var end = first
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= room) end++
                if (end == first) {
                    nextPage()
                    continue
                }
                val used = layout.getLineBottom(end - 1) - top
                val canvas = target.canvas
                canvas.save()
                canvas.clipRect(margin, y, width - margin, y + used)
                canvas.translate(margin.toFloat(), (y - top).toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += used + 3
                first = end
            }
        }
        try {
            paragraph("Recorded workouts  ${report.sessions.size}")
            paragraph("Total workout minutes  ${minutes(report.totalDurationSeconds?.toDouble())}")
            paragraph("Average workout minutes  ${minutes(report.averageDurationSeconds)}  ·  ${report.durations.size} measured sessions")
            paragraph("Average heart rate  ${number(report.averageHeartRateBpm, " BPM")}  ·  ${report.heartRates.size} measured sessions")
            paragraph("Recorded calories  ${report.totalCalories?.let { "$it kcal" } ?: "Unavailable"}  ·  ${report.calories.size} measured sessions")
            if (report.sessions.isEmpty()) paragraph("No recorded workouts this month.")
            for (session in report.sessions) {
                ensureActive()
                if (height - margin - y < 48) nextPage()
                paragraph("${session.date}  ·  ${session.routineName ?: "Workout"}  ·  ${number(session.durationSeconds?.toDouble()?.div(60), " min")}  ·  " +
                    "${number(session.averageHeartRateBpm?.toDouble(), " BPM")}  ·  " +
                    (session.caloriesKcal?.let { "$it kcal" } ?: "Calories unavailable"))
                for (entry in session.entries) {
                    ensureActive()
                    val name = names[entry.exerciseId] ?: "Exercise"
                    val sets = entry.sets.map { set ->
                        listOfNotNull(
                            "Set ${set.number}",
                            set.weight?.let { "${it.value} ${it.unit}" },
                            set.repetitions?.let { "$it reps" },
                            set.durationSeconds?.let { "$it s" },
                        ).joinToString(" · ")
                    }
                    if (compact) paragraph("$name  |  ${sets.joinToString("   /   ").ifEmpty { "No sets recorded" }}")
                    else {
                        if (height - margin - y < 36) nextPage()
                        paragraph(name)
                        if (sets.isEmpty()) paragraph("No sets recorded") else sets.forEach(::paragraph)
                    }
                    entry.remarks?.takeIf { it.isNotBlank() }?.let { paragraph("Remarks  $it") }
                }
                session.notes?.let { paragraph("Notes  $it") }
                y += 8
            }
            page?.let(pdf::finishPage)
            page = null
            FileOutputStream(file).use { pdf.writeTo(it); it.fd.sync() }
            require(file.length() > 0)
        } finally {
            page?.let(pdf::finishPage)
            pdf.close()
        }
    }

    private fun minutes(seconds: Double?) = number(seconds?.div(60), "")
    private fun number(value: Double?, unit: String) =
        value?.let { String.format(Locale.ROOT, "%.1f%s", it, unit) } ?: "Unavailable"
}
