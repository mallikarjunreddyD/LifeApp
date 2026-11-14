package com.myjournal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpenseScreen(
    repository: ExpenseRepository,
    today: LocalDate,
    syncControls: @Composable () -> Unit = {},
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    var ledger by remember { mutableStateOf<ExpenseLedger?>(null) }
    var screen by rememberSaveable { mutableStateOf("history") }
    var revealId by rememberSaveable { mutableStateOf<String?>(null) }
    val historyPosition = rememberLazyListState()
    var month by rememberSaveable { mutableStateOf(JournalMonth.from(today).toString()) }
    var monthInput by rememberSaveable { mutableStateOf(month) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var payment by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var deleted by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Expense?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repository) {
        try {
            repository.seed()
            repository.observe().collect { ledger = it }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            error = "Could not open expenses on this device."
        }
    }
    fun write(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                action()
                notice = "Saved on this device."
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message?.takeUnless { it == "Failed requirement." } ?: "Could not save. Check the expense details."
            } finally {
                busy = false
            }
        }
    }
    val data = ledger
    if (data == null) {
        Text(error ?: "Loading expenses…", Modifier.padding(20.dp))
        return
    }
    val labels = data.labels.associateBy { it.metadata.id }
    val active = data.expenses.filterNot { it.deleted }
        .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.metadata.createdAt }
            .thenBy { it.metadata.id.toString() })
    fun navigate(value: String) {
        error = null
        notice = null
        screen = value
    }
    backHandler(screen != "history" && !busy) { navigate("history") }
    if (screen != "history") {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(enabled = !busy, onClick = { navigate("history") }) { Text("Back to Expenses") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it) }
            if (screen == "analysis") {
                ExpenseAnalysis(data, month)
            } else if (screen == "CATEGORY" || screen == "PAYMENT") {
                ExpenseLabelManager(ExpenseLabelKind.valueOf(screen), data.labels, busy) { label, revision, complete ->
                    write {
                        repository.saveLabel(label, revision)
                        complete()
                    }
                }
            } else {
                val existing = active.find { it.metadata.id.toString() == screen }
                if (screen != "new" && existing == null) Text("This expense is unavailable.")
                else key(screen) {
                    ExpenseEditor(existing, today, data.labels, active, busy, create = { label, selected ->
                        write {
                            repository.saveLabel(label, null)
                            selected(label.metadata.id.toString())
                        }
                    }, save = { build, expected ->
                        write {
                            val expense = build()
                            repository.saveExpense(expense, expected)
                            month = JournalMonth.from(expense.date).toString()
                            monthInput = month
                            query = ""
                            category = ""
                            payment = ""
                            deleted = false
                            screen = "history"
                            revealId = expense.metadata.id.toString()
                        }
                    })
                }
            }
        }
        return
    }
    val period = active.filter { it.date.toString().startsWith("$month-") }
    val visible = if (deleted) data.expenses.filter { it.deleted }.sortedByDescending { it.date }
    else period.filter { (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) &&
        (category.isEmpty() || it.categoryId.toString() == category) &&
        (payment.isEmpty() || it.paymentMethodId.toString() == payment) }.take(10)

    LaunchedEffect(revealId, visible) {
        val id = revealId ?: return@LaunchedEffect
        val index = visible.indexOfFirst { it.metadata.id.toString() == id }
        if (index >= 0) {
            historyPosition.scrollToItem(if (index == 0) 0 else index + 1)
            revealId = null
        }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("expense-history"), state = historyPosition, contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Expenses", style = MaterialTheme.typography.headlineMedium)
                Button(enabled = !busy, onClick = { navigate("new") }) { Text("Add Expense") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it) }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(month)
                    Text(formatExpensePaise(period.sumOf { it.amountMinor }), style = MaterialTheme.typography.headlineMedium)
                    Text("${period.size} ${if (period.size == 1) "transaction" else "transactions"}")
                }
            }
            syncControls()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { filters = !filters }) { Text("Filter Expenses") }
                TextButton(onClick = { navigate("analysis") }) { Text("View Spending Analysis") }
                TextButton(onClick = { navigate("CATEGORY") }) { Text("Manage Categories") }
                TextButton(onClick = { navigate("PAYMENT") }) { Text("Manage Payment Methods") }
                TextButton(onClick = { deleted = !deleted }) { Text(if (deleted) "Show History" else "Recently Deleted") }
            }
            if (filters && !deleted) {
                OutlinedTextField(monthInput, { value ->
                    monthInput = value
                    runCatching { JournalMonth.parse(value) }.getOrNull()?.let { month = it.toString() }
                }, label = { Text("Month (YYYY-MM)") }, singleLine = true, isError = monthInput != month)
                OutlinedTextField(query, { query = it }, label = { Text("Search expense names") }, singleLine = true)
                LabelFilter("All categories", data.labels.filter { it.kind == ExpenseLabelKind.CATEGORY }, category) { category = it }
                LabelFilter("All payment methods", data.labels.filter { it.kind == ExpenseLabelKind.PAYMENT }, payment) { payment = it }
            }
            Text(if (deleted) "Recently Deleted" else "Recent Expenses", style = MaterialTheme.typography.titleLarge)
            if (visible.isEmpty()) Text(if (deleted) "No deleted expenses." else "No expenses match this period or filter.")
        }
        items(visible, key = { it.metadata.id.toString() }) { item ->
            var expanded by rememberSaveable { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val label = labels.getValue(item.categoryId)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label.icon.symbol, color = Color(label.color or 0xff000000.toInt()))
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(formatExpensePaise(item.amountMinor), style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${item.date} · ${labels.getValue(item.paymentMethodId).name}")
                    Text(label.name)
                    FlowRow {
                        if (item.remarks.isNotBlank()) TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Hide remarks" else "Show remarks")
                        }
                        if (item.deleted) TextButton(enabled = !busy, onClick = {
                            write { repository.setDeleted(item.metadata.id, false, item.metadata.revision) }
                        }) { Text("Restore Expense") }
                        else {
                            TextButton(enabled = !busy, onClick = { navigate(item.metadata.id.toString()) }) { Text("Edit") }
                            TextButton(enabled = !busy, onClick = { confirmation = item }) { Text("Delete") }
                        }
                    }
                    if (expanded) Text(item.remarks)
                }
            }
        }
    }
    confirmation?.let { expense ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Delete Expense?") },
            text = { Text("${expense.name} · ${formatExpensePaise(expense.amountMinor)} · ${expense.date}") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                write {
                    repository.setDeleted(expense.metadata.id, true, expense.metadata.revision)
                    confirmation = null
                }
            }) { Text("Confirm Delete") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}

