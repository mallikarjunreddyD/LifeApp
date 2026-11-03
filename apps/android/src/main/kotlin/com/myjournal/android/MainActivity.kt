package com.myjournal.android

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = viewModelFactory {
            initializer { (application as JournalApplication).let { app ->
                JournalViewModel(app.editor, createSavedStateHandle(), app.planner, app.habits)
            } }
        }
        val model = ViewModelProvider(this, factory)[JournalViewModel::class.java]
        setContent {
            val routes by model.navigation.stack.collectAsState()
            val journal by model.editor.state.collectAsState()
            val planner by model.planner.state.collectAsState()
            val habits by model.habits.state.collectAsState()
            BackHandler(enabled = routes.size > 1) { model.back() }
            JournalApp(dailyJournal = { DailyJournalScreen(model) },
                monthlyPlanner = { MonthlyPlannerScreen(model) }, habitTracker = { HabitTrackerScreen(model) },
                selectedSection = routes.last().section, onSectionSelected = model::selectSection,
                globalStatus = {
                    listOf(Triple("Journal", journal.error, model.editor::retry),
                        Triple("Planner", planner.error, model.planner::retry), Triple("Habits", habits.error, model.habits::retry))
                        .forEach { (name, error, retry) -> if (error != null) Row(Modifier.padding(horizontal = 12.dp)) {
                            Text("$name: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = retry) { Text("Retry $name") }
                        } }
                    val pending = journal.pending + planner.pending + habits.pending
                    if (pending > 0) Text("Saving $pending local changes…", Modifier.padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.labelSmall)
                })
        }
    }
}
