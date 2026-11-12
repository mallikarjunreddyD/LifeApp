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
import com.myjournal.domain.*
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

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
    var format by rememberSaveable { mutableStateOf("backup") }
    var info by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var recovery by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfKind by rememberSaveable { mutableStateOf("journal") }
    var from by rememberSaveable { mutableStateOf(today().toString()) }
    var through by rememberSaveable { mutableStateOf(today().toString()) }
    var month by rememberSaveable { mutableStateOf(JournalMonth.from(today()).toString()) }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var pageId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { operations.export(it,format) } }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { operations.export(it,"json") } }
    val exportMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let { operations.export(it,"md") } }
    val selectRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(operations::inspectRestore) }
    val selectRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri != null) { recovery=uri.toString();confirm=true }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if(uri != null) operations.exportPdf(uri) { snapshot -> when(pdfKind) {
            "journal" -> snapshot.journalPdf(LocalDate.parse(from),LocalDate.parse(through))
            "planner" -> snapshot.plannerPdf(JournalMonth.parse(month))
            else -> snapshot.notebookPdf(Uuid.parse(requireNotNull(bookId)),pageId?.let(Uuid::parse))
        } }
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
                        format = "backup"
                        exportBackup.launch(exportName("LifeApp-Backup-${today()}", "zip"))
                    }) { Text("Back up now") }
                    OutlinedButton(enabled = !state.busy, onClick = {
                        selectRestore.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("Restore from backup") }
                    ServerBackupSummary(serverSync, server.username)
                    DriveBackupSettings(operations)
                    var formats by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { formats = !formats }) { Text(if (formats) "Hide export formats" else "Export data") }
                    if (formats) {
                        OutlinedButton(enabled = !state.busy, onClick = operations::preparePdf) { Text("Export PDF") }
                        OutlinedButton(enabled = !state.busy, onClick = { exportJson.launch(exportName("LifeApp-Records", "json")) }) { Text("Export structured JSON") }
                        OutlinedButton(enabled = !state.busy, onClick = {
                            format = "vectors"
                            exportBackup.launch(exportName("LifeApp-NativeVectors", "zip"))
                        }) { Text("Export native vectors") }
                        OutlinedButton(enabled = !state.busy, onClick = { exportMarkdown.launch(exportName("LifeApp-Metadata", "md")) }) { Text("Export Markdown metadata") }
                        TextButton(onClick = { info = true }) { Text("Backup and export format information") }
                    }
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
    if(info) AlertDialog(onDismissRequest={info=false},title={Text("File format information")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Full backup v1 is a ZIP with manifest.json, portable data.json and ink/UUID.ljpt files. It includes all modules and archived records. SHA-256 checksums, identities, ownership and point data are validated before restoration.")
        Text("JSON records v1 includes LJPT1 vectors as Base64. Native vector ZIP v1 stores the same records with separate LJPT1 files. Only full backup archives are accepted for restore.")
        Text("PDF uses portable Canvas paths and includes paper/calendar backgrounds. Pressure is approximated; its brush edge smoothing may differ from live Jetpack Ink. Original vectors stay editable.")
        Text("Limits: 64 MiB archive and expanded total, 8 MiB record/manifest JSON each, 50,000 records and 2,000,000 points. PDFs allow up to 500 pages and 128 MiB. Split PDF selections if needed. Backups are not encrypted or authenticated; use trusted files.")
        Text("Restore replaces all local records atomically. Existing data requires a verified recovery backup in a separately chosen file. Drive backups use the same validation and restore confirmation.")
    }},confirmButton={TextButton(onClick={info=false}){Text("Done")}})
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
    val snapshot=state.pdfSnapshot
    if(snapshot!=null && !state.busy && preview==null) AlertDialog(onDismissRequest=operations::closePdf,title={Text("PDF selection")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("journal" to "Daily Journal","planner" to "Monthly Planner","notebook" to "Notebook").forEach { (id,label) ->
                FilterChip(selected=pdfKind==id,onClick={pdfKind=id},label={Text(label)})
            }
        }
        when(pdfKind) {
            "journal" -> { OutlinedTextField(from,{from=it},label={Text("First date (YYYY-MM-DD)")},singleLine=true)
                OutlinedTextField(through,{through=it},label={Text("Last date (YYYY-MM-DD)")},singleLine=true)
                Text("Includes every saved page, including labelled archived pages, sorted by date and original page order.") }
            "planner" -> OutlinedTextField(month,{month=it},label={Text("Month (YYYY-MM)")},singleLine=true)
            else -> {
                if(snapshot.notebooks.isEmpty()) Text("No saved notebooks")
                snapshot.notebooks.forEach { book -> TextButton(onClick={bookId=book.metadata.id.toString();pageId=null}){
                    Text((if(bookId==book.metadata.id.toString()) "✓ " else "")+book.name+if(book.archived) " (archived)" else "")
                } }
                if(bookId!=null) {
                    TextButton(onClick={pageId=null}){Text(if(pageId==null) "✓ Entire notebook (including archived pages)" else "Entire notebook (including archived pages)")}
                    snapshot.notebookPages.filter{it.notebookId.toString()==bookId}.sortedBy{it.order}.forEach { page -> TextButton(onClick={pageId=page.metadata.id.toString()}){
                        Text((if(pageId==page.metadata.id.toString()) "✓ " else "")+(page.title ?: "Page ${page.order+1}")+if(page.archived) " (archived)" else "")
                    } }
                }
            }
        }
    }},confirmButton={
        val valid=runCatching { when(pdfKind) {
            "journal" -> snapshot.journalPdf(LocalDate.parse(from),LocalDate.parse(through)).isNotEmpty()
            "planner" -> snapshot.plannerPdf(JournalMonth.parse(month)).isNotEmpty()
            else -> bookId!=null && snapshot.notebookPdf(Uuid.parse(bookId!!),pageId?.let(Uuid::parse)).isNotEmpty()
        } }.getOrDefault(false)
        TextButton(enabled=valid,onClick={
            val prefix=when(pdfKind){"journal"->"DailyJournal-$from${if(from!=through) "-to-$through" else ""}";"planner"->"MonthlyPlanner-$month";else->"Notebook-${snapshot.notebooks.single{it.metadata.id.toString()==bookId}.name}"}
            pdf.launch(exportName(prefix,"pdf"))
        }){Text("Choose PDF destination")}
    },dismissButton={TextButton(onClick=operations::closePdf){Text("Cancel PDF")}})
}

internal fun readableBackupTime(value: String): String =
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(kotlin.time.Instant.parse(value).toEpochMilliseconds()))

internal fun AndroidServerState.protectionSummary() = when {
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
