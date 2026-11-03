package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.datetime.*
import kotlin.math.floor
import kotlin.uuid.Uuid
import kotlin.time.Clock

fun newRecordMetadata(): RecordMetadata {
    val at = Clock.System.now()
    return RecordMetadata(Uuid.random(), at, at)
}

data class JournalMonth(val year: Int, val month: Int) : Comparable<JournalMonth> {
    init { require(year in 1..9999); LocalDate(year, month, 1) }
    val firstDate get() = LocalDate(year, month, 1)
    val days get() = firstDate.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
    val mondayOffset get() = firstDate.dayOfWeek.ordinal
    val weeks get() = maxOf(5, (mondayOffset + days + 6) / 7)
    fun date(day: Int) = LocalDate(year, month, day)
    fun next() = from(firstDate.plus(1, DateTimeUnit.MONTH))
    fun previous() = from(firstDate.minus(1, DateTimeUnit.MONTH))
    override fun compareTo(other: JournalMonth) = compareValuesBy(this, other, { it.year }, { it.month })
    override fun toString() = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"
    companion object {
        fun from(date: LocalDate) = JournalMonth(date.year, date.month.ordinal + 1)
        fun parse(value: String) = from(LocalDate.parse("$value-01"))
    }
}

data class LogicalRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** Version 1 is fixed Monday-first geometry, independent of the viewport. */
data class CalendarLayout(
    val month: JournalMonth,
    val version: Int = 1,
    val rows: Int = month.weeks,
    val width: Float = 1400f,
    val headerHeight: Float = 70f,
    val cellHeight: Float = 120f,
) {
    init {
        require(version == 1 && rows == month.weeks)
        require(width == 1400f && headerHeight == 70f && cellHeight == 120f)
    }
    val height get() = headerHeight + rows * cellHeight
    val cellWidth get() = width / 7
    fun cell(day: Int): LogicalRect {
        require(day in 1..month.days)
        val position = month.mondayOffset + day - 1
        val x = position % 7 * cellWidth
        val y = headerHeight + position / 7 * cellHeight
        return LogicalRect(x, y, x + cellWidth, y + cellHeight)
    }
    fun dateAt(x: Float, y: Float): LocalDate? {
        if (!x.isFinite() || !y.isFinite() || x < 0 || x >= width || y < headerHeight || y >= height) return null
        val position = floor((y - headerHeight) / cellHeight).toInt() * 7 + floor(x / cellWidth).toInt()
        val day = position - month.mondayOffset + 1
        return day.takeIf { it in 1..month.days }?.let(month::date)
    }
    fun dateNumberAt(x: Float, y: Float): LocalDate? = dateAt(x, y)?.takeIf {
        val box = cell(it.day)
        x < box.left + 100 && y < box.top + 90
    }
}

data class InkSurface(val id: Uuid, val width: Float, val height: Float, val calendar: CalendarLayout? = null)
fun JournalPage.inkSurface() = InkSurface(metadata.id, width, height)

data class MonthlyPlanner(val metadata: RecordMetadata, val layout: CalendarLayout) {
    val month get() = layout.month
    val surface get() = InkSurface(metadata.id, layout.width, layout.height, layout)
}

data class PlannerEdit(val planner: MonthlyPlanner, val put: List<InkStroke> = emptyList(), val remove: Set<Uuid> = emptySet()) {
    init {
        require(put.all { it.pageId == planner.metadata.id })
        require(put.map { it.metadata.id }.distinct().size == put.size)
        require(put.none { it.metadata.id in remove })
        put.forEach { stroke ->
            val date = requireNotNull(planner.layout.dateAt(stroke.points.first().x, stroke.points.first().y))
            val rect = planner.layout.cell(date.day)
            require(stroke.points.all { rect.contains(it.x, it.y) })
        }
    }
}

interface MonthlyPlannerRepository {
    suspend fun find(month: JournalMonth): MonthlyPlanner?
    suspend fun loadStrokes(surfaceId: Uuid): List<InkStroke>
    suspend fun apply(edit: PlannerEdit)
}
