package com.myjournal.android

import android.os.Bundle
import android.content.Intent
import com.myjournal.domain.AppSection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.myjournal.ui.JournalApp

class MainActivity : ComponentActivity() {
    private lateinit var model: JournalViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = viewModelFactory {
            initializer { (application as JournalApplication).let { app ->
                JournalViewModel(app.editor, createSavedStateHandle(), app.planner, app.habits, app.notebooks)
            } }
        }
        model = ViewModelProvider(this, factory)[JournalViewModel::class.java]
        openReminder(intent, model)
        if (savedInstanceState == null) TodoReminderScheduler(applicationContext).reschedule()
        setContent {
            val applicationState = application as JournalApplication
            val originalGeneration = remember { applicationState.generation.value }
            val generation by applicationState.generation.collectAsState()
            val operation by applicationState.operations.state.collectAsState()
            LaunchedEffect(generation) {
                if(generation != originalGeneration) { viewModelStore.clear();recreate() }
            }
            val routes by model.navigation.stack.collectAsState()
            val journal by model.editor.state.collectAsState()
            val planner by model.planner.state.collectAsState()
            val habits by model.habits.state.collectAsState()
            val notebook by model.notebooks.state.collectAsState()
            BackHandler(enabled = routes.size > 1 || routes.last().section == com.myjournal.domain.AppSection.NOTEBOOK && notebook.notebook != null) {
                if (routes.last().section == com.myjournal.domain.AppSection.NOTEBOOK && notebook.notebook != null) model.openNotebook(null) else model.back()
            }
            if(operation.busy) androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false)) {
                Surface { Column(Modifier.padding(24.dp)) { CircularProgressIndicator();Text(operation.message ?: "Working…") } }
            }
            JournalApp(dailyJournal = { DailyJournalScreen(model) },
                monthlyPlanner = { MonthlyPlannerScreen(model) }, habitTracker = { HabitTrackerScreen(model) },
                notebook = { NotebookScreen(model) }, settings = { SettingsScreen(applicationState.operations, applicationState.serverSync) },
                todo = { TodoScreen(applicationState.todos, applicationState.todoActions) },
                selectedSection = routes.last().section, onSectionSelected = model::selectSection,
                globalStatus = {
                    listOf(Triple("Journal", journal.error, model.editor::retry),
                        Triple("Planner", planner.error, model.planner::retry), Triple("Habits", habits.error, model.habits::retry), Triple("Notebook", notebook.error, model.notebooks::retry))
                        .forEach { (name, error, retry) -> if (error != null) Row(Modifier.padding(horizontal = 12.dp)) {
                            Text("$name: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = retry) { Text("Retry $name") }
                        } }
                    val pending = journal.pending + planner.pending + habits.pending + notebook.pending
                    if (pending > 0) Text("Saving $pending local changes…", Modifier.padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.labelSmall)
                })
        }
    }
    override fun onStart() {
        super.onStart()
        (application as JournalApplication).serverSync.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReminder(intent, model)
    }

    private fun openReminder(intent: Intent, model: JournalViewModel) {
        if (intent.action == OPEN_TODO) {
            model.selectSection(AppSection.TODO)
            intent.action = null
        }
    }
}
