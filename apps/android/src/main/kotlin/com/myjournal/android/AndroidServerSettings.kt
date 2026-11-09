package com.myjournal.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.myjournal.domain.TodoOperation

@Composable
internal fun AndroidServerSettings(sync: AndroidServerSync, operations: DataOperations) {
    val server = sync.state.collectAsState().value
    val backupState = operations.state.collectAsState().value
    var origin by rememberSaveable { mutableStateOf(server.origin.orEmpty()) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(server.origin) {
        if (origin.isBlank() || server.pinnedOrigin != null) origin = server.origin.orEmpty()
    }
    val selectBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val preview = server.preview
        if (uri != null && preview != null && preview.allowed) operations.bindServer(uri, sync, preview)
    }
    val enabled = !server.working && !backupState.busy
    Text("LifeApp Server", style = MaterialTheme.typography.titleLarge)
    Text("Server sync covers To-do. Journal, Planner, Habits, Notebook and editable ink stay local.")
    server.message?.let { Text(it, color = if (server.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
    OutlinedTextField(
        value = origin,
        onValueChange = { origin = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Server HTTPS URL") },
        singleLine = true,
        readOnly = server.pinnedOrigin != null,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedButton(enabled = enabled && origin.isNotBlank(), onClick = { sync.testConnection(origin) }) {
        Text("Test connection")
    }
    if (server.username == null) {
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Server username") },
            singleLine = true, enabled = enabled)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Server password") },
            singleLine = true, enabled = enabled, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button(enabled = enabled && origin.isNotBlank() && user.isNotBlank() && password.isNotEmpty(), onClick = {
            sync.signIn(origin, user, password)
            password = ""
        }) { Text("Sign in to LifeApp") }
    } else {
        Text("Signed in as ${server.username}")
        server.accountScope?.let { account ->
            Text("Dataset ${account.dataset}")
            Text("Recovery epoch ${account.epoch}")
        }
        if (server.bound == null) {
            OutlinedButton(enabled = enabled, onClick = sync::preview) { Text("Review first sync") }
        } else {
            Button(enabled = enabled && server.bound?.scope == server.accountScope, onClick = sync::syncNow) { Text("Sync To-do now") }
        }
        TextButton(enabled = enabled, onClick = sync::disconnect) { Text("Disconnect server") }
    }
    server.bound?.let { Text("Bound dataset ${it.scope.dataset} · applied commit ${it.sequence}") }
    Text("${server.pending} queued To-do operations")
    server.lastSync?.let { Text("Last successful To-do sync $it") }
    server.preview?.let { preview ->
        Text("Local task records ${preview.localCount}. Server task history ${if (preview.remoteHasHistory) "present" else "empty"}.")
        if (preview.allowed) Button(enabled = enabled, onClick = { confirm = true }) { Text("Back up and bind To-do") }
        else Text("Use an empty server dataset for the Android import, or a clean Android database to download server tasks. Both existing datasets are untouched.")
    }
    if (server.conflicts.isNotEmpty()) {
        Text("Task conflicts", style = MaterialTheme.typography.titleMedium)
        for (heads in server.conflicts) ConflictChoices(heads, enabled, sync)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Bind To-do to this server?") },
            text = { Text("Choose a separate backup file, such as in Downloads. LifeApp verifies the backup before binding. To-do operations can then be sent to this dataset.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    selectBackup.launch(exportName("LifeApp-BeforeServerSync", "zip"))
                }) { Text("Choose backup file") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConflictChoices(heads: List<TodoOperation>, enabled: Boolean, sync: AndroidServerSync) {
    val deleted = heads.any { it.record.deleted }
    val recordId = heads.first().record.metadata.id
    val ids = heads.map { it.id }.toSet()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (version in heads) {
                val task = version.record
                Text("${task.title} · ${task.deadline} · ${when {
                    task.deleted -> "Deleted"
                    task.completedAt != null -> "Completed"
                    else -> "Pending"
                }}")
                TextButton(enabled = enabled && (!deleted || task.deleted), onClick = {
                    sync.resolve(recordId, version.id, ids)
                }) { Text(if (task.deleted) "Keep deletion" else "Keep this version") }
            }
        }
    }
}
