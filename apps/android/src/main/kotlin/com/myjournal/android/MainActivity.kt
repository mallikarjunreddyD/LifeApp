package com.myjournal.android

import android.os.Bundle
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.myjournal.domain.AppSection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import com.myjournal.domain.JournalMonth
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.myjournal.ui.JournalApp

class MainActivity : ComponentActivity() {
    private lateinit var model: JournalViewModel
    private var activeSync: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
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
            var previousHabitPending by remember { mutableIntStateOf(habits.pending) }
            LaunchedEffect(habits.pending) {
                if (previousHabitPending > 0 && habits.pending == 0 && habits.error == null) {
                    applicationState.serverSync.scheduleEditableUpload()
                }
                previousHabitPending = habits.pending
            }
            BackHandler(enabled = routes.size > 1 || routes.last().section == com.myjournal.domain.AppSection.NOTEBOOK && notebook.notebook != null) {
                if (routes.last().section == com.myjournal.domain.AppSection.NOTEBOOK && notebook.notebook != null) model.openNotebook(null) else model.back()
            }
            if(operation.busy) androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false)) {
                Surface { Column(Modifier.padding(24.dp)) { CircularProgressIndicator();Text(operation.message ?: "Working…") } }
            }
            var reportMonth by rememberSaveable { mutableStateOf<String?>(null) }
            var compactReport by rememberSaveable { mutableStateOf(false) }
            val exportWorkout = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
                val month = reportMonth
                reportMonth = null
                if (uri != null && month != null) {
                    applicationState.operations.exportWorkoutPdf(uri, JournalMonth.parse(month), compactReport)
                }
            }
            JournalApp(dailyJournal = { DailyJournalScreen(model) },
                monthlyPlanner = { MonthlyPlannerScreen(model) }, habitTracker = { HabitTrackerScreen(model) },
                notebook = { NotebookScreen(model) }, settings = { SettingsScreen(applicationState.operations, applicationState.serverSync) },
                expenses = {
                    AndroidExpenses(applicationState)
                },
                workouts = {
                    com.myjournal.ui.WorkoutScreen(
                        applicationState.workouts,
                        today(),
                        reportStatus = operation.message?.takeIf { !operation.busy && "Workout PDF" in it },
                    ) { month, compact ->
                        reportMonth = month.toString()
                        compactReport = compact
                        exportWorkout.launch("LifeApp-Workouts-$month.pdf")
                    }
                },
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
        val app = application as JournalApplication
        observeSyncConnectivity(app)
        app.serverSync.onForeground()
        app.expenseSync.schedule()
        app.operations.syncTrackedOnForeground(app.serverSync)
        activeSync = lifecycleScope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                app.serverSync.schedule()
                app.serverSync.scheduleEditableUpload()
                app.expenseSync.schedule()
            }
        }
    }

    private fun observeSyncConnectivity(app: JournalApplication) {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        fun connected() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        var online = connected()
        app.serverSync.networkChanged(online)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun update() = runOnUiThread {
                if (networkCallback !== this) return@runOnUiThread
                val available = connected()
                if (available && !online) {
                    app.serverSync.schedule()
                    app.serverSync.scheduleEditableUpload()
                    app.expenseSync.schedule()
                }
                online = available
                app.serverSync.networkChanged(available)
            }

            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = update()
        }
        networkCallback = callback
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback,
        )
    }

    override fun onStop() {
        activeSync?.cancel()
        activeSync = null
        networkCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        networkCallback = null
        super.onStop()
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
