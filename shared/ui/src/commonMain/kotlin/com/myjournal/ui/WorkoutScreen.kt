package com.myjournal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Composable
fun WorkoutScreen(repository: WorkoutRepository, today: LocalDate) {
    var log by remember { mutableStateOf<WorkoutLog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) {
        try {
            repository.observe().collect { log = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Could not load saved workouts."
        }
    }
    fun save(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
                editor = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.takeIf { failure is IllegalArgumentException || failure is IllegalStateException }
                    ?.takeIf { it != "Failed requirement." } ?: "Could not save. Check the workout values and try again."
            } finally {
                busy = false
            }
        }
    }
    val data = log
    if (data == null) {
        Text(error ?: "Loading workouts…", Modifier.padding(24.dp))
        return
    }
    val session = data.sessions.find { it.metadata.id.toString() == activeId }
    val exercises = data.exercises.associateBy { it.metadata.id }
    val available = data.exercises.filterNot { it.archived }.sortedBy { it.name }
    fun change(workout: WorkoutSession, transform: (WorkoutSession) -> WorkoutSession) = save {
        val updated = transform(workout).copy(metadata = workout.metadata.edit())
        repository.saveSession(updated, workout.metadata.revision)
    }
    fun start(routine: WorkoutRoutine? = null) = save {
        val metadata = newRecordMetadata()
        val order = routine?.exerciseOrder.orEmpty()
        require(order.none { exercises[it]?.archived != false }) { "Restore archived exercises before starting this routine." }
        val entries = order.mapIndexed { index, id ->
            val entry = newRecordMetadata()
            val sets = List(routine?.plannedSetCounts?.getOrNull(index) ?: 0) { offset ->
                RecordedSet(newRecordMetadata(), entry.id, offset + 1, null, null)
            }
            WorkoutExerciseEntry(entry, metadata.id, id, index, sets = sets)
        }
        repository.saveSession(WorkoutSession(metadata, today, metadata.createdAt, null, null, entries = entries), null)
        activeId = metadata.id.toString()
    }
    fun editExercise(exercise: ExerciseDefinition?) {
        error = null
        editor = {
            WorkoutFields(
                if (exercise == null) "New exercise" else "Edit exercise",
                listOf("Name" to exercise?.name.orEmpty(), "Category or body part" to exercise?.category.orEmpty(),
                    "Equipment" to exercise?.equipment.orEmpty()), busy, { editor = null }, error = error,
            ) { values ->
                save {
                    repository.saveExercise(ExerciseDefinition(
                        exercise?.metadata?.edit() ?: newRecordMetadata(), values[0].trim(),
                        values[1].trim().ifEmpty { null }, values[2].trim().ifEmpty { null }, exercise?.archived ?: false,
                    ))
                }
            }
        }
    }
    fun editRoutine(routine: WorkoutRoutine?) {
        error = null
        editor = {
            RoutineDialog(routine, data.exercises, busy, error, { editor = null }) { name, order, plans ->
                save { repository.saveRoutine(WorkoutRoutine(routine?.metadata?.edit() ?: newRecordMetadata(), name, order, plans)) }
            }
        }
    }
    fun editSet(workout: WorkoutSession, entry: WorkoutExerciseEntry, set: RecordedSet?) {
        error = null
        editor = {
            WorkoutFields(
                if (set == null) "Add set" else "Edit set ${set.number}",
                listOf("Weight" to set?.weight?.value?.toString().orEmpty(), "Unit (KG or LB)" to (set?.weight?.unit?.name ?: "KG"),
                    "Repetitions" to set?.repetitions?.toString().orEmpty(), "Duration (seconds)" to set?.durationSeconds?.toString().orEmpty(),
                    "Rest (seconds)" to set?.restSeconds?.toString().orEmpty()), busy, { editor = null },
                completed = set?.completed ?: false, error = error,
            ) { values ->
                change(workout) { current ->
                    val weight = values[0].trim().ifEmpty { null }?.let {
                        WorkoutWeight(it.toDouble(), WeightUnit.valueOf(values[1].trim().uppercase()))
                    }
                    val recorded = RecordedSet(set?.metadata?.edit() ?: newRecordMetadata(), entry.metadata.id,
                        set?.number ?: entry.sets.size + 1, weight, values[2].optionalInt(), values[3].optionalInt(),
                        values[4].optionalInt(), values[5] == "true")
                    val sets = if (set == null) entry.sets + recorded else entry.sets.map { if (it.metadata.id == set.metadata.id) recorded else it }
                    current.copy(entries = current.entries.map {
                        if (it.metadata.id == entry.metadata.id) entry.copy(sets = sets) else it
                    })
                }
            }
        }
    }
    fun editSummary(workout: WorkoutSession, finish: Boolean) {
        error = null
        editor = {
            WorkoutFields(
                if (finish) "Finish workout" else "Workout details",
                listOf(
                    "Workout duration (minutes)" to workout.durationSeconds?.div(60)?.toString().orEmpty(),
                    "Average heart rate (BPM)" to workout.averageHeartRateBpm?.toString().orEmpty(),
                    "Date (YYYY-MM-DD)" to workout.date.toString(),
                    "Calories (kcal, optional)" to workout.caloriesKcal?.toString().orEmpty(),
                    "Notes" to workout.notes.orEmpty(),
                ),
                busy, { editor = null }, error = error, numericFields = setOf(0, 1, 3),
                summary = if (finish) {
                    val incomplete = workout.entries.count { entry -> entry.sets.isEmpty() || entry.sets.any { !it.completed } }
                    "$incomplete incomplete exercises will remain in this session."
                } else null,
            ) { values ->
                change(workout) { current ->
                    val minutes = values[0].optionalInt()
                    require(minutes == null || minutes in 0..Int.MAX_VALUE / 60) { "Enter valid workout minutes." }
                    val durationChanged = minutes != current.durationSeconds?.div(60)
                    val started = if (finish) current.startedAt ?: Clock.System.now() else current.startedAt
                    val ended = if (finish) maxOf(requireNotNull(started), Clock.System.now()) else current.endedAt
                    val elapsed = if (started != null && ended != null) {
                        (ended - started).inWholeSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    } else null
                    current.copy(
                        date = LocalDate.parse(values[2].trim()), startedAt = started, endedAt = ended,
                        completed = current.completed || finish,
                        durationSeconds = if (durationChanged) minutes?.times(60) ?: elapsed else current.durationSeconds ?: elapsed,
                        durationManuallyEntered = if (durationChanged) minutes != null else current.durationManuallyEntered,
                        averageHeartRateBpm = values[1].optionalInt(),
                        caloriesKcal = values[3].optionalInt(), notes = values[4].trim().ifEmpty { null },
                    )
                }
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Workouts", style = MaterialTheme.typography.headlineMedium)
            Text("Stored on this device")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (session != null) {
            item {
                TextButton(onClick = { activeId = null }) { Text("Workout history") }
                Text("${session.date} · ${if (session.completed) "Completed" else "In progress"}")
                Text(if (busy) "Saving…" else if (error != null) "Changes need attention" else "Saved on this device")
                session.averageHeartRateBpm?.let { Text("Average heart rate $it BPM") }
                OutlinedButton(enabled = !busy, onClick = { editSummary(session, finish = false) }) {
                    Text("Edit workout details")
                }
                if (!session.completed) Button(enabled = !busy, onClick = { editSummary(session, finish = true) }) {
                    Text("Finish workout")
                }
                OutlinedButton(enabled = !busy && available.isNotEmpty(), onClick = {
                    editor = {
                        AlertDialog(onDismissRequest = { editor = null }, title = { Text("Add exercise") },
                            text = { LazyColumn(Modifier.heightIn(max = 300.dp)) { items(available) { exercise ->
                                TextButton(enabled = !busy, onClick = {
                                    change(session) { current ->
                                        current.copy(entries = current.entries + WorkoutExerciseEntry(
                                            newRecordMetadata(), current.metadata.id, exercise.metadata.id, current.entries.size))
                                    }
                                }) { Text(exercise.name) }
                            } } },
                            confirmButton = { TextButton(onClick = { editor = null }) { Text("Cancel") } })
                    }
                }) { Text("Add exercise entry") }
            }
            items(session.entries, key = { it.metadata.id.toString() }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(exercises.getValue(entry.exerciseId).name, style = MaterialTheme.typography.titleMedium)
                        entry.sets.forEach { set ->
                            TextButton(enabled = !busy, onClick = { editSet(session, entry, set) }) {
                                Text("Set ${set.number} · ${set.summary()}")
                            }
                        }
                        TextButton(enabled = !busy, onClick = { editSet(session, entry, null) }) { Text("Add set") }
                    }
                }
            }
        } else {
            val completed = data.sessions.filter { it.completed }
            val weekStart = today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
            val weekly = completed.filter { it.date in weekStart..today }
            item {
                Text("${weekly.size} completed this week · ${weekly.sumOf { it.durationSeconds?.toLong() ?: 0L }} seconds")
                Button(enabled = !busy, onClick = { start() }) { Text("Start workout") }
                Text("Workout history", style = MaterialTheme.typography.titleLarge)
            }
            items(data.sessions.sortedWith(compareByDescending<WorkoutSession> { it.date }.thenByDescending { it.startedAt }),
                key = { it.metadata.id.toString() }) { workout ->
                OutlinedButton(onClick = { activeId = workout.metadata.id.toString() }) {
                    Text("${workout.date} · ${if (workout.completed) "${workout.durationSeconds ?: 0} seconds" else "In progress"} · ${workout.entries.size} exercises")
                }
            }
            item {
                Text("Exercise library", style = MaterialTheme.typography.titleLarge)
                Button(enabled = !busy, onClick = { editExercise(null) }) { Text("New exercise") }
            }
            items(data.exercises.sortedBy { it.name }, key = { it.metadata.id.toString() }) { exercise ->
                Text(exercise.name + if (exercise.archived) " (archived)" else "")
                Text(listOfNotNull(exercise.category, exercise.equipment).joinToString(" · "))
                Row {
                    TextButton(enabled = !busy, onClick = { editExercise(exercise) }) { Text("Edit") }
                    TextButton(enabled = !busy, onClick = {
                        save { repository.saveExercise(exercise.copy(metadata = exercise.metadata.edit(), archived = !exercise.archived)) }
                    }) { Text(if (exercise.archived) "Restore" else "Archive") }
                    TextButton(onClick = {
                        editor = {
                            AlertDialog(onDismissRequest = { editor = null }, title = { Text(exercise.name) },
                                text = { LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                    val history = completed.sortedByDescending { it.date }.flatMap { workout ->
                                        workout.entries.filter { it.exerciseId == exercise.metadata.id }.flatMap { entry ->
                                            entry.sets.filter { it.completed }.map { "${workout.date} · ${it.summary()}" }
                                        }
                                    }
                                    if (history.isEmpty()) item { Text("No completed sets yet.") }
                                    items(history) { Text(it, Modifier.padding(vertical = 5.dp)) }
                                } }, confirmButton = { TextButton(onClick = { editor = null }) { Text("Close") } })
                        }
                    }) { Text("History") }
                }
            }
            item {
                Text("Routines", style = MaterialTheme.typography.titleLarge)
                Button(enabled = !busy && available.isNotEmpty(), onClick = { editRoutine(null) }) { Text("New routine") }
            }
            items(data.routines, key = { it.metadata.id.toString() }) { routine ->
                Text(routine.name)
                Text(routine.exerciseOrder.joinToString(" · ") { exercises.getValue(it).name })
                Row {
                    TextButton(enabled = !busy, onClick = { editRoutine(routine) }) { Text("Edit routine") }
                    TextButton(enabled = !busy && routine.exerciseOrder.none { exercises.getValue(it).archived },
                        onClick = { start(routine) }) { Text("Start routine") }
                }
            }
        }
    }
    editor?.invoke()
}

@Composable
private fun WorkoutFields(
    title: String,
    fields: List<Pair<String, String>>,
    busy: Boolean,
    close: () -> Unit,
    completed: Boolean? = null,
    error: String? = null,
    numericFields: Set<Int> = emptySet(),
    summary: String? = null,
    submit: (List<String>) -> Unit,
) {
    val values = remember(title) { mutableStateListOf(*fields.map { it.second }.toTypedArray()) }
    var checked by remember(title) { mutableStateOf(completed ?: false) }
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
            if (completed != null) item {
                Row { Checkbox(checked, { checked = it }); Text("Set completed", Modifier.padding(top = 12.dp)) }
            }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { submit(values.toList() + checked.toString()) }) { Text("Save") } },
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

private fun String.optionalInt() = trim().ifEmpty { null }?.toInt()
private fun RecordMetadata.edit() = revised(maxOf(updatedAt, Clock.System.now()))
private fun RecordedSet.summary() = listOfNotNull(
    weight?.let { "${it.value} ${it.unit}" }, repetitions?.let { "$it reps" },
    durationSeconds?.let { "${it}s active" }, restSeconds?.let { "${it}s rest" },
    if (completed) "Completed" else "Open",
).joinToString(" · ")