@Composable
private fun LabelFilter(all: String, labels: List<ExpenseLabel>, selected: String, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(labels.find { it.metadata.id.toString() == selected }?.name ?: all) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(all) }, onClick = { select(""); open = false })
            labels.sortedBy { it.name }.forEach {
                DropdownMenuItem(text = { Text(it.name) }, onClick = { select(it.metadata.id.toString()); open = false })
            }
        }
    }
}

@Composable
private fun ExpenseEditor(
    expense: Expense?, today: LocalDate, labels: List<ExpenseLabel>, history: List<Expense>, busy: Boolean,
    create: (ExpenseLabel, (String) -> Unit) -> Unit, save: (() -> Expense, Long?) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf(expense?.let { expenseDecimal(it.amountMinor) }.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(expense?.name.orEmpty()) }
    var date by rememberSaveable { mutableStateOf((expense?.date ?: today).toString()) }
    var category by rememberSaveable { mutableStateOf(expense?.categoryId?.toString().orEmpty()) }
    var payment by rememberSaveable { mutableStateOf(expense?.paymentMethodId?.toString()
        ?: history.maxByOrNull { it.metadata.updatedAt }?.paymentMethodId?.takeIf { id ->
            labels.any { it.metadata.id == id && !it.archived }
        }?.toString().orEmpty()) }
    var remarks by rememberSaveable { mutableStateOf(expense?.remarks.orEmpty()) }
    val expected by rememberSaveable { mutableStateOf(expense?.metadata?.revision) }
    val recent = history.sortedByDescending { it.metadata.updatedAt }
    Text(if (expense == null) "Add Expense" else "Edit Expense", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(amount, { amount = it.take(20) }, Modifier.fillMaxWidth(), label = { Text("Amount (₹)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    OutlinedTextField(name, { name = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Expense name") }, singleLine = true)
    OutlinedTextField(date, { date = it.take(10) }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
    ExpenseLabelPicker("Category", ExpenseLabelKind.CATEGORY, labels, recent.map { it.categoryId }, category, busy,
        { category = it }, create)
    ExpenseLabelPicker("Paid From", ExpenseLabelKind.PAYMENT, labels, recent.map { it.paymentMethodId }, payment, busy,
        { payment = it }, create)
    OutlinedTextField(remarks, { remarks = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("Remarks (optional)") })
    Button(enabled = !busy && name.isNotBlank() && category.isNotEmpty() && payment.isNotEmpty(), onClick = {
        save({
            val day = runCatching { LocalDate.parse(date) }.getOrElse { error("Enter a valid date in YYYY-MM-DD format.") }
            Expense(expense?.metadata?.next() ?: newRecordMetadata(), day, name.trim(), parseExpensePaise(amount),
                Uuid.parse(category), Uuid.parse(payment), remarks.trim())
        }, expected)
    }) { Text("Save Expense") }
}

@Composable
private fun ExpenseLabelPicker(
    title: String, kind: ExpenseLabelKind, labels: List<ExpenseLabel>, recent: List<Uuid>, selected: String, busy: Boolean,
    select: (String) -> Unit, create: (ExpenseLabel, (String) -> Unit) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val options = labels.filter { it.kind == kind }
    val rank = recent.distinct().withIndex().associate { it.value to it.index }
    OutlinedButton(onClick = { searching = !searching }) {
        Text("$title · ${options.find { it.metadata.id.toString() == selected }?.name ?: "Choose"}")
    }
    if (searching) {
        OutlinedTextField(query, { query = it.take(120) }, label = { Text("Search $title") }, singleLine = true)
        options.filter { !it.archived && expenseNameKey(it.name).contains(expenseNameKey(query)) }
            .sortedWith(compareBy({ rank[it.metadata.id] ?: Int.MAX_VALUE }, { it.name })).take(6).forEach { label ->
                TextButton(onClick = { select(label.metadata.id.toString()); searching = false; query = "" }) { Text(label.name) }
            }
        if (query.isNotBlank() && options.none { expenseNameKey(it.name) == expenseNameKey(query) }) {
            TextButton(enabled = !busy, onClick = {
                create(ExpenseLabel(newRecordMetadata(), kind, query.trim()), {
                    select(it); searching = false; query = ""
                })
            }) { Text("Create \"${query.trim()}\"") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseLabelManager(kind: ExpenseLabelKind, all: List<ExpenseLabel>, busy: Boolean,
    save: (ExpenseLabel, Long?, () -> Unit) -> Unit) {
    val category = kind == ExpenseLabelKind.CATEGORY
    val labels = all.filter { it.kind == kind }.sortedBy { it.name }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = labels.find { it.metadata.id.toString() == editingId }
    var name by rememberSaveable { mutableStateOf("") }
    var icon by rememberSaveable { mutableStateOf(ExpenseIcon.OTHER) }
    var color by rememberSaveable { mutableIntStateOf(0x527062) }
    Text(if (category) "Manage Categories" else "Manage Payment Methods", style = MaterialTheme.typography.headlineMedium)
    if (labels.filterNot { it.archived }.groupBy { expenseNameKey(it.name) }.values.any { it.size > 1 }) {
        Text("Some names were created on different devices. Rename or archive one to distinguish them. Historical expenses keep their labels.")
    }
    OutlinedTextField(name, { name = it.take(120) }, label = { Text(if (editing == null) "New name" else "Edit name") }, singleLine = true)
    if (category) FlowRow {
        TextButton(onClick = { icon = ExpenseIcon.entries[(icon.ordinal + 1) % ExpenseIcon.entries.size] }) {
            Text("Change icon ${icon.symbol}")
        }
        val colors = listOf(0x527062, 0x97583b, 0x415f83, 0x795683, 0x79613d, 0x965761)
        TextButton(onClick = { color = colors[(colors.indexOf(color) + 1) % colors.size] }) {
            Text("Change color ●", color = Color(color or 0xff000000.toInt()))
        }
    }
    Button(enabled = !busy && name.isNotBlank(), onClick = {
        val old = editing
        save(ExpenseLabel(old?.metadata?.next() ?: newRecordMetadata(), kind, name.trim(), icon, color, old?.archived ?: false),
            revision) { editingId = null; revision = null; name = "" }
    }) { Text(if (editing == null) "Add" else "Save Changes") }
    if (editing != null) TextButton(onClick = { editingId = null; revision = null; name = "" }) { Text("New label") }
    labels.forEach { label ->
        Row {
            Text(label.name + if (label.archived) " (archived)" else "", Modifier.weight(1f))
            TextButton(onClick = { editingId = label.metadata.id.toString(); revision = label.metadata.revision
                name = label.name; icon = label.icon; color = label.color }) { Text("Edit") }
            TextButton(enabled = !busy, onClick = {
                save(label.copy(metadata = label.metadata.next(), archived = !label.archived), label.metadata.revision) {
                    if (editingId == label.metadata.id.toString()) { editingId = null; revision = null; name = "" }
                }
            }) { Text(if (label.archived) "Restore" else "Archive") }
        }
    }
}

private fun RecordMetadata.next() = revised(maxOf(Clock.System.now(), updatedAt))
