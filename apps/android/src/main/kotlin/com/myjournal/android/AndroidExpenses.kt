package com.myjournal.android

import androidx.activity.compose.BackHandler
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
    var showConflicts by remember { mutableStateOf(false) }
    val conflicts = remember(state) { state?.effectiveHeads()?.values.orEmpty().filter { versions ->
        versions.map { it.record }.distinct().size > 1
    } }
    ExpenseScreen(app.expenses, today(), syncControls = {
        status?.let { Text(it) }
        val connected = state
        if (connected == null) Text("Connect under Settings → Synchronization.")
        else {
            if (connected.pending.isNotEmpty()) Text("${connected.pending.size} expense changes waiting.")
            if (conflicts.isNotEmpty()) TextButton(onClick = { showConflicts = true }) { Text("Review Expense Conflicts") }
        }
    }) { enabled, back -> BackHandler(enabled, onBack = back) }
    if (showConflicts) AlertDialog(onDismissRequest = { showConflicts = false }, title = { Text("Expense conflicts") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            val labels = remember(state) { state?.project()?.labels.orEmpty().associateBy { it.metadata.id } }
            for (versions in conflicts) {
                for (version in versions) {
                    val record = version.record
                    val description = when (record) {
                        is ExpenseRecord.Item -> with(record.value) {
                            listOf(name, formatExpensePaise(amountMinor), date.toString(),
                                labels[categoryId]?.name.orEmpty(), labels[paymentMethodId]?.name.orEmpty(),
                                remarks, if (deleted) "Deleted" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                        }
                        is ExpenseRecord.Label -> with(record.value) {
                            "$name · ${icon.symbol} · #${color.toString(16).padStart(6, '0')}" +
                                if (archived) " · Archived" else ""
                        }
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
