package com.myjournal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.myjournal.domain.AppSection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val AppSection.title: String get() = when (this) {
    AppSection.JOURNAL -> "Daily Journal"; AppSection.PLANNER -> "Monthly Planner"
    AppSection.HABITS -> "Habit Tracker"; AppSection.NOTEBOOK -> "Notebook"; AppSection.SETTINGS -> "Settings"; AppSection.TODO -> "To-do"
}

private val PaperColors = lightColorScheme(
    primary = Color(0xFF52694F), onPrimary = Color.White,
    background = Color(0xFFF8F5ED), onBackground = Color(0xFF302F2B),
    surface = Color(0xFFFFFCF5), onSurface = Color(0xFF302F2B),
    secondaryContainer = Color(0xFFE4EADB), onSecondaryContainer = Color(0xFF263C26),
)

@Composable
fun JournalApp(dailyJournal: (@Composable () -> Unit)? = null,
    monthlyPlanner: (@Composable () -> Unit)? = null, habitTracker: (@Composable () -> Unit)? = null,
    notebook: (@Composable () -> Unit)? = null, settings: (@Composable () -> Unit)? = null,
    selectedSection: AppSection? = null, onSectionSelected: ((AppSection) -> Unit)? = null,
    globalStatus: (@Composable () -> Unit)? = null, todo: (@Composable () -> Unit)? = null) {
    MaterialTheme(colorScheme = PaperColors, typography = Typography(
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
    )) {
        var selectedName by rememberSaveable { mutableStateOf(AppSection.JOURNAL.name) }
        val selected = selectedSection ?: AppSection.valueOf(selectedName)
        val destinations = AppSection.entries.filter { it != AppSection.TODO || todo != null }
        val holder = rememberSaveableStateHolder()
        fun select(section: AppSection) {
            if (onSectionSelected != null) onSectionSelected(section) else selectedName = section.name
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.safeDrawingPadding()) {
                val wide = maxWidth >= 720.dp
                Column {
                    // Saving a gesture must not insert a row above the paper and
                    // move the contact origin while the next gesture is underway.
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("My Journal", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Column(Modifier.weight(1f)) { globalStatus?.invoke() }
                    }
                    Row(Modifier.weight(1f)) {
                        if (wide) NavigationRail {
                            destinations.forEach { destination ->
                                NavigationRailItem(selected == destination,
                                    onClick = { select(destination) },
                                    icon = { Text(destination.title.take(1)) }, label = { Text(destination.title) },
                                    modifier = Modifier.semantics { contentDescription = "Open ${destination.title}" })
                            }
                        }
                        val screen = when (selected) {
                            AppSection.JOURNAL -> dailyJournal
                            AppSection.PLANNER -> monthlyPlanner
                            AppSection.HABITS -> habitTracker
                            AppSection.NOTEBOOK -> notebook
                            AppSection.SETTINGS -> settings
                            AppSection.TODO -> todo
                        }
                        if (screen != null) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                holder.SaveableStateProvider(selected.name) { screen() }
                            }
                        } else Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(selected.title, style = MaterialTheme.typography.headlineMedium)
                            Text("A quiet place for your thoughts.", style = MaterialTheme.typography.bodyLarge)
                            HorizontalDivider()
                            Text("Phase 0 · Foundation preview", style = MaterialTheme.typography.labelLarge)
                            Text(if (selected == AppSection.SETTINGS)
                                "No settings are available yet. This app has no account or network connection."
                            else "This space is reserved for ${selected.title.lowercase()}. Writing, editing and saving are not available yet.")
                        }
                    }
                    if (!wide) NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(selected == destination,
                                onClick = { select(destination) },
                                icon = { Text(destination.title.take(1)) }, label = { Text(destination.title) },
                                    modifier = Modifier.semantics { contentDescription = "Open ${destination.title}" })
                        }
                    }
                }
            }
        }
    }
}
