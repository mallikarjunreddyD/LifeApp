package com.myjournal.android

import android.app.Application
import com.myjournal.data.RoomJournalRepository
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
    val editor by lazy { JournalEditor(RoomJournalRepository(database), applicationScope, today()) }
}
