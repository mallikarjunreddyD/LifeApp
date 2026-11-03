package com.myjournal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private enum class Destination(val title: String, val mark: String) {
    Daily("Daily Journal", "D"), Monthly("Monthly Planner", "M"),
    Habits("Habit Tracker", "H"), Notebook("Notebook", "N"), Settings("Settings", "S")
}

private val PaperColors = lightColorScheme(
    primary = Color(0xFF52694F), onPrimary = Color.White,
    background = Color(0xFFF8F5ED), onBackground = Color(0xFF302F2B),
    surface = Color(0xFFFFFCF5), onSurface = Color(0xFF302F2B),
    secondaryContainer = Color(0xFFE4EADB), onSecondaryContainer = Color(0xFF263C26),
)

@Composable
fun JournalApp(dailyJournal: (@Composable () -> Unit)? = null) {
    MaterialTheme(colorScheme = PaperColors, typography = Typography(
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
    )) {
        var selectedName by rememberSaveable { mutableStateOf(Destination.Daily.name) }
        val selected = Destination.valueOf(selectedName)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.safeDrawingPadding()) {
                val wide = maxWidth >= 720.dp
                Column {
                    Text("My Journal", style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(24.dp))
                    Row(Modifier.weight(1f)) {
                        if (wide) NavigationRail {
                            Destination.entries.forEach { destination ->
                                NavigationRailItem(selected == destination,
                                    onClick = { selectedName = destination.name },
                                    icon = { Text(destination.mark) }, label = { Text(destination.title) })
                            }
                        }
                        if (selected == Destination.Daily && dailyJournal != null) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { dailyJournal() }
                        } else Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(selected.title, style = MaterialTheme.typography.headlineMedium)
                            Text("A quiet place for your thoughts.", style = MaterialTheme.typography.bodyLarge)
                            HorizontalDivider()
                            Text("Phase 0 · Foundation preview", style = MaterialTheme.typography.labelLarge)
                            Text(if (selected == Destination.Settings)
                                "No settings are available yet. This app has no account or network connection."
                            else "This space is reserved for ${selected.title.lowercase()}. Writing, editing and saving are not available yet.")
                        }
                    }
                    if (!wide) NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(selected == destination,
                                onClick = { selectedName = destination.name },
                                icon = { Text(destination.mark) }, label = { Text(destination.title) })
                        }
                    }
                }
            }
        }
    }
}
