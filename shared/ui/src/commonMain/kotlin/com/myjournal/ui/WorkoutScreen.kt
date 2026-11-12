package com.myjournal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Composable
fun WorkoutScreen(
    repository: WorkoutRepository,
    today: LocalDate,
    reportStatus: String? = null,
    exportReport: ((JournalMonth, Boolean) -> Unit)? = null,
) {
    var log by remember { mutableStateOf<WorkoutLog?>(null) }
    var draft by remember { mutableStateOf<WorkoutDraft?>(null) }
    var spreadsheet by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var validDate by remember(draft?.session?.metadata?.id) { mutableStateOf(true) }
    val invalidNumbers = remember(draft?.session?.metadata?.id) { mutableStateMapOf<String, Boolean>() }
    var editor by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var pendingDelete by remember { mutableStateOf<WorkoutSession?>(null) }
    var library by rememberSaveable { mutableStateOf(false) }
    var routines by rememberSaveable { mutableStateOf(false) }
    var deleted by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var month by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var routineFilter by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val historyPosition = rememberLazyListState()
    val draftPosition = key(draft?.session?.metadata?.id) { rememberLazyListState() }

    LaunchedEffect(repository) {
        try {
            draft = repository.loadDraft()
            spreadsheet = repository.spreadsheetHistory()
            ready = true
            repository.observe().collect { log = it }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            error = "Could not load saved workouts."
        }
    }
    LaunchedEffect(repository, draft, ready) {
        if (ready && draft != null) {
            try {
                repository.saveDraft(draft)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error = "Could not save the workout draft on this device."
            }
        }
    }

    fun save(action: suspend () -> Unit) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                action()
                editor = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message?.takeUnless { it == "Failed requirement." } ?: "Check the workout values and try again."
            } finally {
                busy = false
            }
        }
    }

    val data = log
    if (data == null) {
        Text(error ?: "Loading workouts…", Modifier.padding(20.dp))
        return
    }
    val names = data.exercises.associate { it.metadata.id to it.name }
    val activeExercises = data.exercises.filterNot { it.archived }.sortedBy { it.name }
    val current = draft
    fun update(change: (WorkoutSession) -> WorkoutSession) {
        if (!busy) draft = draft?.let { it.copy(session = change(it.session)) }
    }
    fun editExercise(exercise: ExerciseDefinition?) {
        editor = {
            WorkoutFields(if (exercise == null) "New exercise" else "Edit exercise", listOf(
                "Name" to exercise?.name.orEmpty(), "Category" to exercise?.category.orEmpty(),
                "Description" to exercise?.description.orEmpty(),
            ), busy, { editor = null }, error) { values ->
                save {
                    repository.saveExercise(ExerciseDefinition(exercise?.metadata?.edit() ?: newRecordMetadata(),
                        values[0].trim(), values[1].nonblank(), exercise?.equipment, exercise?.archived ?: false,
                        values[2].nonblank()))
                }
            }
        }
    }
    fun editRoutine(routine: WorkoutRoutine?) {
        editor = {
            RoutineDialog(routine, data.exercises, busy, error, { editor = null }) { name, order, plans ->
                save { repository.saveRoutine(WorkoutRoutine(routine?.metadata?.edit() ?: newRecordMetadata(), name, order, plans)) }
            }
        }
    }

    val sessions = data.sessions.sortedWith(compareByDescending<WorkoutSession> { it.date }
        .thenByDescending { it.metadata.createdAt })
    val visible = sessions.filter { !it.deleted && it.date.toString().startsWith(month.trim()) &&
        it.date.toString().startsWith(date.trim()) && (routineFilter.isEmpty() || it.routineName == routineFilter) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().testTag("workout-logbook"),
        state = if (current == null) historyPosition else draftPosition,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Workouts", style = MaterialTheme.typography.headlineMedium)
            Text("Saved on this device")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (current != null) {
            val session = current.session
            val routine = data.routines.find { it.metadata.id == session.routineId }
            item(key = "workout-header-${session.metadata.id}") {
                Text(if (current.previousRevision == null) "Add workout" else "Edit workout",
                    style = MaterialTheme.typography.titleLarge)
                var dateText by rememberSaveable { mutableStateOf(session.date.toString()) }
                OutlinedTextField(dateText, { value ->
                    dateText = value
                    val day = runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.year in 1..9999 }
                    validDate = day != null
                    if (day != null) update { it.copy(date = day) }
                }, label = { Text("Workout date (YYYY-MM-DD)") }, singleLine = true,
                    isError = dateText != session.date.toString())
                if (dateText != session.date.toString()) Text("Use a valid date. The draft keeps the last valid date.")
                if (current.previousRevision == null) {
                    WorkoutChoice(session.routineName ?: "Choose routine") { close ->
                        DropdownMenuItem(text = { Text("No routine") }, onClick = {
                            close()
                            if (session.entries.isEmpty()) update { it.copy(routineId = null, routineName = null) }
                            else editor = { ReplaceWorkoutExercises({ editor = null }) {
                                invalidNumbers.keys.filter { it.endsWith("-weight") || it.endsWith("-repetitions") }
                                    .forEach { invalidNumbers.remove(it) }
                                update { it.copy(entries = emptyList(), routineId = null, routineName = null) }
                                editor = null
                            } }
                        })
                        data.routines.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.name) }, onClick = {
                                close()
                                val apply = {
                                    invalidNumbers.keys.filter { it.endsWith("-weight") || it.endsWith("-repetitions") }
                                        .forEach { invalidNumbers.remove(it) }
                                    update { it.fromRoutine(choice) }
                                }
                                if (session.entries.isEmpty()) apply()
                                else editor = { ReplaceWorkoutExercises({ editor = null }) { apply(); editor = null } }
                            })
                        }
                    }
                } else session.routineName?.let { Text(it) }
                Row {
                    TextButton(onClick = { editRoutine(routine) }) { Text(if (routine == null) "New routine" else "Edit routine") }
                    if (routine != null && session.entries.isNotEmpty()) {
                        TextButton(enabled = !busy, onClick = {
                            save { repository.saveRoutine(routine.copy(metadata = routine.metadata.edit(),
                                exerciseOrder = session.entries.map { it.exerciseId },
                                plannedSetCounts = session.entries.map { it.sets.size.takeIf { size -> size > 0 } })) }
                        }) { Text("Save changes to routine") }
                    }
                }
            }
            items(session.entries, key = { it.metadata.id.toString() }) { entry ->
                WorkoutEntryCard(entry, names[entry.exerciseId] ?: "Exercise", session.entries.size, { offset ->
                    update { it.copy(entries = it.entries.move(entry.order, offset).mapIndexed { index, value -> value.copy(order = index) }) }
                }, {
                    entry.sets.forEach { row ->
                        invalidNumbers.remove("${row.metadata.id}-weight")
                        invalidNumbers.remove("${row.metadata.id}-repetitions")
                    }
                    update { it.copy(entries = it.entries.filterNot { value -> value.metadata.id == entry.metadata.id }
                        .mapIndexed { index, value -> value.copy(order = index) }) }
                }, { key, invalid ->
                    if (invalid) invalidNumbers[key] = true else invalidNumbers.remove(key)
                }) { edited ->
                    update { it.copy(entries = it.entries.map { value -> if (value.metadata.id == edited.metadata.id) edited else value }) }
                }
            }
            item {
                Row {
                    WorkoutChoice("Add exercise") { close ->
                        activeExercises.forEach { exercise ->
                            DropdownMenuItem(text = { Text(exercise.name) }, onClick = {
                                update { it.copy(entries = it.entries + WorkoutExerciseEntry(newRecordMetadata(),
                                    it.metadata.id, exercise.metadata.id, it.entries.size)) }
                                close()
                            })
                        }
                    }
                    TextButton(onClick = { editExercise(null) }) { Text("New exercise") }
                }
                Text("Workout summary", style = MaterialTheme.typography.titleLarge)
                WorkoutNumberField("Workout minutes", session.durationSeconds?.div(60)?.toString().orEmpty(),
                    maximum = Int.MAX_VALUE / 60, invalid = { invalidNumbers["minutes"] = it }) { raw ->
                    val value = raw.toIntOrNull()
                    if (raw.isBlank() || value != null && value in 0..Int.MAX_VALUE / 60) update {
                        val started = it.startedAt
                        val ended = it.endedAt
                        val elapsed = if (started != null && ended != null)
                            (ended - started).inWholeSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
                        it.copy(durationSeconds = value?.times(60) ?: elapsed, durationManuallyEntered = value != null)
                    }
                }
                WorkoutNumberField("Average heart rate (BPM)", session.averageHeartRateBpm?.toString().orEmpty(),
                    minimum = 20, maximum = 250, invalid = { invalidNumbers["heart-rate"] = it }) { raw ->
                    val value = raw.toIntOrNull()
                    if (raw.isBlank() || value != null && value in 20..250) update { it.copy(averageHeartRateBpm = value) }
                }
                WorkoutNumberField("Active calories", session.caloriesKcal?.toString().orEmpty(),
                    invalid = { invalidNumbers["calories"] = it }) { raw ->
                    val value = raw.toIntOrNull()
                    if (raw.isBlank() || value != null && value >= 0) update { it.copy(caloriesKcal = value) }
                }
                OutlinedTextField(session.notes.orEmpty(), { value -> if (value.length <= 4000) update { it.copy(notes = value.ifEmpty { null }) } },
                    label = { Text("Overall remarks") }, modifier = Modifier.fillMaxWidth())
                Row {
                    Button(enabled = !busy && validDate && invalidNumbers.values.none { it }, onClick = {
                        save {
                            val previous = sessions.find { it.metadata.id == session.metadata.id }
                            repository.saveSession(session.forSave(previous), current.previousRevision)
                            draft = null
                            repository.saveDraft(null)
                        }
                    }) { Text(if (busy) "Saving…" else "Save workout") }
                    TextButton(enabled = !busy, onClick = {
                        editor = {
                            AlertDialog(onDismissRequest = { editor = null }, title = { Text("Discard workout draft?") },
                                confirmButton = { TextButton(onClick = { save { draft = null; repository.saveDraft(null) } }) { Text("Discard") } },
                                dismissButton = { TextButton(onClick = { editor = null }) { Text("Keep editing") } })
                        }
                    }) { Text("Discard draft") }
                }
            }
        } else {
            item {
                Button(enabled = !busy, onClick = {
                    draft = WorkoutDraft(WorkoutSession(newRecordMetadata(), today, null, null, null, logged = true), null)
                }) { Text("Add workout") }
                Text("Workout history", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!spreadsheet, {
                        spreadsheet = false
                        save { repository.setSpreadsheetHistory(false) }
                    }, label = { Text("Workout cards") })
                    FilterChip(spreadsheet, {
                        spreadsheet = true
                        save { repository.setSpreadsheetHistory(true) }
                    }, label = { Text("Spreadsheet") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(month, { month = it }, Modifier.weight(1f), label = { Text("Month (YYYY-MM)") }, singleLine = true)
                    OutlinedTextField(date, { date = it }, Modifier.weight(1f), label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                }
                WorkoutChoice(routineFilter.ifEmpty { "All routines" }) { close ->
                    DropdownMenuItem(text = { Text("All routines") }, onClick = { routineFilter = ""; close() })
                    sessions.mapNotNull { it.routineName }.distinct().sorted().forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { routineFilter = name; close() })
                    }
                }
                if (exportReport != null) TextButton(onClick = {
                    editor = {
                        var reportMonth by remember { mutableStateOf(month.ifEmpty { JournalMonth.from(today).toString() }) }
                        var compact by remember { mutableStateOf(false) }
                        val parsed = runCatching { JournalMonth.parse(reportMonth.trim()) }.getOrNull()
                        AlertDialog(onDismissRequest = { editor = null }, title = { Text("Export monthly PDF") }, text = {
                            Column {
                                OutlinedTextField(reportMonth, { reportMonth = it }, label = { Text("Month (YYYY-MM)") }, singleLine = true)
                                FilterChip(compact, { compact = !compact }, label = { Text("Compact landscape") })
                            }
                        }, confirmButton = { TextButton(enabled = parsed != null, onClick = {
                            exportReport(requireNotNull(parsed), compact); editor = null
                        }) { Text("Save PDF") } }, dismissButton = { TextButton(onClick = { editor = null }) { Text("Cancel") } })
                    }
                }) { Text("Export monthly PDF") }
                reportStatus?.let { Text(it) }
                if (visible.isEmpty()) Text("No workouts match these filters.")
            }
            items(visible, key = { it.metadata.id.toString() }) { session ->
                WorkoutHistoryCard(session, names, spreadsheet, busy,
                    { draft = WorkoutDraft(session, session.metadata.revision) }, { pendingDelete = session })
            }
            if (sessions.any { it.deleted }) {
                item { TextButton(onClick = { deleted = !deleted }) { Text("Recently deleted") } }
                if (deleted) items(sessions.filter { it.deleted }, key = { "deleted-${it.metadata.id}" }) { session ->
                    Text(session.title())
                    TextButton(enabled = !busy, onClick = { save { repository.setSessionDeleted(session.metadata.id, false) } }) {
                        Text("Restore workout")
                    }
                }
            }
            item { TextButton(onClick = { library = !library }) { Text("Exercise library") } }
            if (library) {
                item {
                    OutlinedTextField(query, { query = it }, label = { Text("Search exercises") }, singleLine = true)
                    TextButton(onClick = { editExercise(null) }) { Text("New exercise") }
                }
                items(data.exercises.filter { it.name.contains(query, ignoreCase = true) }.sortedBy { it.name },
                    key = { it.metadata.id.toString() }) { exercise ->
                    Text(exercise.name + if (exercise.archived) " (archived)" else "")
                    listOfNotNull(exercise.category, exercise.description).forEach { Text(it) }
                    Row {
                        TextButton(onClick = { editExercise(exercise) }) { Text("Edit exercise") }
                        TextButton(enabled = !busy, onClick = {
                            save { repository.saveExercise(exercise.copy(metadata = exercise.metadata.edit(), archived = !exercise.archived)) }
                        }) { Text(if (exercise.archived) "Restore exercise" else "Archive exercise") }
                    }
                }
            }
            item { TextButton(onClick = { routines = !routines }) { Text("Manage routines") } }
            if (routines) {
                item { TextButton(onClick = { editRoutine(null) }) { Text("New routine") } }
                items(data.routines, key = { it.metadata.id.toString() }) { routine ->
                    Text(routine.name)
                    Text(routine.exerciseOrder.mapNotNull(names::get).joinToString(" · "))
                    TextButton(onClick = { editRoutine(routine) }) { Text("Edit routine") }
                }
            }
        }
    }
    pendingDelete?.let { session ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete workout?") },
            text = { Text("Move ${session.title()} to Recently deleted?") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                save { repository.setSessionDeleted(session.metadata.id, true); pendingDelete = null }
            }) { Text("Delete workout") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } })
    }
    editor?.invoke()
}

