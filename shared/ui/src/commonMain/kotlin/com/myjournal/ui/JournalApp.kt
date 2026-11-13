package com.myjournal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val AppSection.title: String get() = when (this) {
    AppSection.JOURNAL -> "Daily Journal"; AppSection.PLANNER -> "Monthly Planner"
    AppSection.WORKOUTS -> "Workouts"
    AppSection.EXPENSES -> "Expenses"
    AppSection.HABITS -> "Habit Tracker"; AppSection.NOTEBOOK -> "Notebook"; AppSection.SETTINGS -> "Settings"; AppSection.TODO -> "To-do"
}

private val AppSection.shortLabel get() = when (this) {
    AppSection.JOURNAL -> "Journal"
    AppSection.PLANNER -> "Planner"
    AppSection.HABITS -> "Habits"
    else -> title
}

private val LifeAppColors = lightColorScheme(
    primary = Color(0xFF144C3C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EAE0), onPrimaryContainer = Color(0xFF163C30),
    secondary = Color(0xFF52665B), secondaryContainer = Color(0xFFE1ECE4),
    onSecondaryContainer = Color(0xFF173A2D),
    tertiary = Color(0xFF894C34), tertiaryContainer = Color(0xFFF8DFD1),
    background = Color(0xFFF3F1E9), onBackground = Color(0xFF192721),
    surface = Color(0xFFFFFDF6), onSurface = Color(0xFF192721),
    surfaceVariant = Color(0xFFE8ECE3), onSurfaceVariant = Color(0xFF45584F),
    surfaceContainer = Color(0xFFF5F4EB), surfaceContainerHigh = Color(0xFFEAECE2),
    outline = Color(0xFF697970), outlineVariant = Color(0xFFCDD6CC),
    error = Color(0xFF9D3930), errorContainer = Color(0xFFFFE2DA),
)

private val LifeAppShapes = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

private val destinationOrder = listOf(
    AppSection.JOURNAL, AppSection.PLANNER, AppSection.NOTEBOOK, AppSection.TODO,
    AppSection.HABITS, AppSection.WORKOUTS, AppSection.EXPENSES, AppSection.SETTINGS,
)

@Composable
fun JournalApp(dailyJournal: (@Composable () -> Unit)? = null,
    monthlyPlanner: (@Composable () -> Unit)? = null, habitTracker: (@Composable () -> Unit)? = null,
    notebook: (@Composable () -> Unit)? = null, settings: (@Composable () -> Unit)? = null,
    selectedSection: AppSection? = null, onSectionSelected: ((AppSection) -> Unit)? = null,
    globalStatus: (@Composable () -> Unit)? = null, todo: (@Composable () -> Unit)? = null,
    workouts: (@Composable () -> Unit)? = null, expenses: (@Composable () -> Unit)? = null) {
    val typography = Typography()
    MaterialTheme(colorScheme = LifeAppColors, shapes = LifeAppShapes, typography = typography.copy(
        headlineMedium = typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )) {
        var selectedName by rememberSaveable { mutableStateOf(AppSection.JOURNAL.name) }
        val selected = selectedSection ?: AppSection.valueOf(selectedName)
        val destinations = destinationOrder.filter {
            (it != AppSection.TODO || todo != null) && (it != AppSection.WORKOUTS || workouts != null) &&
                (it != AppSection.EXPENSES || expenses != null)
        }
        val holder = rememberSaveableStateHolder()
        fun select(section: AppSection) {
            if (onSectionSelected != null) onSectionSelected(section) else selectedName = section.name
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.safeDrawingPadding()) {
                val wide = maxWidth >= 720.dp
                val compactColumns = if (maxWidth < 600.dp) 4 else destinations.size
                Column {
                    // Status stays inside a fixed region so a save never shifts the ink surface.
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Row(
                            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LifeAppMark()
                            Text("LifeApp", style = MaterialTheme.typography.titleLarge)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { globalStatus?.invoke() }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.weight(1f)) {
                        if (wide) {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(
                                    Modifier.width(174.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                                        .selectableGroup().padding(horizontal = 10.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    destinations.forEach { destination ->
                                        val active = selected == destination
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                                    .selectable(active, role = Role.Tab) { select(destination) }
                                                    .semantics { contentDescription = "Open ${destination.title}" }
                                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                DestinationIcon(destination, active)
                                                Text(destination.title, style = MaterialTheme.typography.labelLarge)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val screen = when (selected) {
                            AppSection.JOURNAL -> dailyJournal
                            AppSection.PLANNER -> monthlyPlanner
                            AppSection.HABITS -> habitTracker
                            AppSection.NOTEBOOK -> notebook
                            AppSection.SETTINGS -> settings
                            AppSection.TODO -> todo
                            AppSection.WORKOUTS -> workouts
                            AppSection.EXPENSES -> expenses
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
                    if (!wide) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 6.dp, vertical = 4.dp)) {
                                destinations.chunked(compactColumns).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        row.forEach { destination ->
                                            val active = selected == destination
                                            Surface(
                                                modifier = Modifier.weight(1f),
                                                shape = MaterialTheme.shapes.medium,
                                                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                            ) {
                                                Column(
                                                    Modifier.fillMaxWidth().heightIn(min = 49.dp)
                                                        .selectable(active, role = Role.Tab) { select(destination) }
                                                        .semantics { contentDescription = "Open ${destination.title}" }
                                                        .padding(horizontal = 2.dp, vertical = 4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                                                ) {
                                                    DestinationIcon(destination, active)
                                                    Text(destination.shortLabel, style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                        repeat(compactColumns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
