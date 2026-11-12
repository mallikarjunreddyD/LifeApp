package com.myjournal.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myjournal.domain.*
import com.myjournal.ui.ExpenseScreen

@Composable
internal fun AndroidExpenses(app: JournalApplication) {
    val sync = app.expenseSync
    val state by sync.state.collectAsState(initial = null)
    val status by sync.status.collectAsState()
    val review by sync.review.collectAsState()
    val operation by app.operations.state.collectAsState()
    var showConflicts by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val current = review
        if (uri != null && current != null) app.operations.bindExpenses(uri, sync, current)
    }
    val conflicts = remember(state) { state?.effectiveHeads()?.values.orEmpty().filter { versions ->
        versions.map { it.record }.distinct().size > 1
    } }
    ExpenseScreen(app.expenses, today(), syncControls = {
        status?.let { Text(it) }
        operation.message?.takeIf { it.contains("expense", ignoreCase = true) }?.let { Text(it) }
        val connected = state
        if (connected == null) TextButton(onClick = sync::reviewConnection) { Text("Review Expense Connection") }
        else {
            if (connected.pending.isNotEmpty()) Text("${connected.pending.size} expense changes waiting.")
            TextButton(onClick = sync::schedule) { Text("Sync Expenses Now") }
            if (conflicts.isNotEmpty()) TextButton(onClick = { showConflicts = true }) { Text("Review Expense Conflicts") }
        }
    }) { enabled, back -> BackHandler(enabled, onBack = back) }
    review?.let { current ->
        AlertDialog(onDismissRequest = sync::dismissReview, title = { Text("Connect Expenses?") },
            text = { Text("${current.local.expenses.size} local expenses and ${current.serverCount} server expense records and labels. " +
                "A recovery backup is required. Independent records will be kept, and competing versions can be reviewed.") },
            confirmButton = { TextButton(onClick = { picker.launch(exportName("LifeApp-BeforeExpenseSync", "zip")) }) {
                Text("Back Up and Connect")
            } },
            dismissButton = { TextButton(onClick = sync::dismissReview) { Text("Cancel") } })
    }
    if (showConflicts) AlertDialog(onDismissRequest = { showConflicts = false }, title = { Text("Expense conflicts") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            for (versions in conflicts) {
                for (version in versions) {
                    val record = version.record
                    val description = when (record) {
                        is ExpenseRecord.Item -> "${record.value.name} · ${formatExpensePaise(record.value.amountMinor)} · ${record.value.date}" +
                            if (record.deleted) " · Deleted" else ""
                        is ExpenseRecord.Label -> record.value.name + if (record.value.archived) " · Archived" else ""
                    }
                    TextButton(onClick = {
                        sync.resolve(record.metadata.id, version.id, versions.map { it.id }.toSet())
                        showConflicts = false
                    }) { Text("Keep $description") }
                }
                HorizontalDivider()
            }
        } }, confirmButton = { TextButton(onClick = { showConflicts = false }) { Text("Done") } })
}
