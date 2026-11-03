package com.myjournal.android

import android.app.Application
import com.myjournal.data.*
import com.myjournal.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

fun today() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

class EditorSession(database: FoundationDatabase) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val editor = JournalEditor(RoomJournalRepository(database),scope,today())
    val planner = PlannerEditor(RoomPlannerRepository(database),scope,JournalMonth.from(today()))
    val habits = HabitEditor(RoomHabitRepository(database),scope,JournalMonth.from(today()))
    val notebooks = NotebookEditor(RoomNotebookRepository(database),scope)
    fun cancel() = scope.cancel()
    val pending get() = editor.state.value.pending + planner.state.value.pending + habits.state.value.pending + notebooks.state.value.pending
    val error get() = listOf(editor.state.value.error,planner.state.value.error,habits.state.value.error,notebooks.state.value.error).firstOrNull { it != null }
}
class JournalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A process restart has no in-flight operation; discard abandoned private staging files.
        cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("lifeapp-") }?.forEach { it.delete() }
    }
    private val database by lazy { openJournalDatabase(this) }
    private var currentSession: EditorSession? = null
    val session get() = currentSession ?: EditorSession(database).also { currentSession=it }
    val editor get() = session.editor
    val planner get() = session.planner
    val habits get() = session.habits
    val notebooks get() = session.notebooks
    private val generationMutable = MutableStateFlow(0)
    val generation = generationMutable.asStateFlow()
    val operations by lazy { DataOperations(this,RoomSnapshotRepository(database)) }
    fun rebuildEditors() {
        currentSession?.cancel(); currentSession = EditorSession(database)
        generationMutable.value++
    }
}
