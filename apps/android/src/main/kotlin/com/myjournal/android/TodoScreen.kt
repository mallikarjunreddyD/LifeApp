package com.myjournal.android

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.myjournal.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

class TodoActions(private val scope: CoroutineScope, private val onCommitted: () -> Unit = {}) {
    data class State(val saving: Boolean = false, val error: String? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var failed: (suspend () -> Unit)? = null

    fun save(action: suspend () -> Unit) {
        if (mutable.value.saving || mutable.value.error != null) return
        mutable.value = State(saving = true)
        scope.launch {
            try {
                action()
                failed = null
                mutable.value = State()
                runCatching(onCommitted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = action
                mutable.value = State(error = "The task change could not be saved.")
            }
        }
    }

    fun retry() {
        val action = failed ?: return
        mutable.value = State()
        save(action)
    }

    fun dismiss() {
        if (mutable.value.saving) return
        failed = null
        mutable.value = State()
    }
}

@Composable
fun TodoScreen(repository: TodoRepository, actions: TodoActions) {
    val flow = remember(repository) { repository.observe() }
    val records by flow.collectAsState(initial = emptyList())
    val state by actions.state.collectAsState()
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var deadline by rememberSaveable { mutableStateOf(today().toString()) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = groupTodos(records, today())
    val canWrite = !state.saving && state.error == null

    fun edit(item: Todo?) {
        editId = item?.metadata?.id?.toString()
        title = item?.title.orEmpty()
        deadline = (item?.deadline ?: today()).toString()
        editing = true
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("To-do", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                Button(onClick = { edit(null) }, enabled = canWrite) { Text("Add task") }
            }
        }
        if (state.saving) item { Text("Saving task…") }
        state.error?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = actions::retry) { Text("Retry") }
                TextButton(onClick = actions::dismiss) { Text("Dismiss") }
            }
        } }
        if (records.isEmpty()) item { Text("No tasks yet.") }
        for ((group, label) in listOf(TodoGroup.OVERDUE to "Overdue", TodoGroup.TODAY to "Due today",
            TodoGroup.UPCOMING to "Upcoming", TodoGroup.COMPLETED to "Completed")) {
            val tasks = groups[group].orEmpty()
            if (tasks.isEmpty()) continue
            item { Text(label, style = MaterialTheme.typography.titleMedium) }
            items(tasks, key = { it.metadata.id.toString() }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(task.completedAt != null, enabled = canWrite, onCheckedChange = { checked ->
                            actions.save { repository.setCompleted(task.metadata.id, checked) }
                        }, modifier = Modifier.semantics {
                            contentDescription = if (task.completedAt == null) "Complete ${task.title}" else "Reopen ${task.title}"
                        })
                        Column(Modifier.weight(1f)) {
                            Text(task.title)
                            Text("Due ${task.deadline}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(onClick = { edit(task) }, enabled = canWrite) { Text("Edit") }
                        TextButton(onClick = { deleting = task.metadata.id.toString() }, enabled = canWrite,
                            modifier = Modifier.semantics { contentDescription = "Delete ${task.title}" }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(if (editId == null) "Add task" else "Edit task") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true,
                    isError = title.length > Todo.MAX_TITLE_LENGTH)
                TextButton(onClick = {
                    val initial = LocalDate.parse(deadline)
                    DatePickerDialog(context, { _, year, month, day ->
                        deadline = LocalDate(year, month + 1, day).toString()
                    }, initial.year, initial.month.ordinal, initial.day).show()
                }) { Text("Deadline: $deadline") }
            }
        },
        confirmButton = {
            TextButton(enabled = canWrite && title.trim().length in 1..Todo.MAX_TITLE_LENGTH, onClick = {
                val id = editId?.let(Uuid::parse)
                val name = title
                val date = LocalDate.parse(deadline)
                actions.save {
                    if (id == null) repository.add(name, date) else repository.edit(id, name, date)
                }
                editing = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
    )

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete task?") },
            text = { Text(records.firstOrNull { it.metadata.id.toString() == id }?.title.orEmpty()) },
            confirmButton = { TextButton(onClick = {
                actions.save { repository.delete(Uuid.parse(id)) }
                deleting = null
            }, enabled = canWrite) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
