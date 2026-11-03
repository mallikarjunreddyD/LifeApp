package com.myjournal.data

import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun backupFixture(): JournalSnapshot {
    var sequence=0L
    fun meta() = RecordMetadata(Uuid.random(),Instant.parse("2026-01-01T00:00:00.123456789Z"),Instant.parse("2026-10-01T12:00:00.987654321Z"),1,++sequence)
    val journals=(1..2).map { day -> val m=meta();JournalEntry(m,kotlinx.datetime.LocalDate(2026,11,day),(0..1).map { JournalPage(meta(),m.id,it) }) }
    val planners=listOf(JournalMonth(2026,11),JournalMonth(2026,12)).map { MonthlyPlanner(meta(),CalendarLayout(it)) }
    val habits=listOf(JournalMonth(2026,11),JournalMonth(2026,12)).map { month ->
        val m=meta(); val a=HabitDefinition(meta(),m.id,"Exercise",0,"Health",0xFF00AA00,20)
        val b=HabitDefinition(meta(),m.id,"Read Books",1,"Mind",0xFF0000FF,10,true)
        MonthlyHabitLedger(m,month,listOf(a,b),listOf(HabitCompletion(meta(),a.metadata.id,month.date(12),true),
            HabitCompletion(meta(),a.metadata.id,month.date(13),false),HabitCompletion(meta(),b.metadata.id,month.date(12),true)))
    }
    val books=listOf(Notebook(meta(),"Research Notes","Paper and vector study",PaperTemplate.RULED),Notebook(meta(),"Archived Ideas",null,PaperTemplate.BLANK,true))
    val pages=PaperTemplate.entries.mapIndexed { i,paper -> NotebookPage(meta(),books[i/2].metadata.id,1-i%2,"Title $i",paper,archived=i==3) }.sortedBy { it.metadata.id.toString() }
    val surfaces=journals.flatMap { it.pages }.map { it.inkSurface() }+planners.map { it.surface }+pages.map { it.surface }
    val strokes=surfaces.flatMapIndexed { index,surface ->
        val rect=surface.calendar?.cell(12)
        listOf(BrushTool.PEN,BrushTool.HIGHLIGHTER).mapIndexed { order,tool -> InkStroke(meta(),surface.id,order.toLong(),tool,
            if(tool==BrushTool.PEN) 0xFF2859A8 else 0x55F0B020,if(tool==BrushTool.PEN) 4f else 18f,
            listOf(InkPoint((rect?.left ?: 0f)+110f,(rect?.top ?: 0f)+80f,0,0.25f),InkPoint((rect?.left ?: 0f)+150f,(rect?.top ?: 0f)+90f,17,0.9f),
                InkPoint((rect?.left ?: 0f)+170f,(rect?.top ?: 0f)+100f,34,null))) }
    }.sortedBy { it.metadata.id.toString() }
    return JournalSnapshot(listOf(meta()),journals,planners,habits,books,pages,strokes).canonical().also { it.validate() }
}
