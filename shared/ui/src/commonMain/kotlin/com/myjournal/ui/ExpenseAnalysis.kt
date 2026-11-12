package com.myjournal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.myjournal.domain.ExpenseLedger
import com.myjournal.domain.formatExpensePaise
import com.myjournal.domain.spending

@Composable
internal fun ExpenseAnalysis(ledger: ExpenseLedger, initialMonth: String) {
    var yearly by rememberSaveable { mutableStateOf(false) }
    var month by rememberSaveable { mutableStateOf(initialMonth) }
    var year by rememberSaveable { mutableStateOf(initialMonth.take(4)) }
    Text("Spending Analysis", style = MaterialTheme.typography.headlineMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!yearly, { yearly = false }, label = { Text("Monthly") })
        FilterChip(yearly, { yearly = true }, label = { Text("Yearly") })
    }
    val period = if (yearly) year else month
    val valid = if (yearly) Regex("[0-9]{4}").matches(period)
        else Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(period)
    val selectedYear = period.take(4).toIntOrNull()
    val invalid = !valid || selectedYear == null || selectedYear == 0
    OutlinedTextField(period, { if (yearly) year = it.take(4) else month = it.take(7) },
        label = { Text(if (yearly) "Year (YYYY)" else "Month (YYYY-MM)") }, singleLine = true, isError = invalid)
    if (invalid || selectedYear == null) return
    val totals = remember(ledger, period, yearly) {
        ledger.spending(selectedYear, if (yearly) null else period.takeLast(2).toInt())
    }
    Text("Total expenditure", style = MaterialTheme.typography.titleMedium)
    Text(formatExpensePaise(totals.amountMinor), style = MaterialTheme.typography.headlineMedium)
    if (totals.categories.isEmpty()) {
        Text("No expenses in this period.")
        return
    }
    Canvas(Modifier.size(200.dp)) {
        var start = -90f
        totals.categories.forEach { part ->
            val sweep = (360.0 * part.amountMinor / totals.amountMinor).toFloat()
            drawArc(Color(part.category.color or 0xff000000.toInt()), start, sweep, useCenter = true)
            start += sweep
        }
    }
    totals.categories.forEach { part ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("●", color = Color(part.category.color or 0xff000000.toInt()))
            Text(part.category.name, Modifier.weight(1f))
            Text(formatExpensePaise(part.amountMinor))
        }
    }
}
