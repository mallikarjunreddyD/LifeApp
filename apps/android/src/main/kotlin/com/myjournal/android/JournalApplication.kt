package com.myjournal.android

import android.app.Application
import com.myjournal.data.RoomJournalRepository
import com.myjournal.data.RoomPlannerRepository
import com.myjournal.data.RoomHabitRepository
import com.myjournal.domain.PlannerEditor
import com.myjournal.domain.HabitEditor
import com.myjournal.domain.JournalMonth
import com.myjournal.data.openJournalDatabase
import com.myjournal.domain.JournalEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

fun today() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

class JournalApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database by lazy { openJournalDatabase(this) }
    val planner by lazy { PlannerEditor(RoomPlannerRepository(database), applicationScope, JournalMonth.from(today())) }
    val habits by lazy { HabitEditor(RoomHabitRepository(database), applicationScope, JournalMonth.from(today())) }
    val editor by lazy { JournalEditor(RoomJournalRepository(database), applicationScope, today()) }
}
