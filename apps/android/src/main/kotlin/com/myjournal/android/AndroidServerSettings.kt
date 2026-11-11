package com.myjournal.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var secondFactor by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(server.origin) {
        if (origin.isBlank() || server.pinnedOrigin != null) origin = server.origin.orEmpty()
    }
    val selectBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val preview = server.preview
        if (uri != null && preview != null && preview.allowed) operations.bindServer(uri, sync, preview)
    }
    var journalTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val currentTarget = server.accountScope?.let { "${server.origin}/${it.dataset}/${it.epoch}" }
    val selectJournalBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val account = server.accountScope
        val serverOrigin = server.origin
        if (uri != null && account != null && serverOrigin != null && journalTarget == currentTarget) {
            operations.reviewJournal(uri, sync, serverOrigin, account)
        }
        journalTarget = null
    }
    val inventoryPreview = backupState.journalInventory
    LaunchedEffect(currentTarget, inventoryPreview, backupState.busy) {
        if (inventoryPreview != null && (inventoryPreview.scope != server.accountScope || inventoryPreview.origin != server.origin)) {
            operations.dismissJournalInventory()
        }
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
        OutlinedTextField(secondFactor, { secondFactor = it.take(43) }, Modifier.fillMaxWidth(),
            label = { Text("Authenticator or recovery code (if enabled)") }, singleLine = true, enabled = enabled,
            visualTransformation = PasswordVisualTransformation())
        Button(enabled = enabled && origin.isNotBlank() && user.isNotBlank() && password.isNotEmpty(), onClick = {
            sync.signIn(origin, user, password, secondFactor.trim().ifEmpty { null })
            secondFactor = ""
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
        OutlinedButton(enabled = enabled && currentTarget != null, onClick = {
            journalTarget = currentTarget
            selectJournalBackup.launch(exportName("LifeApp-BeforeJournalImport", "zip"))
        }) { Text("Back up and review journal inventory") }
        Text("Choose a local file for a separate backup, then compare journal dates. The review sends no journal records.")
        TextButton(enabled = enabled, onClick = {
            operations.dismissJournalInventory()
            sync.disconnect()
        }) { Text("Disconnect server") }
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
    if (!backupState.busy && inventoryPreview != null &&
        inventoryPreview.scope == server.accountScope && inventoryPreview.origin == server.origin) {
        val inventory = inventoryPreview.inventory
        AlertDialog(
            onDismissRequest = operations::dismissJournalInventory,
            title = { Text("Journal inventory") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("Backup contains ${inventory.localEntries} journal entries, ${inventory.localPages} pages and ${inventory.localStrokes} strokes.")
                    }
                    item { Text("Server has ${inventory.serverEntries} committed entries.") }
                    item {
                        Text("${inventory.localOnlyEntries} entries only in the backup · ${inventory.serverOnlyEntries} only on the server.")
                    }
                    if (inventory.overlappingDates.isNotEmpty()) {
                        item { Text("Dates present in both inventories", style = MaterialTheme.typography.titleSmall) }
                        items(inventory.overlappingDates) { Text(it.toString()) }
                    }
                    if (inventory.reusedIdentityDates.isNotEmpty()) {
                        item { Text("Dates affected by an entry or page ID used on different dates", style = MaterialTheme.typography.titleSmall) }
                        items(inventory.reusedIdentityDates) { Text(it.toString()) }
                    }
                    item { Text("This review compares entry and page identities. Ink comparison and import are still unavailable.") }
                }
            },
            confirmButton = { TextButton(onClick = operations::dismissJournalInventory) { Text("Done") } },
        )
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
