package com.myjournal.android

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class SettingsPage(val title: String) {
    ACCOUNT("Account & Security"),
    SYNC("Synchronization"),
    BACKUP("Backup & Recovery"),
    NOTIFICATIONS("Notifications"),
    APPEARANCE("Appearance"),
    ABOUT("About LifeApp"),
}

@Composable
internal fun SettingsScreen(operations: DataOperations, serverSync: AndroidServerSync) {
    val state by operations.state.collectAsState()
    val server by serverSync.state.collectAsState()
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    BackHandler(page != null && !state.busy) { page = null }
    var confirm by remember { mutableStateOf(false) }
    var recovery by rememberSaveable { mutableStateOf<String?>(null) }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { operations.export(it, "backup") }
    }
    val downloadData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { operations.downloadMyData(it, serverSync) }
    }
    val selectRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(operations::inspectRestore) }
    val selectRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri != null) { recovery=uri.toString();confirm=true }
    }
    key(page) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page != null) TextButton(enabled = !state.busy, onClick = { page = null }) { Text("Back to Settings") }
            Text(page?.title ?: "Settings", style = MaterialTheme.typography.headlineMedium)
            state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            when (page) {
                null -> {
                    Text(server.protectionSummary())
                    state.localBackupVerifiedAt?.let { Text("Last verified local backup: ${readableBackupTime(it)}") }
                    SettingsPage.entries.forEach { section ->
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = { page = section }) {
                            Text(section.title)
                        }
                    }
                }
                SettingsPage.ACCOUNT -> AndroidServerSettings(serverSync, operations, accountPage = true)
                SettingsPage.SYNC -> AndroidServerSettings(serverSync, operations, accountPage = false)
                SettingsPage.BACKUP -> {
                    state.localBackupVerifiedAt?.let { Text("Last verified local backup: ${readableBackupTime(it)}") }
                    Text("Local recovery files are unencrypted. Choose a private destination.")
                    Button(enabled = !state.busy, onClick = {
                        exportBackup.launch(exportName("LifeApp-Backup-${today()}", "zip"))
                    }) { Text("Back up now") }
                    OutlinedButton(enabled = !state.busy, onClick = {
                        selectRestore.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("Restore from backup") }
                    ServerBackupSummary(serverSync, server.username)
                    DriveBackupSettings(operations)
                    OutlinedButton(enabled = !state.busy, onClick = {
                        downloadData.launch(exportName("LifeApp-MyData", "zip"))
                    }) { Text("Download My Data") }
                    Text("Includes committed Android records and available readable copies. Store the export privately.")
                }
                SettingsPage.NOTIFICATIONS -> TodoReminderSettings()
                SettingsPage.APPEARANCE -> Text("LifeApp uses a light paper theme.")
                SettingsPage.ABOUT -> {
                    @Suppress("DEPRECATION")
                    val build = packageInfo.versionCode
                    Text("Version ${packageInfo.versionName} · build $build")
                    Text("Support: LifeApp repository on GitHub")
                    TextButton(onClick = { diagnostics = !diagnostics }) { Text("Advanced diagnostics") }
                    if (diagnostics) ServerDiagnostics(server)
                }
            }
        }
    }
    val preview=state.preview
    if(preview!=null && !state.busy && !confirm) AlertDialog(onDismissRequest=operations::dismissRestore,title={Text("Validated backup")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Created ${preview.backup.createdAt} · App ${preview.backup.appVersion}")
        preview.backup.counts.forEach { (name,count) -> Text("$name: $count") }
        Text(if(preview.replacesData) "REPLACE EXISTING DATA: all current local records will be replaced. First choose a different recovery backup file. It must be saved and verified before any replacement. No merge is performed." else "Restore these records into this clean database. Existing data is never merged.")
    }},confirmButton={TextButton(onClick={ if(preview.replacesData) selectRecovery.launch(exportName("LifeApp-BeforeRestore","zip")) else {recovery=null;confirm=true} }){
        Text(if(preview.replacesData) "Choose recovery file" else "Review restore confirmation")
    }},dismissButton={TextButton(onClick=operations::dismissRestore){Text("Cancel restore")}})
    if(confirm && preview!=null && !state.busy) AlertDialog(onDismissRequest={confirm=false},title={Text(if(preview.replacesData) "Replace existing data?" else "Restore backup?")},
        text={Text("This changes all local application records. Original IDs, revisions and editable vectors will be restored. ${if(preview.replacesData) "A verified recovery backup is mandatory before replacement." else "The database is currently empty."}")},
        confirmButton={TextButton(onClick={confirm=false;operations.restore(recovery?.let(Uri::parse))}){Text(if(preview.replacesData) "Replace existing data" else "Restore all data")}},
        dismissButton={TextButton(onClick={confirm=false;operations.dismissRestore()}){Text("Cancel restore")}})

}

internal fun readableBackupTime(value: String): String =
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(kotlin.time.Instant.parse(value).toEpochMilliseconds()))

internal fun AndroidServerState.protectionSummary() = when {
    online == false -> "Offline — changes saved on this device."
    working -> "Synchronizing…"
    conflicts.isNotEmpty() -> "To-do conflicts need review."
    pending + pendingImport > 0 -> "${pending + pendingImport} changes waiting to sync."
    username == null -> "Records are saved on this device. Sign in to sync."
    bound?.scope != accountScope -> "Review your connection to start syncing."
    error -> "Synchronization needs attention."
    lastSync != null -> "To-do checked with the server. Other modules have separate sync coverage."
    else -> "To-do synchronization is ready."
}

@Composable
internal fun ServerDiagnostics(server: AndroidServerState) {
    server.origin?.let { Text("Server origin: $it") }
    server.accountScope?.let {
        Text("Dataset ${it.dataset}")
        Text("Recovery epoch ${it.epoch}")
    }
    server.bound?.let { Text("Bound dataset ${it.scope.dataset} · applied commit ${it.sequence}") }
}

@Composable
private fun ServerBackupSummary(sync: AndroidServerSync, account: String?) {
    var result by remember(account) { mutableStateOf<Pair<String, String?>?>(null) }
    var unavailable by remember(account) { mutableStateOf(false) }
    LaunchedEffect(account) {
        if (account != null) {
            try { result = sync.verifiedBackupStatus() }
            catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
            catch (_: Exception) { unavailable = true }
        }
    }
    Text(when {
        account == null -> "Sign in to check server backups."
        unavailable -> "Server backup status is unavailable."
        result == null -> "Checking server backups…"
        result?.second != null -> "Last verified server Drive backup: ${readableBackupTime(requireNotNull(result?.second))}"
        else -> "No verified server Drive backup reported."
    })
    if (result?.first == "failed") Text("The latest server backup attempt needs attention.")
}
