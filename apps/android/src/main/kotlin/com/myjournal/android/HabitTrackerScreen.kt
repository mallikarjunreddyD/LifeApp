package com.myjournal.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.myjournal.domain.*
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun HabitTrackerScreen(model: JournalViewModel) {
    val state by model.habits.state.collectAsState()
    val month = state.month
    var editing by remember { mutableStateOf<HabitDefinition?>(null) }
    var editDialog by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    val scroll = model.habitScroll(month)
    val vertical = remember(month) { LazyListState(scroll[0], scroll[1]) }
    val horizontal = remember(month) { ScrollState(scroll[2]) }
    LaunchedEffect(month, vertical, horizontal) {
        snapshotFlow { Triple(vertical.firstVisibleItemIndex, vertical.firstVisibleItemScrollOffset, horizontal.value) }
            .collect { model.rememberHabitScroll(month, it.first, it.second, it.third) }
    }
    val ledger = state.ledger
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        MonthControls(month, { model.changeMonth(AppSection.HABITS, it) })
        Row {
            Text(when {
                state.loading -> "Loading habit ledger…"
                state.error != null -> state.error!!
                state.pending > 0 -> "Saving habits…"
                ledger == null -> "Choose how to start this month"
                else -> "Habits saved locally"
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (state.error != null) TextButton(onClick = model.habits::retry) { Text("Retry habits") }
        }
        if (!state.loading && ledger == null && state.error == null) {
            Text("Each month has its own habits. Nothing is copied automatically.")
            val previous = state.previous
            Button(enabled = previous != null, onClick = model.habits::copyPrevious) {
                Text(if (previous != null) "Copy habits from ${previous.month.title()}" else "No previous configured month")
            }
            Text("Copies active definitions only, without checkmarks or progress. Targets are limited to this month's day count.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model.habits::createBlank) { Text("Create blank ledger") }
        }
        if (!state.loading && ledger != null) {
            Row {
                TextButton(onClick = { editing = null; editDialog = true }) { Text("Add habit") }
                TextButton(onClick = { manage = true }) { Text("Manage habits") }
                Text("${month.days} days · Tap a day to open its journal", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 18.dp))
            }
            val active = ledger.activeHabits
            // Date column and habit header stay visible. Every scrollable row shares one offset.
            Row(Modifier.fillMaxWidth()) {
                Text("Day", Modifier.width(56.dp).padding(vertical = 12.dp))
                Row(Modifier.weight(1f).horizontalScroll(horizontal).semantics { contentDescription = "Habit headers" }) {
                    if (active.isEmpty()) Text("Add your first habit", Modifier.padding(12.dp))
                    active.forEach { habit -> Column(Modifier.width(144.dp).padding(horizontal = 4.dp)) {
                        TextButton(onClick = { editing = habit; editDialog = true }) {
                            Text(habit.name, color = habit.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurface, maxLines = 2)
                        }
                        habit.category?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    } }
                }
            }
            HorizontalDivider()
            LazyColumn(state = vertical, modifier = Modifier.fillMaxWidth().weight(1f)
                .semantics { contentDescription = "Habit days" }) {
                items((1..month.days).toList(), key = { it }) { day ->
                    Row(Modifier.fillMaxWidth().height(56.dp)) {
                        TextButton(onClick = { model.openJournal(month.date(day)) }, modifier = Modifier.width(56.dp).fillMaxHeight()
                            .semantics { contentDescription = "Open journal ${month.date(day)}" }) { Text(day.toString()) }
                        Row(Modifier.weight(1f).horizontalScroll(horizontal)) {
                            active.forEach { habit -> Box(Modifier.width(144.dp).fillMaxHeight(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Checkbox(checked = ledger.checked(habit.metadata.id, day),
                                    onCheckedChange = { model.habits.toggle(habit.metadata.id, month.date(day)) },
                                    modifier = Modifier.semantics { contentDescription = "${habit.name}, ${month.date(day)}" })
                            } }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                Text("Totals", Modifier.width(56.dp).padding(vertical = 10.dp), style = MaterialTheme.typography.labelSmall)
                Row(Modifier.weight(1f).horizontalScroll(horizontal)) {
                    active.forEach { habit -> Column(Modifier.width(144.dp).padding(8.dp)) {
                        Text("${ledger.count(habit.metadata.id)} / ${month.days} days", style = MaterialTheme.typography.labelMedium)
                        Text("${(ledger.percentage(habit.metadata.id) * 10).roundToInt() / 10f}%", style = MaterialTheme.typography.labelSmall)
                        habit.target?.let { Text("Target ${ledger.count(habit.metadata.id)} / $it", style = MaterialTheme.typography.labelSmall) }
                    } }
                }
            }
        } else Spacer(Modifier.weight(1f))
    }
    if (editDialog && ledger != null) {
        HabitEditDialog(editing, month, onDismiss = { editDialog = false }, onSave = { name, category, color, target ->
            model.habits.saveHabit(editing?.metadata?.id, name, category, color, target)
            editDialog = false
        })
    }
    if (manage && ledger != null) AlertDialog(onDismissRequest = { manage = false }, title = { Text("Manage monthly habits") },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(ledger.habits.sortedBy { it.order }, key = { it.metadata.id.toString() }) { habit ->
                Column {
                    Text(habit.name, style = MaterialTheme.typography.titleSmall)
                    if (habit.archived) Text("Archived · ${ledger.count(habit.metadata.id)} completed days retained", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { manage = false; editing = habit; editDialog = true }) { Text("Edit") }
                        if (habit.archived) TextButton(modifier = Modifier.semantics { contentDescription = "Restore habit ${habit.name}" }, onClick = { model.habits.archive(habit.metadata.id, false) }) { Text("Restore") }
                        else {
                            TextButton(enabled = ledger.activeHabits.firstOrNull()?.metadata?.id != habit.metadata.id,
                                modifier = Modifier.semantics { contentDescription = "Move left habit ${habit.name}" }, onClick = { model.habits.move(habit.metadata.id, -1) }) { Text("Move left") }
                            TextButton(enabled = ledger.activeHabits.lastOrNull()?.metadata?.id != habit.metadata.id,
                                onClick = { model.habits.move(habit.metadata.id, 1) }) { Text("Move right") }
                            TextButton(modifier = Modifier.semantics { contentDescription = "Archive habit ${habit.name}" }, onClick = { model.habits.archive(habit.metadata.id) }) { Text("Archive") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        } }, confirmButton = { TextButton(onClick = { manage = false }) { Text("Done") } })
}

@Composable
private fun HabitEditDialog(habit: HabitDefinition?, month: JournalMonth, onDismiss: () -> Unit,
    onSave: (String, String?, Long?, Int?) -> Unit) {
    var name by remember(habit?.metadata?.id) { mutableStateOf(habit?.name ?: "") }
    var category by remember(habit?.metadata?.id) { mutableStateOf(habit?.category ?: "") }
    var target by remember(habit?.metadata?.id) { mutableStateOf(habit?.target?.toString() ?: "") }
    var color by remember(habit?.metadata?.id) { mutableStateOf(habit?.colorArgb) }
    val targetValue = target.toIntOrNull()
    val valid = name.trim().length in 1..80 && category.trim().length <= 80 &&
        (target.isBlank() || targetValue != null && targetValue in 1..month.days)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (habit == null) "Add habit" else "Edit habit") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Habit name") }, singleLine = true)
            OutlinedTextField(category, { category = it }, label = { Text("Category (optional)") }, singleLine = true)
            OutlinedTextField(target, { target = it }, label = { Text("Target days (optional, 1–${month.days})") }, singleLine = true,
                isError = target.isNotBlank() && (targetValue == null || targetValue !in 1..month.days))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("No colour" to null, "Blue" to 0xFF2859A8L, "Green" to 0xFF3F704CL, "Red" to 0xFFB43F3FL).forEach { (label, value) ->
                    FilterChip(selected = color == value, onClick = { color = value }, label = { Text(label) })
                }
            }
            Text("Archiving removes a column without deleting its completion history.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(name.trim(), category.trim().takeIf { it.isNotEmpty() }, color, targetValue) }) { Text(if (habit == null) "Add to month" else "Apply changes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