@Composable
private fun WorkoutChoice(label: String, options: @Composable ColumnScope.(() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(open, { open = false }) { options { open = false } }
    }
}

@Composable
private fun ReplaceWorkoutExercises(close: () -> Unit, replace: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Replace exercises in this draft?") },
        confirmButton = { TextButton(onClick = replace) { Text("Replace") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun WorkoutEntryCard(entry: WorkoutExerciseEntry, name: String, count: Int, move: (Int) -> Unit,
    remove: () -> Unit, validation: (String, Boolean) -> Unit, edit: (WorkoutExerciseEntry) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide $name" else "Show $name") }
            if (expanded) {
                Row {
                    TextButton(enabled = entry.order > 0, onClick = { move(-1) }) { Text("Move up") }
                    TextButton(enabled = entry.order + 1 < count, onClick = { move(1) }) { Text("Move down") }
                    TextButton(onClick = remove) { Text("Remove exercise") }
                }
                entry.sets.forEach { set ->
                    key(set.metadata.id) {
                        fun update(value: RecordedSet) = edit(entry.copy(sets = entry.sets.map {
                            if (it.metadata.id == value.metadata.id) value else it
                        }))
                        Text("Set ${set.number}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorkoutNumberField("Weight", set.weight?.value?.toString().orEmpty(), Modifier.weight(1f), true,
                                invalid = { validation("${set.metadata.id}-weight", it) }) { raw ->
                                val value = raw.toDoubleOrNull()
                                if (raw.isBlank() || value != null && value.isFinite() && value >= 0) {
                                    update(set.copy(weight = value?.let { WorkoutWeight(it, set.weight?.unit ?: WeightUnit.KG) }))
                                }
                            }
                            WorkoutNumberField("Repetitions", set.repetitions?.toString().orEmpty(), Modifier.weight(1f),
                                invalid = { validation("${set.metadata.id}-repetitions", it) }) { raw ->
                                val value = raw.toIntOrNull()
                                if (raw.isBlank() || value != null && value >= 0) update(set.copy(repetitions = value,
                                    completed = set.completed && ((value ?: 0) > 0 || set.durationSeconds != null)))
                            }
                        }
                        Row {
                            WeightUnit.entries.forEach { unit ->
                                FilterChip(set.weight?.unit == unit, {
                                    set.weight?.let { update(set.copy(weight = it.copy(unit = unit))) }
                                }, enabled = set.weight != null, label = { Text(unit.name) }, modifier = Modifier.padding(end = 5.dp))
                            }
                            TextButton(onClick = {
                                validation("${set.metadata.id}-weight", false)
                                validation("${set.metadata.id}-repetitions", false)
                                edit(entry.copy(sets = entry.sets.filterNot { it.metadata.id == set.metadata.id }
                                    .mapIndexed { index, row -> row.copy(number = index + 1) }))
                            }) { Text("Remove set") }
                        }
                    }
                }
                Row {
                    TextButton(onClick = { edit(entry.copy(sets = entry.sets + entry.blankSet())) }) { Text("Add set") }
                    TextButton(enabled = entry.sets.isNotEmpty(), onClick = {
                        val last = entry.sets.last()
                        edit(entry.copy(sets = entry.sets + entry.blankSet().copy(weight = last.weight, repetitions = last.repetitions)))
                    }) { Text("Copy previous set") }
                }
                OutlinedTextField(entry.remarks.orEmpty(), { if (it.length <= 4000) edit(entry.copy(remarks = it)) },
                    label = { Text("Exercise remarks") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun WorkoutNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    minimum: Int = 0,
    maximum: Int = Int.MAX_VALUE,
    invalid: (Boolean) -> Unit,
    change: (String) -> Unit,
) {
    var raw by rememberSaveable { mutableStateOf(value) }
    val valid = raw.isBlank() || if (decimal) raw.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true
        else raw.toIntOrNull()?.let { it in minimum..maximum } == true
    LaunchedEffect(valid) { invalid(!valid) }
    OutlinedTextField(raw, { raw = it; change(it) }, modifier, label = { Text(label) }, singleLine = true,
        isError = !valid, supportingText = if (valid) null else {
            { Text(if (decimal) "Enter a nonnegative weight." else "Enter a whole number from $minimum to $maximum.") }
        }, keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}

@Composable
private fun WorkoutHistoryCard(session: WorkoutSession, names: Map<Uuid, String>, spreadsheet: Boolean,
    busy: Boolean, edit: () -> Unit, delete: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.title(), style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull("${session.entries.size} exercises", session.durationSeconds?.let { "${it / 60.0} min" },
                session.averageHeartRateBpm?.let { "$it BPM" }, session.caloriesKcal?.let { "$it kcal" }).joinToString(" · "))
            if (!spreadsheet) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide exercises" else "Show exercises") }
            if (spreadsheet || expanded) {
                Row {
                    Text("Exercise", Modifier.weight(1f))
                    Text("Weight × reps", Modifier.weight(1f))
                    Text("Remarks", Modifier.weight(1f))
                }
                session.entries.forEach { entry ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(names[entry.exerciseId] ?: "Exercise", Modifier.weight(1f))
                        Text(entry.sets.joinToString(", ") { it.summary() }.ifEmpty { "—" }, Modifier.weight(1f))
                        Text(entry.remarks.orEmpty().ifEmpty { "—" }, Modifier.weight(1f))
                    }
                }
                session.notes?.takeIf { it.isNotBlank() }?.let { Text(it) }
            }
            Row {
                TextButton(enabled = !busy, onClick = edit) { Text("Edit workout") }
                TextButton(enabled = !busy, onClick = delete) { Text("Delete workout") }
            }
        }
    }
}

private fun WorkoutSession.fromRoutine(routine: WorkoutRoutine) = copy(routineId = routine.metadata.id, routineName = routine.name,
    entries = routine.exerciseOrder.mapIndexed { index, id ->
        val entry = WorkoutExerciseEntry(newRecordMetadata(), metadata.id, id, index)
        entry.copy(sets = List(routine.plannedSetCounts.getOrNull(index) ?: 0) { set -> entry.blankSet(set + 1) })
    })

private fun WorkoutExerciseEntry.blankSet(number: Int = sets.size + 1) =
    RecordedSet(newRecordMetadata(), metadata.id, number, null, null)

private fun WorkoutSession.forSave(previous: WorkoutSession?) = copy(logged = true,
    metadata = previous?.metadata?.edit() ?: metadata,
    entries = entries.map { entry ->
        val original = previous?.entries?.find { it.metadata.id == entry.metadata.id }
        entry.copy(metadata = original?.metadata?.edit() ?: entry.metadata, sets = entry.sets.map { set ->
            val old = original?.sets?.find { it.metadata.id == set.metadata.id }
            set.copy(metadata = old?.metadata?.edit() ?: set.metadata)
        })
    })

private fun WorkoutSession.title() = "${routineName ?: "Workout"} · $date"
private fun <T> List<T>.move(index: Int, offset: Int) = toMutableList().apply { add(index + offset, removeAt(index)) }
private fun String.nonblank() = trim().ifEmpty { null }
private fun RecordMetadata.edit() = revised(maxOf(updatedAt, Clock.System.now()))
private fun RecordedSet.summary() = when {
    repetitions != null -> weight?.let { "${it.value} ${it.unit} × $repetitions" } ?: "$repetitions reps"
    weight != null -> weight?.let { "${it.value} ${it.unit}" }.orEmpty()
    durationSeconds != null -> "${durationSeconds}s"
    else -> "—"
}

@Composable
private fun WorkoutFields(
    title: String,
    fields: List<Pair<String, String>>,
    busy: Boolean,
    close: () -> Unit,
    error: String? = null,
    numericFields: Set<Int> = emptySet(),
    summary: String? = null,
    submit: (List<String>) -> Unit,
) {
    val values = remember(title) { mutableStateListOf(*fields.map { it.second }.toTypedArray()) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            summary?.let { item { Text(it) } }
            items(fields.size) { index ->
                OutlinedTextField(
                    values[index], { values[index] = it }, label = { Text(fields[index].first) }, singleLine = true,
                    keyboardOptions = if (index in numericFields) KeyboardOptions(keyboardType = KeyboardType.Number)
                    else KeyboardOptions.Default,
                )
            }

        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { submit(values.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("Cancel") } })
}

@Composable
private fun RoutineDialog(
    routine: WorkoutRoutine?,
    exercises: List<ExerciseDefinition>,
    busy: Boolean,
    error: String?,
    close: () -> Unit,
    save: (String, List<Uuid>, List<Int?>) -> Unit,
) {
    var name by remember { mutableStateOf(routine?.name.orEmpty()) }
    val order = remember { mutableStateListOf(*routine?.exerciseOrder.orEmpty().toTypedArray()) }
    val plans = remember { mutableStateListOf(*order.indices.map {
        routine?.plannedSetCounts?.getOrNull(it)?.toString().orEmpty()
    }.toTypedArray()) }
    val validPlans = plans.all { it.isBlank() || it.trim().toIntOrNull()?.let { count -> count in 1..100 } == true }
    val names = exercises.associate { it.metadata.id to it.name }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Workout routine") }, text = {
        LazyColumn(Modifier.heightIn(max = 350.dp)) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { OutlinedTextField(name, { name = it }, label = { Text("Routine name") }, singleLine = true) }
            items(order.size) { index ->
                TextButton(onClick = { order.removeAt(index); plans.removeAt(index) }) { Text("Remove ${index + 1}. ${names[order[index]]}") }
                Row {
                    listOf(-1, 1).forEach { offset ->
                        TextButton(enabled = index + offset in order.indices, onClick = {
                            order.add(index + offset, order.removeAt(index))
                            plans.add(index + offset, plans.removeAt(index))
                        }) { Text(if (offset < 0) "Move up" else "Move down") }
                    }
                }
                OutlinedTextField(plans[index], { plans[index] = it }, label = { Text("Planned sets (optional, 1–100)") }, singleLine = true)
            }
            items(exercises.filterNot { it.archived }) { exercise ->
                TextButton(onClick = { order.add(exercise.metadata.id); plans.add("") }) { Text("Add ${exercise.name}") }
            }
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && order.isNotEmpty() && validPlans,
        onClick = { save(name.trim(), order.toList(), plans.map { it.trim().toIntOrNull() }) }) { Text("Save routine") } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("Cancel") } })
}
